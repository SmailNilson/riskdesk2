package com.riskdesk.domain.marketdata.port;

import com.riskdesk.domain.contract.RolloverCandidate;
import com.riskdesk.domain.model.Instrument;

import java.util.List;

/**
 * Port for resolving the nearest N futures contract months for an instrument,
 * ordered by expiry (ascending).  The first element is the current front-month,
 * the second is the next-month, and so on.
 */
public interface ContractCalendar {
    List<RolloverCandidate> nearestContracts(Instrument instrument, int count);
}
