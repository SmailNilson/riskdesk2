"""Thin wrapper around `ib_insync` so the rest of the code stays decoupled.

Imports of ib_insync are deferred so unit tests don't require the dependency.
"""

from __future__ import annotations

import logging
import os
from dataclasses import dataclass

logger = logging.getLogger(__name__)


@dataclass
class IbkrConnection:
    host: str
    port: int
    client_id: int


def connection_from_env() -> IbkrConnection:
    return IbkrConnection(
        host=os.environ.get("IBKR_HOST", "100.113.139.64"),
        port=int(os.environ.get("IBKR_PORT", "4003")),
        client_id=int(os.environ.get("IBKR_CLIENT_ID", "18")),
    )


class IbkrClient:
    """Wraps `ib_insync.IB`. Lazy-imports the SDK only when actually connecting."""

    def __init__(self, conn: IbkrConnection | None = None) -> None:
        self.conn = conn or connection_from_env()
        self._ib = None  # set on connect

    def connect(self):
        from ib_insync import IB  # noqa: WPS433  -- lazy import

        self._ib = IB()
        logger.info(
            "Connecting to IBKR Gateway %s:%s as client_id=%s",
            self.conn.host, self.conn.port, self.conn.client_id,
        )
        self._ib.connect(self.conn.host, self.conn.port, clientId=self.conn.client_id)
        return self._ib

    def disconnect(self) -> None:
        if self._ib is not None and self._ib.isConnected():
            self._ib.disconnect()

    def qualify_mnq(self):
        """Resolve the front-month MNQ continuous contract.

        We rely on IBKR's ContFuture for rollover-safe live trading. For
        precise expiry control switch to `Future(symbol='MNQ', lastTradeDateOrContractMonth=...)`.
        """
        from ib_insync import ContFuture

        if self._ib is None:
            raise RuntimeError("IBKR not connected. Call connect() first.")
        contract = ContFuture(symbol="MNQ", exchange="CME", currency="USD")
        qualified = self._ib.qualifyContracts(contract)
        if not qualified:
            raise RuntimeError("Failed to qualify MNQ contract")
        return qualified[0]

    @property
    def ib(self):
        if self._ib is None:
            raise RuntimeError("IBKR not connected. Call connect() first.")
        return self._ib
