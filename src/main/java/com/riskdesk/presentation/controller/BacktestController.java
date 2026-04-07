package com.riskdesk.presentation.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.service.HistoricalDataService;
import com.riskdesk.application.service.WaveTrendSignalScanner;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.backtest.BacktestDataInspector;
import com.riskdesk.domain.engine.backtest.BacktestResult;
import com.riskdesk.domain.engine.backtest.ContinuousContractBuilder;
import com.riskdesk.domain.engine.backtest.EntryFilterService;
import com.riskdesk.domain.engine.backtest.HigherTimeframeLevelService;
import com.riskdesk.domain.engine.backtest.MarketStructureService;
import com.riskdesk.domain.engine.backtest.WaveTrendBacktest;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/backtest")
@CrossOrigin(origins = "*")
public class BacktestController {

    private static final Logger log = LoggerFactory.getLogger(BacktestController.class);

    private final CandleRepositoryPort candlePort;
    private final ObjectMapper objectMapper;
    private final WaveTrendSignalScanner signalScanner;
    private final HistoricalDataService historicalDataService;
    private final MarketStructureService marketStructureService;
    private final HigherTimeframeLevelService higherTimeframeLevelService;

    public BacktestController(CandleRepositoryPort candlePort, ObjectMapper objectMapper,
                              WaveTrendSignalScanner signalScanner,
                              HistoricalDataService historicalDataService) {
        this.candlePort = candlePort;
        this.objectMapper = objectMapper;
        this.signalScanner = signalScanner;
        this.historicalDataService = historicalDataService;
        this.marketStructureService = new MarketStructureService();
        this.higherTimeframeLevelService = new HigherTimeframeLevelService(marketStructureService);
    }

    @GetMapping("/scan")
    public Map<String, String> triggerScan() {
        signalScanner.scanNow();
        return Map.of("status", "scan completed — check alerts");
    }

    @PostMapping("/refresh-db")
    public Map<String, Object> refreshDatabase() {
        return historicalDataService.refreshAll();
    }

    @DeleteMapping("/purge/{instrument}")
    public Map<String, Object> purgeInstrument(@PathVariable String instrument) {
        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return Map.of("error", "Unknown instrument: " + instrument);
        }
        int total = 0;
        for (String tf : List.of("1m", "5m", "10m", "30m", "1h", "4h", "1d")) {
            List<Candle> existing = candlePort.findCandles(inst, tf, Instant.EPOCH);
            if (!existing.isEmpty()) {
                candlePort.deleteByInstrumentAndTimeframe(inst, tf);
                total += existing.size();
                log.info("Purged {} {} {} candles", existing.size(), inst, tf);
            }
        }
        return Map.of("instrument", inst.name(), "purged", total);
    }

    @GetMapping("/import-history")
    public Map<String, Object> importHistory(
        @RequestParam String file,
        @RequestParam(defaultValue = "MNQ") String instrument,
        @RequestParam(defaultValue = "1h") String timeframe
    ) {
        return Map.of(
            "error",
            "Unsupported endpoint. Historical data must come only from the existing IBKR Gateway -> PostgreSQL pipeline."
        );
    }

    /**
     * Import historical candles from a simple JSON array file (IBKR format).
     * File format: [{"timestamp":"2025-06-20T12:00:00Z","open":22626,"high":22700,"low":22600,"close":22650,"volume":100}, ...]
     * Usage: GET /api/backtest/import-ibkr?file=/tmp/mnq_ibkr_1h.json&instrument=MNQ&timeframe=1h
     */
    @org.springframework.transaction.annotation.Transactional
    @GetMapping("/import-ibkr")
    public Map<String, Object> importIbkr(
        @RequestParam String file,
        @RequestParam(defaultValue = "MNQ") String instrument,
        @RequestParam(defaultValue = "1h") String timeframe,
        @RequestParam(defaultValue = "true") boolean purge
    ) {
        Instrument inst = Instrument.valueOf(instrument.toUpperCase());
        try {
            JsonNode root = objectMapper.readTree(new java.io.File(file));
            if (!root.isArray()) {
                return Map.of("error", "Expected JSON array");
            }

            List<Candle> candles = new ArrayList<>();
            for (JsonNode node : root) {
                String ts = node.path("timestamp").asText();
                double o = node.path("open").asDouble();
                double h = node.path("high").asDouble();
                double l = node.path("low").asDouble();
                double c = node.path("close").asDouble();
                long v = node.path("volume").asLong(0);
                if (o == 0 && c == 0) continue;

                Instant instant = Instant.parse(ts);
                candles.add(new Candle(inst, timeframe, instant,
                    round(o), round(h), round(l), round(c), v));
            }

            List<Candle> sanitizedCandles = BacktestDataInspector.inspect(inst, timeframe, candles, null, 0).candles();

            // Purge existing data for this instrument/timeframe to avoid duplicates
            int beforeCount = 0;
            Set<Instant> existingTimestamps = Set.of();
            if (purge) {
                List<Candle> existing = candlePort.findCandles(inst, timeframe,
                        Instant.now().minus(365 * 3, ChronoUnit.DAYS));
                beforeCount = existing.size();
                candlePort.deleteByInstrumentAndTimeframe(inst, timeframe);
                log.info("Purged {} existing {} {} candles before IBKR import", beforeCount, instrument, timeframe);
            } else {
                List<Candle> existing = candlePort.findCandles(inst, timeframe,
                        Instant.now().minus(365 * 3, ChronoUnit.DAYS));
                beforeCount = existing.size();
                existingTimestamps = new HashSet<>();
                for (Candle existingCandle : existing) {
                    existingTimestamps.add(existingCandle.getTimestamp());
                }
            }

            List<Candle> importableCandles = new ArrayList<>();
            for (Candle candle : sanitizedCandles) {
                if (purge || !existingTimestamps.contains(candle.getTimestamp())) {
                    importableCandles.add(candle);
                }
            }

            List<Candle> saved = importableCandles.isEmpty() ? List.of() : candlePort.saveAll(importableCandles);

            List<Candle> afterSave = candlePort.findCandles(inst, timeframe,
                    Instant.now().minus(365 * 3, ChronoUnit.DAYS));

            log.info("IBKR Import: {} candles for {} {} (was: {}, now: {})",
                    saved.size(), instrument, timeframe, beforeCount, afterSave.size());

            return Map.ofEntries(
                Map.entry("instrument", instrument),
                Map.entry("timeframe", timeframe),
                Map.entry("source", "IBKR"),
                Map.entry("parsed", candles.size()),
                Map.entry("dedupedInPayload", sanitizedCandles.size()),
                Map.entry("skippedExisting", sanitizedCandles.size() - importableCandles.size()),
                Map.entry("saved", saved.size()),
                Map.entry("totalInDb", afterSave.size()),
                Map.entry("previousInDb", beforeCount),
                Map.entry("from", sanitizedCandles.isEmpty() ? "N/A" : sanitizedCandles.get(0).getTimestamp().toString()),
                Map.entry("to", sanitizedCandles.isEmpty() ? "N/A" : sanitizedCandles.get(sanitizedCandles.size() - 1).getTimestamp().toString())
            );
        } catch (Exception e) {
            log.error("IBKR Import failed: {}", e.getMessage(), e);
            return Map.of("error", e.getMessage());
        }
    }

    @GetMapping("/wt")
    public BacktestResult runWaveTrendBacktest(
        @RequestParam(defaultValue = "MNQ") String instrument,
        @RequestParam(defaultValue = "1h")  String timeframe,
        @RequestParam(defaultValue = "10")  int n1,
        @RequestParam(defaultValue = "21")  int n2,
        @RequestParam(defaultValue = "53")  double nsc,
        @RequestParam(defaultValue = "-53") double nsv,
        @RequestParam(defaultValue = "true")  boolean useCompra,
        @RequestParam(defaultValue = "true")  boolean useVenta,
        @RequestParam(defaultValue = "false") boolean useCompra1,
        @RequestParam(defaultValue = "false") boolean useVenta1,
        @RequestParam(defaultValue = "true")  boolean reverseOnOpp,
        @RequestParam(defaultValue = "2.0")   double qty,
        @RequestParam(defaultValue = "10000") double capital,
        @RequestParam(defaultValue = "2.0")   double pointValue,
        @RequestParam(defaultValue = "0")     int pyramiding,
        @RequestParam(defaultValue = "false") boolean continuous,
        @RequestParam(defaultValue = "false") boolean nextBarEntry,
        @RequestParam(defaultValue = "0")     int emaFilterPeriod,
        @RequestParam(defaultValue = "0")     double stopLossPoints,
        @RequestParam(defaultValue = "false") boolean atrTrailingStop,
        @RequestParam(defaultValue = "2.0")   double atrMultiplier,
        @RequestParam(defaultValue = "14")    int atrPeriod,
        @RequestParam(defaultValue = "false") boolean bollingerTakeProfit,
        @RequestParam(defaultValue = "20")    int bollingerLength,
        @RequestParam(defaultValue = "false") boolean closeEndOfDay,
        @RequestParam(defaultValue = "false") boolean closeEndOfWeek,
        @RequestParam(defaultValue = "1")     int entryOnSignal,
        @RequestParam(defaultValue = "false") boolean enableSmcFilter,
        @RequestParam(defaultValue = "1h")    String minHtf,
        @RequestParam(defaultValue = "true")  boolean useH1Levels,
        @RequestParam(defaultValue = "true")  boolean useH4Levels,
        @RequestParam(defaultValue = "false") boolean useDailyLevels,
        @RequestParam(defaultValue = "5")     int swingLengthHtf,
        @RequestParam(defaultValue = "true")  boolean requireCloseNearLevel,
        @RequestParam(defaultValue = "ATR")   String nearThresholdMode,
        @RequestParam(defaultValue = "0.25")  double nearThresholdValue,
        @RequestParam(defaultValue = "14")    int nearThresholdAtrPeriod,
        @RequestParam(defaultValue = "0.25")  double tickSize,
        @RequestParam(defaultValue = "false") boolean requireBullishStructureForLong,
        @RequestParam(defaultValue = "false") boolean requireBearishStructureForShort,
        @RequestParam(defaultValue = "true")  boolean useBos,
        @RequestParam(defaultValue = "true")  boolean useChoch,
        @RequestParam(defaultValue = "false") boolean useOrderBlocks,
        @RequestParam(defaultValue = "1")     int minConfirmationScore,
        @RequestParam(defaultValue = "0")     int maxLevelAgeBars,
        @RequestParam(defaultValue = "true")  boolean debugLogging,
        @RequestParam(defaultValue = "false") boolean debug,
        @RequestParam(defaultValue = "")      String fromDate
    ) {
        Instrument inst = Instrument.valueOf(instrument.toUpperCase());
        Instant requestedStart = fromDate != null && !fromDate.isEmpty()
            ? BacktestDataInspector.floorToTimeframe(Instant.parse(fromDate), timeframe)
            : null;
        int requestedWarmupBars = requestedStart == null ? 0 : computeWarmupBars(n1, n2, emaFilterPeriod, atrTrailingStop ? atrPeriod : 0, bollingerTakeProfit ? bollingerLength : 0);
        Instant fetchFrom = requestedStart == null
            ? Instant.now().minus(365 * 3, ChronoUnit.DAYS)
            : requestedStart.minus(BacktestDataInspector.parseTimeframe(timeframe).multipliedBy(requestedWarmupBars));

        List<Candle> candles = candlePort.findCandles(inst, timeframe, fetchFrom);

        if (continuous) {
            candles = buildContinuousCandles(candles, inst, timeframe);
        }

        BacktestDataInspector.InspectionResult inspection = BacktestDataInspector.inspect(
            inst,
            timeframe,
            candles,
            requestedStart,
            requestedWarmupBars
        );
        List<Candle> sanitizedCandles = inspection.candles();

        log.info("Backtest WT: {} {} — {} candles loaded ({} evaluated, continuous={})",
            instrument,
            timeframe,
            sanitizedCandles.size(),
            inspection.audit().evaluatedCandles(),
            continuous
        );

        Instant effectiveEvaluationStart = inspection.effectiveEvaluationStart();
        EntryFilterService.Config smcConfig = buildSmcConfig(
            enableSmcFilter,
            requireCloseNearLevel,
            nearThresholdMode,
            nearThresholdValue,
            nearThresholdAtrPeriod,
            tickSize,
            requireBullishStructureForLong,
            requireBearishStructureForShort,
            useBos,
            useChoch,
            useOrderBlocks,
            minConfirmationScore,
            maxLevelAgeBars,
            debugLogging
        );
        Map<String, List<Candle>> htfCandles = enableSmcFilter
            ? fetchHigherTimeframeCandles(inst, requestedStart, minHtf, useH1Levels, useH4Levels, useDailyLevels, continuous)
            : Map.of();
        HigherTimeframeLevelService.LevelIndex levelIndex = enableSmcFilter
            ? buildHigherTimeframeLevelIndex(htfCandles, swingLengthHtf)
            : HigherTimeframeLevelService.LevelIndex.empty();
        MarketStructureService.StructureContextIndex structureIndex = enableSmcFilter
            ? buildHigherTimeframeStructureIndex(htfCandles, swingLengthHtf)
            : MarketStructureService.StructureContextIndex.empty();

        return new WaveTrendBacktest()
            .n1(n1).n2(n2).nsc(nsc).nsv(nsv)
            .useCompra(useCompra).useVenta(useVenta)
            .useCompra1(useCompra1).useVenta1(useVenta1)
            .reverseOnOpp(reverseOnOpp)
            .entryOnNextBar(nextBarEntry)
            .fixedQty(qty)
            .initialCapital(capital)
            .pointValue(pointValue)
            .maxPyramiding(Math.max(0, pyramiding) + 1)
            .emaFilterPeriod(emaFilterPeriod)
            .stopLossPoints(stopLossPoints)
            .atrTrailingStop(atrTrailingStop)
            .atrMultiplier(atrMultiplier)
            .atrPeriod(atrPeriod)
            .bollingerTakeProfit(bollingerTakeProfit)
            .bollingerLength(bollingerLength)
            .closeEndOfDay(closeEndOfDay)
            .closeEndOfWeek(closeEndOfWeek)
            .entryOnSignal(entryOnSignal)
            .enableSmcFilter(enableSmcFilter)
            .smcFilterConfig(smcConfig)
            .htfLevelIndex(levelIndex)
            .htfStructureIndex(structureIndex)
            .context(inst.name(), timeframe)
            .evaluationStart(effectiveEvaluationStart)
            .debug(debug)
            .dataAudit(inspection.audit())
            .run(sanitizedCandles);
    }

    private EntryFilterService.Config buildSmcConfig(
        boolean enableSmcFilter,
        boolean requireCloseNearLevel,
        String nearThresholdMode,
        double nearThresholdValue,
        int nearThresholdAtrPeriod,
        double tickSize,
        boolean requireBullishStructureForLong,
        boolean requireBearishStructureForShort,
        boolean useBos,
        boolean useChoch,
        boolean useOrderBlocks,
        int minConfirmationScore,
        int maxLevelAgeBars,
        boolean debugLogging
    ) {
        return new EntryFilterService.Config(
            enableSmcFilter,
            requireCloseNearLevel,
            HigherTimeframeLevelService.ThresholdMode.valueOf(nearThresholdMode.toUpperCase()),
            nearThresholdValue,
            nearThresholdAtrPeriod,
            tickSize,
            requireBullishStructureForLong,
            requireBearishStructureForShort,
            useBos,
            useChoch,
            useOrderBlocks,
            minConfirmationScore,
            maxLevelAgeBars,
            debugLogging
        );
    }

    private HigherTimeframeLevelService.LevelIndex buildHigherTimeframeLevelIndex(
        Map<String, List<Candle>> candlesByTimeframe,
        int swingLengthHtf
    ) {
        return higherTimeframeLevelService.buildLevelIndex(candlesByTimeframe, swingLengthHtf);
    }

    private MarketStructureService.StructureContextIndex buildHigherTimeframeStructureIndex(
        Map<String, List<Candle>> candlesByTimeframe,
        int swingLengthHtf
    ) {
        List<MarketStructureService.StructureEvent> events = new ArrayList<>();
        for (Map.Entry<String, List<Candle>> entry : candlesByTimeframe.entrySet()) {
            events.addAll(
                marketStructureService.buildStructureContextIndex(entry.getValue(), swingLengthHtf, entry.getKey()).events()
            );
        }
        events.sort(java.util.Comparator.comparing(MarketStructureService.StructureEvent::breakTime));
        return new MarketStructureService.StructureContextIndex(List.copyOf(events));
    }

    private Map<String, List<Candle>> fetchHigherTimeframeCandles(
        Instrument instrument,
        Instant requestedStart,
        String minHtf,
        boolean useH1Levels,
        boolean useH4Levels,
        boolean useDailyLevels,
        boolean continuous
    ) {
        Instant from = requestedStart == null
            ? Instant.now().minus(365 * 3L, ChronoUnit.DAYS)
            : requestedStart.minus(365, ChronoUnit.DAYS);
        Timeframe minimum = Timeframe.fromLabel(minHtf);
        Map<String, List<Candle>> result = new LinkedHashMap<>();

        maybeAddTimeframe(result, instrument, "1h", minimum, useH1Levels, from, continuous);
        maybeAddTimeframe(result, instrument, "4h", minimum, useH4Levels, from, continuous);
        maybeAddTimeframe(result, instrument, "1d", minimum, useDailyLevels, from, continuous);
        return result;
    }

    private void maybeAddTimeframe(
        Map<String, List<Candle>> result,
        Instrument instrument,
        String timeframe,
        Timeframe minimum,
        boolean enabled,
        Instant from,
        boolean continuous
    ) {
        if (!enabled || Timeframe.fromLabel(timeframe).minutes() < minimum.minutes()) {
            return;
        }
        List<Candle> candles = candlePort.findCandles(instrument, timeframe, from);
        if (continuous) {
            candles = buildContinuousCandles(candles, instrument, timeframe);
        }
        List<Candle> sanitized = BacktestDataInspector.inspect(instrument, timeframe, candles, null, 0).candles();
        if (!sanitized.isEmpty()) {
            result.put(timeframe, sanitized);
        }
    }

    /**
     * Splices raw DB candles from multiple contract months into a single continuous series.
     * Uses the last timestamp of each front-month contract as the roll date, matching
     * TradingView's no-adjustment continuous contract (e.g., MNQ1!).
     *
     * Candles with null contractMonth (legacy, untagged) are treated as the oldest layer
     * and are always included before tagged contracts.
     *
     * If fewer than 2 distinct tagged contract months exist, returns the input unchanged.
     */
    private List<Candle> buildContinuousCandles(List<Candle> rawCandles, Instrument inst, String timeframe) {
        // Partition into legacy (null contractMonth) and tagged groups
        Map<String, List<Candle>> byMonth = rawCandles.stream()
            .collect(Collectors.groupingBy(c -> c.getContractMonth() != null ? c.getContractMonth() : ""));

        List<String> taggedMonths = byMonth.keySet().stream()
            .filter(k -> !k.isEmpty())
            .sorted()
            .collect(Collectors.toList());

        if (taggedMonths.size() <= 1) {
            // Only one (or zero) tagged contract months — no splicing needed
            return rawCandles;
        }

        List<Candle> legacyCandles = byMonth.getOrDefault("", List.of());

        // Seed result: legacy candles + first tagged contract month
        List<Candle> result = new ArrayList<>(legacyCandles);
        result.addAll(byMonth.get(taggedMonths.get(0)));
        result.sort(Comparator.comparing(Candle::getTimestamp));

        // Iteratively splice each subsequent contract month
        for (int i = 1; i < taggedMonths.size(); i++) {
            List<Candle> backMonth = byMonth.get(taggedMonths.get(i));
            // Roll date = last timestamp currently in the series
            Instant rollDate = result.stream()
                .map(Candle::getTimestamp)
                .max(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
            result = ContinuousContractBuilder.splice(result, backMonth, rollDate, inst, timeframe);
        }

        log.info("Continuous contract built for {} {}: {} raw candles → {} spliced across {} contracts",
            inst, timeframe, rawCandles.size(), result.size(), taggedMonths.size());
        return result;
    }

    private static BigDecimal round(double val) {
        return BigDecimal.valueOf(val).setScale(2, RoundingMode.HALF_UP);
    }

    private static int computeWarmupBars(int n1, int n2, int emaFilterPeriod, int atrPeriod, int bollingerLength) {
        int longestPeriod = Math.max(
            Math.max(n1 + n2 + 20, emaFilterPeriod * 5),
            Math.max(atrPeriod * 5, bollingerLength * 5)
        );
        return Math.max(250, longestPeriod);
    }
}
