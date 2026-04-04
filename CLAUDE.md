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

- `domain/engine/indicators/` — EMA, RSI, MACD, Supertrend, VWAP, Bollinger Bands, WaveTrend
- `domain/engine/smc/` — Smart Money Concepts: Market Structure (BOS/CHoCH), Order Blocks
- `domain/engine/backtest/` — Backtesting engine using internal 1m candles only
- `domain/alert/` — **Transition-based** alert evaluation (fire on state *change*, not persistence)
- `domain/trading/` — Position, risk, and portfolio models
- `domain/analysis/` — AI/Mentor analysis models
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

## Alert System

- **Transition-based**: alerts fire only on state *change*, not persistence. `IndicatorAlertEvaluator` tracks last-known state per indicator/instrument/timeframe.
- **Grouped reviews**: when multiple indicators fire simultaneously for the same instrument/timeframe/direction, they produce one combined Mentor review via `captureGroupReview`.
- **Qualified alert families**: SMC (BOS/CHoCH), MACD cross, WaveTrend cross/extremes, RSI extremes, Order Block + VWAP.
- **Mentor reviews are snapshot-based**: first review uses a frozen payload at alert time. `Reanalyse` creates a new revision with live data + original context.

## Execution Workflow

- Live execution state lives in a dedicated `trade_executions` table — not on `MentorSignalReview`.
- Idempotence key: `mentorSignalReviewId`. One review → max one execution.
- Reviews carry `executionEligibilityStatus` / `executionEligibilityReason` — don't parse verdict strings.
- Slice 2: IBKR limit entry orders via `POST /api/mentor/executions/{id}/submit-entry`.

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
