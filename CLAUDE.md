# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Read AGENTS.md first — it contains absolute prohibitions and non-negotiable rules.

## Multi-Agent Rules

- Your working directories are auto-managed worktrees under `.claude/worktrees/`
- Always branch from `main` with prefix `claude/`
- Open a PR after each task — never merge another agent's branch
- Never force-add `.claude/`, `.codex*`, `.gemini/`, `.maq/` to git
- Before starting a task, check open PRs to avoid file conflicts with Codex or MAQ
- Your assigned server port is **8090**, IBKR client-id is **8**

## Build & Test Commands

### Backend (Maven, Java 21, run from repo root)
```bash
mvn -q -DskipTests compile          # Fast compile check
mvn -q test                          # All tests
mvn -q -Dtest=MyTestClass test       # Single test class
mvn -q -Dtest="MyTestClass#methodName" test  # Single test method
mvn -q verify                        # Full CI (compile + test + JaCoCo)
mvn -q spring-boot:run -Dspring-boot.run.profiles=local  # Run locally
```

### Frontend (Next.js 14, run from `frontend/`)
```bash
npm install
npm run lint                          # Required before commit
npm run build
npm run dev                           # Dev server on port 3001
```

### Docker (local dev)
```bash
docker-compose up -d                  # PostgreSQL + services
docker-compose -f docker-compose.release.yml up -d  # Production images
```

## Architecture

RiskDesk is a futures trading risk dashboard. Backend is a Spring Boot 3.2 / Java 21 Maven project; `frontend/` is a Next.js 14 / React 18 / TypeScript subdirectory.

### Hexagonal Layering (enforced by ArchUnit)

```
presentation/   → HTTP/WebSocket controllers and DTOs only — no business logic
application/    → Use-case orchestration (*Service.java)
domain/         → Pure business rules — NO Spring, NO JPA, NO IBKR SDK
infrastructure/ → Adapters implementing domain ports (IBKR, JPA, config)
```

Domain layer is completely isolated. Infrastructure implements `domain/port/` interfaces. Layer violations are caught by `HexagonalArchitectureTest`.

### Key Domain Packages

- `domain/engine/indicators/` — EMA, RSI, MACD, Supertrend, VWAP, Bollinger Bands, WaveTrend, CMF (Chaikin Money Flow), Stochastic (%K/%D), MarketRegimeDetector (TRENDING/RANGING/CHOPPY from EMA alignment + BB width)
- `domain/engine/smc/` — Smart Money Concepts: Market Structure (BOS/CHoCH), Order Blocks, SessionPdArrayCalculator (Premium/Discount/Equilibrium zones)
- `domain/engine/backtest/` — Backtesting engine using internal 1m candles only
- `domain/alert/` — **Transition-based** alert evaluation (fire on state *change*, not persistence)
- `domain/behaviouralert/` — CMF/Chaikin behaviour alerts (separate from technical indicator alerts)
- `domain/trading/` — Position, risk, and portfolio models
- `domain/analysis/` — AI/Mentor analysis models
- `domain/model/AssetClass` — METALS, ENERGY, FOREX, EQUITY_INDEX — per-class macro correlation kinds and sector leader mappings
- `domain/marketdata/port/TickDataPort` — Domain abstraction for real-time order flow (TickAggregation with Lee-Ready classification)
- `domain/shared/TradingSessionResolver` — All timezone/session logic lives here
- `domain/marketdata/model/SyntheticDollarIndexCalculator` — Synthetic DXY from 6 FX pairs

### Data Flow

```
IBKR IB Gateway (riskdesk-prod:4003 via Tailscale)
  → IbGatewayNativeClient (infrastructure/marketdata/ibkr)
  → MarketDataService / HistoricalDataService (application)
  → PostgreSQL
  → IndicatorAlertEvaluator (domain, transition-based)
  → MentorAnalysisService + Google Gemini API
  → WebSocket → Next.js Frontend (STOMP over SockJS)
```

### Domain Events

The domain layer publishes events consumed by application services:
- `CandleClosed` / `MarketPriceUpdated` — trigger indicator recalculation
- `AlertTriggered` — triggers mentor review capture
- `PositionPnLUpdated` — updates portfolio risk state

### WebSocket Topics

| Topic | Content |
|---|---|
| `/topic/prices` | Live market data snapshots |
| `/topic/alerts` | Individual indicator alerts |
| `/topic/mentor-alerts` | AI mentor review updates |
| `/topic/rollover` | Contract rollover events |

### Backend Service Map

| Service | Responsibility |
|---|---|
| `MarketDataService` | Live price polls, DXY synthesis, WebSocket publication |
| `HistoricalDataService` | Candle backfill and refresh coordination from IBKR (Phase 1 gap-fill vs Phase 2 deep backfill with throttling) |
| `SignalConfluenceBuffer` | Weighted signal buffering — accumulates alerts per (instrument, timeframe, direction) before flushing to Mentor |
| `PositionService` | Position P&L, exposure, risk calculations |
| `AlertService` | Indicator alert publishing + Mentor review batching by direction |
| `MentorSignalReviewService` | Persisted review snapshots, re-analysis revisions |
| `MentorIntermarketService` | Macro correlation context (DXY-backed, no external HTTP) |
| `DxyMarketService` | Synthetic DXY computation, persistence, REST + WebSocket publication |
| `ExecutionManagerService` | Trade execution lifecycle — arming, IBKR order submission, idempotence |
| `TradeSimulationService` | Post-trade outcome replay using internal 1m candles |
| `GeminiMentorClient` | Gemini API calls with dynamic per-asset-class system prompts |
| `IbGatewayNativeClient` | Native IB Gateway TCP connection (infra) |

### Frontend Component Map

| Component | Purpose |
|---|---|
| `Dashboard.tsx` | Main layout; composes all panels |
| `MentorPanel.tsx` | Live Mentor analysis (non-persisted) |
| `MentorSignalPanel.tsx` | Persisted reviews grouped by instrument/timeframe/direction (90s window) |
| `DxyPanel.tsx` | Synthetic DXY trend direction + 24h % change |
| `useWebSocket.ts` | STOMP over SockJS hook; subscribes to all `/topic/*` streams |
| `lib/api.ts` | REST API client |

### Market Data — Critical Constraint

**Only source: IBKR Gateway → PostgreSQL.** Never add Yahoo Finance, Stooq, Alpha Vantage, Polygon, Binance, CSV imports, or any scraping. Backtest simulations use internal 1m candles from PostgreSQL only.

### Instruments

MCL (Micro WTI Crude), MGC (Micro Gold), 6E (Euro FX), MNQ (Micro E-mini Nasdaq-100)

### Synthetic DXY

Computed internally from 6 IBKR FX quotes (EURUSD, USDJPY, GBPUSD, USDCAD, USDSEK, USDCHF). Not the exchange-traded `DX` future. Excluded from futures-specific workflows (rollover, contract resolution). Only available in `IB_GATEWAY` mode.

### Frontend Architecture

State management uses React hooks only (no Redux/Zustand). Key files:
- `frontend/app/components/Dashboard.tsx` — central orchestrator, owns instrument/timeframe state, polls portfolio (5s) and indicators (30s)
- `frontend/app/lib/api.ts` — all REST calls via native `fetch` (no axios), 30+ endpoints
- `frontend/app/hooks/useWebSocket.ts` — STOMP/SockJS client, subscribes to `/topic/{prices,alerts,mentor-alerts}`
- `frontend/app/hooks/useRollover.ts` — polls rollover status every 5 min
- `frontend/app/components/Chart.tsx` — TradingView `lightweight-charts` with SMC overlays (order blocks, liquidity, structure breaks)
- `frontend/app/components/MentorSignalPanel.tsx` — largest component (~47KB), AI signal review UI with execution arming

Styling: Tailwind CSS with dark (default) / light theme toggle. `output: 'standalone'` in `next.config.mjs` for Docker.

Frontend dev proxy: `next.config.mjs` rewrites `/api/*` to `RISKDESK_API_PROXY_TARGET` (defaults needed in `.env.example`).

### Database Schema

No Flyway/Liquibase — schema managed by Hibernate DDL auto (`spring.jpa.hibernate.ddl-auto=update`). Tests use H2 with `create-drop`.

### API Documentation

Swagger UI at `/swagger-ui.html`, OpenAPI spec at `/v3/api-docs`.

### IBKR SDK

`tws-api` v10.39.1 is vendored in `vendor/maven-repo/` — not fetched from Maven Central. This is intentional for CI/Docker reproducibility.

## Date, Time & Timezone Rules

These are critical — violating them causes subtle bugs across half the year.

- **Storage is always UTC.** JPA entities use `Instant`. PostgreSQL columns use `TIMESTAMPTZ`. Never `LocalDateTime` for persistence.
- **Business projection is `America/New_York`.** A CME "trading day" runs 17:00 ET → 17:00 ET next day — not midnight UTC.
- **Use `TradingSessionResolver`** for all session boundary logic (daily start/end, trading date, market session inference).
- **Never use** `ZoneId.systemDefault()`, `LocalDate.now()` without zone, `SimpleDateFormat`, or `TIMESTAMP` (without TZ) in DDL.
- **VWAP resets at midnight ET**, not 17:00 ET (matches TradingView/Bloomberg convention).
- **IBKR intraday bars**: prefer `bar.time()` (epoch seconds, UTC). String fallback `bar.timeStr()` is in `America/New_York` — parse accordingly.
- **DST-aware**: never hardcode UTC hour boundaries for session detection. Use `ZoneId.of("America/New_York")`.

Full rules in `docs/ARCHITECTURE_PRINCIPLES.md` § "Date, Time & Timezone Rules".

## Mentor IA v2 — Per-Asset-Class Payloads

The Mentor system uses Gemini with dynamic, per-asset-class system prompts and payloads:

- **AssetClass** (`domain/model/AssetClass`) determines macro correlation kinds and sector leaders (e.g., Silver leads METALS, VIX signals for EQUITY_INDEX)
- **Decision hierarchy** enforced in Gemini system prompt: Structure 50% > Order Flow 30% > Momentum 20%
- **Order flow** sourced via `TickDataPort` — real ticks when available (Lee-Ready classified), CLV estimation as fallback. Payload includes `source: "REAL_TICKS" | "CLV_ESTIMATED"` so Gemini knows data quality
- **Macro correlations** are per-asset-class (not universal). VIX, US10Y, Silver fields are currently null with `data_availability: "DXY_ONLY"` until IBKR subscriptions are added
- `reqTickByTickData()` is NOT yet wired in `IbGatewayNativeClient` — tick infrastructure is ready but subscription is a future slice

### Manual vs Auto Reviews

Two separate review paths exist:
- **Auto reviews**: triggered by qualified alerts → frozen payload snapshot at alert time → persisted in `mentor_signal_reviews` → exposed via `/api/mentor/auto-alerts/*`
- **Manual reviews** ("Ask Mentor"): triggered by user click → live payload at click time → persisted in `mentor_audits` with `manual-mentor:` source reference → exposed via `/api/mentor/manual-reviews/recent`

These paths use different persistence tables and endpoints. Do not merge them.

## Alert System

- **Transition-based**: alerts fire only on state *change*, not persistence. `IndicatorAlertEvaluator` tracks last-known state per indicator/instrument/timeframe.
- **Grouped reviews**: when multiple indicators fire simultaneously for the same instrument/timeframe/direction, they produce one combined Mentor review via `captureGroupReview`.
- **Qualified alert families**: SMC (BOS/CHoCH), MACD cross, WaveTrend cross/extremes, RSI extremes, Order Block + VWAP, Chaikin Behaviour (CMF), Stochastic cross/extremes.
- **Mentor reviews are snapshot-based**: first review uses a frozen payload at alert time. `Reanalyse` creates a new revision with live data + original context.

### Confluence Buffer

Qualified alerts route through `SignalConfluenceBuffer` before triggering Mentor reviews. Each alert carries a weight; the buffer accumulates signals per `(instrument, timeframe, direction)` key within a fixed time window.

- **Flush rules**: cumulative weight >= 3.0 → immediate flush; window expires with weight < 3.0 → no review (logged for backtest)
- **Non-cumul**: signals in the same family (e.g., EMA + MACD = Momentum) count only the max weight, not both
- **H1 bypass**: H1 alerts skip the confluence buffer and go directly to Mentor review (structural setups don't need confirmation)
- **5m kill zones**: 5m alerts only evaluate during ICT kill zones (London 02:00-05:00 ET, NY 08:30-11:00 ET)
- **Active timeframes**: 5m (kill zones only), 10m (always), 1H (bypass). 30m is removed. 4H is a passive trend filter for 1H signals
- Full spec: `docs/SPEC_CONFLUENCE_BUFFER.md`

## Trade Simulation (Outcome Tracker)

Qualified alert reviews with valid Entry/SL/TP plans are tracked as simulated trade outcomes:
- States: `PENDING_ENTRY` → `ACTIVE` → `WIN` | `LOSS` | `MISSED` | `CANCELLED`
- Uses internal 5m candles from PostgreSQL only (no external replay feeds)
- Pessimistic rule: if one candle crosses both SL and TP, result is `LOSS`
- If TP is hit before Entry, result is `MISSED`
- `maxDrawdownPoints` records worst adverse excursion before resolution
- Scheduled backend service (`TradeSimulationService`) polls reviews in `PENDING_ENTRY` or `ACTIVE`

### Trailing Stop (Dual-Track)

- Runs in parallel with fixed SL/TP — both results are persisted on the same review
- `trailingStopResult`: `TRAILING_WIN` | `TRAILING_BE` | `TRAILING_LOSS` | null
- `trailingExitPrice`: dynamic exit price from trailing stop
- `bestFavorablePrice`: Maximum Favorable Excursion (MFE) — best price reached before exit
- ATR-based trailing: `bestPrice - (ATR * multiplier)` for LONG, `bestPrice + (ATR * multiplier)` for SHORT
- Activation gate: trailing only engages after +0.5R favorable move (configurable)
- Config: `riskdesk.simulation.trailing-stop.{enabled,multiplier,activation-threshold}`
- Stats endpoint: `GET /api/mentor/simulation/trailing-stats?days=7`

### Simulation Decoupling Rule (TECH DEBT)

**Simulation fields currently live on `MentorSignalReviewRecord` and `MentorAudit` — this is legacy coupling.** See `docs/ARCHITECTURE_PRINCIPLES.md` § "Simulation Decoupling Rule" for the full migration plan.

**Immediate rules for all agents:**
1. Do NOT add new simulation fields to `MentorSignalReviewRecord` or `MentorAudit`
2. Do NOT extend `findBySimulationStatuses()` on the review repository port
3. Do NOT push new simulation events through `/topic/mentor-alerts`
4. New simulation concerns go on the simulation side, not the review side
5. `TradeSimulationService` is the sole writer of simulation state after initial `PENDING_ENTRY`

## Execution Workflow

- Live execution state lives in a dedicated `trade_executions` table — not on `MentorSignalReview`.
- Idempotence key: `mentorSignalReviewId`. One review → max one execution.
- Reviews carry `executionEligibilityStatus` / `executionEligibilityReason` — don't parse verdict strings.
- Slice 2: IBKR limit entry orders via `POST /api/mentor/executions/{id}/submit-entry`.
- Execution row is locked before broker side effects to prevent duplicate orders.
- Full fill/re-sync/virtual exit orchestration does not exist yet.

## Runtime Configuration

The `local` Spring profile is **required** when running locally. Override in `src/main/resources/application-local.properties`:

```properties
server.port=8090
riskdesk.ibkr.native-client-id=8
riskdesk.ibkr.native-host=100.113.139.64
riskdesk.ibkr.native-port=4003
riskdesk.mentor.api-key=${GEMINI_API_KEY}
riskdesk.mentor.model=gemini-3.1-pro-preview
riskdesk.mentor.embeddings-model=gemini-embedding-001
```

**IBKR Gateway runs on `riskdesk-prod` (`100.113.139.64:4003`) via Tailscale** — NOT on localhost. Always verify Tailscale is active: `tailscale status | grep riskdesk-prod`.

**Gemini API Key** must be in `application-local.properties` as `riskdesk.mentor.api-key=<key>`. The `.env.local` at repo root has the key but Spring does NOT auto-load it. Without this, Mentor IA calls will fail silently.

Key defaults (`application.properties`): PostgreSQL on `localhost:5432/riskdesk`, market data poll every 3000ms.

Environment variables (must stay out of Git): `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_EMBEDDING_MODEL`, `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`.

## Validation Expectations

- Backend-only change: `mvn -q -DskipTests compile` + run affected test class
- Frontend-only change: `cd frontend && npm run lint`
- Cross-stack change: run both

## Documentation Updates

When making significant architectural or workflow changes, update these three docs:
- `docs/AI_HANDOFF.md` — What changed and why (for incoming agents)
- `docs/ARCHITECTURE_PRINCIPLES.md` — If rules or constraints changed
- `docs/PROJECT_CONTEXT.md` — If service map, execution state machine, or environment changed

## Container Release

Images are built and validated in GitHub Actions on every push to `main`. Published to `ghcr.io/smailnilson/riskdesk2` on Git tag push via `docker-compose.release.yml`. The `tws-api` JAR is vendored in `vendor/maven-repo/` so Docker/CI never needs `~/.m2`.

## Testing

- Unit + ArchUnit tests: `src/test/java/com/riskdesk/`
- ArchUnit layer enforcement: `src/test/java/com/riskdesk/architecture/HexagonalArchitectureTest.java`
- BDD scenarios (Cucumber 7): `src/test/resources/features/*.feature`
- Alert evaluation tests must verify **transition** (not steady-state) behavior
- Mentor review tests use frozen payloads — not live market context
- Time-sensitive tests must cover: normal case, session boundary (17:00 ET), DST spring/fall, weekend boundary, cross-midnight UTC

## CI/CD

GitHub Actions builds and validates Docker images on push to `main` and PRs. Tag pushes publish to `ghcr.io/smailnilson/riskdesk2`. Deployment runs over SSH via `docker-compose.release.yml`. Secrets: `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_PATH`, `GHCR_USERNAME`, `GHCR_TOKEN`.

## Key Docs to Read

- `docs/AI_HANDOFF.md` — Latest engineering state, recent changes, known issues
- `docs/ARCHITECTURE_PRINCIPLES.md` — Layer constraints, date/time rules, alert rules
- `docs/PROJECT_CONTEXT.md` — Service map, environment variables, execution state machine
- `docs/SPEC_CONFLUENCE_BUFFER.md` — Signal buffering weights, flush rules, timeframe windows
