package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.engine.indicators.MarketRegimeDetector;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

/**
 * Builds a {@link MarketContext} from the live {@link IndicatorSnapshot}. Lives in
 * the application layer because it bridges infrastructure-adjacent snapshots and
 * the pure-domain engine inputs.
 *
 * <p><b>Caching</b>: not done here. The indicator service owns its own TTL cache;
 * this builder is a cheap transform on top of its output.
 */
@Component
public class MarketContextBuilder {

    /** Fraction of the VA width treated as "at POC". Tuned once; revisit per instrument later. */
    private static final double POC_TOLERANCE_PCT = 0.05;

    private final MarketRegimeDetector regimeDetector = new MarketRegimeDetector();
    private final Clock clock;

    public MarketContextBuilder(Clock clock) {
        this.clock = clock;
    }

    public MarketContext build(Instrument instrument, String timeframe,
                                IndicatorSnapshot snapshot, BigDecimal atr) {
        MacroBias bias = MacroBias.fromSwingBias(snapshot.swingBias());
        MarketRegime regime = MarketRegime.fromDetectorLabel(
            regimeDetector.detect(snapshot.ema9(), snapshot.ema50(), snapshot.ema200(),
                snapshot.bbTrendExpanding()));
        PriceLocation loc = PriceLocation.of(
            snapshot.lastPrice(),
            snapshot.pocPrice(), snapshot.valueAreaHigh(), snapshot.valueAreaLow(),
            POC_TOLERANCE_PCT);
        PdZone pd = PdZone.fromLabel(snapshot.sessionPdZone());
        Instant asOf = snapshot.lastCandleTimestamp() != null
            ? snapshot.lastCandleTimestamp()
            : clock.instant();

        return new MarketContext(
            instrument, timeframe, bias, regime, loc, pd,
            snapshot.lastPrice(), atr, asOf
        );
    }
}
