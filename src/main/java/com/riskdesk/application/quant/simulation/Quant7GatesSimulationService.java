package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationPublisher;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import com.riskdesk.domain.quant.simulation.QuantSimExitPolicy;
import com.riskdesk.domain.quant.simulation.QuantSimStopMode;
import com.riskdesk.domain.quant.simulation.port.Quant7GatesSimulationRepositoryPort;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
 *   <li>Live price crosses the configured SL / TP1 / TP2 offset →
 *       {@link Quant7GatesSimulationStatus#CLOSED_SL} /
 *       {@link Quant7GatesSimulationStatus#CLOSED_TP1} /
 *       {@link Quant7GatesSimulationStatus#CLOSED_TP2}.</li>
 *   <li>Pattern action flips to {@code AVOID} for the trade direction —
 *       handled per the configured {@link QuantSimExitPolicy} (ignored under
 *       {@code SLTP_ONLY}, profit-gated under {@code FLOW_AVOID_IN_PROFIT},
 *       immediate under legacy {@code FLOW_AVOID}).</li>
 *   <li>EOD flat — open rows are flattened at the live price ahead of the
 *       17:00 ET CME break ({@link Quant7GatesSimulationStatus#CLOSED_EOD})
 *       and fresh entries are blocked in the run-up window.</li>
 * </ul>
 *
 * <p>Entry additionally requires HTF trend alignment (1h EMA20/50 by default)
 * when {@code riskdesk.quant.sim.htf-filter-enabled} is on, and SL/TP offsets
 * are ATR-sized under {@link QuantSimStopMode#ATR}. The policy bundle was
 * calibrated on the first 863 recorded trades — see
 * {@code docs/AI_HANDOFF.md} ("Quant 7-Gates exit recalibration").
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

    /** ET zone for the EOD-flat / entry-blackout windows (DST-aware, never hardcoded UTC hours). */
    private static final ZoneId ET = ZoneId.of("America/New_York");

    /** End of the EOD windows — the CME break ends and a fresh session opens at 18:00 ET. */
    private static final LocalTime SESSION_REOPEN_ET = LocalTime.of(18, 0);

    private final ObjectProvider<Quant7GatesSimulationPublisher> publisherProvider;
    private final ObjectProvider<Quant7GatesSimulationRepositoryPort> repositoryProvider;
    private final ObjectProvider<Quant7GatesExecutionBridge> bridgeProvider;
    private final QuantSimProperties props;
    private final ObjectProvider<QuantSimMarketContext> marketContextProvider;
    private final AtomicLong sequence = new AtomicLong(1);

    /** Injectable clock so the ET-window logic is testable (incl. DST cases). */
    private Clock clock = Clock.systemUTC();

    /**
     * Open + closed simulations bucketed by instrument. Closed entries are
     * capped at {@link #CLOSED_HISTORY_CAP} per instrument so memory stays
     * bounded. Read access happens from the controller thread, write access
     * from the scheduler thread — synchronised on {@code this}.
     */
    private final Map<Instrument, List<Quant7GatesSimulation>> byInstrument = new EnumMap<>(Instrument.class);

    /**
     * Backward-compatible constructor (pre Auto-IBKR). Wires the execution bridge
     * to a no-op provider so unit tests that omit it stay paper-only. Runs the
     * LEGACY policy bundle (immediate flow-AVOID, fixed offsets, no HTF/EOD).
     */
    public Quant7GatesSimulationService(
            ObjectProvider<Quant7GatesSimulationPublisher> publisherProvider,
            ObjectProvider<Quant7GatesSimulationRepositoryPort> repositoryProvider) {
        this(publisherProvider, repositoryProvider, new EmptyObjectProvider<>());
    }

    /**
     * Backward-compatible constructor (pre exit-policy recalibration). Runs the
     * LEGACY policy bundle so pre-existing tests keep their semantics.
     */
    public Quant7GatesSimulationService(
            ObjectProvider<Quant7GatesSimulationPublisher> publisherProvider,
            ObjectProvider<Quant7GatesSimulationRepositoryPort> repositoryProvider,
            ObjectProvider<Quant7GatesExecutionBridge> bridgeProvider) {
        this(publisherProvider, repositoryProvider, bridgeProvider,
            QuantSimProperties.legacyDefaults(), new EmptyObjectProvider<>());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public Quant7GatesSimulationService(
            ObjectProvider<Quant7GatesSimulationPublisher> publisherProvider,
            ObjectProvider<Quant7GatesSimulationRepositoryPort> repositoryProvider,
            ObjectProvider<Quant7GatesExecutionBridge> bridgeProvider,
            QuantSimProperties props,
            ObjectProvider<QuantSimMarketContext> marketContextProvider) {
        this.publisherProvider = publisherProvider;
        this.repositoryProvider = repositoryProvider;
        this.bridgeProvider = bridgeProvider;
        this.props = props;
        this.marketContextProvider = marketContextProvider;
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
        Instant now = Instant.now(clock);

        List<Quant7GatesSimulation> rows = bucket(instrument);

        // 0) EOD flat — ahead of the 17:00 ET CME break every open row is
        //    flattened at the live price (mirrors the Auto-IBKR force-close so
        //    the paper row and its live mirror resolve together) and no fresh
        //    entry is considered until the 18:00 ET reopen.
        if (props.isEodFlatEnabled() && inEtWindow(now, props.getEodFlatFrom())) {
            for (int i = 0; i < rows.size(); i++) {
                Quant7GatesSimulation sim = rows.get(i);
                if (!sim.isOpen()) continue;
                Quant7GatesSimulation closed = sim.close(livePrice, liveSource, now,
                    "EOD flat — pre-break 17:00 ET", Quant7GatesSimulationStatus.CLOSED_EOD);
                rows.set(i, closed);
                publish(closed);
                persist(closed);
                routeClose(closed);
            }
            capClosedHistory(instrument);
            return;
        }

        // 1) Process exits on currently-open simulations.
        for (int i = 0; i < rows.size(); i++) {
            Quant7GatesSimulation sim = rows.get(i);
            if (!sim.isOpen()) continue;
            Quant7GatesSimulation closed = maybeClose(sim, livePrice, liveSource, pattern, now);
            if (closed != null) {
                rows.set(i, closed);
                publish(closed);
                persist(closed);
                routeClose(closed);
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

        // 2) Consider opening a fresh simulation on either side — unless we are
        //    inside the pre-break entry blackout.
        boolean entryBlocked = props.isEodFlatEnabled() && inEtWindow(now, props.getEntryBlackoutFrom());
        if (!entryBlocked) {
            tryOpen(instrument, snapshot, pattern, Quant7GatesSimulation.Direction.LONG, livePrice, now);
            tryOpen(instrument, snapshot, pattern, Quant7GatesSimulation.Direction.SHORT, livePrice, now);
        }

        capClosedHistory(instrument);
    }

    /**
     * True when {@code now}, projected to ET wall-clock, falls in
     * {@code [from, 18:00)} — the run-up to the CME break plus the break
     * itself. DST is handled by the zone projection; weekends naturally fall
     * outside because no scan ticks arrive without market data.
     */
    private static boolean inEtWindow(Instant now, LocalTime from) {
        LocalTime et = now.atZone(ET).toLocalTime();
        return !et.isBefore(from) && et.isBefore(SESSION_REOPEN_ET);
    }

    /**
     * Fast exit path — SL/TP-only evaluation on a live price push (~3 s
     * market-data poll), between the 60 s gate scans. Caps the exit overshoot
     * to one poll interval: sim #903 closed 93 pts past its SL because the
     * price crossed the level and kept running inside a single 60 s window
     * (2026-06-11 squeeze).
     *
     * <p>Deliberately NOT here: entries and the flow-AVOID policy (both need
     * a fresh pattern, which only the scan computes) and the EOD flat (a
     * session-window concern the scan already resolves within a minute).
     * Open rows that don't close are marked-to-market so the panel P&amp;L
     * is live at the poll cadence. No-op when no row is open for the
     * instrument — the common case, and the reason this path stays cheap.
     */
    public synchronized void onPriceTick(Instrument instrument, double livePrice, String liveSource, Instant now) {
        if (instrument == null || !Double.isFinite(livePrice) || livePrice <= 0.0) return;
        List<Quant7GatesSimulation> rows = byInstrument.get(instrument);
        if (rows == null || rows.isEmpty()) return;
        Instant ts = now != null ? now : Instant.now(clock);
        String source = liveSource == null ? "" : liveSource;
        boolean closedAny = false;
        for (int i = 0; i < rows.size(); i++) {
            Quant7GatesSimulation sim = rows.get(i);
            if (!sim.isOpen()) continue;
            Quant7GatesSimulation closed = checkSlTp(sim, livePrice, source, ts);
            if (closed != null) {
                rows.set(i, closed);
                publish(closed);
                persist(closed);
                routeClose(closed);
                closedAny = true;
            } else {
                Quant7GatesSimulation mtm = sim.markToMarket(livePrice, source);
                rows.set(i, mtm);
                publish(mtm);
            }
        }
        if (closedAny) capClosedHistory(instrument);
    }

    private void tryOpen(Instrument instrument,
                          QuantSnapshot snapshot,
                          PatternAnalysis pattern,
                          Quant7GatesSimulation.Direction direction,
                          double livePrice,
                          Instant now) {
        if (!isEntryQualified(pattern, direction)) return;
        if (hasOpen(instrument, direction)) return;

        QuantSimMarketContext ctx = marketContextProvider == null
            ? null : marketContextProvider.getIfAvailable();

        // HTF trend filter — fail-closed: a counter-trend (or unverifiable)
        // entry is skipped. Calibration showed counter-trend entries ran
        // -0.29R/trade while HTF-aligned ones ran +0.32R/trade.
        if (props.htfFilterEnabled(instrument.name())) {
            Boolean aligned = ctx == null ? null : ctx.htfAligned(instrument, direction);
            if (aligned == null || !aligned) {
                log.debug("quant-sim entry skipped instr={} dir={} — HTF filter (aligned={})",
                    instrument, direction, aligned);
                return;
            }
        }

        double slOffset = QuantSnapshot.SL_OFFSET;
        double tp1Offset = QuantSnapshot.TP1_OFFSET;
        double tp2Offset = QuantSnapshot.TP2_OFFSET;
        if (props.stopMode(instrument.name()) == QuantSimStopMode.ATR && ctx != null) {
            Double atr = ctx.atr(instrument);
            if (atr != null && atr > 0) {
                slOffset = props.slAtrMult(instrument.name()) * atr;
                tp1Offset = props.tp1AtrMult(instrument.name()) * atr;
                tp2Offset = props.tp2AtrMult(instrument.name()) * atr;
            } else {
                // Fixed fallback keeps the harness alive on a cold candle store,
                // but it is MNQ-scaled — log so the operator sees the degradation.
                log.warn("quant-sim ATR unavailable instr={} — falling back to fixed offsets", instrument);
            }
        }
        double sign = direction == Quant7GatesSimulation.Direction.LONG ? 1.0 : -1.0;
        double sl = livePrice - sign * slOffset;
        double tp1 = livePrice + sign * tp1Offset;
        double tp2 = livePrice + sign * tp2Offset;

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
        routeOpen(opened);
    }

    private Quant7GatesSimulation maybeClose(Quant7GatesSimulation sim,
                                              double livePrice,
                                              String liveSource,
                                              PatternAnalysis pattern,
                                              Instant now) {
        Quant7GatesSimulation closedOnLevel = checkSlTp(sim, livePrice, liveSource, now);
        if (closedOnLevel != null) return closedOnLevel;
        Quant7GatesSimulation.Direction dir = sim.direction();

        // Flow flipped to AVOID for the active side → harness exit, governed by
        // the configured policy. SLTP_ONLY ignores the flip (calibration showed
        // the immediate exit closed 79% of trades in a ~2-minute churn);
        // FLOW_AVOID_IN_PROFIT only locks in gains, never realises a loss early.
        QuantSimExitPolicy exitPolicy = props.exitPolicy(sim.instrument().name());
        if (exitPolicy != QuantSimExitPolicy.SLTP_ONLY && pattern != null) {
            PatternAnalysis.TradeBias bias = dir == Quant7GatesSimulation.Direction.LONG
                ? PatternAnalysis.TradeBias.LONG
                : PatternAnalysis.TradeBias.SHORT;
            PatternAnalysis.Action act = pattern.actionFor(bias);
            if (act == PatternAnalysis.Action.AVOID) {
                double signedPts = dir == Quant7GatesSimulation.Direction.LONG
                    ? livePrice - sim.entryPrice()
                    : sim.entryPrice() - livePrice;
                boolean honour = exitPolicy == QuantSimExitPolicy.FLOW_AVOID
                    || (exitPolicy == QuantSimExitPolicy.FLOW_AVOID_IN_PROFIT && signedPts > 0);
                if (honour) {
                    String why = "flow AVOID — " + pattern.label();
                    return sim.close(livePrice, liveSource, now, why, Quant7GatesSimulationStatus.CLOSED_FLOW_AVOID);
                }
            }
        }
        return null;
    }

    /**
     * Pure SL/TP-level check — they reflect the trade-plan exits the dashboard
     * shows. The exit-price-source on close mirrors the CURRENT feed so a
     * fallback-driven close on a live-entry row is still labelled correctly
     * for the operator. Shared by the 60 s scan path ({@link #maybeClose})
     * and the ~3 s fast exit path ({@link #onPriceTick}).
     */
    private static Quant7GatesSimulation checkSlTp(Quant7GatesSimulation sim,
                                                   double livePrice,
                                                   String liveSource,
                                                   Instant now) {
        if (sim.direction() == Quant7GatesSimulation.Direction.LONG) {
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
        return null;
    }

    /** Test-only hook — pins the clock so the ET EOD/blackout windows are reproducible. */
    synchronized void setClockForTesting(Clock clock) {
        this.clock = clock;
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

    /**
     * True when an OPEN paper simulation exists for {@code (instrument, direction)}.
     * Used by {@code QuantSimFlattenReconciler} to tell a still-legitimate mirrored
     * position (paper sim still open) from an orphan whose paper sim has closed.
     */
    public synchronized boolean hasOpenSimulation(Instrument instrument, Quant7GatesSimulation.Direction direction) {
        return hasOpen(instrument, direction);
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
     * Mirrors a freshly-opened paper trade to a live IBKR entry order when the
     * Auto-IBKR bridge is wired (master flag on). Best-effort: the paper row is
     * never altered by the routing result — the simulation stays the source of
     * truth for stats. No-op when the bridge bean is absent (paper-only mode).
     */
    private void routeOpen(Quant7GatesSimulation opened) {
        Quant7GatesExecutionBridge bridge = bridge();
        if (bridge == null) return;
        try {
            RoutingResult r = bridge.submitOpen(opened);
            if (r.outcome().isFailure()) {
                log.warn("quant-sim OPEN route failed id={} instr={}: {} {}",
                    opened.id(), opened.instrument(), r.outcome(), r.message());
            } else {
                log.debug("quant-sim OPEN route id={} instr={}: {}", opened.id(), opened.instrument(), r.outcome());
            }
        } catch (RuntimeException e) {
            log.warn("quant-sim OPEN route threw id={} instr={}: {}", opened.id(), opened.instrument(), e.toString());
        }
    }

    /**
     * Flattens the mirrored IBKR position when a paper trade resolves (SL / TP /
     * flow-AVOID). Best-effort and a no-op in paper-only mode; the bridge itself
     * no-ops when no mirrored row exists for this instrument.
     */
    private void routeClose(Quant7GatesSimulation closed) {
        Quant7GatesExecutionBridge bridge = bridge();
        if (bridge == null) return;
        try {
            RoutingResult r = bridge.submitClose(closed);
            if (r.outcome().isFailure()) {
                log.warn("quant-sim CLOSE route failed id={} instr={}: {} {}",
                    closed.id(), closed.instrument(), r.outcome(), r.message());
            } else {
                log.debug("quant-sim CLOSE route id={} instr={}: {}", closed.id(), closed.instrument(), r.outcome());
            }
        } catch (RuntimeException e) {
            log.warn("quant-sim CLOSE route threw id={} instr={}: {}", closed.id(), closed.instrument(), e.toString());
        }
    }

    private Quant7GatesExecutionBridge bridge() {
        return bridgeProvider == null ? null : bridgeProvider.getIfAvailable();
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
            return aggregate(filterSinceBaseline(repo.findAllClosed()));
        }
        List<Quant7GatesSimulation> closed = new ArrayList<>();
        for (List<Quant7GatesSimulation> rows : byInstrument.values()) {
            for (Quant7GatesSimulation s : rows) if (!s.isOpen()) closed.add(s);
        }
        return aggregate(filterSinceBaseline(closed));
    }

    /**
     * Pure read — per-instrument win/loss stats over resolved rows, keyed by
     * instrument name (sorted). Each market is judged on its own P&amp;L: an
     * MNQ edge must not be masked (or faked) by MCL rows in a blended number.
     */
    public synchronized Map<String, Stats> statsByInstrument() {
        List<Quant7GatesSimulation> closed = new ArrayList<>();
        Quant7GatesSimulationRepositoryPort repo = repo();
        if (repo != null) {
            closed.addAll(repo.findAllClosed());
        } else {
            for (List<Quant7GatesSimulation> rows : byInstrument.values()) {
                for (Quant7GatesSimulation s : rows) if (!s.isOpen()) closed.add(s);
            }
        }
        Map<String, List<Quant7GatesSimulation>> grouped = new TreeMap<>();
        for (Quant7GatesSimulation s : filterSinceBaseline(closed)) {
            if (s.isOpen()) continue;
            grouped.computeIfAbsent(s.instrument().name(), k -> new ArrayList<>()).add(s);
        }
        Map<String, Stats> out = new LinkedHashMap<>();
        grouped.forEach((name, rows) -> out.put(name, aggregate(rows)));
        return out;
    }

    /**
     * Drops rows OPENED before the stats baseline
     * ({@code riskdesk.quant.sim.stats-since}). Eras whose entry data is
     * known-bad (e.g. the broken-delta rows before the 2026-06-11 order-flow
     * fix deploy) stay visible in the history but must not pollute the
     * reported win-rate / P&amp;L. Filtering keys on {@code openedAt}: it is
     * the ENTRY decision that was taken on bad data, so a legacy row closed
     * after the fix is still excluded.
     */
    private List<Quant7GatesSimulation> filterSinceBaseline(List<Quant7GatesSimulation> rows) {
        Instant since = props.getStatsSince();
        if (since == null) return rows;
        List<Quant7GatesSimulation> out = new ArrayList<>(rows.size());
        for (Quant7GatesSimulation s : rows) {
            if (s.openedAt() != null && !s.openedAt().isBefore(since)) out.add(s);
        }
        return out;
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

    /**
     * Minimal {@link ObjectProvider} reporting "no bean available". Lets the
     * backward-compatible 2-arg constructor (and tests) run paper-only without
     * wiring an execution bridge.
     */
    private static final class EmptyObjectProvider<T> implements ObjectProvider<T> {
        @Override public T getObject() { throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("none"); }
        @Override public T getObject(Object... args) { throw new org.springframework.beans.factory.NoSuchBeanDefinitionException("none"); }
        @Override public T getIfAvailable() { return null; }
        @Override public T getIfUnique() { return null; }
    }
}
