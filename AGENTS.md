# AGENTS.md - Operational Guide

## 1. ABSOLUTE PROHIBITIONS

### Market Data Sources
*   **MUST ALWAYS** use exclusively this data path: `IBKR Gateway -> PostgreSQL -> internal services`.
*   **NEVER** add, restore, or suggest any of the following:
    *   Yahoo Finance
    *   Stooq
    *   Alpha Vantage
    *   Polygon
    *   Binance
    *   CSV imports from external providers
    *   Scraping-based feeds
    *   Simulated fallback feeds for production logic
*   **MUST** immediately remove or replace any code that reintroduces external market data sources with the IBKR/PostgreSQL flow.
*   **MUST** preserve the ability to identify whether a value came from live IBKR or DB fallback when modifying market data behavior.

### Secrets & Credentials
*   **NEVER** commit secrets, API keys, bot tokens, passwords, or local credentials into tracked files.
*   **NEVER** force-add ignored files (e.g., `.env.local`, `frontend/.env.local`, `target/`, `frontend/node_modules/`, `.claude/`, `.gemini/`, `.codex/`, `.maq/`).

### Repository Scope
*   **NEVER** edit files directly in the main scratch clone at the repository root. This is a bare origin.
*   **NEVER** recreate a nested Git repository inside **`frontend`**.

---

## 2. MULTI-AGENT GIT & WORKTREE RULES

**ALL** agents (Codex, Claude, MAQ) **MUST** adhere to strict workspace isolation to prevent collisions.

### Workspace Isolation
*   **MUST ALWAYS** work within your designated **git worktree**.
*   **NEVER** work in the main checkout or another agent's worktree.
*   **MUST** check open PRs on GitHub before starting a task. **NEVER** modify files currently being touched by another agent's open PR without human coordination.

### Agent Environment Mapping
| Agent | Worktree Root | Branch Prefix |
|---|---|---|
| Codex | **`.codex/worktrees/<name>/`** | **`codex/`** |
| Claude Code | **`.claude/worktrees/<name>/`** | **`claude/`** |
| MAQ | **`.maq/worktrees/<name>/`** | **`maq/`** |
| Claude Bedrock | **`.claude-bedrock/worktrees/<name>/`** | **`claude-bedrock/`** |
| Gemini / Antigravity | **`.gemini/worktrees/<name>/`** | **`gemini/`** |

### Branching Rules
*   **ALWAYS** branch from `main`.
*   **ALWAYS** apply your agent prefix to the branch name (e.g., `codex/feature-name`).
*   **MUST** move to an agent branch immediately before any investigation, read-only debugging, or implementation.
*   **NEVER** work directly on `main`.
*   **NEVER** merge your own or another agent's branch. Open a PR and let the human merge.
*   **MUST** keep branches short-lived (1–2 days max).

### Manual Worktree Commands (Fallback)
If auto-creation fails, execute from repo root:
```bash
# Create
git fetch origin
git worktree add ./<agent>/worktrees/<task-slug> -b <agent>/<task-slug> origin/main

# Teardown (Post-Merge)
git worktree remove ./<agent>/worktrees/<task-slug>
git branch -d <agent>/<task-slug>
```

---

## 3. RUNTIME & CONFIGURATION RULES

### Mandatory Local Spring Profile
*   **MUST ALWAYS** run the backend with the `local` Spring profile active.
*   **NEVER** hardcode a port or IBKR `client-id` into `application.properties`.
*   **NEVER** commit `application-local.properties` (it is gitignored).

**Backend Run Commands:**
```bash
# Maven
mvn spring-boot:run -Dspring-boot.run.profiles=local

# JAR
java -Dspring.profiles.active=local -jar target/riskdesk-0.1.0-SNAPSHOT.jar
```

### Required `application-local.properties` Overrides
To prevent IB Gateway connection failures, configure your specific overrides:

| Configuration | Claude | Codex | MAQ | Bedrock | Gemini | Human |
|---|---|---|---|---|---|---|
| **`server.port`** | 8090 | 8085 | 8070 | 8050 | 8060 | 8080 |
| **`riskdesk.ibkr.native-client-id`** | 8 | 7 | 9 | 5 | 6 | 1 |
| **`spring.datasource.username`** | *(local OS user)* | *(local OS user)* | *(local OS user)* | *(local OS user)* | *(local OS user)* | *(local OS user)* |

---

## 4. ARCHITECTURAL & ENGINEERING RULES

**MUST ALWAYS** strictly implement Hexagonal Architecture, Domain-Driven Design (DDD), Test-Driven Development (TDD), and Behavior-Driven Development (BDD). These are structural mandates, not suggestions.

### Layer Responsibilities (Hexagonal Architecture)
*   **`domain`**: **MUST** contain ONLY business rules. **NEVER** depend on Spring, HTTP, Database, or IBKR implementation details.
*   **`application`**: **MUST** coordinate use cases and depend solely on domain abstractions.
*   **`infrastructure`**: **MUST** implement ports declared by domain/application layers. **NEVER** leak infrastructure details upward.
*   **`presentation`**: **MUST** adapt transport input/output (HTTP, WebSocket, DTOs) to use cases. **NEVER** contain domain rules.

### TDD & BDD Mandates
*   **ALWAYS** write or update tests *first* when changing business logic.
*   **ALWAYS** include a regression test for every bug fix (unless purely wiring).
*   **MUST** place focused unit tests in `src/test/java` near the changed business area.
*   **MUST** express user-visible workflows in business scenarios (Cucumber features).

### Editing Guidance
*   **ALWAYS** prefer small, localized changes.
*   **ALWAYS** preserve the current domain-oriented package structure.
*   **ALWAYS** keep API field names stable unless updating backend and frontend simultaneously.
*   **NEVER** create "smart controllers" or "fat infrastructure services".

---

## 5. PROJECT STRUCTURE & COMMANDS

### Tech Stack
*   **Backend**: Spring Boot 3.2, Java 19, PostgreSQL
*   **Frontend**: Next.js 14, React 18, TypeScript
*   **Transport**: WebSocket/STOMP

### Pre-requisite Reading
Read these files BEFORE making architectural changes:
*   **`docs/PROJECT_CONTEXT.md`**
*   **`docs/AI_HANDOFF.md`**
*   **`src/main/resources/application.properties`**

### Development Commands
**Backend:**
```bash
mvn -q -DskipTests compile
mvn -q test
```

**Frontend:**
```bash
cd frontend
npm install
npm run lint
npm run dev
```

---

## 6. VALIDATION & DOCUMENTATION

### Validation Expectations
*   **Backend changes**: **MUST** run `mvn -q -DskipTests compile` and execute relevant tests.
*   **Frontend changes**: **MUST** run `cd frontend && npm run lint`.
*   **Cross-stack changes**: **MUST** run both.

### Documentation Rule
If you make a significant architectural or workflow change, you **MUST** update:
*   **`docs/PROJECT_CONTEXT.md`**
*   **`docs/AI_HANDOFF.md`**
*   **`docs/ARCHITECTURE_PRINCIPLES.md`**