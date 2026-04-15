package com.riskdesk.domain.engine.playbook.agent;

import com.riskdesk.domain.engine.playbook.model.Confidence;
import com.riskdesk.domain.engine.playbook.model.PlaybookEvaluation;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Agent 2 — Divergence & Momentum Hunter.
 *
 * Detects what the Playbook CAN'T see: whether momentum indicators
 * support or contradict the structural setup.
 *
 * A 6/7 zone retest with bearish RSI divergence is a weak LONG.
 * A 5/7 setup with all momentum aligned is stronger than it looks.
 */
public class DivergenceHunterAgent implements TradingAgent {

    @Override
    public String name() { return "Divergence-Hunter"; }

    @Override
    public AgentVerdict evaluate(PlaybookEvaluation playbook, AgentContext context) {
        if (playbook.bestSetup() == null) {
            return AgentVerdict.skip(name(), "No setup to analyze");
        }

        var mom = context.momentum();
        if (mom == null) {
            return AgentVerdict.skip(name(), "No momentum data available");
        }

        String direction = playbook.filters().tradeDirection().name();
        String swingBias = playbook.filters().swingBias();

        List<String> warnings = new ArrayList<>();
        List<String> confirms = new ArrayList<>();
        int negativeSignals = 0;
        int positiveSignals = 0;

        // 1. RSI divergence (price vs RSI disagreement)
        if (mom.hasRsiBearishDivergence(swingBias) && "LONG".equalsIgnoreCase(direction)) {
            warnings.add("RSI bearish divergence — price bullish but RSI weakening (<55)");
            negativeSignals++;
        } else if (mom.hasRsiBullishDivergence(swingBias) && "SHORT".equalsIgnoreCase(direction)) {
            warnings.add("RSI bullish divergence — price bearish but RSI holding (>45)");
            negativeSignals++;
        }

        // 2. RSI extreme (oversold for LONG = good, overbought for LONG = bad)
        if (mom.rsi() != null) {
            if ("LONG".equalsIgnoreCase(direction) && mom.rsi().doubleValue() > 70) {
                warnings.add(String.format("RSI overbought (%.1f) — buying at the top", mom.rsi()));
                negativeSignals++;
            } else if ("SHORT".equalsIgnoreCase(direction) && mom.rsi().doubleValue() < 30) {
                warnings.add(String.format("RSI oversold (%.1f) — selling at the bottom", mom.rsi()));
                negativeSignals++;
            } else if ("LONG".equalsIgnoreCase(direction) && mom.rsi().doubleValue() < 40) {
                confirms.add(String.format("RSI in buy zone (%.1f)", mom.rsi()));
                positiveSignals++;
            } else if ("SHORT".equalsIgnoreCase(direction) && mom.rsi().doubleValue() > 60) {
                confirms.add(String.format("RSI in sell zone (%.1f)", mom.rsi()));
                positiveSignals++;
            }
        }

        // 3. MACD alignment
        if (mom.macdHistogram() != null) {
            double hist = mom.macdHistogram().doubleValue();
            if ("LONG".equalsIgnoreCase(direction) && hist < 0
                && "BEARISH".equalsIgnoreCase(mom.macdCrossover())) {
                warnings.add("MACD bearish — histogram < 0 with bearish cross");
                negativeSignals++;
            } else if ("SHORT".equalsIgnoreCase(direction) && hist > 0
                && "BULLISH".equalsIgnoreCase(mom.macdCrossover())) {
                warnings.add("MACD bullish — histogram > 0 with bullish cross");
                negativeSignals++;
            } else if (mom.momentumConfirms(direction)) {
                confirms.add("MACD aligned with direction");
                positiveSignals++;
            }
        }

        // 4. WaveTrend extreme zones
        if (mom.wtWt1() != null) {
            double wt1 = mom.wtWt1().doubleValue();
            if ("LONG".equalsIgnoreCase(direction) && wt1 > 60) {
                warnings.add(String.format("WaveTrend overbought (%.1f) — late entry risk", wt1));
                negativeSignals++;
            } else if ("SHORT".equalsIgnoreCase(direction) && wt1 < -60) {
                warnings.add(String.format("WaveTrend oversold (%.1f) — late entry risk", wt1));
                negativeSignals++;
            }
        }

        // 5. Stochastic alignment
        if (mom.stochSignal() != null) {
            if ("LONG".equalsIgnoreCase(direction) && "OVERBOUGHT".equalsIgnoreCase(mom.stochSignal())) {
                warnings.add("Stochastic overbought — upside exhausted");
                negativeSignals++;
            } else if ("SHORT".equalsIgnoreCase(direction) && "OVERSOLD".equalsIgnoreCase(mom.stochSignal())) {
                warnings.add("Stochastic oversold — downside exhausted");
                negativeSignals++;
            }
        }

        // 6. Overall momentum verdict
        boolean momentumContradicts = mom.momentumContradicts(direction);
        if (momentumContradicts) {
            negativeSignals++;
        }

        // Build verdict
        Confidence confidence;
        String reasoning;

        if (negativeSignals >= 3) {
            confidence = Confidence.LOW;
            reasoning = String.format("MOMENTUM DIVERGENCE: %d warnings — %s",
                negativeSignals, String.join("; ", warnings));
            return new AgentVerdict(name(), confidence, playbook.filters().tradeDirection(),
                reasoning, Map.of("size_pct", 0.003, "divergence_count", negativeSignals));
        } else if (negativeSignals >= 2) {
            confidence = Confidence.LOW;
            reasoning = String.format("Momentum fragile: %d warnings — %s",
                negativeSignals, String.join("; ", warnings));
            return new AgentVerdict(name(), confidence, playbook.filters().tradeDirection(),
                reasoning, Map.of("size_pct", 0.005, "divergence_count", negativeSignals));
        } else if (positiveSignals >= 3 && negativeSignals == 0) {
            confidence = Confidence.HIGH;
            reasoning = String.format("Momentum fully aligned: %s",
                String.join("; ", confirms));
        } else if (positiveSignals >= 2) {
            confidence = Confidence.HIGH;
            reasoning = String.format("Momentum supports: %d positive, %d negative",
                positiveSignals, negativeSignals);
        } else {
            confidence = Confidence.MEDIUM;
            reasoning = String.format("Momentum neutral: %d positive, %d negative",
                positiveSignals, negativeSignals);
        }

        return new AgentVerdict(name(), confidence, playbook.filters().tradeDirection(),
            reasoning, Map.of("positive", positiveSignals, "negative", negativeSignals));
    }
}
