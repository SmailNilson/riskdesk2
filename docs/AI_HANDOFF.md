# AI Handoff

Last updated: 2026-06-15

## DIV Paper panel — viewer for the CVD-divergence paper loop (2026-06-15)

Frontend-only. Surfaces the RTH-gated "trade the DIV badge" paper simulation
(backend shipped in PR #463) so its edge can be read without curl-ing the API.

- New section `DIV Paper — {instrument}` in `OrderFlowPanel.tsx`, placed right
  under the Delta bars (where the DIV badge lives). Desktop-only, like the rest
  of the panel — it never mounts on mobile.
- `DivPaperPanel` polls `GET /api/order-flow/cvd-divergence/paper/{instrument}?days=7`
  every 30s (trades open/close on the server's 5s scheduler). Stat strip: 7-day
  PnL (points + $), win rate (W/L), closed + open count, LONG/SHORT split.
  Recent trades list with direction, entry→exit, signed PnL, close reason
  (badge expiré / inversion / fin RTH), open trades flagged `OUVERT`.
- API client: `getCvdDivergencePaperTrades` + `CvdDivergencePaperResponse` type
  in `lib/api.ts`. No backend change.
- Reminder for readers: paper fills are last-price (no slippage) and the pivot
  confirms ~5 bars late by construction — judge on multi-week windows.

## Rollover now deep-backfills the new contract (fixes "no 5m/10m data after roll") (2026-06-15)

After a contract roll, charts for some timeframes (notably 5m/10m) collapsed to a
handful of bars. Root cause: `CandleController` filters strictly by the **active
contract month** and only falls back to full history when *zero* bars match. The
live accumulator quickly writes a few new-month bars on fast timeframes (1m/5m/10m),
which suppresses the fallback — but the new contract's *prior weeks* were never
fetched, because the old rollover warm-up used a forward `gapFillTimeframe` (appends
only past the high-water mark, which those live bars had already advanced to now).
Coarser timeframes (no new-month bar closed yet) still showed the previous contract
via the fallback, masking the gap.

- **Fix**: `HistoricalDataService.onContractRollover` now dispatches an idempotent
  **range backfill** (`startBackfillRange`, async) per supported timeframe over
  `[now - lookback, now]` on the new front-month contract, instead of a forward
  gap-fill. Each job queues on the dedicated single-threaded backfill executor
  (IBKR-pacing safe). Lookback per timeframe is configurable
  (`riskdesk.market-data.historical.rollover-backfill-days-{1m,5m,10m,1h,4h,1d}`,
  defaults 14/60/90/200/200/200), clamped to `backfill-range-max-days`.
- **Note**: `30m` is not IBKR-backfillable (`HistoricalDataProvider.supports`
  excludes it) — it rebuilds live only; not in the rollover loop.
- **Manual recovery** if a roll predates this fix (or after a purge): the purge
  endpoint only deletes, it never refills — run
  `POST /api/candles/backfill/{inst}/{tf}?from=…&to=…&async=true` per timeframe,
  or purge + `POST /api/backtest/refresh-db` (empty timeframe → deep backfill).

## CVD-divergence paper trading loop + event persistence (2026-06-12)

The DIV badge (CVD pivot divergence, `/topic/cvd-divergence`) now drives an
**RTH-gated paper-trading simulation** so the signal's edge can be measured
before any real wiring is considered. Prior order-flow event studies showed no
detector has standalone directional edge (some are inverted) — this loop exists
to test the DIV badge against that bar, not to trade it.

- **Rule under test**: divergence fires inside RTH (09:30–16:00 ET) → simulated
  1-contract entry at last price in the divergence direction (bearish → SHORT,
  bullish → LONG). Same-direction events refresh the hold window (mirrors the
  frontend badge refresh); an opposite divergence closes and flips. Close when
  the badge window (`riskdesk.order-flow.cvd.paper-hold-seconds`, default 600s)
  lapses or RTH ends — whichever first.
- **`CvdDivergencePaperTradingService`** (application): event listener + 5s
  expiry scheduler. **Pure paper — never touches the execution path.** Closes
  defer (retry next pass) when no live price is available. Config:
  `riskdesk.order-flow.cvd.paper-trading-enabled` (default `true`).
- **`CvdDivergenceDetected`** now carries `lastPrice` (price at detection — the
  paper fill reference; distinct from the pivot price, which is ~5 bars older).
  The WS payload gained a `price` field.
- **Persistence**: `cvd_divergence_paper_trades` (trades) and
  `order_flow_cvd_divergence_events` (ALL divergence events, all sessions, with
  an `rth` flag — fodder for a future event study). Both purge at 90 days with
  the other order-flow events.
- **Endpoint**: `GET /api/order-flow/cvd-divergence/paper/{instrument}?days=7`
  → trades + stats (win rate, PnL points/currency, per-direction split).
- Caveats for whoever reads the stats: fills are last-price with no
  spread/slippage model, and the detector confirms pivots `pivotBars` (5)
  minutes late by construction — judge the edge accordingly, and only on
  multi-week windows (one good day proves nothing).

## Mobile manual trading — order ticket bottom sheet (2026-06-12)

Mobile users can now place and manage manual orders. Frontend-only — rides the
existing Quant manual-trade API; no backend change.

- **`OrderTicketSheet`** (`frontend/app/components/mobile/`): bottom sheet opened
  from "Passer un ordre" (Chart tab) or "Nouvel ordre" (Portf tab). Side
  (Acheter/Vendre), LIMIT/MARKET, qty stepper (1–10), tick-aligned price steppers,
  **SL/TP required** (backend rejects without them — prefilled at ≈ $50 risk /
  $100 reward per contract), live risk line (risk-at-stop $, target $, R:R,
  $/tick from the per-instrument contract specs mirroring `Instrument.java`).
  Submission is **hold-to-confirm (1 s)** — release early cancels. Geometry is
  validated client-side (and again server-side): wrong-side SL/TP disables the
  button with an explanation.
- **`MobilePositionsCard`** (Portf tab): working entries (Soumis → Présenté →
  Exécuté mini-stepper, one-tap Annuler), open positions (P&L, SL/TP, Clôturer
  with inline confirm — close goes out as marketable limit), `EXIT_SUBMITTED`
  shown as "clôture en cours". Live via `useActivePositions`
  (`/topic/positions` push + REST seed).
- API: `POST /api/quant/manual-trade/{instrument}` (`ManualTradeRequest` with
  `submitImmediately=true` — without it a LIMIT row rests as
  PENDING_ENTRY_SUBMISSION and never reaches IBKR — and `brokerAccountId` from
  the Dashboard account selector), `GET /api/quant/positions/active`,
  `POST /api/quant/positions/{id}/close` for live positions and `cancelEntry`
  for resting orders. `takeProfit2` exists in the API but is deliberately not
  in the mobile ticket v1. The mobile TickChart also receives
  `brokerAccountId`, so the chart click-to-trade MVP (entry below) works on
  mobile too.
- After a successful placement the app switches to the Portf tab to show the
  working order.

## Mobile cockpit design system (2026-06-12)

Second pass on the mobile UI (see entry below for the tabbed layout itself):

- `MobileVitalStrip` — one-line status + total P&L (always visible), expandable
  2×2 secondary metrics, amber offline banner. Replaces MetricsBar + ticker below `lg`.
- `MobileInstrumentPills` — live price inside each instrument pill
  (tick-direction colored, muted when STALE/FALLBACK_DB); the separate ticker
  row is desktop-only now.
- `TabIcons` — inline Lucide-geometry stroke icons (no icon-lib dependency)
  with an emerald active-indicator bar.
- `MobileCollapse` — collapsed-by-default wrapper that only mounts children
  while open; wraps WTX 10m and top-train-Z35 on the WTX tab.
- `useIsMobile` gained a `resize` fallback for viewports that don't dispatch
  matchMedia change events.
## Risk gate daily-drawdown formula fixed (2026-06-12)

**Bug.** The AGENTS panel showed "DAILY DRAWDOWN 83.7% > 3% — NO MORE TRADES TODAY" on a
micro-contract account with a small dollar loss. The drawdown was computed as
`|totalUnrealizedPnL| / totalExposure × 100` (duplicated in
`AgentOrchestratorService.buildPortfolioState` and `PortfolioStateBuilder`). On the IBKR
path `totalExposure` maps to the `GrossPositionValue` account tag, which **excludes
futures** — near-zero denominator → absurd percentages → spurious kill-switch blocks.
It also ignored today's realized P&L (not actually "daily").

**Fix.** `PortfolioSummary` gained an `accountEquity` field (IBKR: `netLiquidation`;
local fallback: configured account margin) and a single derived
`PortfolioSummary.dailyDrawdownPct()`: `max(0, -(todayRealizedPnL + totalUnrealizedPnL))
/ accountEquity × 100`. Unknown/zero equity → 0 (fail-open, consistent with the
risk gates' abstain-on-unknown posture). Both consumers now delegate to it. The 3%
threshold and blocking policy are unchanged. Tests: `PortfolioSummaryDrawdownTest`,
`PortfolioStateBuilderDrawdownTest`.

## Chart trading MVP — click-to-trade sur le Tick Chart (2026-06-11)

**Feature.** `TickChart.tsx` gains a `TRADE` toggle (off by default). When armed, a click on
the chart opens a context menu quoting the clicked price (tick-rounded via
`series.coordinateToPrice`); Acheter/Vendre opens a confirmation ticket (price / qty / SL / TP
editable, LMT or MKT) which submits a real IBKR order through the existing
`POST /api/quant/manual-trade/{instrument}` with the new `submitImmediately=true` flag.
Working orders + live position + virtual SL/TP are drawn as price lines fed by
`/topic/positions` (`useActivePositions`, also newly exposing `cancelEntry`); rows under the
chart cancel a resting entry or flatten the live position with an inline two-click confirm.
Dashboard passes `selectedIbkrAccountId` → ticket → `ManualTradeRequest.brokerAccountId`
(server falls back to `riskdesk.quant.auto-arm.broker-account-id`).

**Backend changes (the real substance — two safety holes closed + one critical bug):**
1. **Broker order cancellation now exists.** `IbGatewayNativeClient.cancelOrderById(int)`
   (ApiController.cancelOrder + IOrderCancelHandler; IBKR code 202 = success), surfaced via
   `IbkrBrokerGateway.cancelOrder` (default: unsupported) → `IbkrOrderService.cancelOrder` →
   `ActivePositionsService.cancelEntry` → `POST /api/quant/positions/{id}/cancel-entry`.
   The row is NOT finalized synchronously: the broker's `Cancelled` callback
   (`ExecutionFillTrackingService`) owns the CANCELLED transition, so a cancel raced by a
   fill still resolves to ACTIVE.
2. **Panel/chart close now reaches the broker.** `ActivePositionsService.closePosition` used
   to mark broker-known rows `EXIT_SUBMITTED` locally WITHOUT submitting any IBKR order —
   stranding the live position (app/broker drift). It now routes a FLATTEN `TradeIntent`
   through the unified `DefaultOrderRouter` (broker-truth reconciliation, marketable exit
   pricing, stuck-close re-fire). `SKIPPED_IBKR_DISABLED` keeps the legacy local mark (tests /
   IBKR-less envs); `ENTRY_SUBMITTED` unfilled delegates to the broker cancel;
   per-click idempotency key `panel-close:{id}:{epochMilli}`.
3. **Action-token bug fixed (would have sent SHORTs as BUYs).** Rows persisted by
   `QuantManualTradeService` / `QuantAutoArmService` stored `action="BUY"/"SELL"`, but the
   gateway maps `"SHORT" → SELL, else BUY` — so a manual/auto-arm **SHORT submitted to IBKR
   became a BUY**. Unnoticed because no UI called these endpoints yet. Both now persist
   `"LONG"/"SHORT"` (`ManualDirection.action()`, `AutoArmDirection.action()`), and the gateway
   defensively maps `"SELL"` → SELL for any legacy rows.

**Known limitations (Phase 2 candidates):** no price-modify of a resting order (drag) — cancel
+ re-place; `ENTRY_PARTIALLY_FILLED` can neither cancel (409 "use close") nor flatten (router
in-flight guard) — rare on 1-lot micros; SL/TP of manual rows are VIRTUAL display levels —
no walker executes them (exits are operator-driven), no broker brackets (by design, matches
WTX); `useActivePositions` in TickChart opens a second SockJS connection.

**Tests.** `ActivePositionsServiceTest` (14, router-mocked FLATTEN capture, cancel matrix),
`ActivePositionsControllerTest` (+3 cancel-entry), `QuantManualTradeServiceTest` (+2,
LONG/SHORT tokens), `QuantAutoArmServiceTest` token update. `HexagonalArchitectureTest` green.

## WTX — live intrabar stop exits on 1m candles (2026-06-11)

**Why.** Live WTX panels evaluated protective-stop exits only on the close of the PANEL
timeframe bar (e.g. every 10m for `top-train-Z35`), while every validated backtest (Z35
PF 1.58 etc.) replays exits on 1m candles. Observed on prod 2026-06-11: a short's SL
(29027.24) was breached intrabar at 14:01 UTC (1m high 29043.50) but the exit row landed
at 14:10:00Z with the order submitted ~9 min after the breach — the app books the SL
level while the real fill happens at the panel-close market price (that day ~64 pts
better, but unbounded worse if the move continues).

**What changed** (`WtxStrategyService`):

- **1m intrabar exit sweep**: on every closed `1m` `CandleClosed`, each panel of that
  instrument (legacy timeframes + variants; a panel whose own data timeframe is 1m is
  excluded) re-checks its OPEN position with the same `WtxTrailingExitEvaluator` against
  the just-closed 1m candle. An exit goes through the existing `applyExit` path — routed
  to IBKR before flattening, **booked at the STOP LEVEL** (backtest fill convention),
  exit row stamped at the 1m bar's timestamp under the owning panel key.
- **Trailing state advances per 1m**: MFE / ratcheted trailing stop update on every 1m
  close via `withTrailing` (saved + published only when values actually change, so no
  per-minute row/WS churn while flat or unchanged). `lastCandleTs` is NOT touched by the
  sweep — day-change detection stays owned by the panel bar.
- **Per-panel position lock**: panel-bar processing, the 1m sweep and the NY force-close
  now serialize on a per-`(instrument, panelKey)` monitor, so a breach landing on the
  same tick as the panel close can never exit twice (the panel path reloads state and
  sees FLAT). Entry/signal evaluation is untouched — still panel-timeframe only.
- The sweep never CREATES panel state, skips BASELINE profiles (no ATR exits), and reads
  the panel's effective config (preset + overrides), so a variant's own SL multiple
  (e.g. Z35 `slAtrMult 4.0`) drives its 1m stop.

Tests: `WtxIntrabarExitTest` — 1m breach mid-10m-bar exits promptly at the SL level with
SL-consistent realized P&L, no double exit when the 10m close carries the same breach,
trailing MFE/stop ratchet on consecutive 1m closes, variant panel uses its preset stop,
BASELINE not swept.


## Mobile layout — focused tabbed UI below `lg` (2026-06-11)

The dashboard now has a dedicated mobile layout (viewport < 1024px) instead of stacking
all ~25 desktop panels in one column. Desktop (≥ `lg`) is unchanged.

- **`useIsMobile()`** (`frontend/app/hooks/useIsMobile.ts`): SSR-safe `matchMedia` hook
  mirroring Tailwind's `lg` breakpoint. Returns `null` until the viewport is known, so
  neither panel tree mounts prematurely — a phone never mounts the desktop panels (and
  their WS subscriptions / polling), not even for one frame.
- **Bottom tab bar** (5 tabs, only the active tab's panels are mounted):
  Chart (= **TickChart**, not the heavy lightweight-charts `Chart`), WTX (WTX · 5m,
  WTX · 10m, top-train-Z35 on MNQ), Quant (Quant7GatesSimulationPanel), Playbook,
  Portf (IbkrPortfolioPanel).
- **Deliberately desktop-only**: AlertsFeed, full Chart, IndicatorPanel, DxyPanel,
  OrderFlowPanel, FootprintChart, FlashCrashPanel, BacktestPanel, CorrelationPanel,
  StrategyPanel, ExternalSetupPanel, WtxRsiStrategyPanel, PerfectSetupPanel — none of
  them mount on mobile.
- **Header**: desktop control cluster is `hidden lg:flex`; mobile gets theme + a "⋯"
  overflow menu (timezone, purge, MarketableSettingsControl) and a dedicated full-width
  instrument/timeframe selector row with larger touch targets. Shared header widgets were
  extracted as `TabGroup` / `TimezoneSelect` / `PurgeButton` / `ThemeToggle` in `Dashboard.tsx`.
- IndicatorPanel Breaks/FVG confidence gauges now `flex-wrap`/`flex-1` — they were the
  only horizontal overflow at 380px.
- Validated in-browser at 380px (all 6 tabs, no horizontal overflow, real prod data) and
  1280px (desktop regression). `npm run lint` + `npm run build` pass.

## Quant 7-Gates: ~3 s fast exit path (SL/TP between scans) (2026-06-11)

Sim #903 (SHORT MNQ) closed 93 pts past its SL (-272.5 pts vs the planned ~-180): exits
were only evaluated by the 60 s gate scan, and the 2026-06-11 squeeze ran +128 pts inside
one scan window (17:30 1m candle: O 29087 → H 29215). The backtest replay fills AT the SL
level, so live results read systematically worse than backtest on fast moves.

- `QuantSimFastExitListener` (`@EventListener` on `MarketPriceUpdated`, the existing ~3 s
  poll + debounced IBKR pushes) → `Quant7GatesSimulationService.onPriceTick`: SL/TP-only
  check on open rows + mark-to-market publish. No new thread, no new state; no-op when no
  row is open. Closes overshoot at most one poll interval now.
- **Provenance guard**: `MarketPriceUpdated` is ALSO published for FALLBACK_DB/STALE prices
  and carries no source — the listener re-reads via `LivePricePort` and only acts on
  `LIVE_PUSH`/`LIVE_PROVIDER`. Fallback-priced exits stay on the 60 s scan, as before.
- Deliberately NOT on the fast path: entries, flow-AVOID (need a fresh pattern from the
  scan) and EOD flat (scan resolves it within a minute). `checkSlTp` is shared by both paths.
- Kill switch: `riskdesk.quant.sim.fast-exit-enabled=false` → scan-only exits (old behaviour).
- Tests: `QuantSimFastExitTest` (tick SL/TP close at tick price, MTM without close, never
  opens, listener live-source gating + disable flag + VIX/null events).

## MNQ contract-month residues purged — 1d 2026 + all pre-2026 1h/4h/1d (2026-06-11)

Completes the contract-month cleanup started with the 10m/1h/4h H6 re-backfill (PR #451).
Two residues remained in prod PostgreSQL and are now fixed via the same proven pattern
(`POST /api/candles/backfill/MNQ/{tf}?from=&to=&contractMonth=YYYYMM&replace=true&async=true`,
jobs run strictly sequentially, polled to `DONE`):

1. **1d 2026-01-01 → 03-11 was still M6 (202606).** Re-backfilled with `contractMonth=202603`:
   47 bars purged/replaced. Validated: daily volumes jumped 1.4k–19k → **1.27M–3.18M**
   (front-month scale) and all sampled 1d closes (6/6 days across the window) sit inside that
   trading day's 1m low–high range (the 1m series is the reference — H6 until 2026-03-12
   00:00 UTC, M6 after).
2. **Pre-2026 1h/4h/1d history was thin M6 back-month junk** (volumes 0–1 typical, avg 11–101).
   **Decision: re-backfill per front-month window rather than purge** — IBKR still serves
   expired 2025 contracts (~2-year retention), and correct deep HTF history feeds the 1h/4h
   trend filters used by Quant/WTX backtests. Roll calendar used (CME equity roll = Thursday
   8 days before 3rd-Friday expiry, switch at 00:00 UTC, matching the observed Mar 12 00:00 UTC
   H6→M6 switch in 1m): H5=202503 → 2025-03-12, M5=202506 → 2025-06-11, U5=202509 → 2025-09-10,
   Z5=202512 → 2025-12-10, H6=202603 → 2026-03-11. Windows re-backfilled (12 jobs, all `DONE`):
   - **1d** from 2025-03-24 (M5/U5/Z5/H6): 196 junk rows → 196 correct bars, avg vol 101 → **1.52M**
   - **1h** from 2025-06-20 (U5/Z5/H6): 1,713 junk rows → **3,151** bars (back-month junk was
     sparse — many hours never traded), avg vol 11.5 → **61k**
   - **4h** from 2025-01-14 (H5/M5/U5/Z5/H6): 1,038 junk rows → **1,736** bars, avg vol 25 → **217k**

Post-checks: roll seams show the expected forward-basis jump (Sep 11 +236 pts, Dec 11 +205 pts)
with thin first-overnight volume building through the roll day — correct stitched-series
behaviour, not residual junk. The only remaining sub-10-volume bars are Thanksgiving-2025
closure hours (legit). **The MNQ candle store is now contract-correct across every stored
timeframe and the full stored history**; backtests may use pre-April windows on 1h/4h/1d
without the back-month caveat. Caveat that remains: per-window series are per-contract
(no back-adjustment), so price levels jump at each roll seam — indicators spanning a seam
(e.g. long EMAs) see the basis step.

## MNQ candle contract-month fix — 10m/1h/4h re-backfilled with H6 (2026-06-11)

**Why.** The PLAYBOOK MNQ 10m backtest study (PR #450, section "PLAYBOOK MNQ 10m — Round 3")
found stored 10m vs 1m MNQ closes disagreeing by the quarterly futures basis pre-April 2026
(~250 pts ≈ 9× the 10m ATR in January, → ~0 by April), invalidating every cross-timeframe
backtest on that window.

**Diagnosis (prod riskdesk-prod-v2, via REST only).**
- The **1m series was correct all along**: front-month H6 (202603) with healthy volume
  (~5–15k/min RTH) until **2026-03-12 00:00:00 UTC**, then M6 (202606). That switch is the
  CME-conventional equity roll date (Thursday, 8 days before the Mar 20 expiry) and shows as a
  one-bar +210 pt jump with volume collapsing to single digits (M6 was still thin on Mar 12–13;
  it became volume-dominant on Mar 16).
- **10m, 1h, 4h and 1d were M6 (202606) for their entire pre-roll history** — prices a carry
  basis above the 1m and back-month volumes (tens per bar in January, single digits earlier).
  Consistent with a deep backfill run after the March roll using the default contract walk,
  which fetched the whole window from the then-front M6 (M6 has thin data going back a year+,
  so the expired-contract walk never engaged). Contaminated spans found: 10m since Jan 5 2026,
  1h since Jun 20 2025, 4h since Jan 14 2025, 1d since Mar 24 2025. **5m has no pre-April data
  at all.** From Mar 12 2026 onward all timeframes were already (correctly) M6.
- The known 1m volume undercount (see PR #413 era; live-built candles only) does **not** affect
  Jan–Mar 1m rows — they are backfill-sourced with IBKR-correct volumes, so no 1m re-backfill
  was needed.

**Fix applied (2026-06-11, prod).** Explicit-contract replace backfills, window
`2026-01-01T00:00:00Z → 2026-03-11T23:59:59Z`, `contractMonth=202603`, `replace=true`:
- `POST /api/candles/backfill/MNQ/10m?...` → 6 726 saved, 6 450 purged
- `POST /api/candles/backfill/MNQ/1h?...`  → 1 121 saved, 1 121 purged
- `POST /api/candles/backfill/MNQ/4h?...`  → 342 saved, 341 purged

Mar 12 → Apr 1 was deliberately left untouched (already correct M6). The roll boundary at
Mar 12 00:00 UTC now appears identically in all fixed timeframes (e.g. 10m closes
24 787.25 → 24 968.75 across the boundary, matching the 1m).

**Validation.** Every higher-TF close compared to the last 1m close inside the same bar over
the full Jan 1 – Mar 31 window: 10m (8 658 bars), 1h (1 443) and 4h (440) all agree with the
1m to **max |diff| = 0.75 pt, zero bars > 5 pts**; January volumes are now front-month scale
(10m median 4 773/bar vs ~tens before). Caveat for future validators: IBKR 4h bars are
session-aligned — the 20:00 UTC bar ends at the 22:00 UTC Globex halt and a fresh bar opens
at 23:00 UTC — so compare against the next bar's start, not a naive +4h.

**Still wrong / follow-ups:**
1. **1d Jan 1 – Mar 11 2026 is still M6** — the same fix command was permission-blocked in
   this session. To fix: `POST /api/candles/backfill/MNQ/1d?from=2026-01-01T00:00:00Z&to=2026-03-11T23:59:59Z&contractMonth=202603&replace=true&async=true`.
2. **Pre-Jan-2026 1h/4h/1d history is thin M6 back-month junk** (volumes 0–1, back to
   Jun 2025 / Jan 2025 / Mar 2025 respectively). Out of scope here; purge it or re-backfill
   per-quarter with the historically correct contracts (Z5 = 202512 until ~Dec 11 2025, etc.).
3. Pre-April cross-timeframe backtests (PR #450 Round 3 `filterConsistent` workaround) can be
   re-run on the corrected data — the 10m/1m basis artefact is gone.

## PLAYBOOK confirmation-entry profile MNQ_10M_CONFIRMATION (2026-06-11)

Implements the backtest-validated confirmation mechanism (PR #450 rounds 3–4, below)
as an armable Playbook execution profile. **Paper-only by design** (`executable=false`)
until forward validation completes.

**Mechanism** (domain: `ConfirmationEntryPlanner`): when the playbook detects an SMC
zone, the entry is a **STOP at the zone exit** — LONG buy-stop at `zoneHigh`, SHORT
sell-stop at `zoneLow` — instead of the legacy passive limit inside the zone (which
fills while the zone is failing — adverse selection on the LONG side). A
pending setup **cancels if price first breaks the far side of the zone by 0.5×ATR**
(never buy the reclaim of a broken zone). Exits are ATR brackets: SL 1.5×ATR,
TP 2.25×ATR (R:R 1.5). Gates: score ≥5; LONGs only inside RTH (09:30–16:00 ET),
SHORTs inside the extended day window (08:00–17:00 ET, both DST-aware via
`TradingSessionResolver`); zone dedup — one attempt per zone/direction (skip while a
same-direction sim is open, and for 2h within 0.3×ATR of a prior attempt).

**Wiring:**
- `PlaybookDecision` gains nullable `entryType` (`LIMIT`/`STOP`) + `invalidationPrice`
  (new columns `entry_type`, `invalidation_price` via ddl-auto).
- `PlaybookAutomationService.onCandleClosed` builds a confirmation decision (plan
  fields overridden, verdict prefixed `CONFIRMATION —`) when the profile is armed;
  legacy path untouched otherwise.
- `TradeSimulationService` honors STOP plans: trigger on break-through (high≥entry
  for longs), pre-fill invalidation → `CANCELLED`, no `MISSED` state; LIMIT plans
  byte-identical (regression-pinned in `StopEntrySimulationTest`).
- Arming guard generalized: profiles declare their scope (`supportsScope`) — the old
  "non-legacy profiles are MGC 10m only" rule is gone.

**To start the paper validation:**
`PUT /api/playbook/automation/MNQ/10m {"armedProfile":"MNQ_10M_CONFIRMATION"}`
(paper stays on by default; Auto-IBKR remains blocked by `executable=false`).
Re-assess after 4–6 weeks via `GET /api/playbook/automation/MNQ/10m/decisions` —
confirmation rows carry `entryType=STOP`.

## PLAYBOOK MNQ 10m — full-pipeline 1m backtest: no edge found (2026-06-11)

**Why.** The PLAYBOOK MNQ 10m automation showed 30% WR / −$4,234 / PF 0.16 on its last
100 paper decisions. A replay backtest was built to diagnose the losses and test fixes
(min-R:R gate, 1h EMA20/50 HTF filter, per-zone dedup, score≥6 threshold, entry style).

**Harness.** `src/test/java/com/riskdesk/backtest/PlaybookBacktestHarnessTest.java`
(auto-skips when data files are absent). Phase A re-runs the REAL production pipeline
(`IndicatorService` snapshot → `PlaybookService` → `PlaybookEvaluator`) bar-by-bar over
stored 10m candles (Jan 5 → Jun 11 2026, 13,490 decisions) — validated 1:1 against the
live decision log (103 vs 100 decisions on the Jun 10–11 window, same directions/scores/
entries). Phase B simulates each decision on stored 1m candles with the exact
`TradeSimulationService` rules (1h PENDING_ENTRY timeout, MISSED, pessimistic
same-candle SL+TP = LOSS, opposite-direction reversal). Candle data comes from
`/api/candles/MNQ/{tf}/range` into `/tmp` (override with `-Dpb.data.dir`).

**Verdicts (realistic portfolio mode, max 1 concurrent position, 1152 configs):**
- Baseline: **−$30,853 net / 5.5 months** (WR 19%, PF 0.42). The dashboard's −$68k
  over-counts by letting the same zone open overlapping sims every 10m candle.
- The recommended filters are **loss reducers, not edge creators**: best non-inverted
  combo (MID entry, score≥5, HTF 1h, skip-late) lands at ≈ $0 net. HTF is the
  strongest single filter.
- Best overall config: **inverted signal + ATR exits (1.5×SL / 2.25×TP) + HTF**:
  +$1,558 net, PF 1.20, WR 42% — positive but fragile (April dominates, 3/6 positive
  months). Not tradeable as-is.
- **Trailing stop never engages on PLAYBOOK sims**: the 1h entry timeout means a fill
  never has the 15 five-minute bars `computeAtrAtActivation` needs → ATR is null →
  trailing skipped. Live PLAYBOOK P&L is pure fixed SL/TP.

**Methodology traps documented for future backtests:**
1. Without per-zone dedup the same zone re-signals every candle → 5–10 overlapping
   sims ride one move → fake +$200k. Always also evaluate one-position-at-a-time.
2. Inverting a limit-entry signal turns the entry into a STOP order: fill =
   `max(entry, bar open)` for longs — `priceInZone` setups are already in-the-money
   and would otherwise fill at impossible below-market prices (fake +$300k / 85% WR).

**Round 2 (session / setup / direction / ATR-multiplier / break-even dimensions):**
- The ONLY structurally positive family is **SHORT-only + 1h HTF bear alignment +
  ATR exits** — 86/144 neighboring configs positive (median +$400), so it is not an
  isolated grid spike. The LONG side of the playbook is worthless under every exit
  scheme tested (10/144 positive, median −$9,275): MNQ bull legs do not retest the
  zones the engine draws. PLAN exits under identical filters stay negative (median
  −$168) — the ATR exit geometry is causally necessary, same conclusion as the
  Quant 7-Gates recalibration (PR #441).
- **Champion config**: MID entry, score≥5, HTF 1h, zone dedup, skip-late, SHORT-only,
  RTH session, ATR exits SL 2.0× / TP 3.0× → 56 resolved trades, **WR 60.7%,
  +$5,252 net / 5.5 months, PF 2.05, maxDD $1,262**, top day 24% of P&L. Highest-WR
  strong variant: same + BREAK_RETEST-only → WR 65.6%, PF 2.76, +$4,104 (n=32).
- Round-1's inverted-signal hope did NOT survive refinement (median −$1,771 across
  288 inverted configs once session/ATR variants are scrutinized).
- Caveats before any live use: n is small (56 trades), profits concentrate in
  bear-regime months (Mar, Jun) by construction of the HTF+SHORT filter, and the
  config was selected from an 864-config search — forward-paper it first.

**Round 3 — CRITICAL DATA ARTIFACT + LONG confirmation mechanism:**
- **Stored 10m and 1m MNQ candles disagree by the quarterly contract basis before
  the March 2026 roll** (~250 pts ≈ 9 ATR in January, decaying to ~0 by April) —
  one timeframe was deep-backfilled with a different contract month than the
  other. Every cross-timeframe simulation over Jan–Feb is invalid (March partly);
  the harness now drops decisions whose plan entry sits >3 ATR from the 1m open
  (`filterConsistent`, 4,701 of 13,490 decisions dropped). **Prod data needs a
  consistent-contract re-backfill before any backtest uses pre-April history.**
- This artifact had poisoned round-2's directional conclusions: on CLEAN data the
  LONG side is NOT worthless. Corrected verdicts (Apr–Jun window, portfolio mode):
  - Baseline playbook: −$2,828 (mildly negative, not −$31k).
  - Best limit-based config is now BIDIRECTIONAL: MID score≥5 + HTF + dedup +
    skip-late + ATR(2.0/3.0) exits + RTH → 198 resolved, **WR 50.5%, +$7,916 net,
    PF 1.51, maxDD $1,224**, 4/5 positive months, top day 16%.
- **LONG confirmation-entry study** (`runLongMechanismStudy`): instead of the
  passive limit inside the zone, arm a **buy-stop at zoneHigh** (BREAKOUT) or
  require touch-then-reclaim (RECLAIM_TOP/MID), cancel if price breaks
  zoneLow − 0.5×ATR before triggering ("never buy the reclaim of a broken zone").
  Result on clean data: **273/288 LONG configs positive (95%)**; champion
  BREAKOUT + ATR(2.0/3.0), no HTF needed: 265 resolved, WR 47.5%, **+$7,402 net,
  PF 1.38, maxDD $1,489**, top day 17%, and June (a −5.3% month) stayed positive —
  the invalidation guard self-filters bear regimes. SHORT mirror (sell-stop at
  zoneLow) also improves on the limit version: +$9,170, WR 52.2%, PF 1.76 (NO_ON).
- Caveat: the clean window is only ~2.5 months and Apr–May was strongly bullish —
  forward-paper before live, and re-run after the data re-backfill.

**Round 4 — full-window revalidation after the contract re-backfill (2026-06-11):**
- The prod re-backfill landed (10m/1h/4h re-filled with H6 through Mar 11, validated
  to ≤0.75 pt vs 1m; see the playbook-mnq-backtest-verdicts memory / PR #452 for
  details). Phase A was recomputed on the repaired 10m series: 13,757 decisions,
  consistency gate now drops only 49 (vs 4,701) — all 5.5 months are simulable.
- **Baseline playbook, portfolio mode, repaired data: +$4,413** (WR 44.1%, PF 1.23).
  The raw signal stream is mildly positive one-position-at-a-time; the heavy losses
  shown on the dashboard come from the overlapping-sims convention and a bad recent
  live stretch, not from a uniformly worthless signal.
- **LONG confirmation champion (full window)**: BREAKOUT buy-stop at zoneHigh +
  zone-broken invalidation + ATR(1.5/2.25) exits + RTH + score≥5, arm 3h →
  n=296, WR 45.6%, **+$4,955 net, PF 1.30, maxDD $1,341, top day 14%,
  5/6 positive months** (Jan, Mar, Jun positive — chop AND bear months; only
  Feb −$510). HTF optional (htf=Y variant: PF 1.35). BREAKOUT family: 74/96
  configs positive, median +$1,626.
- **SHORT mirror champion (full window)**: BREAKOUT sell-stop at zoneLow +
  ATR(1.5/2.25) + NO_ON session + score≥5 → n=315, WR 47.0%, **+$9,515 net,
  PF 1.46, maxDD $1,215**, top day 19%.
- Same exits, same mechanism, both sides: a dual-side deployment (LONG RTH +
  SHORT NO_ON) totals ≈ +$14.5k/5.5 months per micro contract. The
  previous limit-entry champions hold but are weaker and more concentrated
  (top configs' best-day share 70%+) — the confirmation-entry mechanism is the
  better implementation candidate.

## Quant 7-Gates: per-instrument sim policy + per-instrument stats (2026-06-11)

The `riskdesk.quant.sim.*` paper-policy knobs were global; MNQ and MCL trade on very
different volatility/flow profiles and their P&L was blended into one panel aggregate.

- **Per-instrument config overrides** — `riskdesk.quant.sim.per-instrument.<INSTR>.<key>`
  (e.g. `...per-instrument.MCL.sl-atr-mult=1.5`). Overridable keys: exit-policy, stop-mode,
  atr-timeframe, atr-period, sl/tp1/tp2-atr-mult, htf-filter-enabled, htf-timeframe,
  htf-ema-fast/slow. Unset keys inherit the global value (partial blocks are the expected
  usage). EOD-flat / entry-blackout windows stay global — they are CME-session properties,
  not instrument characteristics. Resolution lives in `QuantSimProperties.<key>(String
  instrument)`; `Quant7GatesSimulationService` and `DefaultQuantSimMarketContext` resolve
  per instrument at entry/exit time. **No per-instrument values are set yet** — validate a
  candidate with `GET /api/quant/backtest/exits?instrument=...` before setting one.
- **Per-instrument stats** — `Quant7GatesSimulationService.statsByInstrument()` +
  `byInstrument` map on `GET /api/quant/simulations/stats` (each entry: closed/wins/losses/
  WR/net pts/net USD/open count). The panel renders one compact per-instrument row under
  the global strip (client-side grouping of the same rows, so slices always sum to the strip).
- **Signals were already per-instrument** — each scan/gate evaluation is per instrument and
  PR #445 made delta/abs thresholds per-instrument; this slice separates *policy* and *reporting*.
- **Stats baseline** — `riskdesk.quant.sim.stats-since=2026-06-11T09:33:40Z` (the v1.11.126
  prod boot, from `process.start.time`): rows OPENED before it ran on broken order-flow delta
  (and the legacy FLOW_AVOID exit), so they are excluded from every aggregate — global,
  per-instrument, and the panel strip (the hook fetches `statsSince` from `/stats` and filters
  client-side with the same openedAt rule). History rows stay visible in the list. Keyed on
  `openedAt`, not `closedAt`: the ENTRY decision is what consumed bad data. Clear the key to
  re-include the full history.
- Tests: `Quant7GatesSimulationServicePolicyTest` (override resolution incl. partial
  inheritance, per-instrument exit policy, statsByInstrument separation + slice-sum check,
  stats-since baseline exclusion).
## WTX — session ON par défaut pour tout panneau auto-trade (2026-06-11)

Politique actée: tout signal avec auto-exécution démarre session ON (blocage des entrées
03:00-08:00 ET, fenêtre globale), désactivable par panneau via le bouton Session (#439).
Fondement: l'étude pleine période sur données front réelles (jan→juin) a renversé le verdict
session-OFF de la sélection d'origine (mars→juin) — ON garde ~le même net (+$9,980 vs +$9,768)
avec un maxDD ÷3.5 ($1,614 vs $5,639) et survit aux mois sans direction jamais vus par la
sélection. Changements:
- `WtxParamOverride.TOP_TRAIN_Z35.sessionFilterEnabled`: FALSE → null (hérite du global, qui
  est ON). Ré-appliquer le preset ne désactive plus la session; le bouton reste l'opt-out.
- Fix vue REST: `WtxStrategyController.toStateView`/`defaultStateView` exposent maintenant
  `sessionFilterEnabled` (effectif) — sans quoi le bouton Session affichait toujours OFF même
  moteur ON (bug d'affichage de #439, le moteur n'était pas affecté).
- Côté prod, le panneau Z35 a été basculé ON à chaud via l'endpoint (override DB session=true);
  la config zone du panneau vient du preset déclaré dans application.properties (variants[0]),
  pas de la ligne DB — les colonnes NULL de la ligne d'override sont normales.

## Tick log provenance fix + BBO circularity audit (2026-06-11)

A prod log audit found every sampled `TICK #N` line reading `class=UNCLASSIFIED` with
`bid=price−1tick / ask=price+1tick` while `/api/order-flow/status` showed ~100% ticks
classified. Findings (verified against the deployed v1.11.125 via `/actuator/info`):

- **Log artifact, fixed.** The line in `TickByTickClient.tickByTickAllLast` logged the raw
  Lee-Ready quote-rule result BEFORE the adapter's tick-rule fallback (L2) ran, so any trade
  printing exactly at the BBO midpoint logged `UNCLASSIFIED` even though it was classified
  downstream. `IbkrTickDataAdapter.onTickByTickTrade` now returns a `TickResolution`
  (consumed classification + tickRule flag) and the log is emitted AFTER resolution:
  `class=` is what the aggregator consumed, `via=LEE_READY|TICK_RULE|DROPPED|ADAPTER_UNWIRED`
  says how, `bbo=BBO@<age>ms|QUOTE@<age>ms|NONE` says which quote cache supplied bid/ask and
  its age at classification time.
- **No circular synthetic BBO in current code.** The pre-#413 path that synthesized
  `bid = lastPrice − tickSize / ask = lastPrice + tickSize` (Lee-Ready against it is circular —
  exactly the logged signature, MNQ tick = 0.25) was removed in PR #413; `latestStreamingBbo`
  serves only real two-sided BID/ASK captures (`lastGoodBid/lastGoodAsk`, bounded by
  `bbo-max-staleness-seconds=90`), the quote-sub fallback is real, and the final fallback is
  `bid=ask=0` → tick rule, flagged per-tick.
- **Residual midpoint pattern explained.** The cached BBO is read at tick *processing* time, so
  in a one-tick-wide book an aggressive trade that wipes its level is classified against the
  post-trade book (`bid=price−1t / ask=price+1t` → exact midpoint → tick rule). Near-systematic
  in thin overnight Globex (the sampled `TICK #13–#19300` were the first ticks after the 00:04Z
  deploy); during RTH quote-classified volume dominates and the window stamps `REAL_TICKS`.
  This is honest: such ticks carry `tickRule=true` and degrade the window to
  `REAL_TICKS_TICKRULE` when they exceed `1 − real-ticks-min-quote-fraction` of volume.
- **Note:** `classifiedTicksReceived ≈ totalTicksReceived` is true by construction with the
  tick-rule fallback enabled — it is NOT evidence that Lee-Ready is working; use the per-window
  `source` (or the new `via=` log field) for that.
- Tests: `IbkrTickDataAdapterTest` pins midpoint/missing/inverted-BBO → quote-rule UNCLASSIFIED,
  tick-rule resolution + `TickResolution` contract, and REAL_TICKS vs REAL_TICKS_TICKRULE
  window stamping honesty.
## Quant 7-Gates exit recalibration + exit-replay backtest (2026-06-11)

**Why.** After 863 closed paper trades (2026-06-02 → 2026-06-11) the harness sat at
41% WR / −$2,550 net. A data-driven autopsy on the recorded trades + prod 1m candles
(intrabar replay, pessimistic both-cross rule) found the causes were the EXITS and a
missing trend filter — the order-flow entry signal itself has real edge:

- **Flow-AVOID churn**: 79% of trades (682/863) closed on the first AVOID scan,
  median hold 2 min, median −0.6 pts → −$3,630. The pattern flips constantly at the
  60s cadence; the entry needed HIGH conf only one minute earlier.
- **Fixed 25/40/80-pt offsets** are MNQ-scaled: never reachable on MCL (~$65) / MGC,
  so 100% of their trades degenerated to AVOID churn (zero SL/TP closes on either).
- **60s-scan SL granularity**: average realized SL was −$81.5 vs −$50 design (63%
  overshoot — exits priced at the scan tick, not at the level).
- **Counter-trend longs**: the window was a −2,000-pt MNQ downtrend; LONGs ran
  −0.25R (WR 30%) vs SHORTs +0.34R (WR 55%) under symmetric exits. With a 1h
  EMA20/50 alignment filter, the same recorded entries replayed to **+$37.7k gross /
  +0.32R, WR 54.5%** (ATR 2.0/3.0 stops, no AVOID exit, EOD flat, n=391) vs −$2.5k
  actual. Same lesson as WTX (#418): the edge is the HTF filter, not exit tinkering.
  ⚠ In-sample, 9 days, one bearish regime — re-run the replay as history grows.

**What changed** (all reversible via `riskdesk.quant.sim.*`, defaults = calibrated):

- `QuantSimExitPolicy` — `SLTP_ONLY` (default) | `FLOW_AVOID_IN_PROFIT` |
  `FLOW_AVOID` (legacy). AVOID flips no longer realize losses.
- `QuantSimStopMode.ATR` (default) — SL/TP = 2.0/3.0/6.0 × ATR(14) on 5m, per
  instrument, via `DefaultQuantSimMarketContext` (30s cache; fixed-offset fallback
  + WARN when the candle store is cold).
- **HTF filter** (default on, fail-closed): entry requires 1h EMA20/50 alignment
  with the trade direction.
- **EOD flat**: paper rows flatten from 16:55 ET (status `CLOSED_EOD`, new enum +
  frontend pill) and entries are blocked from 16:50 ET until the 18:00 ET reopen —
  paper and the Auto-IBKR mirror (force-close cron) now resolve together. ET-zone
  projected, DST covered in tests, injectable `Clock`.
- **Exit-replay backtest**: `GET /api/quant/backtest/exits` re-manages the RECORDED
  entry signals under any policy bundle (stopMode/slAtrMult/tpAtrMult/exitPolicy/
  htfFilter/commissionUsd/from/to/instrument, `includeTrades=true` for the trade
  list). Engine: `domain/quant/backtest/QuantExitReplayEngine` (pure; pessimistic
  both-cross, no lookahead — ATR/EMA only from closed buckets, EOD flat). It is an
  ENTRY-replay (recorded signals only), not a gate-replay; a full gate-replay from
  `tick_log`/footprint/event tables is the natural next slice.
- Legacy behaviour for pre-existing unit tests is preserved via the old service
  constructors (`QuantSimProperties.legacyDefaults()`).

**Known limitation**: live SL/TP checks still price at the 60s scan tick (the −63%
SL overshoot persists live); the replay engine resolves intrabar at the exact level.
Tightening the live loop (1m-candle extreme checks or resident broker stops) is a
follow-up.

## Quant per-scan flow log — `quant_scan_snapshots` (2026-06-11)

The rolling delta window the gates consume was ephemeral (`TickDataPort`): no
table held the delta/buy%/absorption state *as the gates saw it*, so gate-replay
backtests and threshold sweeps ("what if G3 required Δ < −150?") were impossible —
`tick_log` reconstruction cannot reproduce the live classifier state (BBO cache,
tick-rule fallback, watchdog abstains from PR #413).

- `QuantGateService.scan()` now appends one row per scan per instrument
  (best-effort, outside the per-instrument lock, never breaks the scan) to
  `quant_scan_snapshots` via `QuantScanSnapshotPort` →
  `QuantScanSnapshotJpaAdapter`. **Non-signal scans are logged too** — a
  signals-only log would be survivorship-biased for backtests.
- Row = raw INPUTS (delta *as seen by gates* — null on stale-drop — plus
  `deltaSource` provenance of the raw window, buy%, absorption window counts +
  dominant side, dist/accu type+conf, cycle, price+source) and key OUTPUTS
  (SHORT/LONG scores, pattern type/label/conf/action, per-gate verdicts as JSON).
  The stale-drop case is distinguishable from "no window at all": `delta=null` +
  `deltaSource!=null` vs both null.
- Volume: ~4.3k rows/day for 3 instruments. Retention 90 days — purged by the
  existing `OrderFlowEventPersistenceService` daily job alongside the event tables.
- Read surface: `GET /api/quant/scan-log/{instrument}?from&to&limit` (newest
  first, default last 2h, cap 5000).
- Known limit: the log freezes the *current* window definition (5m rolling).
  Threshold/entry-rule sweeps are now possible; changing the window length
  itself still needs `tick_log` (30d).
## Live candle volume fix + candle upsert (2026-06-11)

Production diagnosis: LIVE-built 1m candles under-reported volume ~10× (variable 5–17×) vs
IBKR backfill — MNQ daily 1m volume 2.6–3.7M (backfilled Jun 3–5) vs 144k–363k (live-built
Jun 8–10) — so two volume scales coexisted in `candles`. Root cause: `MarketDataService`'s
inner `CandleAccumulator` set `volume = 1` at bar open and `volume++` per price update —
counting **debounced price events** (100ms debounce + same-price skip), not contracts.

Fix (PR claude/frosty-sammet-5c46b9):

- **Volume source = IBKR session-cumulative `TickType.VOLUME` deltas.** The existing
  `reqTopMktData` streaming subscription already received them; `StreamingPriceSubscription.tickSize`
  was a no-op. It now computes per-update deltas (`IbGatewayNativeClient.volumeDelta`: first
  reading = baseline only, counter going backwards = Globex session reset → new reading is the
  delta) and pushes them through the new `StreamingPriceListener.onLiveVolumeUpdate` default
  method (domain port, Spring-free). This covers **all 4 streamed futures** — the AllLast
  tick-by-tick stream only covers MNQ/MCL. Cumulative deltas also self-heal: volume missed
  during a quiet stream lands in the next delta, so hourly/daily sums match the exchange.
- **`MarketDataService`** routes deltas into the open bar of every intraday timeframe
  (gated on market-open + maintenance window, same as prices). Volume arriving before a bar's
  first price tick is stashed (`pendingVolume`, claimed at bar creation); volume lagging just
  past a roll goes to the open bar (totals preserved). Price updates no longer touch volume —
  a bar with no volume ticks closes at volume 0. Pending volume is flushed on rollover.
- **Candle writes are upserts** on `(instrument, timeframe, timestamp)` in
  `JpaCandleRepositoryAdapter` (natural-key id adoption + one range query per batch group +
  `DataIntegrityViolationException` retry). Fixes the boot race: the startup gap-fill read its
  high-water mark, then the live writer closed bars before `saveAll` flushed → 11
  `uk_candle_instrument_tf_ts` violations at boot, each aborting an entire gap-fill batch.
- Tests: `MarketDataServiceTest` (delta accumulation, pending claim, zero-volume honesty),
  `JpaCandleUpsertTest`, `IbGatewayNativeClientVolumeDeltaTest`.

**Post-deploy validation:** let one full hour build live, then
`SELECT date_trunc('hour', timestamp), SUM(volume) FROM candles WHERE instrument='MNQ' AND timeframe='1m' GROUP BY 1`,
re-backfill the same hour via `POST /api/candles/backfill-range` with `replace=true`, and compare
(expect agreement within a few %; also cross-check footprint bar volume for the same window).
**Historical repair:** live-built rows from before this fix (e.g. Jun 8–10) are still at the
wrong scale — re-source them with the range backfill in `replace` mode. Until then, do NOT
normalize tick-vs-candle volume across that window.

## Depth flow signals + DOM heatmap (2026-06-11)

Continuous L2 signals computed from the existing 500ms `DepthMetrics` snapshots —
the hardened `MutableOrderBook` hot path is untouched (snapshot consumers only):

- **`DepthFlowAnalyzer`** (pure domain, one per instrument, WallTracker pattern) emits
  `DepthFlowMetrics` per transition: **OFI** (Cont-Kukanov-Stoikov best-level events,
  1s/10s sums + EMA-60s; 10s sum z-scored vs a trailing 5-min distribution — the
  canonical result is CONTEMPORANEOUS (~65% R² over 10s), so it is an entry-timing
  gauge, not a forecast), **queue imbalance + micro-price** (EMA ~3s, min-queue-mass
  gate 10, micro-price offset in ticks), **liquidity vacuum** (side <40% of its 5-min
  baseline for ≥3s while the other holds ≥70% → VACUUM_BID/ASK; both <50% → THIN), and
  **pull/stack net flow** (per-level resting-size deltas beyond a 2-contract noise
  floor, 10s sums; approximation — no per-level trade attribution).
- **Stale-gap rule:** snapshots older than 10s (or eval gaps >10s) reset the analyzer —
  flow is never computed across a feed freeze. `DepthFlowService` (@Scheduled 500ms,
  initialDelay 30s) feeds the analyzers, caches the latest, publishes flat JSON on
  `/topic/depth-flow` and serves `GET /api/order-flow/depth-flow/{instrument}`.
  Config `riskdesk.order-flow.depth-flow.*` (MNQ-tuned defaults, all documented).
  `OrderFlowController` ctor gained `DepthFlowService`.
- **Frontend:** `DepthFlowStrip` (compact row: OFI z bar amber ≥2 / red ≥3, queue lean
  ▲/▼ 0.4/0.6, µP offset, vacuum chip, P/S net) and `DepthHeatmap` (raw-canvas
  Bookmap-style movie of the last 20 min of `/topic/depth` ladders: ~2400 columns ring
  buffer, 40-tick auto-follow window, log-scaled brightness normalized to rolling p95,
  FLUX FIGÉ overlay on serverStale, collapsible). Both mounted in `OrderFlowPanel`
  under the Order Book; `useOrderFlow` subscribes `/topic/depth-flow`.

## Order flow — session CVD + divergence, speed of tape, big prints (2026-06-11)

Three pro order-flow tools added on the classified AllLast tick stream (research gap pass):

- **Session-anchored CVD (A)** — `TickByTickAggregator` now accumulates a TRUE session
  CVD tick-by-tick (immutable `CvdState` snapshot, volatile swap; survives the 5-min
  deque). Anchors via `TradingSessionResolver` (new `isWithinRth`/`rthSessionStart`,
  DST-aware): **RTH open 09:30 ET inside RTH, else Globex daily start 17:00 ET**
  (≡ 18:00 ET re-open — no prints during the maintenance halt). **Bug fixed:**
  `TickAggregation.cumulativeDelta` was the 5-min window delta relabeled; it now carries
  the session CVD (frontend label "Cum:" → "CVD session (RTH|GLOBEX)"). New
  `SessionCvd`/`TapeSpeed` models + `TickDataPort.sessionCvd/tapeSpeed` defaults.
- **CVD divergence** — pure domain `CvdDivergenceDetector` builds 1m bars internally
  from the orchestrator's 5s (price, CVD) samples; swing pivots confirmed 5L/5R on
  closes; price HH + CVD LH → bearish, price LL + CVD HL → bullish; `min-cvd-swing`
  (200) filters flat CVD; pivot state clears on anchor change (never compares across a
  CVD reset). Emits `CvdDivergenceDetected` → WS `/topic/cvd-divergence`, NO persistence.
  Config `riskdesk.order-flow.cvd.{enabled,pivot-bars,min-cvd-swing}`.
- **Speed of tape (B)** — `TickByTickAggregator.tapeSpeed(window, now)` (single backward
  pass). Orchestrator z-scores 5s/30s trades/sec against a rolling 30-min baseline
  (sampled per 5s pass, current sample excluded). Payload fields on `/topic/order-flow`:
  `tapeSpeed5s/30s`, `tapeZ5s/30s`, `tapeRatio5s`. UI gauge: amber z≥2, red z≥3.
  Config `riskdesk.order-flow.tape.{enabled,burst-z}`.
- **Big prints (C)** — pure domain `BigPrintDetector` (rolling 30-min size histogram,
  flag ≥ p99 with floor 10 contracts, threshold judged vs prior distribution).
  `IbkrBigPrintAdapter` (implements `BigPrintPort`) wired in `IbkrTickDataAdapter`
  routing; emits `BigPrintDetected` rate-limited 1/sec/instrument → WS
  `/topic/big-prints`; `bigPrintDelta5m` rides `/topic/order-flow`. NO persistence.
  Config `riskdesk.order-flow.big-print.{enabled,percentile,min-size,window-minutes}`.
  **Caveat:** IBKR AllLast pre-aggregates same-price simultaneous executions — a
  "print" is a match event, so big prints ≈ sweep detection (Javadoc'd).
- Frontend: `useOrderFlow` subscribes the two new topics; `OrderFlowPanel` Delta section
  shows CVD session, tape gauge, big-print 5m delta, "DIV ▲/▼ il y a Xm" badge (10 min),
  and a "Big Prints" feed (8 rows). `OrderFlowOrchestrator` ctor gained
  `ObjectProvider<BigPrintPort>`.
## Session volume profile + footprint diagonal imbalance (UC-OF-015, 2026-06-11)

Two pro order-flow upgrades for MNQ trading (gap analysis vs Sierra/Bookmap-class
tooling):

**A. Session volume profile (POC / VAH / VAL, 70% VA) + naked POCs.**
- New pure domain calculator `SessionVolumeProfileCalculator`
  (`domain/engine/indicators/`): distributes each 1m candle's volume across its
  high–low range proportionally (uniform-across-ticks approximation) instead of the
  legacy typical-price binning of `VolumeProfileCalculator` (which is unchanged and
  still feeds Mentor/IndicatorSnapshot). Same 70% outward VA expansion. Also hosts the
  pure naked-POC ladder logic (`nakedPocs`): a session POC is naked while no later
  session's range touched it (developing session range acts as a toucher).
- `TradingSessionResolver` gained RTH helpers: `rthStart/rthEnd(LocalDate)`,
  `rthSessionDate(Instant)`, `previousRthDate`, `isWithinRth`, `overnightStart`
  (18:00 ET Globex reopen, Sunday for Monday sessions) — all DST-aware, fixed-instant
  tested (EDT/EST/spring-forward).
- `VolumeProfileService` (application): current RTH session (developing flag), prior
  session, overnight Globex session, naked-POC ladder (lookback default 10 sessions);
  per-instrument cache, recompute at most once per
  `riskdesk.order-flow.volume-profile.cache-seconds` (60). 1m candles from PostgreSQL
  only. Injectable `Clock` for tests.
- REST: `GET /api/order-flow/volume-profile/{instrument}` (400 for DXY/synthetic).
  No WS topic. Config: `riskdesk.order-flow.volume-profile.*` (bucket-size MNQ 1.0,
  MCL 0.05).
- Frontend: `Chart.tsx` "VP" toggle (next to SMC/MTF) draws price lines — developing
  POC solid amber, prior pPOC violet / pVAH+pVAL grey dashed, naked POCs dotted
  violet `nPOC <date>` (prior session's own POC deduped). 60s poll inside Chart.

**B. Footprint diagonal imbalance (industry standard) + finer MNQ buckets.**
- The old per-level `imbalance` flag compared buy vs sell at the SAME price — not the
  pro signal. It is kept (deprecated) in `FootprintLevel` for persisted-JSON
  backward compat. New per-level flags: `diagonalBuyImbalance` (buy(P) ≥ ratio ×
  sell(P − 1 bucket)) and `diagonalSellImbalance` (sell(P) ≥ ratio × buy(P + 1
  bucket)); missing neighbour = 0; the LARGER cell of the pair must be ≥
  `min-cell-volume` (MNQ 20, MCL 5) so 4-vs-1 never flags. Ratio default 3.0.
- Bar-level additions on `FootprintBar`: `stackedBuyZones`/`stackedSellZones`
  (≥3 consecutive bucket-adjacent flags, `{fromPrice,toPrice,buckets}`) and
  `unfinishedHigh`/`unfinishedLow` (extreme bucket traded on both sides).
- Pure logic lives in `FootprintImbalanceCalculator` (domain), shared by the live
  `FootprintAggregator` (new 5-arg ctor; 3-arg keeps defaults) and by
  `OrderFlowHistoryService.toBar`, which recomputes zones/unfinished from persisted
  level JSON — old rows without the new fields parse fine (flags default false,
  zones empty; unfinished works since it only needs volumes).
- **`riskdesk.order-flow.footprint.bucket-size.MNQ` changed 5.0 → 2.0 points (8
  ticks)** — research consensus 4–10 ticks/row for NQ-class; bars persisted before
  the change have 5-point buckets (zones suppressed on them by the adjacency check).
- Frontend `FootprintChart.tsx`: emerald/red cell borders for diagonal flags, row
  band tint for stacked zones (+ header zone counters ⊞/⊟), ▲/▼ unfinished-auction
  markers (also in the closed-bar history strip).
## Quant gates + absorption pipeline recalibration (2026-06-11)

Data-driven recalibration from a production audit + 90-day event study (key finding:
DISTRIBUTION events with conf≥70 were INVERTED at 5m — 44.6% hit vs 47.7% baseline —
while conf<70 carried a small positive edge at 15-30m). Four fixes:

1. **A/D veto de-tautologized.** `InstitutionalDistributionDetector.computeConfidence`
   floored at 50 == the old GateEvaluator veto base tier (50) ⇒ 100% of events vetoed
   by construction. New formula: base `35 + min(15, (count-min)×5)`, log-compressed
   score bonus `min(25, 25·log1p(max(0, avg-minAvg))/log1p(20))` (old linear bonus
   saturated at avg≈5 while real avgScores span 2.5→50+), duration/intensity bonuses
   kept, total capped 95. Veto tiers raised 50/65/75 → **60/70/80** (base+10/base+20)
   and the veto now has a **linear age decay**: `effective = conf × max(0, 1-age/600s)`
   (the 8-min detector cooldown < 10-min lookup window meant the veto re-armed before
   expiring). Config: `riskdesk.quant.veto.{base-threshold=60,decay-seconds=600}` →
   `QuantGateProperties` (infra) → `QuantGateConfig` (domain, Spring-free). Telemetry
   gains `adEffectiveConfidence` (decayed); `adConfidence` stays raw.
2. **Absorption emission de-dup.** The 10s absorption window slides every 5s ⇒ one
   burst was counted 2-3× (~1100 rows/day MNQ, inflating n8 + the DIST streak). New
   per-(instrument, side) `RecentSignalGate` in `OrderFlowOrchestrator`;
   `riskdesk.order-flow.absorption.dedup-seconds=10` (default = window-seconds). A
   sustained 30s burst still yields 3 events 10s apart ⇒ genuine streaks still fire.
3. **Session-aware thresholds.** `riskdesk.order-flow.absorption.eth-threshold-multiplier=0.4`
   scales the absorption/momentum delta baseline outside RTH (09:30-16:00 ET, resolved
   DST-aware via new `TradingSessionResolver.isRegularTradingHours`); detectors stay
   session-unaware — the orchestrator passes the resolved value.
4. **Per-instrument G3/L3 + configurable G4/L4.** `riskdesk.quant.gates.delta-threshold.{MNQ=100,MCL=40}`
   (+`default-delta-threshold=100`), `bearish-buy-pct=48` / `bullish-buy-pct=52`.
   `QuantTelemetry.deltaThreshold` now reports the per-instrument resolved value.

## Candle backfill — explicit per-contract mode (`contractMonth=YYYYMM`) (2026-06-10)

The prod IB Gateway build rejects CONTFUT contracts with IBKR error 200 ("No security
definition has been found"), so `continuous=true` is dead on that build. The proven
alternative (validated 2026-06-10 via a one-shot prod script) is fetching an explicit,
possibly-expired quarterly contract for its front period — e.g. MNQ `202603` for
2026-01-01→2026-03-12, then MNQ `202606` for 2026-03-12→2026-04-10.

New opt-in param on `POST /api/candles/backfill/{inst}/{tf}`:

- `contractMonth=YYYYMM` — pins the whole `[from, to]` window to that single contract.
  Threaded `CandleBackfillController` → `HistoricalDataService.startBackfillRange` →
  new port default `HistoricalDataProvider.fetchContractMonthHistoryRange` (default 0),
  implemented in `IbGatewayHistoricalProvider` via
  `IbGatewayContractResolver.resolveExpiredMonth` (`includeExpired=true`, same
  resolution the expired-contract walk uses) + the existing
  `fetchRangeChunksFromContract` with the real month tag.
- Rows are tagged with the **real** contract month (`yyyyMM`), so
  `BacktestController.buildContinuousCandles` splices them at the correct roll date —
  unlike `"CONT"` rows, which are base-layer. The service-side null-tag fallback also
  uses the requested month, never the registry's current front month.
- **Mutually exclusive with `continuous=true`** — both set is REJECTED (HTTP 400).
  Format is validated (`\d{6}`); blank is normalized to absent. No recency guard
  (unlike continuous): the tag is the real month, so live-window rows stay visible to
  active-month consumers.
- Follows PR #434's hardening: composes with `replace=true` (lazy purge, `PARTIAL`
  state), and `contractMonth` is part of the strict-coalescing identity (an in-flight
  job only coalesces an identical request, including the month) and of the
  `BackfillJob` record + `/status` body.

Typical use (re-source January from the contract that was front then):
`POST /api/candles/backfill/MNQ/1m?from=2026-01-01T00:00:00Z&to=2026-03-12T00:00:00Z&contractMonth=202603&replace=true`

## Wall Tracker — traceability of large resting orders (UC-OF-012, 2026-06-10)

User goal: trace the big walls seen in the DOM ("WALL" flag, >5× avg level size) —
where they appear, how big they grow, and how they end. New components:

- **Domain** `WallTracker` (pure, per-instrument) consumes `DepthMetrics` ladder
  snapshots — NOT the raw `WallEvent` stream, whose per-index flagging flickers on
  book shifts. Episodes are keyed by tick-rounded price with a 5s grace window
  (re-flag within grace = same episode) and a 3s minimum lifetime (drops flicker).
- **Outcomes** on close: `CONSUMED` (same-side best ended ≤1 tick from the level or
  through it — price reached the wall), `PULLED` (remnant ≤25% of max size while
  price still away → spoof suspect), `FADED` (size still resting, just below the
  relative threshold), `OUT_OF_RANGE` (scrolled beyond the visible 10 levels).
- **Application** `WallTrackingService` polls 1s (`riskdesk.order-flow.wall-tracker.*`),
  publishes `WallEpisodeClosed` → persisted in `order_flow_wall_episodes` (90d purge,
  same retention job). REST: `GET /api/order-flow/walls/{instrument}` → `{active, recent}`.
- **Frontend** `WallTrackerPanel` (Order Flow panel, under the Order Book): live walls
  + history with outcome badges; REST poll 5s, no new WS topic.

## Tick chart — selectable 1000/2000-tick bars + SMC overlay (2026-06-10)

- Bar-size selector on `TickChart` (base 200 MNQ / 100 MCL, plus 1k/2k): larger sizes
  are merged client-side from base bars grouped on absolute `seq` — identical
  boundaries to a native N-tick aggregator, no WS change. Backend ring buffer and
  REST clamp raised 300/500 → 3000 (`riskdesk.order-flow.tick-chart.max-bars`).
- SMC overlay (default on, `SMC` toggle): price lines for the 3 nearest active order
  blocks + Strong H/L + Premium/EQ/Discount, sourced from the Dashboard's
  `IndicatorSnapshot` prop (selected timeframe, 30s poll) — guarded against
  instrument-switch lag via `snapshot.instrument`.

## Candle backfill — continuous contract (CONTFUT) + replace mode (2026-06-10)

The deep range backfill previously reconstructed past windows from the **current** front
month first (expired contracts only gap-filled the holes). For windows that predate the
current contract's front period (e.g. backfilling January when the front is M6), that
yields thin back-month bars with futures-curve offset — not the prices that actually
traded as front. Two new opt-in params on `POST /api/candles/backfill/{inst}/{tf}`:

- `continuous=true` — sources the window from IBKR's continuous series (secType
  `CONTFUT`): at every past date the bars come from the contract that was front-month
  at that date (TradingView-style stitching, done by IBKR). New port method
  `HistoricalDataProvider.fetchContinuousHistoryRange` (default 0), implemented in
  `IbGatewayHistoricalProvider` via `IbGatewayContractResolver.resolveContinuous`:
  the CONTFUT spec is probed variant-by-variant (with/without tradingClass ×
  includeExpired) against `reqContractDetails` — gateway builds differ in what they
  acknowledge, and a blind spec surfaces as silent empty historical responses. The
  first acknowledged contract is cached (own cache — CONTFUT is historical-only,
  never used for live/orders). Candles are tagged `contract_month = "CONT"`.
- `replace=true` — purges the stored window (`CandleRepositoryPort.deleteRange`,
  closed range, scoped to the pair) so a polluted window is re-sourced instead of
  being kept by the idempotent skip. The purge is **lazy** (fires on the first
  fetched chunk), so a dead gateway / no-op provider can never empty a window it
  cannot refill. If the refill dies mid-walk after the purge, the job ends
  **`PARTIAL`** (HTTP 206) with an explicit re-run message — never a silent DONE.

Hardening (adversarial review findings, same PR):
- `"CONT"` rows are part of the **base layer** in `BacktestController.buildContinuousCandles`
  (like legacy null-tag rows). Constant lives on `Candle.CONTRACT_MONTH_CONTINUOUS` —
  do NOT sort/splice "CONT" as a month tag (it sorts after every "yyyyMM" and the
  roll-date cut would drop the whole window).
- Continuous windows must END ≥ `riskdesk.market-data.historical.continuous-min-age-days`
  (default 7) in the past — CONT rows in the live window would be invisible to
  active-month-filtered consumers (indicators, Mentor, scanners).
- An in-flight job only coalesces an **identical** request (same window + flags);
  a different one is REJECTED instead of silently swallowed.
- Known residual risk: jobs live in memory — an app restart mid-replace loses the
  `/status` evidence. Re-run the same command after a restart (idempotent).

Typical use (re-source a window that was filled with back-month bars):
`POST /api/candles/backfill/MNQ/1m?from=2026-01-01T00:00:00Z&to=2026-03-01T00:00:00Z&continuous=true&replace=true`

## Tick chart — constant-tick-count bars (2026-06-10)

New activity-normalized chart: one candle per N classified trades (MNQ 200, MCL 100;
`riskdesk.order-flow.tick-chart.*`), with a per-bar delta histogram.

- Domain: `TickBar` record (OHLC + buy/sell volume + delta + monotonic `seq`) and
  `TickBarAggregator` (pure, per-instrument bounded ring buffer of 300 completed bars
  plus the in-progress bar, `complete=false`).
- Infra: `IbkrTickBarAdapter` (implements new `TickBarPort`), fed from
  `IbkrTickDataAdapter` next to the footprint routing (classified ticks only).
- REST: `GET /api/order-flow/tick-bars/{instrument}?limit=200` (oldest first).
- WS: `/topic/tick-bars` pushes `{instrument, bars: last-3 tail}` every 2s — consumers
  merge by `seq`, so bars completed between pushes reconcile on the next one.
- Frontend: `TickChart.tsx` (lightweight-charts candles + delta histogram) mounted in
  `Dashboard` above the footprint; REST seed + live WS merge in `useOrderFlow`.
  lightweight-charts needs strictly increasing times, so same-second closes are
  de-duplicated by bumping +1s.
## Footprint: real clock-aligned 10m bars + persisted history (2026-06-10)

The footprint "bar" was previously a cumulative-since-boot profile: `IbkrFootprintAdapter.onCandleClose`
had no caller, the aggregator never reset, the WS payload was hardcoded `"5m"` with
`barTimestamp = now()`, and `order_flow_footprint_bars` had never received a single row.

Reworked (user-validated spec: **10m bars, 5.00pt MNQ / 0.05 MCL price buckets**):
- `FootprintAggregator` (domain, pure) now owns the bar lifecycle: clock-aligned windows
  (`floor(epoch/barSeconds)`), tick-driven roll-over returning the closed bar, `closeIfElapsed`
  for idle sweeps, and floor-to-bucket price binning (`riskdesk.order-flow.footprint.bucket-size.*`,
  fallback = native tick size).
- `IbkrFootprintAdapter` publishes each closed bar as a `FootprintBarClosed` domain event;
  `OrderFlowEventPersistenceService` persists it (idempotent on the unique constraint);
  `OrderFlowOrchestrator` pushes closed bars + the in-progress bar on `/topic/footprint`
  and sweeps idle bars on the 5s footprint scheduler.
- `FootprintPort` is now `currentBar(Instrument)` + `closeElapsedBars(Instant)` — the
  `timeframe` request param on `GET /api/order-flow/footprint/{instrument}` is accepted
  but ignored (bar duration is server-side config `riskdesk.order-flow.footprint.bar-minutes`).
- New history endpoint `GET /api/order-flow/footprint/{instrument}/history?bars=12`
  (via `OrderFlowHistoryService.recentFootprintBars`, profile JSON round-trip).
- Frontend `FootprintChart.tsx`: shows bar open time, and a "CLOSED BARS" strip
  (time / delta / POC / volumes) refreshed when the live bar rolls.
## Order-flow detector recalibration — data-driven (2026-06-10)

Calibrated from 14 days of prod score percentiles (queried live from `order_flow_*_events`).
Problem was inverted tuning: absorption ~1100 events/day on MNQ (spam) while momentum fired
0-2/day (median emitted score 0.59 vs threshold 0.55 → dense just under the cutoff) and the
Smart Money Cycle panel was EMPTY (confidences cluster 51-53, display floor was 70).

- **Display-only filters** (new): `riskdesk.order-flow.{absorption,spoofing}.min-display-score.<INSTRUMENT>`
  applied at the UI boundary (WS topics + REST history via new score-filtered repository
  queries). Detection, persistence, the distribution chain and quant gates are NOT filtered —
  internal consumers still see every event. Defaults: absorption MNQ 80 (≈P99 → ~11/day),
  MCL/MGC/E6 30; spoofing MNQ 35, others 20.
- **Momentum**: score-threshold 0.55 → 0.50, min-price-move 0.3 → 0.2 ATR (~3-10/day target).
- **Cycle**: min-confidence 70 → 55 (panel shows the genuine >P90 tail instead of nothing).
- **Iceberg**: score is degenerate in prod (every event = exactly 100) so min-score cannot
  discriminate; dedup-seconds 60 → 300 is the effective rate control. Score formula rework
  is a known follow-up.
- **Bug fixed**: `/topic/absorption` was published TWICE per event — by the orchestrator
  (correct keys `score`/`delta`) and by `OrderFlowCorrelationService` (mismatched keys
  `absorptionScore`/`aggressiveDelta` → blank duplicate rows in the UI). The correlation
  listener is now log-only.
## Real L2 order book exposed end-to-end (2026-06-10)

The frontend `DepthBookWidget` was an **orphan component** (mounted nowhere) that fabricated
ladder levels from aggregates with `Math.random()`. Meanwhile the backend's `MutableOrderBook`
already held the real 10 levels per side from `reqMktDepth` but only published aggregates.

- `DepthMetrics` (domain) gains `bids` / `asks`: full best-first ladders of the new
  `DepthLevel(price, size, wall)` record; built inside the seqlock read in
  `MutableOrderBook.computeMetrics` (level arrays copied before the generation re-check).
- `/topic/depth` payloads and `GET /api/order-flow/depth/{instrument}` now include
  `bids` / `asks` as `{price, size, wall}` lists.
- `DepthBookWidget` rewritten as a real DOM ladder (asks above the spread separator,
  bids below, proportional volume bars, WALL highlight) and mounted in
  `OrderFlowPanel` under the depth gauges for the selected instrument.

## Named WTX override preset `top-train-Z35` + zone-entry overrides (2026-06-10)

A real-1m grid study (MNQ 10m, train mars-avril 2026 → OOS mai-juin 2026, exits replayed on 1m
children) surfaced a **zone-entry** configuration that beat the deployed every-cross config on
quality metrics (OOS ≈ +$6.6k / 54% WR at qty=1 session-off vs 33% WR deployed; PF 1.75 vs 1.18
over the full window). Shape: WaveTrend **5/14/2**, initial stop **4.0×ATR** (SL_ONLY ride),
entries **only on crosses inside the ±35 zone** (`useCompra1/useVenta1=false`, `nsc/nsv=±35`).

To make it activatable per panel without touching the global config:
- `WtxParamOverride` gained five nullable fields — `nsc`, `nsv`, `useCompra1`, `useVenta1`,
  `sessionFilterEnabled` — plus the named preset constant `TOP_TRAIN_Z35` and a `preset(name)`
  resolver (`"clear"` → `NONE`). `sessionFilterEnabled=false` in the preset disables the
  03:00-08:00 ET entry block for THIS panel only (the winning run was session-OFF; legacy panels
  keep the global protection; block boundaries stay global; the NY force-close still applies).
- `WtxConfig.withSignalZone(...)` wither; `WtxStrategyService.applyOverrides` applies the zone
  fields; the partial panel edits (`updateIndicatorParams`, `updateSlAtrMult`) now **preserve**
  the zone override instead of wiping it.
- New endpoint `PUT /api/wtx/state/{instrument}/{timeframe}/preset` with body
  `{"preset":"top-train-z35"}` (or `"clear"`), backed by `WtxStrategyService.applyPreset(...)`.
  State views/WS payloads now expose effective `nsc` / `nsv` / `zoneOnlyEntries`.
- `wtx_param_overrides` gains nullable columns (`nsc`, `nsv`, `use_compra1`, `use_venta1`) —
  Hibernate ddl-auto extends the table in place.

**Variant panels — the `top-train-Z35` SIGNAL runs in PARALLEL with the legacy panel.** The
preset is not just an override you can apply to the legacy panel: `riskdesk.wtx.variants[...]`
declares parallel named signals. Each variant rides the SAME closed candles as its base timeframe
but keeps its own state / signal history / overrides under a short **panel key** (`10m-z35`,
≤10 chars — the override table's timeframe column length). Wiring:
- `WtxStrategyService.onCandleClosed` → `processPanel(event, panelKey)` runs once for the legacy
  panel (key == timeframe, bit-for-bit legacy behaviour) then once per matching variant. Candle
  data / enrichment always read the BASE timeframe; identity (state, signals, WS topics,
  overrides) uses the panel key. A variant's base config = global + its named preset; stored
  per-panel overrides still apply on top (the panel stays tunable from the UI).
- `forceCloseAll`, `WtxDailyResetScheduler` and `WtxDefaultProfileBootstrap` (BASELINE→HTF) all
  cover variant panels. Auto-execution defaults OFF like any fresh panel → paper by default.
- `GET /api/wtx/variants` lists configured variants (served as `WtxVariantView` — presentation
  must not touch the infrastructure config type, ArchUnit enforces it). Every per-panel endpoint
  accepts the panel key as its `{timeframe}` path variable.
- Frontend: `Dashboard.tsx` renders a `WtxStrategyPanel` with `displayName="top-train-Z35"` and
  `timeframe="10m-z35"` just below the legacy WTX panels for MNQ (amber header = variant).

**Caveats**: the preset is in-sample-selected (mars-avril) with one OOS window (mai-juin, bullish);
the study assumed no slippage and qty=1. Intended path: let the variant panel run on paper
(auto-execution OFF) and compare against the deployed config on forward data before any live use.

## Deep 1m range backfill + cursor-paginated range read (2026-06-09)

Backtests need real 1m over many days, but the DB only held a narrow 1m window and the chart
endpoint is hard-capped at 1000 candles. Two additions close that gap — **ingestion** and
**retrieval** — without touching the chart endpoint or adding any external data source.

**Ingestion — deep, idempotent, *streaming* range backfill (admin-triggered):**
- Domain port `HistoricalDataProvider.fetchHistoryRange(instrument, tf, from, to, Consumer<List<Candle>>)`
  — the **streaming** overload is the real path; it hands each fetched chunk (~one IBKR request) to
  the sink the moment it arrives instead of accumulating the window. The old List-returning overload
  is kept as a thin convenience wrapper (buffers — small windows/tests only). Implemented in
  `IbGatewayHistoricalProvider`: walks **backward from `to` to `from`** day-by-day for 1m (2s IBKR
  pacing), crossing into expired contracts gap-fill-only (never overwriting newer-contract bars),
  bounded by `MAX_RANGE_CHUNKS=240`. Coverage is tracked with a single `minSeen` instant, not a
  buffered map. Unlike the count-based `fetchDeepHistory` it reconstructs an arbitrary window and
  fills **middle** gaps, not just past the high-water mark.
- `HistoricalDataService.startBackfillRange(...)` orchestrates it. **Memory-bounded**: chunks stream
  straight into PostgreSQL via `persistBackfillChunk` — heap stays at ~one IBKR request, never the
  whole `[from,to]` window (a deep 1m window is ~10^5 candles and must not be buffered). **Idempotent
  per chunk**: an existence probe bounded by the chunk's own min/max skips already-present
  timestamps, so re-runs (and older-contract chunks overlapping already-saved front-month bars) are
  no-op saves that never trip the `(instrument,timeframe,ts)` unique key. Gap-fill-only across
  contracts falls out naturally: front-month chunks are persisted first, so the idempotent sink drops
  the overlap. Runs on a dedicated single thread (serialises heavy pulls, protects pacing); same-pair
  jobs are coalesced. Async by default with a `RUNNING→DONE/FAILED` job snapshot; validation guards reject
  inverted/oversized windows (`backfill-range-max-days`, default 200 ≈ the practical IBKR 1m depth).
  The expired-contract walk depth **scales with the requested window**
  (`IbGatewayHistoricalProvider.rangeContractWalk`, capped at `MAX_CONTRACT_WALK=24`), so 4–6 month
  1m pulls reach far enough back across contracts without manual tuning — the loop still exits early
  once `from` is covered. Real ceiling is now IBKR (1m history ≈ 6 months for futures), not the code.
- REST (`CandleBackfillController`): `POST /api/candles/backfill/{instrument}/{timeframe}?from=ISO&to=ISO[&async=true]`
  and `GET /api/candles/backfill/{instrument}/{timeframe}/status`.
- **Daily currency** of 1m reuses the *existing* hwm-delta path (`gapFillTimeframe`, same as 5m/10m)
  at startup — no new scheduler was added (deliberate; the deep seed is a one-shot admin call).

**Retrieval — cursor-paginated range read (overcomes the 1000 cap):**
- New `CandleRepositoryPort.findCandlesBetweenPaged(...)` + Spring Data `Between ... Pageable`.
- `GET /api/candles/{instrument}/{timeframe}/range?from=ISO&to=ISO&limit=N` returns **raw** candles
  (no out-of-session purge, no contract-month filter — matches what `WtxRsiBacktestService` actually
  consumes) oldest→newest with a `nextFrom` epoch-second cursor (`null` when exhausted). Page size
  defaults to `riskdesk.candles.range.default-page-size` (5000), capped at `max-page-size` (50000).
- The original `?limit=N` (≤1000) chart endpoint is **unchanged**.

Params accept ISO-8601 or bare epoch seconds (`RequestInstants`). Config keys added under
`riskdesk.market-data.historical.backfill-range-max-days` and `riskdesk.candles.range.*`.
Tested: backfill idempotency/validation, range cursor math, repo pagination (H2), controller wiring.

## WTX — HTF-bias early exit ("A2") shipped (forward-paper, 2026-06-08)

New opt-in exit on top of the SL_ONLY/ride profile: **close an open position when the 1h bias no
longer SUPPORTS its direction** (turned NEUTRAL or flipped against it). The ride otherwise only
leaves on the initial SL or a full opposite WaveTrend cross — A2 gives it a way out the moment the
higher-timeframe trend that justified the position fades, instead of waiting for the cross.

**Edge (real-1m MNQ 10m backtest, 2 windows ~82d, qty1):** +60% net vs ride-only, win-rate
**32% → 46%**, robust on both windows. The 1h bias only changes on 1h closes, so it's evaluated on
each closed 10m candle with **no cross-timeframe coupling** — A2 at 10m granularity ≈ A2 at 5m
granularity (+11.9k vs +12.2k$), so the clean 10m implementation was chosen.

**Rule** (`WtxHtfBiasExitEvaluator.shouldExit(position, bias)`, pure domain):
LONG exits on `BEARISH`/`NEUTRAL`; SHORT exits on `BULLISH`/`NEUTRAL`; a supporting or
`UNAVAILABLE` bias never forces an exit (fail-safe); FLAT is a no-op.

**Wiring:**
1. `WtxHtfBiasExitEvaluator` (domain) — pure decision, unit-tested (`WtxHtfBiasExitEvaluatorTest`).
2. `WtxStrategyService.onCandleClosed` §3b — after the signal block, before max-loss. Guarded by
   `state.currentPosition() == posBeforeSignal` so it only acts on a position that *rode* this bar
   (never double-closes a §3 reverse/close; a freshly opened position already passed the HTF gate,
   so re-checking is a no-op). Reuses the MAX_LOSS/force-close close pattern: route-before-flatten,
   `skippedEntryInFlight` guard, realized-P&L delta stamp, `/topic/wtx-signals` publish.
3. New `WtxExitType.HTF_BIAS` — close rows carry it; UI chip **"BIAS 1H"** (violet) in
   `WtxStrategyPanel`, union extended in `api.ts`.
4. Flag `riskdesk.wtx.htf-bias-exit-enabled` on `WtxStrategyProperties` (read directly in the
   service to avoid the 47-field `WtxConfig` record arity change). **Default OFF** (Java); the live
   `application.properties` opts in (`=true`) for forward-paper.

**Caveat (why forward-paper, not "done"):** validated on backtest P&L only; Auto-IBKR stays OFF.
Set `riskdesk.wtx.htf-bias-exit-enabled=false` to restore the pure ride (SL + opposite-cross only).

## WTX — session block moved Asia/overnight → London (forward-paper, 2026-06-07)

Config-only change (reversible): `application.properties` session block
`18:00→03:00` (Asia/overnight) → `03:00→08:00` (London/UK).

**Why:** the overnight block was tuned for the old TRAILING exit (whipsawed in thin overnight
chop). The now-deployed SL_ONLY/ride exit flips it — on the real-1m backtest (2 windows, ~82d),
overnight is the ride config's BEST session (+~7.5k$/82d qty1) while LONDON/UK is the genuinely
weak one (5m mild, 10m negative). Moving the block to London gained ~+7.6k$ net/82d in backtest.

**Caveat (why it's forward-paper, not "done"):** that gain ≈ trading overnight, where execution
(thin-liquidity slippage on Asia fills) is NOT modeled by the backtest. Run in paper (Auto-IBKR
stays OFF) and watch overnight fills before trusting it. Revert the two values to `18:00→03:00`
to restore the old block. The `WtxStrategyProperties` Java default stays `18:00→03:00` as the
conservative fallback (so the existing `WtxSessionFilterTest` is unchanged and still green).

## WTX — per-day realized P&L in the signal history (2026-06-07)

To make the "Signaux récents" history readable at a glance, each WTX day-group header now shows that
day's **realized P&L total** (e.g. `5 Juin (20) · +120$`, green/red).

- **Source of truth.** The per-trade P&L was already computed on close
  (`WtxStrategyService.closePosition` → `Instrument.calculatePnL`) but only folded into the running
  `dailyRealizedPnl` and then discarded. It is now captured as the **delta of `dailyRealizedPnl`
  across the close** and stamped onto the closing signal at all four close paths (REVERSE/CLOSE,
  trailing exit, max-loss halt, force-close). Summing it per day therefore reconciles exactly with the
  panel's live "Daily P&L" bar.
- **New nullable field** `realizedPnl` on `WtxSignal` (record) + `wtx_signal_history.realizedPnl`
  column (Hibernate `ddl-auto=update` creates it — no migration; mirrors how `price`/`exitType` were
  added). Threaded through `JpaWtxSignalHistoryAdapter`, `WtxStrategyController.toSignalView`, and the
  `/topic/wtx-signals` WS payload (so the live close carries it past the panel's first-wins de-dup).
- **Day boundary = CME trading day (17:00 ET).** The history now groups by trading day, not local
  calendar day, so the per-day total aligns with the backend daily reset
  (`TradingSessionResolver.tradingDate`, replicated on the client in `WtxStrategyPanel.tradingDayBucket`).
  `DayGroupedSignals` gained two opt-in props (`bucketOf`, `renderDayMeta`); `WtxRsiStrategyPanel` is
  unchanged (still local-day, no total).
- **Caveats.** Rows persisted before this change have `realizedPnl = null` (treated as 0 → no backfill);
  totals are accurate going forward only. Under Auto-IBKR the value is the optimistic close P&L (may
  roll back on an unfilled close, same as `dailyPnl`); in paper mode it is exact.
- Tests: `JpaWtxSignalHistoryAdapterTest` (round-trips the column, null on opens).

## WTX — "HTF SL-only / ride" profile + frontend param config (2026-06-07)

Real-1m backtest (true intrabar path; 2 windows ~84 days of 1m exported from prod/local PostgreSQL,
resampled to 5m/10m) overturned the trailing magnitudes: the optimistic 5m/10m-OHLC backtest
**massively overstated tight trailing** (the artifact grew with tightness) and **inverted the
trail-distance ranking**. Verified findings: the WTX edge is the **1h entry filter** (path-independent,
reliable), **NOT the trailing** — the tight ratchet was net-**negative** under the real path. Best
config = **HTF + fixed SL 2.0×ATR + ride to the opposite WaveTrend cross (no trailing ratchet)**.

Shipped (paper-safe, Auto-IBKR stays OFF, reversible via config):

1. **New exit mode `WtxTrailingMode.SL_ONLY`** — the fixed initial SL is the only stop; the trail
   never arms, so the position rides to the opposite cross. `WtxTrailingExitEvaluator.evaluate` gets
   a self-contained SL_ONLY branch (keeps the fixed stop, still tracks MFE for display). Now the
   global default: `riskdesk.wtx.trailing-mode=SL_ONLY` (POINTS/ATR retained for reversibility).
2. **New defaults** (`application.properties`): `n2 21 → 28`, `signal-period 4 → 2` (most consistent
   cell in the real-1m n1/n2×sig matrix). `n1=10`, `sl-atr-mult=2.0` unchanged.
3. **Per-(instrument,timeframe) frontend overrides for n1 / n2 / signalPeriod / SL** — isolated store
   (`wtx_param_overrides` table) via `WtxParamOverride` + `WtxParamOverridePort` +
   `JpaWtxParamOverrideAdapter` (cached). Deliberately NOT on `WtxStrategyState` (its ~18 positional
   withers make field-adds fragile). `onCandleClosed` reassigns `config` to an EFFECTIVE config
   (`applyOverrides` → `WtxConfig.withIndicatorParams` / `withSlAtrMult`); since WaveTrend + the exit
   evaluator both read `config`, overrides apply with no further wiring. Null override → global fallback.
4. **Endpoints + UI**: `PUT /api/wtx/state/{i}/{tf}/indicator-params` `{n1,n2,signalPeriod}` and
   `…/sl` `{slAtrMult}` (validated). State view/WS payload expose effective `n1/n2/signalPeriod/slAtrMult`.
   `WtxStrategyPanel` adds 4 inline numeric inputs (n1, n2, Sig, SL×ATR) mirroring the Qty draft/commit
   pattern. SL exposed as an **ATR multiple** (instrument-agnostic).

Honesty caveats: real-1m edge is **thin** (≈ +2–5k$/40d qty1) with **low WR (33–37%)**, in-sample,
no costs → forward-paper before any live. `BASELINE`/`SESSION_ATR`-alone are real-path bad (the entry
filter is what works). Validation: 2151 tests green, ArchUnit clean, frontend lint + build clean.

## WTX — analysis-driven trading rules shipped (2026-06-07)

Backtest analysis (real engine on internal candles; see `docs/WTX_ANALYSIS.md`) drove four rule
changes. All paper-safe (Auto-IBKR stays OFF), reversible via config.

1. **SL widened 1.3 → 2.0×ATR** (`application.properties` `riskdesk.wtx.sl-atr-mult`). The 1.3 stop
   was the n°1 leak (premature stop-outs, deep MAE / near-zero MFE). The sweep showed 1.6–2.0×ATR
   lifts P&L, PF **and** win rate together.
2. **Session entry filter** — blocks NEW entries (OPEN / REVERSE-open) during the thin Asia/overnight
   window, default **18:00 → 03:00 ET** (`riskdesk.wtx.session-filter-enabled` /
   `session-block-{start,end}-et`). Boundaries are `America/New_York`, DST-safe, wrap past midnight.
   Pure-domain: `WtxConfig.isWithinSessionBlock` + `WtxRiskGuard.isEntryBlockedBySession`, folded into
   `WtxBarEvaluator` alongside `htfBlocked`/`structureBlocked` → blocked entry = action `NONE`.
   Open positions are still managed (trailing/force-close run); only fresh overnight risk is gated.
3. **HTF default for MNQ** — `WtxDefaultProfileBootstrap` (ApplicationReadyEvent) upgrades any MNQ
   (instrument, timeframe) still on `BASELINE` to `HTF` at boot, **without** overriding a manual
   SESSION_ATR/STRICT. Config: `riskdesk.wtx.htf-default-instruments=MNQ`. Swing-bias stays OFF
   (validated: redundant with HTF). BASELINE was proven to have no edge — and to **bleed in trending**
   (10m TREND −3158$ over 151d) — so HTF, not BASELINE, is the trend answer.
4. **Regime warning badge** — `WtxStrategyService.currentRegime` (EMA9/50/200 + Bollinger-width →
   `MarketRegimeDetector`) surfaced on the WTX state view + WS payload; `WtxStrategyPanel` shows an
   amber `⚠ TENDANCE` chip in TRENDING. **Informational only** — does not gate trading (HTF already
   filters direction). Max-loss kept as-is ($500 global) by request.

Tests: `WtxSessionFilterTest` (DST spring/fall, wrap, parse, live config). Domain defaults() keep the
session filter OFF so evaluator/risk-guard tests stay bit-for-bit. Full suite green (2142).

## Order-flow "delta" staleness — robust layered fix (L0–L5) (2026-06-05)

**Problem.** The order-flow delta intermittently froze / showed `STALE` ("delta disparue des fois").
Root cause was **classification starvation**, NOT IBKR request overload: futures never open a streaming
**QUOTE** subscription (only `ensureStreamingPriceSubscription`), so `latestStreamingQuote()` is always
empty and ticks were classified against a freshness-unguarded synthesized mid. `StreamingPriceSubscription`
nulls bid/ask after 30s (`SESSION_GAP_SECONDS`), so in a quiet market `classifyTrade(0,0)` returned
`UNCLASSIFIED` and `TickByTickAggregator.onTick` **dropped the tick** — `totalTicksReceived` climbed while
`buyVol+sellVol` stayed 0 and `lastTickError` was null (failed invisibly). The PR-356 publish gate then
suppressed the emit, the frontend froze the last value, and the Quant 7-gates hard-failed G3/G4/L3/L4 to
null (sim-exec silently degraded).

**Fix — 6 layers, all additive + flag/config-gated, reversible:**
- **L0** Cut tick-by-tick + depth to `MNQ,MCL` (`riskdesk.order-flow.{tick-by-tick,depth}.instruments`);
  `MGC,E6` are `tick-by-tick.degraded-instruments` → reported `DEGRADED_NOT_SUBSCRIBED`. Pressure
  reduction, not a cap fix — confirm via `/api/order-flow/status`.
- **L1** `IbGatewayNativeClient.latestStreamingBbo(contract, maxStaleness)` exposes the real BBO already
  captured by the price subscription (zero new IBKR lines) with a last-known-good cache
  (`bbo-max-staleness-seconds=90`, larger than the 30s nulling). `TickByTickClient` resolves bid/ask as
  BBO → quote → `0` (the synthesized-mid mis-anchor is gone).
- **L2** Tick-rule fallback: when Lee-Ready is UNCLASSIFIED, classify by trade-to-trade direction
  (uptick=BUY/downtick=SELL) in `IbkrTickDataAdapter.classifyByTickRule`; such windows are stamped
  `TickAggregation.SOURCE_REAL_TICKS_TICKRULE` (when quote-classified fraction < `real-ticks-min-quote-fraction=0.5`).
  Strict consumers treat it as CLV-grade (0.5): `TriggerContextBuilder` maps it to `CLV_ESTIMATED`,
  `AgentContext.hasRealTicks()` stays exact-`REAL_TICKS`. Gated `tick-rule-fallback-enabled=true`.
- **L3** `OrderFlowOrchestrator.checkDeltaFreshness` (15s) keys on **classified-tick yield**
  (`IbkrTickDataAdapter.lastClassifiedAt`, a new `TickDataPort` default method), closing the 60–300s dead
  zone. Single owner of resubscription: `TickByTickClient.allowResubscribe` is a shared per-instrument
  rate cap (`max-resubscribes-per-minute=2`) consulted by all three loops; the internal 60s watchdog is
  alarm-only unless `internal-watchdog-resubscribes=true`.
- **L4** `publishOrderFlowMetrics` emits a server-authoritative **heartbeat** on quiet/empty/dead windows:
  `serverStale`, `feedHealth` (REAL_TICKS / REAL_TICKS_TICKRULE / STARVED / DEGRADED_NOT_SUBSCRIBED), and
  `dataTimestamp = lastGenuineWindowEnd` (never `now`, never a fabricated delta). Frontend prefers
  `serverStale` over the 120s client age.
- **L5** `GateResult.abstain(...)` (new 3rd record component, backward-compatible 2-arg ctor); G3/G4/L3/L4
  abstain (not directional-fail) when the delta feed is down; `QuantSnapshot.deltaAvailable()` surfaces it.

**Review-hardening (two multi-agent passes):** a frozen feed keeps old ticks lingering in the 5-min
window, so the snapshot stays `REAL_TICKS` with a non-null delta for up to 300s. Closed everywhere
that mattered: the Quant 7-gate now drops a delta whose `windowEnd` is older than `DELTA_MAX_AGE`
(60s) → abstain (no auto-arm on a dead book); `feedHealthFor` returns `STARVED` the moment the last
classified tick is stale (not only when the deque drains), so REST + WS never show green `REAL`
beside a `STALE` badge; `snapshotReadOnly` filters by cutoff instead of `evictExpired` (no deque race
off the scheduler); the disconnect/health-check wipes clear `deltaStaleStrikes` + `lastGenuineFlow`
(no post-reconnect strike-lockout, no stale/wrong-contract replay); fresh subscriptions reset the
tick-rule reference price (no cross-contract mis-classification at rollover); and L5's `abstain` /
`deltaAvailable` are surfaced on `QuantGateView` / `QuantSnapshotResponse`.

Validation: `mvn test` (2134 green, incl. `HexagonalArchitectureTest`), frontend `lint` + `build`.
Fast rollback = config: restore instrument lists + `tick-rule-fallback-enabled=false` +
`freshness.enabled=false` + `internal-watchdog-resubscribes=true`.

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
