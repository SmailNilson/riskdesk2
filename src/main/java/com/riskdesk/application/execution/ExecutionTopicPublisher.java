package com.riskdesk.application.execution;

import com.riskdesk.application.dto.TradeExecutionView;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * Single writer of execution-lifecycle updates to {@code /topic/executions}. Centralizes the
 * STOMP publish (best-effort, never throws into the caller) so every producer — the IBKR fill
 * tracker, the invalidation watcher, etc. — shares one contract instead of re-implementing it.
 */
@Component
public class ExecutionTopicPublisher {

    public static final String EXECUTIONS_TOPIC = "/topic/executions";

    private static final Logger log = LoggerFactory.getLogger(ExecutionTopicPublisher.class);

    private final ObjectProvider<SimpMessagingTemplate> messagingProvider;

    public ExecutionTopicPublisher(ObjectProvider<SimpMessagingTemplate> messagingProvider) {
        this.messagingProvider = messagingProvider;
    }

    /** Publish the execution's current state. Swallows any messaging failure — publication is best-effort. */
    public void publish(TradeExecutionRecord execution) {
        try {
            SimpMessagingTemplate messaging = messagingProvider.getIfAvailable();
            if (messaging != null) {
                messaging.convertAndSend(EXECUTIONS_TOPIC, TradeExecutionView.from(execution));
            }
        } catch (Exception e) {
            log.debug("Could not publish execution {} on {}: {}",
                execution.getId(), EXECUTIONS_TOPIC, e.getMessage());
        }
    }
}
