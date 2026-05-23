import pandas as pd

from auto_trader.strategy.signals import SignalSide, detect_signals


def test_signals_emitted_on_both_sides(bars, cfg):
    signals = detect_signals(bars, cfg)
    assert len(signals) > 0
    sides = {s.side for s in signals}
    assert SignalSide.LONG in sides
    assert SignalSide.SHORT in sides


def test_signals_are_causal_and_in_order(bars, cfg):
    signals = detect_signals(bars, cfg)
    timestamps = [s.timestamp for s in signals]
    assert timestamps == sorted(timestamps)
    for s in signals:
        assert isinstance(s.timestamp, pd.Timestamp)
        assert s.bar_index >= 0
        assert s.bar_index < len(bars)


def test_strict_zone_constrains_long_to_oversold(bars, cfg):
    signals = detect_signals(bars, cfg)
    for s in signals:
        if s.side is SignalSide.LONG:
            assert s.wt1 <= cfg.wavetrend.oversold
        else:
            assert s.wt1 >= cfg.wavetrend.overbought


def test_confirmed_flag_matches_chaikin_sign(bars, cfg):
    signals = detect_signals(bars, cfg)
    for s in signals:
        if not s.confirmed:
            continue
        if s.side is SignalSide.LONG:
            assert s.confirmation_value is not None
            assert s.confirmation_value > 0
        else:
            assert s.confirmation_value is not None
            assert s.confirmation_value < 0


def test_lookback_zero_requires_same_bar(bars, cfg):
    cfg0 = type(cfg)(
        instrument=cfg.instrument,
        timeframe=cfg.timeframe,
        wavetrend=cfg.wavetrend,
        rsi=cfg.rsi,
        confirmation=cfg.confirmation,
        signals=type(cfg.signals)(lookback_bars=0, wt_strict_zone=True),
        risk=cfg.risk,
        execution=cfg.execution,
    )
    signals = detect_signals(bars, cfg0)
    # With lookback=0, both crosses must occur on the same bar — generally rarer.
    assert len(signals) <= len(detect_signals(bars, cfg))
