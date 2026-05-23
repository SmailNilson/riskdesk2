import pandas as pd

from auto_trader.strategy.risk import build_risk_plan, compute_atr
from auto_trader.strategy.signals import Signal, SignalSide, detect_signals


def test_build_risk_plan_long_below_swing(bars, cfg):
    signals = [s for s in detect_signals(bars, cfg) if s.side is SignalSide.LONG]
    assert signals, "expected at least one long signal in fixture"
    s = signals[0]
    entry = float(bars["close"].iloc[s.bar_index])
    plan = build_risk_plan(s, bars, entry_price=entry, cfg=cfg)
    assert plan is not None
    assert plan.stop_loss < entry
    expected_swing = float(bars["low"].iloc[max(0, s.bar_index - cfg.risk.swing_lookback + 1) : s.bar_index + 1].min())
    assert plan.swing_reference == expected_swing


def test_build_risk_plan_short_above_swing(bars, cfg):
    signals = [s for s in detect_signals(bars, cfg) if s.side is SignalSide.SHORT]
    assert signals
    s = signals[0]
    entry = float(bars["close"].iloc[s.bar_index])
    plan = build_risk_plan(s, bars, entry_price=entry, cfg=cfg)
    assert plan is not None
    assert plan.stop_loss > entry


def test_confirmed_signal_doubles_contracts(bars, cfg):
    signals = detect_signals(bars, cfg)
    confirmed = next((s for s in signals if s.confirmed), None)
    unconfirmed = next((s for s in signals if not s.confirmed), None)
    if confirmed is None or unconfirmed is None:
        return
    entry_c = float(bars["close"].iloc[confirmed.bar_index])
    entry_u = float(bars["close"].iloc[unconfirmed.bar_index])
    plan_c = build_risk_plan(confirmed, bars, entry_price=entry_c, cfg=cfg)
    plan_u = build_risk_plan(unconfirmed, bars, entry_price=entry_u, cfg=cfg)
    if plan_c is None or plan_u is None:
        return
    assert plan_c.contracts == cfg.risk.base_contracts * cfg.risk.confirmed_multiplier
    assert plan_u.contracts == cfg.risk.base_contracts


def test_take_profit_respects_r_multiple(bars, cfg):
    signals = detect_signals(bars, cfg)
    s = next(iter(signals), None)
    assert s is not None
    entry = float(bars["close"].iloc[s.bar_index])
    plan = build_risk_plan(s, bars, entry_price=entry, cfg=cfg)
    assert plan is not None
    assert plan.take_profit is not None
    r = cfg.risk.take_profit_r_multiple
    if s.side is SignalSide.LONG:
        expected = plan.entry_price + r * plan.risk_per_contract
    else:
        expected = plan.entry_price - r * plan.risk_per_contract
    assert abs(plan.take_profit - expected) <= cfg.instrument.tick_size


def test_stop_loss_rejected_when_above_entry_long(cfg):
    idx = pd.date_range("2025-01-02", periods=5, freq="5min", tz="UTC")
    bars_bad = pd.DataFrame(
        {
            "open":  [100, 100, 100, 100, 100],
            "high":  [101, 101, 101, 101, 101],
            "low":   [99, 99, 99, 99, 99],
            "close": [100, 100, 100, 100, 100],
            "volume":[1000] * 5,
        },
        index=idx,
    )
    sig = Signal(
        bar_index=4, timestamp=idx[4], side=SignalSide.LONG, confirmed=False,
        wt1=0, wt2=0, rsi=50, rsi_sma=50, confirmation_value=None, close=100,
    )
    # Entry below the swing low → SL would be on the wrong side → must reject.
    plan = build_risk_plan(sig, bars_bad, entry_price=98.0, cfg=cfg)
    assert plan is None


def test_atr_runs(bars):
    atr = compute_atr(bars, 14)
    assert len(atr) == len(bars)
    assert atr.dropna().min() > 0
