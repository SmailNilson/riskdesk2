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
 * Confluence Engine — accumulates qualified signals per (instrument, timeframe, direction)
 * within a fixed time window, then flushes a single consolidated Mentor review when the
 * cumulative weight reaches the threshold (3.0).
 *
 * <p>Sub-threshold buffers are logged for backtest analysis and discarded.
 *
 * <p>Thread safety: {@link ConcurrentHashMap#compute} ensures atomicity per buffer key.
 * The scheduled flush poll and the accumulate path may race on the same key; only one
 * wins the {@code remove()} — the other gets null and returns safely.
 */
@Service
public class SignalConfluenceBuffer {

    private static final Logger log = LoggerFactory.getLogger(SignalConfluenceBuffer.class);
    private static final float FLUSH_THRESHOLD = 2.0f;

    private final ConcurrentHashMap<String, BufferEntry> buffers = new ConcurrentHashMap<>();
    private final MentorSignalReviewService mentorSignalReviewService;

    public SignalConfluenceBuffer(MentorSignalReviewService mentorSignalReviewService) {
        this.mentorSignalReviewService = mentorSignalReviewService;
    }

    /**
     * Accumulates a qualified signal into the buffer for the given instrument/timeframe/direction.
     * If the cumulative weight reaches {@value #FLUSH_THRESHOLD}, flushes immediately.
     */
    public void accumulate(Alert alert, String timeframe, String direction,
                           IndicatorSnapshot snap, SignalWeight sw) {
        String key = alert.instrument() + ":" + timeframe + ":" + direction;
        buffers.compute(key, (k, existing) -> {
            if (existing == null) {
                return new BufferEntry(alert, snap, sw, timeframe);
            }
            existing.addSignal(alert, snap, sw);
            return existing;
        });
        // Immediate flush check — outside compute() to avoid nested CHM operations
        BufferEntry entry = buffers.get(key);
        if (entry != null && entry.effectiveWeight() >= FLUSH_THRESHOLD) {
            flush(key);
        }
    }

    /**
     * Polls every 5s for buffers whose fixed window has expired.
     */
    @Scheduled(fixedDelay = 5000)
    public void flushExpiredBuffers() {
        Instant now = Instant.now();
        buffers.forEach((key, entry) -> {
            long windowSeconds = windowForTimeframe(entry.timeframe);
            if (now.isAfter(entry.firstSignalTime.plusSeconds(windowSeconds))) {
                flush(key);
            }
        });
    }

    private void flush(String key) {
        BufferEntry entry = buffers.remove(key);
        if (entry == null) return;

        float weight = entry.effectiveWeight();
        Alert primary = entry.primarySignal();

        if (weight >= FLUSH_THRESHOLD && primary != null) {
            // Read opposing buffer weight
            String oppositeKey = oppositeKey(key);
            float opposingWeight = Optional.ofNullable(buffers.get(oppositeKey))
                    .map(BufferEntry::effectiveWeight).orElse(0f);

            log.info("CONFLUENCE FLUSH [{}] weight={} signals={} primary={} opposing={}",
                    key, weight, entry.signals.size(), primary.category(), opposingWeight);

            mentorSignalReviewService.captureConsolidatedReview(
                    entry.signals, entry.latestSnapshot, weight, primary, opposingWeight);
        } else {
            log.debug("CONFLUENCE EXPIRED [{}] weight={} signals={} — below threshold, skipped",
                    key, weight, entry.signals.size());
        }
    }

    static long windowForTimeframe(String tf) {
        return switch (tf) {
            case "5m"  -> 60;
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

    // ── BufferEntry ────────────────────────────────────────────────────────────

    static class BufferEntry {
        final List<Alert> signals = new ArrayList<>();
        /** Non-cumul families: keeps max weight per family. */
        private final Map<String, Float> nonCumulWeights = new HashMap<>();
        /** Cumulative families: sums weights per family. */
        private final Map<String, Float> cumulWeights = new HashMap<>();
        IndicatorSnapshot latestSnapshot;
        final Instant firstSignalTime;
        final String timeframe;

        BufferEntry(Alert alert, IndicatorSnapshot snap, SignalWeight sw, String timeframe) {
            this.latestSnapshot = snap;
            this.firstSignalTime = Instant.now();
            this.timeframe = timeframe;
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
