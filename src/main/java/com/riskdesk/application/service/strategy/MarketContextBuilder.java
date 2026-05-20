package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.indicators.MarketRegimeDetector;
import com.riskdesk.domain.engine.strategy.model.IndicatorContext;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MarketContext;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.engine.strategy.model.MtfSnapshot;
import com.riskdesk.domain.engine.strategy.model.PdZone;
import com.riskdesk.domain.engine.strategy.model.PortfolioState;
import com.riskdesk.domain.engine.strategy.model.PriceLocation;
import com.riskdesk.domain.engine.strategy.model.SessionInfo;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

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

    private static final Logger log = LoggerFactory.getLogger(MarketContextBuilder.class);

    /** Fraction of the VA width treated as "at POC". Tuned once; revisit per instrument later. */
    private static final double POC_TOLERANCE_PCT = 0.05;

    /**
     * How many recent candles to fetch for the regime fast-path. The detector itself
     * only needs {@code FAST_PATH_LOOKBACK} (= 6) entries, but we fetch a small
     * cushion so the most recent close is the live one and not a one-bar-stale anchor.
     */
    private static final int FAST_PATH_CANDLE_LIMIT = 10;

    private final MarketRegimeDetector regimeDetector = new MarketRegimeDetector();
    private final Clock clock;
    private final MtfSnapshotBuilder mtfBuilder;
    private final PortfolioStateBuilder portfolioBuilder;
    private final SessionInfoBuilder sessionBuilder;
    private final CandleRepositoryPort candleRepository;

    public MarketContextBuilder(Clock clock,
                                 MtfSnapshotBuilder mtfBuilder,
                                 PortfolioStateBuilder portfolioBuilder,
                                 SessionInfoBuilder sessionBuilder,
                                 CandleRepositoryPort candleRepository) {
        this.clock = clock;
        this.mtfBuilder = mtfBuilder;
        this.portfolioBuilder = portfolioBuilder;
        this.sessionBuilder = sessionBuilder;
        this.candleRepository = candleRepository;
    }

    public MarketContext build(Instrument instrument, String timeframe,
                                IndicatorSnapshot snapshot, BigDecimal atr) {
        MacroBias bias = MacroBias.fromSwingBias(snapshot.swingBias());
        List<BigDecimal> recentCloses = loadRecentCloses(instrument, timeframe);
        String detectorLabel = regimeDetector.detect(
            snapshot.ema9(), snapshot.ema50(), snapshot.ema200(),
            snapshot.bbTrendExpanding(), recentCloses, atr);
        MarketRegime regime = MarketRegime.fromDetectorLabel(detectorLabel);
        MacroBias momentumHint = momentumHintFrom(recentCloses, atr);
        PriceLocation loc = PriceLocation.of(
            snapshot.lastPrice(),
            snapshot.pocPrice(), snapshot.valueAreaHigh(), snapshot.valueAreaLow(),
            POC_TOLERANCE_PCT);
        PdZone pd = PdZone.fromLabel(snapshot.sessionPdZone());
        MtfSnapshot mtf = mtfBuilder.build(instrument, timeframe);
        PortfolioState portfolio = portfolioBuilder.build(instrument);
        SessionInfo session = sessionBuilder.build(instrument);
        IndicatorContext indicators = new IndicatorContext(
            snapshot.vwap(),
            snapshot.vwapLowerBand(),
            snapshot.vwapUpperBand(),
            snapshot.bbPct(),
            snapshot.bbWidth(),
            snapshot.cmf(),
            snapshot.chaikinOscillator()
        );
        Instant asOf = snapshot.lastCandleTimestamp() != null
            ? snapshot.lastCandleTimestamp()
            : clock.instant();

        return new MarketContext(
            instrument, timeframe, bias, regime, loc, pd,
            snapshot.lastPrice(), atr, mtf, portfolio, session, asOf, indicators,
            momentumHint
        );
    }

    /**
     * Loads the most recent closes in chronological (ascending — oldest first) order
     * for the regime fast-path. {@link CandleRepositoryPort#findRecentCandles} returns
     * descending order, so we reverse here.
     */
    private List<BigDecimal> loadRecentCloses(Instrument instrument, String timeframe) {
        try {
            List<Candle> candles = candleRepository.findRecentCandles(
                instrument, timeframe, FAST_PATH_CANDLE_LIMIT);
            if (candles == null || candles.isEmpty()) return List.of();
            List<Candle> ascending = new ArrayList<>(candles);
            Collections.reverse(ascending);
            List<BigDecimal> closes = new ArrayList<>(ascending.size());
            for (Candle c : ascending) {
                closes.add(c.getClose());
            }
            return closes;
        } catch (Exception e) {
            // Best-effort — fast-path is purely additive; on failure fall back to
            // legacy EMA/BB regime classification (the detector handles empty input).
            log.debug("Recent-closes load failed for {} {}: {}", instrument, timeframe, e.getMessage());
            return List.of();
        }
    }

    private MacroBias momentumHintFrom(List<BigDecimal> recentCloses, BigDecimal atr) {
        int dir = regimeDetector.fastPathDirection(recentCloses, atr);
        if (dir > 0) return MacroBias.BULL;
        if (dir < 0) return MacroBias.BEAR;
        return MacroBias.NEUTRAL;
    }
}
