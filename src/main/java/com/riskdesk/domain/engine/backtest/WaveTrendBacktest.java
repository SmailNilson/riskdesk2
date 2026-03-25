package com.riskdesk.domain.engine.backtest;

import com.riskdesk.domain.model.Candle;

import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * WaveTrend crossover backtest engine with pyramiding support.
 *
 * The execution model is intentionally conservative:
 * - indicators are computed over the full candle set, including warmup candles
 * - trading starts only at {@code evaluationStart}
 * - intrabar stops are evaluated before close-based signal reversals
 * - close-on-close entries never use the same candle's high/low for stop checks
 */
public class WaveTrendBacktest {

    private static final int SIGNAL_PERIOD = 4;
    private static final Duration WEEKEND_GAP_THRESHOLD = Duration.ofHours(24);

    // Strategy parameters
    private int n1 = 10;
    private int n2 = 21;
    private double nsc = 53.0;
    private double nsv = -53.0;
    private boolean useCompra = true;
    private boolean useVenta = true;
    private boolean useCompra1 = false;
    private boolean useVenta1 = false;
    private boolean reverseOnOpp = true;
    private double fixedQty = 2.0;
    private double initialCapital = 10_000.0;
    private double pointValue = 2.0;
    private int maxPyramiding = 10;
    private boolean entryOnNextBar = false;

    // Filter parameters
    private int emaFilterPeriod = 0;
    private double stopLossPoints = 0;
    private boolean atrTrailingStop = false;
    private double atrMultiplier = 2.0;
    private int atrPeriod = 14;
    private boolean bollingerTakeProfit = false;
    private int bollingerLength = 20;
    private boolean closeEndOfDay = false;
    private boolean closeEndOfWeek = false;
    private int entryOnSignal = 1;
    private boolean enableSmcFilter = false;
    private EntryFilterService.Config smcFilterConfig = EntryFilterService.Config.disabled();
    private HigherTimeframeLevelService.LevelIndex htfLevelIndex = HigherTimeframeLevelService.LevelIndex.empty();
    private MarketStructureService.StructureContextIndex htfStructureIndex = MarketStructureService.StructureContextIndex.empty();

    // Execution context
    private String contextInstrument = "MNQ";
    private String contextTimeframe = "1h";
    private Instant evaluationStart;
    private boolean debug;
    private BacktestResult.DataAudit dataAudit;
    private final EntryFilterService entryFilterService =
        new EntryFilterService(new HigherTimeframeLevelService(new MarketStructureService()));

    public WaveTrendBacktest n1(int v) { this.n1 = v; return this; }
    public WaveTrendBacktest n2(int v) { this.n2 = v; return this; }
    public WaveTrendBacktest nsc(double v) { this.nsc = v; return this; }
    public WaveTrendBacktest nsv(double v) { this.nsv = v; return this; }
    public WaveTrendBacktest useCompra(boolean v) { this.useCompra = v; return this; }
    public WaveTrendBacktest useVenta(boolean v) { this.useVenta = v; return this; }
    public WaveTrendBacktest useCompra1(boolean v) { this.useCompra1 = v; return this; }
    public WaveTrendBacktest useVenta1(boolean v) { this.useVenta1 = v; return this; }
    public WaveTrendBacktest reverseOnOpp(boolean v) { this.reverseOnOpp = v; return this; }
    public WaveTrendBacktest fixedQty(double v) { this.fixedQty = v; return this; }
    public WaveTrendBacktest initialCapital(double v) { this.initialCapital = v; return this; }
    public WaveTrendBacktest pointValue(double v) { this.pointValue = v; return this; }
    public WaveTrendBacktest maxPyramiding(int v) { this.maxPyramiding = Math.max(1, v); return this; }
    public WaveTrendBacktest entryOnNextBar(boolean v) { this.entryOnNextBar = v; return this; }
    public WaveTrendBacktest emaFilterPeriod(int v) { this.emaFilterPeriod = v; return this; }
    public WaveTrendBacktest stopLossPoints(double v) { this.stopLossPoints = v; return this; }
    public WaveTrendBacktest atrTrailingStop(boolean v) { this.atrTrailingStop = v; return this; }
    public WaveTrendBacktest atrMultiplier(double v) { this.atrMultiplier = v; return this; }
    public WaveTrendBacktest atrPeriod(int v) { this.atrPeriod = v; return this; }
    public WaveTrendBacktest bollingerTakeProfit(boolean v) { this.bollingerTakeProfit = v; return this; }
    public WaveTrendBacktest bollingerLength(int v) { this.bollingerLength = Math.max(1, v); return this; }
    public WaveTrendBacktest closeEndOfDay(boolean v) { this.closeEndOfDay = v; return this; }
    public WaveTrendBacktest closeEndOfWeek(boolean v) { this.closeEndOfWeek = v; return this; }
    public WaveTrendBacktest entryOnSignal(int v) { this.entryOnSignal = Math.max(1, v); return this; }
    public WaveTrendBacktest enableSmcFilter(boolean v) { this.enableSmcFilter = v; return this; }
    public WaveTrendBacktest smcFilterConfig(EntryFilterService.Config config) {
        this.smcFilterConfig = config == null ? EntryFilterService.Config.disabled() : config;
        return this;
    }
    public WaveTrendBacktest htfLevelIndex(HigherTimeframeLevelService.LevelIndex index) {
        this.htfLevelIndex = index == null ? HigherTimeframeLevelService.LevelIndex.empty() : index;
        return this;
    }
    public WaveTrendBacktest htfStructureIndex(MarketStructureService.StructureContextIndex index) {
        this.htfStructureIndex = index == null ? MarketStructureService.StructureContextIndex.empty() : index;
        return this;
    }
    public WaveTrendBacktest context(String instrument, String timeframe) {
        this.contextInstrument = instrument;
        this.contextTimeframe = timeframe;
        return this;
    }
    public WaveTrendBacktest evaluationStart(Instant instant) { this.evaluationStart = instant; return this; }
    public WaveTrendBacktest debug(boolean enabled) { this.debug = enabled; return this; }
    public WaveTrendBacktest dataAudit(BacktestResult.DataAudit audit) { this.dataAudit = audit; return this; }

    private static final class OpenEntry {
        private final String side;
        private final double price;
        private final Instant time;
        private final double qty;
        private final int entryBarIndex;
        private final int stopActiveFromBarIndex;
        private double trailingStop = Double.NaN;

        private OpenEntry(String side, double price, Instant time, double qty, int entryBarIndex, int stopActiveFromBarIndex) {
            this.side = side;
            this.price = price;
            this.time = time;
            this.qty = qty;
            this.entryBarIndex = entryBarIndex;
            this.stopActiveFromBarIndex = stopActiveFromBarIndex;
        }
    }

    private static final class SimulationState {
        private double equity;
        private int tradeNo;

        private SimulationState(double initialCapital) {
            this.equity = initialCapital;
        }
    }

    public BacktestResult run(List<Candle> candles) {
        if (candles.isEmpty()) {
            return new BacktestResult(
                "WT_X WaveTrend Crossover",
                contextInstrument,
                contextTimeframe,
                0,
                initialCapital,
                initialCapital,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                dataAudit,
                List.of()
            );
        }

        int len = candles.size();
        int evaluationStartIndex = resolveEvaluationStartIndex(candles);
        if (evaluationStartIndex >= len || len < Math.max(n1 + n2 + SIGNAL_PERIOD, atrTrailingStop ? atrPeriod + 2 : 0)) {
            return new BacktestResult(
                "WT_X WaveTrend Crossover",
                candles.get(0).getInstrument().name(),
                candles.get(0).getTimeframe(),
                Math.max(0, len - evaluationStartIndex),
                initialCapital,
                initialCapital,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                dataAudit,
                List.of()
            );
        }

        double[] closes = new double[len];
        double[] hlc3 = new double[len];
        for (int i = 0; i < len; i++) {
            Candle candle = candles.get(i);
            closes[i] = candle.getClose().doubleValue();
            hlc3[i] = (candle.getHigh().doubleValue() + candle.getLow().doubleValue() + candle.getClose().doubleValue()) / 3.0;
        }

        double[] esa = ema(hlc3, n1);
        double[] absDeviation = new double[len];
        for (int i = 0; i < len; i++) {
            absDeviation[i] = Math.abs(hlc3[i] - esa[i]);
        }
        double[] d = ema(absDeviation, n1);
        double[] ci = new double[len];
        for (int i = 0; i < len; i++) {
            ci[i] = d[i] == 0 ? 0 : (hlc3[i] - esa[i]) / (0.015 * d[i]);
        }

        double[] wt1 = ema(ci, n2);
        double[] wt2 = smaWithNaN(wt1, SIGNAL_PERIOD);
        double[] emaFilter = emaFilterPeriod > 0 ? ema(closes, emaFilterPeriod) : fillWithNaN(len);
        double[] atr = atrTrailingStop ? atr(candles, atrPeriod) : fillWithNaN(len);
        double[] smcAtr = enableSmcFilter && smcFilterConfig.nearThresholdMode() == HigherTimeframeLevelService.ThresholdMode.ATR
            ? atr(candles, smcFilterConfig.nearThresholdAtrPeriod())
            : fillWithNaN(len);
        BollingerBands bollingerBands = bollingerTakeProfit ? bollingerBands(closes, bollingerLength, 2.0) : BollingerBands.empty(len);

        boolean[] longSignal = new boolean[len];
        boolean[] shortSignal = new boolean[len];
        List<BacktestResult.SignalDebug> signals = new ArrayList<>();

        for (int i = 1; i < len; i++) {
            if (!isFinite(wt2[i]) || !isFinite(wt2[i - 1])) {
                continue;
            }

            boolean crossOver = wt1[i] > wt2[i] && wt1[i - 1] <= wt2[i - 1];
            boolean crossUnder = wt1[i] < wt2[i] && wt1[i - 1] >= wt2[i - 1];

            boolean compra = crossOver && wt1[i] <= nsv;
            boolean compra1 = crossOver && wt1[i] > nsv;
            boolean venta = crossUnder && wt1[i] >= nsc;
            boolean venta1 = crossUnder && wt1[i] < nsc;

            boolean rawLong = (useCompra && compra) || (useCompra1 && compra1);
            boolean rawShort = (useVenta && venta) || (useVenta1 && venta1);

            if (emaFilterPeriod > 0) {
                if (!isFinite(emaFilter[i])) {
                    continue;
                }
                longSignal[i] = rawLong && closes[i] > emaFilter[i];
                shortSignal[i] = rawShort && closes[i] < emaFilter[i];
            } else {
                longSignal[i] = rawLong;
                shortSignal[i] = rawShort;
            }

            if (i >= evaluationStartIndex && (longSignal[i] || shortSignal[i])) {
                signals.add(new BacktestResult.SignalDebug(
                    candles.get(i).getTimestamp().toString(),
                    longSignal[i] ? "LONG" : "SHORT",
                    closes[i],
                    round2(wt1[i]),
                    round2(wt2[i])
                ));
            }
        }

        List<BacktestTrade> closedTrades = new ArrayList<>();
        List<OpenEntry> openEntries = new ArrayList<>();
        List<Double> equityCurve = new ArrayList<>();
        List<BacktestResult.DebugEvent> debugEvents = new ArrayList<>();
        SimulationState state = new SimulationState(initialCapital);
        double peakEquity = initialCapital;
        double maxDrawdown = 0;
        String currentSide = null;
        String reentryBlockedSide = null;
        String pendingSignal = null;
        int pendingSignalIndex = -1;
        int longSignalCount = 0;
        int shortSignalCount = 0;

        for (int i = evaluationStartIndex; i < len; i++) {
            Candle candle = candles.get(i);
            double closePrice = candle.getClose().doubleValue();
            double openPrice = candle.getOpen().doubleValue();
            Instant time = candle.getTimestamp();
            boolean sessionCloseBar = isSessionCloseBar(candles, i);
            int closedTradeCountBeforeBar = closedTrades.size();
            String blockedSideThisBar = null;

            applyStops(openEntries, closedTrades, debugEvents, state, candle, i, closePrice, atr, bollingerBands, false, wt1, wt2, emaFilter);
            if (openEntries.isEmpty()) {
                currentSide = null;
            }
            reentryBlockedSide = updateReentryBlockedSide(reentryBlockedSide, closedTrades, closedTradeCountBeforeBar);
            blockedSideThisBar = blockedSideFrom(closedTrades, closedTradeCountBeforeBar);

            String sessionCloseReason = sessionCloseBar ? resolveSessionCloseReason(candles, i) : null;
            if (sessionCloseReason != null && !openEntries.isEmpty()) {
                int closedTradeCountBeforeSessionClose = closedTrades.size();
                closeAllEntries(openEntries, closedTrades, debugEvents, state, closePrice, time, sessionCloseReason, wt1[i], wt2[i], emaFilter[i], atr[i]);
                currentSide = null;
                reentryBlockedSide = updateReentryBlockedSide(reentryBlockedSide, closedTrades, closedTradeCountBeforeSessionClose);
                String sessionBlockedSide = blockedSideFrom(closedTrades, closedTradeCountBeforeSessionClose);
                if (sessionBlockedSide != null) {
                    blockedSideThisBar = sessionBlockedSide;
                }
            }

            boolean execLong = false;
            boolean execShort = false;
            double execPrice = entryOnNextBar ? openPrice : closePrice;
            Instant execTime = time;

            if (entryOnNextBar) {
                if ("LONG".equals(pendingSignal)) execLong = true;
                if ("SHORT".equals(pendingSignal)) execShort = true;
                pendingSignal = null;
                pendingSignalIndex = -1;

                if (!sessionCloseBar) {
                    if (longSignal[i] && shortSignal[i]) {
                        addDebug(debugEvents, time, "SKIP", "BOTH", closePrice, "Ambiguous long+short signal on same bar", wt1[i], wt2[i], emaFilter[i], atr[i], null);
                    } else if (longSignal[i]) {
                        pendingSignal = "LONG";
                        pendingSignalIndex = i;
                    } else if (shortSignal[i]) {
                        pendingSignal = "SHORT";
                        pendingSignalIndex = i;
                    }
                }
            } else if (!sessionCloseBar) {
                execLong = longSignal[i];
                execShort = shortSignal[i];
            }

            if (sessionCloseBar && (longSignal[i] || shortSignal[i])) {
                addDebug(debugEvents, time, "SKIP", longSignal[i] ? "LONG" : "SHORT", closePrice, "Entry blocked by session close rule", wt1[i], wt2[i], emaFilter[i], atr[i], null);
            }

            if (execLong && execShort) {
                execLong = false;
                execShort = false;
                addDebug(debugEvents, time, "SKIP", "BOTH", execPrice, "Ambiguous executable long+short signal", wt1[i], wt2[i], emaFilter[i], atr[i], null);
            }

            if (execLong && "SHORT".equals(reentryBlockedSide)) {
                reentryBlockedSide = null;
            } else if (execShort && "LONG".equals(reentryBlockedSide)) {
                reentryBlockedSide = null;
            }

            if (execLong && "LONG".equals(reentryBlockedSide)) {
                execLong = false;
                addDebug(debugEvents, time, "SKIP", "LONG", execPrice, "Re-entry blocked until opposite WT signal after filtered exit", wt1[i], wt2[i], emaFilter[i], atr[i], null);
            }
            if (execShort && "SHORT".equals(reentryBlockedSide)) {
                execShort = false;
                addDebug(debugEvents, time, "SKIP", "SHORT", execPrice, "Re-entry blocked until opposite WT signal after filtered exit", wt1[i], wt2[i], emaFilter[i], atr[i], null);
            }
            if (execLong && "LONG".equals(blockedSideThisBar)) {
                execLong = false;
                addDebug(debugEvents, time, "SKIP", "LONG", execPrice, "Same-bar re-entry blocked after filtered exit", wt1[i], wt2[i], emaFilter[i], atr[i], null);
            }
            if (execShort && "SHORT".equals(blockedSideThisBar)) {
                execShort = false;
                addDebug(debugEvents, time, "SKIP", "SHORT", execPrice, "Same-bar re-entry blocked after filtered exit", wt1[i], wt2[i], emaFilter[i], atr[i], null);
            }

            if (currentSide == null) {
                if (execLong) {
                    longSignalCount++;
                    shortSignalCount = 0;
                    if (longSignalCount < entryOnSignal) {
                        addDebug(debugEvents, time, "SKIP", "LONG", execPrice, "Waiting for configured signal occurrence", wt1[i], wt2[i], emaFilter[i], atr[i], null);
                        execLong = false;
                    }
                } else if (execShort) {
                    shortSignalCount++;
                    longSignalCount = 0;
                    if (shortSignalCount < entryOnSignal) {
                        addDebug(debugEvents, time, "SKIP", "SHORT", execPrice, "Waiting for configured signal occurrence", wt1[i], wt2[i], emaFilter[i], atr[i], null);
                        execShort = false;
                    }
                }
            }

            EntryFilterService.Decision longFilterDecision = null;
            EntryFilterService.Decision shortFilterDecision = null;
            if (execLong && enableSmcFilter) {
                longFilterDecision = entryFilterService.evaluate(
                    EntryFilterService.Direction.LONG,
                    candle,
                    smcAtr[i],
                    smcFilterConfig,
                    htfLevelIndex,
                    htfStructureIndex
                );
                if (!longFilterDecision.accepted()) {
                    execLong = false;
                    addDebug(debugEvents, time, "SKIP", "LONG", execPrice, longFilterDecision.reason(), wt1[i], wt2[i], emaFilter[i], smcAtr[i], null);
                }
            }
            if (execShort && enableSmcFilter) {
                shortFilterDecision = entryFilterService.evaluate(
                    EntryFilterService.Direction.SHORT,
                    candle,
                    smcAtr[i],
                    smcFilterConfig,
                    htfLevelIndex,
                    htfStructureIndex
                );
                if (!shortFilterDecision.accepted()) {
                    execShort = false;
                    addDebug(debugEvents, time, "SKIP", "SHORT", execPrice, shortFilterDecision.reason(), wt1[i], wt2[i], emaFilter[i], smcAtr[i], null);
                }
            }

            if (execLong) {
                longSignalCount = 0;
                shortSignalCount = 0;
                if ("SHORT".equals(currentSide)) {
                    if (reverseOnOpp) {
                        closeAllEntries(openEntries, closedTrades, debugEvents, state, execPrice, execTime, "SIGNAL_REVERSE", wt1[i], wt2[i], emaFilter[i], atr[i]);
                        currentSide = null;
                    } else {
                        addDebug(debugEvents, time, "SKIP", "LONG", execPrice, "Opposite-side entry ignored because reverseOnOpp=false", wt1[i], wt2[i], emaFilter[i], atr[i], null);
                        execLong = false;
                    }
                }
                if (execLong && (currentSide == null || "LONG".equals(currentSide))) {
                    if (openEntries.size() < maxPyramiding) {
                        OpenEntry entry = new OpenEntry("LONG", execPrice, execTime, fixedQty, i, entryOnNextBar ? i : i + 1);
                        if (entryOnNextBar && atrTrailingStop && isFinite(atr[i])) {
                            entry.trailingStop = execPrice - atr[i] * atrMultiplier;
                        }
                        openEntries.add(entry);
                        currentSide = "LONG";
                        reentryBlockedSide = null;
                        String entryReason = longFilterDecision != null ? longFilterDecision.reason() : "Signal entry";
                        addDebug(debugEvents, time, "ENTRY", "LONG", execPrice, entryReason, wt1[i], wt2[i], emaFilter[i], atr[i], entry.trailingStop);
                    } else {
                        addDebug(debugEvents, time, "SKIP", "LONG", execPrice, "Pyramiding limit reached", wt1[i], wt2[i], emaFilter[i], atr[i], null);
                    }
                }
            }

            if (execShort) {
                longSignalCount = 0;
                shortSignalCount = 0;
                if ("LONG".equals(currentSide)) {
                    if (reverseOnOpp) {
                        closeAllEntries(openEntries, closedTrades, debugEvents, state, execPrice, execTime, "SIGNAL_REVERSE", wt1[i], wt2[i], emaFilter[i], atr[i]);
                        currentSide = null;
                    } else {
                        addDebug(debugEvents, time, "SKIP", "SHORT", execPrice, "Opposite-side entry ignored because reverseOnOpp=false", wt1[i], wt2[i], emaFilter[i], atr[i], null);
                        execShort = false;
                    }
                }
                if (execShort && (currentSide == null || "SHORT".equals(currentSide))) {
                    if (openEntries.size() < maxPyramiding) {
                        OpenEntry entry = new OpenEntry("SHORT", execPrice, execTime, fixedQty, i, entryOnNextBar ? i : i + 1);
                        if (entryOnNextBar && atrTrailingStop && isFinite(atr[i])) {
                            entry.trailingStop = execPrice + atr[i] * atrMultiplier;
                        }
                        openEntries.add(entry);
                        currentSide = "SHORT";
                        reentryBlockedSide = null;
                        String entryReason = shortFilterDecision != null ? shortFilterDecision.reason() : "Signal entry";
                        addDebug(debugEvents, time, "ENTRY", "SHORT", execPrice, entryReason, wt1[i], wt2[i], emaFilter[i], atr[i], entry.trailingStop);
                    } else {
                        addDebug(debugEvents, time, "SKIP", "SHORT", execPrice, "Pyramiding limit reached", wt1[i], wt2[i], emaFilter[i], atr[i], null);
                    }
                }
            }

            if (entryOnNextBar) {
                int closedTradeCountBeforeNextBarStops = closedTrades.size();
                applyStops(openEntries, closedTrades, debugEvents, state, candle, i, closePrice, atr, bollingerBands, true, wt1, wt2, emaFilter);
                if (openEntries.isEmpty()) {
                    currentSide = null;
                }
                reentryBlockedSide = updateReentryBlockedSide(reentryBlockedSide, closedTrades, closedTradeCountBeforeNextBarStops);
                String nextBarBlockedSide = blockedSideFrom(closedTrades, closedTradeCountBeforeNextBarStops);
                if (nextBarBlockedSide != null) {
                    blockedSideThisBar = nextBarBlockedSide;
                }
            }

            updateTrailingStops(openEntries, closePrice, i, atr);

            double unrealized = 0;
            for (OpenEntry entry : openEntries) {
                if ("LONG".equals(entry.side)) {
                    unrealized += (closePrice - entry.price) * entry.qty * pointValue;
                } else {
                    unrealized += (entry.price - closePrice) * entry.qty * pointValue;
                }
            }
            double currentEquity = state.equity + unrealized;
            equityCurve.add(round2(currentEquity));
            peakEquity = Math.max(peakEquity, currentEquity);
            maxDrawdown = Math.max(maxDrawdown, peakEquity - currentEquity);
        }

        if (!openEntries.isEmpty()) {
            Candle lastCandle = candles.get(len - 1);
            closeAllEntries(openEntries, closedTrades, debugEvents, state, lastCandle.getClose().doubleValue(), lastCandle.getTimestamp(), "END_OF_DATA", wt1[len - 1], wt2[len - 1], emaFilter[len - 1], atr[len - 1]);
        }

        int wins = 0;
        int losses = 0;
        double totalWin = 0;
        double totalLoss = 0;
        List<Double> returns = new ArrayList<>();
        for (BacktestTrade trade : closedTrades) {
            if (trade.pnl() > 0) {
                wins++;
                totalWin += trade.pnl();
            } else {
                losses++;
                totalLoss += Math.abs(trade.pnl());
            }
            returns.add(trade.pnl());
        }

        double winRate = closedTrades.isEmpty() ? 0 : (double) wins / closedTrades.size() * 100;
        double avgWin = wins > 0 ? totalWin / wins : 0;
        double avgLoss = losses > 0 ? totalLoss / losses : 0;
        double profitFactor = totalLoss > 0 ? totalWin / totalLoss : totalWin > 0 ? Double.MAX_VALUE : 0;
        double totalPnl = state.equity - initialCapital;
        double maxDdPct = peakEquity > 0 ? maxDrawdown / peakEquity * 100 : 0;

        double sharpe = 0;
        if (returns.size() > 1) {
            double mean = returns.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            double variance = returns.stream().mapToDouble(r -> Math.pow(r - mean, 2)).average().orElse(0);
            double std = Math.sqrt(variance);
            sharpe = std > 0 ? mean / std * Math.sqrt(returns.size()) : 0;
        }

        String strategyName = "WT_X WaveTrend Crossover";
        if (entryOnSignal > 1) strategyName += " + Entry@" + entryOnSignal;
        if (emaFilterPeriod > 0) strategyName += " + EMA" + emaFilterPeriod;
        if (stopLossPoints > 0) strategyName += " + SL" + (int) stopLossPoints;
        if (atrTrailingStop) strategyName += " + ATR-TS";
        if (bollingerTakeProfit) strategyName += " + BBTP" + bollingerLength;
        if (enableSmcFilter) strategyName += " + SMC-HTF";
        if (closeEndOfDay) strategyName += " + EOD";
        if (closeEndOfWeek) strategyName += " + EOW";
        if (entryOnNextBar) strategyName += " + NextBar";

        return new BacktestResult(
            strategyName,
            candles.get(0).getInstrument().name(),
            candles.get(0).getTimeframe(),
            len - evaluationStartIndex,
            initialCapital,
            round2(state.equity),
            round2(totalPnl),
            round2(totalPnl / initialCapital * 100),
            closedTrades.size(),
            wins,
            losses,
            round2(winRate),
            round2(avgWin),
            round2(avgLoss),
            round2(profitFactor),
            round2(maxDrawdown),
            round2(maxDdPct),
            round2(sharpe),
            closedTrades,
            equityCurve,
            signals,
            dataAudit,
            debug ? debugEvents : List.of()
        );
    }

    private int resolveEvaluationStartIndex(List<Candle> candles) {
        if (evaluationStart == null) {
            return 0;
        }
        for (int i = 0; i < candles.size(); i++) {
            if (!candles.get(i).getTimestamp().isBefore(evaluationStart)) {
                return i;
            }
        }
        return candles.size();
    }

    private void applyStops(
        List<OpenEntry> openEntries,
        List<BacktestTrade> closedTrades,
        List<BacktestResult.DebugEvent> debugEvents,
        SimulationState state,
        Candle candle,
        int candleIndex,
        double closePrice,
        double[] atr,
        BollingerBands bollingerBands,
        boolean onlyEntriesOpenedThisBar,
        double[] wt1,
        double[] wt2,
        double[] emaFilter
    ) {
        double highPrice = candle.getHigh().doubleValue();
        double lowPrice = candle.getLow().doubleValue();
        Instant time = candle.getTimestamp();

        Iterator<OpenEntry> iterator = openEntries.iterator();
        while (iterator.hasNext()) {
            OpenEntry entry = iterator.next();
            boolean isCurrentBarEntry = entry.entryBarIndex == candleIndex;
            if (onlyEntriesOpenedThisBar != isCurrentBarEntry) {
                continue;
            }
            if (candleIndex < entry.stopActiveFromBarIndex) {
                continue;
            }

            Double stopPrice = null;
            String exitReason = null;

            if (stopLossPoints > 0) {
                double candidate = "LONG".equals(entry.side)
                    ? entry.price - stopLossPoints
                    : entry.price + stopLossPoints;
                boolean hit = "LONG".equals(entry.side) ? lowPrice <= candidate : highPrice >= candidate;
                if (hit) {
                    stopPrice = candidate;
                    exitReason = "STOP_LOSS";
                }
            }

            if (exitReason == null && atrTrailingStop && isFinite(entry.trailingStop)) {
                boolean hit = "LONG".equals(entry.side) ? lowPrice <= entry.trailingStop : highPrice >= entry.trailingStop;
                if (hit) {
                    stopPrice = entry.trailingStop;
                    exitReason = "ATR_TRAILING";
                }
            }

            if (exitReason == null && bollingerTakeProfit) {
                double candidate = "LONG".equals(entry.side)
                    ? bollingerBands.upper[candleIndex]
                    : bollingerBands.lower[candleIndex];
                boolean hit = isFinite(candidate)
                    && ("LONG".equals(entry.side) ? highPrice >= candidate : lowPrice <= candidate);
                if (hit) {
                    stopPrice = candidate;
                    exitReason = "BB_TAKE_PROFIT";
                }
            }

            if (exitReason == null) {
                continue;
            }

            double pnl = "LONG".equals(entry.side)
                ? (stopPrice - entry.price) * entry.qty * pointValue
                : (entry.price - stopPrice) * entry.qty * pointValue;
            state.equity += pnl;
            state.tradeNo++;
            closedTrades.add(new BacktestTrade(
                state.tradeNo,
                entry.side,
                entry.price,
                entry.time,
                round2(stopPrice),
                time,
                entry.qty,
                round2(pnl),
                round2(pnl / initialCapital * 100),
                exitReason
            ));
            addDebug(debugEvents, time, "EXIT", entry.side, stopPrice, exitReason, wt1[candleIndex], wt2[candleIndex], emaFilter[candleIndex], atr[candleIndex], stopPrice);
            iterator.remove();
        }
    }

    private void closeAllEntries(
        List<OpenEntry> openEntries,
        List<BacktestTrade> closedTrades,
        List<BacktestResult.DebugEvent> debugEvents,
        SimulationState state,
        double exitPrice,
        Instant exitTime,
        String reason,
        double wt1,
        double wt2,
        double ema,
        double atr
    ) {
        for (OpenEntry entry : openEntries) {
            double pnl = "LONG".equals(entry.side)
                ? (exitPrice - entry.price) * entry.qty * pointValue
                : (entry.price - exitPrice) * entry.qty * pointValue;
            state.equity += pnl;
            state.tradeNo++;
            closedTrades.add(new BacktestTrade(
                state.tradeNo,
                entry.side,
                entry.price,
                entry.time,
                round2(exitPrice),
                exitTime,
                entry.qty,
                round2(pnl),
                round2(pnl / initialCapital * 100),
                reason
            ));
            addDebug(debugEvents, exitTime, "EXIT", entry.side, exitPrice, reason, wt1, wt2, ema, atr, entry.trailingStop);
        }
        openEntries.clear();
    }

    private void updateTrailingStops(List<OpenEntry> openEntries, double closePrice, int candleIndex, double[] atr) {
        if (!atrTrailingStop || !isFinite(atr[candleIndex])) {
            return;
        }

        double offset = atr[candleIndex] * atrMultiplier;
        for (OpenEntry entry : openEntries) {
            if (candleIndex < entry.entryBarIndex) {
                continue;
            }
            if ("LONG".equals(entry.side)) {
                double candidate = closePrice - offset;
                entry.trailingStop = isFinite(entry.trailingStop) ? Math.max(entry.trailingStop, candidate) : candidate;
            } else {
                double candidate = closePrice + offset;
                entry.trailingStop = isFinite(entry.trailingStop) ? Math.min(entry.trailingStop, candidate) : candidate;
            }
        }
    }

    private boolean isSessionCloseBar(List<Candle> candles, int index) {
        return resolveSessionCloseReason(candles, index) != null;
    }

    private String resolveSessionCloseReason(List<Candle> candles, int index) {
        if ((!closeEndOfDay && !closeEndOfWeek) || index + 1 >= candles.size()) {
            return null;
        }

        Duration barDuration = BacktestDataInspector.parseTimeframe(candles.get(index).getTimeframe());
        if (barDuration.isZero()) {
            return null;
        }

        Instant current = candles.get(index).getTimestamp();
        Instant next = candles.get(index + 1).getTimestamp();
        Duration gap = Duration.between(current, next);

        if (gap.compareTo(barDuration) <= 0) {
            return null;
        }

        if (closeEndOfWeek && gap.compareTo(WEEKEND_GAP_THRESHOLD) >= 0) {
            return "END_OF_WEEK";
        }

        if (closeEndOfDay) {
            return "END_OF_DAY";
        }

        return null;
    }

    private void addDebug(
        List<BacktestResult.DebugEvent> debugEvents,
        Instant time,
        String event,
        String side,
        double price,
        String reason,
        Double wt1,
        Double wt2,
        Double ema,
        Double atr,
        Double stopPrice
    ) {
        if (!debug) {
            return;
        }
        debugEvents.add(new BacktestResult.DebugEvent(
            time.toString(),
            event,
            side,
            round2(price),
            reason,
            finiteOrNull(wt1),
            finiteOrNull(wt2),
            finiteOrNull(ema),
            finiteOrNull(atr),
            finiteOrNull(stopPrice)
        ));
    }

    private static Double finiteOrNull(Double value) {
        return value != null && Double.isFinite(value) ? round2(value) : null;
    }

    private static boolean isFinite(double value) {
        return Double.isFinite(value) && !Double.isNaN(value);
    }

    private static double[] fillWithNaN(int len) {
        double[] values = new double[len];
        for (int i = 0; i < len; i++) {
            values[i] = Double.NaN;
        }
        return values;
    }

    private static double[] ema(double[] source, int period) {
        double[] result = new double[source.length];
        double multiplier = 2.0 / (period + 1);
        result[0] = source[0];
        for (int i = 1; i < source.length; i++) {
            result[i] = (source[i] - result[i - 1]) * multiplier + result[i - 1];
        }
        return result;
    }

    private static double[] smaWithNaN(double[] source, int period) {
        double[] result = fillWithNaN(source.length);
        double sum = 0;
        for (int i = 0; i < source.length; i++) {
            sum += source[i];
            if (i >= period) {
                sum -= source[i - period];
            }
            if (i >= period - 1) {
                result[i] = sum / period;
            }
        }
        return result;
    }

    private static double[] atr(List<Candle> candles, int period) {
        int len = candles.size();
        double[] result = fillWithNaN(len);
        double[] tr = new double[len];
        tr[0] = candles.get(0).getHigh().doubleValue() - candles.get(0).getLow().doubleValue();
        for (int i = 1; i < len; i++) {
            double high = candles.get(i).getHigh().doubleValue();
            double low = candles.get(i).getLow().doubleValue();
            double prevClose = candles.get(i - 1).getClose().doubleValue();
            tr[i] = Math.max(high - low, Math.max(Math.abs(high - prevClose), Math.abs(low - prevClose)));
        }

        if (len < period) {
            return result;
        }

        double sum = 0;
        for (int i = 0; i < period; i++) {
            sum += tr[i];
        }
        result[period - 1] = sum / period;
        for (int i = period; i < len; i++) {
            result[i] = ((result[i - 1] * (period - 1)) + tr[i]) / period;
        }
        return result;
    }

    private static BollingerBands bollingerBands(double[] closes, int period, double multiplier) {
        double[] upper = fillWithNaN(closes.length);
        double[] lower = fillWithNaN(closes.length);
        for (int i = period - 1; i < closes.length; i++) {
            double sum = 0;
            for (int j = i - period + 1; j <= i; j++) {
                sum += closes[j];
            }
            double basis = sum / period;
            double variance = 0;
            for (int j = i - period + 1; j <= i; j++) {
                double diff = closes[j] - basis;
                variance += diff * diff;
            }
            double dev = multiplier * Math.sqrt(variance / period);
            upper[i] = basis + dev;
            lower[i] = basis - dev;
        }
        return new BollingerBands(upper, lower);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String updateReentryBlockedSide(String currentBlockedSide, List<BacktestTrade> closedTrades, int previousClosedTradeCount) {
        if (closedTrades.size() <= previousClosedTradeCount) {
            return currentBlockedSide;
        }

        BacktestTrade lastClosed = closedTrades.get(closedTrades.size() - 1);
        return switch (lastClosed.exitReason()) {
            case "SIGNAL_REVERSE", "END_OF_DATA" -> null;
            default -> lastClosed.side();
        };
    }

    private static String blockedSideFrom(List<BacktestTrade> closedTrades, int previousClosedTradeCount) {
        if (closedTrades.size() <= previousClosedTradeCount) {
            return null;
        }

        BacktestTrade lastClosed = closedTrades.get(closedTrades.size() - 1);
        return switch (lastClosed.exitReason()) {
            case "SIGNAL_REVERSE", "END_OF_DATA" -> null;
            default -> lastClosed.side();
        };
    }

    private record BollingerBands(double[] upper, double[] lower) {
        private static BollingerBands empty(int len) {
            return new BollingerBands(fillWithNaN(len), fillWithNaN(len));
        }
    }
}
