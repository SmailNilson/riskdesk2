package com.riskdesk.application.quant.positions;

import com.riskdesk.application.dto.ActivePositionView;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.positions.ActivePositionChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Application-layer use case for the Active Positions panel.
 *
 * <p>Two responsibilities:
 * <ol>
 *   <li>{@link #listActive()} — fetch every non-terminal execution and
 *       enrich each row with a server-side PnL snapshot computed from the
 *       latest live-price reading. This is the cold-start payload used by
 *       the panel before the WS price stream warms up.</li>
 *   <li>{@link #closePosition(Long, String)} — operator-driven close. For
 *       rows that have not yet hit the broker (PENDING_ENTRY_SUBMISSION),
 *       transition straight to {@link ExecutionStatus#CANCELLED}. For rows
 *       already in flight at the broker (ENTRY_SUBMITTED, ACTIVE, ...), mark
 *       {@link ExecutionStatus#EXIT_SUBMITTED} so the existing fill-tracking
 *       path will reconcile the broker side. Real IBKR market-exit submission
 *       is intentionally out-of-scope for this panel slice — the simulation /
 *       virtual-exit flow already covers the close lifecycle from
 *       EXIT_SUBMITTED → CLOSED.</li>
 * </ol>
 *
 * <p>An {@link ActivePositionChangedEvent} is published on every state
 * transition so the WebSocket adapter can fan out the new list without the
 * caller having to know about messaging. Event publication failures are
 * never propagated — the executed close must remain atomic from the API
 * caller's perspective.</p>
 */
@Service
public class ActivePositionsService {

    private static final Logger log = LoggerFactory.getLogger(ActivePositionsService.class);

    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final LivePricePort livePricePort;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public ActivePositionsService(TradeExecutionRepositoryPort tradeExecutionRepository,
                                  LivePricePort livePricePort,
                                  ApplicationEventPublisher eventPublisher,
                                  Clock clock) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.livePricePort = livePricePort;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    /**
     * Snapshot of every currently-open (non-terminal) execution, enriched
     * with a server-side PnL computation. The price cache is keyed by
     * instrument so we hit the live-price port once per distinct symbol
     * rather than once per row.
     */
    public List<ActivePositionView> listActive() {
        List<TradeExecutionRecord> active = tradeExecutionRepository.findAllActive();
        if (active.isEmpty()) {
            return List.of();
        }
        Map<Instrument, BigDecimal> priceCache = new EnumMap<>(Instrument.class);
        return active.stream()
            .map(rec -> ActivePositionView.from(rec, priceFor(rec.getInstrument(), priceCache)))
            .toList();
    }

    /**
     * Close a position by execution id.
     *
     * @return the updated execution view, or empty if the id does not exist
     */
    public Optional<ActivePositionView> closePosition(Long executionId, String requestedBy) {
        if (executionId == null) {
            return Optional.empty();
        }
        Optional<TradeExecutionRecord> opt = tradeExecutionRepository.findByIdForUpdate(executionId);
        if (opt.isEmpty()) {
            return Optional.empty();
        }
        TradeExecutionRecord exec = opt.get();
        ExecutionStatus current = exec.getStatus();

        if (current == null) {
            throw new IllegalStateException("execution status is null — cannot close");
        }
        if (isTerminal(current)) {
            // Idempotence — surface the existing terminal row rather than 409.
            return Optional.of(ActivePositionView.from(exec,
                priceFor(exec.getInstrument(), new EnumMap<>(Instrument.class))));
        }

        Instant now = clock.instant();
        String who = (requestedBy == null || requestedBy.isBlank()) ? "operator" : requestedBy;

        if (current == ExecutionStatus.PENDING_ENTRY_SUBMISSION) {
            exec.setStatus(ExecutionStatus.CANCELLED);
            exec.setStatusReason("Cancelled by " + who + " — close requested before broker submission.");
        } else {
            // Broker-known states: signal the exit so fill-tracking can reconcile.
            exec.setStatus(ExecutionStatus.EXIT_SUBMITTED);
            exec.setStatusReason("Exit requested by " + who + ". Broker exit submission tracked downstream.");
            exec.setExitSubmittedAt(now);
        }
        exec.setUpdatedAt(now);
        TradeExecutionRecord saved = tradeExecutionRepository.save(exec);

        publishSafely(new ActivePositionChangedEvent(
            saved.getId(),
            saved.getInstrument(),
            saved.getStatus(),
            ActivePositionChangedEvent.Kind.CLOSE_REQUESTED,
            now
        ));
        log.info("Active position close requested executionId={} instrument={} previousStatus={} newStatus={} by={}",
            saved.getId(), saved.getInstrument(), current, saved.getStatus(), who);

        return Optional.of(ActivePositionView.from(saved,
            priceFor(saved.getInstrument(), new EnumMap<>(Instrument.class))));
    }

    private BigDecimal priceFor(String instrumentName, Map<Instrument, BigDecimal> cache) {
        Instrument instrument = parseInstrument(instrumentName);
        if (instrument == null) return null;
        if (cache.containsKey(instrument)) return cache.get(instrument);
        BigDecimal price = livePricePort.current(instrument)
            .map(snap -> BigDecimal.valueOf(snap.price()))
            .orElse(null);
        cache.put(instrument, price);
        return price;
    }

    private static boolean isTerminal(ExecutionStatus status) {
        return switch (status) {
            case CLOSED, CANCELLED, REJECTED, FAILED -> true;
            default -> false;
        };
    }

    private static Instrument parseInstrument(String name) {
        try {
            return name == null ? null : Instrument.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private void publishSafely(ActivePositionChangedEvent event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            // Notification hiccups must never break a successful close.
            log.debug("ActivePositionChangedEvent publish failed: {}", e.toString());
        }
    }
}
