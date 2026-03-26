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

## Review Checklist

When reviewing a change, ask:

- did business logic stay in `domain`?
- did use-case orchestration stay in `application`?
- did transport stay in `presentation`?
- did adapters stay in `infrastructure`?
- was a regression test or scenario added where appropriate?
- did the change preserve the IBKR/PostgreSQL-only data policy?
