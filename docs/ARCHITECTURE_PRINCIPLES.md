# Architecture Principles

## Objective

This project should be evolved using:

- DDD
- TDD
- BDD
- Hexagonal Architecture

These principles exist to keep the codebase maintainable while multiple developers and coding agents work in parallel.

## Hexagonal Layer Model

### Presentation

Location:

- `src/main/java/com/riskdesk/presentation`

Responsibilities:

- REST controllers
- WebSocket-facing entry points
- request/response DTO mapping
- transport validation and serialization

Must not:

- contain business rules
- contain persistence logic
- orchestrate low-level infrastructure details

### Application

Location:

- `src/main/java/com/riskdesk/application`

Responsibilities:

- use-case orchestration
- transaction/use-flow coordination
- calling domain services and ports
- assembling data for presentation

Must not:

- become a controller layer in disguise
- directly own database schemas or HTTP concerns
- encode core business rules that belong in domain objects/services

### Domain

Location:

- `src/main/java/com/riskdesk/domain`

Responsibilities:

- business rules
- domain events
- aggregates
- value objects
- indicators and trading logic
- ports/interfaces that express required capabilities

Must not:

- depend on Spring
- depend on JPA entities or repositories
- depend on HTTP, WebSocket, or IBKR SDK details

### Infrastructure

Location:

- `src/main/java/com/riskdesk/infrastructure`

Responsibilities:

- JPA adapters
- IBKR adapters
- config/wiring
- external service integration
- persistence and transport implementations of ports

Must not:

- absorb business decision logic just because it is convenient
- redefine domain rules

## DDD Guidance

- model business language explicitly
- prefer value objects and domain services over procedural blobs
- keep futures trading vocabulary consistent across layers
- domain ports should describe capabilities, not technologies

Good examples:

- “fetch current market price”
- “load open positions”
- “calculate portfolio risk”

Less good examples:

- “call REST endpoint”
- “query postgres row”
- “read ib socket packet”

## TDD Guidance

Apply TDD especially when:

- fixing a bug
- changing indicator behavior
- changing PnL/risk logic
- changing mentor payload composition
- changing source attribution or fallback behavior

Preferred order:

1. write or update the failing test
2. implement the change
3. refactor while tests stay green

## BDD Guidance

Use BDD to protect business workflows, not implementation details.

Good BDD candidates:

- opening and closing a position
- portfolio summary and exposure calculations
- risk alerts and threshold behavior
- mentor analysis request/response behavior

Existing BDD resources live under:

- `src/test/resources/features`
- `src/test/java/com/riskdesk/bdd`

## Change Rules for Agents

Before making a change, identify:

- which layer owns the behavior
- which tests should move with the behavior
- whether the change is business logic, orchestration, or infrastructure

If a change touches several layers:

- keep each responsibility in its own layer
- avoid shortcutting directly from `presentation` to `infrastructure`
- prefer domain/application ports where the dependency direction matters

### Container Release Rule

- container image validation belongs in GitHub Actions, not as a required local developer step
- release image publication must be driven by immutable Git tags
- production compose deployment must consume immutable GHCR tags rather than rebuilding the app from source on the server
- prefer GitHub Container Registry for repository-scoped image publishing because `GITHUB_TOKEN` can publish without storing an extra Docker registry secret
- keep the Docker build context minimal with `.dockerignore` so CI does not upload local agent/runtime artifacts
- if a required build dependency is not published to Maven Central, vendor it in a repo-local Maven repository so Docker and CI builds stay reproducible

### Frontend Workflow Rule

When extending Mentor behavior in the UI:

- keep raw transport alerts separate from AI-reviewed trading alerts
- reuse shared Mentor payload-building helpers instead of duplicating payload logic across components
- keep account-risk sharing an explicit toggle or mode, not an implicit default for new workflows
- when an alert must trigger autonomous Mentor review, prefer backend orchestration plus WebSocket publication over client-only side effects
- when an alert review must stay historically stable, persist the frozen payload snapshot in the backend and reopen that saved thread rather than rebuilding live market context on click
- group alerts in the UI only when they arrive within a short time window (~90s) for the same instrument/timeframe/direction — do not merge alerts that are temporally distant
- reconstruct virtual alerts from saved reviews so that historical reviews remain visible even after the WebSocket session ends

### Alert Evaluation Rule

Alert evaluation must use transition-based detection, not state-based:

- alerts fire only when a condition *changes* (e.g., RSI crosses into oversold), not when it persists
- `IndicatorAlertEvaluator` in `domain` tracks last-known state per indicator/instrument/timeframe
- this is a domain concern and must stay in the domain layer
- `AlertService` in `application` orchestrates publishing and mentor review batching
- indicator alert dedup remains key-based with a short shared cooldown; do not reintroduce timeframe-length blocking windows that suppress otherwise valid multiple alerts on `10m` or `1h`
- when multiple indicators fire in the same polling cycle for the same instrument/timeframe/direction, the application layer batches them into a single mentor review via `captureGroupReview`
- individual alerts are still published to WebSocket separately for the UI

Do not:

- revert to state-based evaluation where alerts re-fire for persistent conditions
- create separate mentor reviews for alerts that fire simultaneously for the same trading signal
- move transition detection logic out of the domain layer

### Synthetic DXY Rule

When working with the dollar index inside RiskDesk:

- treat `DXY` as an internal synthetic series, not as the exchange-traded `DX` future
- source the six FX inputs only from the existing native IBKR Gateway quote path
- keep the calculation rule in `domain`
- keep orchestration, fallback, WebSocket publication, and REST exposure in `application`
- keep FX contract resolution and quote collection in `infrastructure`
- persist complete snapshots in a dedicated DXY persistence model instead of overloading the generic candle history
- exclude `DXY` from futures-only workflows such as active-contract rollover, exchange-traded contract discovery, and futures historical refresh
- preserve explicit source attribution between live synthetic output and DB-served fallback

Do not:

- reintroduce `DX/ICEUS` as the source of truth for internal DXY logic
- hide whether a served DXY value is live synthetic data or a persisted DB fallback
- push FX quote parsing or DXY formula rules into controllers or persistence adapters

### Mentor Outcome Tracking Rule

When evaluating whether a saved Mentor trade plan was correct:

- keep the replay/simulation orchestration in `application`
- source candles only from the internal PostgreSQL-backed candle repository
- do not recompute the original Mentor review payload during outcome evaluation
- use deterministic, pessimistic execution rules when candle granularity cannot disambiguate intrabar order

#### Simulation Decoupling Rule (TECH DEBT — active)

**Current state:** Simulation fields (`simulationStatus`, `activationTime`, `resolutionTime`, `maxDrawdownPoints`, `trailingStopResult`, `trailingExitPrice`, `bestFavorablePrice`) live directly on `MentorSignalReviewRecord` and `MentorAudit`. This creates tight coupling: 29+ references across 11 files, 5 layers deep. The same model carries signal review concerns AND simulation lifecycle concerns.

**Target state:** A dedicated `TradeSimulation` aggregate owns all simulation state:

```
trade_simulations
  id                  BIGSERIAL PK
  review_id           BIGINT UNIQUE NOT NULL  → mentor_signal_reviews(id)
  review_type         VARCHAR(16) NOT NULL     → 'SIGNAL' | 'AUDIT'
  instrument          VARCHAR(16) NOT NULL
  action              VARCHAR(16) NOT NULL
  simulation_status   VARCHAR(32) NOT NULL
  activation_time     TIMESTAMPTZ
  resolution_time     TIMESTAMPTZ
  max_drawdown_points NUMERIC(19,6)
  trailing_stop_result VARCHAR(32)
  trailing_exit_price  NUMERIC(19,6)
  best_favorable_price NUMERIC(19,6)
  created_at          TIMESTAMPTZ NOT NULL
```

**Rules for new simulation-related development (effective immediately):**

1. **Do not add new simulation fields to `MentorSignalReviewRecord` or `MentorAudit`.** Any new simulation concern (e.g., partial TP tracking, multi-leg simulation, paper-trading state) must be modeled on the simulation side, not the review side.
2. **Do not query simulation state via `MentorSignalReviewRepositoryPort`.** New code should access simulation state through a dedicated port. The existing `findBySimulationStatuses()` method on the review repository is legacy and should not be duplicated or extended.
3. **Do not push simulation updates through the review WebSocket topic.** New simulation events should emit on a dedicated `/topic/simulations` channel. The existing `/topic/mentor-alerts` push for simulation changes is legacy.
4. **New simulation strategies (trailing stop variants, bracket orders, time-based exits) must be additive columns or new entities on the simulation aggregate**, never on the review model.
5. **The `TradeSimulationService` is the sole writer of simulation state.** `MentorSignalReviewService.initializeSimulationState()` may set `PENDING_ENTRY` at creation time, but all subsequent state transitions belong to `TradeSimulationService`.

**Migration plan (when the refactor happens):**

Phase 1 — Create `trade_simulations` table with FK to reviews. Dual-write: `TradeSimulationService` writes to both old fields and new table. New code reads from new table.

Phase 2 — Migrate frontend to read simulation state from new `/topic/simulations` or from a joined DTO. Remove simulation fields from `MentorSignalReview` DTO.

Phase 3 — Drop simulation columns from `mentor_signal_reviews` and `mentor_audits`. Remove `findBySimulationStatuses()` from `MentorSignalReviewRepositoryPort`.

**Why not now:** The refactor touches 11+ files, changes the REST API contract, requires frontend migration, and risks breaking the 60s scheduled poller that drives all simulation state. It should be a standalone PR with full regression testing, not bundled with feature work.

### Live Execution Foundation Rule

When introducing real-order execution:

- do not overload `MentorSignalReview` with broker order lifecycle state
- persist live execution workflow in a dedicated model/table linked by review ID
- treat `mentorSignalReviewId` as the idempotence key for execution creation
- require backend-owned eligibility metadata on the review; do not parse UI verdict strings as execution truth
- keep Slice 1 free of IBKR order placement side effects until the persistence and state machine foundation is stable
- expose execution reads and writes through dedicated execution transport DTOs/endpoints rather than nesting the execution aggregate into review persistence by default
- freeze execution quantity on the execution aggregate itself before any broker submission
- when submitting an entry order, lock the execution row before the external broker side effect so two concurrent triggers cannot place two orders

## Date, Time & Timezone Rules

RiskDesk trades CME Futures. A "trading day" runs from 17:00 ET to 17:00 ET the next calendar day — NOT from midnight UTC. Every piece of code that touches time must respect this.

### Non-Negotiable Principles

1. **Storage is always UTC.** JPA entities use `java.time.Instant`. PostgreSQL columns use `TIMESTAMPTZ`. No exceptions.
2. **Business projection is America/New_York.** When code needs to answer "which trading day is this?" or "has the session closed?", it must project the UTC instant into `ZoneId.of("America/New_York")` via `TradingSessionResolver`.
3. **Never use `LocalDateTime` for persistence or domain logic.** `LocalDateTime` has no timezone — it is ambiguous by design. Use `Instant` (UTC point in time) or `ZonedDateTime` (when the timezone is part of the business meaning).
4. **Never use `ZoneId.systemDefault()` or `LocalDate.now()` without an explicit zone.** The JVM timezone is an infrastructure accident, not a business decision.

### Allowed Types by Layer

| Layer | Allowed types | Forbidden types |
|---|---|---|
| JPA entities | `Instant` | `LocalDateTime`, `Date`, `Calendar`, `Timestamp` |
| Domain model / value objects | `Instant`, `ZonedDateTime`, `LocalDate` (with explicit `ZoneId`) | `LocalDateTime` without zone context |
| Presentation (REST DTOs) | `Instant` (serialized as ISO-8601 UTC string) | Epoch millis, custom string formats |
| IBKR adapter (infrastructure) | `Instant` for internal use; explicit formatter with `ZoneOffset.UTC` for IBKR wire format | `SimpleDateFormat`, implicit timezone parsing |

### TradingSessionResolver — Single Source of Truth

All session boundary logic must go through `TradingSessionResolver` (domain layer, `domain/shared/`). This class owns:

- `dailySessionStart(Instant)` — returns 17:00 ET of the previous calendar day
- `dailySessionEnd(Instant)` — returns 17:00 ET of the current calendar day
- `tradingDate(Instant)` — returns the `LocalDate` of the trading session (a tick at 02:00 UTC Tuesday = Monday's trading day)
- `weeklySessionStart(Instant)` — returns Sunday 17:00 ET
- `inferMarketSession(Instant)` — returns ASIAN / LONDON / NEW_YORK / OFF_HOURS (DST-aware)

Constants are hardcoded for CME: `ZoneId.of("America/New_York")`, session close at `LocalTime.of(17, 0)`. If multi-exchange support is added later, extract to `@ConfigurationProperties` at that point — not before.

### Candle Aggregation Rules

- **Intraday timeframes (1m, 5m, 10m, 15m, 30m, 1h, 4h):** truncate to UTC epoch boundary. `(epochSeconds / periodSeconds) * periodSeconds` is correct.
- **Daily (1d):** aggregate from `dailySessionStart()` to `dailySessionEnd()` via `TradingSessionResolver`. Do NOT truncate to midnight UTC — this would mix two CME sessions.
- **Weekly (1w):** aggregate from `weeklySessionStart()` (Sunday 17:00 ET) to Friday 17:00 ET via `TradingSessionResolver`.

### VWAP Reset Convention

Session VWAP resets at **midnight ET** (change of `LocalDate` in `America/New_York`), consistent with TradingView and Bloomberg. This is NOT the same as the CME session boundary (17:00 ET). Do not "fix" this to 17:00 ET — it would break comparability with external charting platforms.

If a CME-session VWAP is needed, create a separate indicator (`CmeSessionVwapIndicator`) that resets at 17:00 ET.

### IBKR Date Parsing Rules

- **Sending requests to IBKR:** format `Instant` with `DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss").withZone(ZoneOffset.UTC)`.
- **Receiving intraday bars:** prefer `bar.time()` (epoch seconds, always UTC). The string fallback `bar.timeStr()` is in exchange timezone (America/New_York for CME) — if you must parse it, use `ZoneId.of("America/New_York")`, NOT `ZoneOffset.UTC`.
- **Receiving daily bars:** IBKR returns `"yyyyMMdd"` in exchange timezone. Interpret as `LocalDate` in `America/New_York`, then convert to `Instant` via `TradingSessionResolver.dailySessionStart()`.
- **Always log a warning** when falling back from epoch to string parsing.

### PostgreSQL Rules

- All timestamp columns must be `TIMESTAMPTZ`, never `TIMESTAMP`.
- When writing raw DDL (e.g., `CREATE TABLE` in Java), always specify `TIMESTAMPTZ`.
- Never use `CURRENT_DATE` or `CURRENT_TIMESTAMP` in JPQL/SQL queries for trading-day boundaries — pass an explicit `Instant` parameter computed by `TradingSessionResolver`.
- Hibernate config must include `spring.jpa.properties.hibernate.jdbc.time_zone=UTC`.

### JVM Startup Rule

Production and development JVMs must start with `-Duser.timezone=UTC`. This is a safety net, not a substitute for explicit timezone handling in code.

### DST Awareness

Daylight Saving Time shifts (second Sunday of March, first Sunday of November) change the UTC offset of `America/New_York` from -5 to -4. Code that hardcodes UTC hour boundaries (e.g., `hour >= 22` for Asian session) will be wrong for half the year. Always use `ZoneId.of("America/New_York")` and let `java.time` handle DST transitions.

### What NOT to Do

- Do not call `LocalDate.now()` — use `LocalDate.now(TradingSessionResolver.CME_ZONE)` or `tradingSessionResolver.tradingDate(Instant.now())`
- Do not call `ZoneId.systemDefault()` — ever
- Do not store `LocalDateTime` in a database column
- Do not parse IBKR date strings assuming UTC unless the format includes hours
- Do not use `SimpleDateFormat` — use `java.time.format.DateTimeFormatter`
- Do not truncate Daily candles to midnight UTC
- Do not make session boundary hours configurable via properties (YAGNI until multi-exchange)

### Test Requirements for Time-Sensitive Code

Any change to date/time logic must include tests covering:

1. **Normal case** — mid-session tick (e.g., Tuesday 14:00 ET)
2. **Session boundary** — tick at exactly 17:00 ET
3. **DST spring forward** — second Sunday of March (e.g., 2026-03-08), where 02:00 ET becomes 03:00 ET
4. **DST fall back** — first Sunday of November (e.g., 2026-11-01), where 02:00 ET occurs twice
5. **Weekend boundary** — Friday 16:59 ET vs Sunday 17:01 ET
6. **Cross-midnight UTC** — tick at 23:30 UTC (= 18:30 ET winter / 19:30 ET summer) must belong to the correct trading day

## Review Checklist

When reviewing a change, ask:

- did business logic stay in `domain`?
- did use-case orchestration stay in `application`?
- did transport stay in `presentation`?
- did adapters stay in `infrastructure`?
- was a regression test or scenario added where appropriate?
- did the change preserve the IBKR/PostgreSQL-only data policy?
- did alert evaluation remain transition-based (not state-based)?
- are simultaneous alerts for the same signal still batched into one mentor review?
- does any new time-related code follow the Date, Time & Timezone Rules above?
- are `Instant` and `TIMESTAMPTZ` used consistently (no `LocalDateTime` in entities, no `TIMESTAMP` in DDL)?
- is `TradingSessionResolver` used for any trading-day boundary logic instead of UTC truncation or `CURRENT_DATE`?
