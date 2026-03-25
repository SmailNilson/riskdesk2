package com.riskdesk.domain.engine.backtest;

import com.riskdesk.application.service.EntryFilterService;
import com.riskdesk.application.service.HigherTimeframeLevelService;
import com.riskdesk.application.service.MarketStructureService;
import com.riskdesk.domain.engine.smc.MarketStructure;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EntryFilterServiceTest {

    private final HigherTimeframeLevelService levelService =
        new HigherTimeframeLevelService(new MarketStructureService());
    private final EntryFilterService entryFilterService = new EntryFilterService(levelService);

    @Test
    void longRejectedWhenNoHtfSupportFound() {
        EntryFilterService.Decision decision = entryFilterService.evaluate(
            EntryFilterService.Direction.LONG,
            candle("2026-01-01T10:00:00Z", 100),
            4.0,
            config(false, false),
            HigherTimeframeLevelService.LevelIndex.empty(),
            MarketStructureService.StructureContextIndex.empty()
        );

        assertFalse(decision.accepted());
        assertTrue(decision.reason().contains("no confirmed HTF support"));
    }

    @Test
    void longRejectedWhenBullishStructureIsRequiredButMissing() {
        HigherTimeframeLevelService.LevelIndex index = new HigherTimeframeLevelService.LevelIndex(
            List.of(
                new HigherTimeframeLevelService.HtfLevel(
                    HigherTimeframeLevelService.LevelType.SUPPORT,
                    BigDecimal.valueOf(100),
                    Instant.parse("2026-01-01T08:00:00Z"),
                    Instant.parse("2026-01-01T09:00:00Z"),
                    "1h"
                )
            ),
            List.of()
        );

        EntryFilterService.Decision decision = entryFilterService.evaluate(
            EntryFilterService.Direction.LONG,
            candle("2026-01-01T10:00:00Z", 100.10),
            4.0,
            config(true, false),
            index,
            new MarketStructureService.StructureContextIndex(List.of(
                new MarketStructureService.StructureEvent(
                    MarketStructure.StructureType.BOS,
                    MarketStructure.Trend.BEARISH,
                    BigDecimal.valueOf(99),
                    5,
                    Instant.parse("2026-01-01T09:30:00Z"),
                    new MarketStructureService.ConfirmedSwing(
                        MarketStructure.SwingType.LOW,
                        BigDecimal.valueOf(99),
                        2,
                        Instant.parse("2026-01-01T07:00:00Z"),
                        Instant.parse("2026-01-01T09:00:00Z"),
                        "1h"
                    ),
                    "1h"
                )
            ))
        );

        assertFalse(decision.accepted());
        assertTrue(decision.reason().contains("structure context"));
    }

    @Test
    void shortAcceptedNearResistanceWithBearishStructure() {
        HigherTimeframeLevelService.LevelIndex index = new HigherTimeframeLevelService.LevelIndex(
            List.of(),
            List.of(
                new HigherTimeframeLevelService.HtfLevel(
                    HigherTimeframeLevelService.LevelType.RESISTANCE,
                    BigDecimal.valueOf(105),
                    Instant.parse("2026-01-01T08:00:00Z"),
                    Instant.parse("2026-01-01T09:00:00Z"),
                    "1h"
                )
            )
        );

        EntryFilterService.Decision decision = entryFilterService.evaluate(
            EntryFilterService.Direction.SHORT,
            candle("2026-01-01T10:00:00Z", 104.90),
            4.0,
            config(false, true),
            index,
            new MarketStructureService.StructureContextIndex(List.of(
                new MarketStructureService.StructureEvent(
                    MarketStructure.StructureType.CHOCH,
                    MarketStructure.Trend.BEARISH,
                    BigDecimal.valueOf(103),
                    5,
                    Instant.parse("2026-01-01T09:30:00Z"),
                    new MarketStructureService.ConfirmedSwing(
                        MarketStructure.SwingType.LOW,
                        BigDecimal.valueOf(103),
                        2,
                        Instant.parse("2026-01-01T07:00:00Z"),
                        Instant.parse("2026-01-01T09:00:00Z"),
                        "1h"
                    ),
                    "1h"
                )
            ))
        );

        assertTrue(decision.accepted());
        assertTrue(decision.reason().contains("accepted"));
        assertEquals(2, decision.score());
    }

    private static EntryFilterService.Config config(boolean requireBullishLong, boolean requireBearishShort) {
        return new EntryFilterService.Config(
            true,
            true,
            HigherTimeframeLevelService.ThresholdMode.ATR,
            0.25,
            14,
            0.25,
            requireBullishLong,
            requireBearishShort,
            true,
            true,
            false,
            1,
            0,
            true
        );
    }

    private static Candle candle(String ts, double close) {
        return new Candle(
            Instrument.MNQ,
            "1h",
            Instant.parse(ts),
            BigDecimal.valueOf(close),
            BigDecimal.valueOf(close + 0.5),
            BigDecimal.valueOf(close - 0.5),
            BigDecimal.valueOf(close),
            1000
        );
    }
}
