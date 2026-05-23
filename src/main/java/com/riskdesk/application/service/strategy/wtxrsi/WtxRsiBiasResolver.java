package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiBiasSource;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiConfig;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSwingBias;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiSwingBiasDetector;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Returns the swing bias for the latest closed bar using whichever engine
 * the config selects.
 *
 * <ul>
 *   <li>{@link WtxRsiBiasSource#FRACTAL_HH_HL} → delegates to
 *       {@link WtxRsiSwingBiasDetector} — pure-domain, no Spring deps.</li>
 *   <li>{@link WtxRsiBiasSource#SMC_ENGINE} → reads
 *       {@link SmcBiasSource#readSwingBias} (same source the WTx swing-bias
 *       path consumes — the production SMC structure engine). Falls back to
 *       FRACTAL_HH_HL when the SMC source returns empty (warm-up, missing
 *       bean, lookup failure).</li>
 * </ul>
 *
 * <p>A {@code null} / unknown SMC bias resolves to {@link WtxRsiSwingBias#NEUTRAL}
 * (passthrough by the filter) — same convention the WTx pipeline applies, so the
 * operator never sees a hard block during the first minutes of a session.</p>
 */
@Component
public class WtxRsiBiasResolver {

    private static final Logger log = LoggerFactory.getLogger(WtxRsiBiasResolver.class);

    private final SmcBiasSource smcBiasSource;

    public WtxRsiBiasResolver(SmcBiasSource smcBiasSource) {
        this.smcBiasSource = smcBiasSource;
    }

    public WtxRsiSwingBias resolve(
            Instrument instrument, String timeframe,
            List<Candle> candles, WtxRsiConfig config) {

        WtxRsiBiasSource source = config.biasSource() != null
                ? config.biasSource() : WtxRsiBiasSource.FRACTAL_HH_HL;

        return switch (source) {
            case FRACTAL_HH_HL -> fractalBias(candles, config);
            case SMC_ENGINE -> smcOrFallback(instrument, timeframe, candles, config);
        };
    }

    private WtxRsiSwingBias smcOrFallback(
            Instrument instrument, String timeframe,
            List<Candle> candles, WtxRsiConfig config) {
        Optional<String> smc = smcBiasSource.readSwingBias(instrument, timeframe);
        if (smc.isEmpty()) {
            log.debug("WTX-RSI [{} {}] SMC bias empty — falling back to FRACTAL_HH_HL",
                    instrument, timeframe);
            return fractalBias(candles, config);
        }
        return switch (smc.get().toUpperCase()) {
            case "BULLISH" -> WtxRsiSwingBias.BULLISH;
            case "BEARISH" -> WtxRsiSwingBias.BEARISH;
            default -> WtxRsiSwingBias.NEUTRAL;
        };
    }

    private static WtxRsiSwingBias fractalBias(List<Candle> candles, WtxRsiConfig config) {
        return WtxRsiSwingBiasDetector.detect(
                candles, config.fractalLeftRight(), config.fractalMaxLookback());
    }
}
