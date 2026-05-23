from pathlib import Path

from auto_trader.config import load_strategy_config

REPO = Path(__file__).resolve().parent.parent


def test_load_5m_config():
    cfg = load_strategy_config(REPO / "config" / "strategy_5m.yaml")
    assert cfg.timeframe == "5m"
    assert cfg.bar_minutes == 5
    assert cfg.wavetrend.overbought == 53
    assert cfg.wavetrend.oversold == -53
    assert cfg.risk.swing_lookback == 10
    assert cfg.signals.lookback_bars == 3


def test_load_10m_config():
    cfg = load_strategy_config(REPO / "config" / "strategy_10m.yaml")
    assert cfg.timeframe == "10m"
    assert cfg.bar_minutes == 10
    assert cfg.signals.lookback_bars == 2
