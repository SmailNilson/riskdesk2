package com.riskdesk.infrastructure.persistence;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Position;
import com.riskdesk.domain.trading.port.PositionRepositoryPort;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter that bridges the domain port to the Spring Data repository.
 */
@Component
public class JpaPositionRepositoryAdapter implements PositionRepositoryPort {

    private final PositionRepository springDataRepo;

    public JpaPositionRepositoryAdapter(PositionRepository springDataRepo) {
        this.springDataRepo = springDataRepo;
    }

    @Override
    public Position save(Position position) {
        return PositionEntityMapper.toDomain(springDataRepo.save(PositionEntityMapper.toEntity(position)));
    }

    @Override
    public Optional<Position> findById(Long id) {
        return springDataRepo.findById(id).map(PositionEntityMapper::toDomain);
    }

    @Override
    public List<Position> findOpenPositions() {
        return springDataRepo.findByOpenTrue().stream()
            .map(PositionEntityMapper::toDomain)
            .toList();
    }

    @Override
    public List<Position> findOpenPositionsByInstrument(Instrument instrument) {
        return springDataRepo.findByOpenTrueAndInstrument(instrument).stream()
            .map(PositionEntityMapper::toDomain)
            .toList();
    }

    @Override
    public List<Position> findClosedPositions() {
        return springDataRepo.findByOpenFalseOrderByClosedAtDesc().stream()
            .map(PositionEntityMapper::toDomain)
            .toList();
    }

    @Override
    public BigDecimal totalUnrealizedPnL() {
        return springDataRepo.totalUnrealizedPnL();
    }

    @Override
    public BigDecimal todayRealizedPnL() {
        return springDataRepo.todayRealizedPnL();
    }

    @Override
    public long openPositionCount() {
        return springDataRepo.openPositionCount();
    }

    @Override
    public void deleteAll() {
        springDataRepo.deleteAll();
    }

    @Override
    public long count() {
        return springDataRepo.count();
    }
}
