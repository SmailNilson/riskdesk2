# Contributing

## First Read

Before changing code, read these files in order:

1. `AGENTS.md`
2. `docs/PROJECT_CONTEXT.md`
3. `docs/ARCHITECTURE_PRINCIPLES.md`
4. `docs/AI_HANDOFF.md`

If your change conflicts with any of them, update the docs in the same branch or stop and explain the conflict.

## Non-Negotiable Product Rules

- Production market data must flow only through `IBKR Gateway -> PostgreSQL -> internal services`.
- Do not add Yahoo, Binance, Polygon, AlphaVantage, scraping, CSV imports, or mock live feeds as runtime substitutes.
- Do not commit secrets, API keys, session cookies, `.env.local`, or local credentials.
- Do not bypass the architecture rules just to make a controller or demo work.

## Architecture Rules

The codebase follows DDD + hexagonal architecture.

- `presentation`
  - HTTP/WebSocket controllers, request validation, transport concerns only.
  - May depend on `application` contracts and DTOs.
- `application`
  - Use cases, orchestration, facades, application DTOs.
  - Must not depend on `presentation`.
- `domain`
  - Business rules, aggregates, value objects, domain services, pure backtest engines.
  - Must not depend on `application`, `presentation`, `infrastructure`, Spring, or JPA.
- `infrastructure`
  - Persistence, external gateways, framework adapters, configuration.
  - Must not depend on `presentation`.

When in doubt:
- move transport DTOs into `presentation`
- move use-case DTOs into `application`
- move business concepts into `domain`
- move framework code into `infrastructure`

## Testing Discipline

We expect TDD/BDD behavior, not just tests added afterward.

- Add or update tests with every behavior change.
- Prefer unit tests for domain logic.
- Use integration tests for adapters, persistence, and controller wiring.
- Keep BDD scenarios aligned with user-visible behavior.
- Run the smallest relevant test set first, then broader validation.

Minimum validation before pushing:

- `mvn -q -DskipTests compile`
- targeted backend tests for touched code
- `npm run lint` when frontend code changes

If you could not run something, say so explicitly in the commit/hand-off message.

## Multi-AI Workflow

When several agents or contributors work in parallel:

- Claim a clear write scope before editing.
- Do not rewrite unrelated files opportunistically.
- Do not revert someone else’s work unless explicitly requested.
- Prefer additive refactors with clear boundaries over wide “cleanup” passes.
- Update `docs/AI_HANDOFF.md` when the branch changes architecture, contracts, startup behavior, or operational assumptions.

Before editing:

- check `git status`
- inspect the touched files carefully
- preserve uncommitted user changes

After editing:

- summarize what changed
- list any follow-up debt deliberately left behind
- mention tests run and notable risks

## Backend Startup Hygiene

Keep local startup useful, not noisy.

- Prefer summary logs at `INFO`.
- Keep repetitive fetch/detail logs at `DEBUG`.
- Do not downgrade real failures that block trading, persistence, or connectivity.
- Avoid warnings for expected transient snapshot behavior unless they require operator action.

## Commits

Use focused commits with imperative messages, for example:

- `Move application DTOs out of presentation`
- `Tighten hexagonal architecture rules`
- `Reduce backend startup noise`

Do not mix unrelated refactors in one commit unless they are inseparable.
