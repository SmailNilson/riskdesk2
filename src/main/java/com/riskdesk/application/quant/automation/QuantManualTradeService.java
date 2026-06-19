package com.riskdesk.application.quant.automation;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.quant.service.QuantGateService;
import com.riskdesk.application.service.ExecutionManagerService;
import com.riskdesk.application.service.SubmitEntryOrderCommand;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;

/**
 * Manual trade-ticket entry point invoked from the QuantGatePanel UI.
 *
 * <p>Independent of the auto-arm pipeline: the operator chooses the
 * direction, entry, SL, TP and quantity. Auto-arm thresholds (score &gt;= 7,
 * structural blocks) do NOT apply — the operator takes full responsibility
 * for the trade.
 */
@Service
public class QuantManualTradeService {

    private static final Logger log = LoggerFactory.getLogger(QuantManualTradeService.class);

    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final ExecutionManagerService executionManagerService;
    private final QuantGateService quantGateService;
    private final QuantAutoArmProperties autoArmProps;
    private final Clock clock;

    public QuantManualTradeService(TradeExecutionRepositoryPort tradeExecutionRepository,
                                   ExecutionManagerService executionManagerService,
                                   QuantGateService quantGateService,
                                   QuantAutoArmProperties autoArmProps,
                                   Clock clock) {
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.executionManagerService = executionManagerService;
        this.quantGateService = quantGateService;
        this.autoArmProps = autoArmProps;
        this.clock = clock;
    }

    public TradeExecutionRecord place(Instrument instrument, ManualTradeRequest req, String requestedBy) {
        if (instrument == null) {
            throw new IllegalArgumentException("instrument is required");
        }
        if (req == null) {
            throw new IllegalArgumentException("request body is required");
        }
        if (req.direction() == null) {
            throw new IllegalArgumentException("direction is required (LONG or SHORT)");
        }
        ManualEntryType entryType = req.entryType() == null ? ManualEntryType.LIMIT : req.entryType();
        if (req.stopLoss() == null) {
            throw new IllegalArgumentException("stopLoss is required");
        }
        if (req.takeProfit1() == null) {
            throw new IllegalArgumentException("takeProfit1 is required");
        }
        boolean stop = entryType == ManualEntryType.STOP || entryType == ManualEntryType.STOP_LIMIT;
        if (entryType == ManualEntryType.LIMIT && req.entryPrice() == null) {
            throw new IllegalArgumentException("entryPrice is required for LIMIT orders");
        }
        if (stop && req.triggerPrice() == null) {
            throw new IllegalArgumentException("triggerPrice is required for STOP / STOP_LIMIT orders");
        }
        if (entryType == ManualEntryType.STOP_LIMIT && req.entryPrice() == null) {
            throw new IllegalArgumentException("entryPrice (limit cap) is required for STOP_LIMIT orders");
        }
        int quantity = req.quantity() == null || req.quantity() < 1 ? 1 : req.quantity();

        // Reference price the SL/TP geometry is validated against — the resting price the entry arms at:
        // the trigger for STOP/STOP_LIMIT, the limit for LIMIT, the live price for MARKET.
        BigDecimal referencePrice = switch (entryType) {
            case STOP, STOP_LIMIT -> req.triggerPrice();
            case MARKET -> resolveLivePrice(instrument);
            case LIMIT -> req.entryPrice();
        };
        if (referencePrice == null) {
            throw new IllegalStateException("Cannot resolve a live price for MARKET order on " + instrument
                + " — no recent quant snapshot. Use LIMIT and supply an entryPrice.");
        }

        // Breakout geometry for stop entries: a buy STOP arms ABOVE the market, a sell STOP BELOW it.
        // Enforced only when a live price is available; otherwise the operator's trigger is trusted.
        if (stop) {
            BigDecimal live = resolveLivePrice(instrument);
            if (live != null && req.direction() == ManualDirection.LONG && req.triggerPrice().compareTo(live) <= 0) {
                throw new IllegalArgumentException("LONG stop trigger must be above the live price " + live);
            }
            if (live != null && req.direction() == ManualDirection.SHORT && req.triggerPrice().compareTo(live) >= 0) {
                throw new IllegalArgumentException("SHORT stop trigger must be below the live price " + live);
            }
            if (entryType == ManualEntryType.STOP_LIMIT && req.direction() == ManualDirection.LONG
                && req.entryPrice().compareTo(req.triggerPrice()) < 0) {
                throw new IllegalArgumentException("LONG STOP_LIMIT limit cap must be >= the trigger");
            }
            if (entryType == ManualEntryType.STOP_LIMIT && req.direction() == ManualDirection.SHORT
                && req.entryPrice().compareTo(req.triggerPrice()) > 0) {
                throw new IllegalArgumentException("SHORT STOP_LIMIT limit cap must be <= the trigger");
            }
        }

        validatePlanGeometry(req.direction(), referencePrice, req.stopLoss(), req.takeProfit1());

        // Broker order shape: STOP arms at the trigger (stop-market); STOP_LIMIT arms at the trigger and
        // caps the fill at entryPrice; LIMIT/MARKET submit a (marketable) limit at the reference price.
        String orderTypeToken = switch (entryType) {
            case STOP -> BrokerEntryOrderRequest.ORDER_TYPE_STOP;
            case STOP_LIMIT -> BrokerEntryOrderRequest.ORDER_TYPE_STOP_LIMIT;
            case LIMIT, MARKET -> BrokerEntryOrderRequest.ORDER_TYPE_LIMIT;
        };
        BigDecimal normalizedEntry = switch (entryType) {
            case STOP -> normalize(req.triggerPrice(), instrument);   // stop-market: order price = trigger
            case STOP_LIMIT -> normalize(req.entryPrice(), instrument); // limit cap
            case LIMIT, MARKET -> normalize(referencePrice, instrument);
        };
        BigDecimal triggerToPersist = stop ? normalize(req.triggerPrice(), instrument) : null;

        // Per-request account (chart trading passes the account selected in the IBKR panel) with
        // the auto-arm config as fallback for the legacy ticket flow.
        String brokerAccount = req.brokerAccountId() != null && !req.brokerAccountId().isBlank()
            ? req.brokerAccountId()
            : autoArmProps.getBrokerAccountId();
        if (brokerAccount == null || brokerAccount.isBlank()) {
            throw new IllegalStateException(
                "a brokerAccountId (request) or riskdesk.quant.auto-arm.broker-account-id is required to place manual trades");
        }

        Instant now = clock.instant();
        TradeExecutionRecord candidate = new TradeExecutionRecord();
        candidate.setExecutionKey("exec:quant-manual:" + instrument.name() + ":"
            + req.direction().name() + ":" + now.toEpochMilli());
        candidate.setMentorSignalReviewId(null);
        candidate.setReviewAlertKey(null);
        candidate.setReviewRevision(null);
        candidate.setBrokerAccountId(brokerAccount);
        candidate.setInstrument(instrument.name());
        candidate.setTimeframe("manual");
        candidate.setAction(req.direction().action());
        candidate.setQuantity(quantity);
        candidate.setTriggerSource(ExecutionTriggerSource.MANUAL_QUANT_PANEL);
        candidate.setRequestedBy(requestedBy == null || requestedBy.isBlank() ? "manual-panel" : requestedBy);
        candidate.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        candidate.setStatusReason("Manual trade ticket — " + entryType.name() + " by operator");
        candidate.setNormalizedEntryPrice(normalizedEntry);
        candidate.setOrderType(orderTypeToken);
        candidate.setTriggerPrice(triggerToPersist);
        candidate.setVirtualStopLoss(normalize(req.stopLoss(), instrument));
        candidate.setVirtualTakeProfit(normalize(req.takeProfit1(), instrument));
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);

        TradeExecutionRecord persisted = tradeExecutionRepository.createIfAbsent(candidate);

        log.info("manual-trade created instrument={} direction={} entryType={} entry={} sl={} tp1={} qty={} executionId={}",
            instrument, req.direction(), entryType, persisted.getNormalizedEntryPrice(),
            persisted.getVirtualStopLoss(), persisted.getVirtualTakeProfit(), quantity, persisted.getId());

        // MARKET always goes straight to the broker. A LIMIT goes straight too when the caller
        // asks for it (chart trading: one click = one resting order at IBKR) — the legacy two-step
        // flow (create PENDING, then POST /api/mentor/executions/{id}/submit-entry) stays the
        // default for backward compatibility.
        if (entryType == ManualEntryType.MARKET || Boolean.TRUE.equals(req.submitImmediately())) {
            return executionManagerService.submitEntryOrder(new SubmitEntryOrderCommand(
                persisted.getId(),
                now,
                candidate.getRequestedBy()
            ));
        }
        return persisted;
    }

    private BigDecimal resolveLivePrice(Instrument instrument) {
        QuantSnapshot snapshot = quantGateService.latestSnapshot(instrument);
        if (snapshot == null || snapshot.price() == null) return null;
        return BigDecimal.valueOf(snapshot.price());
    }

    private static void validatePlanGeometry(ManualDirection direction, BigDecimal entry, BigDecimal sl, BigDecimal tp1) {
        if (direction == ManualDirection.LONG) {
            if (sl.compareTo(entry) >= 0) {
                throw new IllegalArgumentException("LONG stopLoss must be below entryPrice");
            }
            if (tp1.compareTo(entry) <= 0) {
                throw new IllegalArgumentException("LONG takeProfit1 must be above entryPrice");
            }
        } else {
            if (sl.compareTo(entry) <= 0) {
                throw new IllegalArgumentException("SHORT stopLoss must be above entryPrice");
            }
            if (tp1.compareTo(entry) >= 0) {
                throw new IllegalArgumentException("SHORT takeProfit1 must be below entryPrice");
            }
        }
    }

    private static BigDecimal normalize(BigDecimal price, Instrument instrument) {
        if (price == null) return null;
        BigDecimal tick = instrument.getTickSize();
        return price.divide(tick, 0, RoundingMode.HALF_UP)
            .multiply(tick)
            .setScale(tick.scale(), RoundingMode.HALF_UP);
    }

    public enum ManualDirection {
        LONG, SHORT;

        /**
         * Broker-side action token persisted on the execution row. MUST be "LONG"/"SHORT" — the
         * gateway maps the token with {@code "SHORT"||"SELL" → SELL, else BUY}, so the previous
         * "BUY"/"SELL" convention sent every manual/auto-arm SHORT to IBKR as a BUY.
         */
        public String action() {
            return name();
        }
    }

    public enum ManualEntryType {
        MARKET, LIMIT, STOP, STOP_LIMIT
    }

    public record ManualTradeRequest(
        ManualDirection direction,
        ManualEntryType entryType,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        Integer quantity,
        String brokerAccountId,
        Boolean submitImmediately,
        /** Stop trigger (breakout arm price) for STOP / STOP_LIMIT entries; null otherwise. Last for
         *  backward-compatible JSON + the pre-STOP constructors below. */
        BigDecimal triggerPrice
    ) {
        /** Legacy 7-arg shape (pre chart-trading) — no account / submit flag / trigger. */
        public ManualTradeRequest(ManualDirection direction, ManualEntryType entryType, BigDecimal entryPrice,
                                  BigDecimal stopLoss, BigDecimal takeProfit1, BigDecimal takeProfit2,
                                  Integer quantity) {
            this(direction, entryType, entryPrice, stopLoss, takeProfit1, takeProfit2, quantity, null, null, null);
        }

        /** 9-arg chart-trading shape (pre STOP/STOP_LIMIT) — no trigger. */
        public ManualTradeRequest(ManualDirection direction, ManualEntryType entryType, BigDecimal entryPrice,
                                  BigDecimal stopLoss, BigDecimal takeProfit1, BigDecimal takeProfit2,
                                  Integer quantity, String brokerAccountId, Boolean submitImmediately) {
            this(direction, entryType, entryPrice, stopLoss, takeProfit1, takeProfit2, quantity,
                brokerAccountId, submitImmediately, null);
        }
    }
}
