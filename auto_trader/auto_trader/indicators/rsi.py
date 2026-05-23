"""RSI (Wilder) + SMA-of-RSI.

Wilder smoothing is the standard TradingView/IBKR convention:
    avg_gain = (prev_avg_gain * (n-1) + gain) / n
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class RsiResult:
    rsi: pd.Series
    sma: pd.Series

    def cross_up(self) -> pd.Series:
        prev = self.rsi.shift(1) - self.sma.shift(1)
        cur = self.rsi - self.sma
        return (prev <= 0) & (cur > 0)

    def cross_down(self) -> pd.Series:
        prev = self.rsi.shift(1) - self.sma.shift(1)
        cur = self.rsi - self.sma
        return (prev >= 0) & (cur < 0)


def rsi(close: pd.Series, length: int = 14) -> pd.Series:
    """Wilder's RSI."""
    delta = close.diff()
    gain = delta.clip(lower=0.0)
    loss = (-delta).clip(lower=0.0)

    # Wilder smoothing == EMA with alpha = 1/length
    avg_gain = gain.ewm(alpha=1.0 / length, adjust=False, min_periods=length).mean()
    avg_loss = loss.ewm(alpha=1.0 / length, adjust=False, min_periods=length).mean()

    rs = avg_gain / avg_loss.replace(0, np.nan)
    out = 100.0 - (100.0 / (1.0 + rs))
    # When avg_loss == 0, RSI is by convention 100 (pure gains).
    out = out.where(avg_loss != 0, 100.0)
    return out.rename("rsi")


def rsi_with_sma(close: pd.Series, length: int = 14, sma_length: int = 14) -> RsiResult:
    r = rsi(close, length)
    s = r.rolling(window=sma_length, min_periods=sma_length).mean().rename("rsi_sma")
    return RsiResult(rsi=r, sma=s)
