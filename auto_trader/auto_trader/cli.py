"""CLI entry points."""

from __future__ import annotations

import logging
import sys
from pathlib import Path

import click
import pandas as pd

from auto_trader.backtest import (
    BacktestEngine,
    compute_metrics,
    load_ohlcv_csv,
    resample_ohlcv,
)
from auto_trader.config import load_strategy_config
from auto_trader.live import IbkrClient, LiveExecutor


def _setup_logging(verbose: bool) -> None:
    logging.basicConfig(
        level=logging.DEBUG if verbose else logging.INFO,
        format="%(asctime)s %(levelname)-7s %(name)s :: %(message)s",
    )


@click.group()
@click.option("-v", "--verbose", is_flag=True)
@click.pass_context
def main(ctx: click.Context, verbose: bool) -> None:
    """auto-trader — MNQ WaveTrend/RSI trading system."""
    ctx.ensure_object(dict)
    _setup_logging(verbose)


@main.command()
@click.option("--config", "config_path", type=click.Path(exists=True, dir_okay=False), required=True)
@click.option("--data", "data_path", type=click.Path(exists=True, dir_okay=False), required=True)
@click.option(
    "--resample",
    "resample_rule",
    type=str,
    default=None,
    help="Optional pandas resample rule (e.g. '5min') applied to the input bars.",
)
@click.option(
    "--out",
    "out_dir",
    type=click.Path(file_okay=False),
    default="results",
    show_default=True,
)
def backtest(config_path: str, data_path: str, resample_rule: str | None, out_dir: str) -> None:
    """Run the backtest on CSV OHLCV data."""
    cfg = load_strategy_config(config_path)
    bars = load_ohlcv_csv(data_path)
    if resample_rule:
        bars = resample_ohlcv(bars, resample_rule)
    click.echo(f"Loaded {len(bars)} bars ({bars.index.min()} → {bars.index.max()}) on TF {cfg.timeframe}")
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
@click.option(
    "--warmup",
    "warmup_path",
    type=click.Path(exists=True, dir_okay=False),
    default=None,
    help="Optional CSV to warm up indicators before the first live tick.",
)
@click.option("--poll", "poll_seconds", type=float, default=5.0, show_default=True)
def live(config_path: str, warmup_path: str | None, poll_seconds: float) -> None:
    """Start the live execution loop against IBKR Gateway."""
    cfg = load_strategy_config(config_path)
    warmup = load_ohlcv_csv(warmup_path) if warmup_path else pd.DataFrame(
        columns=["open", "high", "low", "close", "volume"]
    )
    executor = LiveExecutor(cfg=cfg, client=IbkrClient())
    try:
        executor.run(warmup, poll_seconds=poll_seconds)
    except KeyboardInterrupt:
        click.echo("Interrupted.")
        sys.exit(0)


if __name__ == "__main__":
    main()
