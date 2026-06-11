package com.riskdesk.domain.quant.scanlog;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.DeltaSnapshot;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.MarketSnapshot;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One persisted row per Quant 7-Gates scan tick — the raw INPUTS the gates saw
 * (delta, buy%, absorption window, dist/accu confidence, price) plus the key
 * OUTPUTS (scores, pattern, per-gate verdicts).
 *
 * <p>This is the durable time series the live pipeline never had: the rolling
 * delta window is ephemeral (`TickDataPort`), so before this log a gate-replay
 * backtest or a threshold sweep ("what if G3 required Δ &lt; −150?") was
 * impossible — `tick_log` reconstruction can't reproduce the classifier state
 * the gates actually observed (BBO cache, tick-rule fallback, watchdog
 * abstains). One row per scan per instrument (~4.3k rows/day for 3
 * instruments), 90-day retention alongside the order-flow event tables.</p>
 *
 * <p>Non-signal scans are logged too — a backtest over signals-only rows would
 * be survivorship-biased.</p>
 *
 * @param instrument        scanned instrument
 * @param scannedAt         scan timestamp (UTC)
 * @param price             live price at scan time (null when no quote)
 * @param priceSource       provenance of the price (e.g. {@code LIVE_PUSH})
 * @param delta             rolling-window delta AS SEEN BY THE GATES — null when
 *                          the feed was stale/down (gates abstained)
 * @param buyRatioPct       buy ratio % as seen by the gates (null when stale)
 * @param deltaSource       provenance of the RAW delta window (e.g.
 *                          {@code REAL_TICKS}, {@code CLV_ESTIMATED}) — may be
 *                          non-null while {@link #delta} is null, which is the
 *                          stale-dropped diagnostic case
 * @param absFreshTotal     absorption events in the 3-minute window
 * @param absBull8Count     bullish ABS events with score ≥ 8 in the window
 * @param absBear8Count     bearish ABS events with score ≥ 8 in the window
 * @param absMaxScore       max absorption score over the window
 * @param dominantSide      BULL / BEAR / MIX
 * @param distType          most recent DIST/ACCU type in the 10-minute window
 * @param distConf          its confidence (0–100)
 * @param cycleType         most recent cycle type in the window
 * @param cyclePhase        its phase
 * @param score             SHORT gates passed (0–7)
 * @param longScore         LONG gates passed (0–7)
 * @param patternType       detected order-flow pattern enum name
 * @param patternLabel      human label (e.g. "Distribution silencieuse")
 * @param patternConfidence LOW / MEDIUM / HIGH
 * @param patternActionShort canonical SHORT-view action (TRADE/WAIT/AVOID);
 *                          the LONG view is the deterministic mirror
 * @param gateResults       per-gate verdict summary, gate name → "PASS|FAIL|ABSTAIN — reason"
 */
public record QuantScanSnapshot(
    Instrument instrument,
    Instant scannedAt,
    Double price,
    String priceSource,
    Double delta,
    Double buyRatioPct,
    String deltaSource,
    int absFreshTotal,
    int absBull8Count,
    int absBear8Count,
    double absMaxScore,
    String dominantSide,
    String distType,
    Integer distConf,
    String cycleType,
    String cyclePhase,
    int score,
    int longScore,
    String patternType,
    String patternLabel,
    String patternConfidence,
    String patternActionShort,
    Map<String, String> gateResults
) {

    public QuantScanSnapshot {
        gateResults = gateResults == null ? Map.of() : Map.copyOf(gateResults);
    }

    /**
     * Builds the log row from the scan pipeline's own objects — the
     * {@link MarketSnapshot} fed to the evaluator (inputs), the raw
     * {@link DeltaSnapshot} before staleness filtering (provenance), the
     * evaluated {@link QuantSnapshot} (outputs) and the detected pattern.
     */
    public static QuantScanSnapshot from(MarketSnapshot inputs,
                                         DeltaSnapshot rawDelta,
                                         QuantSnapshot evaluated,
                                         PatternAnalysis pattern) {
        Map<String, String> gates = new LinkedHashMap<>();
        for (Gate gate : Gate.values()) {
            GateResult r = evaluated.gates().get(gate);
            if (r == null) continue;
            String verdict = r.abstain() ? "ABSTAIN" : (r.ok() ? "PASS" : "FAIL");
            gates.put(gate.name(), r.reason() == null || r.reason().isBlank()
                ? verdict : verdict + " — " + r.reason());
        }
        return new QuantScanSnapshot(
            evaluated.instrument(),
            inputs.now(),
            inputs.currentPrice(),
            inputs.priceSource(),
            inputs.delta(),
            inputs.buyPct(),
            rawDelta == null ? null : rawDelta.source(),
            inputs.absFreshTotal(),
            inputs.absBull8Count(),
            inputs.absBear8Count(),
            inputs.absMaxScore(),
            inputs.dominantSide(),
            inputs.distType(),
            inputs.distConf(),
            inputs.cycleType(),
            inputs.cyclePhase(),
            evaluated.score(),
            evaluated.longScore(),
            pattern == null ? null : pattern.type().name(),
            pattern == null ? null : pattern.label(),
            pattern == null ? null : pattern.confidence().name(),
            pattern == null ? null : pattern.action().name(),
            gates);
    }
}
