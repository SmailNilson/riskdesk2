package com.riskdesk.integration;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.infrastructure.persistence.PositionRepository;
import com.riskdesk.infrastructure.persistence.PositionEntityMapper;
import com.riskdesk.infrastructure.persistence.entity.PositionEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class PositionRepositoryIntegrationTest {

    @Autowired
    private PositionRepository positionRepository;

    @BeforeEach
    void setUp() {
        positionRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private PositionEntity openPosition(Instrument inst, Side side, int qty, double entry, double unrealizedPnL) {
        PositionEntity p = PositionEntityMapper.toEntity(
            new com.riskdesk.domain.model.Position(inst, side, qty, new BigDecimal(String.valueOf(entry)))
        );
        p.setUnrealizedPnL(new BigDecimal(String.valueOf(unrealizedPnL)));
        return positionRepository.save(p);
    }

    private PositionEntity closedPosition(Instrument inst, Side side, int qty, double entry,
                                          double exitPrice, double realizedPnL, Instant closedAt) {
        PositionEntity p = PositionEntityMapper.toEntity(
            new com.riskdesk.domain.model.Position(inst, side, qty, new BigDecimal(String.valueOf(entry)))
        );
        p.setOpen(false);
        p.setClosedAt(closedAt);
        p.setRealizedPnL(new BigDecimal(String.valueOf(realizedPnL)));
        p.setUnrealizedPnL(BigDecimal.ZERO);
        p.setCurrentPrice(new BigDecimal(String.valueOf(exitPrice)));
        return positionRepository.save(p);
    }

    // -----------------------------------------------------------------------
    // save and findById
    // -----------------------------------------------------------------------

    @Test
    void saveAndFindById_returnsSamePosition() {
        PositionEntity saved = openPosition(Instrument.MCL, Side.LONG, 2, 63.50, 150.00);

        Optional<PositionEntity> found = positionRepository.findById(saved.getId());

        assertTrue(found.isPresent(), "Saved position should be found by ID");
        assertEquals(saved.getId(), found.get().getId());
        assertEquals(Instrument.MCL, found.get().getInstrument());
        assertEquals(Side.LONG, found.get().getSide());
        assertEquals(2, found.get().getQuantity());
        assertTrue(found.get().isOpen());
    }

    // -----------------------------------------------------------------------
    // findByOpenTrue
    // -----------------------------------------------------------------------

    @Test
    void findByOpenTrue_returnsOnlyOpenPositions() {
        openPosition(Instrument.MCL, Side.LONG, 1, 63.50, 100.00);
        openPosition(Instrument.MGC, Side.SHORT, 2, 2040.00, -50.00);
        closedPosition(Instrument.E6, Side.LONG, 1, 1.08200, 1.08500, 375.00, Instant.now());

        List<PositionEntity> openPositions = positionRepository.findByOpenTrue();

        assertEquals(2, openPositions.size(), "Should return only 2 open positions");
        assertTrue(openPositions.stream().allMatch(PositionEntity::isOpen),
                "All returned positions should be open");
    }

    // -----------------------------------------------------------------------
    // findByOpenFalseOrderByClosedAtDesc
    // -----------------------------------------------------------------------

    @Test
    void findByOpenFalseOrderByClosedAtDesc_returnsClosedPositionsInCorrectOrder() {
        Instant now = Instant.now();
        Instant twoHoursAgo = now.minus(2, ChronoUnit.HOURS);
        Instant fiveHoursAgo = now.minus(5, ChronoUnit.HOURS);

        closedPosition(Instrument.MCL, Side.SHORT, 2, 63.80, 62.40, 280.00, fiveHoursAgo);
        closedPosition(Instrument.MGC, Side.LONG, 1, 2050.00, 2038.00, -120.00, twoHoursAgo);
        closedPosition(Instrument.E6, Side.LONG, 1, 1.07850, 1.08450, 750.00, now);

        // Also add an open position that should NOT appear
        openPosition(Instrument.MNQ, Side.LONG, 1, 18100.00, 300.00);

        List<PositionEntity> closed = positionRepository.findByOpenFalseOrderByClosedAtDesc();

        assertEquals(3, closed.size(), "Should return 3 closed positions");
        assertTrue(closed.stream().noneMatch(PositionEntity::isOpen),
                "All returned positions should be closed");

        // Verify ordering: most recent closedAt first
        assertTrue(closed.get(0).getClosedAt().isAfter(closed.get(1).getClosedAt())
                        || closed.get(0).getClosedAt().equals(closed.get(1).getClosedAt()),
                "First position should have most recent closedAt");
        assertTrue(closed.get(1).getClosedAt().isAfter(closed.get(2).getClosedAt())
                        || closed.get(1).getClosedAt().equals(closed.get(2).getClosedAt()),
                "Second position should have more recent closedAt than third");
    }

    // -----------------------------------------------------------------------
    // totalUnrealizedPnL
    // -----------------------------------------------------------------------

    @Test
    void totalUnrealizedPnL_returnsSumOfOpenPositionsUnrealizedPnL() {
        openPosition(Instrument.MCL, Side.LONG, 1, 63.50, 100.00);
        openPosition(Instrument.MGC, Side.SHORT, 2, 2040.00, -50.00);
        openPosition(Instrument.MNQ, Side.LONG, 1, 18100.00, 200.00);

        // Also add a closed position whose unrealized PnL should NOT be counted
        closedPosition(Instrument.E6, Side.LONG, 1, 1.08200, 1.08500, 375.00, Instant.now());

        BigDecimal total = positionRepository.totalUnrealizedPnL();

        // 100 + (-50) + 200 = 250
        assertEquals(0, new BigDecimal("250.00").compareTo(total),
                "Total unrealized PnL should be 250.00 but was " + total);
    }

    // -----------------------------------------------------------------------
    // todayRealizedPnL
    // -----------------------------------------------------------------------

    @Test
    void todayRealizedPnL_returnsSumOfTodaysClosedPositionsRealizedPnL() {
        Instant now = Instant.now();
        Instant yesterday = now.minus(25, ChronoUnit.HOURS);

        // Today's closed positions
        closedPosition(Instrument.MCL, Side.SHORT, 2, 63.80, 62.40, 280.00, now);
        closedPosition(Instrument.E6, Side.LONG, 1, 1.07850, 1.08450, 750.00, now);

        // Yesterday's closed position — should NOT be included
        closedPosition(Instrument.MGC, Side.LONG, 1, 2050.00, 2038.00, -120.00, yesterday);

        // Open position — should NOT be included
        openPosition(Instrument.MNQ, Side.LONG, 1, 18100.00, 300.00);

        BigDecimal todayPnL = positionRepository.todayRealizedPnL();

        // 280 + 750 = 1030
        assertEquals(0, new BigDecimal("1030.00").compareTo(todayPnL),
                "Today's realized PnL should be 1030.00 but was " + todayPnL);
    }

    // -----------------------------------------------------------------------
    // openPositionCount
    // -----------------------------------------------------------------------

    @Test
    void openPositionCount_returnsCorrectCount() {
        openPosition(Instrument.MCL, Side.LONG, 1, 63.50, 100.00);
        openPosition(Instrument.MGC, Side.SHORT, 2, 2040.00, -50.00);
        closedPosition(Instrument.E6, Side.LONG, 1, 1.08200, 1.08500, 375.00, Instant.now());

        long count = positionRepository.openPositionCount();

        assertEquals(2, count, "Open position count should be 2");
    }
}
