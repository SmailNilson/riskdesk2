package com.riskdesk.application.service;

import com.riskdesk.application.dto.ClosePositionCommand;
import com.riskdesk.application.dto.CreatePositionCommand;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.PortfolioSummary;
import com.riskdesk.application.dto.PositionView;
import com.riskdesk.domain.model.*;
import com.riskdesk.domain.shared.vo.Money;
import com.riskdesk.domain.trading.aggregate.Portfolio;
import com.riskdesk.domain.trading.event.PositionClosed;
import com.riskdesk.domain.trading.event.PositionOpened;
import com.riskdesk.domain.trading.event.PositionPnLUpdated;
import com.riskdesk.domain.trading.port.PositionRepositoryPort;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Service
public class PositionService {

    private final PositionRepositoryPort positionPort;
    private final ApplicationEventPublisher eventPublisher;
    private final Optional<IbkrPortfolioService> ibkrPortfolioService;

    // Configurable — total account margin (will be user-configurable later)
    private BigDecimal accountMargin = new BigDecimal("25000");

    public PositionService(PositionRepositoryPort positionPort,
                           ApplicationEventPublisher eventPublisher,
                           Optional<IbkrPortfolioService> ibkrPortfolioService) {
        this.positionPort    = positionPort;
        this.eventPublisher  = eventPublisher;
        this.ibkrPortfolioService = ibkrPortfolioService;
    }

    @Transactional
    public Position openPosition(CreatePositionCommand req) {
        Position pos = new Position(req.instrument(), req.side(), req.quantity(), req.entryPrice());
        pos.setStopLoss(req.stopLoss());
        pos.setTakeProfit(req.takeProfit());
        pos.setNotes(req.notes());
        pos.setCurrentPrice(req.entryPrice());
        pos.setUnrealizedPnL(BigDecimal.ZERO);
        Position saved = positionPort.save(pos);
        eventPublisher.publishEvent(new PositionOpened(
                saved.getId(), saved.getInstrument().name(), saved.getSide().name(),
                saved.getQuantity(), saved.getEntryPrice(), Instant.now()));
        return saved;
    }

    @Transactional
    public Position closePosition(Long id, ClosePositionCommand req) {
        Position pos = positionPort.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Position not found: " + id));
        if (!pos.isOpen()) {
            throw new IllegalStateException("Position already closed: " + id);
        }
        pos.close(req.exitPrice());
        Position saved = positionPort.save(pos);
        eventPublisher.publishEvent(new PositionClosed(
                saved.getId(), saved.getInstrument().name(),
                req.exitPrice(), saved.getRealizedPnL(), Instant.now()));
        return saved;
    }

    @Transactional
    public void updateMarketPrice(Instrument instrument, BigDecimal price) {
        List<Position> positions = positionPort.findOpenPositionsByInstrument(instrument);
        for (Position pos : positions) {
            pos.updatePnL(price);
            positionPort.save(pos);
            eventPublisher.publishEvent(new PositionPnLUpdated(
                    pos.getId(), instrument.name(), price, pos.getUnrealizedPnL(), Instant.now()));
        }
    }

    /**
     * Assembles the Portfolio aggregate for risk evaluation.
     */
    public Portfolio getPortfolio() {
        return new Portfolio(Money.of(accountMargin), positionPort.findOpenPositions());
    }

    public PortfolioSummary getPortfolioSummary() {
        return getPortfolioSummary(null);
    }

    public PortfolioSummary getPortfolioSummary(String accountId) {
        Optional<IbkrPortfolioSnapshot> ibkrSnapshot = loadIbkrSnapshot(accountId);
        if (ibkrSnapshot.isPresent()) {
            return mapIbkrSummary(ibkrSnapshot.get());
        }
        return getLocalPortfolioSummary();
    }

    private PortfolioSummary getLocalPortfolioSummary() {
        List<Position> openPositions = positionPort.findOpenPositions();
        BigDecimal unrealizedPnL = positionPort.totalUnrealizedPnL();
        BigDecimal realizedPnL   = positionPort.todayRealizedPnL();
        long count               = positionPort.openPositionCount();

        BigDecimal totalExposure = openPositions.stream()
                .map(p -> p.getEntryPrice()
                        .multiply(p.getInstrument().getContractMultiplier())
                        .multiply(BigDecimal.valueOf(p.getQuantity())))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        BigDecimal marginUsedPct = accountMargin.compareTo(BigDecimal.ZERO) > 0
                ? totalExposure.divide(accountMargin, 4, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100)).setScale(1, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        List<PositionView> views = openPositions.stream().map(PositionView::from).toList();

        return new PortfolioSummary(
                unrealizedPnL, realizedPnL,
                unrealizedPnL.add(realizedPnL),
                count, totalExposure, marginUsedPct, views);
    }

    public List<PositionView> getOpenPositions() {
        return getOpenPositions(null);
    }

    public List<PositionView> getOpenPositions(String accountId) {
        Optional<IbkrPortfolioSnapshot> ibkrSnapshot = loadIbkrSnapshot(accountId);
        if (ibkrSnapshot.isPresent()) {
            return ibkrSnapshot.get().positions().stream().map(PositionView::fromIbkr).toList();
        }
        return positionPort.findOpenPositions().stream().map(PositionView::from).toList();
    }

    public List<PositionView> getClosedPositions() {
        return positionPort.findClosedPositions().stream().map(PositionView::from).toList();
    }

    public void setAccountMargin(BigDecimal margin) {
        this.accountMargin = margin;
    }

    private Optional<IbkrPortfolioSnapshot> loadIbkrSnapshot(String accountId) {
        if (ibkrPortfolioService.isEmpty()) {
            return Optional.empty();
        }
        IbkrPortfolioSnapshot snapshot = ibkrPortfolioService.get().getPortfolio(accountId);
        return snapshot.connected() ? Optional.of(snapshot) : Optional.empty();
    }

    private PortfolioSummary mapIbkrSummary(IbkrPortfolioSnapshot snapshot) {
        BigDecimal totalPnL = snapshot.totalUnrealizedPnl().add(snapshot.totalRealizedPnl()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal marginUsedPct = BigDecimal.ZERO;
        if (snapshot.netLiquidation().compareTo(BigDecimal.ZERO) > 0) {
            marginUsedPct = snapshot.initMarginReq()
                .divide(snapshot.netLiquidation(), 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
        }

        return new PortfolioSummary(
            snapshot.totalUnrealizedPnl(),
            snapshot.totalRealizedPnl(),
            totalPnL,
            snapshot.positions().size(),
            snapshot.grossPositionValue(),
            marginUsedPct,
            snapshot.positions().stream().map(PositionView::fromIbkr).toList()
        );
    }
}
