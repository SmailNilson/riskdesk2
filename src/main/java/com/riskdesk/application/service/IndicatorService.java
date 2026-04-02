package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSeriesSnapshot;
import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.contract.ActiveContractRegistry;
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

    private static final int SNAPSHOT_LOOKBACK_BARS = 2_000;
    private static final int SERIES_LIMIT = 500;
    private static final int SERIES_WARMUP_BARS = 1_000;
    private static final int FVG_LOOKBACK_BARS = SNAPSHOT_LOOKBACK_BARS;
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
    private static final int EQUAL_LEVEL_LOOKBACK = 5;
    private static final int RECENT_BREAK_LIMIT = 12;
    private static final int MAX_VISIBLE_LIQUIDITY_POOLS = 8;
    private static final String FVG_MIN_GAP_SIZE = "0";     // UC-SMC-010: no minimum threshold by default
    private static final int FVG_EXTENSION_BARS = 0;        // UC-SMC-010: no visual extension by default
    private static final String FVG_DEDICATED_TIMEFRAME = null; // UC-SMC-010: null = use chart timeframe
    private static final boolean SMC_CONFLUENCE_FILTER = false; // UC-SMC-008: suppress internal breaks against swing

    private final CandleRepositoryPort  candlePort;
    private final ActiveContractRegistry contractRegistry;

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
    // UC-SMC-010: FVG detector with threshold filtering and visual extension
    private final FairValueGapDetector fvgDetector = new FairValueGapDetector(
            5,
            new BigDecimal(FVG_MIN_GAP_SIZE),
            FVG_EXTENSION_BARS
    );

    public IndicatorService(CandleRepositoryPort candlePort, ActiveContractRegistry contractRegistry) {
        this.candlePort       = candlePort;
        this.contractRegistry = contractRegistry;
    }

    public IndicatorSnapshot computeSnapshot(Instrument instrument, String timeframe) {
        List<Candle> candles = loadCandles(instrument, timeframe, SNAPSHOT_LOOKBACK_BARS);
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

        SmcStructureEngine smcEngine = new SmcStructureEngine(5, 50, SMC_CONFLUENCE_FILTER);
        SmcStructureEngine.StructureSnapshot smcSnap = smcEngine.computeFromHistory(candles, RECENT_BREAK_LIMIT);
        SmcStructureEngine.StructureEvent latestInternalEvent = latestStructureEvent(smcSnap.events(), SmcStructureEngine.StructureLevel.INTERNAL);
        SmcStructureEngine.StructureEvent latestSwingEvent = latestStructureEvent(smcSnap.events(), SmcStructureEngine.StructureLevel.SWING);
        SmcStructureEngine.StructureEvent latestEvent = latestStructureEvent(smcSnap.events(), null);

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
        String lastInternalBreak = formatBreakType(latestInternalEvent);
        String lastSwingBreak = formatBreakType(latestSwingEvent);
        // Legacy single lastBreakType: prefer swing, else internal
        String lastBreak = formatBreakType(latestEvent);

        List<IndicatorSnapshot.StructureBreakView> recentBreaks = smcSnap.events().stream()
                .sorted(Comparator.comparingInt(SmcStructureEngine.StructureEvent::barIndex).reversed())
                .map(event -> new IndicatorSnapshot.StructureBreakView(
                        event.type().name(),
                        event.newBias().name(),
                        BigDecimal.valueOf(event.breakPrice()),
                        event.timestamp().getEpochSecond(),
                        event.level().name()))
                .toList();

        // Order Blocks + lifecycle events (UC-SMC-009)
        OrderBlockDetector.DetectionResult obResult = obDetector.detectWithEvents(candles);
        List<IndicatorSnapshot.OrderBlockView> obViews = obResult.activeOrderBlocks().stream()
                .map(ob -> new IndicatorSnapshot.OrderBlockView(
                        ob.type().name(), ob.status().name(), ob.highPrice(), ob.lowPrice(), ob.midPoint(),
                        ob.formationIndex() < candles.size()
                                ? candles.get(ob.formationIndex()).getTimestamp().getEpochSecond() : 0L,
                        ob.type().name(),
                        null))
                .toList();
        List<IndicatorSnapshot.OrderBlockView> breakerViews = obResult.breakerOrderBlocks().stream()
                .map(ob -> new IndicatorSnapshot.OrderBlockView(
                        ob.type().name(), ob.status().name(), ob.highPrice(), ob.lowPrice(), ob.midPoint(),
                        ob.formationIndex() < candles.size()
                                ? candles.get(ob.formationIndex()).getTimestamp().getEpochSecond() : 0L,
                        oppositeType(ob.type()).name(),
                        ob.mitigationIndex() >= 0 && ob.mitigationIndex() < candles.size()
                                ? candles.get(ob.mitigationIndex()).getTimestamp().getEpochSecond() : null))
                .toList();
        List<IndicatorSnapshot.OrderBlockEventView> obEventViews = obResult.events().stream()
                .map(evt -> new IndicatorSnapshot.OrderBlockEventView(
                        evt.eventType().name(), evt.obType().name(),
                        evt.high(), evt.low(),
                        evt.eventTime().getEpochSecond()))
                .toList();

        // Fair Value Gaps (UC-SMC-010: dedicated timeframe + extension metadata)
        List<Candle> fvgCandles = FVG_DEDICATED_TIMEFRAME != null
                ? loadCandles(instrument, FVG_DEDICATED_TIMEFRAME, FVG_LOOKBACK_BARS)
                : candles;
        List<FairValueGapDetector.FairValueGap> fvgs = fvgDetector.detect(fvgCandles);
        List<IndicatorSnapshot.FairValueGapView> fvgViews = fvgs.stream()
                .map(f -> new IndicatorSnapshot.FairValueGapView(
                        f.bias(), f.top(), f.bottom(), f.startBarTime(), f.extensionEndTime()))
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
        EqualLevelDetector eqDetector = EqualLevelDetector.tickNormalized(
                EQUAL_LEVEL_LOOKBACK,
                instrument.getTickSize(),
                liquidityToleranceTicks(timeframe)
        );
        double currentPrice = candles.get(candles.size() - 1).getClose().doubleValue();
        List<EqualLevelDetector.LiquidityPool> liquidityPools = selectVisibleLiquidityPools(
                eqDetector.detectPools(candles),
                currentPrice,
                MAX_VISIBLE_LIQUIDITY_POOLS
        );
        List<IndicatorSnapshot.EqualLevelView> eqhViews = liquidityPools.stream()
                .filter(pool -> pool.type() == EqualLevelDetector.EqualType.EQH)
                .map(pool -> new IndicatorSnapshot.EqualLevelView(
                        "EQH",
                        BigDecimal.valueOf(pool.price()),
                        pool.firstTime().getEpochSecond(),
                        pool.lastTime().getEpochSecond(),
                        pool.touchCount()))
                .toList();
        List<IndicatorSnapshot.EqualLevelView> eqlViews = liquidityPools.stream()
                .filter(pool -> pool.type() == EqualLevelDetector.EqualType.EQL)
                .map(pool -> new IndicatorSnapshot.EqualLevelView(
                        "EQL",
                        BigDecimal.valueOf(pool.price()),
                        pool.firstTime().getEpochSecond(),
                        pool.lastTime().getEpochSecond(),
                        pool.touchCount()))
                .toList();

        // ── UC-SMC-005: Multi-timeframe levels (Daily / Weekly / Monthly) ──────────
        IndicatorSnapshot.MtfLevelView dailyLevel  = loadMtfLevel(instrument, "1d");
        IndicatorSnapshot.MtfLevelView weeklyLevel  = loadMtfLevel(instrument, "1w");
        IndicatorSnapshot.MtfLevelView monthlyLevel = loadMtfLevel(instrument, "1M");
        IndicatorSnapshot.MtfLevelsView mtfLevels =
                new IndicatorSnapshot.MtfLevelsView(dailyLevel, weeklyLevel, monthlyLevel);

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
                // SMC: UC-SMC-008 confluence filter state
                SMC_CONFLUENCE_FILTER,
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
                obViews, breakerViews, obEventViews,
                fvgViews,
                recentBreaks,
                // UC-SMC-005: MTF levels
                mtfLevels,
                lastCandleTimestamp,
                candles.get(candles.size() - 1).getClose()
        );
    }

    public IndicatorSeriesSnapshot computeSeries(Instrument instrument, String timeframe, int limit) {
        int safeLimit = Math.max(limit, 1);
        List<Candle> candles = loadCandles(instrument, timeframe, safeLimit + SERIES_WARMUP_BARS);
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

        List<IndicatorSeriesSnapshot.LinePoint> ema9Series = trimLast(
                mapLinePoints(candles, EMA_9_PERIOD - 1, ema9.calculate(candles)),
                safeLimit
        );
        List<IndicatorSeriesSnapshot.LinePoint> ema50Series = trimLast(
                mapLinePoints(candles, EMA_50_PERIOD - 1, ema50.calculate(candles)),
                safeLimit
        );
        List<IndicatorSeriesSnapshot.LinePoint> ema200Series = trimLast(
                mapLinePoints(candles, EMA_200_PERIOD - 1, ema200.calculate(candles)),
                safeLimit
        );

        List<IndicatorSeriesSnapshot.BollingerPoint> bollingerSeries = trimLast(
                mapBollingerPoints(candles, BB_PERIOD - 1, bb.calculate(candles)),
                safeLimit
        );
        List<IndicatorSeriesSnapshot.WaveTrendPoint> waveTrendSeries = trimLast(
                mapWaveTrendPoints(candles, WT_SIGNAL_PERIOD - 1, waveTrend.calculate(candles)),
                safeLimit
        );

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

    /**
     * UC-SMC-005: Load the most recent closed candle for a higher timeframe and
     * return its OHLC as an MtfLevelView. Returns null if no data is available.
     */
    private IndicatorSnapshot.MtfLevelView loadMtfLevel(Instrument instrument, String timeframe) {
        List<Candle> htfCandles = loadCandles(instrument, timeframe, 2);
        if (htfCandles.isEmpty()) return null;
        Candle last = htfCandles.get(htfCandles.size() - 1);
        return new IndicatorSnapshot.MtfLevelView(
                last.getOpen(), last.getHigh(), last.getLow(), last.getClose());
    }

    private static OrderBlockDetector.OBType oppositeType(OrderBlockDetector.OBType type) {
        return type == OrderBlockDetector.OBType.BULLISH
                ? OrderBlockDetector.OBType.BEARISH
                : OrderBlockDetector.OBType.BULLISH;
    }

    private List<Candle> loadCandles(Instrument instrument, String timeframe, int limit) {
        String contractMonth = contractRegistry.getContractMonth(instrument).orElse(null);
        List<Candle> candles;
        if (contractMonth != null) {
            candles = candlePort.findRecentCandlesByContractMonth(instrument, timeframe, contractMonth, limit);
            if (candles.isEmpty()) {
                // Migration path: no tagged candles yet — fall back to legacy untagged rows
                candles = candlePort.findRecentCandles(instrument, timeframe, limit);
            }
        } else {
            candles = candlePort.findRecentCandles(instrument, timeframe, limit);
        }
        List<Candle> ordered = new ArrayList<>(candles);
        Collections.reverse(ordered);
        return ordered;
    }

    private BigDecimal last(List<BigDecimal> list) {
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }

    private <T> List<T> trimLast(List<T> values, int limit) {
        if (values.size() <= limit) {
            return values;
        }
        return values.subList(values.size() - limit, values.size());
    }

    private SmcStructureEngine.StructureEvent latestStructureEvent(
            List<SmcStructureEngine.StructureEvent> events,
            SmcStructureEngine.StructureLevel level
    ) {
        return events.stream()
                .filter(event -> level == null || event.level() == level)
                .max(Comparator.comparingInt(SmcStructureEngine.StructureEvent::barIndex))
                .orElse(null);
    }

    private String formatBreakType(SmcStructureEngine.StructureEvent event) {
        if (event == null) return null;
        return event.type().name() + "_" + event.newBias().name();
    }

    private int liquidityToleranceTicks(String timeframe) {
        return switch (timeframe) {
            case "5m" -> 3;
            case "10m", "1h" -> 4;
            case "4h", "1d", "1w", "1M" -> 5;
            default -> 4;
        };
    }

    private List<EqualLevelDetector.LiquidityPool> selectVisibleLiquidityPools(
            List<EqualLevelDetector.LiquidityPool> pools,
            double currentPrice,
            int maxVisible
    ) {
        if (pools.size() <= maxVisible) {
            return pools.stream()
                    .sorted(Comparator.comparingDouble(pool -> Math.abs(pool.price() - currentPrice)))
                    .toList();
        }

        int sideBudget = maxVisible / 2;
        List<EqualLevelDetector.LiquidityPool> above = pools.stream()
                .filter(pool -> pool.price() >= currentPrice)
                .sorted(Comparator.comparingDouble(pool -> pool.price() - currentPrice))
                .toList();
        List<EqualLevelDetector.LiquidityPool> below = pools.stream()
                .filter(pool -> pool.price() < currentPrice)
                .sorted(Comparator.comparingDouble(pool -> currentPrice - pool.price()))
                .toList();

        List<EqualLevelDetector.LiquidityPool> selected = new ArrayList<>();
        selected.addAll(above.subList(0, Math.min(sideBudget, above.size())));
        selected.addAll(below.subList(0, Math.min(sideBudget, below.size())));

        if (selected.size() < maxVisible) {
            List<EqualLevelDetector.LiquidityPool> leftovers = new ArrayList<>();
            leftovers.addAll(above.subList(Math.min(sideBudget, above.size()), above.size()));
            leftovers.addAll(below.subList(Math.min(sideBudget, below.size()), below.size()));
            leftovers.sort(Comparator.comparingDouble(pool -> Math.abs(pool.price() - currentPrice)));
            selected.addAll(leftovers.subList(0, Math.min(maxVisible - selected.size(), leftovers.size())));
        }

        return selected.stream()
                .sorted(Comparator.comparingDouble(pool -> Math.abs(pool.price() - currentPrice)))
                .toList();
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
                // SMC: UC-SMC-008 confluence filter state
                false,
                // SMC: Legacy / derived
                "UNDEFINED", null, null, null, null, null,
                null, null, null, null,
                // SMC: Liquidity (EQH / EQL)
                Collections.emptyList(), Collections.emptyList(),
                // SMC: Premium / Discount / Equilibrium
                null, null, null, null,
                // SMC: Zones
                Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                // UC-SMC-005: MTF levels
                null,
                null,
                null
        );
    }
}
