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

### Synthetic Dollar Index

`DXY` is no longer sourced from the `DX` future on `ICEUS`.

Current behavior:

- `DXY` is an internal synthetic market series computed from six IBKR FX quotes on `IDEALPRO`
- the pricing inputs are `EURUSD`, `USDJPY`, `GBPUSD`, `USDCAD`, `USDSEK`, and `USDCHF`
- live computation prefers `mid=(bid+ask)/2`, then falls back to `last` when bid/ask are incomplete
- only complete, valid, time-coherent snapshots are persisted
- persisted snapshots live in `market_dxy_snapshots`
- historical DXY reads now come from that dedicated snapshot table, not from the generic `candles` table
- `DXY` remains a business instrument symbol but must be excluded from futures contract rollover, active-contract fallback, and IBKR futures contract resolution
- `CLIENT_PORTAL` mode does not support synthetic DXY; the dedicated DXY endpoints are intentionally unavailable there

## Main Runtime Config

See:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/resources/application.properties`

Important settings today:

- backend port: `8080`
- PostgreSQL DB: `riskdesk`
- IBKR mode: `IB_GATEWAY`
- native host: `100.113.139.64` (riskdesk-prod via Tailscale — NOT localhost)
- native port: `4003`
- native client id: `7`

Note: IB Gateway runs on the `riskdesk-prod` server, reachable via Tailscale at `100.113.139.64:4003`. Verify with `tailscale status | grep riskdesk-prod`.

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
- `application/service/TradeSimulationService.java` — sole owner of simulation state transitions. Post Phase 3 it reads open simulations via `TradeSimulationRepositoryPort.findByStatuses(...)` and writes exclusively to the simulation aggregate. No legacy sim write path remains.
- `application/service/ExecutionManagerService.java` — arming + entry submission for live executions.
- `application/service/ExecutionFillTrackingService.java` — Slice 3a. Implements `ExecutionFillListener` domain port. Receives IBKR `execDetails` + `orderStatus` callbacks from `IbGatewayNativeClient`, deduplicates by `execId`, persists raw broker feedback on `TradeExecutionEntity`, transitions domain state to `ACTIVE` on first `Filled`, publishes `/topic/executions` on every state-changing update.
- `application/quant/service/QuantGateService.java` — runs the 7-gate SHORT-setup evaluator every 60 s (MNQ, MGC, MCL). Pure orchestration: parallel port fetch (Absorption, Distribution, Cycle, Delta, LivePrice) → `GateEvaluator.evaluate()` → state save → WebSocket publish. State persists in the `quant_state` table; recent snapshots in an in-memory ring buffer (`QuantSnapshotHistoryStore`).

These coordinate use cases and should not become infrastructure adapters.

### Simulation decoupling (Phase 3 — single writer, simulation aggregate)

- Domain port: `domain/simulation/port/TradeSimulationRepositoryPort`
- Aggregate: `domain/simulation/TradeSimulation` (+ `ReviewType` enum)
- REST controller: `presentation/controller/SimulationController` — `GET /api/simulations/{recent,by-instrument/{instrument},by-review/{reviewId}}`
- WebSocket topic: `/topic/simulations` (sole channel for simulation events; `/topic/mentor-alerts` no longer carries simulation transitions)
- Initial `PENDING_ENTRY` is created directly on `trade_simulations` by `MentorSignalReviewService.initializeSimulationAggregate()` (auto reviews) and `MentorAnalysisService.initializeAuditSimulation()` (manual "Ask Mentor" audits).

**Legacy sim getters on the review/audit records are `@Deprecated(since = "phase-3")`** — write-never from production, read-only for JPA round-trip until the physical columns are dropped. Column drop is a separate follow-up PR that requires adopting Flyway or Liquibase first. See `docs/ARCHITECTURE_PRINCIPLES.md` § Simulation Decoupling Rule.

### IBKR integration

- `infrastructure/marketdata/ibkr/IbGatewayNativeClient.java`
- `infrastructure/marketdata/ibkr/IbGatewayContractResolver.java`
- `infrastructure/marketdata/ibkr/IbGatewayMarketDataProvider.java`
- `infrastructure/marketdata/ibkr/IbGatewayHistoricalProvider.java`
- `infrastructure/marketdata/ibkr/IbGatewayFxContractResolver.java`
- `infrastructure/marketdata/ibkr/IbGatewayFxQuoteProvider.java`
- `application/service/IbGatewayBrokerGateway.java`

### Persistence

- `infrastructure/persistence/*`

These are adapters, not business-rule owners.

Relevant DXY persistence now includes:

- `infrastructure/persistence/entity/MarketDxySnapshotEntity.java`
- `infrastructure/persistence/JpaDxySnapshotRepositoryAdapter.java`

Relevant simulation persistence (Phase 1 — new `trade_simulations` table running alongside the legacy simulation columns on `mentor_signal_reviews` / `mentor_audits`):

- `infrastructure/persistence/entity/TradeSimulationEntity.java`
- `infrastructure/persistence/JpaTradeSimulationRepository.java`
- `infrastructure/persistence/JpaTradeSimulationRepositoryAdapter.java`
- `infrastructure/persistence/TradeSimulationEntityMapper.java`

Schema of `trade_simulations` (unique constraint `(review_id, review_type)`):
`id`, `review_id`, `review_type` (`SIGNAL`|`AUDIT`), `instrument`, `action`, `simulation_status`, `activation_time`, `resolution_time`, `max_drawdown_points`, `trailing_stop_result`, `trailing_exit_price`, `best_favorable_price`, `created_at`.

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
- display the synthetic DXY latest snapshot and 24h persisted history on the dashboard

### Mentor Alert Workflow

The dashboard has a dedicated Mentor review panel under the IBKR connection panel. The bottom alerts ticker bar has been removed.

Only these alert families are escalated into Mentor review:

- structure: `BOS`, `CHoCH`
- momentum: `MACD Bullish/Bearish Cross`, `WaveTrend Bullish/Bearish Cross`
- extremes: `RSI oversold/overbought`, `WaveTrend oversold/overbought`
- structure/price: `VWAP inside BULLISH/BEARISH Order Block`

#### Alert evaluation

Alerts use transition-based detection: an alert fires only when a condition *changes* (e.g., RSI crosses into oversold), not when it persists across polling cycles. The `IndicatorAlertEvaluator` tracks last-known state per indicator/instrument/timeframe using a `ConcurrentHashMap`.

`AlertService` still deduplicates by alert key on a short shared cooldown, but it no longer blocks an entire timeframe window (`10m` or `1h`). This means multiple alerts can be emitted on the same timeframe when distinct transitions happen before the bar period ends.

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
- simulation state transitions are additionally published on `/topic/simulations` (Phase 1 dual-publish; `/topic/mentor-alerts` continues to carry the same events until Phase 2 frontend cutover)
- live execution fill/status updates (from IBKR `execDetails` + `orderStatus`) are published on `/topic/executions` — payload is `TradeExecutionView` including the new fill fields (`filledQuantity`, `avgFillPrice`, `lastFillTime`, `orderStatus`, `ibkrOrderId`)
- manual `Ask Mentor` analyses are stored separately in `mentor_audits` with a dedicated `manual-mentor:` source reference and exposed through `/api/mentor/manual-reviews/recent`

### Mentor Outcome Tracker

Qualified alert reviews can now also be tracked after the fact as simulated trade outcomes.

Current behavior:

- when a saved `MentorSignalReview` finishes with a valid trade plan, the backend writes a `TradeSimulation(PENDING_ENTRY, reviewType=SIGNAL)` row into `trade_simulations` (Phase 3+). Manual "Ask Mentor" audits follow the same path with `reviewType=AUDIT`.
- tracked fields live on the `TradeSimulation` aggregate:
  - `simulationStatus`
  - `activationTime`
  - `resolutionTime`
  - `maxDrawdownPoints`
  - `trailingStopResult`, `trailingExitPrice`, `bestFavorablePrice`
- identical getters still exist on `MentorSignalReviewRecord` / `MentorAudit` but are `@Deprecated(since = "phase-3")` — write-never from production; used only by the JPA mappers for backwards compatibility until the physical columns are dropped in a follow-up PR.
- the simulation uses internal `5m` candles from PostgreSQL only
- the scheduler polls `trade_simulations` in `PENDING_ENTRY` or `ACTIVE` via `TradeSimulationRepositoryPort.findByStatuses(...)`
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
- Slice 3a is now live — IBKR `execDetails` + `orderStatus` fill tracking:
  - `TradeExecutionEntity` carries raw IBKR feedback: `filledQuantity`, `avgFillPrice`, `lastFillTime`, `orderStatus`, `ibkrOrderId`, `lastExecId` (per-fill idempotence key)
  - `ExecutionFillTrackingService` (application) implements the `ExecutionFillListener` domain port and is invoked from `IbGatewayNativeClient` on every IBKR callback
  - transition from `ENTRY_SUBMITTED` to domain state `ACTIVE` happens on first IBKR `Filled` status; cancellation without any fill transitions to `CANCELLED`; partial-fill-then-cancel is deferred to Slice 3c
  - WebSocket topic `/topic/executions` carries every state-changing update
- Slice 3b (next) will add startup reconciliation: query IBKR open/completed orders and reconcile against dangling `trade_executions` rows from prior runs.
- Slice 3c (future) will add bracket orders — submit SL + TP once entry fills, monitor and close.

## Operational Commands

### Compile backend

```bash
mvn -q -DskipTests compile
```

## Container Release Workflow

- Docker image validation now runs in GitHub Actions on `push` to `main` and on pull requests targeting `main`
- Docker image publication now runs in GitHub Actions on Git tag pushes
- published images are pushed to `ghcr.io/smailnilson/riskdesk2`
- if a tag already exists before the workflow is added, rerun publication with the manual `workflow_dispatch` input `git_tag`
- release deployment can now run from GitHub Actions over SSH using `docker-compose.release.yml` on the target server
- the deployment workflow expects GitHub Actions secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_PATH`, `GHCR_USERNAME`, and `GHCR_TOKEN`
- local Docker is no longer required for the standard image release path
- the private IBKR `tws-api` dependency is vendored under `vendor/maven-repo` so Docker/CI builds do not depend on a developer-local `~/.m2`

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
