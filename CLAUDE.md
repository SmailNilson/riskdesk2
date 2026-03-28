# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

RiskDesk is a futures trading risk management dashboard with AI-powered signal review (Gemini "Mentor") and Interactive Brokers (IBKR) integration. Spring Boot 3.2 backend (Java 19, PostgreSQL 16) + Next.js 14 frontend (React 18, TypeScript).

## Commands

### Backend (Maven, from repo root)

```bash
mvn -q -DskipTests compile          # Compile only
mvn -q test                          # Run all tests
mvn -q -pl . -Dtest=SomeTest test    # Run a single test class
mvn -q spring-boot:run               # Start dev server (port 8080)
mvn -q -DskipTests package           # Build JAR
```

### Frontend (npm, from `frontend/`)

```bash
npm install        # Install deps
npm run dev        # Dev server (port 3000, proxies /api/ to :8080)
npm run build      # Production build
npm run lint       # ESLint
```

### Validation before committing

- Backend changes: `mvn -q -DskipTests compile` + relevant tests
- Frontend changes: `cd frontend && npm run lint`
- Both: run both checks

### Useful runtime checks

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/swagger-ui.html    # API docs
```

## Architecture

Strict DDD + Hexagonal Architecture with four layers. ArchUnit tests enforce boundaries.

### Layer structure (backend)

```
src/main/java/com/riskdesk/
├── presentation/    # REST controllers, WebSocket endpoints, DTOs (transport only)
├── application/     # Use-case orchestration, service coordination
├── domain/          # Business rules, aggregates, indicators, ports/interfaces
│   ├── alert/       # Transition-based alert evaluation
│   ├── engine/      # Technical indicators (EMA, RSI, MACD, Supertrend, VWAP, Chaikin, CMF)
│   │   ├── smc/     # Smart Money Concepts (BOS/CHoCH, Order Blocks)
│   │   └── backtest/
│   ├── model/       # Core domain models (Position, Candle, Instrument)
│   ├── trading/     # Trading aggregates and events
│   └── shared/      # Value objects, domain events
└── infrastructure/
    ├── marketdata/ibkr/   # IBKR native socket client, contract resolver, providers
    ├── persistence/       # JPA adapters and entities
    └── config/            # WebSocket, CORS, bootstrap wiring
```

### Dependency direction

`presentation -> application -> domain <- infrastructure`

- **domain** must never depend on Spring, JPA, HTTP, or IBKR implementation details
- **infrastructure** implements ports declared by domain/application
- **presentation** only adapts transport I/O to use cases

### Frontend structure

```
frontend/app/
├── components/     # Dashboard, Chart, MentorPanel, MentorSignalPanel, etc.
├── lib/api.ts      # Centralized API client
├── hooks/useWebSocket.ts  # STOMP WebSocket connection
└── page.tsx        # Root entry
```

## Non-negotiable rules

### Market data policy

Only path allowed: **IBKR Gateway -> PostgreSQL -> internal services**

Do not introduce Yahoo Finance, Polygon, Alpha Vantage, Binance, CSV imports, scraping, or simulated fallback feeds. PostgreSQL fallback is only acceptable when IBKR is temporarily unavailable.

### Alert evaluation

Must use **transition-based detection** (fires on condition *change*, not persistence). `IndicatorAlertEvaluator` in domain tracks last-known state per indicator/instrument/timeframe. Simultaneous alerts for the same instrument/timeframe/direction get batched into a single Mentor review via `captureGroupReview`.

### Mentor workflow

- Alert triggers a frozen payload snapshot saved to PostgreSQL immediately
- Clicking a review reads the saved thread (no fresh Gemini call)
- "Reanalyse" creates a new revision under the same thread using live market data
- Trade outcome simulation uses internal 1m candles from PostgreSQL only
- Simulation uses pessimistic execution (if SL and TP both crossed in one candle, result is LOSS)

## Testing

- **JUnit 5**: Unit tests for domain logic, integration tests for adapters
- **Cucumber BDD**: Feature files in `src/test/resources/features/` with steps in `src/test/java/com/riskdesk/bdd/`
- **ArchUnit**: Architecture compliance tests in `src/test/java/com/riskdesk/architecture/`
- TDD expected for business logic and bug fixes; BDD for user-visible workflows

## Key docs to read before editing

1. `AGENTS.md` - Operational rules
2. `docs/PROJECT_CONTEXT.md` - Core decisions and workflow details
3. `docs/ARCHITECTURE_PRINCIPLES.md` - Layer enforcement rules
4. `docs/AI_HANDOFF.md` - Recent changes and current operational state

Update `docs/AI_HANDOFF.md` when making significant architectural or workflow changes.

## Environment variables (must not be committed)

- `GEMINI_API_KEY`, `GEMINI_MODEL`, `GEMINI_EMBEDDING_MODEL` - Mentor AI
- `TELEGRAM_BOT_TOKEN`, `TELEGRAM_CHAT_ID` - Optional alert notifications

## WebSocket topics

- `/topic/prices` - Live price updates
- `/topic/alerts` - Risk and indicator alerts
- `/topic/mentor-alerts` - Mentor review updates
