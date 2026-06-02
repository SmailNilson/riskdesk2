package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationPublisher;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import com.riskdesk.domain.quant.simulation.port.Quant7GatesSimulationRepositoryPort;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulation harness validating the Quant 7-Gates evaluator end-to-end.
 *
 * <p>Mirrors the WTX strategy/playbook simulators ({@code PlaybookAutomationService},
 * {@code TradeSimulationService}) but stays decoupled from Mentor — the rows
 * live in memory only and never touch {@code MentorSignalReviewRecord}, in
 * line with the "Simulation Decoupling Rule" in CLAUDE.md.
 *
 * <p><b>Entry rule</b> (per direction):
 * <ul>
 *   <li>The latest pattern is HIGH confidence.</li>
 *   <li>The reason carries {@code [Δ CONFIRMED]} AND {@code [ABS BULL ACTIVE]}
 *       (LONG) or {@code [ABS BEAR ACTIVE]} (SHORT).</li>
 *   <li>{@link PatternAnalysis#actionFor(PatternAnalysis.TradeBias)} from the
 *       trade direction returns {@code TRADE}.</li>
 *   <li>No simulation is already open for the same instrument + direction
 *       (idempotence — one in-flight setup per side per instrument).</li>
 * </ul>
 *
 * <p><b>Exit rules</b> (whichever triggers first):
 * <ul>
 *   <li>Pattern action seen from the trade direction flips to {@code AVOID} →
 *       {@link Quant7GatesSimulationStatus#CLOSED_FLOW_AVOID} at live price.</li>
 *   <li>Live price crosses the configured TP1/TP2 offset →
 *       {@link Quant7GatesSimulationStatus#CLOSED_TP1} /
 *       {@link Quant7GatesSimulationStatus#CLOSED_TP2}.</li>
 *   <li>Live price crosses the configured SL offset →
 *       {@link Quant7GatesSimulationStatus#CLOSED_SL}.</li>
 * </ul>
 *
 * <p>The service is driven from {@code QuantGateService.scan(...)} after the
 * pattern + price are resolved, so the harness sees the exact same data the
 * dashboard renders.
 */
@Service
public class Quant7GatesSimulationService {

    private static final Logger log = LoggerFactory.getLogger(Quant7GatesSimulationService.class);

    /** Maximum closed-trade history kept per instrument (ring buffer). */
    public static final int CLOSED_HISTORY_CAP = 50;

    static final String TAG_DELTA = "[Δ CONFIRMED]";
    static final String TAG_ABS_BULL = "[ABS BULL ACTIVE]";
    static final String TAG_ABS_BEAR = "[ABS BEAR ACTIVE]";

    private final ObjectProvider<Quant7GatesSimulationPublisher> publisherProvider;
    private final ObjectProvider<Quant7GatesSimulationRepositoryPort> repositoryProvider;
    private final AtomicLong sequence = new AtomicLong(1);

    /**
     * Open + closed simulations bucketed by instrument. Closed entries are
     * capped at {@link #CLOSED_HISTORY_CAP} per instrument so memory stays
     * bounded. Read access happens from the controller thread, write access
     * from the scheduler thread — synchronised on {@code this}.
     */
    private final Map<Instrument, List<Quant7GatesSimulation>> byInstrument = new EnumMap<>(Instrument.class);

    public Quant7GatesSimulationService(
            ObjectProvider<Quant7GatesSimulationPublisher> publisherProvider,
            ObjectProvider<Quant7GatesSimulationRepositoryPort> repositoryProvider) {
        this.publisherProvider = publisherProvider;
        this.repositoryProvider = repositoryProvider;
    }

    /**
     * Rehydrates harness state from the durable store on startup (no-op when no
     * repository is wired — e.g. unit tests). Seeds the id sequence past the
     * highest persisted id so restarts never reuse an id, and reloads still-OPEN
     * rows so in-flight trades keep tracking. Closed history is read on demand
     * from the store, so it is intentionally NOT loaded into the capped buckets.
     */
    @PostConstruct
    synchronized void rehydrate() {
        Quant7GatesSimulationRepositoryPort repo = repo();
        if (repo == null) return;
        try {
            long max = repo.maxId();
            if (max >= sequence.get()) sequence.set(max + 1);
            int reloaded = 0;
            for (Quant7GatesSimulation open : repo.findAllOpen()) {
                bucket(open.instrument()).add(open);
                reloaded++;
            }
            log.info("quant-sim rehydrated from DB: nextId={} openRowsReloaded={}", sequence.get(), reloaded);
        } catch (RuntimeException e) {
            log.warn("quant-sim rehydrate failed (continuing in-memory): {}", e.toString());
        }
    }

    private Quant7GatesSimulationRepositoryPort repo() {
        return repositoryProvider == null ? null : repositoryProvider.getIfAvailable();
    }

    /**
     * Drives the harness from one snapshot tick. Side-effects:
     *
     * <ol>
     *   <li>If a simulation is open for the instrument, checks SL/TP/flow-AVOID
     *       and closes it on the first hit.</li>
     *   <li>If no simulation is open on the side that just passed the entry
     *       rule, opens a fresh one at {@code snapshot.price()}.</li>
     * </ol>
     *
     * <p>Either step may publish a row through {@link Quant7GatesSimulationPublisher}.
     */
    public synchronized void onSnapshot(Instrument instrument,
                                         QuantSnapshot snapshot,
                                         PatternAnalysis pattern) {
        if (snapshot == null || snapshot.price() == null) return;
        double livePrice = snapshot.price();
        String liveSource = snapshot.priceSource() == null ? "" : snapshot.priceSource();
        Instant now = Instant.now();

        // 1) Process exits on currently-open simulations.
        List<Quant7GatesSimulation> rows = bucket(instrument);
        for (int i = 0; i < rows.size(); i++) {
            Quant7GatesSimulation sim = rows.get(i);
            if (!sim.isOpen()) continue;
            Quant7GatesSimulation closed = maybeClose(sim, livePrice, liveSource, pattern, now);
            if (closed != null) {
                rows.set(i, closed);
                publish(closed);
                persist(closed);
            } else {
                // Refresh mark-to-market view so the frontend P&L stays live —
                // tag with the CURRENT snapshot's source, not the row's entry
                // source. A trade entered LIVE_PUSH but marked on a fallback
                // tick must surface the fallback pill until the feed recovers.
                Quant7GatesSimulation mtm = sim.markToMarket(livePrice, liveSource);
                rows.set(i, mtm);
                publish(mtm);
            }
        }

        // 2) Consider opening a fresh simulation on either side.
        tryOpen(instrument, snapshot, pattern, Quant7GatesSimulation.Direction.LONG, livePrice, now);
        tryOpen(instrument, snapshot, pattern, Quant7GatesSimulation.Direction.SHORT, livePrice, now);

        capClosedHistory(instrument);
    }

    private void tryOpen(Instrument instrument,
                          QuantSnapshot snapshot,
                          PatternAnalysis pattern,
                          Quant7GatesSimulation.Direction direction,
                          double livePrice,
                          Instant now) {
        if (!isEntryQualified(pattern, direction)) return;
        if (hasOpen(instrument, direction)) return;

        double sl;
        double tp1;
        double tp2;
        if (direction == Quant7GatesSimulation.Direction.LONG) {
            sl = snapshot.suggestedSL_LONG();
            tp1 = snapshot.suggestedTP1_LONG();
            tp2 = snapshot.suggestedTP2_LONG();
        } else {
            sl = snapshot.suggestedSL();
            tp1 = snapshot.suggestedTP1();
            tp2 = snapshot.suggestedTP2();
        }

        Quant7GatesSimulation opened = new Quant7GatesSimulation(
            sequence.getAndIncrement(),
            instrument,
            direction,
            livePrice,
            sl,
            tp1,
            tp2,
            now,
            describeEntry(pattern, direction),
            // Carry the snapshot's price-source through so the panel and any
            // downstream consumer can distinguish a live-tick entry from one
            // driven by a DB fallback during a feed outage.
            snapshot.priceSource() == null ? "" : snapshot.priceSource(),
            Quant7GatesSimulationStatus.OPEN,
            // exitPrice / exitPriceSource / closedAt / exitReason are unset on OPEN —
            // P&L is 0 until the first markToMarket lands.
            null, "", null, null, 0.0, 0.0);
        bucket(instrument).add(opened);
        log.info("quant-sim OPEN id={} instr={} dir={} entry={} src={} sl={} tp1={} tp2={} reason=\"{}\"",
            opened.id(), instrument, direction, livePrice, opened.priceSource(), sl, tp1, tp2, opened.entryReason());
        publish(opened);
        persist(opened);
    }

    private Quant7GatesSimulation maybeClose(Quant7GatesSimulation sim,
                                              double livePrice,
                                              String liveSource,
                                              PatternAnalysis pattern,
                                              Instant now) {
        Quant7GatesSimulation.Direction dir = sim.direction();

        // SL/TP first — they reflect the trade-plan exits the dashboard shows.
        // The exit-price-source on close mirrors the CURRENT snapshot feed so
        // a fallback-driven close on a live-entry row is still labelled
        // correctly for the operator.
        if (dir == Quant7GatesSimulation.Direction.LONG) {
            if (livePrice <= sim.stopLoss()) {
                return sim.close(livePrice, liveSource, now, "SL hit", Quant7GatesSimulationStatus.CLOSED_SL);
            }
            if (livePrice >= sim.takeProfit2()) {
                return sim.close(livePrice, liveSource, now, "TP2 hit", Quant7GatesSimulationStatus.CLOSED_TP2);
            }
            if (livePrice >= sim.takeProfit1()) {
                return sim.close(livePrice, liveSource, now, "TP1 hit", Quant7GatesSimulationStatus.CLOSED_TP1);
            }
        } else {
            if (livePrice >= sim.stopLoss()) {
                return sim.close(livePrice, liveSource, now, "SL hit", Quant7GatesSimulationStatus.CLOSED_SL);
            }
            if (livePrice <= sim.takeProfit2()) {
                return sim.close(livePrice, liveSource, now, "TP2 hit", Quant7GatesSimulationStatus.CLOSED_TP2);
            }
            if (livePrice <= sim.takeProfit1()) {
                return sim.close(livePrice, liveSource, now, "TP1 hit", Quant7GatesSimulationStatus.CLOSED_TP1);
            }
        }

        // Flow flipped to AVOID for the active side → harness exit.
        if (pattern != null) {
            PatternAnalysis.TradeBias bias = dir == Quant7GatesSimulation.Direction.LONG
                ? PatternAnalysis.TradeBias.LONG
                : PatternAnalysis.TradeBias.SHORT;
            PatternAnalysis.Action act = pattern.actionFor(bias);
            if (act == PatternAnalysis.Action.AVOID) {
                String why = "flow AVOID — " + pattern.label();
                return sim.close(livePrice, liveSource, now, why, Quant7GatesSimulationStatus.CLOSED_FLOW_AVOID);
            }
        }
        return null;
    }

    /**
     * Entry gate. Must pass:
     * <ul>
     *   <li>{@code pattern.confidence == HIGH}</li>
     *   <li>{@code pattern.reason} contains the Δ CONFIRMED tag AND the
     *       direction-aligned ABS tag.</li>
     *   <li>{@code pattern.actionFor(direction) == TRADE} (flow TRADE).</li>
     * </ul>
     */
    static boolean isEntryQualified(PatternAnalysis pattern,
                                     Quant7GatesSimulation.Direction direction) {
        if (pattern == null) return false;
        if (pattern.confidence() != PatternAnalysis.Confidence.HIGH) return false;
        String reason = pattern.reason() == null ? "" : pattern.reason();
        if (!reason.contains(TAG_DELTA)) return false;
        String absTag = direction == Quant7GatesSimulation.Direction.LONG ? TAG_ABS_BULL : TAG_ABS_BEAR;
        if (!reason.contains(absTag)) return false;
        PatternAnalysis.TradeBias bias = direction == Quant7GatesSimulation.Direction.LONG
            ? PatternAnalysis.TradeBias.LONG
            : PatternAnalysis.TradeBias.SHORT;
        return pattern.actionFor(bias) == PatternAnalysis.Action.TRADE;
    }

    private static String describeEntry(PatternAnalysis pattern,
                                         Quant7GatesSimulation.Direction direction) {
        if (pattern == null) return direction + " auto-entry";
        return direction + " · " + pattern.label() + " · HIGH conf · Δ Confirmed";
    }

    private boolean hasOpen(Instrument instrument, Quant7GatesSimulation.Direction direction) {
        for (Quant7GatesSimulation s : bucket(instrument)) {
            if (s.isOpen() && s.direction() == direction) return true;
        }
        return false;
    }

    private List<Quant7GatesSimulation> bucket(Instrument instrument) {
        return byInstrument.computeIfAbsent(instrument, k -> new ArrayList<>());
    }

    private void capClosedHistory(Instrument instrument) {
        List<Quant7GatesSimulation> rows = bucket(instrument);
        int closedCount = 0;
        for (Quant7GatesSimulation s : rows) if (!s.isOpen()) closedCount++;
        if (closedCount <= CLOSED_HISTORY_CAP) return;
        // Evict by close recency (oldest close first) rather than open time —
        // a long-running trade that just closed must outrank older quick
        // trades in the "recently closed" view. closedAt falls back to
        // openedAt for safety (closedAt is always set once status != OPEN,
        // but the fallback keeps the comparator total even on malformed rows).
        rows.sort(Comparator.comparing(
            (Quant7GatesSimulation s) -> s.closedAt() != null ? s.closedAt() : s.openedAt()));
        int toDrop = closedCount - CLOSED_HISTORY_CAP;
        for (int i = 0; i < rows.size() && toDrop > 0; ) {
            Quant7GatesSimulation s = rows.get(i);
            if (!s.isOpen()) { rows.remove(i); toDrop--; } else { i++; }
        }
    }

    private void publish(Quant7GatesSimulation sim) {
        Quant7GatesSimulationPublisher publisher = publisherProvider.getIfAvailable();
        if (publisher == null) return;
        try {
            publisher.publish(sim);
        } catch (RuntimeException e) {
            log.warn("quant-sim publish failed id={}: {}", sim.id(), e.toString());
        }
    }

    /**
     * Mirrors an OPEN (insert) or terminal CLOSE (update) into the durable
     * store when one is wired. Mark-to-market refreshes are intentionally NOT
     * persisted — they are transient live-view noise; only the entry snapshot
     * and the final resolved row matter for the historical report.
     */
    private void persist(Quant7GatesSimulation sim) {
        Quant7GatesSimulationRepositoryPort repo = repo();
        if (repo == null) return;
        try {
            repo.save(sim);
        } catch (RuntimeException e) {
            log.warn("quant-sim persist failed id={}: {}", sim.id(), e.toString());
        }
    }

    /**
     * Pure read — all simulations across instruments, newest first. When a
     * durable store is wired, the full closed history comes from the DB (not
     * the capped in-memory buckets) and is merged with the live OPEN rows
     * (which carry the latest mark-to-market price); otherwise the in-memory
     * buckets are returned as-is.
     */
    public synchronized List<Quant7GatesSimulation> listAll() {
        Quant7GatesSimulationRepositoryPort repo = repo();
        List<Quant7GatesSimulation> out = new ArrayList<>();
        if (repo == null) {
            for (List<Quant7GatesSimulation> rows : byInstrument.values()) out.addAll(rows);
        } else {
            out.addAll(repo.findAllClosed());
            for (List<Quant7GatesSimulation> rows : byInstrument.values()) {
                for (Quant7GatesSimulation s : rows) if (s.isOpen()) out.add(s);
            }
        }
        out.sort(Comparator.comparing(Quant7GatesSimulation::openedAt).reversed());
        return Collections.unmodifiableList(out);
    }

    /** Pure read — only the OPEN rows, newest first. */
    public synchronized List<Quant7GatesSimulation> listOpen() {
        List<Quant7GatesSimulation> out = new ArrayList<>();
        for (List<Quant7GatesSimulation> rows : byInstrument.values()) {
            for (Quant7GatesSimulation s : rows) if (s.isOpen()) out.add(s);
        }
        out.sort(Comparator.comparing(Quant7GatesSimulation::openedAt).reversed());
        return Collections.unmodifiableList(out);
    }

    /**
     * Pure read — aggregated win/loss stats across resolved rows. Reads the
     * full closed history from the durable store when wired, otherwise the
     * (capped) in-memory closed rows.
     */
    public synchronized Stats stats() {
        Quant7GatesSimulationRepositoryPort repo = repo();
        if (repo != null) {
            return aggregate(repo.findAllClosed());
        }
        List<Quant7GatesSimulation> closed = new ArrayList<>();
        for (List<Quant7GatesSimulation> rows : byInstrument.values()) {
            for (Quant7GatesSimulation s : rows) if (!s.isOpen()) closed.add(s);
        }
        return aggregate(closed);
    }

    /** Aggregates win/loss + net P&amp;L over a list of (already resolved) rows. */
    private static Stats aggregate(List<Quant7GatesSimulation> rows) {
        int total = 0;
        int wins = 0;
        int losses = 0;
        double netPts = 0.0;
        double netUsd = 0.0;
        for (Quant7GatesSimulation s : rows) {
            if (s.isOpen()) continue;
            total++;
            double pts = s.pnlPoints() == null ? 0.0 : s.pnlPoints();
            double usd = s.pnlUsd() == null ? 0.0 : s.pnlUsd();
            netPts += pts;
            netUsd += usd;
            if (pts > 0) wins++;
            else if (pts < 0) losses++;
        }
        return new Stats(total, wins, losses, netPts, netUsd);
    }

    /** Pure stats aggregate for the REST + WS payload. */
    public record Stats(int closedCount, int wins, int losses, double netPoints, double netUsd) {
        public Double winRatePct() {
            int decided = wins + losses;
            if (decided == 0) return null;
            return (wins * 100.0) / decided;
        }
    }

    /** Test-only hook to clear state between scenarios. */
    public synchronized void resetForTesting() {
        byInstrument.clear();
        sequence.set(1);
    }

    /** Test-only accessor exposing the per-instrument bucket snapshot. */
    synchronized Map<Instrument, List<Quant7GatesSimulation>> snapshotForTesting() {
        Map<Instrument, List<Quant7GatesSimulation>> copy = new HashMap<>();
        for (Map.Entry<Instrument, List<Quant7GatesSimulation>> e : byInstrument.entrySet()) {
            copy.put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        return copy;
    }
}
