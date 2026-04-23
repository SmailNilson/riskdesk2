package com.riskdesk.application.service;

import com.riskdesk.application.dto.TradeExecutionView;
import com.riskdesk.domain.execution.port.ExecutionFillListener;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Slice 3a — IBKR execDetails + orderStatus fill tracking.
 *
 * <p>Receives raw broker feedback from the IBKR adapter (via the
 * {@link ExecutionFillListener} domain port) and persists it on the matching
 * {@link TradeExecutionRecord}. Only updates fill-specific fields and the
 * domain lifecycle on {@code Filled} (transition to {@link ExecutionStatus#ACTIVE});
 * it DOES NOT place any IBKR child orders — bracket / virtual exit orchestration
 * is future slice 3c.</p>
 *
 * <p>Idempotence:</p>
 * <ul>
 *   <li>{@code execDetails} is deduped by IBKR {@code execId} stored in
 *       {@code lastExecId}. A replay of the same fill is a no-op.</li>
 *   <li>{@code orderStatus} is deduped by comparing the new raw fields against
 *       the persisted ones — if nothing material changed, no save, no publish.</li>
 * </ul>
 *
 * <p>Every state-changing update is published on {@code /topic/executions}.</p>
 */
@Service
public class ExecutionFillTrackingService implements ExecutionFillListener {

    private static final Logger log = LoggerFactory.getLogger(ExecutionFillTrackingService.class);
    private static final String EXECUTIONS_TOPIC = "/topic/executions";
    private static final String IBKR_STATUS_FILLED = "Filled";
    private static final String IBKR_STATUS_CANCELLED = "Cancelled";
    private static final String IBKR_STATUS_API_CANCELLED = "ApiCancelled";
    private static final String IBKR_STATUS_INACTIVE = "Inactive";

    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final ObjectProvider<SimpMessagingTemplate> messagingProvider;

    public ExecutionFillTrackingService(TradeExecutionRepositoryPort tradeExecutionRepository,
                                        ObjectProvider<SimpMessagingTemplate> messagingProvider) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.messagingProvider = messagingProvider;
    }

    @Override
    public synchronized void onExecDetails(int orderId,
                                           String execId,
                                           String orderRef,
                                           BigDecimal cumQty,
                                           BigDecimal avgPrice,
                                           BigDecimal lastFillPrice,
                                           String side,
                                           Instant time) {
        Optional<TradeExecutionRecord> found = locate(orderId, orderRef);
        if (found.isEmpty()) {
            log.debug("execDetails ignored — no TradeExecution for orderId={} orderRef={}", orderId, orderRef);
            return;
        }
        TradeExecutionRecord execution = found.get();

        if (execId != null && !execId.isBlank() && execId.equals(execution.getLastExecId())) {
            log.debug("execDetails dedup — execId={} already applied to execution {}", execId, execution.getId());
            return;
        }

        boolean dirty = false;
        if (execution.getIbkrOrderId() == null || execution.getIbkrOrderId() != orderId) {
            execution.setIbkrOrderId(orderId);
            dirty = true;
        }
        if (cumQty != null && !cumQty.equals(execution.getFilledQuantity())) {
            execution.setFilledQuantity(cumQty);
            dirty = true;
        }
        if (avgPrice != null && !avgPrice.equals(execution.getAvgFillPrice())) {
            execution.setAvgFillPrice(avgPrice);
            dirty = true;
        }
        if (time != null && !time.equals(execution.getLastFillTime())) {
            execution.setLastFillTime(time);
            dirty = true;
        }
        if (execId != null && !execId.isBlank()) {
            execution.setLastExecId(execId);
            dirty = true;
        }

        if (!dirty) {
            return;
        }

        Instant now = Instant.now();
        execution.setUpdatedAt(now);

        TradeExecutionRecord saved = tradeExecutionRepository.save(execution);
        log.info("IBKR execDetails applied — execution={} orderId={} execId={} cumQty={} avgPx={} side={}",
            saved.getId(), orderId, execId, cumQty, avgPrice, side);
        publish(saved);
    }

    @Override
    public synchronized void onOrderStatus(int orderId,
                                           String status,
                                           BigDecimal filled,
                                           BigDecimal remaining,
                                           BigDecimal avgFillPrice,
                                           Instant lastFillTime) {
        Optional<TradeExecutionRecord> found = locate(orderId, null);
        if (found.isEmpty()) {
            log.debug("orderStatus ignored — no TradeExecution for orderId={}", orderId);
            return;
        }
        TradeExecutionRecord execution = found.get();

        boolean dirty = false;
        if (execution.getIbkrOrderId() == null || execution.getIbkrOrderId() != orderId) {
            execution.setIbkrOrderId(orderId);
            dirty = true;
        }
        if (status != null && !status.equals(execution.getOrderStatus())) {
            execution.setOrderStatus(status);
            dirty = true;
        }
        if (filled != null && !filled.equals(execution.getFilledQuantity())) {
            execution.setFilledQuantity(filled);
            dirty = true;
        }
        if (avgFillPrice != null && avgFillPrice.signum() > 0 && !avgFillPrice.equals(execution.getAvgFillPrice())) {
            execution.setAvgFillPrice(avgFillPrice);
            dirty = true;
        }
        if (lastFillTime != null && !lastFillTime.equals(execution.getLastFillTime())) {
            execution.setLastFillTime(lastFillTime);
            dirty = true;
        }

        // Domain lifecycle transitions based on IBKR status.
        if (IBKR_STATUS_FILLED.equalsIgnoreCase(status)
            && execution.getStatus() != ExecutionStatus.ACTIVE
            && execution.getStatus() != ExecutionStatus.CLOSED
            && execution.getStatus() != ExecutionStatus.EXIT_SUBMITTED
            && execution.getStatus() != ExecutionStatus.VIRTUAL_EXIT_TRIGGERED) {
            execution.setStatus(ExecutionStatus.ACTIVE);
            execution.setStatusReason("IBKR order fully filled");
            if (execution.getEntryFilledAt() == null) {
                execution.setEntryFilledAt(lastFillTime == null ? Instant.now() : lastFillTime);
            }
            dirty = true;
        } else if ((IBKR_STATUS_CANCELLED.equalsIgnoreCase(status)
                || IBKR_STATUS_API_CANCELLED.equalsIgnoreCase(status)
                || IBKR_STATUS_INACTIVE.equalsIgnoreCase(status))
            && execution.getStatus() != ExecutionStatus.CANCELLED
            && execution.getStatus() != ExecutionStatus.CLOSED) {
            // Only flip to CANCELLED when nothing has been filled — a partial fill
            // followed by a cancel leaves the position open and is handled in 3c.
            if (execution.getFilledQuantity() == null
                || execution.getFilledQuantity().signum() == 0) {
                execution.setStatus(ExecutionStatus.CANCELLED);
                execution.setStatusReason("IBKR order " + status);
                dirty = true;
            }
        }

        if (!dirty) {
            return;
        }

        execution.setUpdatedAt(Instant.now());
        TradeExecutionRecord saved = tradeExecutionRepository.save(execution);
        log.info("IBKR orderStatus applied — execution={} orderId={} status={} filled={}/{} avgPx={}",
            saved.getId(), orderId, status, filled, remaining, avgFillPrice);
        publish(saved);
    }

    private Optional<TradeExecutionRecord> locate(int orderId, String orderRef) {
        Optional<TradeExecutionRecord> byOrderId = tradeExecutionRepository.findByIbkrOrderId(orderId);
        if (byOrderId.isPresent()) {
            return byOrderId;
        }
        if (orderRef == null || orderRef.isBlank()) {
            return Optional.empty();
        }
        // Fallback: orderRef is set at submission to executionKey, so we can
        // recover the linkage even if the TWS orderId hasn't been persisted yet.
        return tradeExecutionRepository.findByExecutionKey(orderRef);
    }

    private void publish(TradeExecutionRecord execution) {
        try {
            SimpMessagingTemplate messaging = messagingProvider.getIfAvailable();
            if (messaging != null) {
                messaging.convertAndSend(EXECUTIONS_TOPIC, TradeExecutionView.from(execution));
            }
        } catch (Exception e) {
            log.debug("Could not publish on {} for execution {}: {}",
                EXECUTIONS_TOPIC, execution.getId(), e.getMessage());
        }
    }
}
