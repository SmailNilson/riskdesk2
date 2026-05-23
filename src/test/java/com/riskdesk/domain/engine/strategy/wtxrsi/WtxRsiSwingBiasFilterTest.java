package com.riskdesk.domain.engine.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSwingBiasFilter.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WtxRsiSwingBiasFilterTest {

    @Test
    void aligned_long_signal_with_bullish_bias_is_allowed() {
        assertEquals(Decision.ALLOW_OPEN,
                WtxRsiSwingBiasFilter.evaluate(
                        WtxRsiSignal.Side.LONG, WtxRsiPosition.FLAT, WtxRsiSwingBias.BULLISH));
    }

    @Test
    void contradictory_long_signal_in_bearish_bias_is_suppressed() {
        assertEquals(Decision.SUPPRESS,
                WtxRsiSwingBiasFilter.evaluate(
                        WtxRsiSignal.Side.LONG, WtxRsiPosition.FLAT, WtxRsiSwingBias.BEARISH));
    }

    @Test
    void open_long_against_bearish_bias_is_force_closed() {
        assertEquals(Decision.FORCE_CLOSE_LONG,
                WtxRsiSwingBiasFilter.evaluate(
                        null, WtxRsiPosition.LONG, WtxRsiSwingBias.BEARISH));
    }

    @Test
    void open_short_against_bullish_bias_is_force_closed() {
        assertEquals(Decision.FORCE_CLOSE_SHORT,
                WtxRsiSwingBiasFilter.evaluate(
                        null, WtxRsiPosition.SHORT, WtxRsiSwingBias.BULLISH));
    }

    @Test
    void neutral_bias_is_passthrough() {
        // Fresh signal — allowed.
        assertEquals(Decision.ALLOW_OPEN,
                WtxRsiSwingBiasFilter.evaluate(
                        WtxRsiSignal.Side.SHORT, WtxRsiPosition.FLAT, WtxRsiSwingBias.NEUTRAL));
        // Open position — never force-closed.
        assertEquals(Decision.ALLOW_OPEN,
                WtxRsiSwingBiasFilter.evaluate(
                        null, WtxRsiPosition.LONG, WtxRsiSwingBias.NEUTRAL));
    }

    @Test
    void null_bias_is_passthrough() {
        assertEquals(Decision.ALLOW_OPEN,
                WtxRsiSwingBiasFilter.evaluate(
                        WtxRsiSignal.Side.LONG, WtxRsiPosition.FLAT, null));
    }

    @Test
    void aligned_long_signal_while_long_open_is_allowed() {
        assertEquals(Decision.ALLOW_OPEN,
                WtxRsiSwingBiasFilter.evaluate(
                        WtxRsiSignal.Side.LONG, WtxRsiPosition.LONG, WtxRsiSwingBias.BULLISH));
    }
}
