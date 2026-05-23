"""Signal detection: WT_X cross in OB/OS zone synchronised with RSI/SMA cross.

Sync rule (LONG, symmetric for SHORT):
    bar i is a candidate WT bullish cross if cross_up[i] AND wt1[i] <= oversold
    bar j is a candidate RSI bullish cross if rsi crosses above sma
    they are "synchronised" if |i - j| <= lookback_bars

When confirmed, the signal is emitted on max(i, j) — the bar where the
second condition lands. This keeps the strategy strictly causal.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import StrEnum

import pandas as pd

from auto_trader.config import StrategyConfig
from auto_trader.indicators import chaikin_oscillator, rsi_with_sma, wavetrend


class SignalSide(StrEnum):
    LONG = "LONG"
    SHORT = "SHORT"


@dataclass(frozen=True)
class Signal:
    bar_index: int
    timestamp: pd.Timestamp
    side: SignalSide
    confirmed: bool
    wt1: float
    wt2: float
    rsi: float
    rsi_sma: float
    confirmation_value: float | None
    close: float


def _strict_zone_mask(wt1: pd.Series, threshold: float, *, below: bool) -> pd.Series:
    return (wt1 <= threshold) if below else (wt1 >= threshold)


def _sync_indices(
    primary: pd.Series,
    secondary: pd.Series,
    lookback: int,
) -> pd.Series:
    """For each bar where primary is True, return True if any bar in
    [i-lookback, i+lookback] has secondary True. Bidirectional ± window.
    """
    # Forward window: secondary fired in the last (lookback+1) bars including i
    fwd = secondary.rolling(window=lookback + 1, min_periods=1).max().fillna(0).astype(bool)
    # Backward window: secondary will fire within the next lookback bars
    bwd_raw = secondary.iloc[::-1].rolling(window=lookback + 1, min_periods=1).max()
    bwd = bwd_raw.iloc[::-1].fillna(0).astype(bool)
    bwd.index = secondary.index
    in_window = fwd | bwd
    return primary & in_window


def detect_signals(
    bars: pd.DataFrame,
    cfg: StrategyConfig,
) -> list[Signal]:
    """Run indicators over `bars` and return ordered list of Signal objects.

    `bars` must have columns: open, high, low, close, volume; index is
    a pandas DatetimeIndex sorted ascending.
    """
    required = {"open", "high", "low", "close", "volume"}
    missing = required - set(bars.columns)
    if missing:
        raise ValueError(f"bars missing required columns: {sorted(missing)}")
    if not bars.index.is_monotonic_increasing:
        raise ValueError("bars index must be monotonically increasing")

    wt = wavetrend(
        bars["high"], bars["low"], bars["close"],
        channel_length=cfg.wavetrend.channel_length,
        average_length=cfg.wavetrend.average_length,
    )
    rs = rsi_with_sma(
        bars["close"],
        length=cfg.rsi.length,
        sma_length=cfg.rsi.sma_length,
    )

    confirm: pd.Series | None = None
    if cfg.confirmation.enabled and cfg.confirmation.kind == "chaikin":
        confirm = chaikin_oscillator(
            bars["high"], bars["low"], bars["close"], bars["volume"],
            fast=cfg.confirmation.chaikin_fast,
            slow=cfg.confirmation.chaikin_slow,
        )

    wt_up = wt.cross_up().fillna(False)
    wt_down = wt.cross_down().fillna(False)
    rsi_up = rs.cross_up().fillna(False)
    rsi_down = rs.cross_down().fillna(False)

    if cfg.signals.wt_strict_zone:
        wt_up = wt_up & _strict_zone_mask(wt.wt1, cfg.wavetrend.oversold, below=True)
        wt_down = wt_down & _strict_zone_mask(wt.wt1, cfg.wavetrend.overbought, below=False)

    lookback = cfg.signals.lookback_bars
    long_trigger = _sync_indices(wt_up, rsi_up, lookback)
    short_trigger = _sync_indices(wt_down, rsi_down, lookback)

    signals: list[Signal] = []
    for side, trigger in ((SignalSide.LONG, long_trigger), (SignalSide.SHORT, short_trigger)):
        for ts in trigger.index[trigger]:
            i = bars.index.get_loc(ts)
            confirm_val = float(confirm.iloc[i]) if confirm is not None else None
            confirmed = _is_confirmed(side, confirm_val, cfg)
            signals.append(
                Signal(
                    bar_index=i,
                    timestamp=ts,
                    side=side,
                    confirmed=confirmed,
                    wt1=float(wt.wt1.iloc[i]),
                    wt2=float(wt.wt2.iloc[i]),
                    rsi=float(rs.rsi.iloc[i]),
                    rsi_sma=float(rs.sma.iloc[i]),
                    confirmation_value=confirm_val,
                    close=float(bars["close"].iloc[i]),
                )
            )

    signals.sort(key=lambda s: s.bar_index)
    return signals


def _is_confirmed(side: SignalSide, value: float | None, cfg: StrategyConfig) -> bool:
    if not cfg.confirmation.enabled or value is None:
        return False
    if side is SignalSide.LONG:
        return value > 0
    return value < 0
