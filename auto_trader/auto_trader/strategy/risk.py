"""Risk planning: swing-based stop loss, position sizing, optional TP/trailing.

A RiskPlan is what you need to actually fire an order:
    side, contracts, entry_price (planned), stop_loss, take_profit (optional)
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd

from auto_trader.config import StrategyConfig
from auto_trader.strategy.signals import Signal, SignalSide


@dataclass(frozen=True)
class RiskPlan:
    side: SignalSide
    contracts: int
    entry_price: float
    stop_loss: float
    take_profit: float | None
    risk_per_contract: float          # entry - SL (absolute)
    swing_reference: float            # raw swing value before buffer


def _round_to_tick(price: float, tick_size: float) -> float:
    return round(price / tick_size) * tick_size


def compute_atr(bars: pd.DataFrame, period: int) -> pd.Series:
    """Wilder ATR — used for optional trailing-stop logic."""
    high = bars["high"]
    low = bars["low"]
    close = bars["close"]
    prev_close = close.shift(1)
    tr = pd.concat(
        [
            (high - low).abs(),
            (high - prev_close).abs(),
            (low - prev_close).abs(),
        ],
        axis=1,
    ).max(axis=1)
    return tr.ewm(alpha=1.0 / period, adjust=False, min_periods=period).mean()


def build_risk_plan(
    signal: Signal,
    bars: pd.DataFrame,
    entry_price: float,
    cfg: StrategyConfig,
) -> RiskPlan | None:
    """Construct the RiskPlan for a signal. Returns None if SL is invalid
    (e.g. swing window resolves to NaN at start of series, or SL would be on
    the wrong side of entry — happens on noisy first bars).
    """
    y = cfg.risk.swing_lookback
    # Bars strictly before the entry bar.
    window = bars.iloc[max(0, signal.bar_index - y + 1) : signal.bar_index + 1]
    if len(window) == 0:
        return None

    buffer = cfg.risk.swing_buffer_ticks * cfg.instrument.tick_size

    if signal.side is SignalSide.LONG:
        swing = float(window["low"].min())
        if not np.isfinite(swing):
            return None
        stop = _round_to_tick(swing - buffer, cfg.instrument.tick_size)
        if stop >= entry_price:
            return None
        risk_per_contract = entry_price - stop
    else:
        swing = float(window["high"].max())
        if not np.isfinite(swing):
            return None
        stop = _round_to_tick(swing + buffer, cfg.instrument.tick_size)
        if stop <= entry_price:
            return None
        risk_per_contract = stop - entry_price

    contracts = cfg.risk.base_contracts * (
        cfg.risk.confirmed_multiplier if signal.confirmed else 1
    )

    take_profit: float | None = None
    if cfg.risk.take_profit_r_multiple is not None and cfg.risk.take_profit_r_multiple > 0:
        r = cfg.risk.take_profit_r_multiple
        if signal.side is SignalSide.LONG:
            tp = entry_price + r * risk_per_contract
        else:
            tp = entry_price - r * risk_per_contract
        take_profit = _round_to_tick(tp, cfg.instrument.tick_size)

    return RiskPlan(
        side=signal.side,
        contracts=contracts,
        entry_price=_round_to_tick(entry_price, cfg.instrument.tick_size),
        stop_loss=stop,
        take_profit=take_profit,
        risk_per_contract=risk_per_contract,
        swing_reference=swing,
    )


def update_trailing_stop(
    *,
    side: SignalSide,
    current_stop: float,
    best_favorable_price: float,
    atr_value: float | None,
    multiplier: float | None,
    tick_size: float,
) -> float:
    """Return the new (tightened-only) stop. Never loosens."""
    if atr_value is None or multiplier is None or not np.isfinite(atr_value):
        return current_stop
    if side is SignalSide.LONG:
        candidate = _round_to_tick(best_favorable_price - multiplier * atr_value, tick_size)
        return max(current_stop, candidate)
    candidate = _round_to_tick(best_favorable_price + multiplier * atr_value, tick_size)
    return min(current_stop, candidate)
