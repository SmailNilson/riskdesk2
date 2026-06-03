package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.RoutingResult;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.Gate;
import com.riskdesk.domain.quant.model.GateResult;
import com.riskdesk.domain.quant.model.QuantSnapshot;
import com.riskdesk.domain.quant.pattern.OrderFlowPattern;
import com.riskdesk.domain.quant.pattern.PatternAnalysis;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationPublisher;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests covering the entry rule, the flow-AVOID exit, and the SL/TP exit
 * paths. The harness sits behind {@link Quant7GatesSimulationService#onSnapshot}
 * — drive it with crafted pattern + snapshot pairs and assert the resulting
 * simulation rows.
 */
class Quant7GatesSimulationServiceTest {

    private Quant7GatesSimulationService service;

    @BeforeEach
    void setUp() {
        service = new Quant7GatesSimulationService(emptyProvider(), emptyRepoProvider());
        service.resetForTesting();
    }

    @Test
    void opensLongWhenAllConditionsMet() {
        PatternAnalysis pattern = absorptionHaussiereHighConf();
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), pattern);

        List<Quant7GatesSimulation> open = service.listOpen();
        assertThat(open).hasSize(1);
        Quant7GatesSimulation row = open.get(0);
        assertThat(row.direction()).isEqualTo(Quant7GatesSimulation.Direction.LONG);
        assertThat(row.status()).isEqualTo(Quant7GatesSimulationStatus.OPEN);
        assertThat(row.entryPrice()).isEqualTo(29687.25);
        // LONG plan: SL = entry - 25, TP1 = entry + 40
        assertThat(row.stopLoss()).isEqualTo(29687.25 - 25.0);
        assertThat(row.takeProfit1()).isEqualTo(29687.25 + 40.0);
        // Provenance carries through from the snapshot — important for the
        // panel to distinguish live ticks from DB-fallback during outages.
        assertThat(row.priceSource()).isEqualTo("LIVE_PUSH");
    }

    @Test
    void doesNotOpenWhenConfidenceIsMedium() {
        PatternAnalysis pattern = new PatternAnalysis(
            OrderFlowPattern.ABSORPTION_HAUSSIERE,
            "Absorption haussière",
            "Δ=-864 mais prix +12.3pts | Confirmations: [Δ CONFIRMED][ABS BULL ACTIVE]",
            PatternAnalysis.Confidence.MEDIUM,
            PatternAnalysis.Action.AVOID // SHORT view; LONG view = TRADE
        );
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), pattern);
        assertThat(service.listOpen()).isEmpty();
    }

    @Test
    void doesNotOpenWhenDeltaTagMissing() {
        PatternAnalysis pattern = new PatternAnalysis(
            OrderFlowPattern.ABSORPTION_HAUSSIERE,
            "Absorption haussière",
            "Δ=-100 mais prix +12.3pts | Confirmations: [ABS BULL ACTIVE]",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.AVOID
        );
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), pattern);
        assertThat(service.listOpen()).isEmpty();
    }

    @Test
    void closesOnFlowAvoidForLong() {
        // Open LONG.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), absorptionHaussiereHighConf());
        assertThat(service.listOpen()).hasSize(1);

        // Flow flips: real-buy pattern → LONG view = AVOID
        PatternAnalysis vraiAchat = new PatternAnalysis(
            OrderFlowPattern.VRAI_ACHAT,
            "Vrai achat",
            "Δ=+500 et prix +20.0pts | Confirmations: [Δ CONFIRMED]",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.AVOID // VRAI_ACHAT is AVOID for SHORT; LONG mirror = TRADE … but the spec used here is the test case wanting AVOID for LONG. To flip LONG to AVOID we need pattern.action=TRADE (so mirror = AVOID for LONG).
        );
        // Build a pattern whose LONG mirror is AVOID — SHORT side = TRADE, so DISTRIBUTION_SILENCIEUSE / VRAIE_VENTE.
        PatternAnalysis vraieVente = new PatternAnalysis(
            OrderFlowPattern.VRAIE_VENTE,
            "Vraie vente",
            "Δ=-500 et prix -20.0pts | Confirmations: [Δ CONFIRMED][ABS BEAR ACTIVE]",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.TRADE
        );
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.50), vraieVente);

        // The original LONG row is now closed on flow AVOID. A new SHORT row
        // legitimately opens because the same pattern qualifies SHORT entry —
        // that's the harness's intent (it must catch every qualified signal,
        // not gatekeep based on the previous side).
        List<Quant7GatesSimulation> all = service.listAll();
        assertThat(all).anyMatch(r ->
            r.status() == Quant7GatesSimulationStatus.CLOSED_FLOW_AVOID
                && r.direction() == Quant7GatesSimulation.Direction.LONG
                && r.exitReason() != null && r.exitReason().startsWith("flow AVOID"));
        // Only the freshly-opened SHORT remains in the open bucket.
        assertThat(service.listOpen())
            .singleElement()
            .satisfies(r -> assertThat(r.direction()).isEqualTo(Quant7GatesSimulation.Direction.SHORT));
        // Reference vraiAchat so the unused-var lint doesn't fire.
        assertThat(vraiAchat.type()).isEqualTo(OrderFlowPattern.VRAI_ACHAT);
    }

    @Test
    void closesOnTp1Hit() {
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), absorptionHaussiereHighConf());
        // Move price up by +45 — TP1 (entry+40) hit.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25 + 45.0), absorptionHaussiereHighConf());

        List<Quant7GatesSimulation> all = service.listAll();
        assertThat(all).anyMatch(r ->
            r.status() == Quant7GatesSimulationStatus.CLOSED_TP1
                && r.pnlPoints() != null && r.pnlPoints() > 0);
    }

    @Test
    void closesOnStopLossHit() {
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), absorptionHaussiereHighConf());
        // Move price down by 30 — SL (entry-25) hit.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25 - 30.0), absorptionHaussiereHighConf());

        List<Quant7GatesSimulation> all = service.listAll();
        assertThat(all).anyMatch(r ->
            r.status() == Quant7GatesSimulationStatus.CLOSED_SL
                && r.pnlPoints() != null && r.pnlPoints() < 0);
    }

    @Test
    void opensShortFromDistributionSilencieuseHighConf() {
        PatternAnalysis pattern = new PatternAnalysis(
            OrderFlowPattern.DISTRIBUTION_SILENCIEUSE,
            "Distribution silencieuse",
            "Δ=+500 mais prix -10.0pts | Confirmations: [Δ CONFIRMED][ABS BEAR ACTIVE]",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.TRADE
        );
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), pattern);

        List<Quant7GatesSimulation> open = service.listOpen();
        assertThat(open).hasSize(1);
        assertThat(open.get(0).direction()).isEqualTo(Quant7GatesSimulation.Direction.SHORT);
    }

    @Test
    void doesNotOpenTwiceOnSameDirection() {
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), absorptionHaussiereHighConf());
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.26), absorptionHaussiereHighConf());
        assertThat(service.listOpen()).hasSize(1);
    }

    @Test
    void exitPriceSourceReflectsLatestSnapshotFeed() {
        // Open on LIVE_PUSH.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25, "LIVE_PUSH"),
            absorptionHaussiereHighConf());
        Quant7GatesSimulation open = service.listOpen().get(0);
        assertThat(open.priceSource()).isEqualTo("LIVE_PUSH");
        assertThat(open.exitPriceSource()).isEmpty();

        // Mark-to-market on a fallback tick — exitPriceSource should switch
        // to DB_FALLBACK while priceSource (entry provenance) stays LIVE_PUSH.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.30, "DB_FALLBACK"),
            absorptionHaussiereHighConf());
        Quant7GatesSimulation marked = service.listOpen().get(0);
        assertThat(marked.priceSource()).isEqualTo("LIVE_PUSH");
        assertThat(marked.exitPriceSource()).isEqualTo("DB_FALLBACK");
    }

    @Test
    void closeRetainsClosedAtForEvictionOrdering() {
        // Direct record-level check that closedAt is set on close(). The
        // service's capClosedHistory evicts by closedAt (fallback openedAt),
        // so close() must produce a row whose closedAt is monotonically
        // greater than openedAt — otherwise the comparator could keep a
        // long-running trade out of the "recently closed" bucket.
        java.time.Instant openedAt = java.time.Instant.parse("2026-05-22T10:00:00Z");
        java.time.Instant closedAt = java.time.Instant.parse("2026-05-22T11:30:00Z");
        Quant7GatesSimulation row = new Quant7GatesSimulation(
            1L, Instrument.MNQ, Quant7GatesSimulation.Direction.LONG,
            29687.25, 29662.25, 29727.25, 29767.25,
            openedAt, "LONG · test", "LIVE_PUSH",
            Quant7GatesSimulationStatus.OPEN, null, "", null, null, 0.0, 0.0);
        Quant7GatesSimulation closedRow = row.close(
            29727.25, "LIVE_PUSH", closedAt, "TP1 hit", Quant7GatesSimulationStatus.CLOSED_TP1);
        assertThat(closedRow.openedAt()).isEqualTo(openedAt);
        assertThat(closedRow.closedAt()).isEqualTo(closedAt);
        assertThat(closedRow.closedAt()).isAfter(closedRow.openedAt());
    }

    @Test
    void routesOpenAndCloseThroughBridgeWhenWired() {
        RecordingBridge recording = new RecordingBridge();
        Quant7GatesSimulationService wired = new Quant7GatesSimulationService(
            emptyProvider(), emptyRepoProvider(), bridgeProvider(recording));
        wired.resetForTesting();

        // Open LONG → bridge.submitOpen invoked once.
        wired.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), absorptionHaussiereHighConf());
        assertThat(recording.opens).hasSize(1);
        assertThat(recording.opens.get(0).direction()).isEqualTo(Quant7GatesSimulation.Direction.LONG);

        // Drop below SL → paper trade closes → bridge.submitClose invoked once.
        wired.onSnapshot(Instrument.MNQ, snapshotAt(29687.25 - 30.0), absorptionHaussiereHighConf());
        assertThat(recording.closes).hasSize(1);
        assertThat(recording.closes.get(0).status()).isEqualTo(Quant7GatesSimulationStatus.CLOSED_SL);
    }

    @Test
    void paperOnlyWhenBridgeProviderEmpty() {
        // The default service (2-arg ctor) has no bridge — opening must not throw
        // and produces a paper row with no broker interaction.
        service.onSnapshot(Instrument.MNQ, snapshotAt(29687.25), absorptionHaussiereHighConf());
        assertThat(service.listOpen()).hasSize(1);
    }

    private static QuantSnapshot snapshotAt(double price, String source) {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        return new QuantSnapshot(
            Instrument.MNQ, gates, 4, 4,
            price, source, 0.0,
            ZonedDateTime.now(ZoneId.of("America/New_York"))
        );
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static PatternAnalysis absorptionHaussiereHighConf() {
        // Mirrors the screenshot the user shared — bullish absorption with
        // strong delta confirmation and active bull absorption signal.
        return new PatternAnalysis(
            OrderFlowPattern.ABSORPTION_HAUSSIERE,
            "Absorption haussière",
            "Δ=-864 mais prix +12.3pts → acheteurs absorbent les ventes | Confirmations: [Δ CONFIRMED][ABS BULL ACTIVE]",
            PatternAnalysis.Confidence.HIGH,
            PatternAnalysis.Action.AVOID // SHORT view; LONG mirror = TRADE
        );
    }

    private static QuantSnapshot snapshotAt(double price) {
        Map<Gate, GateResult> gates = new EnumMap<>(Gate.class);
        return new QuantSnapshot(
            Instrument.MNQ, gates, 4, 4,
            price, "LIVE_PUSH", 0.0,
            ZonedDateTime.now(ZoneId.of("America/New_York"))
        );
    }

    private static ObjectProvider<Quant7GatesSimulationPublisher> emptyProvider() {
        return new ObjectProvider<>() {
            @Override public Quant7GatesSimulationPublisher getObject() { return null; }
            @Override public Quant7GatesSimulationPublisher getObject(Object... args) { return null; }
            @Override public Quant7GatesSimulationPublisher getIfAvailable() { return null; }
            @Override public Quant7GatesSimulationPublisher getIfUnique() { return null; }
        };
    }

    /** Records the simulations routed through the execution bridge. */
    private static final class RecordingBridge implements Quant7GatesExecutionBridge {
        final List<Quant7GatesSimulation> opens = new java.util.ArrayList<>();
        final List<Quant7GatesSimulation> closes = new java.util.ArrayList<>();
        @Override public RoutingResult submitOpen(Quant7GatesSimulation opened) {
            opens.add(opened);
            return RoutingResult.of(RoutingOutcome.ROUTED);
        }
        @Override public RoutingResult submitClose(Quant7GatesSimulation closed) {
            closes.add(closed);
            return RoutingResult.of(RoutingOutcome.ROUTED);
        }
        @Override public RoutingResult flatten(com.riskdesk.domain.model.TradeExecutionRecord row) {
            return RoutingResult.of(RoutingOutcome.ROUTED);
        }
    }

    private static ObjectProvider<Quant7GatesExecutionBridge> bridgeProvider(Quant7GatesExecutionBridge bridge) {
        return new ObjectProvider<>() {
            @Override public Quant7GatesExecutionBridge getObject() { return bridge; }
            @Override public Quant7GatesExecutionBridge getObject(Object... args) { return bridge; }
            @Override public Quant7GatesExecutionBridge getIfAvailable() { return bridge; }
            @Override public Quant7GatesExecutionBridge getIfUnique() { return bridge; }
        };
    }

    private static ObjectProvider<com.riskdesk.domain.quant.simulation.port.Quant7GatesSimulationRepositoryPort> emptyRepoProvider() {
        return new ObjectProvider<>() {
            @Override public com.riskdesk.domain.quant.simulation.port.Quant7GatesSimulationRepositoryPort getObject() { return null; }
            @Override public com.riskdesk.domain.quant.simulation.port.Quant7GatesSimulationRepositoryPort getObject(Object... args) { return null; }
            @Override public com.riskdesk.domain.quant.simulation.port.Quant7GatesSimulationRepositoryPort getIfAvailable() { return null; }
            @Override public com.riskdesk.domain.quant.simulation.port.Quant7GatesSimulationRepositoryPort getIfUnique() { return null; }
        };
    }
}
