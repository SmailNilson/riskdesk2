package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiBiasSource;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiConfig;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSwingBias;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WtxRsiBiasResolverTest {

    @Test
    void smc_mode_uses_smc_bias_source() {
        StubSmcBiasSource stub = new StubSmcBiasSource(Optional.of("BULLISH"));
        WtxRsiBiasResolver resolver = new WtxRsiBiasResolver(stub);
        WtxRsiConfig cfg = withSource(WtxRsiConfig.defaults5m(), WtxRsiBiasSource.SMC_ENGINE);
        assertEquals(WtxRsiSwingBias.BULLISH,
                resolver.resolve(Instrument.MNQ, "5m", flatCandles(60), cfg));
        assertEquals(1, stub.calls, "SMC source should be queried in SMC mode");
    }

    @Test
    void smc_mode_falls_back_to_fractal_when_smc_returns_empty() {
        StubSmcBiasSource stub = new StubSmcBiasSource(Optional.empty()); // warm-up
        WtxRsiBiasResolver resolver = new WtxRsiBiasResolver(stub);
        WtxRsiConfig cfg = withSource(WtxRsiConfig.defaults5m(), WtxRsiBiasSource.SMC_ENGINE);
        // Flat candles → fractal detector returns NEUTRAL (no clear HH/HL).
        assertEquals(WtxRsiSwingBias.NEUTRAL,
                resolver.resolve(Instrument.MNQ, "5m", flatCandles(60), cfg));
    }

    @Test
    void smc_unknown_bias_string_maps_to_neutral() {
        StubSmcBiasSource stub = new StubSmcBiasSource(Optional.of("CONFUSED"));
        WtxRsiBiasResolver resolver = new WtxRsiBiasResolver(stub);
        WtxRsiConfig cfg = withSource(WtxRsiConfig.defaults5m(), WtxRsiBiasSource.SMC_ENGINE);
        assertEquals(WtxRsiSwingBias.NEUTRAL,
                resolver.resolve(Instrument.MNQ, "5m", flatCandles(60), cfg));
    }

    @Test
    void fractal_mode_never_calls_smc_source() {
        StubSmcBiasSource stub = new StubSmcBiasSource(Optional.of("BEARISH"));
        WtxRsiBiasResolver resolver = new WtxRsiBiasResolver(stub);
        WtxRsiConfig cfg = withSource(WtxRsiConfig.defaults5m(), WtxRsiBiasSource.FRACTAL_HH_HL);
        resolver.resolve(Instrument.MNQ, "5m", flatCandles(60), cfg);
        assertTrue(stub.calls == 0, "FRACTAL mode must not consult the SMC source");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Minimal stub since Mockito 3 can't mock the final IndicatorSnapshot record. */
    private static final class StubSmcBiasSource extends SmcBiasSource {
        private final Optional<String> bias;
        int calls = 0;
        StubSmcBiasSource(Optional<String> bias) {
            super(new NoopObjectProvider<>());
            this.bias = bias;
        }
        @Override
        public Optional<String> readSwingBias(Instrument instrument, String timeframe) {
            calls++;
            return bias;
        }
    }

    private static List<Candle> flatCandles(int n) {
        List<Candle> out = new ArrayList<>(n);
        Instant ts = Instant.parse("2025-01-02T14:30:00Z");
        for (int i = 0; i < n; i++) {
            out.add(new Candle(
                    Instrument.MNQ, "5m",
                    ts.plus(i * 5L, ChronoUnit.MINUTES),
                    BigDecimal.valueOf(100), BigDecimal.valueOf(101),
                    BigDecimal.valueOf(99),  BigDecimal.valueOf(100),
                    1000L));
        }
        return out;
    }

    private static WtxRsiConfig withSource(WtxRsiConfig base, WtxRsiBiasSource src) {
        return new WtxRsiConfig(
                base.wtN1(), base.wtN2(), base.wtSignalPeriod(),
                base.wtOverbought(), base.wtOversold(),
                base.rsiLength(), base.rsiSmaLength(),
                base.syncLookbackBars(),
                base.zoneMode(), base.zoneLookbackBars(),
                base.fractalLeftRight(), base.fractalMaxLookback(),
                base.swingBufferTicks(), base.tickSize(), base.tickValueUsd(),
                base.baseContracts(), base.confirmedMultiplier(),
                base.tpMode(), base.tpRMultiple(),
                base.chaikinFast(), base.chaikinSlow(), base.chaikinEnabled(),
                src
        );
    }
}
