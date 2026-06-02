package com.riskdesk.infrastructure.quant.simulation.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import com.riskdesk.domain.quant.simulation.port.Quant7GatesSimulationRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * JPA-backed implementation of {@link Quant7GatesSimulationRepositoryPort}.
 *
 * <p>Upserts by the application-assigned id and maps rows back to the immutable
 * {@link Quant7GatesSimulation} domain record. Enum columns are parsed
 * defensively so a malformed legacy row can never break a full-history read.
 */
@Component
public class Quant7GatesSimulationJpaAdapter implements Quant7GatesSimulationRepositoryPort {

    private static final String OPEN = Quant7GatesSimulationStatus.OPEN.name();

    private final Quant7GatesSimulationJpaRepository repository;

    public Quant7GatesSimulationJpaAdapter(Quant7GatesSimulationJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(Quant7GatesSimulation s) {
        Quant7GatesSimulationEntity e = repository.findById(s.id())
            .orElseGet(() -> new Quant7GatesSimulationEntity(s.id()));
        e.setInstrument(s.instrument().name());
        e.setDirection(s.direction().name());
        e.setEntryPrice(s.entryPrice());
        e.setStopLoss(s.stopLoss());
        e.setTakeProfit1(s.takeProfit1());
        e.setTakeProfit2(s.takeProfit2());
        e.setOpenedAt(s.openedAt());
        e.setEntryReason(s.entryReason());
        e.setPriceSource(s.priceSource());
        e.setStatus(s.status().name());
        e.setExitPrice(s.exitPrice());
        e.setExitPriceSource(s.exitPriceSource());
        e.setClosedAt(s.closedAt());
        e.setExitReason(s.exitReason());
        e.setPnlPoints(s.pnlPoints());
        e.setPnlUsd(s.pnlUsd());
        repository.save(e);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Quant7GatesSimulation> findAllClosed() {
        return repository.findByStatusNot(OPEN).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<Quant7GatesSimulation> findAllOpen() {
        return repository.findByStatus(OPEN).stream().map(this::toDomain).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long maxId() {
        return repository.findMaxId();
    }

    // ── Mapping ────────────────────────────────────────────────────────────

    private Quant7GatesSimulation toDomain(Quant7GatesSimulationEntity e) {
        return new Quant7GatesSimulation(
            e.getId(),
            safeInstrument(e.getInstrument()),
            safeDirection(e.getDirection()),
            e.getEntryPrice(),
            e.getStopLoss(),
            e.getTakeProfit1(),
            e.getTakeProfit2(),
            e.getOpenedAt(),
            e.getEntryReason(),
            e.getPriceSource(),
            safeStatus(e.getStatus()),
            e.getExitPrice(),
            e.getExitPriceSource(),
            e.getClosedAt(),
            e.getExitReason(),
            e.getPnlPoints(),
            e.getPnlUsd()
        );
    }

    private Instrument safeInstrument(String s) {
        try { return Instrument.valueOf(s); } catch (RuntimeException e) { return Instrument.MCL; }
    }

    private Quant7GatesSimulation.Direction safeDirection(String s) {
        try { return Quant7GatesSimulation.Direction.valueOf(s); }
        catch (RuntimeException e) { return Quant7GatesSimulation.Direction.LONG; }
    }

    private Quant7GatesSimulationStatus safeStatus(String s) {
        try { return Quant7GatesSimulationStatus.valueOf(s); }
        catch (RuntimeException e) { return Quant7GatesSimulationStatus.OPEN; }
    }
}
