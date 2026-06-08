package com.riskdesk.application.service.strategy;

import com.riskdesk.application.service.strategy.wtxrsi.CandleResampler;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.backtest.BacktestDataInspector;
import com.riskdesk.domain.engine.backtest.BacktestResult;
import com.riskdesk.domain.engine.backtest.WtxReplayBacktest;
import com.riskdesk.domain.engine.backtest.WtxReplayBacktest.BarSlice;
import com.riskdesk.domain.engine.indicators.EMAIndicator;
import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.domain.engine.strategy.wtx.WtxHtfBiasFilter;
import com.riskdesk.domain.engine.strategy.wtx.WtxProfile;
import com.riskdesk.domain.engine.strategy.wtx.WtxTrailingMode;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates the FAITHFUL WTX backtest — the one that reuses the live evaluators (see
 * {@link WtxReplayBacktest}) so a take-profit A/B reflects the live HTF / SL_ONLY strategy, unlike the
 * divergent {@code WaveTrendBacktest}.
 *
 * <p>Pipeline: load 1m candles → resample to the signal timeframe (and to the HTF timeframe for the bias) →
 * attach each signal bar's constituent 1m candles (intrabar exits) + its as-of HTF-bias context → merge the
 * {@code application.properties} config with the request overrides → run the replay engine.</p>
 */
@Service
public class WtxBacktestService {

    private static final Logger log = LoggerFactory.getLogger(WtxBacktestService.class);

    private final CandleRepositoryPort candleRepository;
    private final WtxStrategyProperties properties;

    public WtxBacktestService(CandleRepositoryPort candleRepository, WtxStrategyProperties properties) {
        this.candleRepository = candleRepository;
        this.properties = properties;
    }

    public BacktestResult run(WtxBacktestRequest request) {
        Instrument instrument = Instrument.valueOf(request.instrument());
        String timeframe = request.timeframe() != null ? request.timeframe() : "10m";
        WtxProfile profile = request.profile() != null ? request.profile() : WtxProfile.HTF;
        double pointValue = request.pointValue() != null ? request.pointValue() : 2.0;
        double startEquity = request.startEquity() != null
                ? request.startEquity() : properties.getInitialEquity().doubleValue();

        // 1m is the canonical base; everything else is resampled in memory.
        List<Candle> oneMinute = candleRepository.findCandlesBetween(
                instrument, "1m", request.from(), request.to());
        log.info("WTX faithful backtest: loaded {} 1m candles for {} {} → {}",
                oneMinute.size(), request.instrument(), request.from(), request.to());

        WtxConfig config = mergeConfig(request);

        List<Candle> signalBars = CandleResampler.resample(oneMinute, timeframe);
        Map<Long, List<Candle>> minutesByBucket = groupByBucket(oneMinute, seconds(timeframe));
        List<BarSlice> slices = buildSlices(signalBars, minutesByBucket, oneMinute, config, profile);

        WtxReplayBacktest engine = new WtxReplayBacktest(
                config, profile, properties.getAtrLength(), pointValue, startEquity);
        return engine.run(request.instrument(), timeframe, slices);
    }

    /** Merge properties defaults with the request overrides. */
    private WtxConfig mergeConfig(WtxBacktestRequest req) {
        WtxConfig c = properties.toConfig();
        if (req.n1() != null || req.n2() != null || req.signalPeriod() != null) {
            c = c.withIndicatorParams(
                    req.n1() != null ? req.n1() : c.n1(),
                    req.n2() != null ? req.n2() : c.n2(),
                    req.signalPeriod() != null ? req.signalPeriod() : c.signalPeriod());
        }
        if (req.slAtrMult() != null) {
            c = c.withSlAtrMult(req.slAtrMult());
        }
        // Trailing mode override (keeps the configured distances; SL_ONLY ignores them).
        WtxTrailingMode mode = req.trailingMode() != null ? req.trailingMode() : c.trailingMode();
        c = c.withTrailing(mode, c.trailingActivationPoints(), c.trailingPoints(), c.slPoints());
        // The lever under test.
        boolean tpEnabled = Boolean.TRUE.equals(req.takeProfitEnabled());
        c = c.withTakeProfit(tpEnabled, req.tpPoints() != null ? req.tpPoints() : c.tpPoints());
        return c;
    }

    /** Group 1m candles by their {@code timeframe}-bucket start epoch (same bucketing as CandleResampler). */
    private static Map<Long, List<Candle>> groupByBucket(List<Candle> oneMinute, long bucketSecs) {
        Map<Long, List<Candle>> map = new HashMap<>();
        for (Candle m : oneMinute) {
            long start = (m.getTimestamp().getEpochSecond() / bucketSecs) * bucketSecs;
            map.computeIfAbsent(start, k -> new ArrayList<>()).add(m);
        }
        return map;
    }

    /**
     * Attach to each signal bar its constituent 1m candles (for intrabar exits) and the HTF-bias context as
     * of the most recently CLOSED HTF bar at/before the signal bar (no lookahead). The HTF context is null
     * until enough HTF history has accumulated — the bias filter then reads UNAVAILABLE and stays permissive,
     * matching the live fail-safe.
     */
    private List<BarSlice> buildSlices(List<Candle> signalBars, Map<Long, List<Candle>> minutesByBucket,
                                       List<Candle> oneMinute, WtxConfig config, WtxProfile profile) {
        boolean needHtf = profile.requiresHtfFilter();
        List<Candle> htfBars = needHtf ? CandleResampler.resample(oneMinute, config.htfTimeframe()) : List.of();
        long htfSecs = needHtf ? seconds(config.htfTimeframe()) : 0;
        List<BigDecimal> fastEma = needHtf
                ? new EMAIndicator(config.htfFastLen()).calculate(htfBars) : List.of();
        List<BigDecimal> slowEma = needHtf
                ? new EMAIndicator(config.htfSlowLen()).calculate(htfBars) : List.of();
        int minHtfBars = config.htfSlowLen() + 1; // live requires slowLen+1 before computing a bias

        List<BarSlice> slices = new ArrayList<>(signalBars.size());
        int htfIdx = -1; // latest HTF bar whose close is at/before the current signal bar
        for (Candle bar : signalBars) {
            long barEpoch = bar.getTimestamp().getEpochSecond();
            WtxHtfBiasFilter.HtfBiasContext ctx = null;
            if (needHtf) {
                // Advance to the latest HTF bar that has fully CLOSED by this signal bar's timestamp.
                while (htfIdx + 1 < htfBars.size()
                        && htfBars.get(htfIdx + 1).getTimestamp().getEpochSecond() + htfSecs <= barEpoch) {
                    htfIdx++;
                }
                if (htfIdx >= minHtfBars - 1) {
                    ctx = new WtxHtfBiasFilter.HtfBiasContext(
                            htfBars.get(htfIdx).getClose(), fastEma.get(htfIdx), slowEma.get(htfIdx));
                }
            }
            List<Candle> minutes = minutesByBucket.getOrDefault(barEpoch, List.of(bar));
            slices.add(new BarSlice(bar, minutes, ctx));
        }
        return slices;
    }

    private static long seconds(String timeframe) {
        return BacktestDataInspector.parseTimeframe(timeframe).toSeconds();
    }
}
