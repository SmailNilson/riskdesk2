# AI Handoff

Last updated: 2026-04-29

## Quant 7-Gates + AI Advisor (Tier 1 / Tier 2 split)

**Tier 1 — deterministic Java gates (always on).** Same engine as before:
seven gates lifted from `mnq_monitor_v3.py`, no LLM in the loop, scanned every
60 s for MNQ / MGC / MCL. See the section below for the gate table.

**Tier 2 — AI advisor (opt-in, rare).** When tier 1 reaches the trigger score
(6/7 by default), a single Gemini call enriches the verdict with:

- **Session memory** — same-day pattern observations + win-rate + last outcome
- **RAG** — top-5 nearest historical situations via pgvector cosine similarity (`<=>`)
- **Multi-instrument context** — current scores / day-moves for every other futures contract
- **Order-flow pattern** — the deterministic `OrderFlowPattern` classification
  (`ABSORPTION_HAUSSIERE`, `DISTRIBUTION_SILENCIEUSE`, `VRAIE_VENTE`,
  `VRAI_ACHAT`, `INDETERMINE`)

Cost stays low: ~10–20 calls/day per instrument max, plus a 30 s in-memory
cache deduplicates manual "Ask AI" double-clicks.

**Failure-mode contract.** The advisor never blocks tier 1. If Gemini is down,
if the API key is missing, or if pgvector is unavailable, the advisor adapter
returns `AiAdvice.unavailable(...)` and the gate panel keeps publishing
snapshots untouched.

**Deviation from spec.** The original spec called for the Vertex AI Java SDK
in `europe-west1` with context caching. RiskDesk does not yet ship the Vertex
AI dependency — the existing `GeminiMentorClient` + `GeminiEmbeddingClient`
talk directly to `generativelanguage.googleapis.com`. To stay shippable, the
adapter (`GeminiQuantAdvisorAdapter`) reuses that pattern. The
`AdvisorPort` contract stays Vertex-ready: a future swap is a single new
adapter class. Likewise, audit logs are SLF4J (instrument, score, verdict,
prompt-hash, latency) instead of GCP Cloud Logging.

**Frontend wiring.**

- `useQuantStream` now also subscribes to `/topic/quant/narration/{instr}` and
  `/topic/quant/advice/{instr}` (one STOMP client, three subscriptions per
  instrument)
- `QuantAdvisorBadge` renders the verdict with a colour token + tooltip
  (reasoning, risk, confidence, model)
- `QuantNarrationPanel` renders the markdown emitted by `QuantNarrator`
- A "Ask AI" button on `QuantGatePanel` triggers `POST /api/quant/ai-advice/{instr}`
  for an on-demand call

**New / extended files.**

- Domain: `domain/quant/pattern/{OrderFlowPattern, PatternAnalysis, OrderFlowPatternDetector}`,
  `domain/quant/narrative/QuantNarrator`,
  `domain/quant/advisor/{AdvisorPort, AiAdvice, MultiInstrumentContext}`,
  `domain/quant/memory/{SessionMemory, MemoryRecord, QuantMemoryPort}`
- Application: `application/quant/service/{QuantSetupNarrationService,
  QuantSessionMemoryService, QuantAiAdvisorService}`,
  `application/quant/adapter/GeminiQuantEmbeddingAdapter`
- Infrastructure: `infrastructure/quant/advisor/{QuantAdvisorPromptBuilder,
  GeminiQuantAdvisorAdapter}`, `infrastructure/quant/memory/QuantMemoryJdbcAdapter`
  (raw `JdbcTemplate` mirroring `MentorMemoryService`)
- Resources: `src/main/resources/prompts/quant-advisor.txt` (template loaded once at startup)
- Properties: `riskdesk.quant.advisor.enabled` (default `false`),
  `riskdesk.quant.ai-advice-trigger-score=6`,
  `riskdesk.quant.ai-advice-cache-seconds=30`,
  `riskdesk.quant.memory-rag-top-k=5`,
  `riskdesk.quant.advisor-model` (defaults to `riskdesk.mentor.model`),
  `riskdesk.quant.advisor-temperature=0.2`
- Tests: `OrderFlowPatternDetectorTest` (6), `QuantNarratorTest` (2),
  `QuantAiAdvisorServiceTest` (4 — cache TTL, context wiring, trigger
  threshold, failsafe). Integration test for the pgvector adapter is **not**
  included (would require a testcontainers + custom pgvector image — out of
  scope for this slice; the production code mirrors the proven
  `MentorMemoryService` pattern).

## Quant 7-Gates Order Flow Evaluator

Deterministic, framework-free SHORT-setup detector lifted verbatim from the
battle-tested `mnq_monitor_v3.py` Python script. No LLM in the loop — the seven
gates are pure functions over the order-flow stream, scanned every 60 s for
MNQ, MGC and MCL.

**Gates (G0..G6):**

| Gate | Rule (failure means setup invalid) |
| --- | --- |
| G0 Régime | day-move > +75 pts OR ≥3 ABS BULL scans (n8 ≥ 8) in the last 30 min |
| G1 ABS BEAR | n8 < 8, dom ≠ BEAR, or Δ > +500 (incoherent) |
| G2 DIST_pur 2/3 | fewer than 2/3 recent DIST scans ≥ 60 % (ACCU **never** counts toward this — Option B fix) |
| G3 Δ < -100 | spot delta not below −100 (bonus +TREND when strictly decreasing) |
| G4 buy% < 48 | buy ratio ≥ 48 % |
| G5 ACCU seuil | latest ACCU ≥ conditional threshold (50 / 65 / 75 depending on Δ + buy%) |
| G6 LIVE_PUSH | price source not `LIVE_PUSH` (stale snapshot) |

**Architecture (hexagonal, ArchUnit-enforced):**

- `domain/quant/model/` — pure records + enum (`Gate`, `GateResult`, `DistEntry`, `QuantState`, `MarketSnapshot`, `QuantSnapshot`, `LivePriceSnapshot`, `DeltaSnapshot`)
- `domain/quant/engine/GateEvaluator` — stateless pure function `evaluate(snap, state, instr) → Outcome(snapshot, nextState)`. Reset is per ET calendar day.
- `domain/quant/port/` — input/output ports (`AbsorptionPort`, `DistributionPort`, `CyclePort`, `DeltaPort`, `LivePricePort`, `QuantStatePort`, `QuantNotificationPort`)
- `application/quant/service/QuantGateService` — orchestrates parallel port fetch (CompletableFuture) + evaluator + state save + notify
- `application/quant/scheduling/QuantGateScheduler` — `@Scheduled(60_000)` calling `service.scan(instr)` for MNQ, MGC, MCL in parallel
- `application/quant/adapter/{Delta,LivePrice}PortAdapter` — bridges to existing `TickDataPort` and `MarketDataService`
- `infrastructure/quant/persistence/QuantState{Entity,JpaRepository,JpaAdapter}` — `quant_state` table, JSON-serialised history lists
- `infrastructure/quant/notification/QuantWebSocketAdapter` — STOMP topics `/topic/quant/snapshot/{instr}`, `/topic/quant/signals` (7/7), `/topic/quant/setups` (6/7)
- `infrastructure/quant/port/{Absorption,Distribution,Cycle}PortAdapter` — translate JPA event entities into domain signals
- `infrastructure/quant/QuantConfiguration` — exposes `GateEvaluator` as a Spring bean (kept out of the domain so it stays framework-free)
- `presentation/quant/QuantGateController` — `GET /api/quant/snapshot/{instr}` + `GET /api/quant/history/{instr}?hours=N`

**Frontend:** `frontend/app/components/quant/QuantGatePanel.tsx` is mounted in
the AI Trade Desk zone, subscribes to `/topic/quant/snapshot/{instr}` via the
new `useQuantStream` hook, and renders the 7 gates with ✅/❌ + reason. A
`QuantSetupNotification` component fires a one-shot WebAudio cue and a
toast when the backend confirms a 7/7 setup.

**State persistence:** `QuantState` lives in the `quant_state` table (PK =
instrument). The history lists (delta, dist_only, accu_only, abs_bull_scans)
are stored as JSON and rotated on every scan. The `/history` endpoint is
backed by an in-memory ring buffer (`QuantSnapshotHistoryStore`, capacity 240
entries per instrument ≈ 4 hours) — fine for dashboard playback, resets on
restart.

**Spec source-of-truth:** `mnq_monitor_v3.py` was the reference — every
threshold, window and reset rule mirrors that script. Coverage is in
`GateEvaluatorTest` (13 cases including the v2→v3 G2 dist/accu separation,
G5 conditional threshold paths, regime trap, and ET session reset).

## Hidden features surfaced — FALLBACK_DB badge, DXY breakdown, Flash Crash status, Rollover OI

Four backend capabilities that already existed but were not wired through to
the UI are now surfaced. Pure read-path additions — no new business logic, no
schema migration, no change to existing alert/execution/simulation flows.

- **FALLBACK_DB source badge (Slice A).** `MetricsBar.tsx` now renders per-ticker
  amber badges whenever `prices[instrument].source` is `FALLBACK_DB` or `STALE`.
  No new polling — the flag is already carried over the existing
  `/topic/prices` WebSocket payload (`PriceUpdate.source`). A trader can now see
  at-a-glance which instrument is being served from PostgreSQL because the IBKR
  farm is degraded.
- **DXY breakdown panel (Slice B).** `DxyPanel.tsx` gains a collapsed "Breakdown"
  section backed by the existing `GET /api/market/dxy/breakdown` endpoint (typed
  wrapper `api.getDxyBreakdown()`, DTO `FxComponentContributionView`). Refreshes
  every 30 s. Shows per-FX-pair price, % change, DXY weight, and weighted
  impact (colour-coded bullish/bearish/flat) so the trader can tell which of
  the 6 components is driving the index today.
- **Flash Crash status seed (Slice C).** `FlashCrashController#getStatus` is no
  longer a stub. It now returns the most recent persisted phase per instrument
  via a new application-layer `FlashCrashStatusService` reading from
  `JpaFlashCrashEventRepository.findFirstByInstrumentOrderByTimestampDesc`
  (hexagonal rule: controller cannot import the JPA repo directly, hence the
  service in between). `GET /api/order-flow/flash-crash/status` and
  `GET /api/order-flow/flash-crash/status/{instrument}` are both wired. The
  `LiveMonitorTab` in `FlashCrashPanel.tsx` seeds its state from REST on mount,
  and `/topic/flash-crash` WebSocket pushes continue to overlay live updates.
  Individual condition booleans are not persisted; the REST payload returns an
  empty `conditions` array and the card renders the 5-dot row without filled
  circles until a live WS push arrives.
- **Rollover OI status (Slice D).** `api.getRolloverOiStatus()` wraps the
  existing `GET /api/rollover/oi-status`. `RolloverBanner.tsx` now fetches OI
  crossover every 5 min (matching `useRollover`'s cadence) and, when any
  instrument's backend recommendation is `RECOMMEND_ROLL`, surfaces a red
  "OI crossover" row with current → next month, both open-interest values, the
  ratio, and a `SWITCH NOW` button that opens the existing confirm modal
  pre-filled with the next month. The banner now stays visible when an OI
  crossover is pending even if no contract is within the time-to-expiry window.

Files touched:
- Backend: `src/main/java/com/riskdesk/presentation/controller/FlashCrashController.java`,
  `src/main/java/com/riskdesk/application/service/FlashCrashStatusService.java` (new),
  `src/main/java/com/riskdesk/infrastructure/persistence/JpaFlashCrashEventRepository.java`.
- Frontend: `frontend/app/lib/api.ts` (4 new wrappers + DTOs),
  `frontend/app/components/MetricsBar.tsx`, `frontend/app/components/DxyPanel.tsx`,
  `frontend/app/components/FlashCrashPanel.tsx`, `frontend/app/components/RolloverBanner.tsx`.

Non-goals for this PR: no new business logic, no new FSM wiring (a live
per-instrument `FlashCrashFSM` engine feeding real ticks is still future work),
no changes to `useWebSocket` / `useOrderFlow` hook signatures, no Dashboard
layout changes.

## Goal of this file

This file captures the current engineering state so another agent can continue safely without rediscovering critical decisions.

## Latest change — Simulation Decoupling Phase 3 delivered

Phase 3 of the Simulation Decoupling Rule is in. The legacy coupling between
`MentorSignalReview` / `MentorAudit` and simulation state is now effectively
severed at the code level — only the physical columns remain (pending a
separate schema-migration PR).

**What changed in Phase 3:**

- `TradeSimulationService.refreshPendingSimulations()` and
  `refreshPendingAuditSimulations()` now read opens via
  `TradeSimulationRepositoryPort.findByStatuses(List.of(PENDING_ENTRY, ACTIVE))`.
  The previous entry points —
  `MentorSignalReviewRepositoryPort.findBySimulationStatuses(...)` and
  `MentorAuditRepositoryPort.findBySimulationStatuses(...)` — have been
  **removed** from both the port interfaces and the JPA adapters.
- The dual-write machinery is gone:
  `dualWriteSignalSimulation`, `dualWriteAuditSimulation`,
  `buildSignalCandidate`, `buildAuditCandidate`, `attemptDualWrite`,
  `reconcileWithExisting`, `retryPendingDualWrites`,
  `pendingDualWriteRetries`, `pendingDualWriteRetryCount`,
  `hasSimulationContentChanged`, `bigDecimalValueEquals` have all been
  deleted. Each scheduler pass now writes a single `TradeSimulation.save(...)`
  per transition.
- Initial `PENDING_ENTRY` creation moved off the legacy review row:
  - `MentorSignalReviewService.initializeSimulationAggregate()` writes a new
    row directly into `trade_simulations` when the ELIGIBLE analysis arrives
    with a complete trade plan.
  - `MentorAnalysisService.initializeAuditSimulation()` does the same for
    manual "Ask Mentor" audits.
  - Neither service calls `review.setSimulationStatus(...)` or
    `audit.setSimulationStatus(...)` anymore — those setters are write-never
    from production code paths.
- Simulation events publish only on `/topic/simulations`. The previous
  `/topic/mentor-alerts` push from the simulation scheduler is gone.
  `/topic/mentor-alerts` still carries non-simulation mentor review events
  (status/verdict/eligibility updates) via `MentorSignalReviewService.publish()`.
- Legacy sim getters/setters on `MentorSignalReviewRecord` and `MentorAudit`
  are annotated `@Deprecated(since = "phase-3")` with a Javadoc note
  pointing to `TradeSimulation`.

**What intentionally stayed (tech debt to close in a follow-up):**

- Physical columns on `mentor_signal_reviews` and `mentor_audits`
  (`simulation_status`, `activation_time`, `resolution_time`,
  `max_drawdown_points`, `trailing_stop_result`, `trailing_exit_price`,
  `best_favorable_price`) still exist on the JPA entities. This is because
  the repo has no Flyway/Liquibase and Hibernate `ddl-auto=update` never
  drops columns. The JPA mappers still round-trip them so historical rows
  remain readable. Dropping these columns needs a schema-migration strategy
  PR — do not remove them inline.

**Tests reshuffled:**

- `TradeSimulationServiceDualWriteTest` deleted.
- New `TradeSimulationServiceSchedulerTest` (7 tests) asserts the post-Phase-3
  contract: scheduler reads from the simulation port, writes only to the
  simulation aggregate, publishes only on `/topic/simulations`, and never
  touches the legacy review/audit `save(...)` path for simulation transitions.
- `MentorAnalysisServiceTest.analyze_eligibleWithFullPlan_*` rewritten — it
  now asserts a `TradeSimulation(AUDIT, PENDING_ENTRY)` row is persisted and
  the audit legacy sim field stays null.
- `MentorSignalReviewService` constructor extended with
  `TradeSimulationRepositoryPort`; all test call sites updated.
- Full suite: 1191 tests, all green. `HexagonalArchitectureTest` passes.

**Follow-up PRs needed:**

1. Introduce Flyway (or Liquibase), then drop the seven legacy sim columns
   from `mentor_signal_reviews` and `mentor_audits`. At that point the
   `@Deprecated` getters can disappear too.
2. Optional: extend the simulation REST API with a writable endpoint so an
   operator can manually cancel a stuck `PENDING_ENTRY` (previously possible
   via a direct UPDATE on the review table).

---

## Previous change — Execution Slice 3a: IBKR execDetails + orderStatus fill tracking

Adds raw IBKR broker feedback to live executions. Slice 3 is split into three
sub-slices; this PR ships **3a only**.

**What ships now (3a):**
- New domain port `domain/execution/port/ExecutionFillListener` — callback sink
  implemented by the application layer. Keeps the hexagonal boundary intact
  (infrastructure doesn't import application types).
- New application service `application/service/ExecutionFillTrackingService`
  persists IBKR feedback on `TradeExecutionEntity` and publishes
  `/topic/executions` on every state-changing update.
- `TradeExecutionEntity` + `TradeExecutionRecord` extended with fill-tracking
  columns (additive only, nothing removed):
  - `filledQuantity` / `avgFillPrice` (BigDecimal)
  - `lastFillTime` (Instant / TIMESTAMPTZ)
  - `orderStatus` (raw IBKR status name — `Submitted`, `PreSubmitted`,
    `PartiallyFilled`, `Filled`, `Cancelled`, …)
  - `ibkrOrderId` (Integer — TWS orderId, used by 3b for startup reconciliation)
  - `lastExecId` (String — per-fill idempotence key, IBKR execId of last
    applied fill report)
- `TradeExecutionRepositoryPort` gains `findByIbkrOrderId(...)` +
  `findByExecutionKey(...)`.
- `IbGatewayNativeClient` now accepts an `ExecutionFillListener` via
  `setExecutionFillListener(...)`. On connect it attaches two persistent
  handlers to `ApiController`:
  - `ITradeReportHandler` backed by `reqExecutions(filter, handler)` — scoped
    to today's executions — forwards `execDetails()` callbacks.
  - `ILiveOrderHandler` backed by `reqLiveOrders(handler)` — forwards
    `orderStatus()` callbacks. (The existing ad-hoc `findOpenOrderByOrderRef`
    handler remains untouched — the two handlers coexist.)
- Wiring in `MarketDataConfig#ibGatewayExecutionFillListenerWiring` (same
  `IB_GATEWAY` conditional as the price listener).
- Idempotence: `execDetails` dedups on `execId`; `orderStatus` dedups by
  comparing all raw fields against the persisted row. Transition from
  `ENTRY_SUBMITTED` to domain state `ACTIVE` happens on first `Filled` status.
  Cancellation only flips to `CANCELLED` when no fills have been recorded —
  partial-fill-then-cancel stays open and is handled in 3c.
- Existing endpoint `GET /api/mentor/executions/by-review/{reviewId}` now
  surfaces the new fill fields on `TradeExecutionView` (shape additions
  only — existing consumers unaffected).

**Tests:** `ExecutionFillTrackingServiceTest` — 8 Mockito scenarios covering
happy path, execId replay idempotence, orderRef fallback, domain state
transition on `Filled`, cancel-without-fills flow, unchanged-status no-op,
unknown-order ignore, and multi-fill sequence.

**What this PR does NOT do (follow-ups):**
- **3b — startup reconciliation:** on app start, query IBKR open/completed
  orders and reconcile against `trade_executions` rows left dangling from a
  prior run.
- **3c — bracket / virtual exit orchestration:** once `ACTIVE`, submit
  matching SL + TP orders, monitor and update state through closure.
- Frontend UI for the new fields — the DTO shape is exposed so a future UI
  PR can surface it without touching the backend again.

---

## Earlier change — Simulation Decoupling Phase 1 (a + b)

The TECH DEBT around simulation state living on `MentorSignalReviewRecord` /
`MentorAudit` is now being unwound. Phase 1 is **additive only** — no legacy
field, endpoint, or WebSocket topic has been removed yet.

**Phase 1a (foundation, already merged — PR #253):**
- Domain aggregate `domain/simulation/TradeSimulation` (pure record, no Spring/JPA)
- Discriminator enum `domain/simulation/ReviewType` — `SIGNAL` | `AUDIT`
- Port `domain/simulation/port/TradeSimulationRepositoryPort`
- JPA side: `TradeSimulationEntity` (table `trade_simulations`), `JpaTradeSimulationRepository`, `JpaTradeSimulationRepositoryAdapter`, `TradeSimulationEntityMapper`

**Phase 1b (wire-up, this PR):**
- `TradeSimulationService` now injects `TradeSimulationRepositoryPort` and
  **dual-writes** every state transition to the new port IN ADDITION to the
  legacy review/audit repositories. Dual-write is wrapped in try/catch + warn
  log so a new-side failure cannot break the legacy flow.
- A back-fill pass at the top of each scheduler run ensures reviews created
  by `MentorSignalReviewService.initializeSimulationState()` get their
  `trade_simulations` row on the next poll, even without a state change.
  Back-fill is idempotent via the `(review_id, review_type)` unique constraint.
- New REST endpoints on `SimulationController`:
  - `GET /api/simulations/recent?limit=50`
  - `GET /api/simulations/by-instrument/{instrument}?limit=20`
  - `GET /api/simulations/by-review/{reviewId}?type=SIGNAL|AUDIT` (404 if missing)
- New WebSocket topic `/topic/simulations` — published alongside (not instead
  of) the legacy `/topic/mentor-alerts` push. Payload is the `TradeSimulation`
  domain aggregate.
- Frontend `app/lib/api.ts` exposes typed wrappers
  (`getRecentSimulations`, `getSimulationsByInstrument`, `getSimulationByReview`)
  and `TradeSimulationView` type. **No UI component has been migrated yet** —
  Phase 2 will swap `MentorSignalPanel` / `MentorPanel` to read from these
  endpoints and subscribe to `/topic/simulations`.

**What is NOT changed by Phase 1:**
- Simulation fields on `MentorSignalReviewRecord` / `MentorAudit` are still the
  primary source of truth. `TradeSimulationService` still writes to them.
- `/topic/mentor-alerts` still carries simulation updates.
- `MentorSignalReviewRepositoryPort.findBySimulationStatuses()` is still the
  scheduler's entry point.

**Phase 2 plan (next PR):** migrate frontend (`MentorSignalPanel`,
`MentorPanel`) to consume `/api/simulations/*` + `/topic/simulations`; drop
simulation fields from `MentorSignalReview` / `MentorManualReview` JSON DTOs.

**Phase 3 plan (follow-up):** drop simulation columns from `mentor_signal_reviews`
and `mentor_audits`; remove `findBySimulationStatuses()` from the review port;
make `TradeSimulationRepositoryPort` the sole query path. Requires a data
backfill script and a deprecation release.

**Tests:** new dual-write suite `TradeSimulationServiceDualWriteTest` (5 tests),
new controller test `SimulationControllerTest` (8 tests). Existing
`TradeSimulationServiceTest` updated for the new constructor arg. Hex-arch
tests and full suite green (1188 tests, all passing).

## Previous change — Probabilistic Strategy Engine (Slices S1 + S2)

A new top-down decision funnel has been added alongside the legacy 7/7 Playbook. It
is **read-only in this slice** — no execution, no persistence, no WebSocket push.
The legacy `PlaybookEvaluator`, `SignalConfluenceBuffer` and `ExecutionManagerService`
are **untouched**; both engines run side by side so a/b comparison is possible.

**New packages:**
- `domain/engine/strategy/` — pure domain, framework-free
  - `model/` — records: `MarketContext`, `ZoneContext`, `TriggerContext`,
    `AgentVote`, `StrategyInput`, `StrategyDecision`, `MechanicalPlan`,
    `OrderBlockZone`, `FvgZone`, `LiquidityLevel`; enums: `StrategyLayer`,
    `MacroBias`, `MarketRegime`, `PriceLocation`, `PdZone`, `DeltaSignature`,
    `DomSignal`, `TickDataQuality`, `ReactionPattern`, `DecisionType`
  - `agent/` — `StrategyAgent` port + 7 pilot agents (3 CONTEXT, 2 ZONE, 2 TRIGGER)
  - `playbook/` — `Playbook` port + `LsarPlaybook` + `SbdrPlaybook` + `PlaybookSelector`
  - `policy/StrategyScoringPolicy` — Bayesian per-layer aggregation with veto &
    inter-layer coherence gates
  - `DefaultStrategyEngine` — pure-domain wiring
- `application/service/strategy/` — builders + `StrategyEngineService`
- `presentation/controller/StrategyController` → `GET /api/strategy/{instrument}/{timeframe}`
- `presentation/dto/StrategyDecisionView` — Optional-flattened JSON shape
- `infrastructure/config/StrategyEngineConfig` — Spring wiring (agents + playbooks + Clock)
- Frontend `components/StrategyPanel.tsx` — dedicated STRATEGY tab in `AiMentorDesk`

**Scoring doctrine (CLAUDE.md-aligned):** `0.50 × CONTEXT + 0.30 × ZONE + 0.20 × TRIGGER`.
Abstain ≠ neutral vote — agents lacking data drop out of the denominator rather
than pulling the score to zero.

**Thresholds (in `StrategyScoringPolicy`):**
- `|score| < 30` → `NO_TRADE`
- `|score| < playbook.min` → `PAPER_TRADE`
- `|score| < playbook.min + 20` → `HALF_SIZE`
- else → `FULL_SIZE`
- Any agent `vetoReason` → forces `NO_TRADE` regardless of score
- Inter-layer coherence: if `sign(CONTEXT) ≠ sign(TRIGGER)` and `|score| < 70` →
  `MONITORING` (this is the gate that catches "SMC says LONG but flow says SHORT")

**Playbook minima:** LSAR = 55 (reversal), SBDR = 65 (trend-continuation).

**Tests:** 29 new tests under `src/test/java/com/riskdesk/domain/engine/strategy/`
plus `architecture/StrategyLayerIsolationTest` enforcing package discipline.
Total suite: **1040 tests, all passing**.

**Pending — next slices:**
- **S3** — wire the legacy agents (`MtfConfluenceAIAgent` etc.) into
  `StrategyAgent` so only one agent abstraction remains. Route Mentor Gemini
  through `StrategyDecision.evidence` instead of the verdict text.
- **S4** — swap `ExecutionManagerService` off `executionEligibilityStatus` onto
  `StrategyDecision.decision`. Needs a feature flag
  `riskdesk.strategy.new-engine.enabled` for per-instrument rollout.

## Important Decisions Already Made

### Architecture policy

The project should continue to respect:

- DDD
- TDD
- BDD
- Hexagonal Architecture

Agents should preserve the intended layer split:

- `presentation`
- `application`
- `domain`
- `infrastructure`

Do not collapse these boundaries for convenience.

### Market data policy

External providers were intentionally removed from the active data path.

The only accepted data path is:

`IBKR Gateway -> PostgreSQL -> internal services`

Do not reintroduce Yahoo, Stooq, Alpha Vantage, Polygon, Binance, scraping, or simulated providers as production fallback.

### Mentor intermarket behavior

The mentor intermarket service was changed to avoid external HTTP lookups.

Current behavior when internal data is not available:

- correlation values may be `null`
- convergence status can be `"UNAVAILABLE"`

This is expected until internal IBKR/DB-backed inputs exist.

## Recent Technical Changes

### 0. Synthetic DXY replaced the delayed `DX/ICEUS` contract path

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/DxyMarketService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/domain/marketdata/model/SyntheticDollarIndexCalculator.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayFxQuoteProvider.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayFxContractResolver.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/persistence/entity/MarketDxySnapshotEntity.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/presentation/controller/DxyMarketController.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/DxyPanel.tsx`

What changed:

- `DXY` is now computed internally from six IBKR FX quotes (`EURUSD`, `USDJPY`, `GBPUSD`, `USDCAD`, `USDSEK`, `USDCHF`) instead of resolving the delayed `DX` future on `ICEUS`
- the calculator prefers `mid=(bid+ask)/2`, falls back to `last`, rejects missing/non-positive values, and rejects snapshots whose component timestamps drift by more than `15s`
- complete snapshots are persisted into `market_dxy_snapshots` with all six FX inputs, the computed DXY value, source, and completeness flag
- `MarketDataService` refreshes the synthetic DXY on the existing poll cycle and publishes `DXY` to `/topic/prices`
- `MentorIntermarketService` now reads only from the synthetic DXY service and uses persisted DXY snapshots for its `10m` / `1h` baseline lookup
- `DXY` is excluded from futures-specific loops such as active-contract resolution, rollover detection, and futures historical refresh
- dedicated REST endpoints now exist at `/api/market/dxy/latest`, `/api/market/dxy/history`, and `/api/market/dxy/health`
- `CLIENT_PORTAL` mode does not serve these DXY endpoints; synthetic DXY is only supported in `IB_GATEWAY` mode

Why:

- the `DX/ICEUS` path was delayed and unsuitable for an internal dollar-index signal used by trading workflows
- the repo already had the required IBKR connectivity, so the correct fix was to stay inside the existing `IBKR Gateway -> PostgreSQL -> internal services` path

### 1. GitHub-hosted Docker image pipeline

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/.github/workflows/docker-image.yml`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/.dockerignore`

What changed:

- Docker image validation now runs in GitHub Actions for `push` to `main` and pull requests targeting `main`
- Docker image publication now runs in GitHub Actions for Git tag pushes
- manual publication is available through `workflow_dispatch` with a `git_tag` input for tags that already exist
- images are published to `ghcr.io/smailnilson/riskdesk2`
- stable tags also refresh the `latest` container tag
- tagged releases can now also be deployed from GitHub Actions over SSH via `docker-compose.release.yml` on the server
- the deploy workflow expects GitHub Actions secrets `DEPLOY_HOST`, `DEPLOY_USER`, `DEPLOY_SSH_KEY`, `DEPLOY_PATH`, `GHCR_USERNAME`, and `GHCR_TOKEN`
- the private IBKR `tws-api` dependency is vendored in `vendor/maven-repo` so Docker and GitHub Actions builds can resolve it without a developer-local Maven cache

Why:

- release publishing should not depend on running `docker build` locally
- tag-based publishing keeps the release image aligned with an immutable Git ref

### 2. Native IBKR client stabilization

File:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayNativeClient.java`

What changed:

- reconnect cooldown added
- failed connect attempts no longer thrash reconnects
- controller state is cleared on disconnect/error
- connect errors `326`, `502`, and `504` now trigger a cleaner failure path

Why:

- IB Gateway logs showed repeated `client id already in use` and connection churn

### 3. Direct preconfigured futures contract resolution

File:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayContractResolver.java`

What changed:

- for known futures instruments, the resolver can use a preconfigured IBKR contract directly via `conid`
- this reduces dependency on `reqContractDetails` on the live-path critical section

### 4. Live provider path simplified

File:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbGatewayMarketDataProvider.java`

What changed:

- live snapshot lookup no longer retries into discovery refresh on the hot path

Why:

- when IBKR itself is unhealthy, rediscovery only adds noise and timeouts

### 5. Mentor persisted review threads for qualified alerts

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/MentorSignalPanel.tsx`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/lib/mentor.ts`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/MentorPanel.tsx`

What changed:

- selected trading alerts now capture a frozen Mentor payload snapshot at alert time on the backend
- the first Mentor review for that alert is saved in PostgreSQL and broadcast to the UI
- each alert now owns a persisted review thread keyed by the exact alert occurrence
- opening an alert in the panel reads the saved thread only; it does not recompute current market context
- manual reanalysis is explicit through `Reanalyse`, which appends a new saved revision to the same thread
- `/api/mentor/auto-alerts/recent` exposes persisted recent reviews
- `/api/mentor/auto-alerts/thread` returns the saved thread for one alert
- `/api/mentor/auto-alerts/reanalyze` appends a new review revision using a fresh live payload plus `original_alert_context`
- `/topic/mentor-alerts` pushes thread updates to the frontend
- manual `Ask Mentor` runs are intentionally separate from alert reviews and can be listed via `/api/mentor/manual-reviews/recent`

Qualified alert families:

- `SMC` with `BOS` or `CHoCH`
- `MACD` bullish/bearish cross
- `WAVETREND` bullish/bearish cross and overbought/oversold
- `RSI` overbought/oversold
- `ORDER_BLOCK` when VWAP is inside the block

Important behavior:

- the auto-review flow keeps portfolio context disabled
- the panel is intended for trade-conformance review, not account-risk judgment
- the initial review is snapshot-based and no longer depends on click-time market state
- manual reanalysis does rebuild current indicators/candles/live price, but also passes the original alert time/reason/price to Gemini for comparison

### 6. Mentor trade outcome tracker

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/TradeSimulationService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/domain/model/TradeSimulationStatus.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/persistence/entity/MentorSignalReviewEntity.java`

What changed:

- saved alert reviews now carry simulation fields for post-trade outcome tracking
- valid reviews with a complete `Entry / SL / TP` plan are initialized as `PENDING_ENTRY`
- invalid or incomplete plans are initialized as `CANCELLED`
- a scheduled backend service replays subsequent `1m` candles from PostgreSQL and updates the review outcome asynchronously

Simulation rules:

- before entry:
  - if `TP` is touched before the limit `Entry`, the result becomes `MISSED`
  - if the limit `Entry` is touched, the trade becomes `ACTIVE`
- after entry:
  - `SL` resolves to `LOSS`
  - `TP` resolves to `WIN`
- if one candle crosses both `SL` and `TP`, the result is recorded as `LOSS` pessimistically
- `maxDrawdownPoints` records the worst adverse excursion before resolution

Operational note:

- this uses the existing internal candle repository only
- no external replay feed or provider is involved

### 7. Transition-based alert evaluation and grouped reviews

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/domain/alert/service/IndicatorAlertEvaluator.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/AlertService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/MentorSignalReviewService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/MentorSignalPanel.tsx`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/frontend/app/components/Dashboard.tsx`

What changed:

- `IndicatorAlertEvaluator` switched from state-based to transition-based detection: alerts only fire when a condition *changes*, not when it persists across polling cycles
- uses a `ConcurrentHashMap<String, String>` to track last-known state per indicator/instrument/timeframe
- `AlertService` now publishes individual alerts to WebSocket but batches Mentor reviews by direction for alerts that fire in the same polling cycle
- indicator alert dedup is now key-based on the shared short cooldown only; it no longer blocks a full `10m` or `1h` window, so multiple same-timeframe alerts can surface when new transitions occur
- `MentorSignalReviewService.captureGroupReview()` groups alerts by direction and creates one combined review per group
- `MentorSignalPanel` groups alerts in the UI by instrument+timeframe+direction within a 90-second time window
- added instrument filter dropdown to `MentorSignalPanel`
- removed AI JSON export button and bottom `AlertsFeed` ticker from `Dashboard`

Why:

- persistent conditions (e.g., RSI overbought, BOS) were re-firing every 300s (dedup cooldown) across all instruments simultaneously
- multiple indicators reacting to the same market move at the same time should produce one combined review, not N separate reviews

### 8. Execution foundation for real IBKR workflow

Files:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/application/service/ExecutionManagerService.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/persistence/entity/TradeExecutionEntity.java`
- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/java/com/riskdesk/infrastructure/persistence/JpaTradeExecutionRepositoryAdapter.java`

What changed:

- introduced a dedicated `trade_executions` table instead of extending `mentor_signal_reviews` again
- idempotence is now defined per persisted Mentor review ID (`mentorSignalReviewId`)
- `ExecutionManagerService.ensureExecutionCreated()` creates a pending execution foundation row only
- `TradeExecutionRecord` now freezes `quantity` at arming time; this is required before any live broker submission
- `MentorController` now exposes:
  - `POST /api/mentor/executions`
  - `GET /api/mentor/executions/by-review/{mentorSignalReviewId}`
  - `POST /api/mentor/executions/by-review-ids`
- `MentorController` now also exposes `POST /api/mentor/executions/{executionId}/submit-entry`
- `MentorSignalPanel` now supports manual Slice 1 arming against the selected IBKR account and displays the persisted execution state
- Slice 2 now submits a simple IBKR limit entry order through the native gateway adapter
- full fill/re-sync/virtual exit orchestration does not exist yet
- `MentorSignalReview` persistence now stores explicit execution eligibility metadata:
  - `executionEligibilityStatus`
  - `executionEligibilityReason`

Why:

- live execution lifecycle is orthogonal to review persistence and simulation persistence
- one review must never create duplicate live executions under retries, restart replays, or double clicks
- execution gating must not depend on parsing the verdict string in UI code

Operational note:

- the new eligibility field on historical rows may be `null`; treat those rows as legacy, display-only records until they are reanalyzed
- the UI no longer needs to parse verdict text to decide whether a review is execution-eligible
- legacy `trade_executions` rows created before `quantity` existed may need enrichment before they can be submitted live

## Known Runtime Behavior

### Current good news

- the reconnect storm around `clientId=7` appears fixed at the application level
- IB Gateway runs on `riskdesk-prod` (`100.113.139.64:4003`) via Tailscale — NOT on localhost
- local `application-local.properties` must set `riskdesk.ibkr.native-host=100.113.139.64` and `riskdesk.ibkr.native-port=4003`
- verify Tailscale connectivity: `tailscale status | grep riskdesk-prod` and `nc -z 100.113.139.64 4003`

### Current remaining issue

Live prices can still return DB fallback instead of native live values when IBKR market data farms are down.

Observed symptoms:

- `/api/live-price/E6` returning:
  - `source = FALLBACK_DB`
- IB Gateway messages such as:
  - `2110`
  - `2103`
  - `2105`
  - `2157`
- native snapshot requests timing out

Interpretation:

- this is an upstream IBKR runtime/connectivity condition
- it is not a reason to add external market data providers

## Verification Commands Used Recently

```bash
mvn -q -DskipTests compile
cd frontend && npm run lint
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/live-price/E6
lsof -nP -iTCP:4001
```

## What an Incoming Agent Should Check First

1. Read `AGENTS.md`
2. Read `docs/PROJECT_CONTEXT.md`
3. Read `docs/ARCHITECTURE_PRINCIPLES.md`
4. Confirm `application.properties` still points to `IB_GATEWAY`
5. Confirm no external market data provider was reintroduced
6. Re-run backend compile and frontend lint before deeper edits

### 9. Mentor IA v2 — per-asset-class payloads, tick data, decision hierarchy

Files:

- `src/main/java/com/riskdesk/domain/model/AssetClass.java` (new)
- `src/main/java/com/riskdesk/domain/engine/smc/SessionPdArrayCalculator.java` (new)
- `src/main/java/com/riskdesk/domain/engine/indicators/MarketRegimeDetector.java` (new)
- `src/main/java/com/riskdesk/domain/marketdata/port/TickDataPort.java` (new)
- `src/main/java/com/riskdesk/domain/marketdata/model/TickAggregation.java` (new)
- `src/main/java/com/riskdesk/application/dto/MacroCorrelationSnapshot.java` (new)
- `src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/TickByTickAggregator.java` (new)
- `src/main/java/com/riskdesk/infrastructure/marketdata/ibkr/IbkrTickDataAdapter.java` (new)
- `src/main/java/com/riskdesk/application/service/MentorSignalReviewService.java` (modified)
- `src/main/java/com/riskdesk/application/service/GeminiMentorClient.java` (modified)
- `src/main/java/com/riskdesk/application/service/MentorIntermarketService.java` (modified)
- `frontend/app/lib/mentor.ts` (modified)
- `frontend/app/components/MentorSignalPanel.tsx` (modified)
- `frontend/app/lib/api.ts` (modified)

What changed:

- `AssetClass` enum (METALS, ENERGY, FOREX, EQUITY_INDEX) added to domain layer with per-class macro correlation kinds and sector leader mappings
- `Instrument.assetClass()` maps each instrument to its asset class (DXY returns null)
- `SessionPdArrayCalculator` computes intraday Premium/Discount/Equilibrium zones from session high/low
- `MarketRegimeDetector` detects TRENDING_UP/DOWN, RANGING, CHOPPY from EMA alignment + BB width
- `TickDataPort` + `TickAggregation` provide a domain abstraction for real-time order flow
- `TickByTickAggregator` aggregates classified ticks in a rolling 5-minute window with Lee-Ready buy/sell classification and delta divergence detection
- `IbkrTickDataAdapter` implements TickDataPort; falls back to CLV estimation when tick data unavailable
- Mentor payload restructured: sections renamed (market_structure_smc, momentum_oscillators, etc.), enriched with PD Array (session + structural), liquidity pools (EQH/EQL), Bollinger state, CMF, order flow with source tracking
- `MacroCorrelationSnapshot` replaces per-instrument intermarket fields with per-asset-class fields (sector leader, VIX, US10Y)
- `GeminiMentorClient` system prompt is now dynamic per asset class with strict decision hierarchy (Structure 50% > Order Flow 30% > Momentum 20%), rejection rules, winning patterns (Catch-up Trade, Kill Zone, CHoCH Pullback), and Entry/SL/TP formulas
- Frontend updated: mentor.ts payload mirrors backend structure, MentorSignalPanel shows color-coded asset class badges

Important behavior:

- the `order_flow_and_volume` payload section includes a `source` field ("REAL_TICKS" or "CLV_ESTIMATED") so Gemini knows the data quality
- macro correlation fields for VIX, US10Y, Silver are null with `data_availability: "DXY_ONLY"` until IBKR subscriptions for those instruments are added
- the decision hierarchy is enforced only in the Gemini system prompt, not as Java pre-scoring gates
- `IbGatewayNativeClient` does NOT yet call `reqTickByTickData()` — the TickByTickAggregator and IbkrTickDataAdapter are ready to receive ticks but the subscription wiring in the native client is a future slice

Why:

- universal payload with no asset class differentiation produced generic Gemini verdicts that didn't account for metals-specific correlations (Silver leader) or equity-index-specific risks (VIX spikes)
- CLV-based delta estimation lacks precision for real order flow analysis; the tick infrastructure prepares for real data
- the decision hierarchy prevents Gemini from weighting contradictory indicators equally

## Suggested Next Improvements

- wire `reqTickByTickData("AllLast")` into `IbGatewayNativeClient` and connect to `IbkrTickDataAdapter.onTickByTickTrade()`
- add IBKR subscriptions for VIX (IND/CBOE), ZN/US10Y (FUT/CBOT), SI (FUT/COMEX) to populate macro correlation fields
- compute session PD Array in `IndicatorService.computeSnapshot()` using session candles from `TradingSessionResolver`
- wire `MarketRegimeDetector` into the payload (add `market_regime_context` section to `buildPayload()`)
- surface the reason for `FALLBACK_DB` in API/UI
- add explicit health/status around IBKR live market data farm availability
- expose the simulation outcome in the UI once the product design is ready
- add tests for transition-based alert evaluation edge cases
