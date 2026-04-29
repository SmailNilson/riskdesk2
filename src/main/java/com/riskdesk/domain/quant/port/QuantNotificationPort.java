package com.riskdesk.domain.quant.port;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.advisor.AiAdvice;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;

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

    /** Pushes the markdown narration + pattern verdict for the dashboard. */
    void publishNarration(Instrument instrument, QuantSnapshot snapshot, PatternAnalysis pattern, String markdown);

    /** Pushes the AI advisor verdict (tier 2) when one is available. */
    void publishAdvice(Instrument instrument, QuantSnapshot snapshot, AiAdvice advice);
}
