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
- when multiple indicators fire in the same polling cycle for the same instrument/timeframe/direction, the application layer batches them into a single mentor review via `captureGroupReview`
- individual alerts are still published to WebSocket separately for the UI

Do not:

- revert to state-based evaluation where alerts re-fire for persistent conditions
- create separate mentor reviews for alerts that fire simultaneously for the same trading signal
- move transition detection logic out of the domain layer

### Mentor Outcome Tracking Rule

When evaluating whether a saved Mentor trade plan was correct:

- keep the replay/simulation orchestration in `application`
- keep the simulation state as part of the persisted review model or a closely related persistence model
- source candles only from the internal PostgreSQL-backed candle repository
- do not recompute the original Mentor review payload during outcome evaluation
- use deterministic, pessimistic execution rules when candle granularity cannot disambiguate intrabar order

### Futures Contract Resolution Rule

When selecting a futures contract for live prices or IBKR historical requests:

- treat `MGC`, `MCL`, `E6`, `MNQ`, and `DXY` as root instruments with a discoverable contract chain
- do not hard-wire business flows to a permanent front-month conid
- keep the IBKR chain discovery and snapshot probing in `infrastructure`
- keep the selection policy explicit and testable
- prefer a tradable month over the chronologically nearest month when the front month is inside an IBKR close-out or delivery window
- for liquidity-aware instruments, allow a later month to win when bid/ask/volume data is materially better

Do not:

- assume `sorted by expiry -> first item` is sufficient for every futures family
- hide the selection reason inside unrelated controllers or UI code
- reintroduce external data to compensate for poor IBKR contract selection

When a user-facing or AI-facing payload needs an instrument label:

- derive it from the resolved active contract, not from a hard-coded continuous alias
- keep the normalization logic in a reusable application service so manual Mentor flows and automatic alert reviews stay aligned

### Active Candle Series Rule

When a workflow needs candles that must match the currently tradable futures contract:

- store `contractMonth` as candle metadata when the source provides it
- keep active-contract candle selection in an application service, not in controllers or raw repository calls
- use `ActiveContractCandleService` for indicator, mentor, chart, backtest, and simulation reads that must remain aligned with the active contract

Do not:

- assume `instrument + timeframe` alone identifies a unique futures series
- mix contract-resolution logic into presentation or persistence adapters
- rely on payload labels alone while reading an unqualified candle series underneath

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
