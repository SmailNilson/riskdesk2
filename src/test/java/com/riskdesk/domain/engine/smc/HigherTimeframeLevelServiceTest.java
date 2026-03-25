package com.riskdesk.domain.engine.smc;

import com.riskdesk.domain.engine.backtest.HigherTimeframeLevelService;
import com.riskdesk.domain.engine.backtest.MarketStructureService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class HigherTimeframeLevelServiceTest {

    private final HigherTimeframeLevelService service =
        new HigherTimeframeLevelService(new MarketStructureService());

    @Test
    void nearestSupport_prefersConfirmedLevelBelowClose() {
        HigherTimeframeLevelService.LevelIndex index = new HigherTimeframeLevelService.LevelIndex(
            List.of(
                new HigherTimeframeLevelService.HtfLevel(
                    HigherTimeframeLevelService.LevelType.SUPPORT,
                    BigDecimal.valueOf(100),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T02:00:00Z"),
                    "1h"
                ),
                new HigherTimeframeLevelService.HtfLevel(
                    HigherTimeframeLevelService.LevelType.SUPPORT,
                    BigDecimal.valueOf(98),
                    Instant.parse("2026-01-01T03:00:00Z"),
                    Instant.parse("2026-01-01T05:00:00Z"),
                    "1h"
                )
            ),
            List.of()
        );

        Optional<HigherTimeframeLevelService.LevelSelection> selection = service.nearestSupportAbove1H(
            index,
            Instant.parse("2026-01-01T06:00:00Z"),
            100.20,
            HigherTimeframeLevelService.ThresholdMode.POINTS,
            0.25,
            Double.NaN,
            0.25,
            0
        );

        assertTrue(selection.isPresent());
        assertEquals(100.0, selection.get().level().price().doubleValue());
    }

    @Test
    void nearestResistance_acceptsDistanceExactlyOnThreshold() {
        HigherTimeframeLevelService.LevelIndex index = new HigherTimeframeLevelService.LevelIndex(
            List.of(),
            List.of(
                new HigherTimeframeLevelService.HtfLevel(
                    HigherTimeframeLevelService.LevelType.RESISTANCE,
                    BigDecimal.valueOf(101),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T02:00:00Z"),
                    "1h"
                )
            )
        );

        Optional<HigherTimeframeLevelService.LevelSelection> selection = service.nearestResistanceAbove1H(
            index,
            Instant.parse("2026-01-01T03:00:00Z"),
            100.75,
            HigherTimeframeLevelService.ThresholdMode.POINTS,
            0.25,
            Double.NaN,
            0.25,
            0
        );

        assertTrue(selection.isPresent());
        assertEquals(0.25, selection.get().distance(), 1e-9);
    }

    @Test
    void nearestSupport_rejectsLevelThatIsTooOldWhenMaxAgeIsConfigured() {
        HigherTimeframeLevelService.LevelIndex index = new HigherTimeframeLevelService.LevelIndex(
            List.of(
                new HigherTimeframeLevelService.HtfLevel(
                    HigherTimeframeLevelService.LevelType.SUPPORT,
                    BigDecimal.valueOf(100),
                    Instant.parse("2026-01-01T00:00:00Z"),
                    Instant.parse("2026-01-01T01:00:00Z"),
                    "1h"
                )
            ),
            List.of()
        );

        Optional<HigherTimeframeLevelService.LevelSelection> selection = service.nearestSupportAbove1H(
            index,
            Instant.parse("2026-01-05T01:00:00Z"),
            100.10,
            HigherTimeframeLevelService.ThresholdMode.POINTS,
            0.25,
            Double.NaN,
            0.25,
            24
        );

        assertTrue(selection.isEmpty());
    }
}
