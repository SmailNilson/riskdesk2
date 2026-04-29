package com.riskdesk.domain.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.QuantSnapshot;

/**
 * Output port broadcasting Quant evaluator results to interested consumers
 * (frontend WebSocket subscribers, notification adapters, ...).
 */
public interface QuantNotificationPort {

    /** Publishes a per-instrument snapshot stream consumed by the frontend dashboard. */
    void publishSnapshot(Instrument instrument, QuantSnapshot snapshot);

    /** Fires a high-priority audio alert when a 7/7 SHORT setup is confirmed. */
    void publishShortSignal7_7(Instrument instrument, QuantSnapshot snapshot);

    /** Fires a soft alert when 6/7 gates have passed (early warning). */
    void publishSetupAlert6_7(Instrument instrument, QuantSnapshot snapshot);
}
