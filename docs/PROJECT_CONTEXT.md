# Project Context

## What this project is

`riskdesk2` is a futures trading risk dashboard combining:

- portfolio and position monitoring
- indicator computation
- mentor-style analysis payload generation
- IBKR account and market data integration

The stack is split into a Spring Boot backend and a Next.js frontend.

## Architectural Standard

This project should be maintained as:

- DDD-oriented
- test-first where practical
- BDD-friendly for end-to-end business workflows
- hexagonal in backend structure

The target conceptual backend layers are:

- `presentation`
- `application`
- `domain`
- `infrastructure`

Supporting detail lives in:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/ARCHITECTURE_PRINCIPLES.md`

## Source of Truth for Market Data

The only accepted market data flow is:

`IBKR Gateway -> PostgreSQL -> internal services`

This rule is intentional and strict.

### Implications

- live prices should come from the IBKR native gateway when available
- historical candles should come from the IBKR/PostgreSQL pipeline
- PostgreSQL fallback is acceptable when IBKR is temporarily unavailable
- external providers must not be introduced as “temporary fixes”

## Main Runtime Config

See:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/resources/application.properties`

Important settings today:

- backend port: `8080`
- PostgreSQL DB: `riskdesk`
- IBKR mode: `IB_GATEWAY`
- native host: `127.0.0.1`
- native port: `4001`
- native client id: `7`

## Environment Variables Used

The application reads these from environment when present:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`
- `GEMINI_API_KEY`
- `GEMINI_MODEL`
- `GEMINI_EMBEDDING_MODEL`

These must stay out of Git.

## Backend Map

### Entry points

- `presentation/controller`
- `presentation/dto`

These should stay transport-oriented only.

### Core services

- `application/service/MarketDataService.java`
- `application/service/HistoricalDataService.java`
- `application/service/PositionService.java`
- `application/service/AlertService.java`
- `application/service/MentorAnalysisService.java`
- `application/service/MentorSignalReviewService.java`
- `application/service/MentorIntermarketService.java`

These coordinate use cases and should not become infrastructure adapters.

### IBKR integration

- `infrastructure/marketdata/ibkr/IbGatewayNativeClient.java`
- `infrastructure/marketdata/ibkr/IbGatewayContractResolver.java`
- `infrastructure/marketdata/ibkr/IbGatewayMarketDataProvider.java`
- `infrastructure/marketdata/ibkr/IbGatewayHistoricalProvider.java`
- `application/service/IbGatewayBrokerGateway.java`

### Persistence

- `infrastructure/persistence/*`

These are adapters, not business-rule owners.

## Frontend Map

### High-value files

- `frontend/app/page.tsx`
- `frontend/app/components/Dashboard.tsx`
- `frontend/app/components/MentorPanel.tsx`
- `frontend/app/components/MentorSignalPanel.tsx`
- `frontend/app/lib/api.ts`
- `frontend/app/hooks/useWebSocket.ts`

### Frontend responsibilities

- display live and fallback market data
- show mentor payload and result
- expose positions, indicators, alerts, and IBKR portfolio state
- auto-route selected trading alerts into a dedicated Mentor review panel

### Mentor Alert Workflow

The dashboard has a dedicated Mentor review panel under the IBKR connection panel. The bottom alerts ticker bar has been removed.

Only these alert families are escalated into Mentor review:

- structure: `BOS`, `CHoCH`
- momentum: `MACD Bullish/Bearish Cross`, `WaveTrend Bullish/Bearish Cross`
- extremes: `RSI oversold/overbought`, `WaveTrend oversold/overbought`
- structure/price: `VWAP inside BULLISH/BEARISH Order Block`

#### Alert evaluation

Alerts use transition-based detection: an alert fires only when a condition *changes* (e.g., RSI crosses into oversold), not when it persists across polling cycles. The `IndicatorAlertEvaluator` tracks last-known state per indicator/instrument/timeframe using a `ConcurrentHashMap`.

#### Grouped reviews

When multiple indicators fire simultaneously for the same instrument, timeframe, and direction (e.g., SMC + WAVETREND both signal LONG on MCL 10m), the backend batches them into a single combined Mentor review via `captureGroupReview`. Individual alerts are still published to WebSocket for the UI.

#### UI grouping

The `MentorSignalPanel` groups alerts by instrument + timeframe + direction within a 90-second time window. Groups show indicator category badges and a combined review count. An instrument filter dropdown allows filtering by specific instrument.

Current behavior:

- when a qualified alert is published, the backend captures a frozen Mentor payload snapshot immediately
- that first review is created from the frozen snapshot and saved in PostgreSQL
- the review thread is keyed by the exact alert occurrence (`timestamp + instrument + category + message`)
- clicking an alert in the Mentor review panel reads the saved thread only; it does not trigger a fresh Gemini call
- the button `Reanalyse` creates a new saved review revision under the same alert thread using LIVE market data at click time
- a re-review payload mixes live indicators with `original_alert_context` so Gemini can judge whether the old setup is still valid now
- raw alerts still flow through `/topic/alerts`
- Mentor review updates still flow through `/topic/mentor-alerts`
- manual `Ask Mentor` analyses are stored separately in `mentor_audits` with a dedicated `manual-mentor:` source reference and exposed through `/api/mentor/manual-reviews/recent`

### Mentor Outcome Tracker

Qualified alert reviews can now also be tracked after the fact as simulated trade outcomes.

Current behavior:

- when a saved `MentorSignalReview` finishes with a valid trade plan, the backend initializes a trade simulation state
- tracked fields live on the saved review:
  - `simulationStatus`
  - `activationTime`
  - `resolutionTime`
  - `maxDrawdownPoints`
- the simulation uses internal `1m` candles from PostgreSQL only
- the scheduler polls reviews in `PENDING_ENTRY` or `ACTIVE`
- trigger logic respects limit-order semantics:
  - if price reaches `TP` before `Entry`, the result becomes `MISSED`
  - once `Entry` is touched, the review becomes `ACTIVE`
  - if `SL` and `TP` are both crossed in one candle, the result is `LOSS` pessimistically
- terminal states are:
  - `WIN`
  - `LOSS`
  - `MISSED`
  - `CANCELLED`

### Execution Foundation

RiskDesk now has a dedicated execution persistence model for future live-order orchestration.

Current behavior:

- live execution state is no longer meant to be stored on `mentor_signal_reviews`
- a dedicated `trade_executions` table owns the future live execution lifecycle
- the idempotence key is the persisted Mentor review ID, not the alert thread key
- `TradeExecutionEntity` stores frozen review linkage, broker account, execution quantity, normalized entry price, virtual SL/TP placeholders, and execution timestamps
- Slice 1 does not place any IBKR order yet
- Slice 1 now exposes manual arming endpoints under `/api/mentor/executions`
- the `MentorSignalPanel` can create an execution foundation row for an eligible saved review once an IBKR account and quantity are selected
- execution lookup is batched by `mentorSignalReviewId` so the UI can display existing execution state without coupling it into the review aggregate
- Mentor reviews now carry an explicit `executionEligibilityStatus` and `executionEligibilityReason`
- backend execution creation must require:
  - review status `DONE`
  - explicit eligibility status `ELIGIBLE`
  - complete `Entry / SL / TP`
  - tick-normalized prices
- Slice 2 has now started:
  - `POST /api/mentor/executions/{executionId}/submit-entry` submits a simple IBKR limit entry order
  - submission is locked on the execution row before broker side effects
  - the IB Gateway adapter uses `orderRef = executionKey` and checks existing live/completed orders before re-submitting

## Operational Commands

### Compile backend

```bash
mvn -q -DskipTests compile
```

### Run backend

```bash
mvn -q spring-boot:run
```

or:

```bash
mvn -q -DskipTests package
java -jar target/riskdesk-0.1.0-SNAPSHOT.jar
```

### Frontend lint

```bash
cd frontend
npm run lint
```

### Useful API checks

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/live-price/E6
```

## Coding Conventions

### Backend

- preserve existing package boundaries
- avoid mixing controller logic into services
- prefer explicit DTOs over ad-hoc maps for API contracts
- treat IBKR timeouts and farm outages as first-class operational states
- preserve separation between `presentation`, `application`, `domain`, and `infrastructure`
- express external dependencies through ports/interfaces when possible
- keep Spring and persistence details out of the domain layer

### Testing approach

- use TDD for business logic and bug fixes where practical
- use unit tests for domain rules and application orchestration
- use integration tests for adapters and controllers
- use BDD scenarios when user workflows or acceptance behavior changes

### Frontend

- keep API logic in `frontend/app/lib/api.ts`
- keep dashboard logic in components, not in raw page-level glue
- when changing payloads, update backend and frontend in the same task

## Git Notes

- the repository root is now the project root
- `frontend/` is no longer a separate Git repository
- a backup of the old frontend `.git` was stored under `/tmp` during first import

## Where to Handoff Work

For agent-to-agent continuity, update:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/AI_HANDOFF.md`
