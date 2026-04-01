# CONTRIBUTING.md - Operational Guide

## 1. MANDATORY PRE-REQUISITES
**MUST ALWAYS** read the following files in exact order before modifying any code:
1. **`AGENTS.md`**
2. **`docs/PROJECT_CONTEXT.md`**
3. **`docs/ARCHITECTURE_PRINCIPLES.md`**
4. **`docs/AI_HANDOFF.md`**

* **MUST** update documentation within the same branch OR stop and explicitly explain the conflict if your changes contradict any of these files.

---

## 2. NON-NEGOTIABLE PRODUCT RULES
* **MUST ALWAYS** route production market data exclusively through this path: **`IBKR Gateway -> PostgreSQL -> internal services`**.
* **NEVER** add or utilize Yahoo, Binance, Polygon, AlphaVantage, web scraping, CSV imports, or mock live feeds as runtime substitutes.
* **NEVER** commit secrets, API keys, session cookies, **`.env.local`**, or local credentials.
* **NEVER** bypass architectural rules to force a controller or demo to work.

---

## 3. STRICT ARCHITECTURE RULES (DDD + HEXAGONAL)
**MUST ALWAYS** adhere to the following layer constraints and dependency rules:

### `presentation` Layer
* **MUST** contain ONLY HTTP/WebSocket controllers, request validation, and transport concerns.
* **MAY** depend on `application` contracts and DTOs.
* **ALWAYS** move transport DTOs here.

### `application` Layer
* **MUST** contain ONLY use cases, orchestration, facades, and application DTOs.
* **MUST NOT** depend on `presentation`.
* **ALWAYS** move use-case DTOs here.

### `domain` Layer
* **MUST** contain ONLY business rules, aggregates, value objects, domain services, and pure backtest engines.
* **MUST NOT** depend on `application`, `presentation`, `infrastructure`, Spring, or JPA.
* **ALWAYS** move business concepts here.

### `infrastructure` Layer
* **MUST** contain ONLY persistence, external gateways, framework adapters, and configuration.
* **MUST NOT** depend on `presentation`.
* **ALWAYS** move framework code here.

---

## 4. TESTING DISCIPLINE (TDD / BDD)
* **ALWAYS** add or update tests concurrently with behavior changes (TDD/BDD expected).
* **ALWAYS** prefer unit tests for **`domain`** logic.
* **ALWAYS** use integration tests for adapters, persistence, and controller wiring.
* **MUST** align BDD scenarios with user-visible behavior.
* **MUST** run the smallest relevant test set first before broader validation.

### Minimum Pre-Push Validation
* **Backend:** **MUST** run **`mvn -q -DskipTests compile`** AND targeted tests for touched code.
* **Frontend:** **MUST** run **`npm run lint`** when frontend code changes.
* **MUST** explicitly state in the commit or hand-off message if you were unable to run a validation step.

---

## 5. MULTI-AGENT WORKFLOW
### Pre-Edit
* **MUST** claim a clear write scope before editing.
* **MUST** check **`git status`**, inspect touched files carefully, and preserve uncommitted user changes.

### Execution
* **NEVER** rewrite unrelated files opportunistically.
* **NEVER** revert another contributor's work unless explicitly requested.
* **ALWAYS** prefer additive refactors with clear boundaries over wide "cleanup" passes.

### Post-Edit
* **MUST** summarize what changed.
* **MUST** deliberately list any follow-up technical debt left behind.
* **MUST** mention tests run and notable risks.
* **MUST** update **`docs/AI_HANDOFF.md`** if the branch alters architecture, contracts, startup behavior, or operational assumptions.

---

## 6. BACKEND STARTUP HYGIENE
* **MUST** keep local startup logs useful and avoid noise.
* **ALWAYS** use **`INFO`** level for summary logs.
* **ALWAYS** use **`DEBUG`** level for repetitive fetch/detail logs.
* **NEVER** downgrade real failures that block trading, persistence, or connectivity.
* **MUST NOT** log warnings for expected transient snapshot behavior unless they require operator action.

---

## 7. COMMIT STANDARDS
* **MUST** write focused commits using imperative messages (e.g., `Move application DTOs out of presentation`).
* **NEVER** mix unrelated refactors into a single commit unless they are strictly inseparable.