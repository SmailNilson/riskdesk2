import pandas as pd

from auto_trader.backtest import BacktestEngine, compute_metrics


def test_backtest_runs_and_produces_trades(bars, cfg):
    engine = BacktestEngine(cfg=cfg)
    result = engine.run(bars)
    assert isinstance(result.trades, pd.DataFrame)
    assert isinstance(result.equity_curve, pd.Series)
    assert len(result.equity_curve) == len(bars)
    if not result.trades.empty:
        for col in [
            "side", "entry_time", "entry_price", "exit_time", "exit_price",
            "contracts", "stop_loss_final", "outcome", "pnl_points", "pnl_usd",
        ]:
            assert col in result.trades.columns
        assert (result.trades["contracts"] >= 1).all()


def test_metrics_complete(bars, cfg):
    engine = BacktestEngine(cfg=cfg)
    result = engine.run(bars)
    m = compute_metrics(result.trades, result.equity_curve)
    d = m.to_dict()
    expected = {
        "trades", "wins", "losses", "win_rate", "avg_win_usd", "avg_loss_usd",
        "profit_factor", "expectancy_usd", "total_pnl_usd",
        "max_drawdown_usd", "max_drawdown_pct", "long_trades", "short_trades",
    }
    assert expected.issubset(d.keys())
    assert m.long_trades + m.short_trades == m.trades


def test_no_concurrent_same_side(bars, cfg):
    engine = BacktestEngine(cfg=cfg)
    result = engine.run(bars)
    if result.trades.empty:
        return
    for _side, group in result.trades.groupby("side"):
        ordered = group.sort_values("entry_time")
        # Each entry must be after the previous trade on that side has exited.
        for prev_exit, next_entry in zip(
            ordered["exit_time"].iloc[:-1], ordered["entry_time"].iloc[1:], strict=True
        ):
            assert next_entry >= prev_exit


def test_sl_wins_when_both_touched_same_bar(cfg):
    """Pessimistic intra-bar rule: SL must win if both SL and TP are hit."""
    from auto_trader.backtest.engine import BacktestEngine
    from auto_trader.strategy.signals import SignalSide
    from auto_trader.strategy.state import OpenPosition

    engine = BacktestEngine(cfg=cfg)
    pos = OpenPosition(
        side=SignalSide.LONG,
        entry_time=pd.Timestamp("2025-01-02", tz="UTC"),
        entry_price=100.0, contracts=1,
        stop_loss=98.0, take_profit=102.0,
        best_favorable_price=100.0, confirmed=False,
    )
    bar = pd.Series({"open": 100.0, "high": 103.0, "low": 97.0, "close": 99.0})
    exit_info = engine._check_exit(pos, bar)
    assert exit_info is not None
    exit_price, outcome = exit_info
    assert exit_price == 98.0
    assert outcome.value == "SL_HIT"
