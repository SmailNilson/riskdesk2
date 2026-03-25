package com.riskdesk.application.service;

import com.riskdesk.domain.engine.smc.MarketStructure;
import com.riskdesk.domain.model.Candle;

import java.util.Optional;

/**
 * Applies optional higher-timeframe SMC entry rules before a trade is opened.
 */
public class EntryFilterService {

    public enum Direction { LONG, SHORT }

    public record Config(
        boolean enableSmcFilter,
        boolean requireCloseNearLevel,
        HigherTimeframeLevelService.ThresholdMode nearThresholdMode,
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
        public static Config disabled() {
            return new Config(
                false,
                true,
                HigherTimeframeLevelService.ThresholdMode.ATR,
                0.25,
                14,
                0.25,
                false,
                false,
                true,
                true,
                false,
                1,
                0,
                false
            );
        }
    }

    public record Decision(
        boolean accepted,
        String reason,
        int score,
        HigherTimeframeLevelService.LevelSelection levelSelection,
        MarketStructureService.StructureContext structureContext
    ) {}

    private final HigherTimeframeLevelService higherTimeframeLevelService;

    public EntryFilterService(HigherTimeframeLevelService higherTimeframeLevelService) {
        this.higherTimeframeLevelService = higherTimeframeLevelService;
    }

    public Decision evaluate(
        Direction direction,
        Candle candle,
        double atrValue,
        Config config,
        HigherTimeframeLevelService.LevelIndex levelIndex,
        MarketStructureService.StructureContextIndex structureIndex
    ) {
        if (!config.enableSmcFilter()) {
            return new Decision(true, "SMC filter disabled", 0, null, MarketStructureService.StructureContext.empty());
        }

        double close = candle.getClose().doubleValue();
        MarketStructureService.StructureContext context = structureIndex.contextAt(candle.getTimestamp());
        Optional<HigherTimeframeLevelService.LevelSelection> levelSelection = direction == Direction.LONG
            ? higherTimeframeLevelService.nearestSupportAbove1H(
                levelIndex,
                candle.getTimestamp(),
                close,
                config.nearThresholdMode(),
                config.nearThresholdValue(),
                atrValue,
                config.tickSize(),
                config.maxLevelAgeBars()
            )
            : higherTimeframeLevelService.nearestResistanceAbove1H(
                levelIndex,
                candle.getTimestamp(),
                close,
                config.nearThresholdMode(),
                config.nearThresholdValue(),
                atrValue,
                config.tickSize(),
                config.maxLevelAgeBars()
            );

        if (config.requireCloseNearLevel() && levelSelection.isEmpty()) {
            return new Decision(
                false,
                direction + " rejected: no confirmed HTF " + (direction == Direction.LONG ? "support" : "resistance")
                    + " within " + config.nearThresholdValue() + " " + config.nearThresholdMode(),
                0,
                null,
                context
            );
        }

        int score = 0;
        if (levelSelection.isPresent()) {
            score += 1;
        }

        boolean bullishStructure = context.bullishConfirmation(config.useBos(), config.useChoch());
        boolean bearishStructure = context.bearishConfirmation(config.useBos(), config.useChoch());

        if (direction == Direction.LONG && bullishStructure) {
            score += 1;
        }
        if (direction == Direction.SHORT && bearishStructure) {
            score += 1;
        }

        if (direction == Direction.LONG && config.requireBullishStructureForLong() && !bullishStructure) {
            return new Decision(
                false,
                "LONG rejected: bearish/undefined HTF structure context"
                    + levelSuffix(levelSelection.orElse(null)),
                score,
                levelSelection.orElse(null),
                context
            );
        }
        if (direction == Direction.SHORT && config.requireBearishStructureForShort() && !bearishStructure) {
            return new Decision(
                false,
                "SHORT rejected: bullish/undefined HTF structure context"
                    + levelSuffix(levelSelection.orElse(null)),
                score,
                levelSelection.orElse(null),
                context
            );
        }

        int requiredScore = Math.max(1, config.minConfirmationScore());
        if (score < requiredScore) {
            return new Decision(
                false,
                direction + " rejected: confirmation score " + score + " < " + requiredScore
                    + levelSuffix(levelSelection.orElse(null)),
                score,
                levelSelection.orElse(null),
                context
            );
        }

        String acceptedReason = direction
            + " accepted: existing signal + close near "
            + (direction == Direction.LONG ? "HTF support" : "HTF resistance")
            + levelSuffix(levelSelection.orElse(null))
            + structureSuffix(context, bullishStructure, bearishStructure);

        return new Decision(
            true,
            acceptedReason,
            score,
            levelSelection.orElse(null),
            context
        );
    }

    private String levelSuffix(HigherTimeframeLevelService.LevelSelection selection) {
        if (selection == null) {
            return "";
        }
        return " [" + selection.level().timeframe()
            + " @" + selection.level().price().stripTrailingZeros().toPlainString()
            + ", distance=" + round(selection.distance())
            + ", threshold=" + round(selection.threshold())
            + "]";
    }

    private String structureSuffix(
        MarketStructureService.StructureContext context,
        boolean bullishStructure,
        boolean bearishStructure
    ) {
        if (context.lastEvent() == null) {
            return "";
        }
        String bias = bullishStructure ? " bullish" : bearishStructure ? " bearish" : " neutral";
        return " + " + context.lastEvent().type() + bias + " structure";
    }

    private String round(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}
