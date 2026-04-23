package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.engine.playbook.PlaybookEvaluator;
import com.riskdesk.domain.engine.playbook.model.*;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class PlaybookService {

    private static final Logger log = LoggerFactory.getLogger(PlaybookService.class);
    private static final int ATR_PERIOD = 14;
    private static final int ATR_CANDLE_LIMIT = 50;

    private final IndicatorService indicatorService;
    private final CandleRepositoryPort candleRepository;
    private final PlaybookEvaluator evaluator = new PlaybookEvaluator();

    public PlaybookService(IndicatorService indicatorService, CandleRepositoryPort candleRepository) {
        this.indicatorService = indicatorService;
        this.candleRepository = candleRepository;
    }

    public PlaybookEvaluation evaluate(Instrument instrument, String timeframe) {
        IndicatorSnapshot snapshot = indicatorService.computeSnapshot(instrument, timeframe);
        BigDecimal atr = computeAtr(instrument, timeframe);

        PlaybookInput input = toPlaybookInput(snapshot, atr != null ? atr : BigDecimal.ONE);
        PlaybookEvaluation result = evaluator.evaluate(input);

        log.debug("Playbook {} {} — verdict: {}, score: {}/7",
            instrument, timeframe, result.verdict(), result.checklistScore());
        return result;
    }

    public PlaybookEvaluation evaluateFromSnapshot(IndicatorSnapshot snapshot, BigDecimal atr) {
        PlaybookInput input = toPlaybookInput(snapshot, atr != null ? atr : BigDecimal.ONE);
        return evaluator.evaluate(input);
    }

    /**
     * Maps application-layer IndicatorSnapshot to domain-layer PlaybookInput.
     * This boundary conversion keeps the domain layer free of application DTOs.
     */
    public static PlaybookInput toPlaybookInput(IndicatorSnapshot snap, BigDecimal atr) {
        return new PlaybookInput(
            snap.swingBias(),
            snap.internalBias(),
            snap.swingHigh(),
            snap.swingLow(),
            snap.lastPrice(),
            snap.recentBreaks() != null
                ? snap.recentBreaks().stream()
                    .map(b -> new SmcBreak(b.type(), b.trend(), b.level(), b.structureLevel(), b.breakConfidenceScore()))
                    .toList()
                : List.of(),
            snap.activeOrderBlocks() != null
                ? snap.activeOrderBlocks().stream()
                    .map(ob -> new SmcOrderBlock(ob.type(), ob.status(), ob.high(), ob.low(), ob.mid(), ob.originalType()))
                    .toList()
                : List.of(),
            snap.breakerOrderBlocks() != null
                ? snap.breakerOrderBlocks().stream()
                    .map(ob -> new SmcOrderBlock(ob.type(), ob.status(), ob.high(), ob.low(), ob.mid(), ob.originalType()))
                    .toList()
                : List.of(),
            snap.activeFairValueGaps() != null
                ? snap.activeFairValueGaps().stream()
                    .map(fvg -> new SmcFvg(fvg.bias(), fvg.top(), fvg.bottom()))
                    .toList()
                : List.of(),
            snap.equalHighs() != null
                ? snap.equalHighs().stream()
                    .map(eq -> new SmcEqualLevel(eq.type(), eq.price(), eq.touchCount()))
                    .toList()
                : List.of(),
            snap.equalLows() != null
                ? snap.equalLows().stream()
                    .map(eq -> new SmcEqualLevel(eq.type(), eq.price(), eq.touchCount()))
                    .toList()
                : List.of(),
            snap.pocPrice(),
            snap.valueAreaHigh(),
            snap.valueAreaLow(),
            snap.deltaFlowBias(),
            snap.buyRatio(),
            snap.currentZone(),
            snap.sessionPdZone(),
            atr
        );
    }

    /**
     * Computes the ATR for the given instrument/timeframe. Exposed (vs. private) so
     * the presentation layer can reuse the exact same ATR source when enriching an
     * {@link com.riskdesk.domain.engine.playbook.agent.AgentContext}. Returns
     * {@code null} when there aren't enough candles or the repository fails — callers
     * must handle the null case (never silently substitute {@code BigDecimal.ONE},
     * which would corrupt all ATR-relative sizing / trap-detection calculations).
     */
    public BigDecimal computeAtr(Instrument instrument, String timeframe) {
        try {
            List<Candle> candles = candleRepository.findRecentCandles(instrument, timeframe, ATR_CANDLE_LIMIT);
            if (candles == null || candles.size() <= ATR_PERIOD) return null;
            return AtrCalculator.compute(candles, ATR_PERIOD);
        } catch (Exception e) {
            log.warn("Failed to compute ATR for {} {}: {}", instrument, timeframe, e.getMessage());
            return null;
        }
    }
}
