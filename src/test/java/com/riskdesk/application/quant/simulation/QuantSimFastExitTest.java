package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.marketdata.event.MarketPriceUpdated;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import com.riskdesk.domain.quant.port.LivePricePort;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import com.riskdesk.domain.quant.simulation.QuantSimExitPolicy;
import com.riskdesk.domain.quant.simulation.QuantSimStopMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the ~3 s fast exit path: {@code onPriceTick} (SL/TP-only, no
 * entries, no AVOID) and {@link QuantSimFastExitListener} (live-source
 * gating, master flag, non-simulated feeds).
 */
class QuantSimFastExitTest {

    private static final Instant TICK_AT = Instant.parse("2026-06-11T17:30:30Z");

    // ── service fast path ───────────────────────────────────────────────────

    @Test
    void tickClosesLongOnStopLossAtTickPrice() {
        Quant7GatesSimulationService service = service(fixedProps());
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(service.listOpen()).hasSize(1);

        // Price blows through the SL (29662.25) — the close is recorded at the
        // tick price, honestly carrying whatever overshoot the tick saw.
        service.onPriceTick(Instrument.MNQ, 29650.00, "LIVE_PUSH", TICK_AT);

        assertThat(service.listOpen()).isEmpty();
        Quant7GatesSimulation closed = service.listAll().get(0);
        assertThat(closed.status()).isEqualTo(Quant7GatesSimulationStatus.CLOSED_SL);
        assertThat(closed.exitPrice()).isEqualTo(29650.00);
        assertThat(closed.exitPriceSource()).isEqualTo("LIVE_PUSH");
        assertThat(closed.closedAt()).isEqualTo(TICK_AT);
    }

    @Test
    void tickClosesShortOnTakeProfit() {
        Quant7GatesSimulationService service = service(fixedProps());
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), shortTradePattern());
        assertThat(service.listOpen()).hasSize(1);

        // SHORT TP1 = entry - 40.
        service.onPriceTick(Instrument.MNQ, 29687.25 - QuantSnapshot.TP1_OFFSET, "LIVE_PUSH", TICK_AT);

        assertThat(service.listOpen()).isEmpty();
        assertThat(service.listAll().get(0).status()).isEqualTo(Quant7GatesSimulationStatus.CLOSED_TP1);
    }

    @Test
    void tickMarksToMarketWithoutClosing() {
        Quant7GatesSimulationService service = service(fixedProps());
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());

        service.onPriceTick(Instrument.MNQ, 29690.00, "LIVE_PUSH", TICK_AT);

        Quant7GatesSimulation open = service.listOpen().get(0);
        assertThat(open.isOpen()).isTrue();
        assertThat(open.exitPrice()).isEqualTo(29690.00); // live mark for the panel P&L
    }

    @Test
    void tickNeverOpensAndIgnoresUnknownInstrument() {
        Quant7GatesSimulationService service = service(fixedProps());

        // No open row → strict no-op, even on an extreme price.
        service.onPriceTick(Instrument.MNQ, 29000.00, "LIVE_PUSH", TICK_AT);
        assertThat(service.listAll()).isEmpty();

        // Open a LONG, tick the OTHER instrument through its would-be levels —
        // the MNQ row must be untouched.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        service.onPriceTick(Instrument.MCL, 1.00, "LIVE_PUSH", TICK_AT);
        assertThat(service.listOpen()).hasSize(1);
    }

    // ── listener gating ─────────────────────────────────────────────────────

    @Test
    void listenerClosesRowOnLiveSourceTick() {
        Quant7GatesSimulationService service = service(fixedProps());
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        QuantSimFastExitListener listener = new QuantSimFastExitListener(
            service, port(new LivePriceSnapshot(29650.00, TICK_AT, "LIVE_PUSH")), fixedProps());

        listener.onPriceUpdate(event("MNQ", 29650.00));

        assertThat(service.listOpen()).isEmpty();
        assertThat(service.listAll().get(0).status()).isEqualTo(Quant7GatesSimulationStatus.CLOSED_SL);
    }

    @Test
    void listenerIgnoresFallbackAndStaleSources() {
        Quant7GatesSimulationService service = service(fixedProps());
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());

        new QuantSimFastExitListener(
            service, port(new LivePriceSnapshot(29650.00, TICK_AT, "FALLBACK_DB")), fixedProps())
            .onPriceUpdate(event("MNQ", 29650.00));
        new QuantSimFastExitListener(
            service, port(new LivePriceSnapshot(29650.00, TICK_AT, "STALE")), fixedProps())
            .onPriceUpdate(event("MNQ", 29650.00));

        // Fallback/stale prices never drive a fast exit — the 60 s scan owns those.
        assertThat(service.listOpen()).hasSize(1);
    }

    @Test
    void listenerHonoursDisableFlagAndUnknownFeeds() {
        Quant7GatesSimulationService service = service(fixedProps());
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());

        QuantSimProperties off = fixedProps();
        off.setFastExitEnabled(false);
        new QuantSimFastExitListener(
            service, port(new LivePriceSnapshot(29650.00, TICK_AT, "LIVE_PUSH")), off)
            .onPriceUpdate(event("MNQ", 29650.00));
        assertThat(service.listOpen()).hasSize(1);

        // VIX rides the same event stream but is not a simulated instrument.
        QuantSimFastExitListener listener = new QuantSimFastExitListener(
            service, port(new LivePriceSnapshot(29650.00, TICK_AT, "LIVE_PUSH")), fixedProps());
        listener.onPriceUpdate(event("VIX", 18.50));
        listener.onPriceUpdate(null);
        assertThat(service.listOpen()).hasSize(1);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static QuantSimProperties fixedProps() {
        QuantSimProperties p = new QuantSimProperties();
        p.setHtfFilterEnabled(false);
        p.setEodFlatEnabled(false);
        p.setStopMode(QuantSimStopMode.FIXED);
        p.setExitPolicy(QuantSimExitPolicy.SLTP_ONLY);
        return p;
    }

    private static Quant7GatesSimulationService service(QuantSimProperties props) {
        Quant7GatesSimulationService s = new Quant7GatesSimulationService(
            provider(null), provider(null), provider(null), props, provider(null));
        s.resetForTesting();
        return s;
    }

    private static LivePricePort port(LivePriceSnapshot snapshot) {
        return instrument -> Optional.ofNullable(snapshot);
    }

    private static MarketPriceUpdated event(String instrument, double price) {
        return new MarketPriceUpdated(instrument, BigDecimal.valueOf(price), TICK_AT);
    }

    /** HIGH-conf bullish absorption — LONG view = TRADE (entry qualifies). */
    private static PatternAnalysis longTradePattern() {
        return new PatternAnalysis(
            OrderFlowPattern.ABSORPTION_HAUSSIERE,
            "Absorption haussière",
            "Δ=+864 | Confirmations: [Δ CONFIRMED][ABS BULL ACTIVE]",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.AVOID); // SHORT view AVOID → LONG mirror TRADE
    }

    /** HIGH-conf silent distribution — SHORT view = TRADE (entry qualifies). */
    private static PatternAnalysis shortTradePattern() {
        return new PatternAnalysis(
            OrderFlowPattern.DISTRIBUTION_SILENCIEUSE,
            "Distribution silencieuse",
            "Δ=-864 | Confirmations: [Δ CONFIRMED][ABS BEAR ACTIVE]",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.TRADE);
    }

    private static QuantSnapshot snapshotAt(double price) {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        return new QuantSnapshot(
            Instrument.MNQ, gates, 4, 4,
            price, "LIVE_PUSH", 0.0,
            ZonedDateTime.now(ZoneId.of("America/New_York")));
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override public T getObject() { return value; }
            @Override public T getObject(Object... args) { return value; }
            @Override public T getIfAvailable() { return value; }
            @Override public T getIfUnique() { return value; }
        };
    }
}
