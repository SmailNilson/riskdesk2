"""WaveTrend Oscillator (LazyBear).

Reference: https://www.tradingview.com/script/2KE8wTuF-Indicator-WaveTrend-Oscillator-WT/

    ap  = (high + low + close) / 3
    esa = EMA(ap, channel_length)
    d   = EMA(|ap - esa|, channel_length)
    ci  = (ap - esa) / (0.015 * d)
    tci = EMA(ci, average_length)        # WT1
    wt2 = SMA(tci, 4)                    # WT2
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class WaveTrendResult:
    wt1: pd.Series
    wt2: pd.Series

    def cross_up(self) -> pd.Series:
        """Bar-aligned boolean: WT1 crosses above WT2 on this bar."""
        prev_diff = (self.wt1.shift(1) - self.wt2.shift(1))
        cur_diff = self.wt1 - self.wt2
        return (prev_diff <= 0) & (cur_diff > 0)

    def cross_down(self) -> pd.Series:
        prev_diff = (self.wt1.shift(1) - self.wt2.shift(1))
        cur_diff = self.wt1 - self.wt2
        return (prev_diff >= 0) & (cur_diff < 0)


def _ema(series: pd.Series, length: int) -> pd.Series:
    return series.ewm(span=length, adjust=False, min_periods=length).mean()


def wavetrend(
    high: pd.Series,
    low: pd.Series,
    close: pd.Series,
    channel_length: int = 10,
    average_length: int = 21,
) -> WaveTrendResult:
    """Return WT1 and WT2 series aligned to the input index."""
    ap = (high + low + close) / 3.0
    esa = _ema(ap, channel_length)
    d = _ema((ap - esa).abs(), channel_length)
    # Guard division by zero on flat data.
    d_safe = d.replace(0, np.nan)
    ci = (ap - esa) / (0.015 * d_safe)
    wt1 = _ema(ci, average_length)
    wt2 = wt1.rolling(window=4, min_periods=4).mean()
    return WaveTrendResult(wt1=wt1.rename("wt1"), wt2=wt2.rename("wt2"))
