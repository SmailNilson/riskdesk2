package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.BigPrintDetected;
import com.riskdesk.domain.orderflow.model.BigPrint;
import com.riskdesk.domain.orderflow.port.BigPrintPort;
import com.riskdesk.domain.orderflow.service.BigPrintDetector;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Infrastructure adapter that manages per-instrument {@link BigPrintDetector} instances.
 * Routes classified AllLast ticks into the detectors and publishes each flagged print as
 * a {@link BigPrintDetected} event (WebSocket fan-out by the orchestrator, no persistence),
 * rate-limited to max 1 event per second per instrument. The 5-min big-print delta keeps
 * counting EVERY flagged print regardless of the event rate limit.
 *
 * <p>Thread-safe: ticks arrive on the IBKR EReader thread; the detector itself is
 * synchronized and {@link #bigPrintDelta5m} is read from the 5s orchestrator pass.</p>
 */
@Component
public class IbkrBigPrintAdapter implements BigPrintPort {

    private static final Logger log = LoggerFactory.getLogger(IbkrBigPrintAdapter.class);

    private static final long EVENT_RATE_LIMIT_MS = 1_000;

    private final ConcurrentHashMap<Instrument, BigPrintDetector> detectors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Instrument, Long> lastEventAtMs = new ConcurrentHashMap<>();
    private final OrderFlowProperties properties;
    private final ApplicationEventPublisher eventPublisher;

    public IbkrBigPrintAdapter(OrderFlowProperties properties,
                               ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Routes a classified trade tick into the instrument's big-print detector.
     * Publishes a {@link BigPrintDetected} event when the print is flagged and the
     * per-instrument 1/sec rate limit allows.
     */
    public void onTick(Instrument instrument, double price, long size,
                       String classification, Instant timestamp) {
        OrderFlowProperties.BigPrint cfg = properties.getBigPrint();
        if (!cfg.isEnabled()) {
            return;
        }
        BigPrintDetector detector = detectors.computeIfAbsent(instrument,
            k -> new BigPrintDetector(cfg.getPercentile(), cfg.getMinSize(), cfg.getWindowMinutes()));

        Optional<BigPrint> flagged = detector.onPrint(price, size, classification, timestamp);
        if (flagged.isEmpty()) {
            return;
        }

        long nowMs = System.currentTimeMillis();
        Long last = lastEventAtMs.get(instrument);
        if (last != null && nowMs - last < EVENT_RATE_LIMIT_MS) {
            return; // delta already counted by the detector; only the event is suppressed
        }
        lastEventAtMs.put(instrument, nowMs);
        try {
            eventPublisher.publishEvent(new BigPrintDetected(instrument, flagged.get(), timestamp));
        } catch (Exception e) {
            log.warn("Big print: failed to publish event for {}: {}", instrument, e.getMessage());
        }
    }

    @Override
    public long bigPrintDelta5m(Instrument instrument, Instant now) {
        BigPrintDetector detector = detectors.get(instrument);
        return detector == null ? 0L : detector.bigPrintDelta5m(now);
    }
}
