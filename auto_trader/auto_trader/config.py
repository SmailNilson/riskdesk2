"""Strategy configuration loader. YAML → typed dataclasses.

Single source of truth for tunable parameters. Anything the brief calls "a
variable to optimise" lives here and only here.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Literal

import yaml

ConfirmationKind = Literal["chaikin", "delta", "none"]
EntryMode = Literal["next_bar_open", "signal_close"]


@dataclass(frozen=True)
class InstrumentConfig:
    symbol: str
    exchange: str
    currency: str
    tick_size: float
    tick_value: float


@dataclass(frozen=True)
class WaveTrendConfig:
    channel_length: int = 10
    average_length: int = 21
    overbought: float = 53.0
    oversold: float = -53.0


@dataclass(frozen=True)
class RsiConfig:
    length: int = 14
    sma_length: int = 14
    source: str = "close"


@dataclass(frozen=True)
class ConfirmationConfig:
    enabled: bool = True
    kind: ConfirmationKind = "chaikin"
    chaikin_fast: int = 3
    chaikin_slow: int = 10


@dataclass(frozen=True)
class SignalsConfig:
    lookback_bars: int = 3
    wt_strict_zone: bool = True


@dataclass(frozen=True)
class RiskConfig:
    base_contracts: int = 1
    confirmed_multiplier: int = 2
    swing_lookback: int = 10
    swing_buffer_ticks: int = 2
    take_profit_r_multiple: float | None = None
    trailing_atr_period: int | None = None
    trailing_atr_multiplier: float | None = None


@dataclass(frozen=True)
class ExecutionConfig:
    entry_mode: EntryMode = "next_bar_open"
    max_concurrent_per_direction: int = 1


@dataclass(frozen=True)
class StrategyConfig:
    instrument: InstrumentConfig
    timeframe: str
    wavetrend: WaveTrendConfig = field(default_factory=WaveTrendConfig)
    rsi: RsiConfig = field(default_factory=RsiConfig)
    confirmation: ConfirmationConfig = field(default_factory=ConfirmationConfig)
    signals: SignalsConfig = field(default_factory=SignalsConfig)
    risk: RiskConfig = field(default_factory=RiskConfig)
    execution: ExecutionConfig = field(default_factory=ExecutionConfig)

    @property
    def bar_minutes(self) -> int:
        tf = self.timeframe.lower().strip()
        if tf.endswith("m"):
            return int(tf[:-1])
        if tf.endswith("h"):
            return int(tf[:-1]) * 60
        raise ValueError(f"Unsupported timeframe: {self.timeframe!r}")


def load_strategy_config(path: str | Path) -> StrategyConfig:
    """Load a strategy YAML config into a typed StrategyConfig."""
    raw = yaml.safe_load(Path(path).read_text())
    return StrategyConfig(
        instrument=InstrumentConfig(**raw["instrument"]),
        timeframe=raw["timeframe"],
        wavetrend=WaveTrendConfig(**raw.get("wavetrend", {})),
        rsi=RsiConfig(**raw.get("rsi", {})),
        confirmation=ConfirmationConfig(**raw.get("confirmation", {})),
        signals=SignalsConfig(**raw.get("signals", {})),
        risk=RiskConfig(**raw.get("risk", {})),
        execution=ExecutionConfig(**raw.get("execution", {})),
    )
