package com.riskdesk.application.execution;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrOrderRejectionException;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;

/**
 * The single entry point through which every strategy submits to IBKR. Owns the shared mechanics —
 * startup gate, idempotence, execution-row persistence, broker submission and typed error mapping —
 * by reusing the existing {@link IbkrOrderService} and {@link TradeExecutionRepositoryPort}; it does
 * not duplicate them. Generalises the proven {@code WtxExecutionBridge.handleEntry} flow.
 *
 * <p>This step implements {@code OPEN}. {@code REVERSE}/{@code CLOSE}/{@code FLATTEN} (which need
 * broker-truth reconciliation and, for FLATTEN, the held side) land in a later step — see
 * docs/PLAN_ORDER_ROUTER_IMPL.md. The router is not yet wired to any strategy.</p>
 */
@Service
public class DefaultOrderRouter implements OrderRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultOrderRouter.class);

    private final IbkrOrderService ibkrOrderService;
    private final TradeExecutionRepositoryPort executionRepository;
    private final IbkrProperties ibkrProperties;
    private final ExecutionReadinessGate readinessGate;

    public DefaultOrderRouter(IbkrOrderService ibkrOrderService,
                              TradeExecutionRepositoryPort executionRepository,
                              IbkrProperties ibkrProperties,
                              ExecutionReadinessGate readinessGate) {
        this.ibkrOrderService = ibkrOrderService;
        this.executionRepository = executionRepository;
        this.ibkrProperties = ibkrProperties;
        this.readinessGate = readinessGate;
    }

    @Override
    public RoutingResult route(TradeIntent intent) {
        if (intent == null) {
            throw new IllegalArgumentException("intent is required");
        }
        if (!readinessGate.isReady()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_RECONCILING,
                "execution core is still reconciling broker truth at startup");
        }
        if (!ibkrProperties.isEnabled()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_IBKR_DISABLED,
                "IBKR is disabled in the backend configuration");
        }
        return switch (intent.kind()) {
            case OPEN -> routeOpen(intent);
            case REVERSE, CLOSE, FLATTEN -> throw new UnsupportedOperationException(
                "OrderRouter: " + intent.kind() + " is not implemented yet (see docs/PLAN_ORDER_ROUTER_IMPL.md)");
        };
    }

    private RoutingResult routeOpen(TradeIntent intent) {
        if (executionRepository.findByExecutionKey(intent.idempotencyKey()).isPresent()) {
            return RoutingResult.of(RoutingOutcome.SKIPPED_DUPLICATE,
                "execution already exists for " + intent.idempotencyKey());
        }

        // createIfAbsent de-dups on a unique constraint, so a concurrent caller cannot double-submit.
        TradeExecutionRecord persisted = executionRepository.createIfAbsent(toPendingRecord(intent));
        if (persisted.getStatus() != ExecutionStatus.PENDING_ENTRY_SUBMISSION) {
            return RoutingResult.tracked(RoutingOutcome.SKIPPED_DUPLICATE, "execution already in flight",
                persisted.getId(), persisted.getEntryOrderId());
        }

        try {
            BrokerEntryOrderSubmission submission = ibkrOrderService.submitEntryOrder(new BrokerEntryOrderRequest(
                persisted.getId(),
                persisted.getExecutionKey(),
                persisted.getBrokerAccountId(),
                persisted.getInstrument(),
                brokerAction(intent),
                persisted.getQuantity(),
                persisted.getNormalizedEntryPrice()));

            // BOTH ids must be set: entryOrderId for the audit, ibkrOrderId (Integer) so the fill
            // tracker can locate this row on the orderStatus/execDetails callbacks.
            persisted.setEntryOrderId(submission.brokerOrderId());
            persisted.setIbkrOrderId(toIbkrOrderId(submission.brokerOrderId()));
            persisted.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
            persisted.setStatusReason("OrderRouter OPEN submitted: " + submission.brokerOrderStatus());
            persisted.setEntrySubmittedAt(submission.submittedAt() != null ? submission.submittedAt() : Instant.now());
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);

            RoutingOutcome outcome = "PendingSubmit".equalsIgnoreCase(submission.brokerOrderStatus())
                ? RoutingOutcome.ACK_PENDING
                : RoutingOutcome.ROUTED;
            return RoutingResult.tracked(outcome, persisted.getId(), submission.brokerOrderId());

        } catch (IbkrOrderRejectionException e) {
            RoutingOutcome outcome = mapRejection(e);
            // Keep the row NON-TERMINAL whenever the order is — or may be — live at the broker:
            // ACK_PENDING (id present, late ack) AND FAILED_TIMEOUT (no id, broker state UNKNOWN). Late
            // orderStatus/execDetails callbacks (or the stale-entry reconciler) must still resolve it,
            // and terminal-failing here would allow a retry against an order the broker may already
            // hold. mustTrackExecutionRow() is the single source of truth for that distinction.
            persisted.setStatus(outcome.mustTrackExecutionRow()
                ? ExecutionStatus.ENTRY_SUBMITTED : ExecutionStatus.FAILED);
            persisted.setStatusReason(truncate("OrderRouter OPEN " + e.kind() + ": " + e.getMessage(), 256));
            if (e.brokerOrderId() != null) {
                persisted.setEntryOrderId(e.brokerOrderId());
                persisted.setIbkrOrderId(toIbkrOrderId(e.brokerOrderId()));
                persisted.setEntrySubmittedAt(Instant.now());
            }
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.info("OrderRouter OPEN {} ({}) — {}", outcome, e.kind(), intent.idempotencyKey());
            return RoutingResult.tracked(outcome, persisted.getId(), e.brokerOrderId());

        } catch (RuntimeException e) {
            persisted.setStatus(ExecutionStatus.FAILED);
            persisted.setStatusReason(truncate("OrderRouter OPEN failed: " + e.getMessage(), 256));
            persisted.setUpdatedAt(Instant.now());
            executionRepository.save(persisted);
            log.warn("OrderRouter OPEN failed for {}: {}", intent.idempotencyKey(), e.getMessage());
            return RoutingResult.tracked(RoutingOutcome.FAILED, e.getMessage(), persisted.getId(), null);
        }
    }

    private TradeExecutionRecord toPendingRecord(TradeIntent intent) {
        Instant now = Instant.now();
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setExecutionKey(intent.idempotencyKey());
        r.setInstrument(intent.instrument().name());
        r.setTimeframe(intent.timeframe());
        r.setAction(brokerAction(intent));
        r.setQuantity(intent.quantity());
        r.setTriggerSource(intent.source());
        r.setBrokerAccountId(intent.brokerAccountId());
        r.setRequestedBy(intent.source().name());
        r.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        r.setNormalizedEntryPrice(normalizeToTick(intent.limitPrice(), intent.instrument()));
        r.setCreatedAt(now);
        r.setUpdatedAt(now);
        return r;
    }

    /** Broker action token ("LONG"/"SHORT") for the order, derived from intent kind + side. */
    private static String brokerAction(TradeIntent intent) {
        Side side = intent.side();
        return switch (intent.kind()) {
            case OPEN, REVERSE -> side == Side.LONG ? "LONG" : "SHORT";   // BUY a long / SELL a short
            case CLOSE -> side == Side.LONG ? "SHORT" : "LONG";           // closing a long is a SELL
            case FLATTEN -> throw new IllegalStateException("FLATTEN requires the held side (broker-truth) — later step");
        };
    }

    private static RoutingOutcome mapRejection(IbkrOrderRejectionException e) {
        return switch (e.kind()) {
            case INSUFFICIENT_MARGIN -> RoutingOutcome.FAILED_INSUFFICIENT_MARGIN;
            case TIMEOUT -> e.brokerOrderId() != null ? RoutingOutcome.ACK_PENDING : RoutingOutcome.FAILED_TIMEOUT;
            case BROKER_REJECT, CANCELLED -> RoutingOutcome.FAILED_BROKER_REJECT;
            case UNKNOWN -> RoutingOutcome.FAILED;
        };
    }

    private static Integer toIbkrOrderId(Long brokerOrderId) {
        return brokerOrderId == null ? null : brokerOrderId.intValue();
    }

    private static BigDecimal normalizeToTick(BigDecimal price, Instrument instrument) {
        BigDecimal tick = instrument.getTickSize();
        return price.divide(tick, 0, RoundingMode.HALF_UP)
            .multiply(tick)
            .setScale(tick.scale(), RoundingMode.HALF_UP);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
