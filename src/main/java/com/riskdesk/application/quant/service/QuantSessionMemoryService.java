package com.riskdesk.application.quant.service;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.memory.SessionMemory;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * In-memory aggregator for the same-session view fed to the AI advisor.
 * Tracks scan count, observed patterns, executed trades and win rate per
 * instrument, and rolls over at the ET calendar-day boundary.
 */
@Service
public class QuantSessionMemoryService {

    private static final ZoneId ET = ZoneId.of("America/New_York");

    private final Map<Instrument, Aggregate> aggregates = new EnumMap<>(Instrument.class);

    public synchronized SessionMemory getCurrentSession(Instrument instrument) {
        Aggregate a = ensureForToday(instrument);
        double winRate = a.tradesExecuted == 0 ? 0.0 : ((double) a.wins / a.tradesExecuted) * 100.0;
        Map<OrderFlowPattern, Integer> patternsCopy = new EnumMap<>(OrderFlowPattern.class);
        patternsCopy.putAll(a.patternsObserved);
        return new SessionMemory(
            a.day,
            a.scansCount,
            patternsCopy,
            a.tradesExecuted,
            winRate,
            a.lastSetupOutcome == null ? "" : a.lastSetupOutcome,
            a.recentAbnormalities == null ? "" : a.recentAbnormalities
        );
    }

    public synchronized void recordScan(Instrument instrument, OrderFlowPattern observed) {
        Aggregate a = ensureForToday(instrument);
        a.scansCount++;
        if (observed != null) {
            a.patternsObserved.merge(observed, 1, Integer::sum);
        }
    }

    /** Called by the simulation/execution layer once we know how a setup played out. */
    public synchronized void recordOutcome(Instrument instrument, boolean won, String summary) {
        Aggregate a = ensureForToday(instrument);
        a.tradesExecuted++;
        if (won) a.wins++;
        if (summary != null && !summary.isBlank()) a.lastSetupOutcome = summary;
    }

    public synchronized void noteAbnormality(Instrument instrument, String message) {
        Aggregate a = ensureForToday(instrument);
        a.recentAbnormalities = message;
    }

    private Aggregate ensureForToday(Instrument instrument) {
        LocalDate today = Instant.now().atZone(ET).toLocalDate();
        Aggregate a = aggregates.get(instrument);
        if (a == null || !today.equals(a.day)) {
            a = new Aggregate(today);
            aggregates.put(instrument, a);
        }
        return a;
    }

    private static final class Aggregate {
        final LocalDate day;
        int scansCount;
        int tradesExecuted;
        int wins;
        String lastSetupOutcome;
        String recentAbnormalities;
        final Map<OrderFlowPattern, Integer> patternsObserved = new HashMap<>();

        Aggregate(LocalDate day) { this.day = day; }
    }
}
