package com.riskdesk.domain.shared.vo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TimeframeTest {

    @Test
    void m10_label_returns10m() {
        assertEquals("10m", Timeframe.M10.label());
    }

    @Test
    void h1_minutes_returns60() {
        assertEquals(60, Timeframe.H1.minutes());
    }

    @Test
    void m10_periodSeconds_returns600() {
        assertEquals(600, Timeframe.M10.periodSeconds());
    }

    @Test
    void m1_minutes_returns1() {
        assertEquals(1, Timeframe.M1.minutes());
    }

    @Test
    void m5_minutes_returns5() {
        assertEquals(5, Timeframe.M5.minutes());
    }

    @Test
    void h4_minutes_returns240() {
        assertEquals(240, Timeframe.H4.minutes());
    }

    @Test
    void d1_minutes_returns1440() {
        assertEquals(1440, Timeframe.D1.minutes());
    }

    @Test
    void d1_periodSeconds_returns86400() {
        assertEquals(86400, Timeframe.D1.periodSeconds());
    }

    @Test
    void fromLabel_1h_returnsH1() {
        assertEquals(Timeframe.H1, Timeframe.fromLabel("1h"));
    }

    @Test
    void fromLabel_1m_returnsM1() {
        assertEquals(Timeframe.M1, Timeframe.fromLabel("1m"));
    }

    @Test
    void fromLabel_5m_returnsM5() {
        assertEquals(Timeframe.M5, Timeframe.fromLabel("5m"));
    }

    @Test
    void fromLabel_1d_returnsD1() {
        assertEquals(Timeframe.D1, Timeframe.fromLabel("1d"));
    }

    @Test
    void fromLabel_invalid_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Timeframe.fromLabel("invalid"));
    }

    @Test
    void fromLabel_null_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> Timeframe.fromLabel(null));
    }
}
