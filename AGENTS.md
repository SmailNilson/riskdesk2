# AGENTS.md

## Purpose

This file is the operational guide for human developers and coding agents working on `riskdesk2`.

Read this file before making changes.

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

## Current Architecture

### Backend layers

- `application/service`: orchestration and use cases
- `domain/*`: indicators, trading, market-data ports, alert logic
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

For frontend changes:

- at minimum run `cd frontend && npm run lint`

If touching both, run both checks.

## Editing Guidance

- Prefer small, localized changes
- Preserve the current domain-oriented package structure
- Keep API field names stable unless you are updating both backend and frontend together
- When changing market data behavior, preserve the ability to identify whether a value came from live IBKR or DB fallback

## Current Runtime Reality

The application now avoids reconnect storms on the native IBKR client, but live prices can still fall back to PostgreSQL when IBKR market data farms are unavailable.

That is an upstream IBKR connectivity/runtime issue, not a license to reintroduce external providers.

## Documentation Rule

If you make a significant architectural or workflow change, update:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/PROJECT_CONTEXT.md`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/AI_HANDOFF.md`

