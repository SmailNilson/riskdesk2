# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Read AGENTS.md first — it contains the full project rules.

## Multi-Agent Rules (summary for Claude Code)

- Your working directories are the auto-managed worktrees under `.claude/worktrees/`
- Always branch from `main` with prefix `claude/`
- Open a PR after each task — never merge another agent's branch
- Never force-add `.claude/`, `.codex*`, `.gemini/`, `.maq/` to git
- Before starting a task, check open PRs to avoid file conflicts with Codex or MAQ

## Commands

### Backend (Spring Boot / Maven)
```bash
mvn -q -DskipTests compile                              # Compile only
mvn -q test                                             # Run all tests
mvn -q verify                                           # Full verify (used by CI)
mvn -q test -Dtest=ClassName                            # Run a single test class
mvn -q test -Dtest=ClassName#methodName                 # Run a single test method
mvn spring-boot:run -Dspring-boot.run.profiles=local    # Run backend (Claude port 8090)
```

### Frontend (Next.js)
```bash
cd frontend
npm install          # Install dependencies
npm run lint         # Lint check (ESLint)
npm run dev          # Dev server on port 3001
npm run build        # Production build
```

## Architecture

**Stack:** Spring Boot 3.2 / Java 21 backend + Next.js 14 / TypeScript frontend + PostgreSQL 16 + WebSocket (STOMP).

### Backend — Hexagonal / DDD

The backend enforces strict layer isolation verified by ArchUnit tests:

- **`domain/`** — Pure Java, zero Spring/JPA. Contains aggregates, value objects, domain services, and ports (interfaces). Sub-domains: `alert/`, `analysis/`, `contract/`, `engine/indicators/`, `engine/smc/`, `execution/`, `marketdata/`, `trading/`, `shared/`.
- **`application/`** — Orchestrates use cases via application services (`AlertService`, `MarketDataService`, `PositionService`, `MentorAnalysisService`, `RolloverDetectionService`). Uses domain ports, emits domain events.
- **`infrastructure/`** — Adapters: IBKR native socket client (`IbGatewayNativeClient` on port 4001), JPA repositories, Spring configuration, WebSocket broker config.
- **`presentation/`** — REST controllers + WebSocket message handlers. DTOs are transport-only and never leak into domain.

### Market Data Flow

IBKR Gateway (port 4001) → `IbGatewayNativeClient` → PostgreSQL → application services → WebSocket (STOMP) → frontend. **No external data providers** (Yahoo, Polygon, etc.) are allowed.

### Alert System

Alerts are **transition-based** only — they fire when an indicator's state changes, not when price reaches a level. Key components:

- `IndicatorAlertEvaluator` — fires EMA crossovers, RSI extremes, MACD/WaveTrend crosses, SMC structure breaks (CHoCH/BOS), order block lifecycle events.
- `SignalPreFilterService` — guard rules: HTF trend filter (Rule 1), anti-chop 60s window (Rule 3), candle-close only (Rule 4).
- `AlertDeduplicator` — 5-minute cooldown per instrument+timeframe (hardcoded in `AlertConfig`).
- `AlertService` — only EMA and risk category alerts are published to WebSocket; MACD, RSI, WaveTrend, SMC, OrderBlock alerts are routed to mentor review only.

**Not implemented:** support/resistance level touches, price proximity to EMA50 (only crossover exists), VWAP bounce, FVG fills, BB alerts, Supertrend alerts.

### Frontend

Next.js 14 App Router. Real-time prices via STOMP (`@stomp/stompjs`). Charts via `lightweight-charts`. State via React hooks in `app/hooks/`. API utilities in `app/lib/`.

### Agent Port / IBKR Client-ID Map

| Agent  | Port | Client-ID |
|--------|------|-----------|
| Human  | 8080 | 1         |
| Claude | 8090 | 8         |
| Codex  | 8085 | 7         |
| MAQ    | 8070 | 9         |
| Gemini | 8060 | 6         |

Claude always runs on port 8090 with client-id 8 (`application-local.properties`).
