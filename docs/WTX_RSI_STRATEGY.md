# WTX+RSI Strategy

MNQ-focused strategy combining **WaveTrend (LazyBear)** crossovers with an
**RSI/SMA(RSI)** confirmation gate, Chaikin oscillator size multiplier, and a
**Williams fractal** stop-loss. Lives in `domain/engine/strategy/wtxrsi/`.

The Python prototype under `auto_trader/` documents the same rules as an
executable specification — useful for sanity-checking parameter changes
before they hit the Java backend.

## Logic

A signal fires on the close of bar `i` when:

1. **RSI cross** — RSI(14) crosses its SMA(14) on bar `i`.
2. **WT cross** — a WaveTrend bullish (LONG) or bearish (SHORT) cross
   occurred on some bar `j` in `[i - X, i]` where `X = syncLookbackBars`.
   The WT cross is causal: it must precede or coincide with the RSI cross.
3. **Zone predicate** — depending on `zoneMode`:
   - `STRICT_ZONE`: WT1 at bar `j` is within OB/OS band
   - `VISITED_RECENTLY`: OB/OS was visited in the last `zoneLookbackBars`
   - `CROSS_FROM_ZONE`: WT1 at bar `j` or `j-1` is within OB/OS
4. **Chaikin confirmation (optional)** — if Chaikin oscillator agrees with
   the signal direction at bar `i`, the contract count is doubled. When
   `chaikin-required=true`, confirmation becomes a hard **entry gate**:
   unconfirmed signals do not open at all (see below).

Sizing: `baseContracts × (confirmedMultiplier if Chaikin agrees else 1)`.

**Chaikin as an entry gate (`chaikin-required`, opt-in):** by default Chaikin
only scales size. Set `riskdesk.wtxrsi.chaikin-required=true` to also *require*
confirmation to open — unconfirmed signals are suppressed (recorded as a `NONE`
signal with reason `chaikin-required:`). This is **entry-only**: exits keep their
existing mechanism (reversal-on-opposite-signal and SL/TP fire regardless). The
gate is a no-op unless `chaikin-enabled=true` (confirmation that is never
computed would otherwise block every entry). Honoured by both the live executor
and the backtest (`chaikinRequired` request override).

Risk: SL = most recent **confirmed** Williams fractal of opposite polarity
(LONG → fractal low, SHORT → fractal high), offset by `swingBufferTicks ×
tickSize`. A fractal is *confirmed* only after `fractalLeftRight` bars have
closed to its right — no look-ahead.

Take-profit (optional):
- `REVERSAL` (default): exit on the next opposite WTX+RSI signal
- `R_MULTIPLE`: fixed TP at `entry ± tpRMultiple × initialRisk`

## Configuration

`application.properties` keys (override per-instrument as needed):

```
riskdesk.wtxrsi.enabled=false                    # master switch (off by default)
riskdesk.wtxrsi.instruments=MNQ
riskdesk.wtxrsi.timeframes=5m,10m
riskdesk.wtxrsi.zone-mode=STRICT_ZONE            # or VISITED_RECENTLY / CROSS_FROM_ZONE
riskdesk.wtxrsi.sync-lookback-bars=3             # X (5m default)
riskdesk.wtxrsi.fractal-left-right=2             # Y
riskdesk.wtxrsi.fractal-max-lookback=20
riskdesk.wtxrsi.swing-buffer-ticks=2
riskdesk.wtxrsi.tp-mode=REVERSAL
riskdesk.wtxrsi.tp-r-multiple=0
riskdesk.wtxrsi.chaikin-enabled=true
riskdesk.wtxrsi.chaikin-required=false           # entry-only gate: only open Chaikin-confirmed signals
```

See `WtxRsiStrategyProperties` for the full list.

## REST endpoint

```
POST /api/strategy/wtxrsi/backtest
Content-Type: application/json

{
  "instrument": "MNQ",
  "timeframe":  "5m",
  "from":       "2025-01-01T00:00:00Z",
  "to":         "2025-04-30T23:59:59Z",
  "syncLookbackBars": 3,
  "zoneMode":   "STRICT_ZONE",
  "tpMode":     "R_MULTIPLE",
  "tpRMultiple": 2.0
}
```

The service reads 1m candles from PostgreSQL and resamples in memory to the
target timeframe; the backtest engine then walks bar-by-bar, fills entries
at the open of bar `i+1`, and applies the pessimistic intra-bar rule (SL
wins when both SL and TP touched in the same bar — matches
`TradeSimulationService`).

## Files

| Path | Role |
|------|------|
| `domain/engine/strategy/wtxrsi/WtxRsiConfig.java` | Immutable parameter record |
| `domain/engine/strategy/wtxrsi/WtxRsiBarEvaluator.java` | Core sync detector |
| `domain/engine/strategy/wtxrsi/WilliamsFractal.java` | Confirmed fractal pivot search |
| `domain/engine/strategy/wtxrsi/WtxRsiRiskCalculator.java` | SL/TP/sizing |
| `domain/engine/strategy/wtxrsi/WtxRsiBacktestEngine.java` | Bar-by-bar simulator |
| `domain/engine/strategy/wtxrsi/WtxRsiMetrics.java` | Win-rate / PF / max DD |
| `domain/engine/indicators/RsiWithSma.java` | RSI + rolling SMA(RSI) with cross flags |
| `application/service/strategy/wtxrsi/WtxRsiBacktestService.java` | Spring orchestrator |
| `application/service/strategy/wtxrsi/CandleResampler.java` | 1m → Nm aggregation |
| `presentation/controller/WtxRsiBacktestController.java` | REST endpoint |
| `infrastructure/config/WtxRsiStrategyProperties.java` | `@ConfigurationProperties` |

Tests under `src/test/java/com/riskdesk/domain/engine/strategy/wtxrsi/`
and `RsiWithSmaTest`. 22 unit tests; all green; full project at 1750/1750.

## Live executor

Mirror of `WtxStrategyService` — same shape, same gates, slimmer state.

- **Activation**: `riskdesk.wtxrsi.enabled=true` (default OFF). Without it, the
  service bean isn't created and `CandleClosed` events are ignored by this module.
- **Event entry**: `WtxRsiStrategyService.onCandleClosed(CandleClosed)` —
  filters by `instruments` + `timeframes`, then:
  1. Refresh open position: if intra-bar high/low touches SL or TP, close it
     (pessimistic: SL wins on a same-bar collision).
  2. Evaluate a fresh signal on the closed bar.
  3. If opposite to current side AND `tpMode=REVERSAL`, close-and-reverse.
  4. Persist state + append signal record + WebSocket publish.
- **IBKR routing toggle**: per (instrument, timeframe) flag
  `WtxRsiStrategyState.autoExecutionEnabled`, default OFF.
  - OFF → signals fire and are persisted, but the bridge is never called.
    The signal record carries `routingOutcome = SKIPPED_AUTO_OFF` so the UI can
    explain why no order went out.
  - ON → routing goes to `IbkrWtxRsiExecutionBridge` (only built when
    `riskdesk.wtxrsi.enabled=true`), which submits a single limit entry via the
    existing `IbkrOrderService` and writes a `TradeExecutionRecord` with
    `triggerSource=WTXRSI_AUTO`. Idempotence key: `wtxrsi:<i>:<tf>:<ts>:<action>`.
- **Bracket orders are deferred**: SL is enforced *server-side* by the
  orchestrator (next bar evaluation) — not pushed as an attached IBKR stop.
  Implementing native brackets requires extending `IbGatewayNativeClient` with
  a `placeBracketOrder` helper. Tracked as a follow-up slice.

REST endpoints (only bound when `riskdesk.wtxrsi.enabled=true`):

```
GET  /api/strategy/wtxrsi/state/{instrument}/{timeframe}
POST /api/strategy/wtxrsi/state/{instrument}/{timeframe}/auto-execution
     Body: { "enabled": true }
POST /api/strategy/wtxrsi/state/{instrument}/{timeframe}/order-qty
     Body: { "quantity": 2 }
POST /api/strategy/wtxrsi/state/{instrument}/{timeframe}/swing-bias-filter
     Body: { "enabled": true }
GET  /api/strategy/wtxrsi/signals/{instrument}?timeframe=5m&limit=50
```

## Swing-bias filter

Optional directional filter, off by default, flippable per (instrument, timeframe).
When enabled:

- **Fresh entry signals** are suppressed if their direction contradicts the resolved
  bias. The history row carries `action=NONE` with `routingErrorMessage="swing-bias
  filter: BEARISH"` so the UI can explain the silence.
- **Open positions** pointing against the bias are downgraded to a CLOSE — the
  orchestrator routes the close through the same bridge path as a normal exit,
  with `routingErrorMessage="swing-bias flip → BULLISH"`.
- **NEUTRAL bias is always a passthrough** — the filter never blocks during warm-up,
  during chop, or when fractals are still being built. Same rule as the existing WTx
  swing-bias path (null bias = passthrough).

Bias resolution: configurable via `riskdesk.wtxrsi.bias-source`:

- **`FRACTAL_HH_HL`** (default) — derived from the two most-recent confirmed
  Williams fractals on each side (HH+HL → BULLISH, LH+LL → BEARISH, mixed →
  NEUTRAL). Uses the same `fractalLeftRight` / `fractalMaxLookback` as the SL,
  so one config knob defines "what is a swing". Self-contained.
- **`SMC_ENGINE`** — reuses `IndicatorService.computeSnapshot().swingBias()`,
  the same source the WTx swing-bias path consumes. Richer (BOS / CHoCH /
  liquidity sweeps), consistent cross-strategy. Falls back to FRACTAL_HH_HL
  during warm-up or if the SMC bean is unavailable.

Switchable via config or per-request override on the backtest endpoint
(`"biasSource": "SMC_ENGINE"`).

The current bias is persisted on the state row (`lastSwingBias`) and pushed on
`/topic/wtxrsi-state` after every closed candle so the UI can show a
BULLISH / BEARISH / NEUTRAL badge regardless of the toggle position.

WebSocket topics (single fanout per concern — payload carries
`instrument` + `timeframe`; clients route by those fields, no wildcard
subscription required):
- `/topic/wtxrsi-signals` — each fired signal (OPEN/CLOSE/NONE)
- `/topic/wtxrsi-state` — state mutations

## Status / Next slices

- ✅ Domain logic, backtest engine, REST endpoint
- ✅ Live executor (orchestrator + JPA state/history + IBKR bridge + REST toggle)
- ⏳ Frontend panel to drive backtest / display state and signals.
- ⏳ Native bracket-order helper on `IbGatewayNativeClient` (entry + protective
  stop in a single atomic submit).
- ⏳ ATR-based trailing stop (config hooks are already in `WtxRsiConfig`).
