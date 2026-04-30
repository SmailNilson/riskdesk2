package com.riskdesk.infrastructure.quant.notification;

import com.riskdesk.application.dto.ActivePositionView;
import com.riskdesk.application.quant.positions.ActivePositionsService;
import com.riskdesk.domain.quant.positions.ActivePositionChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Listens to {@link ActivePositionChangedEvent} from the application layer
 * and pushes the current active-positions snapshot to {@code /topic/positions}.
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
public class ActivePositionsWebSocketAdapter {

    private static final Logger log = LoggerFactory.getLogger(ActivePositionsWebSocketAdapter.class);
    private static final String DESTINATION = "/topic/positions";

    private final SimpMessagingTemplate messagingTemplate;
    private final ActivePositionsService activePositionsService;

    public ActivePositionsWebSocketAdapter(SimpMessagingTemplate messagingTemplate,
                                           ActivePositionsService activePositionsService) {
        this.messagingTemplate = messagingTemplate;
        this.activePositionsService = activePositionsService;
    }

    @EventListener
    public void onChange(ActivePositionChangedEvent event) {
        publishSnapshot();
    }

    /**
     * Periodic 5s heartbeat. Publishes the latest snapshot so the panel sees
     * fresh PnL even when nothing transitions on the broker side. Configurable
     * via {@code riskdesk.positions.heartbeat-millis} if a deployment needs
     * a different cadence; defaults to 5_000 ms.
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
