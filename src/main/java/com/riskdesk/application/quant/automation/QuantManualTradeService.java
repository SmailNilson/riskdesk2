package com.riskdesk.application.quant.automation;

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
        if (entryType == ManualEntryType.LIMIT && req.entryPrice() == null) {
            throw new IllegalArgumentException("entryPrice is required for LIMIT orders");
        }
        if (req.stopLoss() == null) {
            throw new IllegalArgumentException("stopLoss is required");
        }
        if (req.takeProfit1() == null) {
            throw new IllegalArgumentException("takeProfit1 is required");
        }
        int quantity = req.quantity() == null || req.quantity() < 1 ? 1 : req.quantity();

        BigDecimal entryPrice = entryType == ManualEntryType.MARKET
            ? resolveLivePrice(instrument)
            : req.entryPrice();

        if (entryPrice == null) {
            throw new IllegalStateException("Cannot resolve a live price for MARKET order on " + instrument
                + " — no recent quant snapshot. Use LIMIT and supply an entryPrice.");
        }

        validatePlanGeometry(req.direction(), entryPrice, req.stopLoss(), req.takeProfit1());

        String brokerAccount = autoArmProps.getBrokerAccountId();
        if (brokerAccount == null || brokerAccount.isBlank()) {
            throw new IllegalStateException(
                "riskdesk.quant.auto-arm.broker-account-id is required to place manual trades");
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
        candidate.setNormalizedEntryPrice(normalize(entryPrice, instrument));
        candidate.setVirtualStopLoss(normalize(req.stopLoss(), instrument));
        candidate.setVirtualTakeProfit(normalize(req.takeProfit1(), instrument));
        candidate.setCreatedAt(now);
        candidate.setUpdatedAt(now);

        TradeExecutionRecord persisted = tradeExecutionRepository.createIfAbsent(candidate);

        log.info("manual-trade created instrument={} direction={} entryType={} entry={} sl={} tp1={} qty={} executionId={}",
            instrument, req.direction(), entryType, persisted.getNormalizedEntryPrice(),
            persisted.getVirtualStopLoss(), persisted.getVirtualTakeProfit(), quantity, persisted.getId());

        if (entryType == ManualEntryType.MARKET) {
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
        LONG("BUY"), SHORT("SELL");

        private final String action;

        ManualDirection(String action) {
            this.action = action;
        }

        public String action() {
            return action;
        }
    }

    public enum ManualEntryType {
        MARKET, LIMIT
    }

    public record ManualTradeRequest(
        ManualDirection direction,
        ManualEntryType entryType,
        BigDecimal entryPrice,
        BigDecimal stopLoss,
        BigDecimal takeProfit1,
        BigDecimal takeProfit2,
        Integer quantity
    ) {
    }
}
