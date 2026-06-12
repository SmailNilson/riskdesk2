package com.riskdesk.application.service;

import com.riskdesk.application.dto.CvdDivergencePaperTradeView;
import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.event.CvdDivergenceDetected;
import com.riskdesk.domain.orderflow.model.CvdDivergenceSignal;
import com.riskdesk.domain.shared.TradingSessionResolver;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import com.riskdesk.infrastructure.persistence.JpaCvdDivergencePaperTradeRepository;
import com.riskdesk.infrastructure.persistence.entity.CvdDivergencePaperTradeEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RTH-gated paper-trading loop on CVD pivot divergences (UC-OF-CVD-PAPER).
 *
 * <p>Rule under test: when a divergence fires inside the NY cash session
 * (09:30–16:00 ET), open a simulated position in the divergence direction
 * (bearish → SHORT, bullish → LONG) at the last traded price. Same-direction
 * events refresh the hold window (mirrors the frontend DIV badge refresh);
 * an opposite divergence closes and flips. The position is closed when the
 * badge window ({@code riskdesk.order-flow.cvd.paper-hold-seconds}) lapses
 * with no refresh, or when RTH ends — whichever comes first.</p>
 *
 * <p><b>Pure simulation.</b> Fills are at last price with no spread or slippage
 * model, sized at one contract. This service never touches the execution path —
 * its only output is the {@code cvd_divergence_paper_trades} table, which exists
 * to measure whether the signal has an edge before any real wiring is considered.</p>
 */
@Service
public class CvdDivergencePaperTradingService {

    private static final Logger log = LoggerFactory.getLogger(CvdDivergencePaperTradingService.class);

    private final JpaCvdDivergencePaperTradeRepository repository;
    private final ObjectProvider<TickDataPort> tickDataPortProvider;
    private final OrderFlowProperties properties;

    private Clock clock = Clock.systemUTC();

    public CvdDivergencePaperTradingService(JpaCvdDivergencePaperTradeRepository repository,
                                            ObjectProvider<TickDataPort> tickDataPortProvider,
                                            OrderFlowProperties properties) {
        this.repository = repository;
        this.tickDataPortProvider = tickDataPortProvider;
        this.properties = properties;
    }

    void setClock(Clock clock) {
        this.clock = clock;
    }

    @EventListener
    @Transactional
    public void onCvdDivergence(CvdDivergenceDetected event) {
        if (!properties.getCvd().isPaperTradingEnabled()) {
            return;
        }
        if (Double.isNaN(event.lastPrice())) {
            return;
        }
        try {
            handleSignal(event);
        } catch (Exception e) {
            log.warn("CVD paper trading failed to handle divergence for {}: {}",
                     event.instrument(), e.getMessage());
        }
    }

    private void handleSignal(CvdDivergenceDetected event) {
        boolean rth = TradingSessionResolver.isWithinRth(event.timestamp());
        String direction = CvdDivergenceSignal.BEARISH.equals(event.signal().type()) ? "SHORT" : "LONG";

        var open = repository.findFirstByInstrumentAndStatusOrderByEntryTimeDesc(
            event.instrument(), CvdDivergencePaperTradeEntity.STATUS_OPEN);

        if (open.isPresent()) {
            CvdDivergencePaperTradeEntity trade = open.get();
            if (trade.getDirection().equals(direction)) {
                // Badge refresh — extend the hold window, but never from outside RTH.
                if (rth) {
                    trade.refreshSignal(event.timestamp());
                    repository.save(trade);
                }
                return;
            }
            closeTrade(trade, event.timestamp(), event.lastPrice(),
                       CvdDivergencePaperTradeEntity.REASON_FLIPPED);
        }

        if (rth) {
            CvdDivergenceSignal s = event.signal();
            CvdDivergencePaperTradeEntity trade = new CvdDivergencePaperTradeEntity(
                event.instrument(), direction, s.type(),
                event.timestamp(), event.lastPrice(),
                s.prevPivotPrice(), s.newPivotPrice(),
                s.prevPivotCvd(), s.newPivotCvd(), s.pivotTimestamp());
            repository.save(trade);
            log.info("CVD paper trade OPEN: {} {} @ {} ({})",
                     event.instrument(), direction, event.lastPrice(), s.type());
        }
    }

    /**
     * Closes open paper trades whose badge window lapsed without a refresh, or
     * that outlived the NY session. Skips (and retries next pass) when no live
     * price is available — a paper fill at a stale or absent price would lie.
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void closeExpiredTrades() {
        if (!properties.getCvd().isPaperTradingEnabled()) {
            return;
        }
        List<CvdDivergencePaperTradeEntity> open =
            repository.findByStatus(CvdDivergencePaperTradeEntity.STATUS_OPEN);
        if (open.isEmpty()) {
            return;
        }
        Instant now = Instant.now(clock);
        int holdSeconds = properties.getCvd().getPaperHoldSeconds();
        for (CvdDivergencePaperTradeEntity trade : open) {
            boolean expired = now.isAfter(trade.getLastSignalTime().plusSeconds(holdSeconds));
            boolean sessionEnded = !TradingSessionResolver.isWithinRth(now);
            if (!expired && !sessionEnded) {
                continue;
            }
            double price = currentPrice(trade.getInstrument());
            if (Double.isNaN(price)) {
                log.debug("CVD paper trade {} close deferred — no live price for {}",
                          trade.getId(), trade.getInstrument());
                continue;
            }
            closeTrade(trade, now, price,
                       expired ? CvdDivergencePaperTradeEntity.REASON_BADGE_EXPIRED
                               : CvdDivergencePaperTradeEntity.REASON_SESSION_END);
        }
    }

    private void closeTrade(CvdDivergencePaperTradeEntity trade, Instant exitTime,
                            double exitPrice, String reason) {
        double sign = "LONG".equals(trade.getDirection()) ? 1.0 : -1.0;
        double pnlPoints = (exitPrice - trade.getEntryPrice()) * sign;
        double currencyPerPoint = trade.getInstrument().getTickValue().doubleValue()
            / trade.getInstrument().getTickSize().doubleValue();
        double pnlCurrency = pnlPoints * currencyPerPoint;
        trade.close(exitTime, exitPrice, reason, pnlPoints, pnlCurrency);
        repository.save(trade);
        log.info("CVD paper trade CLOSED ({}): {} {} {} -> {} = {} pts ({} {})",
                 reason, trade.getInstrument(), trade.getDirection(),
                 trade.getEntryPrice(), exitPrice, pnlPoints, pnlCurrency, "USD");
    }

    private double currentPrice(Instrument instrument) {
        TickDataPort port = tickDataPortProvider.getIfAvailable();
        if (port == null) {
            return Double.NaN;
        }
        return port.currentAggregationReadOnly(instrument)
            .map(TickAggregation::lastPrice)
            .orElse(Double.NaN);
    }

    /** Recent paper trades plus aggregate stats for the REST endpoint. */
    @Transactional(readOnly = true)
    public Map<String, Object> recentTradesWithStats(Instrument instrument, int days) {
        Instant since = Instant.now(clock).minus(days, ChronoUnit.DAYS);
        List<CvdDivergencePaperTradeEntity> trades =
            repository.findByInstrumentAndEntryTimeAfterOrderByEntryTimeDesc(instrument, since);

        List<CvdDivergencePaperTradeView> views = trades.stream().map(t ->
            new CvdDivergencePaperTradeView(t.getId(), t.getInstrument().name(), t.getDirection(),
                t.getDivergenceType(), t.getEntryTime(), t.getEntryPrice(), t.getLastSignalTime(),
                t.getStatus(), t.getExitTime(), t.getExitPrice(), t.getCloseReason(),
                t.getPnlPoints(), t.getPnlCurrency())).toList();

        Map<String, Object> stats = new LinkedHashMap<>();
        List<CvdDivergencePaperTradeEntity> closed = trades.stream()
            .filter(t -> CvdDivergencePaperTradeEntity.STATUS_CLOSED.equals(t.getStatus()))
            .toList();
        long wins = closed.stream().filter(t -> t.getPnlPoints() != null && t.getPnlPoints() > 0).count();
        long losses = closed.stream().filter(t -> t.getPnlPoints() != null && t.getPnlPoints() < 0).count();
        double totalPoints = closed.stream()
            .filter(t -> t.getPnlPoints() != null).mapToDouble(CvdDivergencePaperTradeEntity::getPnlPoints).sum();
        double totalCurrency = closed.stream()
            .filter(t -> t.getPnlCurrency() != null).mapToDouble(CvdDivergencePaperTradeEntity::getPnlCurrency).sum();
        stats.put("total", trades.size());
        stats.put("open", trades.size() - closed.size());
        stats.put("closed", closed.size());
        stats.put("wins", wins);
        stats.put("losses", losses);
        stats.put("winRatePct", closed.isEmpty() ? null
            : Math.round(wins * 1000.0 / closed.size()) / 10.0);
        stats.put("totalPnlPoints", totalPoints);
        stats.put("totalPnlCurrency", totalCurrency);
        for (String dir : List.of("LONG", "SHORT")) {
            List<CvdDivergencePaperTradeEntity> ofDir = closed.stream()
                .filter(t -> dir.equals(t.getDirection())).toList();
            Map<String, Object> dirStats = new LinkedHashMap<>();
            dirStats.put("closed", ofDir.size());
            dirStats.put("wins", ofDir.stream()
                .filter(t -> t.getPnlPoints() != null && t.getPnlPoints() > 0).count());
            dirStats.put("totalPnlPoints", ofDir.stream()
                .filter(t -> t.getPnlPoints() != null)
                .mapToDouble(CvdDivergencePaperTradeEntity::getPnlPoints).sum());
            stats.put(dir, dirStats);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("instrument", instrument.name());
        result.put("days", days);
        result.put("stats", stats);
        result.put("trades", views);
        return result;
    }
}
