"""Shared fixtures. Synthetic OHLCV bars so tests stay deterministic."""

from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

from auto_trader.config import (
    ConfirmationConfig,
    ExecutionConfig,
    InstrumentConfig,
    RiskConfig,
    RsiConfig,
    SignalsConfig,
    StrategyConfig,
    WaveTrendConfig,
)


@pytest.fixture
def instrument_mnq() -> InstrumentConfig:
    return InstrumentConfig(
        symbol="MNQ", exchange="CME", currency="USD",
        tick_size=0.25, tick_value=0.50,
    )


@pytest.fixture
def cfg(instrument_mnq) -> StrategyConfig:
    return StrategyConfig(
        instrument=instrument_mnq,
        timeframe="5m",
        wavetrend=WaveTrendConfig(),
        rsi=RsiConfig(),
        confirmation=ConfirmationConfig(enabled=True, kind="chaikin"),
        signals=SignalsConfig(lookback_bars=3, wt_strict_zone=True),
        risk=RiskConfig(
            base_contracts=1, confirmed_multiplier=2,
            swing_lookback=10, swing_buffer_ticks=2,
            take_profit_r_multiple=2.0,
        ),
        execution=ExecutionConfig(entry_mode="next_bar_open", max_concurrent_per_direction=1),
    )


def _synthetic_bars(seed: int, n: int = 500, base: float = 17000.0, freq: str = "5min") -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    # Stitch together a long uptrend, a deep selloff, then a bounce so WT visits
    # both OB and OS zones and RSI crosses its SMA in both directions.
    t = np.linspace(0, 6 * np.pi, n)
    base_curve = base + 80 * np.sin(t) + np.linspace(0, 40, n)
    noise = rng.normal(0, 4, size=n)
    closes = base_curve + noise
    # Build OHLC from a stochastic intra-bar range so SL/TP tests have realistic wicks.
    wick = rng.uniform(2, 8, size=n)
    body_dir = rng.choice([-1, 1], size=n)
    opens = np.concatenate([[closes[0] - body_dir[0] * 2.0], closes[:-1]])
    highs = np.maximum(opens, closes) + wick
    lows = np.minimum(opens, closes) - wick
    volumes = rng.integers(500, 5000, size=n).astype(float)
    idx = pd.date_range("2025-01-02 14:30", periods=n, freq=freq, tz="UTC")
    return pd.DataFrame(
        {
            "open": opens,
            "high": highs,
            "low": lows,
            "close": closes,
            "volume": volumes,
        },
        index=idx,
    )


@pytest.fixture
def bars() -> pd.DataFrame:
    return _synthetic_bars(seed=42)


@pytest.fixture
def short_bars() -> pd.DataFrame:
    return _synthetic_bars(seed=42, n=120)
