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

### Backend (Maven, run from repo root)
```bash
mvn -q -DskipTests compile          # Fast compile check
mvn -q test                          # All tests
mvn -q -Dtest=MyTestClass test       # Single test class
mvn -q verify                        # Full CI (compile + test + JaCoCo)
mvn -q spring-boot:run -Dspring-boot.run.profiles=local  # Run locally
```

### Frontend (run from `frontend/`)
```bash
npm install
npm run lint                          # Required before commit
npm run build
npm run dev                           # Dev server on port 3001
```

### Validation before commit
- Backend changes: `mvn -q -DskipTests compile` + relevant tests
- Frontend changes: `cd frontend && npm run lint`
- Cross-stack changes: both

### Docker (local dev)
```bash
docker-compose up -d                  # PostgreSQL + services
docker-compose -f docker-compose.release.yml up -d  # Production images
```

## Architecture

RiskDesk is a futures trading risk dashboard. The backend is a Spring Boot 3.2 / Java 21 Maven project; `frontend/` is a Next.js 14 (React 18, TypeScript, Tailwind CSS) subdirectory.

### Hexagonal Layering (enforced by ArchUnit)

```
presentation/   → HTTP/WebSocket controllers and DTOs only — no business logic
application/    → Use-case orchestration (*Service.java)
domain/         → Pure business rules — NO Spring, NO JPA, NO IBKR SDK
infrastructure/ → Adapters implementing domain ports (IBKR, JPA, config)
```

Domain layer is completely isolated. Infrastructure implements `domain/port/` interfaces.

### Key Domain Packages

- `domain/engine/indicators/` — EMA, RSI, MACD, Supertrend, VWAP, Bollinger Bands, WaveTrend
- `domain/engine/smc/` — Smart Money Concepts: Market Structure (BOS/CHoCH), Order Blocks
- `domain/engine/backtest/` — Backtesting engine using internal 1m candles only
- `domain/alert/` — **Transition-based** alert evaluation (fire on state *change*, not persistence)
- `domain/behaviouralert/` — **Level/proximity-based** alert evaluation (EMA proximity, S/R touch, etc.)
- `domain/trading/` — Position, risk, and portfolio models
- `domain/analysis/` — AI/Mentor analysis models
- `domain/shared/TradingSessionResolver` — All timezone/session logic lives here

### Data Flow

```
IBKR IB Gateway (port 4001)
  → IbGatewayNativeClient (infrastructure/marketdata/ibkr)
  → MarketDataService / HistoricalDataService (application)
  → PostgreSQL
  → IndicatorAlertEvaluator (domain, transition-based)
  → BehaviourAlertEvaluator (domain, level/proximity-based)
  → MentorAnalysisService + Google Gemini API
  → WebSocket /topic/alerts, /topic/mentor-alerts
  → Next.js Frontend (STOMP over SockJS)
```

### Market Data — Critical Constraint

**Only source: IBKR Gateway → PostgreSQL.** Never add Yahoo Finance, Stooq, Alpha Vantage, Polygon, Binance, CSV imports, or any scraping. Backtest simulations use internal 1m candles from PostgreSQL only.

### Instruments

MCL (Micro WTI Crude), MGC (Micro Gold), 6E (Euro FX), MNQ (Micro E-mini Nasdaq-100). DXY is a synthetic index — see below.

### Synthetic DXY

`DXY` is an internal synthetic series computed from six IBKR FX quotes (`EURUSD`, `USDJPY`, `GBPUSD`, `USDCAD`, `USDSEK`, `USDCHF`), NOT the exchange-traded `DX` future. It is excluded from futures-only workflows (rollover, contract resolution, historical refresh). `CLIENT_PORTAL` mode does not serve DXY endpoints. Snapshots persist in `market_dxy_snapshots`.

### IBKR SDK

`tws-api` v10.39.1 is vendored in `vendor/maven-repo/` — not fetched from Maven Central. This is intentional for CI/Docker reproducibility.

## Alert System

### Transition-based evaluation (domain layer)

Alerts fire only when a condition *changes* (e.g., RSI crosses into oversold), not when it persists. `IndicatorAlertEvaluator` tracks last-known state per indicator/instrument/timeframe via `ConcurrentHashMap`. Do not revert to state-based evaluation.

### Grouped Mentor reviews (application layer)

When multiple indicators fire in the same polling cycle for the same instrument/timeframe/direction, `AlertService` batches them into a single Mentor review via `captureGroupReview`. Individual alerts are still published to WebSocket separately.

### Qualified alert families for Mentor review

- Structure: `BOS`, `CHoCH`
- Momentum: `MACD Bullish/Bearish Cross`, `WaveTrend Bullish/Bearish Cross`
- Extremes: `RSI oversold/overbought`, `WaveTrend oversold/overbought`
- Structure/price: `VWAP inside BULLISH/BEARISH Order Block`

### Mentor review threads

First review uses a frozen snapshot at alert time (not click-time market state). `Reanalyse` creates a new revision with live data + `original_alert_context`. Manual `Ask Mentor` analyses are stored separately in `mentor_audits`.

## Date, Time & Timezone Rules

CME trading day runs **17:00 ET to 17:00 ET** — not midnight UTC.

- **Storage is always UTC.** JPA entities use `Instant`, PostgreSQL columns use `TIMESTAMPTZ`.
- **Business projection is `America/New_York`.** Use `TradingSessionResolver` for all session boundary logic.
- **Never use `LocalDateTime` for persistence.** Use `Instant` or `ZonedDateTime`.
- **Never use `ZoneId.systemDefault()` or `LocalDate.now()` without explicit zone.**
- **VWAP resets at midnight ET** (not 17:00 ET) — consistent with TradingView/Bloomberg.
- **Intraday candles:** truncate to UTC epoch boundary. **Daily candles:** aggregate via `TradingSessionResolver.dailySessionStart()/End()`.
- **IBKR intraday bars:** prefer `bar.time()` (epoch seconds, UTC). String fallback `bar.timeStr()` is in `America/New_York`.

## Runtime Configuration

The `local` Spring profile is **required** when running locally. Override in `src/main/resources/application-local.properties`:

```properties
server.port=8090
riskdesk.ibkr.native-client-id=8
```

Key defaults (`application.properties`): PostgreSQL on `localhost:5432/riskdesk`, IBKR native socket on `127.0.0.1:4001`, market data poll every 3000ms.

Environment variables (must stay out of Git): `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID`, `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_EMBEDDING_MODEL`.

## Testing

- Unit + ArchUnit tests: `src/test/java/com/riskdesk/`
- BDD scenarios (Cucumber 7): `src/test/resources/features/*.feature` / `src/test/java/com/riskdesk/bdd/`
- Alert evaluation tests must verify **transition** (not steady-state) behavior
- Mentor review tests use frozen payloads — not live market context
- Time-sensitive changes must cover: normal case, session boundary (17:00 ET), DST spring/fall, weekend boundary, cross-midnight UTC

## Key Docs to Read

- `docs/AI_HANDOFF.md` — Latest engineering state, recent changes, known issues
- `docs/ARCHITECTURE_PRINCIPLES.md` — Layer constraints, date/time rules, alert rules
- `docs/PROJECT_CONTEXT.md` — Service map, environment variables, execution state machine
