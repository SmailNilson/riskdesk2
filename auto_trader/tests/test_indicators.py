import numpy as np
import pandas as pd

from auto_trader.indicators import chaikin_oscillator, rsi_with_sma, wavetrend
from auto_trader.indicators.rsi import rsi


def test_rsi_pure_gains_is_100():
    closes = pd.Series(np.arange(1, 50, dtype=float))
    r = rsi(closes, length=14)
    last = r.dropna().iloc[-1]
    assert last == 100.0


def test_rsi_pure_losses_is_0():
    closes = pd.Series(np.arange(50, 1, -1, dtype=float))
    r = rsi(closes, length=14)
    last = r.dropna().iloc[-1]
    assert last == 0.0


def test_rsi_cross_up_detected(bars):
    res = rsi_with_sma(bars["close"], 14, 14)
    crosses = res.cross_up().fillna(False)
    # Synthetic oscillating series must produce at least one cross both directions.
    assert crosses.any()
    assert res.cross_down().fillna(False).any()


def test_wavetrend_shape_and_crosses(bars):
    wt = wavetrend(bars["high"], bars["low"], bars["close"], 10, 21)
    assert len(wt.wt1) == len(bars)
    assert len(wt.wt2) == len(bars)
    assert wt.wt1.dropna().shape[0] > 0
    assert wt.wt2.dropna().shape[0] > 0
    # Cross series are bar-aligned booleans.
    assert wt.cross_up().dtype == bool
    assert wt.cross_down().dtype == bool
    assert wt.cross_up().any()
    assert wt.cross_down().any()


def test_wavetrend_visits_both_zones(bars):
    wt = wavetrend(bars["high"], bars["low"], bars["close"], 10, 21).wt1.dropna()
    # The synthetic sine fixture is engineered to reach both zones.
    assert wt.min() < -53
    assert wt.max() > 53


def test_chaikin_oscillator_runs(bars):
    osc = chaikin_oscillator(
        bars["high"], bars["low"], bars["close"], bars["volume"], fast=3, slow=10
    )
    assert len(osc) == len(bars)
    assert osc.dropna().shape[0] > 0
    # Oscillates around zero on realistic data.
    assert osc.dropna().min() < 0
    assert osc.dropna().max() > 0
