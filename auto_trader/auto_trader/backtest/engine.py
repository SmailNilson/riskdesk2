"""Bar-by-bar backtest engine.

Execution model:
- Signals are emitted on the close of bar i.
- Entry fills at the OPEN of bar i+1 (entry_mode = next_bar_open).
- Stops and TPs are evaluated intra-bar. If both are touched, SL wins
  (pessimistic — matches the project's TradeSimulationService rule).
- Reverse signal while in position: close current, then open opposite
  on the same bar's open (handled on the NEXT bar to stay causal).
"""

from __future__ import annotations

from dataclasses import dataclass, field

import pandas as pd

from auto_trader.config import StrategyConfig
from auto_trader.strategy.risk import (
    RiskPlan,
    build_risk_plan,
    compute_atr,
    update_trailing_stop,
)
from auto_trader.strategy.signals import Signal, SignalSide, detect_signals
from auto_trader.strategy.state import OpenPosition, PositionState, TradeOutcome


@dataclass
class BacktestResult:
    trades: pd.DataFrame
    equity_curve: pd.Series
    signals: list[Signal]
    config: StrategyConfig
    bars: pd.DataFrame = field(repr=False)


@dataclass
class BacktestEngine:
    cfg: StrategyConfig

    def run(self, bars: pd.DataFrame) -> BacktestResult:
        signals = detect_signals(bars, self.cfg)
        signals_by_bar: dict[int, list[Signal]] = {}
        for s in signals:
            signals_by_bar.setdefault(s.bar_index, []).append(s)

        atr_series: pd.Series | None = None
        if self.cfg.risk.trailing_atr_period is not None:
            atr_series = compute_atr(bars, self.cfg.risk.trailing_atr_period)

        state = PositionState()
        # pending_plan[side] = RiskPlan to fire at next bar's open
        pending: dict[SignalSide, RiskPlan] = {}
        equity = 0.0
        equity_points: list[tuple[pd.Timestamp, float]] = []

        n = len(bars)
        for i in range(n):
            ts = bars.index[i]
            bar = bars.iloc[i]

            # 1) Open pending entries on this bar's OPEN price.
            for side, plan in list(pending.items()):
                # Re-stamp entry to the actual open of bar i.
                actual_entry = float(bar["open"])
                refreshed = self._refresh_plan_at_entry(plan, actual_entry)
                if refreshed is None:
                    pending.pop(side, None)
                    continue
                if state.has_open(side) and self.cfg.execution.max_concurrent_per_direction <= 1:
                    pending.pop(side, None)
                    continue
                state.set(side, OpenPosition(
                    side=side,
                    entry_time=ts,
                    entry_price=refreshed.entry_price,
                    contracts=refreshed.contracts,
                    stop_loss=refreshed.stop_loss,
                    take_profit=refreshed.take_profit,
                    best_favorable_price=refreshed.entry_price,
                    confirmed=False,  # already accounted for in contracts
                ))
                pending.pop(side, None)

            # 2) Update MFE + optional trailing stop, then check exits.
            for side in (SignalSide.LONG, SignalSide.SHORT):
                pos = state.get(side)
                if pos is None:
                    continue
                pos.update_best(float(bar["high"]), float(bar["low"]))
                if atr_series is not None:
                    atr_val = float(atr_series.iloc[i]) if pd.notna(atr_series.iloc[i]) else None
                    new_stop = update_trailing_stop(
                        side=pos.side,
                        current_stop=pos.stop_loss,
                        best_favorable_price=pos.best_favorable_price,
                        atr_value=atr_val,
                        multiplier=self.cfg.risk.trailing_atr_multiplier,
                        tick_size=self.cfg.instrument.tick_size,
                    )
                    pos.stop_loss = new_stop

                exit_info = self._check_exit(pos, bar)
                if exit_info is not None:
                    exit_price, outcome = exit_info
                    pnl = self._pnl(pos, exit_price)
                    equity += pnl
                    state.trades.append({
                        "side": pos.side.value,
                        "entry_time": pos.entry_time,
                        "entry_price": pos.entry_price,
                        "exit_time": ts,
                        "exit_price": exit_price,
                        "contracts": pos.contracts,
                        "stop_loss_final": pos.stop_loss,
                        "take_profit": pos.take_profit,
                        "outcome": outcome.value,
                        "pnl_points": (exit_price - pos.entry_price) * (1 if pos.side is SignalSide.LONG else -1),
                        "pnl_usd": pnl,
                    })
                    state.set(side, None)

            # 3) Mark equity at end of bar.
            equity_points.append((ts, equity))

            # 4) Convert any signals fired on this bar into pending entries
            #    that will execute at bar i+1's open.
            if i in signals_by_bar:
                for sig in signals_by_bar[i]:
                    if state.has_open(sig.side) and self.cfg.execution.max_concurrent_per_direction <= 1:
                        continue
                    # Provisional entry price = this bar's close; refreshed at fill.
                    plan = build_risk_plan(sig, bars, entry_price=float(bar["close"]), cfg=self.cfg)
                    if plan is None:
                        continue
                    pending[sig.side] = plan

        # Close any still-open position at last bar close.
        last_bar = bars.iloc[-1]
        last_ts = bars.index[-1]
        for side in (SignalSide.LONG, SignalSide.SHORT):
            pos = state.get(side)
            if pos is None:
                continue
            exit_price = float(last_bar["close"])
            pnl = self._pnl(pos, exit_price)
            equity += pnl
            state.trades.append({
                "side": pos.side.value,
                "entry_time": pos.entry_time,
                "entry_price": pos.entry_price,
                "exit_time": last_ts,
                "exit_price": exit_price,
                "contracts": pos.contracts,
                "stop_loss_final": pos.stop_loss,
                "take_profit": pos.take_profit,
                "outcome": TradeOutcome.MANUAL.value,
                "pnl_points": (exit_price - pos.entry_price) * (1 if pos.side is SignalSide.LONG else -1),
                "pnl_usd": pnl,
            })
            state.set(side, None)
        equity_points[-1] = (last_ts, equity)

        trades_df = pd.DataFrame(state.trades)
        equity_curve = pd.Series(
            data=[v for _, v in equity_points],
            index=pd.DatetimeIndex([t for t, _ in equity_points], name="timestamp"),
            name="equity_usd",
        )
        return BacktestResult(
            trades=trades_df,
            equity_curve=equity_curve,
            signals=signals,
            config=self.cfg,
            bars=bars,
        )

    def _check_exit(
        self, pos: OpenPosition, bar: pd.Series
    ) -> tuple[float, TradeOutcome] | None:
        high = float(bar["high"])
        low = float(bar["low"])
        if pos.side is SignalSide.LONG:
            sl_hit = low <= pos.stop_loss
            tp_hit = pos.take_profit is not None and high >= pos.take_profit
            if sl_hit:
                return (pos.stop_loss, TradeOutcome.SL_HIT)
            if tp_hit:
                return (pos.take_profit, TradeOutcome.TP_HIT)
        else:
            sl_hit = high >= pos.stop_loss
            tp_hit = pos.take_profit is not None and low <= pos.take_profit
            if sl_hit:
                return (pos.stop_loss, TradeOutcome.SL_HIT)
            if tp_hit:
                return (pos.take_profit, TradeOutcome.TP_HIT)
        return None

    def _pnl(self, pos: OpenPosition, exit_price: float) -> float:
        direction = 1 if pos.side is SignalSide.LONG else -1
        points = (exit_price - pos.entry_price) * direction
        ticks = points / self.cfg.instrument.tick_size
        return ticks * self.cfg.instrument.tick_value * pos.contracts

    def _refresh_plan_at_entry(self, plan: RiskPlan, actual_entry: float) -> RiskPlan | None:
        """Recompute risk against the actual fill price; preserve raw swing."""
        if plan.side is SignalSide.LONG:
            risk = actual_entry - plan.stop_loss
        else:
            risk = plan.stop_loss - actual_entry
        if risk <= 0:
            return None
        tp = plan.take_profit
        if tp is not None and self.cfg.risk.take_profit_r_multiple is not None:
            r = self.cfg.risk.take_profit_r_multiple
            tp = (
                actual_entry + r * risk
                if plan.side is SignalSide.LONG
                else actual_entry - r * risk
            )
            tp = round(tp / self.cfg.instrument.tick_size) * self.cfg.instrument.tick_size
        return RiskPlan(
            side=plan.side,
            contracts=plan.contracts,
            entry_price=actual_entry,
            stop_loss=plan.stop_loss,
            take_profit=tp,
            risk_per_contract=risk,
            swing_reference=plan.swing_reference,
        )
