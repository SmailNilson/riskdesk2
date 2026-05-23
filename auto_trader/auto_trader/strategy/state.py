"""Tracking of open positions and trade outcomes."""

from __future__ import annotations

from dataclasses import dataclass, field
from enum import StrEnum

import pandas as pd

from auto_trader.strategy.signals import SignalSide


class TradeOutcome(StrEnum):
    OPEN = "OPEN"
    SL_HIT = "SL_HIT"
    TP_HIT = "TP_HIT"
    TRAILING_EXIT = "TRAILING_EXIT"
    REVERSE_SIGNAL = "REVERSE_SIGNAL"
    MANUAL = "MANUAL"


@dataclass
class OpenPosition:
    side: SignalSide
    entry_time: pd.Timestamp
    entry_price: float
    contracts: int
    stop_loss: float
    take_profit: float | None
    best_favorable_price: float  # MFE tracker for trailing
    confirmed: bool

    def update_best(self, high: float, low: float) -> None:
        if self.side is SignalSide.LONG:
            self.best_favorable_price = max(self.best_favorable_price, high)
        else:
            self.best_favorable_price = min(self.best_favorable_price, low)


@dataclass
class PositionState:
    """Per-side concurrency cap (max_concurrent_per_direction)."""

    long_open: OpenPosition | None = None
    short_open: OpenPosition | None = None
    trades: list[dict] = field(default_factory=list)

    def has_open(self, side: SignalSide) -> bool:
        return (self.long_open if side is SignalSide.LONG else self.short_open) is not None

    def get(self, side: SignalSide) -> OpenPosition | None:
        return self.long_open if side is SignalSide.LONG else self.short_open

    def set(self, side: SignalSide, pos: OpenPosition | None) -> None:
        if side is SignalSide.LONG:
            self.long_open = pos
        else:
            self.short_open = pos
