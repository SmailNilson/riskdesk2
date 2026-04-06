package com.riskdesk.application.service;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.domain.alert.model.Alert;
import com.riskdesk.domain.alert.model.SignalWeight;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Confluence Engine v2 — Arm &amp; Fire multi-timeframe model.
 *
 * <h3>Arm (HTF: 10m, 1H)</h3>
 * Qualified signals accumulate per (instrument, timeframe, direction).
 * When cumulative weight &ge; 3.0, the buffer enters ARMED state — it does NOT
 * flush to Gemini. It waits for a LTF trigger.
 *
 * <h3>Fire (LTF: 5m)</h3>
 * 5m signals are NEVER accumulated. They act as triggers only.
 * When a 5m signal arrives, all ARMED buffers matching the same instrument
 * and direction are FIRED — each producing a separate Gemini review.
 * If no HTF is ARMED, the 5m signal is logged and discarded.
 *
 * <h3>TTL</h3>
 * ARMED buffers expire after one candle duration from armement time
 * (10m → 600s, 1H → 3600s). Expired buffers are discarded.
 *
 * <p>Thread safety: {@link ConcurrentHashMap#compute} for accumulation,
 * atomic {@code remove()} for fire/expiry. No additional locks needed.
 */
@Service
public class SignalConfluenceBuffer {

    private static final Logger log = LoggerFactory.getLogger(SignalConfluenceBuffer.class);
    private static final float ARM_THRESHOLD = 3.0f;

    private final ConcurrentHashMap<String, BufferEntry> buffers = new ConcurrentHashMap<>();
    private final MentorSignalReviewService mentorSignalReviewService;

    public SignalConfluenceBuffer(MentorSignalReviewService mentorSignalReviewService) {
        this.mentorSignalReviewService = mentorSignalReviewService;
    }

    /**
     * Accumulates a HTF signal (10m or 1H) into the buffer.
     * When weight reaches {@value #ARM_THRESHOLD}, transitions to ARMED state.
     * Does NOT flush to Gemini — waits for a 5m trigger via {@link #trigger}.
     */
    public void accumulate(Alert alert, String timeframe, String direction,
                           IndicatorSnapshot snap, SignalWeight sw) {
        String key = alert.instrument() + ":" + timeframe + ":" + direction;
        buffers.compute(key, (k, existing) -> {
            if (existing == null) {
                return new BufferEntry(alert, snap, sw, timeframe, direction, alert.instrument());
            }
            existing.addSignal(alert, snap, sw);
            return existing;
        });
        // Check armement outside compute() to avoid nested CHM operations
        BufferEntry entry = buffers.get(key);
        if (entry != null && entry.state == BufferState.ACCUMULATING
                && entry.effectiveWeight() >= ARM_THRESHOLD) {
            entry.arm();
            log.info("ARMED [{}] weight={} signals={} primary={}",
                    key, entry.effectiveWeight(), entry.signals.size(),
                    entry.primarySignal() != null ? entry.primarySignal().category() : "?");
        }
    }

    /**
     * Processes a 5m signal as a trigger — NOT accumulated.
     * Scans all ARMED buffers for same instrument + same direction.
     * Each matching ARMED buffer is FIRED (separate Gemini review).
     * If no match, the signal is logged and discarded.
     */
    public void trigger(Alert alert, String direction, IndicatorSnapshot ltfSnap) {
        String instrument = alert.instrument();
        boolean fired = false;

        for (Map.Entry<String, BufferEntry> e : buffers.entrySet()) {
            BufferEntry entry = e.getValue();
            if (entry.state != BufferState.ARMED) continue;
            if (!entry.instrument.equals(instrument)) continue;
            if (!entry.direction.equals(direction)) continue;

            // Fire this armed buffer
            BufferEntry removed = buffers.remove(e.getKey());
            if (removed != null) {
                fire(e.getKey(), removed, alert, ltfSnap);
                fired = true;
            }
        }

        if (!fired) {
            log.debug("5m TRIGGER NO-MATCH [{}:5m:{}] — no ARMED HTF buffer, discarded",
                    instrument, direction);
        }
    }

    /**
     * Fires an armed buffer — sends HTF signals + LTF trigger to Gemini.
     */
    private void fire(String key, BufferEntry entry, Alert ltfTrigger, IndicatorSnapshot ltfSnap) {
        Alert primary = entry.primarySignal();
        if (primary == null) return;

        String tradeType = "1h".equals(entry.timeframe) ? "DAY_TRADE" : "SCALP";

        // Read opposing buffer weight
        String oppositeKey = oppositeKey(key);
        float opposingWeight = Optional.ofNullable(buffers.get(oppositeKey))
                .map(BufferEntry::effectiveWeight).orElse(0f);

        log.info("ARM&FIRE [{}] type={} weight={} htfSignals={} ltfTrigger={} opposing={}",
                key, tradeType, entry.effectiveWeight(), entry.signals.size(),
                ltfTrigger.category(), opposingWeight);

        mentorSignalReviewService.captureConsolidatedReview(
                entry.signals, entry.latestSnapshot, entry.effectiveWeight(),
                primary, opposingWeight, ltfTrigger, ltfSnap, tradeType);
    }

    /**
     * Polls every 5s — cleans up expired buffers.
     * ACCUMULATING buffers: expire after window (10m→120s, 1h→300s) — sub-threshold, discard.
     * ARMED buffers: expire after TTL (10m→600s, 1h→3600s) — no LTF trigger arrived, discard.
     */
    @Scheduled(fixedDelay = 5000)
    public void flushExpiredBuffers() {
        Instant now = Instant.now();
        buffers.forEach((key, entry) -> {
            if (entry.state == BufferState.ARMED) {
                long ttl = ttlForTimeframe(entry.timeframe);
                if (now.isAfter(entry.armedAt.plusSeconds(ttl))) {
                    BufferEntry removed = buffers.remove(key);
                    if (removed != null) {
                        log.info("ARMED EXPIRED [{}] weight={} signals={} — no LTF trigger within {}s",
                                key, removed.effectiveWeight(), removed.signals.size(), ttl);
                    }
                }
            } else {
                long window = accumulationWindowForTimeframe(entry.timeframe);
                if (now.isAfter(entry.firstSignalTime.plusSeconds(window))) {
                    BufferEntry removed = buffers.remove(key);
                    if (removed != null) {
                        log.debug("ACCUMULATING EXPIRED [{}] weight={} signals={} — sub-threshold, discarded",
                                key, removed.effectiveWeight(), removed.signals.size());
                    }
                }
            }
        });
    }

    // ── TTL and window helpers ─────────────────────────────────────────────────

    /** TTL for ARMED state = one candle duration. */
    static long ttlForTimeframe(String tf) {
        return switch (tf) {
            case "10m" -> 600;   // 10 minutes
            case "1h"  -> 3600;  // 60 minutes
            default    -> 600;
        };
    }

    /** Accumulation window for sub-threshold signals still building up. */
    static long accumulationWindowForTimeframe(String tf) {
        return switch (tf) {
            case "10m" -> 120;
            case "1h"  -> 300;
            default    -> 120;
        };
    }

    static String oppositeKey(String key) {
        if (key.endsWith(":LONG"))  return key.substring(0, key.length() - 4) + "SHORT";
        if (key.endsWith(":SHORT")) return key.substring(0, key.length() - 5) + "LONG";
        return key;
    }

    // ── State ──────────────────────────────────────────────────────────────────

    enum BufferState { ACCUMULATING, ARMED }

    // ── BufferEntry ────────────────────────────────────────────────────────────

    static class BufferEntry {
        final List<Alert> signals = new ArrayList<>();
        private final Map<String, Float> nonCumulWeights = new HashMap<>();
        private final Map<String, Float> cumulWeights = new HashMap<>();
        IndicatorSnapshot latestSnapshot;
        final Instant firstSignalTime;
        final String timeframe;
        final String direction;
        final String instrument;
        volatile BufferState state = BufferState.ACCUMULATING;
        volatile Instant armedAt;

        BufferEntry(Alert alert, IndicatorSnapshot snap, SignalWeight sw,
                    String timeframe, String direction, String instrument) {
            this.latestSnapshot = snap;
            this.firstSignalTime = Instant.now();
            this.timeframe = timeframe;
            this.direction = direction;
            this.instrument = instrument;
            addSignal(alert, snap, sw);
        }

        void addSignal(Alert alert, IndicatorSnapshot snap, SignalWeight sw) {
            signals.add(alert);
            latestSnapshot = snap;
            if (SignalWeight.isNonCumulFamily(sw.family())) {
                nonCumulWeights.merge(sw.family(), sw.weight(), Math::max);
            } else {
                cumulWeights.merge(sw.family() + ":" + sw.name(), sw.weight(), (old, v) -> old);
            }
        }

        void arm() {
            this.state = BufferState.ARMED;
            this.armedAt = Instant.now();
        }

        float effectiveWeight() {
            float total = 0f;
            for (float w : nonCumulWeights.values()) total += w;
            for (float w : cumulWeights.values()) total += w;
            return total;
        }

        Alert primarySignal() {
            Alert best = null;
            int bestPriority = Integer.MAX_VALUE;
            for (Alert alert : signals) {
                SignalWeight sw = SignalWeight.fromAlert(alert);
                if (sw != null && sw.priority() < bestPriority) {
                    bestPriority = sw.priority();
                    best = alert;
                }
            }
            return best;
        }
    }
}
