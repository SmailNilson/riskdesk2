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

### Docker (local dev)
```bash
docker-compose up -d                  # PostgreSQL + services
docker-compose -f docker-compose.release.yml up -d  # Production images
```

## Architecture

RiskDesk is a futures trading risk dashboard. The backend root is the Maven project; `frontend/` is a Next.js 14 subdirectory.

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
  → MentorAnalysisService + Google Gemini API
  → WebSocket /topic/alerts, /topic/mentor-alerts
  → Next.js Frontend (STOMP over SockJS)
```

### Market Data — Critical Constraint

**Only source: IBKR Gateway → PostgreSQL.** Never add Yahoo Finance, Stooq, Alpha Vantage, Polygon, Binance, CSV imports, or any scraping. Backtest simulations use internal 1m candles from PostgreSQL only.

### Instruments

MCL (Micro WTI Crude), MGC (Micro Gold), 6E (Euro FX), MNQ (Micro E-mini Nasdaq-100)

### IBKR SDK

`tws-api` v10.39.1 is vendored in `vendor/maven-repo/` — not fetched from Maven Central. This is intentional for CI/Docker reproducibility.

## Runtime Configuration

The `local` Spring profile is **required** when running locally. Override in `src/main/resources/application-local.properties`:

```properties
server.port=8090
riskdesk.ibkr.native-client-id=8
```

Key defaults (`application.properties`): PostgreSQL on `localhost:5432/riskdesk`, IBKR native socket on `127.0.0.1:4001`, market data poll every 3000ms.

## Testing

- Unit + ArchUnit tests: `src/test/java/com/riskdesk/`
- BDD scenarios (Cucumber 7): `src/test/resources/features/*.feature`
- Alert evaluation tests must verify **transition** (not steady-state) behavior
- Mentor review tests use frozen payloads — not live market context

## Key Docs to Read

- `docs/AI_HANDOFF.md` — Latest engineering state, recent changes, known issues
- `docs/ARCHITECTURE_PRINCIPLES.md` — Layer constraints, date/time rules, alert rules
- `docs/PROJECT_CONTEXT.md` — Service map, environment variables, execution state machine
