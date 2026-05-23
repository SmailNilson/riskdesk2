"""OHLCV loading from the internal PostgreSQL ``candles`` table + timeframe resampling.

**No CSV / external feed.** The project's `AGENTS.md` mandates a single
provenance path for market data: IBKR Gateway → PostgreSQL → internal services.
This loader reads the same ``candles`` table the Java backend persists from
the IBKR ingestion pipeline, so the Python prototype operates on bit-identical
inputs to the production engine and cannot accidentally pick up a third-party
file.
"""

from __future__ import annotations

import os
from datetime import datetime, timezone

import pandas as pd

REQUIRED_COLS = ["open", "high", "low", "close", "volume"]


def _connect():
    """Lazy import psycopg so the rest of the package stays loadable in environments
    where Postgres isn't installed (unit tests, indicator playground, etc.).
    """
    try:
        import psycopg  # noqa: WPS433
    except ImportError as exc:  # pragma: no cover - import error path
        raise RuntimeError(
            "psycopg is required to load candles from PostgreSQL. "
            "Install it with `pip install psycopg[binary]` or use the Java "
            "backtest endpoint instead."
        ) from exc

    dsn = os.environ.get(
        "RISKDESK_DB_URL",
        "postgresql://riskdesk:riskdesk@localhost:5432/riskdesk",
    )
    return psycopg.connect(dsn)


def load_ohlcv_pg(
    instrument: str,
    timeframe: str,
    *,
    from_ts: datetime,
    to_ts: datetime,
) -> pd.DataFrame:
    """Load candles for ``[from_ts, to_ts]`` from the internal ``candles`` table.

    The same table the Java backend (``CandleRepository``) writes from IBKR.
    Returned DataFrame is indexed on a UTC :class:`pandas.DatetimeIndex` sorted
    ascending — same shape the rest of the Python prototype expects.
    """
    if from_ts.tzinfo is None:
        from_ts = from_ts.replace(tzinfo=timezone.utc)
    if to_ts.tzinfo is None:
        to_ts = to_ts.replace(tzinfo=timezone.utc)

    query = """
        SELECT timestamp, open, high, low, close, volume
          FROM candles
         WHERE instrument = %s AND timeframe = %s
           AND timestamp >= %s AND timestamp <= %s
         ORDER BY timestamp ASC
    """
    with _connect() as conn, conn.cursor() as cur:
        cur.execute(query, (instrument, timeframe, from_ts, to_ts))
        rows = cur.fetchall()

    if not rows:
        return pd.DataFrame(columns=REQUIRED_COLS).set_index(
            pd.DatetimeIndex([], name="timestamp", tz="UTC")
        )

    df = pd.DataFrame(rows, columns=["timestamp", *REQUIRED_COLS])
    df["timestamp"] = pd.to_datetime(df["timestamp"], utc=True)
    df = df.set_index("timestamp").sort_index()
    return df.astype(
        {
            "open": "float64",
            "high": "float64",
            "low": "float64",
            "close": "float64",
            "volume": "float64",
        }
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
    return df.resample(rule, label="left", closed="left").agg(agg).dropna(subset=["open"])
