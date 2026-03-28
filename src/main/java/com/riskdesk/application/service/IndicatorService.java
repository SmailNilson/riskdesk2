package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSeriesSnapshot;
import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.engine.indicators.*;
import com.riskdesk.domain.engine.smc.*;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
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

    private final CandleRepositoryPort candlePort;

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
    private final OrderBlockDetector obDetector = new OrderBlockDetector(10, 3, 0.5);
    private final FairValueGapDetector fvgDetector = new FairValueGapDetector(5);
    private final EqualLevelDetector eqDetector = new EqualLevelDetector(5, 0.1);

    public IndicatorService(CandleRepositoryPort candlePort) {
        this.candlePort = candlePort;
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

        // ── SMC (UC-SMC-002: Internal + Swing structure) ─────────────────────────

        SmcStructureEngine smcEngine = new SmcStructureEngine(5, 50);
        SmcStructureEngine.StructureSnapshot smcSnap = smcEngine.computeFromHistory(candles);

        // Internal bias & pivots
        String internalBias = smcSnap.internalBias() != null ? smcSnap.internalBias().name() : null;
        BigDecimal internalHigh = smcSnap.internalHigh() != null ? BigDecimal.valueOf(smcSnap.internalHigh().price()) : null;
        BigDecimal internalLow  = smcSnap.internalLow()  != null ? BigDecimal.valueOf(smcSnap.internalLow().price())  : null;
        Long internalHighTime = smcSnap.internalHigh() != null ? smcSnap.internalHigh().timestamp().getEpochSecond() : null;
        Long internalLowTime  = smcSnap.internalLow()  != null ? smcSnap.internalLow().timestamp().getEpochSecond()  : null;

        // Swing bias & pivots
        String swingBias = smcSnap.swingBias() != null ? smcSnap.swingBias().name() : null;
        BigDecimal swingHigh = smcSnap.swingHigh() != null ? BigDecimal.valueOf(smcSnap.swingHigh().price()) : null;
        BigDecimal swingLow  = smcSnap.swingLow()  != null ? BigDecimal.valueOf(smcSnap.swingLow().price())  : null;
        Long swingHighTime = smcSnap.swingHigh() != null ? smcSnap.swingHigh().timestamp().getEpochSecond() : null;
        Long swingLowTime  = smcSnap.swingLow()  != null ? smcSnap.swingLow().timestamp().getEpochSecond()  : null;

        // Legacy derived fields (backward compat until frontend migrates to internal/swing)
        // marketStructureTrend: prefer swing bias if available, else internal
        String marketStructureTrend = swingBias != null ? swingBias : (internalBias != null ? internalBias : "UNDEFINED");
        // strong = swing pivots, weak = internal pivots (matches LuxAlgo semantics)
        BigDecimal strongHigh = swingHigh;
        BigDecimal strongLow  = swingLow;
        BigDecimal weakHigh   = internalHigh;
        BigDecimal weakLow    = internalLow;
        Long strongHighTime = swingHighTime;
        Long strongLowTime  = swingLowTime;
        Long weakHighTime   = internalHighTime;
        Long weakLowTime    = internalLowTime;

        // Last break type per level (from allEvents — empty in batch mode, so derive from bias transitions)
        // In batch mode, allEvents is empty. We use bias as proxy: if bias is set, there was at least one break.
        // For alert purposes, the IndicatorAlertEvaluator uses lastBreakType which fires on transition.
        String lastInternalBreak = null;
        String lastSwingBreak = null;
        // Legacy single lastBreakType: prefer swing, else internal
        String lastBreak = lastSwingBreak != null ? lastSwingBreak : lastInternalBreak;

        // recentBreaks: empty in batch mode (no events captured — to be populated in step 1d with tail capture)
        List<IndicatorSnapshot.StructureBreakView> recentBreaks = List.of();

        // Order Blocks (unchanged — still uses MarketStructure-compatible detector)
        List<OrderBlockDetector.OrderBlock> obs = obDetector.detect(candles);
        List<IndicatorSnapshot.OrderBlockView> obViews = obs.stream()
                .map(ob -> new IndicatorSnapshot.OrderBlockView(
                        ob.type().name(), ob.highPrice(), ob.lowPrice(), ob.midPoint(),
                        ob.formationIndex() < candles.size()
                                ? candles.get(ob.formationIndex()).getTimestamp().getEpochSecond() : 0L))
                .toList();

        // Fair Value Gaps (unchanged)
        List<FairValueGapDetector.FairValueGap> fvgs = fvgDetector.detect(candles);
        List<IndicatorSnapshot.FairValueGapView> fvgViews = fvgs.stream()
                .map(f -> new IndicatorSnapshot.FairValueGapView(
                        f.bias(), f.top(), f.bottom(), f.startBarTime()))
                .toList();

        // ── Premium / Discount / Equilibrium (UC-SMC-004) ──────────────────
        BigDecimal premiumZoneTop = null;
        BigDecimal equilibriumLevel = null;
        BigDecimal discountZoneBottom = null;
        String currentZone = null;
        double tt = smcSnap.trailingTop();
        double tb = smcSnap.trailingBottom();
        if (!Double.isNaN(tt) && !Double.isNaN(tb) && tt > tb) {
            premiumZoneTop = BigDecimal.valueOf(tt);
            discountZoneBottom = BigDecimal.valueOf(tb);
            double eq = (tt + tb) / 2.0;
            equilibriumLevel = BigDecimal.valueOf(eq);
            double lastClose = candles.get(candles.size() - 1).getClose().doubleValue();
            if (lastClose > eq) {
                currentZone = "PREMIUM";
            } else if (lastClose < eq) {
                currentZone = "DISCOUNT";
            } else {
                currentZone = "EQUILIBRIUM";
            }
        }

        // Equal Highs / Equal Lows (UC-SMC-003)
        List<EqualLevelDetector.EqualLevel> eqLevels = eqDetector.detect(candles);
        List<IndicatorSnapshot.EqualLevelView> eqhViews = eqLevels.stream()
                .filter(e -> e.type() == EqualLevelDetector.EqualType.EQH)
                .map(e -> new IndicatorSnapshot.EqualLevelView("EQH",
                        BigDecimal.valueOf(e.price()),
                        e.firstTime().getEpochSecond(),
                        e.secondTime().getEpochSecond()))
                .toList();
        List<IndicatorSnapshot.EqualLevelView> eqlViews = eqLevels.stream()
                .filter(e -> e.type() == EqualLevelDetector.EqualType.EQL)
                .map(e -> new IndicatorSnapshot.EqualLevelView("EQL",
                        BigDecimal.valueOf(e.price()),
                        e.firstTime().getEpochSecond(),
                        e.secondTime().getEpochSecond()))
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
                // SMC: Internal
                internalBias, internalHigh, internalLow,
                internalHighTime, internalLowTime, lastInternalBreak,
                // SMC: Swing
                swingBias, swingHigh, swingLow,
                swingHighTime, swingLowTime, lastSwingBreak,
                // SMC: Legacy / derived
                marketStructureTrend,
                strongHigh, strongLow, weakHigh, weakLow,
                lastBreak,
                strongHighTime, strongLowTime, weakHighTime, weakLowTime,
                // SMC: Liquidity (EQH / EQL)
                eqhViews, eqlViews,
                // SMC: Premium / Discount / Equilibrium
                premiumZoneTop, equilibriumLevel, discountZoneBottom, currentZone,
                // SMC: Zones
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
        List<Candle> candles = new ArrayList<>(candlePort.findRecentCandles(instrument, timeframe, limit));
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
                // SMC: Internal
                null, null, null, null, null, null,
                // SMC: Swing
                null, null, null, null, null, null,
                // SMC: Legacy / derived
                "UNDEFINED", null, null, null, null, null,
                null, null, null, null,
                // SMC: Liquidity (EQH / EQL)
                Collections.emptyList(), Collections.emptyList(),
                // SMC: Premium / Discount / Equilibrium
                null, null, null, null,
                // SMC: Zones
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                null
        );
    }
}
