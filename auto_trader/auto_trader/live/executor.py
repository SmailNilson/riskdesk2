"""Live execution loop.

Subscribes to MNQ realtime bars from IBKR, recomputes indicators on each
new closed bar, and submits bracket (entry + stop) orders when signals fire.

Safety:
- AUTO_TRADER_LIVE != "1" → dry-run mode (orders are LOGGED only).
- max_concurrent_per_direction enforced.
- All entry submissions require an attached protective STOP at the swing.
"""

from __future__ import annotations

import logging
import os
import time
from dataclasses import dataclass

import pandas as pd

from auto_trader.config import StrategyConfig
from auto_trader.live.ibkr_client import IbkrClient
from auto_trader.strategy.risk import build_risk_plan
from auto_trader.strategy.signals import SignalSide, detect_signals
from auto_trader.strategy.state import OpenPosition, PositionState

logger = logging.getLogger(__name__)


@dataclass
class LiveExecutor:
    cfg: StrategyConfig
    client: IbkrClient

    def __post_init__(self) -> None:
        self.state = PositionState()
        self._live = os.environ.get("AUTO_TRADER_LIVE", "0") == "1"
        self._last_signal_ts: pd.Timestamp | None = None

    def run(self, history_bars: pd.DataFrame, poll_seconds: float = 5.0) -> None:
        """Run until interrupted. `history_bars` is the warm-up DataFrame."""
        self.client.connect()
        try:
            contract = self.client.qualify_mnq()
            logger.info("Trading %s. Live=%s", contract.localSymbol, self._live)
            bars = history_bars.copy()
            while True:
                bars = self._refresh_bars(contract, bars)
                self._tick(bars)
                time.sleep(poll_seconds)
        finally:
            self.client.disconnect()

    def _refresh_bars(self, contract, current: pd.DataFrame) -> pd.DataFrame:
        """Fetch a small window of recent bars to pick up the latest close."""
        bar_size = self._ib_bar_size()
        bars = self.client.ib.reqHistoricalData(
            contract,
            endDateTime="",
            durationStr="1800 S",  # last 30 min — enough to refresh the active bar
            barSizeSetting=bar_size,
            whatToShow="TRADES",
            useRTH=False,
            formatDate=2,
            keepUpToDate=False,
        )
        if not bars:
            return current
        df = pd.DataFrame(
            [
                {
                    "timestamp": pd.to_datetime(b.date, utc=True),
                    "open": float(b.open),
                    "high": float(b.high),
                    "low": float(b.low),
                    "close": float(b.close),
                    "volume": float(b.volume),
                }
                for b in bars
            ]
        ).set_index("timestamp").sort_index()
        merged = pd.concat([current, df])
        merged = merged[~merged.index.duplicated(keep="last")].sort_index()
        return merged

    def _ib_bar_size(self) -> str:
        m = self.cfg.bar_minutes
        if m == 5:
            return "5 mins"
        if m == 10:
            return "10 mins"
        if m == 1:
            return "1 min"
        if m == 60:
            return "1 hour"
        raise ValueError(f"Unsupported timeframe for IBKR bar size: {self.cfg.timeframe}")

    def _tick(self, bars: pd.DataFrame) -> None:
        if len(bars) < self.cfg.wavetrend.average_length + 10:
            return  # warming up
        signals = detect_signals(bars, self.cfg)
        if not signals:
            return
        latest = signals[-1]
        # Skip if we already processed this bar's signal in a previous tick.
        if self._last_signal_ts is not None and latest.timestamp <= self._last_signal_ts:
            return
        # Strictly causal: only act on the prior fully-closed bar.
        if latest.bar_index >= len(bars) - 1:
            return
        self._last_signal_ts = latest.timestamp

        if self.state.has_open(latest.side):
            logger.info("Signal %s suppressed: position already open on this side", latest.side.value)
            return

        # Live entry price approximation = last close. The IBKR LMT-at-market
        # path is intentionally pessimistic; revise once IB market data is wired.
        last_close = float(bars["close"].iloc[-1])
        plan = build_risk_plan(latest, bars, entry_price=last_close, cfg=self.cfg)
        if plan is None:
            logger.warning("Risk plan rejected for signal at %s", latest.timestamp)
            return

        self._submit_bracket(plan)
        self.state.set(latest.side, OpenPosition(
            side=latest.side,
            entry_time=latest.timestamp,
            entry_price=plan.entry_price,
            contracts=plan.contracts,
            stop_loss=plan.stop_loss,
            take_profit=plan.take_profit,
            best_favorable_price=plan.entry_price,
            confirmed=latest.confirmed,
        ))

    def _submit_bracket(self, plan) -> None:
        if not self._live:
            logger.info(
                "[DRY-RUN] %s %sx @%.2f  SL=%.2f  TP=%s",
                plan.side.value, plan.contracts, plan.entry_price,
                plan.stop_loss, f"{plan.take_profit:.2f}" if plan.take_profit else "—",
            )
            return

        from ib_insync import LimitOrder, StopOrder  # noqa: WPS433

        action = "BUY" if plan.side is SignalSide.LONG else "SELL"
        opp = "SELL" if action == "BUY" else "BUY"
        ib = self.client.ib
        contract = self.client.qualify_mnq()

        parent = LimitOrder(action, plan.contracts, plan.entry_price)
        parent.transmit = False
        parent.outsideRth = True

        stop = StopOrder(opp, plan.contracts, plan.stop_loss)
        stop.parentId = 0  # set after parent gets id
        stop.transmit = plan.take_profit is None
        stop.outsideRth = True

        children = [stop]
        if plan.take_profit is not None:
            tp = LimitOrder(opp, plan.contracts, plan.take_profit)
            tp.transmit = True
            tp.outsideRth = True
            children.append(tp)

        parent_trade = ib.placeOrder(contract, parent)
        for child in children:
            child.parentId = parent_trade.order.orderId
            ib.placeOrder(contract, child)

        logger.info(
            "Submitted bracket: %s %sx @%.2f SL=%.2f TP=%s",
            action, plan.contracts, plan.entry_price, plan.stop_loss,
            f"{plan.take_profit:.2f}" if plan.take_profit else "—",
        )
