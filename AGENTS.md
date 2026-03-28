# AGENTS.md

## Purpose

This file is the operational guide for human developers and coding agents working on `riskdesk2`.

Read this file before making changes.

## Multi-Agent Git Rules

This repo is worked on by multiple AI agents simultaneously (Codex, Claude Code, MAQ).
These rules are mandatory for all agents.

### Workspace isolation

Each agent has its own isolated working directory. Never work from another agent's directory.

| Agent | Working directory |
|---|---|
| Codex | `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/` |
| Claude Code | `~/.claude/worktrees/<name>/` (auto-managed) |
| Claude Bedrock (VS Code) | `~/riskdesk2-maq/` |
| Human / VS Code | `~/riskdesk2/` |

### Branch rules

- Always branch from `main` before starting a task:
  ```bash
  git fetch origin
  git switch -c <agent>/my-feature origin/main
  ```
- Use your agent prefix: `codex/`, `claude/`, `claude-bedrock/`
- Keep branches short-lived (1–2 days max)
- Open a PR and let the human merge — never merge another agent's branch yourself

### Never commit agent runtime files

The following are already in `.gitignore` — do not force-add them:
- `.codex/`, `.codex-bedrock/`, `.codex-vertex/`
- `.claude/`
- `.gemini/`
- `.maq/`

### Avoid working on the same files as another agent

Before starting a task, check open PRs on GitHub. If another agent has an open PR touching the same files, coordinate with the human before proceeding.

---

## Non-Negotiable Rules

### 1. Market data source policy

Only the following data path is allowed:

`IBKR Gateway -> PostgreSQL -> internal services`

Do not add, restore, or suggest any of the following:

- Yahoo Finance
- Stooq
- Alpha Vantage
- Polygon
- Binance
- CSV imports from external providers
- scraping-based feeds
- simulated fallback feeds for production logic

If you find code that reintroduces an external market data source, remove it or replace it with the IBKR/PostgreSQL flow.

### 2. Secret handling

Never commit secrets.

Ignored local files already include:

- `.env.local`
- `frontend/.env.local`
- `target/`
- `frontend/node_modules/`
- `.claude/`

Do not paste or hardcode API keys, bot tokens, passwords, or local credentials into tracked files.

### 3. Repo scope

The Git repository root is this directory:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2`

The frontend lives inside:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend`

Do not recreate a nested Git repository inside `frontend/`.

## Project Overview

- Backend: Spring Boot 3.2, Java 19, PostgreSQL
- Frontend: Next.js 14, React 18, TypeScript
- Real-time transport: WebSocket/STOMP
- Trading focus: futures risk dashboard with mentor workflow and IBKR integration

## Engineering Principles

The project must respect these principles:

- DDD
- TDD
- BDD
- Hexagonal Architecture

These are not decorative labels. They should shape how changes are designed.

### DDD expectations

- business rules belong in `domain`
- orchestration belongs in `application`
- technical adapters belong in `infrastructure`
- HTTP, WebSocket, and DTO concerns belong in `presentation`
- avoid putting domain rules into controllers, repositories, or framework annotations

### Hexagonal architecture expectations

Use the following conceptual layers:

- `presentation`
- `application`
- `domain`
- `infrastructure`

Rules:

- `domain` must not depend on Spring, HTTP, database, or IBKR implementation details
- `application` coordinates use cases and depends on domain abstractions
- `infrastructure` implements ports declared by the domain/application layers
- `presentation` only adapts transport input/output to use cases

### TDD expectations

- when changing business logic, add or update tests first when practical
- every bug fix should come with a regression test unless the code path is purely wiring
- prefer focused unit tests in `src/test/java` near the changed business area

### BDD expectations

- user-visible workflows should remain expressible in business scenarios
- when a change affects behavior across the app, consider updating or adding Cucumber features
- preserve the language of business outcomes, not framework internals

## Current Architecture

### Backend layers

- `presentation`: controllers and DTOs, transport-only concerns
- `application/service`: orchestration and use cases
- `domain/*`: indicators, trading, market-data ports, alert logic, business rules
- `infrastructure/config`: wiring and runtime configuration
- `infrastructure/marketdata/ibkr`: IBKR adapters and native gateway integration
- `infrastructure/persistence`: JPA adapters
- `presentation/controller` and `presentation/dto`: HTTP API surface

### Frontend areas

- `frontend/app/components`: dashboard and mentor UI
- `frontend/app/lib/api.ts`: API client calls
- `frontend/app/hooks/useWebSocket.ts`: live updates

## Files to Read First

Before editing, prefer reading:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/PROJECT_CONTEXT.md`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/AI_HANDOFF.md`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/resources/application.properties`

## Development Commands

### Backend

```bash
mvn -q -DskipTests compile
mvn -q test
mvn -q spring-boot:run
java -jar target/riskdesk-0.1.0-SNAPSHOT.jar
```

### Frontend

```bash
cd frontend
npm install
npm run lint
npm run dev
```

## Validation Expectations

For backend changes:

- at minimum run `mvn -q -DskipTests compile`
- when domain or application logic changes, prefer running the relevant tests or adding them first

For frontend changes:

- at minimum run `cd frontend && npm run lint`

If touching both, run both checks.

## Editing Guidance

- Prefer small, localized changes
- Preserve the current domain-oriented package structure
- Keep API field names stable unless you are updating both backend and frontend together
- When changing market data behavior, preserve the ability to identify whether a value came from live IBKR or DB fallback
- If a change crosses layers, check that responsibilities still align with `presentation -> application -> domain <- infrastructure`
- Prefer ports/interfaces over leaking infrastructure details upward
- Avoid “smart controllers” and “fat infrastructure services”

## Current Runtime Reality

The application now avoids reconnect storms on the native IBKR client, but live prices can still fall back to PostgreSQL when IBKR market data farms are unavailable.

That is an upstream IBKR connectivity/runtime issue, not a license to reintroduce external providers.

## Documentation Rule

If you make a significant architectural or workflow change, update:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/PROJECT_CONTEXT.md`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/AI_HANDOFF.md`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/ARCHITECTURE_PRINCIPLES.md`
