package com.riskdesk.domain.engine.strategy.wtx;

import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session entry filter — blocks NEW entries during the Asia/overnight window (default 18:00 → 03:00 ET).
 * Boundaries are America/New_York and wrap past midnight, so the tests assert against ET wall-clock
 * and must hold across DST transitions (the UTC offset shifts but the ET boundary is stable).
 */
class WtxSessionFilterTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    /** 18:00 → 03:00 ET window enabled. */
    private static WtxConfig blocking() {
        return WtxConfig.defaults().withSessionFilter(true, 18 * 60, 3 * 60);
    }

    /** Instant at the given ET wall-clock on a date (DST handled by the zone). */
    private static Instant et(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), NY).toInstant();
    }

    @Test
    void disabledFilterNeverBlocks() {
        WtxConfig cfg = WtxConfig.defaults(); // session filter off by default
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(LocalDate.of(2026, 1, 15), 22, 0), cfg)).isFalse();
    }

    @Test
    void blocksInsideOvernightWindow() {
        WtxConfig cfg = blocking();
        LocalDate d = LocalDate.of(2026, 1, 15); // winter (EST)
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(d, 18, 0), cfg)).isTrue();   // start inclusive
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(d, 22, 30), cfg)).isTrue();  // mid-evening
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(d, 1, 0), cfg)).isTrue();    // past midnight
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(d, 2, 59), cfg)).isTrue();   // just before end
    }

    @Test
    void allowsLondonAndNySessions() {
        WtxConfig cfg = blocking();
        LocalDate d = LocalDate.of(2026, 1, 15);
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(d, 3, 0), cfg)).isFalse();   // end exclusive → tradeable
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(d, 4, 0), cfg)).isFalse();   // London
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(d, 10, 0), cfg)).isFalse();  // NY-AM
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(d, 15, 30), cfg)).isFalse(); // NY-PM
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(d, 17, 59), cfg)).isFalse(); // just before block
    }

    @Test
    void dstSpringForward_boundaryStableInEt() {
        WtxConfig cfg = blocking();
        // 2026 US DST spring-forward = Mar 8. 22:00 ET is blocked on both sides of the transition,
        // even though the UTC offset moved from -05:00 to -04:00.
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(LocalDate.of(2026, 3, 7), 22, 0), cfg)).isTrue();
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(LocalDate.of(2026, 3, 9), 22, 0), cfg)).isTrue();
        // 10:00 ET stays tradeable across the transition.
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(LocalDate.of(2026, 3, 9), 10, 0), cfg)).isFalse();
    }

    @Test
    void dstFallBack_boundaryStableInEt() {
        WtxConfig cfg = blocking();
        // 2026 US DST fall-back = Nov 1. 01:00 ET is inside the overnight block on both sides.
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(LocalDate.of(2026, 10, 31), 1, 0), cfg)).isTrue();
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(LocalDate.of(2026, 11, 2), 1, 0), cfg)).isTrue();
    }

    @Test
    void wrapWindowHelperHandlesMidnightCrossing() {
        WtxConfig cfg = blocking(); // 1080 → 180, wraps
        assertThat(cfg.isWithinSessionBlock(18 * 60)).isTrue();
        assertThat(cfg.isWithinSessionBlock(23 * 60)).isTrue();
        assertThat(cfg.isWithinSessionBlock(0)).isTrue();       // midnight
        assertThat(cfg.isWithinSessionBlock(3 * 60)).isFalse(); // end exclusive
        assertThat(cfg.isWithinSessionBlock(12 * 60)).isFalse();
    }

    @Test
    void propertiesParseEtMinutes() {
        assertThat(WtxStrategyProperties.parseEtMinutes("18:00", -1)).isEqualTo(1080);
        assertThat(WtxStrategyProperties.parseEtMinutes("03:00", -1)).isEqualTo(180);
        assertThat(WtxStrategyProperties.parseEtMinutes("09:30", -1)).isEqualTo(570);
        assertThat(WtxStrategyProperties.parseEtMinutes("bad", 42)).isEqualTo(42);     // malformed → default
        assertThat(WtxStrategyProperties.parseEtMinutes("25:00", 42)).isEqualTo(42);   // out of range → default
        assertThat(WtxStrategyProperties.parseEtMinutes(null, 42)).isEqualTo(42);      // null → default
    }

    @Test
    void liveConfigEnablesOvernightBlock() {
        WtxStrategyProperties props = new WtxStrategyProperties(); // prod defaults
        WtxConfig cfg = props.toConfig();
        assertThat(cfg.sessionFilterEnabled()).isTrue();
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(LocalDate.of(2026, 1, 15), 23, 0), cfg)).isTrue();
        assertThat(WtxRiskGuard.isEntryBlockedBySession(et(LocalDate.of(2026, 1, 15), 10, 0), cfg)).isFalse();
    }
}
