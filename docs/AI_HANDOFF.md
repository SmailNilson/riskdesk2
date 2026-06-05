# AI Handoff

Last updated: 2026-06-05

## 1-minute candles wired (backtests + order-flow) + tick-by-tick failure now diagnosable (2026-06-05)

**Problem.** Backtests were silently empty: `WtxRsiBacktestService` loads only `"1m"` from PostgreSQL
and resamples upward (`CandleResampler` only coarsens, never refines), but **nothing populated 1m** —
neither the historical backfill (`HistoricalDataService.TIMEFRAMES` was `5m,10m,1h,4h,1d`) nor the live
accumulator (`MarketDataService.TIMEFRAMES`). 15m was missing for the same reason.

**Slice 1 — historical 1m backfill.** Added `1m` to `IbGatewayHistoricalProvider` (`supports`,
`supportsDeepBackfill`, `chunkQueryFor` → 1-DAY chunks of `BarSize._1_min`, `queryFor`, `maxContractWalk`)
and to `HistoricalDataService.TIMEFRAMES` + `candlesTargetFor`. Front-month 1m now **throttles** (it walks
day-by-day over ~30 requests, unlike coarse TFs); `MAX_BACKFILL_CHUNKS` 32→45. Depth = new
`riskdesk.market-data.historical.backfill-days-1m=30`. NB: the deep seed runs once (empty table); afterwards
gap-fill is forward-only — same rolling-window caveat as 5m/10m.

**Slice 2 — live 1m.** Added `1m` to `MarketDataService.TIMEFRAMES` (fed by the 5s `reqRealTimeBars` push,
so bars are well-formed). Verified safe: all six `CandleClosed` listeners filter by timeframe, so `1m`
closes create no alert/scan fan-out.

**Slice 3 — real ticks were already wired; the blocker is IBKR delivery, not code.** `TickByTickClient`
(dedicated socket, Lee-Ready `classifyTrade`) is activated by `OrderFlowOrchestrator.ensureTickByTickSubscriptions`
(@Scheduled) and wired via `MarketDataConfig`. Prod `/api/order-flow/status` shows all 4 instruments
`tickSubscribed:true` but `totalTicksReceived:0` → IBKR sends nothing (entitlement / competing session).
The CLAUDE.md note "reqTickByTickData NOT wired" refers to `IbGatewayNativeClient`; the separate
`TickByTickClient` does it. **Added diagnostics** so this stops failing silently: `TickByTickClient` now
captures the last IBKR error per instrument (codes 354 / 10089 / 10197 / 10167 / 1100 with hints), cleared
when a tick arrives; `/api/order-flow/status` now exposes `totalTicksReceived`, `tickConnectionUp`,
`tickSystemError`, and per-instrument `lastTickError`. The real fix is an IBKR market-data entitlement,
not application code.

## Quant UI trim — "Setup Recommendations" feature removed, "Quant 7-Gates" live panel removed (2026-06-05)

Two overlapping quant panels were removed from the dashboard:

- **Setup Recommendations** (the scalp/day-trading template engine — `SetupOrchestrationService`,
  `domain/quant/setup/**`, `application/quant/setup/**`, `infrastructure/quant/setup/**`,
  `SetupController` + `/api/quant/setups/*`, the `/topic/quant/setup-recommendation/{instr}` WS
  topic, `SetupRecommendationPanel.tsx` + `useSetupStream.ts`, and their tests) was **fully
  deleted** (frontend + backend). Its only upstream hook — `QuantGateService` calling
  `setupOrchestrationProvider.onSnapshot(...)` — was unwired (the `ObjectProvider` removed from the
  constructor; the convenience constructor now passes two empty providers, not three). The
  now-orphaned `setup_recommendations` table is left in place (harmless under `ddl-auto=update`).
  Design doc `docs/analysis/SCALPING_DAYTRADING_FUSION.md` is marked **REMOVED** for reference.
- **Quant 7-Gates (live panel)** — `QuantGatePanel.tsx` and its three exclusive helpers
  (`QuantAdvisorBadge`, `QuantNarrationPanel`, `QuantManualTradeModal`) were deleted. This removes
  the UI for manual BUY/SELL, "Ask AI", and the auto-arm FIRE/CANCEL controls; the backing REST
  endpoints and the 60 s scan are **kept** because the **Quant 7-Gates Simulation** panel, the
  auto-arm pipeline, `OrderFlowPanel`, and `QuantSetupNotification` all still depend on the scan +
  `useQuantStream`. The Simulation panel (`Quant7GatesSimulationPanel`) remains the live quant view.

## WTX close dead-lock — a stuck EXIT_SUBMITTED on a still-open position now self-heals (2026-06-05)

A marketable close usually fills synchronously, but it can still **gap out of the book** (priced
`cross-ticks` through the live price, the market jumps past it) or have its ack/fill callback dropped —
leaving the WTX row stuck in `EXIT_SUBMITTED` while **IBKR still holds the position**. The old
`WtxExecutionBridge.handleClose` then returned `SKIPPED_DUPLICATE` for *every* later CLOSE (the
"flatten already in flight" guard), and the entry-path reconcile returned `SKIPPED_DUPLICATE` for every
same-side OPEN ("IBKR already short/long"). The position could be **neither exited nor reversed** and bled
— visible in the UI as rows stuck on **"NON EXÉCUTÉ / DUPLICATE"**, unaffected by the marketable toggle
(the skip happens *before* any order is priced). `StaleCloseReconciler` did not help: it only finalizes an
`EXIT_SUBMITTED` row when IBKR is **flat**; a still-open stuck close was its blind spot.

Fix — `handleClose` now consults broker truth on an `EXIT_SUBMITTED` row (`stuckCloseNeedsRetry`): once the
close has been submitted past a grace window **and** IBKR confirms the position is still open on that row's
side, it **re-fires a fresh marketable close** (a new per-bar exit ref — exactly what `submitCloseLeg` was
built to retry) instead of skipping. Conservative by construction — it keeps the duplicate-skip when broker
truth is unavailable, the position is confirmed flat (the `StaleCloseReconciler` owns that case), IBKR holds
the opposite side, or the close is still within grace (a genuinely in-flight marketable close is never
double-submitted). Grace = `riskdesk.wtx.stale-close-retry-seconds` (default 45; `0` disables → legacy skip).

- **Scope** — `WtxExecutionBridge` (legacy path) only. The REVERSE close-leg is intentionally untouched (it
  opens the opposite leg instead of re-firing the close — locked by `exitSubmittedPriorButIbkrStillHolds_…`).
- **Known parallel gap (follow-up)** — `IbkrWtxRsiExecutionBridge.submitClose` has the identical guard but no
  portfolio read; the same safe fix needs `IbkrPortfolioService` wired in first. `DefaultOrderRouter`
  (unified path, default OFF) shares the guard too.

## WTX exits — point trailing + exit-type marker + 17:00 ET daily reset (2026-06-05)

Three coupled changes to the WTX (legacy WaveTrend) strategy, driven by a backtest of 116 MNQ 5m
trades (1–4 Jun): the reverse-only exit surrendered ~8.5× its realized profit as give-back, and the
existing SESSION_ATR trailing was mis-calibrated (arm 0.5R ≈ +15pt too early, trail 2.0×ATR ≈ 44pt too wide).

- **Point-based trailing (`WtxTrailingMode.POINTS`, now the live default).** `WtxConfig` gains
  `trailingMode` + `trailingActivationPoints` (30) + `trailingPoints` (15) + `slPoints` (0). In POINTS
  mode the arm/trail distances are fixed points (beat ATR-scaling, which widens the trail on big momentum
  legs). The initial stop is independent: `slPoints>0` → fixed point stop, else dynamic `slAtrMult*ATR`
  (`slAtrMult` lowered 1.4 → 1.3 ≈ ATR×1.3 ≈ 30pt). `WtxTrailingExitEvaluator.evaluate`/`currentStop`
  branch on mode via a shared `slDistance(...)` helper. Legacy ATR mode preserved (`WtxConfig.defaults()`
  still ATR, so existing tests are bit-for-bit). Keys: `riskdesk.wtx.trailing-mode|trailing-activation-points|trailing-points|sl-points|sl-atr-mult`.
- **Exit-type marker (`WtxExitType`).** Dedicated field on `WtxSignal` + `wtx_signal_history.exit_type`
  column + controller DTO + a colored UI badge in `WtxStrategyPanel`, so a close is identifiable as
  TRAILING_TP / STOP_LOSS / REVERSE / FORCE_CLOSE / MAX_LOSS / SWING_BIAS (was buried in the overloaded
  `structureReason`). `buildCloseSignal` takes the type; the normal path tags REVERSE / SWING_BIAS.
- **17:00 ET daily reset (`WtxDailyResetScheduler`).** The `maxLossHit` latch was cleared only by the
  first new-day candle (`isNewTradingDay`) — so a stuck "blocked on a new day" state could persist on feed
  lag / restart. A cron (`0 0 17 * * *`, zone America/New_York) now rebaselines equity + clears the latch
  for every state, gated by `riskdesk.wtx.daily-reset-enabled` (default true). Position/entry carry over.

## Marketable settings — operator-controlled at runtime from the UI (2026-06-04)

The marketable-execution policy (the `marketable-close` / `marketable-reverse-open` toggles + `cross-ticks`)
is now flippable LIVE from the frontend, like Auto-IBKR — no redeploy. The static `@Value` flags on
`DefaultOrderRouter` were replaced by a `MarketableSettingsProvider` (domain port) it reads per order.

- **State** — `MarketableExecutionSettings` (domain record: closeEnabled / reverseOpenEnabled / crossTicks,
  crossTicks clamped ≥ 0). Held by `MarketableExecutionSettingsService` (application, implements the
  provider): seeds from the `riskdesk.execution.marketable-*` `@Value` defaults, caches, validates
  (crossTicks 0..1000), persists each change. GLOBAL — one policy for the whole execution core (all
  strategies), unlike Auto-IBKR which is per-(instrument,timeframe).
- **Persistence** — singleton row `execution_marketable_settings` (`MarketableSettingsRepositoryPort` →
  `JpaMarketableSettingsAdapter`). Survives restart; `load()` empty until first save → service uses the
  config defaults.
- **REST** — `GET` / `PUT /api/execution/marketable-settings` (`MarketableSettingsController`, partial PUT:
  null fields keep their value, 400 on out-of-range crossTicks).
- **Frontend** — compact `MarketableSettingsControl` in the Dashboard header (Exit/Rev toggles + cross-ticks
  input), polls every 5s, PUTs on change. `api.{getMarketableSettings,updateMarketableSettings}`.
- The `application.properties` values are now SEED DEFAULTS only; the persisted/operator value wins once set.

## Marketable exit pricing — reversals/closes no longer rest unfilled (2026-06-04)

A REVERSE placed two legs (close-then-open), and the **close leg inherited the entry's passive limit**
(`intent.limitPrice()`). If the market moved away the close rested unfilled, the open was deferred
indefinitely behind it (`ReverseDeferredOpenScheduler` waits for the close FILL), and the user stayed
stuck holding the position the signal said to exit. Same latent bug for `CLOSE`/`FLATTEN` — all three route
through `DefaultOrderRouter.submitCloseLeg`.

Fix — every **reducing** leg is now priced as a **marketable LIMIT** (`submitCloseLeg` →
`marketableCloseLimit`): the internal live price is crossed by `cross-ticks·minTick` — SELL (reduce a long)
at `price − cross`, BUY (reduce a short) at `price + cross`. It fills immediately like a market order, but
stays a LIMIT (the deliberately limit-only broker path) so the cross caps worst-case slippage — safer than a
raw MKT on thin micro books. **Entries stay passive** by design: the asymmetry is the point — exiting is
risk-reduction (must fill), entering is opportunity (may rest; if it doesn't fill you're simply flat). With a
marketable close the reverse close usually fills synchronously → the open submits inline, so the
deferred-open path becomes a rare fallback, not the norm.

**Reverse-open refinement** — the OPEN leg of a reverse that *actually flattened* (`reverseFlattened`) is
ALSO priced marketable (same cross + live-source gating, re-priced off the live price at submit time), so
the flip COMPLETES: previously a flattened-then-unfilled passive open left the user FLAT instead of reversed
when price moved past the entry in the close→open gap. Plain OPENs (fresh entries, nothing flattened) still
stay passive. Bounded — a price gapped beyond cross-ticks still rests (no runaway chase). Independent toggle
`riskdesk.execution.marketable-reverse-open.enabled` (default on). `marketableLimit` is now shared by both
the exit legs and the reverse open; enable-gating lives at each call site. The reverse margin preflight runs
against the price the open will ACTUALLY submit — the crossed price only when a close fired
(`closeLegFired`), the passive limit when the broker was already flat / the prior row was voided — so it
neither lets a size-increasing reverse pass cheap then get IBKR-rejected on the crossed order
(→ ROUTED_FLATTEN_ONLY), nor falsely denies a flat-reversal open the passive submit could afford. The inline reverse-open
submits the EXACT preflighted price (the marketable price is computed once in `executeReverse` and carried
into the submit — not a second live read that could tick higher); the deferred open re-prices at its own
submit time. The ACTIVE reverse-open row is persisted at the crossed price so live P&L isn't skewed.

The price comes from the existing `LivePricePort` (`MarketDataService.currentPrice` → the compliant
`IBKR Gateway → PostgreSQL → services` feed, carrying live-vs-DB provenance) — **the same source the Quant
force-close uses**, NOT a direct broker read (AGENTS.md market-data rule). The compliant path exposes a
single reconciled price, not a bid/ask, so it crosses that price by `cross-ticks` rather than sitting on a
touch — exactly `IbkrQuant7GatesExecutionBridge.marketableLimit`. Only a **genuinely-live, fresh** price is
crossed (source `LIVE_PUSH`/`LIVE_PROVIDER` and < 20s old); a `FALLBACK_DB` candle close or a stale value
during a feed outage is treated as no price — crossing a stale price would yield a falsely-"marketable"
limit that can rest unfilled. The router degrades to the passive intent limit (legacy behaviour, no worse
than before) when the feature is off, no executable-live price is available, or the lookup throws — a price
hiccup never breaks a close; a still-unfilled close stays a retryable LIMIT.
Config `riskdesk.execution.marketable-close.{enabled:true, cross-ticks:10}` (`cross-ticks` mirrors
`riskdesk.quant.sim-exec.flatten-cross-ticks`, also 10). No execution state-machine change (still
`EXIT_SUBMITTED`/`CLOSED`); the broker adapter is untouched (still `OrderType.LMT`).

## Stuck EXIT_SUBMITTED reconciliation — broker-truth replay (2026-06-03)

Across all strategies, `trade_executions` rows were piling up stuck in `EXIT_SUBMITTED` (broker truth:
IBKR flat, `positions: []`) — the position WAS closed but the row never reached `CLOSED`, so the Active
Positions panel showed phantom positions and the one-position-per-instrument guards blocked new routing.

Root cause: a **marketable close fills during `submitEntryOrder`**; the `orderStatus(Filled)` callback
fires before the bridge persists the close `ibkrOrderId`, so `ExecutionFillTrackingService.onOrderStatus`
(`findByIbkrOrderId`) can't locate the row and drops it; no later callback re-fires. `onOrderStatus` also
has no `orderRef` fallback (the TWS callback carries none), and `onExecDetails` (which does carry
`orderRef`) never applies the lifecycle transition.

Fix — `application/execution/StaleCloseReconciler` (additive, NOT on the order-placement hot path):
a scheduled sweep (60s + boot replay via `ExecutionReadinessGate`, 90s grace) that, for each stale
`EXIT_SUBMITTED` row confirmed flat, flips **that exact row** to `CLOSED` directly. It does NOT route by
`orderId` through the fill tracker: reused/colliding IBKR orderIds after reconnect (multiple rows shared
"broker order 21/29/41") make `findByIbkrOrderId` ambiguous, so an orderId-keyed replay hit the wrong row
(or none) and left it stuck — observed in prod, where the ACTIVE-phantom path (already a direct flip)
cleared but the EXIT path didn't. The WTX close-P&L settler still finalizes on its next bar ("no
non-terminal row" → finalize); the active-positions publisher drops the row. The authoritative "close
completed" signal is **position flatness**: a live order still working under the ref → skip; else IBKR
**confirmed flat** → `CLOSED`; UNAVAILABLE / portfolio-unreadable / a position still open → skip (never
marks CLOSED while a real position could exist). Flag: `riskdesk.execution.close-reconcile.enabled`
(default on). Does NOT change how any strategy submits/closes; the deeper permId-keyed fix stays with the
unified core.

It also closes **ACTIVE phantoms** — rows the app believes open but IBKR holds no position for (missed
close fill / external close) — so the WTX position reconciler resyncs the virtual state to FLAT (fixes the
"frozen Entry on a flat account" WTX-panel symptom). And it cancels **zombie ENTRY_SUBMITTED** rows whose
order never reached the exchange (`PendingSubmit` never transmitted — IBKR shows 0 orders — or the order is
gone) while IBKR is flat: these freeze a reversal strategy (`SKIPPED_ENTRY_IN_FLIGHT` on every signal) and
keep its virtual state desynced, and `WtxStaleEntryReconciler` won't touch them because it treats
`PendingSubmit` as live — but a genuinely-working resting limit is `Submitted`/`PreSubmitted`, not
`PendingSubmit`. Both destructive paths are **debounced** (`confirm-seconds`, default 120): they act only
after IBKR is confirmed reconcilable for the row continuously across the window, so a transient empty
snapshot / a freshly-submitted entry is never touched; a genuinely-working `Submitted`/`PreSubmitted`/
`PartiallyFilled` order is always left alone. Gated by `reconcile-active-phantoms` / `reconcile-stuck-entries`
(both default on).

## Quant 7-Gates Simulation → Auto-IBKR mirror (2026-06-03)

The Quant 7-Gates **simulation** harness can now mirror each qualified paper trade to a real IBKR order,
like WTX / WTX-RSI / Playbook. 6th routed `ExecutionTriggerSource`: `QUANT_SIM_AUTO`. Shipped in 4 PRs
(#388 core, #389 REST + force-close, #390 frontend, #391 docs). Full spec: `docs/PLAN_QUANT_SIM_AUTO_IBKR.md`.

- **Design from 129 prod trades:** one position per instrument (0 LONG/SHORT overlaps observed), NOT a
  reversal (next-trade direction ~50/50, ~2–4 min gaps). Full mirror: paper SL/TP/flow-AVOID flatten the
  broker position. Entry-Limit only, no resident broker stop.
- **Bridge** `IbkrQuant7GatesExecutionBridge` (`@ConditionalOnProperty riskdesk.quant.sim-exec.enabled`):
  one entry row per OPEN; CLOSE transitions the existing row to `EXIT_SUBMITTED` (never a new row) and is
  NOT toggle-gated (a live position is always closable). Flatten side derived from `row.getAction()`, no-op
  on a direction mismatch. Reconciliation via the shared, source-agnostic `ExecutionFillTrackingService`.
- **Gates:** hard allowlist `MNQ,MCL` (MGC net-negative −$303, 6E not scanned by `QuantGateScheduler`),
  per-instrument toggle (`QuantSimExecutionState`, default OFF), master flag, dedupe, one-position-per-
  instrument, `SKIPPED_ENTRY_IN_FLIGHT` for the same-tick re-entry.
- **Close safety:** `QuantSimFlattenReconciler` (30 s) re-flattens orphaned positions (paper closed, broker
  still open) so a transient close failure can't strand a position; `QuantSimSessionCloseScheduler`
  (16:55–16:59 ET) force-flattens at a **marketable** price (current price crossed by `flatten-cross-ticks`)
  before the 17:00 CME break; `submitOpen` refuses new arms after 16:50 ET (no broker order-cancel API, so
  resting DAY entries are bounded + expire at the break).
- **REST/UI:** `PUT /api/quant/simulations/{instrument}/auto-execution`, `GET …/exec-state`; the panel shows
  a per-instrument Auto-IBKR toggle (MNQ/MCL) as an *instrument-armed* status (no misleading per-row badge).
- **Account:** no account to configure — the bridge sets a placeholder (`quant-sim-default`) and the gateway
  resolves it to the session's managed account (`resolveAccountId`), exactly like WTX-RSI. (Earlier the flag
  fail-fasted on a missing `broker-account-id`; that crashed the prod boot / deploy health check and was
  removed — see the broker-account simplification.)
- **Gating:** `riskdesk.quant.sim-exec.enabled=true` is the committed default (operator decision); a live
  order still requires IBKR connected + a per-instrument MNQ/MCL toggle armed (default OFF, in-memory) + the
  allowlist. Tests force the flag OFF in `application-test.properties`.

## D2 re-land — reverse open serialised behind the close FILL (2026-06-03)

The last execution-core slice (D2 was pulled earlier because the WTX state model couldn't absorb its async
transient; that model — close-PnL settler + side reconciler with the defer-while-pending guard — is now
merged through #385). Re-landed **simpler**, since the fill tracker now owns the cancelled-close revive.

- New nullable `TradeExecutionRecord.deferredReverseCloseRowId` — the PK of the resting close row a deferred
  REVERSE open waits on. Keyed by the close **row PK** (not the order id), because the fill tracker
  **detaches the order id** when it revives a cancelled-without-fill close to `ACTIVE`.
- `DefaultOrderRouter.executeReverse`: when the close leg RESTS (`EXIT_SUBMITTED`), defer the open (persist a
  PENDING row linked to the close row, return `ROUTED`) instead of firing it on the close ACCEPT. A FILLED
  (marketable) close, or no close (broker flat), opens inline. `submitEntry` shares `submitPersistedEntry`
  with the deferred path.
- `ReverseDeferredOpenScheduler` (offloaded — the fill callback is on the EReader thread): per deferred row,
  read the linked close ROW by PK — `CLOSED` → submit the open; `ACTIVE`/terminal-without-fill → position
  still live → cancel the deferred open (no stacking); resting → wait. No revive logic here — the fill
  tracker owns it.

The D4 affordability gate runs BEFORE the defer (an unaffordable reverse open is `ROUTED_FLATTEN_ONLY`,
never deferred). Flag still default-OFF → zero live impact. Full suite **2012 green**.

**Next:** flip `riskdesk.execution.unified-router` live for WTX (all prerequisites — D1–D4, the WTX state
model, D2 — now merged), then migrate the other four strategies onto the router.

## WTX close-PnL finalized only on a confirmed close (fill-driven) (2026-06-03)

The real-money accounting prerequisite for the WTX position reconciler (#385, held draft). WTX booked
close-PnL **synchronously** on close-submission, but a `LMT DAY` close can rest then cancel/expire; the
reconciler re-adopting such a position would leave the optimistic PnL booked and double-book the eventual
real close (Codex P1 on #385). This makes close-PnL final only once the broker confirms the close.

- `WtxStrategyState.pendingClosePnl` — close PnL booked optimistically but not yet broker-confirmed (+ a
  compact-ctor null→0 normalization; persisted column; cleared by open/day-reset/max-loss).
- `closePosition` (auto-exec): books as today **and** marks the amount pending.
- `WtxClosePnlSettler` (bar start, before day-reset): resolves the pending against execution-row truth —
  row gone/`CLOSED` (filled, flat) → **finalize**; row `ACTIVE` (close cancelled without a fill, or none
  went out → still live) → **roll back**; `EXIT_SUBMITTED`/`VIRTUAL_EXIT_TRIGGERED` (in flight) → **wait**.
  Side-agnostic (the side is the reconciler's job).
- Fill-tracker: an `EXIT_SUBMITTED` close that cancels without a fill now revives the row to `ACTIVE` (+
  detaches the dead order id), so the settler can distinguish a cancelled close (rollback) from a filled
  one (finalize).

Paper (auto-exec off) closes are immediate/real → never pending. Full suite 1993 green.

**Next:** rebase #385 (the WTX side-reconcile) onto this — its re-adopt becomes safe (no optimistic PnL to
double-book), and it drops its own PnL-realization (close-PnL now lives here). Then re-land D2.

## Unified execution core — Slice D: D4 + D3 (live-flip prerequisites) (2026-06-02)

Two of the three prerequisites that gate flipping `riskdesk.execution.unified-router` live for WTX
(flag still **default-OFF** → zero live impact). The unified `DefaultOrderRouter` was already
functionally complete and parity-tested behind the flag (Slices A–C, D1).

- **D4 — margin pre-flight after reconcile.** New `OrderAffordabilityPort` (application.execution);
  `IbkrMarginPreflightService` implements it, so the unified deny decision is byte-for-byte the legacy
  `WtxExecutionBridge` pre-flight. Injected as `Optional` into the router (absent → fail-open). OPEN
  pre-checks the FULL qty → deny = `SKIPPED_INSUFFICIENT_MARGIN` (no broker side effect). REVERSE
  pre-checks only the **net margin delta** (same-size/smaller frees margin → skipped); a denial never
  aborts the close — open skipped, user flat → `ROUTED_FLATTEN_ONLY`. Runs AFTER reconcile (a pre-route
  pre-flight would skip the broker-truth reconcile and leave a live position unmanaged). The pre-flight
  is scoped to the **routed account** (`getPortfolio(intent.brokerAccountId())`), not the gateway default.
- **D3 — router-account-aware ENTRY_SUBMITTED boot replay.** The router persists WTX entries under
  `WTX_AUTO`, exactly the set `WtxStaleEntryReconciler` already scans, so it IS the per-row boot/stale
  replay. Fixed `effectiveAccount()` to treat the router's `"__default__"` placeholder as "no specific
  account" — without it a `NOT_FOUND` order read as flat and a row backed by a **live position was wrongly
  CANCELLED** (orphaned). Added a gate-tied one-shot `bootReplayWhenReady()` so a restart unblocks the
  strategy in seconds.

**D2 (reverse open serialised behind the close FILL) was pulled.** It was built and tested, but Codex
correctly flagged that fill-driven deferral is incompatible with WTX's *synchronous* virtual-state model:
`WtxStrategyService` commits the optimistic new side from the synchronous routing outcome and never
reconciles `currentPosition` from broker truth, so a deferred reverse whose close later cancels leaves the
strategy on the wrong side (unmanaged position, wrong PnL / 17:00 force-close). D2 therefore depends on a
**WTX-state-reconcile-from-broker-truth** that does not exist yet. The pre-D2 behavior (open on
close-accept) remains — it is already downstream-protected (no naked exit). D2 + the state-reconcile is the
next slice (reflog refs `e9a12f2`/`0b55c41`/`72a9273` hold the pulled implementation).

**Next:** build the WTX virtual-state reconcile-from-truth, then re-land D2 on it; then flip the flag for
WTX and migrate the other four strategies.

## WTX: reconcile stuck ENTRY_SUBMITTED rows (un-freeze the strategy) (2026-06-02)

**Problem (prod follow-up to #368).** WTX entries are `LMT` `DAY` limit orders. The fill
tracker only flips a row `ENTRY_SUBMITTED → ACTIVE/CANCELLED` on the matching `orderStatus`
callback. If that callback is missed (disconnect, restart, or the DAY order expiring while the
app is down) the row stays `ENTRY_SUBMITTED` forever. The confirmed-flat reconcile from #368 then
reads it as an entry still "in flight" → returns `SKIPPED_ENTRY_IN_FLIGHT` on every signal,
**freezing the strategy** (panel shows a phantom position; reverses show `ENTRY IN FLIGHT`,
same-side signals show `NONE`). Repro: user had no live order at IBKR but the panel stayed stuck.

**Fix — a scheduled reconciler that checks the broker's order truth:**

- New `WtxStaleEntryReconciler` (`application/service/strategy`, `@ConditionalOnProperty
  riskdesk.wtx.enabled`, `@Scheduled`). For each `WTX_AUTO` + `ENTRY_SUBMITTED` row older than a
  grace period it looks the order up by `executionKey` (which IS the IBKR `orderRef`) — live
  orders first, then completed orders — and reconciles **deterministically**:
  `Cancelled`/`ApiCancelled`/`Inactive` → `CANCELLED`; `Filled` → `ACTIVE` (missed-fill);
  live (`Submitted`/`PreSubmitted`/…) → left; **`NOT_FOUND` (confirmed absent) gated on the
  position truth**: cancel only when IBKR holds **no position** for the instrument (the order
  never filled into a position); if a position exists the order likely filled (fill aged out of
  completed orders) → left. Anything uncertain is left untouched — it never guesses. No broker
  side effect; it only writes the local tracking row.
- Order lookup is a **tri-state** that never reads an outage as absence: `IbGatewayNativeClient
  .lookupOrderByOrderRef` returns `FOUND` / `NOT_FOUND` / `UNAVAILABLE`. `NOT_FOUND` requires **both**
  books to have been *fully delivered* (the live `openOrderEnd` / completed `completedOrdersEnd`
  callback fired) and the order absent; a **timeout or async error** on either book yields
  `UNAVAILABLE` (tracked via an `endReached` flag, not just an empty result). Surfaced as
  `BrokerOrderLookup` via `IbkrBrokerGateway.findOrder` (default `UNAVAILABLE`) →
  `IbkrOrderService.findOrder`. New repo port method `findByTriggerSourceAndStatus`.
- The flat check `isInstrumentFlat` mirrors the bridge's confirmed-flat (no nonzero matching leg;
  offsetting rollover legs count as a live position) **and scopes positions to the row's account**
  (a multi-account gateway returning another account's position must not block the reconcile).
- The bridge routing path is **unchanged** (still reads the cached position snapshot, stays fast);
  the reconciler runs out-of-band and unblocks within one interval (~1 min).
- Config: `riskdesk.wtx.stale-entry.{reconcile-interval-ms,initial-delay-ms,grace-seconds}`.

Tests: `WtxStaleEntryReconcilerTest` (14) — cancelled/inactive→CANCELLED, filled→ACTIVE, live→left;
not-found+flat→CANCELLED, +position→left, +offsetting-legs→left, +other-account-position→CANCELLED,
+same-account-position→left, +portfolio-unavailable→left; unavailable→left (no position read);
within-grace→not-looked-up; IBKR-disabled→no-op; lookup-throws→left. Full suite green.

## WTX: no naked orders when IBKR is confirmed flat (2026-06-01)

**Problem (prod, reported from the panel).** The WTX auto-IBKR bridge fired a REVERSE
as TWO orders (close leg to flatten the prior side + open leg for the new side). When
the broker position had been flattened **outside WTX** — a manual close, or a side that
was opened while `Auto-IBKR` was OFF so no order ever reached IBKR — IBKR was actually
**flat**, but the stale `trade_executions` row was still `ACTIVE`. `WtxExecutionBridge`'s
IBKR-truth reconcile treated `livePos == 0` (confirmed flat) the same as `livePos == null`
(snapshot unavailable) and passed the REVERSE through unchanged → the close leg became a
**naked order** (a BUY that *opens* instead of flattening) on top of the open leg. Result:
two orders, ~double exposure, one resting unfilled in limit. The pure CLOSE path
(`handleClose`: MAX_LOSS / NY-force / trailing) never consulted IBKR at all, so it had the
same naked-flatten risk.

**Fix — trust a *confirmed-flat* IBKR snapshot, only in `WtxExecutionBridge`:**

- `reconcileWithIbkr`: split `livePos == null` (snapshot unavailable → legacy passthrough)
  from `livePos == 0` (IBKR confirmed flat). On confirmed flat, **downgrade**
  `REVERSE_TO_LONG/SHORT → OPEN_LONG/SHORT` so no close leg runs — exactly one order at the
  panel qty.
- `handleEntry`: on confirmed flat, resolve our own non-terminal WTX row before opening.
  An `ACTIVE` row (a filled position the broker no longer holds) is voided (`→ CANCELLED`)
  so the new OPEN is the sole active row. An **in-flight** row (`ENTRY_SUBMITTED` /
  `ENTRY_PARTIALLY_FILLED` …) means an entry is resting **unfilled** — IBKR reads flat only
  because it hasn't filled yet, so the open is **skipped** with the dedicated
  `SKIPPED_ENTRY_IN_FLIGHT` outcome to avoid a double fill once both rest. DB-only, no broker
  side effect. The caller (`WtxStrategyService`) applies the action optimistically before
  routing, so on `SKIPPED_ENTRY_IN_FLIGHT` it **reverts** the virtual state to `preActionState`
  (the only live order is the resting one — keep pointing at that side, not the never-opened
  new side). A new `WtxRoutingOutcome` value distinct from `SKIPPED_DUPLICATE`, whose
  reconcile-same-side case must *keep* the applied state.
- `handleClose`: on confirmed flat, never send a naked flatten. An `ACTIVE` row (filled
  position the broker no longer holds) is voided → `SKIPPED_NO_OPEN_ROW`. An **in-flight** row
  (`ENTRY_SUBMITTED` / `ENTRY_PARTIALLY_FILLED`) is the resting unfilled entry that *makes*
  IBKR read flat — the flatten would be a naked opposite-side order, so it is skipped **without
  voiding** (the order is still live & tracked) → `SKIPPED_ENTRY_IN_FLIGHT`. An `EXIT_SUBMITTED`
  flatten already in flight is left for the fill tracker.
- **Caller keeps the position side on `SKIPPED_ENTRY_IN_FLIGHT`.** All four `WtxStrategyService`
  flatten sites (signal close, swing-bias rewrite-to-close, MAX_LOSS halt, NY force-close, plus
  the main open/reverse branch) skip their virtual-state flatten on that outcome — no broker
  order was sent, so the panel keeps pointing at the resting entry's side. The **MAX_LOSS halt**
  additionally **defers the latch**: it skips `state.withMaxLossHit()` (which forces FLAT) when the
  flatten was deferred, so the retry gate (`!state.maxLossHit()`) stays open and the halt re-fires
  on a later bar once the entry fills — otherwise the resting entry would fill into an unmanaged,
  un-haltable position.
- **A filled `ACTIVE` row the broker no longer holds is genuine drift** (voided); an unfilled
  resting entry legitimately reads flat and must never be voided (would corrupt the live order),
  stacked on (would double fill), or flattened against (would be naked).
- **"Confirmed flat" ≠ net == 0.** `readIbkrPositionState` does ONE account-scoped snapshot read
  and returns both the signed `net` (for the same/opposite reconcile) and `confirmedFlat` — true
  only when there is **no matching nonzero leg at all**. Offsetting legs across expiries (a rollover/
  calendar overlap holding `+1 MCLM6` and `-1 MCLU6`) net to zero but are LIVE: `confirmedFlat` is
  false, so the reverse keeps its two-leg behaviour and the close flattens the tracked leg instead of
  voiding it. Only `confirmedFlat` gates the void / REVERSE→OPEN downgrade / in-flight skip.
- Unchanged when the snapshot is unavailable (`null`) or when IBKR holds a real position —
  the existing OPEN→REVERSE upgrade / duplicate-skip / synthesize paths are untouched.

Tests: `WtxExecutionBridgeTest` +8 (reverse-on-flat downgrade with/without stale row,
close-on-flat void+skip, snapshot-unavailable legacy two-leg, in-flight entry open-skip,
in-flight entry close-skip, offsetting-legs reverse keeps two legs, offsetting-legs close
flattens tracked leg) — 53 green; full suite 1830 green.

**Known gap (follow-up, not in this change):** `IbkrWtxRsiExecutionBridge` (the separate
WTX+RSI strategy) has no IBKR-truth reconcile at all — its `submitClose`/`submitOpen` trust
the DB row, so the same naked-order class is latent there. It would need an
`IbkrPortfolioService` wired in.

## WTX+RSI: unified FSM via Reducer + Command (2026-05-31)

**Problem.** The live orchestrator (`WtxRsiStrategyService`) and the backtest
engine (`WtxRsiBacktestEngine`) each re-implemented the position transition logic
(open / reverse / suppress / chaikin-block / SL-TP), and they had **silently
diverged**: live filled entries at `signal.close()`, backtest at the *next bar's
open*; backtest had no swing-bias filter and used a different quantity rule. The
simulated P&L was therefore not comparable to live — the measured edge was wrong.

**Fix — single pure transition function shared by both:**

- New `domain/.../wtxrsi/WtxRsiTransition.reduce(state, bar, candles, signal?, bias, config)
  → (newState, List<WtxRsiDecision>)`. Pure FSM, no Spring/JPA/IBKR (ArchUnit-guarded).
- New `WtxRsiDecision` (`sealed`: `Open`, `Close(cause)`, `Suppress`, `Block`, `Reject`)
  — the Command half: decisions are data; nothing is executed inside the reducer.
- `WtxRsiStrategyService` is now a thin interpreter: it resolves the bias upstream
  (SMC engine stays out of the pure reducer), calls `reduce`, then `execute(decision)`
  is the ONLY place that routes to IBKR / persists / publishes WS. **Live behaviour
  is unchanged** — `WtxRsiStrategyServiceTest` (13) stays green untouched.
- `WtxRsiBacktestEngine` now drives the same `reduce`. **Behaviour change (intended):**
  entries fill at the signal-bar close (not next-bar open); the chaikin gate and
  (optional) swing-bias filter apply as live; qty uses `configuredOrderQty`. Equity
  curves shift vs the old engine. `run(candles, swingBiasFilterEnabled)` overload
  added; default keeps the filter off (live default). `SMC_ENGINE` bias is not
  replayable in a pure backtest → it uses `FRACTAL_HH_HL`.
- Parity guard: `WtxRsiBacktestParityTest` proves the engine reproduces a reference
  `reduce` loop trade-for-trade, and that entries fill at the rounded signal close.
- Concurrency: `onCandleClosed` + the REST toggles now serialise the
  load→reduce→save read-modify-write under a per-(instrument, timeframe) monitor
  (single-node guard) — kills the lost-update race and duplicate IBKR routing.

Tests: `WtxRsiTransitionTest` (8), `WtxRsiBacktestParityTest` (1); full `WtxRsi*`
suite 54 green; `HexagonalArchitectureTest` green (reducer purity).

## Live wiring: Iceberg / Spoofing / Flash-Crash detectors (2026-05-29)

## Perfect Setup — order-flow confluence detector (2026-05-29)

**New feature.** Fuses the individual order-flow signals (which previously had to
be combined by eye) into a single transition-based ARMED signal.

- **Domain (pure):** `domain/orderflow/perfectsetup/` — `PerfectSetupDetector`
  scores 6 axes per direction (REGIME / ICEBERG / ABSORPTION / VALUE /
  LIQUIDITY_GRAB / RISK_REWARD), resolves the dominant direction, and runs the
  state machine `IDLE → LONG_ARMED/SHORT_ARMED → TRIGGERED | INVALIDATED | EXPIRED`
  with a post-terminal cooldown. Arms when passing axes ≥ `arm-threshold` (default
  **4/6**) **and** R:R ≥ `min-rr` (hard gate). Stateless — caller feeds back the
  prior signal. `PerfectSetupDetected` domain event added.
- **Application:** `application/service/perfectsetup/PerfectSetupService` — a
  `@Scheduled` (5s) evaluator that gathers inputs from **existing beans only**
  (`AbsorptionPort` / `DistributionPort` / `CyclePort`, `IndicatorsPort`,
  `LivePricePort`, `OrderFlowHistoryService.recentIcebergs`,
  `FlashCrashStatusService`, `CandleRepositoryPort` for ATR), runs the detector,
  keeps the latest signal per instrument in memory, publishes `/topic/perfect-setup`
  every scan, emits `PerfectSetupDetected` on each state **transition**, and
  optionally bridges an ARM to the auto-arm pipeline.
- **Auto-arm bridge (opt-in, default OFF):** `QuantAutoArmService.armFromPerfectSetup`
  reuses the existing `trade_executions` persist+event path with new
  `ExecutionTriggerSource.PERFECT_SETUP`. Gated by `riskdesk.perfect-setup.auto-arm.enabled`;
  a live broker order still additionally requires `riskdesk.quant.auto-submit.enabled`.
  Shared active-execution + cooldown gates prevent double-arming with the 7-gate path.
  `PERFECT_SETUP` is also in `QuantAutoSubmitScheduler`'s auto-submit source set and in
  `PlaybookAutomationService.CROSS_STRATEGY_BLOCKING_SOURCES`, so a live Perfect Setup
  execution both auto-submits (when the auto-submit flag is on) and blocks a duplicate
  Playbook order on the same instrument/account.
- **REST:** `GET /api/perfect-setup`, `GET /api/perfect-setup/{instrument}`.
- **Frontend:** `useOrderFlow` subscribes to `/topic/perfect-setup`; new
  `PerfectSetupPanel.tsx` (composed in `Dashboard.tsx`) renders the 6-axis
  checklist + ARMED verdict + entry/SL/TP/R:R. `api.getPerfectSetups()` seeds it.
- **Config:** `riskdesk.perfect-setup.*` in `application.properties` (detector ON
  by default; bridge OFF). **No DB schema change** — signal is in-memory + on the wire.

## Live wiring: Iceberg / Spoofing / Flash-Crash detectors (2026-05-29)

**Root cause found.** `IcebergDetector`, `SpoofingDetector` and `FlashCrashFSM`
had a complete downstream pipeline — domain events → `OrderFlowCorrelationService`
(`/topic/{iceberg,spoofing,flash-crash}`) + `OrderFlowEventPersistenceService`
(DB) → frontend `useOrderFlow.ts` subscriptions — but the detectors were **never
invoked on the live feed**. `IcebergDetector`/`SpoofingDetector` had zero
production callers; `FlashCrashFSM` was only used by the offline
`FlashCrashSimulationService` (replay). So the three events were never published
→ topics never emitted → panels permanently empty and tables never written, for
**every** instrument (not just MNQ). The wall-event data (`MutableOrderBook` →
`MarketDepthPort.recentWallEvents`) and depth/tick inputs already existed; only
the orchestration was missing.

**Fix — wired into `OrderFlowOrchestrator` (the existing live driver):**

- **Iceberg + Spoofing**: new `@Scheduled evaluateBookManipulation()` (2s) reads
  `recentWallEvents()` per depth instrument, derives `tickSize` / mid price /
  `avgLevelSize` from `DepthMetrics`, calls the (stateless, shared) detectors, and
  publishes `IcebergDetected` / `SpoofingDetected`.
- **Flash Crash**: `evaluateFlashCrash()` runs inside the existing 5s order-flow
  loop with a **stateful FSM per instrument**. Builds a live `FlashCrashInput`
  (velocity from the short tick window, `delta5s`, acceleration vs previous
  velocity, live `depthImbalance`, volume-spike ratio). Thresholds load from
  `FlashCrashConfigPort` (cached, refreshed every 60s) with
  `FlashCrashThresholds.defaults()` fallback. Publishes `FlashCrashPhaseChanged`
  **only on a phase transition** (transition-based, like the alert system).
- **`RecentSignalGate`** (new application helper) de-dups re-scanned wall-event
  windows so a pattern still inside the lookback is emitted once, not every 2s.
- New config block `riskdesk.order-flow.{iceberg,spoofing,flash-crash}.*` in
  `OrderFlowProperties` + `application.properties` (all enabled by default).
- Constructor of `OrderFlowOrchestrator` gained `FlashCrashConfigPort`.

**Calibration note:** `depth.wall-threshold-multiplier=5.0` governs whether wall
events are emitted at all; on very liquid MNQ this may need tuning if icebergs
stay quiet. The flash-crash `depthImbalanceThreshold=0.3` is met for most
balanced/sell-skewed books — it is one of 5 conditions (3 required), so a genuine
event still needs 2 of the stringent velocity/delta/accel/volume conditions.
Tune thresholds via `FlashCrashConfigPort` once real wall events are observed.

Tests: `RecentSignalGateTest`, `OrderFlowManipulationWiringTest` (regression guard
against the wiring being removed again). Detectors keep their existing unit tests.

## Client-side trim: MentorDesk removed, Engine v2 deleted, Exec/Sim gated (2026-05-28)

## Client-side trim: MentorDesk removed, Engine v2 deleted, Exec/Sim gated (2026-05-28)

Driven by excessive client-side resource use and the operator decision that AI
MentorDesk is no longer useful. Three coordinated changes:

**Frontend (resource reduction).**

- Deleted the AI MentorDesk panel and its subtree: `AiMentorDesk.tsx`,
  `MentorPanel.tsx`, `MentorSignalPanel.tsx` (the ~47KB review UI),
  `TradeDecisionPanel.tsx`, `SimulationDashboard.tsx`, `TrailingStopStatsPanel.tsx`.
- `useWebSocket.ts` no longer holds the 1000-item review buffer, no longer sorts
  on every message, dropped the `/topic/mentor-alerts` subscription and the two
  30s Mentor polls. `/topic/prices` is now coalesced via `requestAnimationFrame`.
- 1s `setInterval` clock tickers (`ExternalSetupPanel`, `QuantGatePanel`) only run
  while a countdown is actually active, then stop.
- `OrderFlowPanel`, `Chart`, `IndicatorPanel`, `QuantGatePanel` wrapped in
  `React.memo` to stop per-tick re-renders.

**Engine v2 deleted.** `SignalConfluenceBuffer` (the weighted accumulate/flush
`@Scheduled` engine) is removed. `AlertService` now triggers a unitary
`captureInitialReview(...)` per qualified directional alert — no consolidation.
`MentorSignalReviewService.captureConsolidatedReview(...)` (only caller was the
buffer) deleted. `riskdesk.confluence.*` config removed. The `SignalWeight` enum
is **kept** (shared with `SignalPreFilterService`).

**Mentor / Execution / Simulation isolated behind `riskdesk.mentor.enabled`**
(default `false`, reversible). When off: `MentorSignalReviewService` capture +
cleanup scheduler early-return; all three `TradeSimulationService` schedulers
early-return (no 60s polling); `ExecutionManagerService` is dormant (HTTP-only,
no UI caller after the frontend trim). No DB schema changes —
`mentor_signal_reviews` / `trade_executions` tables are retained.

See `docs/SPEC_CONFLUENCE_BUFFER.md` (marked removed) for the historical buffer spec.

## Playbook Auto-Simulation + Auto-IBKR (2026-05-22)

PLAYBOOK now has a candle-close automation path that stays inside the internal
IBKR/PostgreSQL data flow. It evaluates deterministic `PlaybookService` output
on `CandleClosed`, persists a frozen `PlaybookDecision`, starts forward paper
simulation at 4/7, and can route a live IBKR entry at 5/7 only when Auto-IBKR is
explicitly enabled for that `(instrument,timeframe)`.

What landed:

- BDD acceptance coverage in `src/test/resources/features/playbook-auto-routing.feature`
  with executable test glue under `src/test/java/com/riskdesk/bdd/steps/`.
- Domain tests in `src/test/java/com/riskdesk/domain/playbook/automation/`.
- `PlaybookAutomationService`, `PlaybookDecision`, and `playbook_automation_states`
  / `playbook_decisions` persistence.
- `ReviewType.PLAYBOOK` simulation resolution from `PlaybookDecision`, not fake
  Mentor review JSON.
- `ExecutionTriggerSource.PLAYBOOK_AUTO` live routing through the existing
  `IbkrOrderService`, including ACK-pending handling and broker preflight reuse.
- REST endpoints:
  - `GET/PUT /api/playbook/automation/{instrument}/{timeframe}`
  - `GET /api/playbook/automation/{instrument}/{timeframe}/decisions?limit=N`
- WebSocket fan-out on `/topic/playbook-decisions/{instrument}/{timeframe}`.
- Execution profile state on `playbook_automation_states`: `LEGACY` by default,
  optional `MGC_10M_SCALP_0_5R` for `MGC 10m BREAK_RETEST` after manual
  validation, and `MGC_10M_NORMAL_1R_BENCHMARK` reserved as non-executable
  benchmark until replay support lands.

Important safety gates:

- Auto-IBKR defaults OFF and requires an explicit account-bound toggle.
- Live routing requires complete entry/SL/TP, score >= 5/7, positive quantity,
  non-late entry, live IBKR price source, no active duplicate, IBKR enabled, and
  broker preflight approval.
- Non-legacy Playbook profiles are scoped to `MGC 10m`; the 0.5R scalp profile
  cannot route live until `scalpProfileValidated=true`, and the 1R profile is
  not armable.
- Playbook live routing also checks active executions from `PLAYBOOK_AUTO`,
  `WTX_AUTO`, `WTXRSI_AUTO`, and `QUANT_AUTO_ARM` for the same instrument/account
  before submitting a new IBKR entry.
- Paper simulation still records late entries for forward stats.

## IBKR — persistent account snapshot subscription (2026-05-20)

Fixes the structural cause of recurring WTX `ACK_PENDING` / `TIMEOUT` chips when no
order ever reaches IBKR (incident 2026-05-20 09:10 UTC, ~90 → 109 `request timed out`
warnings per 10 min during EU/US open windows).

**Root cause.** `IbGatewayNativeClient.requestAccountSnapshot(...)` used to subscribe,
await `accountDownloadEnd`, and unsubscribe on every call:

```java
controller.reqAccountUpdates(true,  accountId, handler);
accountLatch.await(15s);
controller.reqAccountUpdates(false, accountId, handler);
```

Although the method itself is `synchronized` on `accountSnapshotLock`, IBKR Gateway TWS
reacts to a rapid subscribe/unsubscribe cycle by emitting `code=2100 "API client has
been unsubscribed from account data"` and dropping the in-flight handler — the
`accountLatch` is never tripped, the 15s timeout fires, and the same controller
connection that should be delivering `orderStatus` callbacks is saturated with retries.
Production logs showed 27-30 `code=2100` per 10 min in baseline, spiking to 109 during
EU/US market open, with collateral damage on the order-acknowledgement path.

**Fix.** New `PersistentAccountSnapshotCache` (in
`infrastructure/marketdata/ibkr/`) implements `ApiController.IAccountHandler` and is
registered **once** per connection via `reqAccountUpdates(true, accountId, cache)`.
Callbacks flow into `ConcurrentHashMap` mirrors of account values and positions.
`requestAccountSnapshot(...)` now returns a defensive copy of the cache — readers
never round-trip to IBKR. Bootstrap waits at most `REQUEST_TIMEOUT` for the initial
`accountDownloadEnd`; subsequent calls are constant-time map copies.

The persistent subscription is captured into `DisconnectContext` so `submitCleanup`
can `reqAccountUpdates(false, ...)` it on the old controller before disconnect —
forgetting this would leave a dangling broker-side subscription and the next reconnect
would open a second one in parallel, reintroducing the very pattern this layer eliminates.

The application-layer 5s cache in `IbGatewayBrokerGateway` is unchanged — it now
coalesces frontend-poll bursts onto a single defensive copy rather than gating an
expensive upstream call.

Tested: `PersistentAccountSnapshotCacheTest` (12 cases) covers handler-callback
contract, defensive-copy semantics under concurrent writers + readers, position
flatten dropping, cross-account filtering, and bootstrap latch behavior.

## WTX auto-execution — ack timeout reconciliation (2026-05-19)

Fixes the confusing "TIMEOUT but the order passed in IBKR" case.

- `FAILED_TIMEOUT` now means no acknowledgement and no broker order id was available.
- When the native IBKR client has already assigned/sent a broker `orderId` but the first
  acknowledgement arrives after `riskdesk.ibkr.order-ack-timeout-ms`, WTX returns
  `ACK_PENDING` instead of a red failure. The execution row persists that `ibkrOrderId` so
  delayed `orderStatus` / `execDetails` callbacks can reconcile it.
- Open-leg ack-pending rows move to `ENTRY_SUBMITTED`; close-leg ack-pending rows move to
  `EXIT_SUBMITTED` to avoid duplicate flatten orders while the broker state is still unknown.
- `ExecutionFillTrackingService` now promotes a pending entry to `ENTRY_SUBMITTED` when a late
  `Submitted` / `PreSubmitted` / `PendingSubmit` callback arrives.
- Frontend WTX signal cards render this state as `ACK ?`, not `TIMEOUT`.

## WTX — per-(instrument, timeframe) state + routing visibility (2026-05-14)

Fixes the "Auto-IBKR : ON but no order" report. Two root causes:

- **WTX state was keyed by `instrument` only**, so the 5m and 10m candle-close events for the
  same instrument loaded/mutated/saved the *same* row. A position opened by one timeframe made
  same-direction signals on the other return `NONE` (which never routes), and `REVERSE` legs
  thrashed each other's `entryQty`/`entryPrice`/P&L.
- **Routing failures were silent** (DEBUG/WARN only) — nothing on the signal told you whether a
  routed signal reached IBKR or was dropped at a gate.

What changed:
- `WtxStrategyState` now carries `timeframe`; `wtx_strategy_states` has a **composite primary
  key `(instrument, timeframe)`** (`@IdClass WtxStrategyStateId`). Profile, auto-execution,
  position, equity and daily max-loss are all per-timeframe now — `maxDailyLossUsd` applies
  **per timeframe** (effective per-instrument budget is 5m + 10m).
- **DB migration is automatic.** Hibernate `ddl-auto=update` cannot alter a primary key, so
  `WtxStrategyStateSchemaMigration` runs *before* the JPA `EntityManagerFactory` (ordered via
  `WtxStrategyStateSchemaMigrationDependsOnPostProcessor`): if it detects the legacy
  instrument-only `wtx_strategy_states` table (no `timeframe` column) it drops it so Hibernate
  recreates it with the composite key. The guard is idempotent — a fresh DB or an
  already-migrated table is a no-op. The table is pure runtime state (rebuilt from candles on
  the next close), so no manual `DROP TABLE` step is needed anymore.
- REST routes are now `/api/wtx/state/{instrument}/{timeframe}` (+ `/profile`,
  `/auto-execution`). WS state topic is `/topic/wtx-state/{instrument}/{timeframe}`.
- `WtxExecutionBridge` lookups (`findActiveByInstrumentAndTimeframeAndTriggerSource`) and the
  `executionKey` (`wtx:<instrument>:<timeframe>:<signalTs>:<action>`) are timeframe-scoped — a
  10m close/reverse can no longer target a 5m execution row.
- New `WtxRoutingOutcome` enum (`ROUTED`, `SKIPPED_AUTO_OFF`, `SKIPPED_BRIDGE_UNAVAILABLE`,
  `SKIPPED_IBKR_DISABLED`, `SKIPPED_DUPLICATE`, `SKIPPED_NO_PRICE`, `SKIPPED_NO_QTY`,
  `SKIPPED_NO_OPEN_ROW`, `FAILED`). `WtxExecutionBridge.submit` returns it, every gate logs the
  reason at INFO, and it is persisted on `wtx_signal_history.routing_outcome`, broadcast on
  `/topic/wtx-signals`, and rendered as a chip on each signal card in the WTX panel.

## WTX auto-execution — lifecycle correctness fix (2026-05-14)

Follow-up to PR #325, addressing Codex review findings on `WtxExecutionBridge`:

- **Broker-side action token.** Execution rows store `action = "LONG"/"SHORT"` instead of the
  WTX enum name. This is the token `IbGatewayBrokerGateway` interprets correctly (only `"SHORT"`
  maps to `Action.SELL`; anything else is a BUY — so `"BUY"`/`"SELL"` would both be misread as
  buys) and which `ActivePositionView` also resolves to direction + PnL sign. The WTX semantic
  action is preserved in `statusReason`.
- **Exit lifecycle.** `CLOSE_LONG` / `CLOSE_SHORT` no longer create a fresh `ENTRY_SUBMITTED`
  row. The bridge locates its own open `WTX_AUTO` execution row via the new
  `TradeExecutionRepositoryPort.findActiveByInstrumentAndTriggerSource(...)`, submits the flatten
  order against it, and transitions that row to `EXIT_SUBMITTED` (non-terminal).
- **REVERSE = two 1:1 orders.** A `REVERSE_*` is decomposed into a **close leg** against the
  prior row (→ `EXIT_SUBMITTED`) and an **open leg** for the new row (→ `ENTRY_SUBMITTED`) —
  two real broker orders instead of one doubled order. This means each row is a clean 1:1
  `order ↔ row` pair the standard fill tracker reconciles via its own `ibkrOrderId`; there is
  no "one order, two rows" mismatch and the prior row is never stranded (terminal-before-fill
  or orphaned-non-terminal). All open-leg validation runs before the close leg, and if the
  close leg is rejected the reverse aborts without opening anything.
- **Exit-fill reconciliation.** `ExecutionFillTrackingService.onOrderStatus` now transitions an
  `EXIT_SUBMITTED` row to `CLOSED` on the `Filled` callback. That callback is located **only by
  `orderId`** (`onOrderStatus` receives no `orderRef`), so the bridge persists the broker order id
  on `ibkrOrderId` at submission time for both entries and closes — otherwise an early
  `Filled` status arriving before `execDetails` would be dropped. `handleClose` also skips
  submission when the open row is already `EXIT_SUBMITTED` — no duplicate flatten while a close
  is in flight.
- When a CLOSE finds no open WTX row, it logs a warning and skips submission — never fires a naked order.

## WTX Strategy — Pine Script profile parity (2026-05-13)

The WTX (WaveTrend XT) runtime now mirrors the four profiles of the reference Pine
Script `RiskDesk WT_X MNQ Filtered - TV v1`. Profile is stored **per instrument** in
`wtx_strategy_states.active_profile` and is switchable live from the WTX panel.

| Profile | Behaviour |
|---|---|
| `BASELINE` | Raw WT crossover + zone signals only. Preserves the legacy behaviour — no max-loss gating beyond NY force-close. |
| `SESSION_ATR` | Adds the daily max-loss kill switch (fixes a regression where `canTrade` ignored `maxLossHit`) and ATR trailing exits. |
| `HTF` | Above + 60m EMA21/55 bias filter (configurable). A bearish HTF blocks longs; bullish HTF blocks shorts. |
| `STRICT` | Above + structure proxy (sweep-and-reclaim / break-and-reclaim) ported bar-for-bar from Pine. |

### What changed
- `WtxProfile` enum + `state.activeProfile` field, default `BASELINE` (no regression for existing users).
- `WtxRiskGuard.canTradeForProfile(profile, maxLossHit, forceCloseWindow)` — the daily-loss flag now blocks new trades from `SESSION_ATR` upward.
- `WtxTrailingExitEvaluator` — fixed initial stop at `slAtrMult × ATR`, switches to trailing at `bestFavorablePrice ± trailingAtrMult × ATR` once the position is in profit by `trailingActivationR × slAtrMult × ATR`. Exits emit synthetic `CLOSE_LONG` / `CLOSE_SHORT` signals on `/topic/wtx-signals`.
- `WtxHtfBiasFilter` (pure function) + HTF candle fetch in `WtxStrategyService.buildHtfContext`.
- `WtxStructureFilter` (pure function) — rolling priorLow/priorHigh over `structureLookback` bars, sweep buffer `sweepBufferAtr × ATR`, reclaim buffer `0.25 × ATR`.
- `WtxEnrichmentSnapshot` now carries `htfBias`, `structurePassed`, `structureReason` so the panel can show *why* a signal was blocked.
- `WtxExecutionBridge` (opt-in per instrument) routes WTX actions to IBKR via the existing `IbkrOrderService`. Idempotence is keyed by `wtx:<instrument>:<signalTs>:<action>` in `trade_executions`. Trigger source: new `ExecutionTriggerSource.WTX_AUTO`.
- Two new endpoints — `PUT /api/wtx/state/{instrument}/profile` and `PUT /api/wtx/state/{instrument}/auto-execution`.
- UI: profile dropdown + Auto-IBKR toggle with confirmation modal. Panel border turns red while auto-IBKR is ON.

### Safety defaults
- `autoExecutionEnabled = false` for every instrument on first contact. The user must opt in explicitly via the modal-confirmed toggle.
- `activeProfile = BASELINE` for every instrument on first contact.
- HTF fetch failures (insufficient 1h history) fall back to permissive — never silently block.

### Configuration
New properties under `riskdesk.wtx.*` in `application.properties`:
```
riskdesk.wtx.atr-length=14
riskdesk.wtx.sl-atr-mult=1.4
riskdesk.wtx.tp-atr-mult=2.1
riskdesk.wtx.trailing-atr-mult=2.0
riskdesk.wtx.trailing-activation-r=0.5
riskdesk.wtx.htf-timeframe=1h
riskdesk.wtx.htf-fast-len=21
riskdesk.wtx.htf-slow-len=55
riskdesk.wtx.structure-lookback=12
riskdesk.wtx.sweep-buffer-atr=0.05
riskdesk.wtx.broker-account-id=<your IBKR account or "wtx-default">
```

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

**Frontend:** `QuantGatePanel.tsx` (the live 7-gates panel) was **removed**
2026-06-05 — see the changelog entry at the top of this doc. The `useQuantStream`
hook (subscribing to `/topic/quant/snapshot/{instr}`) lives on, now consumed by
`OrderFlowPanel` and `QuantSetupNotification`; the latter still fires a one-shot
WebAudio cue and a toast when the backend confirms a 7/7 setup. Historical
description of the removed panel: it rendered the 7 gates with ✅/❌ + reason.

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
- tagged releases are now deployed by the `Tag & Deploy` workflow to the GCE VM through an IAP SSH tunnel after images are pushed to GCP Artifact Registry
- the deploy workflow expects GitHub Actions variables `GCP_PROJECT_ID`, `GCP_REGION`, `GCP_ZONE`, `GCP_ARTIFACT_REGISTRY_REPOSITORY`, `GCP_INSTANCE_NAME`, `GCP_CONFIG_BUCKET`, `GCP_WORKLOAD_IDENTITY_PROVIDER`, `GCP_DEPLOY_SERVICE_ACCOUNT`, and `GCP_DEPLOY_SSH_USER`
- the deploy workflow expects GitHub Actions secret `DEPLOY_SSH_PRIVATE_KEY`
- `GCP_DEPLOY_SSH_USER` must be a VM user with the deploy public key in `~/.ssh/authorized_keys` and passwordless `sudo`; do not hardcode `root` in workflows
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
- prod host is now `riskdesk-prod-v2` (Tailscale `100.69.177.128`, API/nginx `:3000`); old `riskdesk-prod` (`100.113.139.64`) is deprecated (Tailscale idle)
- on prod the IB Gateway runs as a Docker container reached internally as `ibkr-gateway:4003` (`GET /api/ibkr/auth/status` → `socket://ibkr-gateway:4003`); it is NOT exposed on the host's Tailscale IP (`nc -z 100.69.177.128 4003` fails)
- local `application-local.properties` must set `riskdesk.ibkr.native-host` to a reachable IB Gateway and `riskdesk.ibkr.native-port=4003`; verify with `nc -z <host> 4003`

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
