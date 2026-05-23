"""OHLCV loading + timeframe resampling."""

from __future__ import annotations

from pathlib import Path

import pandas as pd

REQUIRED_COLS = ["open", "high", "low", "close", "volume"]


def load_ohlcv_csv(path: str | Path, *, timestamp_col: str = "timestamp") -> pd.DataFrame:
    """Load a CSV with columns timestamp,open,high,low,close,volume.

    Timestamps are parsed as UTC. Returned DataFrame is indexed on a
    UTC DatetimeIndex and sorted ascending.
    """
    df = pd.read_csv(path)
    if timestamp_col not in df.columns:
        raise ValueError(f"CSV missing '{timestamp_col}' column; got {list(df.columns)}")
    df[timestamp_col] = pd.to_datetime(df[timestamp_col], utc=True)
    df = df.set_index(timestamp_col).sort_index()
    missing = [c for c in REQUIRED_COLS if c not in df.columns]
    if missing:
        raise ValueError(f"CSV missing columns: {missing}")
    return df[REQUIRED_COLS].astype(
        {"open": "float64", "high": "float64", "low": "float64", "close": "float64", "volume": "float64"}
    )


def resample_ohlcv(df: pd.DataFrame, rule: str) -> pd.DataFrame:
    """Resample 1-min (or any finer) OHLCV bars to a coarser timeframe."""
    agg = {
        "open": "first",
        "high": "max",
        "low": "min",
        "close": "last",
        "volume": "sum",
    }
    out = df.resample(rule, label="left", closed="left").agg(agg).dropna(subset=["open"])
    return out
