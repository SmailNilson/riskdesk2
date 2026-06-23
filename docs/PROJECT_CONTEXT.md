# Project Context

## What this project is

`riskdesk2` is a futures trading risk dashboard combining:

- portfolio and position monitoring
- indicator computation
- mentor-style analysis payload generation
- IBKR account and market data integration

The stack is split into a Spring Boot backend and a Next.js frontend.

## Architectural Standard

This project should be maintained as:

- DDD-oriented
- test-first where practical
- BDD-friendly for end-to-end business workflows
- hexagonal in backend structure

The target conceptual backend layers are:

- `presentation`
- `application`
- `domain`
- `infrastructure`

Supporting detail lives in:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/ARCHITECTURE_PRINCIPLES.md`

## Source of Truth for Market Data

The only accepted market data flow is:

`IBKR Gateway -> PostgreSQL -> internal services`

This rule is intentional and strict.

### Implications

- live prices should come from the IBKR native gateway when available
- historical candles should come from the IBKR/PostgreSQL pipeline
- PostgreSQL fallback is acceptable when IBKR is temporarily unavailable
- external providers must not be introduced as “temporary fixes”
- **deep 1m history for backtests** is seeded via the admin range backfill
  (`POST /api/candles/backfill/{instrument}/{timeframe}?from=ISO&to=ISO`), still IBKR-only and
  idempotent (skips bars already present); it walks backward `to → from` across contracts with IBKR
  pacing. Read it back over the 1000-candle cap with the cursor-paginated
  `GET /api/candles/{instrument}/{timeframe}/range?from=ISO&to=ISO&limit=N` (`nextFrom` cursor).
  The range read returns **raw** candles (no session purge / contract-month filter) so the series
  matches exactly what the backtest engine consumes.

### Synthetic Dollar Index

`DXY` is no longer sourced from the `DX` future on `ICEUS`.

Current behavior:

- `DXY` is an internal synthetic market series computed from six IBKR FX quotes on `IDEALPRO`
- the pricing inputs are `EURUSD`, `USDJPY`, `GBPUSD`, `USDCAD`, `USDSEK`, and `USDCHF`
- live computation prefers `mid=(bid+ask)/2`, then falls back to `last` when bid/ask are incomplete
- only complete, valid, time-coherent snapshots are persisted
- persisted snapshots live in `market_dxy_snapshots`
- historical DXY reads now come from that dedicated snapshot table, not from the generic `candles` table
- `DXY` remains a business instrument symbol but must be excluded from futures contract rollover, active-contract fallback, and IBKR futures contract resolution
- `CLIENT_PORTAL` mode does not support synthetic DXY; the dedicated DXY endpoints are intentionally unavailable there

## Main Runtime Config

See:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/src/main/resources/application.properties`

Important settings today:

- backend port: `8080`
- PostgreSQL DB: `riskdesk`
- IBKR mode: `IB_GATEWAY`
- native host: a reachable IB Gateway (see note — NOT localhost)
- native port: `4003`
- native client id: `7`

Note: prod now runs on **`riskdesk-prod-v2`** (Tailscale `100.69.177.128`, API/nginx `:3000`); the old `riskdesk-prod` (`100.113.139.64`) is **deprecated** (Tailscale idle). On prod the IB Gateway runs as a Docker container reached internally as `ibkr-gateway:4003` (confirm via `GET /api/ibkr/auth/status` → `socket://ibkr-gateway:4003`) — it is **NOT** exposed on the host's Tailscale IP (`nc -z 100.69.177.128 4003` fails). For local dev, point `native-host` at a gateway you can reach and verify with `nc -z <host> 4003`.

## Environment Variables Used

The application reads these from environment when present:

- `TELEGRAM_BOT_TOKEN`
- `TELEGRAM_CHAT_ID`
- `GEMINI_API_KEY`
- `GEMINI_MODEL`
- `GEMINI_EMBEDDING_MODEL`

These must stay out of Git.

## Backend Map

### Entry points

- `presentation/controller`
- `presentation/dto`

These should stay transport-oriented only.

### Core services

- `application/service/MarketDataService.java`
- `application/service/HistoricalDataService.java`
- `application/service/OrderFlowOrchestrator.java` — owns the tick-by-tick / depth subscription lifecycle and `/topic/order-flow|depth|...` publication. Tick + depth default to `MNQ,MCL` (`MGC,E6` are `degraded-instruments`, surfaced as `DEGRADED_NOT_SUBSCRIBED`). `publishOrderFlowMetrics` (5s) emits a server-authoritative staleness **heartbeat** (`serverStale`, `feedHealth`, last-genuine `dataTimestamp`) on quiet windows. `checkDeltaFreshness` (15s) resubscribes a tick line whose last **classified** tick is stale, through the shared `TickByTickClient.allowResubscribe` rate cap (single owner of resubscription). `/api/order-flow/status` now also reports `classifiedTicksReceived` (vs raw `totalTicksReceived` — the gap = UNCLASSIFIED drops), per-instrument `classifiedTickAgeSec`, and feed health (`REAL_TICKS` / `REAL_TICKS_TICKRULE` / `STARVED` / `DEGRADED_NOT_SUBSCRIBED`) — the same `feedHealthFor` vocabulary the `/topic/order-flow` heartbeat uses, so REST and WS never diverge. The `/topic/order-flow` payload's `cumulativeDelta` is the **session-anchored CVD** (RTH anchor 09:30 ET inside RTH, else Globex-day 17:00 ET; the `cvdAnchor` field says which), plus speed-of-tape fields (`tapeSpeed5s/30s`, `tapeZ5s/30s`, `tapeRatio5s` — z-scored vs a rolling 30-min baseline) and `bigPrintDelta5m`. The 5s pass also feeds per-instrument `CvdDivergenceDetector`s (1m bars built from samples, 5L/5R pivot confirmation) → `/topic/cvd-divergence`, and an `@EventListener` relays `BigPrintDetected` (from `IbkrBigPrintAdapter`, prints ≥ p99 with floor 10, rate-limited 1/sec/instrument) → `/topic/big-prints`. Neither new topic is persisted.
- `application/service/PositionService.java`
- `application/service/AlertService.java` — publishes indicator alerts and triggers a **unitary** Mentor capture per qualified directional alert. The `SignalConfluenceBuffer` (Engine v2) was deleted, so there is no weighted accumulation/consolidation.
- `application/service/MentorAnalysisService.java`
- `application/service/MentorSignalReviewService.java` — capture + cleanup scheduler **gated by `riskdesk.mentor.enabled`** (early-return when off, default off).
- `application/service/MentorIntermarketService.java`
- `application/service/TradeSimulationService.java` — sole owner of simulation state transitions. Post Phase 3 it reads open simulations via `TradeSimulationRepositoryPort.findByStatuses(...)` and writes exclusively to the simulation aggregate. No legacy sim write path remains. All three `@Scheduled` pollers are **gated by `riskdesk.mentor.enabled`** (no 60s polling when off).
- `application/service/ExecutionManagerService.java` — arming + entry submission for live executions. **Dormant**: HTTP-only, no scheduler, and the frontend arming UI was removed, so nothing calls it by default.
- `application/service/PlaybookService.java` — evaluates SMC playbook candidates from internal indicator/candle state. `PlaybookAutomationService` listens to `CandleClosed`, freezes qualifying `PlaybookDecision` rows, creates `ReviewType.PLAYBOOK` forward simulations at 4/7, and routes optional live entries through the existing execution/IBKR path only after explicit per-`(instrument,timeframe)` Auto-IBKR opt-in and broker preflight.
- `application/service/strategy/WtxStrategyService.java` — WaveTrend XT strategy orchestrator. Listens to `CandleClosed` per instrument/timeframe, evaluates the configured profile (`BASELINE` / `SESSION_ATR` / `HTF` / `STRICT`), applies trailing-ATR exits, attaches filter decisions to the enrichment snapshot, and optionally routes through `WtxExecutionBridge` for IBKR. **State is per `(instrument, timeframe)`** — `WtxStrategyState` carries `timeframe` and `wtx_strategy_states` has a composite PK, so 5m and 10m each have their own position, profile, auto-execution toggle and daily max-loss. REST: `/api/wtx/state/{instrument}/{timeframe}` (+ `/profile`, `/auto-execution`, `/indicator-params`, `/sl`, `/preset`). Per-panel overrides (`WtxParamOverride`: WaveTrend periods, SL ATR-mult, and signal-zone gating `nsc/nsv` + `useCompra1/useVenta1`) persist in `wtx_param_overrides` and apply on every candle close; named presets (e.g. `top-train-z35` — zone-only entries, 5/14/2, SL 4.0×ATR, ±35) apply the full set atomically via `PUT .../preset {"preset":"top-train-z35"}`, and `{"preset":"clear"}` reverts to the global config. **Variant panels** (`riskdesk.wtx.variants[...]`, listed by `GET /api/wtx/variants`): parallel named signals — e.g. `top-train-Z35` on MNQ rides the 10m candles under panel key `10m-z35` with its own state/signals/overrides and a preset-seeded base config; force-close, daily reset and the HTF bootstrap all cover variant panels, and the frontend renders one `WtxStrategyPanel` per variant below the legacy WTX panels.
- `application/service/strategy/WtxExecutionBridge.java` — opt-in IBKR routing for WTX. Writes a `TradeExecutionRecord` (mentorSignalReviewId = null, triggerSource = WTX_AUTO) keyed by `wtx:<instrument>:<timeframe>:<signalTs>:<action>`, then calls `IbkrOrderService.submitEntryOrder`. Open-row lookups are timeframe-scoped (`findActiveByInstrumentAndTimeframeAndTriggerSource`). Disabled when `state.autoExecutionEnabled = false` (default). `submit(...)` returns a `WtxRoutingOutcome` (`ROUTED` / `ACK_PENDING` / `SKIPPED_*` / `FAILED_*`) — logged at INFO, persisted on `wtx_signal_history.routing_outcome`, and shown as a chip in the WTX panel so a non-routed signal is always diagnosable. `ACK_PENDING` means IBKR has an order id but the initial acknowledgement timed out; delayed `orderStatus` / `execDetails` callbacks can still reconcile the execution row.
- Domain helpers in `domain/engine/strategy/wtx/`:
  - `WtxBarEvaluator` — filter-aware bar evaluation, returns the candidate `WtxSignal`.
  - `WtxTrailingExitEvaluator` — fixed initial stop, then ATR trailing stop above an activation threshold.
  - `WtxHtfBiasFilter` — 60m EMA stack bias (close ≥ fast ≥ slow → bullish, mirror for bearish).
  - `WtxStructureFilter` — Pine `sweep + reclaim` / `break + reclaim` proxy over a rolling lookback.
  - `WtxRiskGuard` — `canTradeForProfile`, `isForceCloseWindow`, `isNewTradingDay`, `isMaxLossHit`.
- `application/service/ExecutionFillTrackingService.java` — Slice 3a. Implements `ExecutionFillListener` domain port. Receives IBKR `execDetails` + `orderStatus` callbacks from `IbGatewayNativeClient`, deduplicates by `execId`, persists raw broker feedback on `TradeExecutionEntity`, transitions domain state to `ACTIVE` on first `Filled`, publishes `/topic/executions` on every state-changing update.
- `application/service/VolumeProfileService.java` — session volume profile (UC-OF-015). Builds the current RTH session (09:30–16:00 ET, `developing` flag), the prior RTH session, the overnight Globex session (18:00 ET reopen → RTH open) and the naked-POC ladder from internal 1m candles via `CandleRepositoryPort` + the pure `SessionVolumeProfileCalculator` (range-distributed binning, 70% VA expansion). Per-instrument cache (`riskdesk.order-flow.volume-profile.cache-seconds`, default 60 s). REST: `GET /api/order-flow/volume-profile/{instrument}` (no WS topic; 400 for synthetic DXY). Frontend overlay: `Chart.tsx` "VP" toggle (prior pPOC/pVAH/pVAL, developing POC, dotted `nPOC <date>` lines).
- `application/quant/service/QuantGateService.java` — runs the 7-gate SHORT-setup evaluator every 60 s (MNQ, MGC, MCL). Pure orchestration: parallel port fetch (Absorption, Distribution, Cycle, Delta, LivePrice) → `GateEvaluator.evaluate()` → state save → narration → optional tier-2 advisor → WebSocket publish. State persists in the `quant_state` table; recent snapshots in an in-memory ring buffer (`QuantSnapshotHistoryStore`).
- `application/quant/simulation/Quant7GatesSimulationService.java` — paper harness that opens/closes simulated trades from the live snapshot (HIGH conf + `[Δ CONFIRMED]` + `[ABS BULL/BEAR ACTIVE]` + flow TRADE + HTF 1h EMA20/50 alignment → entry; SL/TP/EOD-flat → exit, flow-AVOID per `riskdesk.quant.sim.exit-policy`, default ignored). Recalibrated 2026-06-11 on the first 863 recorded trades (see `docs/AI_HANDOFF.md`): SL/TP are ATR-sized (2.0/3.0/6.0 × ATR14-5m via `DefaultQuantSimMarketContext`), entries are blocked from 16:50 ET and open rows flatten `CLOSED_EOD` from 16:55 ET (DST-aware). Persists to `quant_7gates_simulations`; never touches mentor tables (Simulation Decoupling Rule). When `riskdesk.quant.sim-exec.enabled`, it also **mirrors** each qualified setup to IBKR via `Quant7GatesExecutionBridge` (trigger source `QUANT_SIM_AUTO`) — entry-Limit only, no resident broker stop, OFF by default, hard allowlist `MNQ,MCL` (MGC net-negative, 6E not scanned), per-instrument panel toggle (`QuantSimExecutionState`), one position per instrument. `QuantSimFlattenReconciler` (30 s) re-flattens orphaned positions and `QuantSimSessionCloseScheduler` (16:55–16:59 ET) force-flattens at a marketable price before the CME break; `submitOpen` refuses new arms after 16:50 ET. REST: `GET/PUT /api/quant/simulations[/exec-state | /{instrument}/auto-execution]`. Spec: `docs/PLAN_QUANT_SIM_AUTO_IBKR.md`.
- `application/quant/backtest/Quant7GatesExitBacktestService.java` — exit-policy replay over the RECORDED quant simulations: re-manages every persisted entry signal against historical 1m candles under a parameterised bundle (FIXED/ATR stops, flow-AVOID policy, HTF EMA filter, commission). Pure engine in `domain/quant/backtest/QuantExitReplayEngine` (pessimistic both-cross rule, no lookahead, EOD flat 16:55 ET). REST: `GET /api/quant/backtest/exits` (aggregates overall / byInstrument / byDirection / byExitReason / byDay + optional per-trade list). Entry-replay only — it cannot discover entries the live evaluator never fired.
- **Quant per-scan flow log** — `QuantGateService.scan()` appends one row per scan per instrument to `quant_scan_snapshots` (best-effort, via `domain/quant/scanlog/QuantScanSnapshotPort` → `QuantScanSnapshotJpaAdapter`): raw gate inputs (delta as-seen — null on stale-drop, `deltaSource` provenance, buy%, absorption window, dist/accu conf, price) + outputs (SHORT/LONG scores, pattern, per-gate verdicts JSON). Non-signal scans included (survivorship-bias guard). 90-day retention purged with the order-flow event tables. REST: `GET /api/quant/scan-log/{instrument}?from&to&limit`. This is the durable series that future gate-replay backtests and threshold sweeps read.
- `application/quant/service/QuantSetupNarrationService.java` — combines the gate snapshot with the deterministic `OrderFlowPatternDetector` and the `QuantNarrator` to produce the markdown surfaced in `/topic/quant/narration/{instr}`.
- `application/quant/service/QuantSessionMemoryService.java` — in-memory aggregator (per-instrument scan count, observed patterns, win rate, last outcome) that feeds the AI advisor's same-session view. Resets at the ET calendar-day boundary.
- `application/quant/service/QuantAiAdvisorService.java` — tier-2 orchestrator. Triggered when the gate score reaches `riskdesk.quant.ai-advice-trigger-score` (default 6). Pulls top-K nearest situations via pgvector (`QuantMemoryPort`), the same-session memory and the multi-instrument context, then calls `AdvisorPort`. Caches verdicts 30 s per instrument. Failure-safe — returns `AiAdvice.unavailable(...)` instead of throwing when the LLM / memory store is down.
- `application/service/perfectsetup/PerfectSetupService.java` — Perfect Setup order-flow confluence detector. `@Scheduled` (5 s) per `riskdesk.perfect-setup.instruments`. Gathers regime/absorption/cycle (quant ports), nearest icebergs (`OrderFlowHistoryService`), flash phase (`FlashCrashStatusService`), VWAP/BB (`IndicatorsPort`), price (`LivePricePort`), ATR (`CandleRepositoryPort`), then runs the pure `PerfectSetupDetector` (6-axis scoring + `IDLE→ARMED→TRIGGERED/INVALIDATED/EXPIRED` state machine, arm-threshold default 4/6, R:R hard gate). Keeps latest signal in memory, publishes `/topic/perfect-setup` each scan, emits `PerfectSetupDetected` on each state **transition**, and (when `riskdesk.perfect-setup.auto-arm.enabled`) bridges an ARM to `QuantAutoArmService.armFromPerfectSetup` (trigger source `PERFECT_SETUP`). REST: `GET /api/perfect-setup[/{instrument}]`. No DB schema — signal is in-memory + on the wire.

These coordinate use cases and should not become infrastructure adapters.

### Simulation decoupling (Phase 3 — single writer, simulation aggregate)

- Domain port: `domain/simulation/port/TradeSimulationRepositoryPort`
- Aggregate: `domain/simulation/TradeSimulation` (+ `ReviewType` enum)
- REST controller: `presentation/controller/SimulationController` — `GET /api/simulations/{recent,by-instrument/{instrument},by-review/{reviewId}}`
- WebSocket topic: `/topic/simulations` (sole channel for simulation events; `/topic/mentor-alerts` no longer carries simulation transitions)
- Initial `PENDING_ENTRY` is created directly on `trade_simulations` by `MentorSignalReviewService.initializeSimulationAggregate()` (auto reviews) and `MentorAnalysisService.initializeAuditSimulation()` (manual "Ask Mentor" audits).

**Legacy sim getters on the review/audit records are `@Deprecated(since = "phase-3")`** — write-never from production, read-only for JPA round-trip until the physical columns are dropped. Column drop is a separate follow-up PR that requires adopting Flyway or Liquibase first. See `docs/ARCHITECTURE_PRINCIPLES.md` § Simulation Decoupling Rule.

### IBKR integration

- `infrastructure/marketdata/ibkr/IbGatewayNativeClient.java`
- `infrastructure/marketdata/ibkr/IbGatewayContractResolver.java`
- `infrastructure/marketdata/ibkr/IbGatewayMarketDataProvider.java`
- `infrastructure/marketdata/ibkr/IbGatewayHistoricalProvider.java`
- `infrastructure/marketdata/ibkr/IbGatewayFxContractResolver.java`
- `infrastructure/marketdata/ibkr/IbGatewayFxQuoteProvider.java`
- `application/service/IbGatewayBrokerGateway.java`

### Persistence

- `infrastructure/persistence/*`

These are adapters, not business-rule owners.

Relevant DXY persistence now includes:

- `infrastructure/persistence/entity/MarketDxySnapshotEntity.java`
- `infrastructure/persistence/JpaDxySnapshotRepositoryAdapter.java`

Relevant simulation persistence (Phase 1 — new `trade_simulations` table running alongside the legacy simulation columns on `mentor_signal_reviews` / `mentor_audits`):

- `infrastructure/persistence/entity/TradeSimulationEntity.java`
- `infrastructure/persistence/JpaTradeSimulationRepository.java`
- `infrastructure/persistence/JpaTradeSimulationRepositoryAdapter.java`
- `infrastructure/persistence/TradeSimulationEntityMapper.java`

Schema of `trade_simulations` (unique constraint `(review_id, review_type)`):
`id`, `review_id`, `review_type` (`SIGNAL`|`AUDIT`), `instrument`, `action`, `simulation_status`, `activation_time`, `resolution_time`, `max_drawdown_points`, `trailing_stop_result`, `trailing_exit_price`, `best_favorable_price`, `created_at`.

## Frontend Map

### High-value files

- `frontend/app/page.tsx`
- `frontend/app/components/Dashboard.tsx`
- `frontend/app/lib/api.ts`
- `frontend/app/hooks/useWebSocket.ts`

> **AI MentorDesk UI removed (2026-05-28).** `AiMentorDesk.tsx`, `MentorPanel.tsx`,
> `MentorSignalPanel.tsx`, `TradeDecisionPanel.tsx`, `SimulationDashboard.tsx`, and
> `TrailingStopStatsPanel.tsx` were deleted to cut client-side resource use.
> `useWebSocket.ts` no longer subscribes to `/topic/mentor-alerts` or polls Mentor
> reviews. The Mentor backend (capture, narration, simulation) is isolated behind
> `riskdesk.mentor.enabled` (default off). The Mentor-related subsections below
> describe the **backend** capture behaviour, which only runs when the flag is on.

### Frontend responsibilities

- display live and fallback market data
- show mentor payload and result
- expose positions, indicators, alerts, and IBKR portfolio state
- auto-route selected trading alerts into a dedicated Mentor review panel
- display the synthetic DXY latest snapshot and 24h persisted history on the dashboard

### Mentor Alert Workflow

The dashboard has a dedicated Mentor review panel under the IBKR connection panel. The bottom alerts ticker bar has been removed.

Only these alert families are escalated into Mentor review:

- structure: `BOS`, `CHoCH`
- momentum: `MACD Bullish/Bearish Cross`, `WaveTrend Bullish/Bearish Cross`
- extremes: `RSI oversold/overbought`, `WaveTrend oversold/overbought`
- structure/price: `VWAP inside BULLISH/BEARISH Order Block`

#### Alert evaluation

Alerts use transition-based detection: an alert fires only when a condition *changes* (e.g., RSI crosses into oversold), not when it persists across polling cycles. The `IndicatorAlertEvaluator` tracks last-known state per indicator/instrument/timeframe using a `ConcurrentHashMap`.

`AlertService` still deduplicates by alert key on a short shared cooldown, but it no longer blocks an entire timeframe window (`10m` or `1h`). This means multiple alerts can be emitted on the same timeframe when distinct transitions happen before the bar period ends.

#### Grouped reviews

When multiple indicators fire simultaneously for the same instrument, timeframe, and direction (e.g., SMC + WAVETREND both signal LONG on MCL 10m), the backend batches them into a single combined Mentor review via `captureGroupReview`. Individual alerts are still published to WebSocket for the UI.

#### UI grouping

The `MentorSignalPanel` groups alerts by instrument + timeframe + direction within a 90-second time window. Groups show indicator category badges and a combined review count. An instrument filter dropdown allows filtering by specific instrument.

Current behavior:

- when a qualified alert is published, the backend captures a frozen Mentor payload snapshot immediately
- that first review is created from the frozen snapshot and saved in PostgreSQL
- the review thread is keyed by the exact alert occurrence (`timestamp + instrument + category + message`)
- clicking an alert in the Mentor review panel reads the saved thread only; it does not trigger a fresh Gemini call
- the button `Reanalyse` creates a new saved review revision under the same alert thread using LIVE market data at click time
- a re-review payload mixes live indicators with `original_alert_context` so Gemini can judge whether the old setup is still valid now
- raw alerts still flow through `/topic/alerts`
- Mentor review updates still flow through `/topic/mentor-alerts`
- simulation state transitions are additionally published on `/topic/simulations` (Phase 1 dual-publish; `/topic/mentor-alerts` continues to carry the same events until Phase 2 frontend cutover)
- live execution fill/status updates (from IBKR `execDetails` + `orderStatus`) are published on `/topic/executions` — payload is `TradeExecutionView` including the new fill fields (`filledQuantity`, `avgFillPrice`, `lastFillTime`, `orderStatus`, `ibkrOrderId`)
- manual `Ask Mentor` analyses are stored separately in `mentor_audits` with a dedicated `manual-mentor:` source reference and exposed through `/api/mentor/manual-reviews/recent`

### Mentor Outcome Tracker

Qualified alert reviews can now also be tracked after the fact as simulated trade outcomes.

Current behavior:

- when a saved `MentorSignalReview` finishes with a valid trade plan, the backend writes a `TradeSimulation(PENDING_ENTRY, reviewType=SIGNAL)` row into `trade_simulations` (Phase 3+). Manual "Ask Mentor" audits follow the same path with `reviewType=AUDIT`.
- tracked fields live on the `TradeSimulation` aggregate:
  - `simulationStatus`
  - `activationTime`
  - `resolutionTime`
  - `maxDrawdownPoints`
  - `trailingStopResult`, `trailingExitPrice`, `bestFavorablePrice`
- identical getters still exist on `MentorSignalReviewRecord` / `MentorAudit` but are `@Deprecated(since = "phase-3")` — write-never from production; used only by the JPA mappers for backwards compatibility until the physical columns are dropped in a follow-up PR.
- the simulation uses internal `5m` candles from PostgreSQL only
- the scheduler polls `trade_simulations` in `PENDING_ENTRY` or `ACTIVE` via `TradeSimulationRepositoryPort.findByStatuses(...)`
- trigger logic respects limit-order semantics:
  - if price reaches `TP` before `Entry`, the result becomes `MISSED`
  - once `Entry` is touched, the review becomes `ACTIVE`
  - if `SL` and `TP` are both crossed in one candle, the result is `LOSS` pessimistically
- terminal states are:
  - `WIN`
  - `LOSS`
  - `MISSED`
  - `CANCELLED`

### Playbook Auto-Simulation + Auto-IBKR contract

PLAYBOOK automation is a playbook-specific workflow driven by `CandleClosed`, not frontend polling. It uses only internal candle/price state sourced from `IBKR Gateway -> PostgreSQL -> internal services`.

Current behavior:

- Per-panel state lives in `playbook_automation_states`: `paperThreshold=4`, `liveThreshold=5`, `paperEnabled=true`, `autoExecutionEnabled=false`, `configuredOrderQty=1`, `armedProfile=LEGACY`, `scalpProfileValidated=false`.
- Qualifying decisions are frozen in `playbook_decisions` with setup identity, score, direction, entry/SL/TP, evaluated candle time, routing outcome, and price source.
- Paper simulation uses `ReviewType.PLAYBOOK` and resolves the plan from `PlaybookDecision`; it does not write simulation state back to Mentor review/audit records.
- Auto-IBKR is a second, explicit opt-in gate and requires a selected broker account, complete plan, score >= 5/7, positive quantity, non-late entry, live IBKR price source, no duplicate execution, IBKR enabled, and margin/preflight approval.
- `MGC 10m BREAK_RETEST` can arm the `MGC_10M_SCALP_0_5R` execution profile after manual validation; `MGC_10M_NORMAL_1R_BENCHMARK` remains benchmark-only until candle-by-candle replay lands. Other instruments/timeframes remain `LEGACY`.
- Live Playbook routing blocks when an active execution already exists for the same instrument/account across `PLAYBOOK_AUTO`, `WTX_AUTO`, `WTXRSI_AUTO`, or `QUANT_AUTO_ARM`.
- `routeLive` submits ONLY the entry order; SL/TP are stored as virtual levels (no broker bracket). Two app-side watchers enforce them while the backend is up: `PlaybookEntryInvalidationWatcher` cancels a resting *unfilled* entry on zone break, and `PlaybookPositionReconciler` flattens a *filled* `PLAYBOOK_AUTO` position once a fresh live price crosses its virtual SL/TP (`riskdesk.playbook.position-watch.enabled`, default true, runtime kill-switch). Neither survives a backend outage — that residual gap is what real OCO brackets would close (deferred).
- Every non-routed path exposes a stable diagnostic outcome such as `PAPER_ONLY`, `SKIPPED_NO_PLAN`, `SKIPPED_NO_QTY`, `SKIPPED_NO_ACCOUNT`, `SKIPPED_STALE_PRICE_SOURCE`, `SKIPPED_INSUFFICIENT_MARGIN`, or `SKIPPED_IBKR_DISABLED`.
- REST: `GET/PUT /api/playbook/automation/{instrument}/{timeframe}` and `GET /api/playbook/automation/{instrument}/{timeframe}/decisions?limit=N`.
- WebSocket: `/topic/playbook-decisions/{instrument}/{timeframe}`.

### Execution Foundation

RiskDesk now has a dedicated execution persistence model for future live-order orchestration.

Current behavior:

- live execution state is no longer meant to be stored on `mentor_signal_reviews`
- a dedicated `trade_executions` table owns the future live execution lifecycle
- the idempotence key is the persisted Mentor review ID, not the alert thread key
- `TradeExecutionEntity` stores frozen review linkage, broker account, execution quantity, normalized entry price, virtual SL/TP placeholders, and execution timestamps
- Slice 1 does not place any IBKR order yet
- Slice 1 now exposes manual arming endpoints under `/api/mentor/executions`
- the `MentorSignalPanel` can create an execution foundation row for an eligible saved review once an IBKR account and quantity are selected
- execution lookup is batched by `mentorSignalReviewId` so the UI can display existing execution state without coupling it into the review aggregate
- Mentor reviews now carry an explicit `executionEligibilityStatus` and `executionEligibilityReason`
- backend execution creation must require:
  - review status `DONE`
  - explicit eligibility status `ELIGIBLE`
  - complete `Entry / SL / TP`
  - tick-normalized prices
- Slice 2 has now started:
  - `POST /api/mentor/executions/{executionId}/submit-entry` submits a simple IBKR limit entry order
  - submission is locked on the execution row before broker side effects
  - the IB Gateway adapter uses `orderRef = executionKey` and checks existing live/completed orders before re-submitting
- Slice 3a is now live — IBKR `execDetails` + `orderStatus` fill tracking:
  - `TradeExecutionEntity` carries raw IBKR feedback: `filledQuantity`, `avgFillPrice`, `lastFillTime`, `orderStatus`, `ibkrOrderId`, `lastExecId` (per-fill idempotence key)
  - `ExecutionFillTrackingService` (application) implements the `ExecutionFillListener` domain port and is invoked from `IbGatewayNativeClient` on every IBKR callback
  - transition from `ENTRY_SUBMITTED` to domain state `ACTIVE` happens on first IBKR `Filled` status; cancellation without any fill transitions to `CANCELLED`; partial-fill-then-cancel is deferred to Slice 3c
  - WebSocket topic `/topic/executions` carries every state-changing update
- Slice 3b (next) will add startup reconciliation: query IBKR open/completed orders and reconcile against dangling `trade_executions` rows from prior runs.
- Slice 3c (future) will add bracket orders — submit SL + TP once entry fills, monitor and close.

### Chart trading / Active Positions surface (2026-06-11)

- `POST /api/quant/manual-trade/{instrument}` accepts `brokerAccountId` (falls back to
  `riskdesk.quant.auto-arm.broker-account-id`) and `submitImmediately` (LIMIT goes straight to
  IBKR instead of resting as PENDING awaiting a separate submit-entry call)
- `POST /api/quant/positions/{id}/close` routes a FLATTEN through the unified `OrderRouter`
  for broker-known rows (marketable exit, broker-truth reconcile, stuck-close re-fire);
  unfilled `ENTRY_SUBMITTED` rows delegate to the broker cancel instead
- `POST /api/quant/positions/{id}/cancel-entry` cancels an unfilled entry: local cancel for
  PENDING rows, real broker cancel (`IbGatewayNativeClient.cancelOrderById`, IBKR code 202 =
  success) for a resting limit; the `Cancelled` callback finalizes the row
- Frontend: `TickChart.tsx` `TRADE` toggle → click on chart → Acheter/Vendre context menu →
  confirmation ticket → `submitManualTrade(submitImmediately)`; order/position/SL/TP price
  lines from `/topic/positions`; cancel/flatten rows under the chart (two-click confirm).
  SL/TP of manual rows are VIRTUAL display levels — no walker, no broker brackets.

## Operational Commands

### Compile backend

```bash
mvn -q -DskipTests compile
```

## Container Release Workflow

- Docker image validation runs in GitHub Actions on `push` to `main` and on pull requests targeting `main`
- the `Tag & Deploy` workflow builds immutable release images, pushes them to GCP Artifact Registry, uploads the runtime config bundle to GCS, and refreshes the GCE VM through an IAP SSH tunnel
- the deploy job expects GitHub Actions variables `GCP_PROJECT_ID`, `GCP_REGION`, `GCP_ZONE`, `GCP_ARTIFACT_REGISTRY_REPOSITORY`, `GCP_INSTANCE_NAME`, `GCP_CONFIG_BUCKET`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT`, and `GCP_DEPLOY_SSH_USER`
- the deploy job expects GitHub Actions secret `DEPLOY_SSH_PRIVATE_KEY`
- `GCP_DEPLOY_SSH_USER` must identify the VM user that owns the public half of `DEPLOY_SSH_PRIVATE_KEY`; that user must have passwordless `sudo` because runtime refresh writes under `/opt/riskdesk` and controls Docker
- local Docker is no longer required for the standard image release path
- the private IBKR `tws-api` dependency is vendored under `vendor/maven-repo` so Docker/CI builds do not depend on a developer-local `~/.m2`

### Run backend

```bash
mvn -q spring-boot:run
```

or:

```bash
mvn -q -DskipTests package
java -jar target/riskdesk-0.1.0-SNAPSHOT.jar
```

### Frontend lint

```bash
cd frontend
npm run lint
```

### Useful API checks

```bash
curl -s http://localhost:8080/actuator/health
curl -s http://localhost:8080/api/live-price/E6

# Seed 90 days of real 1m for a backtest (idempotent; async by default), then poll status:
curl -s -X POST "http://localhost:8090/api/candles/backfill/MNQ/1m?from=2026-03-01T00:00:00Z&to=2026-05-30T00:00:00Z"
curl -s "http://localhost:8090/api/candles/backfill/MNQ/1m/status"

# Read back beyond the 1000-candle cap via the cursor (pass nextFrom back as from):
curl -s "http://localhost:8090/api/candles/MNQ/1m/range?from=2026-03-01T00:00:00Z&to=2026-05-30T00:00:00Z&limit=5000"
```

## Coding Conventions

### Backend

- preserve existing package boundaries
- avoid mixing controller logic into services
- prefer explicit DTOs over ad-hoc maps for API contracts
- treat IBKR timeouts and farm outages as first-class operational states
- preserve separation between `presentation`, `application`, `domain`, and `infrastructure`
- express external dependencies through ports/interfaces when possible
- keep Spring and persistence details out of the domain layer

### Testing approach

- use TDD for business logic and bug fixes where practical
- use unit tests for domain rules and application orchestration
- use integration tests for adapters and controllers
- use BDD scenarios when user workflows or acceptance behavior changes

### Frontend

- keep API logic in `frontend/app/lib/api.ts`
- keep dashboard logic in components, not in raw page-level glue
- when changing payloads, update backend and frontend in the same task

## Git Notes

- the repository root is now the project root
- `frontend/` is no longer a separate Git repository
- a backup of the old frontend `.git` was stored under `/tmp` during first import

## Where to Handoff Work

For agent-to-agent continuity, update:

- `/Users/ismailassri/.gemini/antigravity/scratch/riskdesk2/docs/AI_HANDOFF.md`
