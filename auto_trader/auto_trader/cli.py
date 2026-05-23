"""CLI entry points.

Data provenance: candles are loaded **exclusively** from the internal PostgreSQL
``candles`` table (the one the Java backend writes from IBKR). The repo-level
``AGENTS.md`` forbids CSV imports / scraping / simulated fallback feeds.
"""

from __future__ import annotations

import logging
import sys
from datetime import datetime, timezone
from pathlib import Path

import click
import pandas as pd

from auto_trader.backtest import (
    BacktestEngine,
    compute_metrics,
    load_ohlcv_pg,
    resample_ohlcv,
)
from auto_trader.config import load_strategy_config
from auto_trader.live import IbkrClient, LiveExecutor


def _setup_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s :: %(message)s",
    )


def _parse_iso(ts: str) -> datetime:
    """Parse an ISO-8601 timestamp, defaulting naive inputs to UTC."""
    parsed = datetime.fromisoformat(ts.replace("Z", "+00:00"))
    return parsed.astimezone(timezone.utc) if parsed.tzinfo else parsed.replace(tzinfo=timezone.utc)


@click.group()
@click.option("-v", "--verbose", is_flag=True)
@click.pass_context
def main(ctx: click.Context, verbose: bool) -> None:
    """auto-trader — MNQ WaveTrend/RSI trading system."""
    ctx.ensure_object(dict)
    _setup_logging(verbose)


@main.command()
@click.option("--config", "config_path", type=click.Path(exists=True, dir_okay=False), required=True)
@click.option("--instrument", default="MNQ", show_default=True,
              help="Instrument symbol — matches a row in the candles table.")
@click.option("--source-timeframe", default="1m", show_default=True,
              help="Native timeframe in the candles table to read.")
@click.option("--from", "from_ts", required=True,
              help="Start of the backtest window (ISO 8601, e.g. 2025-01-01T00:00:00Z).")
@click.option("--to", "to_ts", required=True,
              help="End of the backtest window (ISO 8601).")
@click.option(
    "--resample",
    "resample_rule",
    type=str,
    default=None,
    help="Optional pandas resample rule (e.g. '5min') applied to the loaded bars.",
)
@click.option(
    "--out",
    "out_dir",
    type=click.Path(file_okay=False),
    default="results",
    show_default=True,
)
def backtest(
    config_path: str,
    instrument: str,
    source_timeframe: str,
    from_ts: str,
    to_ts: str,
    resample_rule: str | None,
    out_dir: str,
) -> None:
    """Run the backtest on candles loaded from the internal PostgreSQL ``candles`` table."""
    cfg = load_strategy_config(config_path)
    bars = load_ohlcv_pg(
        instrument=instrument,
        timeframe=source_timeframe,
        from_ts=_parse_iso(from_ts),
        to_ts=_parse_iso(to_ts),
    )
    if bars.empty:
        raise click.ClickException(
            f"No candles for {instrument} {source_timeframe} in [{from_ts}, {to_ts}]. "
            "Verify the Java backend has backfilled this window."
        )
    if resample_rule:
        bars = resample_ohlcv(bars, resample_rule)
    click.echo(
        f"Loaded {len(bars)} bars ({bars.index.min()} → {bars.index.max()}) on TF {cfg.timeframe}"
    )
    engine = BacktestEngine(cfg=cfg)
    result = engine.run(bars)
    metrics = compute_metrics(result.trades, result.equity_curve)

    out = Path(out_dir)
    out.mkdir(parents=True, exist_ok=True)
    result.trades.to_csv(out / "trades.csv", index=False)
    result.equity_curve.to_csv(out / "equity_curve.csv")
    pd.Series(metrics.to_dict()).to_csv(out / "metrics.csv")

    click.echo("\n=== Metrics ===")
    for k, v in metrics.to_dict().items():
        if isinstance(v, float):
            click.echo(f"  {k:24s} {v:>14.4f}")
        else:
            click.echo(f"  {k:24s} {v:>14}")
    click.echo(f"\nTrades / equity / metrics written to {out.resolve()}")


@main.command()
@click.option("--config", "config_path", type=click.Path(exists=True, dir_okay=False), required=True)
@click.option("--instrument", default="MNQ", show_default=True)
@click.option("--warmup-from", "warmup_from",
              help="Optional ISO 8601 timestamp — pull indicator warm-up bars from the candles table.")
@click.option("--poll", "poll_seconds", type=float, default=5.0, show_default=True)
def live(
    config_path: str,
    instrument: str,
    warmup_from: str | None,
    poll_seconds: float,
) -> None:
    """Start the live execution loop against IBKR Gateway."""
    cfg = load_strategy_config(config_path)
    if warmup_from:
        warmup = load_ohlcv_pg(
            instrument=instrument,
            timeframe=cfg.timeframe,
            from_ts=_parse_iso(warmup_from),
            to_ts=datetime.now(timezone.utc),
        )
    else:
        warmup = pd.DataFrame(columns=["open", "high", "low", "close", "volume"])
    executor = LiveExecutor(cfg=cfg, client=IbkrClient())
    try:
        executor.run(warmup, poll_seconds=poll_seconds)
    except KeyboardInterrupt:
        click.echo("Interrupted.")
        sys.exit(0)


if __name__ == "__main__":
    main()
