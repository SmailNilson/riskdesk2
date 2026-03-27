package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSeriesSnapshot;
import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.engine.indicators.*;
import com.riskdesk.domain.engine.smc.*;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class IndicatorService {

    private static final int SERIES_LIMIT = 500;
    private static final int EMA_9_PERIOD = 9;
    private static final int EMA_50_PERIOD = 50;
    private static final int EMA_200_PERIOD = 200;
    private static final int RSI_PERIOD = 14;
    private static final int BB_PERIOD = 20;
    private static final int BB_TREND_FAST_PERIOD = 14;
    private static final int BB_TREND_SLOW_PERIOD = 30;
    private static final int DELTA_FLOW_LOOKBACK = 20;
    private static final int WT_N1 = 10;
    private static final int WT_N2 = 21;
    private static final int WT_SIGNAL_PERIOD = 4;

    private final ActiveContractCandleService activeContractCandleService;

    // Indicators — initialized with TradingView defaults
    private final EMAIndicator ema9   = new EMAIndicator(EMA_9_PERIOD);
    private final EMAIndicator ema50  = new EMAIndicator(EMA_50_PERIOD);
    private final EMAIndicator ema200 = new EMAIndicator(EMA_200_PERIOD);
    private final RSIIndicator rsi    = new RSIIndicator(RSI_PERIOD, 33.0, 40.0, 60.0);
    private final MACDIndicator macd  = new MACDIndicator(12, 26, 9);
    private final SupertrendIndicator supertrend = new SupertrendIndicator(10, 3.0);
    private final VWAPIndicator vwap  = new VWAPIndicator();
    private final ChaikinIndicator chaikin = new ChaikinIndicator(3, 10, 20);
    private final BollingerBandsIndicator bb = new BollingerBandsIndicator(BB_PERIOD, 2.0, BB_TREND_FAST_PERIOD, BB_TREND_SLOW_PERIOD, 2.0);
    private final DeltaFlowProfile deltaFlow = new DeltaFlowProfile(DELTA_FLOW_LOOKBACK);
    private final WaveTrendIndicator waveTrend = new WaveTrendIndicator(WT_N1, WT_N2, WT_SIGNAL_PERIOD);
    private final MarketStructure marketStructure = new MarketStructure(5);
    private final OrderBlockDetector obDetector = new OrderBlockDetector(10, 3, 0.5);
    private final FairValueGapDetector fvgDetector = new FairValueGapDetector(5);

    public IndicatorService(ActiveContractCandleService activeContractCandleService) {
        this.activeContractCandleService = activeContractCandleService;
    }

    public IndicatorSnapshot computeSnapshot(Instrument instrument, String timeframe) {
        List<Candle> candles = loadCandles(instrument, timeframe, SERIES_LIMIT);
        if (candles.isEmpty()) {
            return emptySnapshot(instrument, timeframe);
        }

        // EMAs
        List<BigDecimal> ema9v   = ema9.calculate(candles);
        List<BigDecimal> ema50v  = ema50.calculate(candles);
        List<BigDecimal> ema200v = ema200.calculate(candles);
        String emaCross = EMAIndicator.detectCrossover(ema9v, ema50v);

        // RSI
        BigDecimal rsiValue = rsi.current(candles);
        String rsiSignal    = rsi.signal(rsiValue);

        // MACD
        List<MACDIndicator.MACDResult> macdResults = macd.calculate(candles);
        MACDIndicator.MACDResult macdCurrent = macdResults.isEmpty() ? null : macdResults.get(macdResults.size() - 1);
        String macdCross = macd.detectCrossover(macdResults);

        // Supertrend
        SupertrendIndicator.SupertrendResult stResult = supertrend.current(candles);

        // VWAP
        VWAPIndicator.VWAPResult vwapResult = vwap.current(candles);

        // Chaikin
        List<BigDecimal> chaikinOsc = chaikin.calculateOscillator(candles);
        List<BigDecimal> cmfValues  = chaikin.calculateCMF(candles);

        // Bollinger Bands
        BollingerBandsIndicator.BBResult bbResult = bb.current(candles);
        BollingerBandsIndicator.BBTrendResult bbTrend = bb.currentTrend(candles);

        // Delta Flow
        DeltaFlowProfile.DeltaFlowResult delta = deltaFlow.current(candles);

        // WaveTrend
        WaveTrendIndicator.WaveTrendResult wt = waveTrend.current(candles);

        // ── SMC ──────────────────────────────────────────────────────────────────

        // Market Structure (BOS / CHoCH, strong/weak H/L)
        MarketStructure.StructureAnalysis structure = marketStructure.analyze(candles);
        List<MarketStructure.StructureBreak> breaks = structure.breaks();
        String lastBreak = breaks.isEmpty() ? null :
                breaks.get(breaks.size() - 1).type().name() + "_" + breaks.get(breaks.size() - 1).newTrend().name();

        // Timestamps for chart price-line rendering
        Long strongHighTime = swingTime(structure.swingPoints(), candles,
                MarketStructure.SwingType.HIGH, structure.strongHigh(), MarketStructure.Strength.STRONG);
        Long strongLowTime  = swingTime(structure.swingPoints(), candles,
                MarketStructure.SwingType.LOW,  structure.strongLow(),  MarketStructure.Strength.STRONG);
        Long weakHighTime   = swingTime(structure.swingPoints(), candles,
                MarketStructure.SwingType.HIGH, structure.weakHigh(),   MarketStructure.Strength.WEAK);
        Long weakLowTime    = swingTime(structure.swingPoints(), candles,
                MarketStructure.SwingType.LOW,  structure.weakLow(),    MarketStructure.Strength.WEAK);

        // Recent BOS / CHoCH with bar timestamps (last 15 for chart markers)
        List<IndicatorSnapshot.StructureBreakView> recentBreaks = breaks.stream()
                .filter(b -> b.breakIndex() < candles.size())
                .map(b -> new IndicatorSnapshot.StructureBreakView(
                        b.type().name(),
                        b.newTrend().name(),
                        b.breakLevel(),
                        candles.get(b.breakIndex()).getTimestamp().getEpochSecond()
                ))
                .toList();

        // Order Blocks
        List<OrderBlockDetector.OrderBlock> obs = obDetector.detect(candles);
        List<IndicatorSnapshot.OrderBlockView> obViews = obs.stream()
                .map(ob -> new IndicatorSnapshot.OrderBlockView(
                        ob.type().name(), ob.highPrice(), ob.lowPrice(), ob.midPoint(),
                        ob.formationIndex() < candles.size()
                                ? candles.get(ob.formationIndex()).getTimestamp().getEpochSecond() : 0L))
                .toList();

        // Fair Value Gaps
        List<FairValueGapDetector.FairValueGap> fvgs = fvgDetector.detect(candles);
        List<IndicatorSnapshot.FairValueGapView> fvgViews = fvgs.stream()
                .map(f -> new IndicatorSnapshot.FairValueGapView(
                        f.bias(), f.top(), f.bottom(), f.startBarTime()))
                .toList();

        Instant lastCandleTimestamp = candles.get(candles.size() - 1).getTimestamp();

        return new IndicatorSnapshot(
                instrument.name(), timeframe,
                last(ema9v), last(ema50v), last(ema200v), emaCross,
                rsiValue, rsiSignal,
                macdCurrent != null ? macdCurrent.macdLine()   : null,
                macdCurrent != null ? macdCurrent.signalLine() : null,
                macdCurrent != null ? macdCurrent.histogram()  : null,
                macdCross,
                stResult != null ? stResult.value()    : null,
                stResult != null && stResult.isUptrend(),
                vwapResult != null ? vwapResult.vwap()       : null,
                vwapResult != null ? vwapResult.upperBand()  : null,
                vwapResult != null ? vwapResult.lowerBand()  : null,
                last(chaikinOsc), last(cmfValues),
                bbResult != null ? bbResult.middle() : null,
                bbResult != null ? bbResult.upper()  : null,
                bbResult != null ? bbResult.lower()  : null,
                bbResult != null ? bbResult.width()  : null,
                bbResult != null ? bbResult.pct()    : null,
                bbTrend != null ? bbTrend.value()    : null,
                bbTrend != null && bbTrend.expanding(),
                bbTrend != null ? bbTrend.signal()   : null,
                delta != null ? delta.currentDelta()    : null,
                delta != null ? delta.cumulativeDelta() : null,
                delta != null ? delta.buyRatio()        : null,
                delta != null ? delta.bias()            : null,
                wt != null ? wt.wt1()       : null,
                wt != null ? wt.wt2()       : null,
                wt != null ? wt.diff()      : null,
                wt != null ? wt.crossover() : null,
                wt != null ? wt.signal()    : null,
                structure.currentTrend().name(),
                structure.strongHigh(), structure.strongLow(),
                structure.weakHigh(),   structure.weakLow(),
                lastBreak,
                strongHighTime, strongLowTime, weakHighTime, weakLowTime,
                obViews,
                fvgViews,
                recentBreaks,
                lastCandleTimestamp
        );
    }

    public IndicatorSeriesSnapshot computeSeries(Instrument instrument, String timeframe, int limit) {
        List<Candle> candles = loadCandles(instrument, timeframe, limit);
        if (candles.isEmpty()) {
            return new IndicatorSeriesSnapshot(
                    instrument.name(),
                    timeframe,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList()
            );
        }

        List<IndicatorSeriesSnapshot.LinePoint> ema9Series = mapLinePoints(candles, EMA_9_PERIOD - 1, ema9.calculate(candles));
        List<IndicatorSeriesSnapshot.LinePoint> ema50Series = mapLinePoints(candles, EMA_50_PERIOD - 1, ema50.calculate(candles));
        List<IndicatorSeriesSnapshot.LinePoint> ema200Series = mapLinePoints(candles, EMA_200_PERIOD - 1, ema200.calculate(candles));

        List<IndicatorSeriesSnapshot.BollingerPoint> bollingerSeries = mapBollingerPoints(candles, BB_PERIOD - 1, bb.calculate(candles));
        List<IndicatorSeriesSnapshot.WaveTrendPoint> waveTrendSeries = mapWaveTrendPoints(candles, WT_SIGNAL_PERIOD - 1, waveTrend.calculate(candles));

        return new IndicatorSeriesSnapshot(
                instrument.name(),
                timeframe,
                ema9Series,
                ema50Series,
                ema200Series,
                bollingerSeries,
                waveTrendSeries
        );
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private List<Candle> loadCandles(Instrument instrument, String timeframe, int limit) {
        List<Candle> candles = new ArrayList<>(activeContractCandleService.findRecentCandles(instrument, timeframe, limit));
        Collections.reverse(candles);
        return candles;
    }

    private BigDecimal last(List<BigDecimal> list) {
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    private List<IndicatorSeriesSnapshot.LinePoint> mapLinePoints(List<Candle> candles, int startIndex, List<BigDecimal> values) {
        if (values.isEmpty()) return Collections.emptyList();

        List<IndicatorSeriesSnapshot.LinePoint> points = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Candle candle = candles.get(startIndex + i);
            points.add(new IndicatorSeriesSnapshot.LinePoint(
                    candle.getTimestamp().getEpochSecond(),
                    values.get(i)
            ));
        }
        return points;
    }

    private List<IndicatorSeriesSnapshot.BollingerPoint> mapBollingerPoints(
            List<Candle> candles,
            int startIndex,
            List<BollingerBandsIndicator.BBResult> values
    ) {
        if (values.isEmpty()) return Collections.emptyList();

        List<IndicatorSeriesSnapshot.BollingerPoint> points = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Candle candle = candles.get(startIndex + i);
            BollingerBandsIndicator.BBResult value = values.get(i);
            points.add(new IndicatorSeriesSnapshot.BollingerPoint(
                    candle.getTimestamp().getEpochSecond(),
                    value.upper(),
                    value.lower()
            ));
        }
        return points;
    }

    private List<IndicatorSeriesSnapshot.WaveTrendPoint> mapWaveTrendPoints(
            List<Candle> candles,
            int startIndex,
            List<WaveTrendIndicator.WaveTrendResult> values
    ) {
        if (values.isEmpty()) return Collections.emptyList();

        List<IndicatorSeriesSnapshot.WaveTrendPoint> points = new ArrayList<>(values.size());
        for (int i = 0; i < values.size(); i++) {
            Candle candle = candles.get(startIndex + i);
            WaveTrendIndicator.WaveTrendResult value = values.get(i);
            points.add(new IndicatorSeriesSnapshot.WaveTrendPoint(
                    candle.getTimestamp().getEpochSecond(),
                    value.wt1(),
                    value.wt2(),
                    value.diff()
            ));
        }
        return points;
    }

    /** Return the epoch-second timestamp of the candle at a swing point matching price + type + strength. */
    private Long swingTime(List<MarketStructure.SwingPoint> swings, List<Candle> candles,
                           MarketStructure.SwingType type, BigDecimal price,
                           MarketStructure.Strength strength) {
        if (price == null) return null;
        return swings.stream()
                .filter(sp -> sp.type() == type
                        && sp.strength() == strength
                        && sp.price().compareTo(price) == 0
                        && sp.index() < candles.size())
                .findFirst()
                .map(sp -> candles.get(sp.index()).getTimestamp().getEpochSecond())
                .orElse(null);
    }

    private IndicatorSnapshot emptySnapshot(Instrument instrument, String timeframe) {
        return new IndicatorSnapshot(
                instrument.name(), timeframe,
                null, null, null, null,
                null, null,
                null, null, null, null,
                null, false,
                null, null, null,
                null, null,
                null, null, null, null, null,
                null, false, null,
                null, null, null, null,
                null, null, null, null, null,
                "UNDEFINED", null, null, null, null, null,
                null, null, null, null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                null
        );
    }
}
