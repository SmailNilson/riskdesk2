"""Performance metrics on a closed-trade ledger + equity curve."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np
import pandas as pd


@dataclass(frozen=True)
class BacktestMetrics:
    trades: int
    wins: int
    losses: int
    win_rate: float
    avg_win_usd: float
    avg_loss_usd: float
    profit_factor: float
    expectancy_usd: float
    total_pnl_usd: float
    max_drawdown_usd: float
    max_drawdown_pct: float
    long_trades: int
    short_trades: int

    def to_dict(self) -> dict:
        return {k: getattr(self, k) for k in self.__dataclass_fields__}


def _max_drawdown(equity: pd.Series) -> tuple[float, float]:
    if equity.empty:
        return 0.0, 0.0
    running_peak = equity.cummax()
    drawdown = equity - running_peak
    abs_dd = float(drawdown.min())
    peak = float(running_peak.max())
    # The equity curve starts at 0 (we trade cash on margin, not a principal),
    # so a positive peak is required for a percentage to be meaningful.
    pct_dd = (abs_dd / peak) if peak > 0 else 0.0
    return abs_dd, pct_dd


def compute_metrics(trades: pd.DataFrame, equity_curve: pd.Series) -> BacktestMetrics:
    if trades.empty:
        return BacktestMetrics(
            trades=0, wins=0, losses=0, win_rate=0.0,
            avg_win_usd=0.0, avg_loss_usd=0.0, profit_factor=0.0,
            expectancy_usd=0.0, total_pnl_usd=0.0,
            max_drawdown_usd=0.0, max_drawdown_pct=0.0,
            long_trades=0, short_trades=0,
        )
    wins = trades[trades["pnl_usd"] > 0]
    losses = trades[trades["pnl_usd"] < 0]
    gross_profit = float(wins["pnl_usd"].sum())
    gross_loss = float(losses["pnl_usd"].sum())
    pf = (gross_profit / abs(gross_loss)) if gross_loss < 0 else (np.inf if gross_profit > 0 else 0.0)
    win_rate = len(wins) / len(trades) if len(trades) else 0.0
    avg_win = float(wins["pnl_usd"].mean()) if not wins.empty else 0.0
    avg_loss = float(losses["pnl_usd"].mean()) if not losses.empty else 0.0
    expectancy = float(trades["pnl_usd"].mean())
    total = float(trades["pnl_usd"].sum())
    abs_dd, pct_dd = _max_drawdown(equity_curve)
    return BacktestMetrics(
        trades=len(trades),
        wins=len(wins),
        losses=len(losses),
        win_rate=win_rate,
        avg_win_usd=avg_win,
        avg_loss_usd=avg_loss,
        profit_factor=pf,
        expectancy_usd=expectancy,
        total_pnl_usd=total,
        max_drawdown_usd=abs_dd,
        max_drawdown_pct=pct_dd,
        long_trades=int((trades["side"] == "LONG").sum()),
        short_trades=int((trades["side"] == "SHORT").sum()),
    )
