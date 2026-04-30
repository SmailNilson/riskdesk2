package com.riskdesk.application.quant.positions;

import com.riskdesk.application.dto.ActivePositionView;
import com.riskdesk.domain.quant.positions.ActivePositionChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Bridges {@link ActivePositionChangedEvent} (and a periodic 5s heartbeat)
 * to the {@code /topic/positions} WebSocket destination.
 *
 * <p>Lives in the application layer because ArchUnit forbids
 * infrastructure → application dependencies, and we need the snapshot
 * computed by {@link ActivePositionsService}. Other application services
 * already use {@link SimpMessagingTemplate} the same way (e.g.
 * {@code MarketDataService}, {@code BehaviourAlertService}).</p>
 *
 * <p>Two trigger paths feed the same destination:
 * <ul>
 *   <li><strong>Event-driven</strong> — every status transition (close
 *       requested, broker fill flipping the row through EXIT_SUBMITTED, ...)
 *       fans out an immediate snapshot so the dashboard never lags behind a
 *       user-driven action.</li>
 *   <li><strong>Periodic 5s tick</strong> — a Spring {@code @Scheduled}
 *       heartbeat publishes a fresh snapshot regardless of event traffic.
 *       This catches cases where the only thing that changed is the live
 *       price (and the per-row PnL with it) — we recompute server-side once
 *       every 5s as a fallback for clients that haven't received the
 *       {@code /topic/prices} stream yet.</li>
 * </ul>
 *
 * <p>The publish is best-effort — broker / messaging hiccups must never
 * propagate into the application service that emitted the event.</p>
 */
@Component
public class ActivePositionsWebSocketPublisher {

    private static final Logger log = LoggerFactory.getLogger(ActivePositionsWebSocketPublisher.class);
    private static final String DESTINATION = "/topic/positions";

    private final SimpMessagingTemplate messagingTemplate;
    private final ActivePositionsService activePositionsService;

    public ActivePositionsWebSocketPublisher(SimpMessagingTemplate messagingTemplate,
                                             ActivePositionsService activePositionsService) {
        this.messagingTemplate = messagingTemplate;
        this.activePositionsService = activePositionsService;
    }

    @EventListener
    public void onChange(ActivePositionChangedEvent event) {
        publishSnapshot();
    }

    /**
     * Periodic 5s heartbeat. Configurable via
     * {@code riskdesk.positions.heartbeat-millis}; defaults to 5_000 ms.
     */
    @Scheduled(fixedDelayString = "${riskdesk.positions.heartbeat-millis:5000}")
    public void heartbeat() {
        publishSnapshot();
    }

    private void publishSnapshot() {
        try {
            List<ActivePositionView> snapshot = activePositionsService.listActive();
            messagingTemplate.convertAndSend(DESTINATION, snapshot);
        } catch (Exception e) {
            log.debug("Active positions WS publish failed: {}", e.toString());
        }
    }
}
