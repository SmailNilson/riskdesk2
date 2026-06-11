package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import com.riskdesk.domain.quant.simulation.QuantSimExitPolicy;
import com.riskdesk.domain.quant.simulation.QuantSimStopMode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests of the recalibrated trade-management policy: SLTP_ONLY /
 * FLOW_AVOID_IN_PROFIT exit handling, ATR-sized offsets, the fail-closed HTF
 * filter and the ET EOD-flat / entry-blackout windows (winter, summer-DST and
 * boundary cases — per the repo's date/time test rules).
 */
class Quant7GatesSimulationServicePolicyTest {

    // ── exit policies ───────────────────────────────────────────────────────

    @Test
    void sltpOnlyIgnoresAvoidFlip() {
        Quant7GatesSimulationService service = service(propsWith(p -> {
            p.setExitPolicy(QuantSimExitPolicy.SLTP_ONLY);
            p.setStopMode(QuantSimStopMode.FIXED);
        }), null);

        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(service.listOpen()).hasSize(1);

        // Pattern flips to AVOID for LONG at a small loss — row must stay open.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29682.25), longAvoidPattern());
        assertThat(service.listOpen()).hasSize(1);
    }

    @Test
    void flowAvoidInProfitOnlyLocksGains() {
        Quant7GatesSimulationService service = service(propsWith(p -> {
            p.setExitPolicy(QuantSimExitPolicy.FLOW_AVOID_IN_PROFIT);
            p.setStopMode(QuantSimStopMode.FIXED);
        }), null);

        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(service.listOpen()).hasSize(1);

        // AVOID at a loss → ignored, the trade rides on.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29682.25), longAvoidPattern());
        assertThat(service.listOpen()).hasSize(1);

        // AVOID in profit → honoured.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29692.25), longAvoidPattern());
        assertThat(service.listOpen()).isEmpty();
        Quant7GatesSimulation closed = service.listAll().get(0);
        assertThat(closed.status()).isEqualTo(Quant7GatesSimulationStatus.CLOSED_FLOW_AVOID);
        assertThat(closed.pnlPoints()).isEqualTo(5.0);
    }

    @Test
    void legacyFlowAvoidClosesImmediately() {
        Quant7GatesSimulationService service = service(QuantSimProperties.legacyDefaults(), null);

        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        service.onSnapshot(Instrument.MNQ, snapshotAt(29682.25), longAvoidPattern());
        assertThat(service.listOpen()).isEmpty();
        assertThat(service.listAll().get(0).status())
            .isEqualTo(Quant7GatesSimulationStatus.CLOSED_FLOW_AVOID);
    }

    // ── stop sizing ─────────────────────────────────────────────────────────

    @Test
    void atrStopModeSizesOffsetsFromContext() {
        QuantSimProperties props = propsWith(p -> {
            p.setStopMode(QuantSimStopMode.ATR);
            p.setSlAtrMult(2.0);
            p.setTp1AtrMult(3.0);
            p.setTp2AtrMult(6.0);
        });
        Quant7GatesSimulationService service = service(props, stubContext(10.0, true));

        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        Quant7GatesSimulation row = service.listOpen().get(0);
        assertThat(row.stopLoss()).isEqualTo(29687.25 - 20.0);
        assertThat(row.takeProfit1()).isEqualTo(29687.25 + 30.0);
        assertThat(row.takeProfit2()).isEqualTo(29687.25 + 60.0);
    }

    @Test
    void atrUnavailableFallsBackToFixedOffsets() {
        QuantSimProperties props = propsWith(p -> p.setStopMode(QuantSimStopMode.ATR));
        Quant7GatesSimulationService service = service(props, stubContext(null, true));

        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        Quant7GatesSimulation row = service.listOpen().get(0);
        assertThat(row.stopLoss()).isEqualTo(29687.25 - QuantSnapshot.SL_OFFSET);
        assertThat(row.takeProfit1()).isEqualTo(29687.25 + QuantSnapshot.TP1_OFFSET);
    }

    // ── per-instrument overrides ────────────────────────────────────────────

    @Test
    void perInstrumentAtrMultsOverrideGlobals() {
        QuantSimProperties props = propsWith(p -> {
            p.setStopMode(QuantSimStopMode.ATR);
            p.setSlAtrMult(2.0);
            p.setTp1AtrMult(3.0);
            p.setTp2AtrMult(6.0);
            QuantSimProperties.InstrumentOverride mcl = new QuantSimProperties.InstrumentOverride();
            mcl.setSlAtrMult(1.0);
            mcl.setTp1AtrMult(2.0);
            // tp2AtrMult left unset — must inherit the global 6.0.
            p.setPerInstrument(Map.of("MCL", mcl));
        });
        Quant7GatesSimulationService service = service(props, stubContext(10.0, true));

        service.onSnapshot(Instrument.MNQ, snapshotAt(Instrument.MNQ, 29687.25), longTradePattern());
        service.onSnapshot(Instrument.MCL, snapshotAt(Instrument.MCL, 90.00), longTradePattern());

        Quant7GatesSimulation mnq = service.listOpen().stream()
            .filter(r -> r.instrument() == Instrument.MNQ).findFirst().orElseThrow();
        assertThat(mnq.stopLoss()).isEqualTo(29687.25 - 20.0);     // global 2.0 × ATR 10

        Quant7GatesSimulation mcl = service.listOpen().stream()
            .filter(r -> r.instrument() == Instrument.MCL).findFirst().orElseThrow();
        assertThat(mcl.stopLoss()).isEqualTo(90.00 - 10.0);        // override 1.0 × ATR 10
        assertThat(mcl.takeProfit1()).isEqualTo(90.00 + 20.0);     // override 2.0 × ATR 10
        assertThat(mcl.takeProfit2()).isEqualTo(90.00 + 60.0);     // unset → global 6.0
    }

    @Test
    void perInstrumentExitPolicyOverridesGlobal() {
        QuantSimProperties props = propsWith(p -> {
            p.setExitPolicy(QuantSimExitPolicy.SLTP_ONLY);
            p.setStopMode(QuantSimStopMode.FIXED);
            QuantSimProperties.InstrumentOverride mnq = new QuantSimProperties.InstrumentOverride();
            mnq.setExitPolicy(QuantSimExitPolicy.FLOW_AVOID);
            p.setPerInstrument(Map.of("MNQ", mnq));
        });
        Quant7GatesSimulationService service = service(props, null);

        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(service.listOpen()).hasSize(1);

        // Global policy would ignore the AVOID flip — the MNQ override honours it.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29682.25), longAvoidPattern());
        assertThat(service.listOpen()).isEmpty();
        assertThat(service.listAll().get(0).status())
            .isEqualTo(Quant7GatesSimulationStatus.CLOSED_FLOW_AVOID);
    }

    // ── per-instrument stats ────────────────────────────────────────────────

    @Test
    void statsByInstrumentSeparatesMarkets() {
        QuantSimProperties props = propsWith(p -> p.setStopMode(QuantSimStopMode.FIXED));
        Quant7GatesSimulationService service = service(props, null);

        // MNQ LONG rides to TP2 (+80 pts) — a win.
        service.onSnapshot(Instrument.MNQ, snapshotAt(Instrument.MNQ, 29687.25), longTradePattern());
        service.onSnapshot(Instrument.MNQ,
            snapshotAt(Instrument.MNQ, 29687.25 + QuantSnapshot.TP2_OFFSET), longTradePattern());

        // MCL LONG collapses through the SL — a loss.
        service.onSnapshot(Instrument.MCL, snapshotAt(Instrument.MCL, 90.00), longTradePattern());
        service.onSnapshot(Instrument.MCL, snapshotAt(Instrument.MCL, 60.00), longTradePattern());

        Map<String, Quant7GatesSimulationService.Stats> by = service.statsByInstrument();
        assertThat(by).containsOnlyKeys("MCL", "MNQ");
        assertThat(by.get("MNQ").wins()).isEqualTo(1);
        assertThat(by.get("MNQ").losses()).isZero();
        assertThat(by.get("MNQ").netPoints()).isEqualTo(QuantSnapshot.TP2_OFFSET);
        assertThat(by.get("MCL").wins()).isZero();
        assertThat(by.get("MCL").losses()).isEqualTo(1);
        assertThat(by.get("MCL").netPoints()).isEqualTo(-30.0);

        // The blended aggregate still matches the sum of the slices.
        Quant7GatesSimulationService.Stats total = service.stats();
        assertThat(total.closedCount())
            .isEqualTo(by.values().stream().mapToInt(Quant7GatesSimulationService.Stats::closedCount).sum());
    }

    // ── HTF filter ──────────────────────────────────────────────────────────

    @Test
    void htfFilterBlocksMisalignedEntry() {
        QuantSimProperties props = propsWith(p -> p.setHtfFilterEnabled(true));
        Quant7GatesSimulationService blocked = service(props, stubContext(10.0, false));
        blocked.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(blocked.listOpen()).isEmpty();

        Quant7GatesSimulationService allowed = service(props, stubContext(10.0, true));
        allowed.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(allowed.listOpen()).hasSize(1);
    }

    @Test
    void htfFilterFailsClosedWithoutContext() {
        QuantSimProperties props = propsWith(p -> p.setHtfFilterEnabled(true));
        Quant7GatesSimulationService service = service(props, null); // no context bean
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(service.listOpen()).isEmpty();
    }

    // ── EOD flat / entry blackout (ET, DST-aware) ───────────────────────────

    @Test
    void eodFlatClosesOpenRowsInWinterWindow() {
        // Winter (EST, UTC-5): 16:56 ET == 21:56 UTC.
        QuantSimProperties props = propsWith(p -> p.setEodFlatEnabled(true));
        Quant7GatesSimulationService service = service(props, null);
        service.setClockForTesting(Clock.fixed(Instant.parse("2026-01-05T20:00:00Z"), ZoneOffset.UTC));

        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(service.listOpen()).hasSize(1);

        service.setClockForTesting(Clock.fixed(Instant.parse("2026-01-05T21:56:00Z"), ZoneOffset.UTC));
        service.onSnapshot(Instrument.MNQ, snapshotAt(29690.00), longTradePattern());
        assertThat(service.listOpen()).isEmpty();
        Quant7GatesSimulation closed = service.listAll().get(0);
        assertThat(closed.status()).isEqualTo(Quant7GatesSimulationStatus.CLOSED_EOD);
        assertThat(closed.exitReason()).contains("EOD flat");
    }

    @Test
    void eodFlatClosesOpenRowsInSummerDstWindow() {
        // Summer (EDT, UTC-4): 16:56 ET == 20:56 UTC — one hour earlier in UTC.
        QuantSimProperties props = propsWith(p -> p.setEodFlatEnabled(true));
        Quant7GatesSimulationService service = service(props, null);
        service.setClockForTesting(Clock.fixed(Instant.parse("2026-06-10T18:00:00Z"), ZoneOffset.UTC));

        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(service.listOpen()).hasSize(1);

        service.setClockForTesting(Clock.fixed(Instant.parse("2026-06-10T20:56:00Z"), ZoneOffset.UTC));
        service.onSnapshot(Instrument.MNQ, snapshotAt(29690.00), longTradePattern());
        assertThat(service.listOpen()).isEmpty();
        assertThat(service.listAll().get(0).status()).isEqualTo(Quant7GatesSimulationStatus.CLOSED_EOD);
    }

    @Test
    void noEodFlatBeforeWindowOrAfterReopen() {
        QuantSimProperties props = propsWith(p -> p.setEodFlatEnabled(true));
        Quant7GatesSimulationService service = service(props, null);

        // 16:54 ET (21:54 UTC winter) — one minute before the window: stays open.
        service.setClockForTesting(Clock.fixed(Instant.parse("2026-01-05T20:00:00Z"), ZoneOffset.UTC));
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        service.setClockForTesting(Clock.fixed(Instant.parse("2026-01-05T21:54:00Z"), ZoneOffset.UTC));
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(service.listOpen()).hasSize(1);

        // 18:01 ET (23:01 UTC winter) — after the reopen: entries allowed again.
        Quant7GatesSimulationService evening = service(props, null);
        evening.setClockForTesting(Clock.fixed(Instant.parse("2026-01-05T23:01:00Z"), ZoneOffset.UTC));
        evening.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(evening.listOpen()).hasSize(1);
    }

    @Test
    void entryBlackoutBlocksNewEntriesBeforeBreak() {
        // 16:52 ET (21:52 UTC winter) — inside [16:50, 18:00): no fresh entry,
        // but it is before 16:55 so an existing row is NOT yet flattened.
        QuantSimProperties props = propsWith(p -> p.setEodFlatEnabled(true));
        Quant7GatesSimulationService service = service(props, null);
        service.setClockForTesting(Clock.fixed(Instant.parse("2026-01-05T21:52:00Z"), ZoneOffset.UTC));
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), longTradePattern());
        assertThat(service.listOpen()).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    /** New-policy props (calibrated defaults) tweaked per test, HTF/EOD off unless enabled. */
    private static QuantSimProperties propsWith(java.util.function.Consumer<QuantSimProperties> tweak) {
        QuantSimProperties p = new QuantSimProperties();
        p.setHtfFilterEnabled(false);
        p.setEodFlatEnabled(false);
        p.setStopMode(QuantSimStopMode.FIXED);
        p.setExitPolicy(QuantSimExitPolicy.SLTP_ONLY);
        tweak.accept(p);
        return p;
    }

    private static Quant7GatesSimulationService service(QuantSimProperties props,
                                                        QuantSimMarketContext context) {
        Quant7GatesSimulationService s = new Quant7GatesSimulationService(
            provider(null), provider(null), provider(null), props, provider(context));
        s.resetForTesting();
        return s;
    }

    private static QuantSimMarketContext stubContext(Double atr, boolean aligned) {
        return new QuantSimMarketContext() {
            @Override public Double atr(Instrument instrument) { return atr; }
            @Override public Boolean htfAligned(Instrument instrument,
                                                Quant7GatesSimulation.Direction direction) {
                return aligned;
            }
        };
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

    /** Pattern whose LONG view is AVOID (SHORT view TRADE). */
    private static PatternAnalysis longAvoidPattern() {
        return new PatternAnalysis(
            OrderFlowPattern.DISTRIBUTION_SILENCIEUSE,
            "Distribution silencieuse",
            "Δ=-400 | Confirmations: [Δ CONFIRMED][ABS BEAR ACTIVE]",
            PatternAnalysis.Confidence.LOW, // LOW conf → never opens a SHORT here
            PatternAnalysis.Action.TRADE);  // SHORT view TRADE → LONG mirror AVOID
    }

    private static QuantSnapshot snapshotAt(double price) {
        return snapshotAt(Instrument.MNQ, price);
    }

    private static QuantSnapshot snapshotAt(Instrument instrument, double price) {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        return new QuantSnapshot(
            instrument, gates, 4, 4,
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
