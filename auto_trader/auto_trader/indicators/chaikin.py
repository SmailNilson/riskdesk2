"""Chaikin Oscillator = EMA(ADL, fast) - EMA(ADL, slow)

ADL (Accumulation/Distribution Line) cumulates Money Flow Volume:
    mfm = ((close - low) - (high - close)) / (high - low)
    mfv = mfm * volume
    adl = cumsum(mfv)
"""

from __future__ import annotations

import numpy as np
import pandas as pd


def _ema(series: pd.Series, length: int) -> pd.Series:
    return series.ewm(span=length, adjust=False, min_periods=length).mean()


def adl(high: pd.Series, low: pd.Series, close: pd.Series, volume: pd.Series) -> pd.Series:
    rng = (high - low).replace(0, np.nan)
    mfm = ((close - low) - (high - close)) / rng
    mfm = mfm.fillna(0.0)
    mfv = mfm * volume
    return mfv.cumsum().rename("adl")


def chaikin_oscillator(
    high: pd.Series,
    low: pd.Series,
    close: pd.Series,
    volume: pd.Series,
    fast: int = 3,
    slow: int = 10,
) -> pd.Series:
    line = adl(high, low, close, volume)
    return (_ema(line, fast) - _ema(line, slow)).rename("chaikin_osc")
