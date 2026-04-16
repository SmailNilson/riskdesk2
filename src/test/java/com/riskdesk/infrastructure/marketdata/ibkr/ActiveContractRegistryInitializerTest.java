package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.port.ActiveContractSnapshotStore;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the boot-time resolution chain, especially the failure mode reported when
 * IBKR Gateway is unreachable during a redeploy: IBKR → snapshot → property fallback,
 * with a staleness guard that refuses any contract month older than the current month.
 */
class ActiveContractRegistryInitializerTest {

    // Deterministic "today" = 2026-04-16 (matches the current trading calendar).
    private static final Clock FIXED_CLOCK = Clock.fixed(
        Instant.parse("2026-04-16T12:00:00Z"),
        ZoneId.of("America/New_York"));

    private ActiveContractRegistry        registry;
    private IbGatewayContractResolver     resolver;
    private IbkrProperties                ibkrProperties;
    private OpenInterestProvider          openInterestProvider;
    private ActiveContractSnapshotStore   snapshotStore;
    private ApplicationArguments          args;

    @BeforeEach
    void setUp() {
        registry             = new ActiveContractRegistry();
        resolver             = mock(IbGatewayContractResolver.class);
        ibkrProperties       = mock(IbkrProperties.class);
        openInterestProvider = mock(OpenInterestProvider.class);
        snapshotStore        = mock(ActiveContractSnapshotStore.class);
        args                 = mock(ApplicationArguments.class);
    }

    private ActiveContractRegistryInitializer newInitializer(String fallbackMcl,
                                                             String fallbackMgc,
                                                             String fallbackMnq,
                                                             String fallbackE6) {
        ActiveContractRegistryInitializer init = new ActiveContractRegistryInitializer(
            registry, resolver, ibkrProperties, openInterestProvider, snapshotStore, FIXED_CLOCK);
        ReflectionTestUtils.setField(init, "fallbackMcl", fallbackMcl);
        ReflectionTestUtils.setField(init, "fallbackMgc", fallbackMgc);
        ReflectionTestUtils.setField(init, "fallbackMnq", fallbackMnq);
        ReflectionTestUtils.setField(init, "fallbackE6",  fallbackE6);
        return init;
    }

    private static IbGatewayResolvedContract contractFor(Instrument instrument, String yyyymm) {
        Contract c = new Contract();
        c.symbol(instrument.name());
        c.secType("FUT");
        c.lastTradeDateOrContractMonth(yyyymm);
        return new IbGatewayResolvedContract(instrument, c, null);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IBKR reachable — persists the resolved month as a snapshot.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void ibkrResolution_persistsSnapshotAndInitializesRegistry() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(resolver.resolveNextContracts(any())).thenReturn(List.of());
        // Only MNQ resolves from IBKR — the other instruments stay unresolved in this test.
        when(resolver.resolveNextContracts(Instrument.MNQ))
            .thenReturn(List.of(contractFor(Instrument.MNQ, "202606"), contractFor(Instrument.MNQ, "202609")));
        when(openInterestProvider.fetchOpenInterest(Instrument.MNQ, "202606")).thenReturn(OptionalLong.of(5_000));
        when(openInterestProvider.fetchOpenInterest(Instrument.MNQ, "202609")).thenReturn(OptionalLong.of(100));

        newInitializer("202606", "202606", "202606", "202606").run(args);

        assertThat(registry.getContractMonth(Instrument.MNQ)).contains("202606");
        verify(snapshotStore).save(eq(Instrument.MNQ), eq("202606"),
            eq(ActiveContractSnapshotStore.Source.IBKR_OI), any(Instant.class));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IBKR unreachable — prefers the persisted snapshot over stale properties.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void ibkrUnreachable_restoresFromSnapshotBeforeFallingBackToProperties() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(resolver.resolveNextContracts(any())).thenReturn(List.of());
        // Snapshot has the correct June 2026 contract; property is stale June 2025.
        when(snapshotStore.load(Instrument.MGC)).thenReturn(Optional.of(
            new ActiveContractSnapshotStore.Snapshot(
                Instrument.MGC, "202606",
                ActiveContractSnapshotStore.Source.ROLLOVER_CONFIRM,
                Instant.parse("2026-03-01T00:00:00Z"))));

        // Give MGC a stale property — the snapshot MUST win.
        newInitializer("202606", "202506", "202606", "202606").run(args);

        assertThat(registry.getContractMonth(Instrument.MGC)).contains("202606");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Staleness guard — an expired property is refused, the slot stays empty.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void staleFallbackProperty_isRefusedAndInstrumentLeftUninitialized() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(resolver.resolveNextContracts(any())).thenReturn(List.of());
        when(snapshotStore.load(any())).thenReturn(Optional.empty());

        // MGC fallback is Jan 2024 — 27 months behind the current Apr-2026 clock.
        newInitializer("202606", "202401", "202606", "202606").run(args);

        assertThat(registry.getContractMonth(Instrument.MGC)).isEmpty();
        // Other instruments with valid fallbacks still get initialized.
        assertThat(registry.getContractMonth(Instrument.MCL)).contains("202606");
        assertThat(registry.getContractMonth(Instrument.MNQ)).contains("202606");
        assertThat(registry.getContractMonth(Instrument.E6)).contains("202606");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Staleness guard also rejects an expired snapshot (e.g. app down 3 months).
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void expiredSnapshot_isRejectedAndPropertyFallbackTakesOver() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(resolver.resolveNextContracts(any())).thenReturn(List.of());
        when(snapshotStore.load(Instrument.MCL)).thenReturn(Optional.of(
            new ActiveContractSnapshotStore.Snapshot(
                Instrument.MCL, "202601",
                ActiveContractSnapshotStore.Source.IBKR_OI,
                Instant.parse("2026-01-01T00:00:00Z"))));

        newInitializer("202606", "202606", "202606", "202606").run(args);

        // Expired snapshot (Jan 2026) rejected — fresh property (Jun 2026) wins.
        assertThat(registry.getContractMonth(Instrument.MCL)).contains("202606");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // IBKR disabled + no snapshot — property fallback works if fresh.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void ibkrDisabled_fallsBackToPropertyWhenFreshAndNeverCallsResolver() {
        when(ibkrProperties.isEnabled()).thenReturn(false);
        when(snapshotStore.load(any())).thenReturn(Optional.empty());

        newInitializer("202606", "202606", "202606", "202606").run(args);

        assertThat(registry.snapshot())
            .containsEntry(Instrument.MCL, "202606")
            .containsEntry(Instrument.MGC, "202606")
            .containsEntry(Instrument.MNQ, "202606")
            .containsEntry(Instrument.E6,  "202606");
        verify(resolver, never()).resolveNextContracts(any());
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Snapshot store failure must not crash startup — we degrade to properties.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void snapshotLoadException_degradesGracefullyToPropertyFallback() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(resolver.resolveNextContracts(any())).thenReturn(List.of());
        when(snapshotStore.load(any())).thenThrow(new RuntimeException("DB down"));
        // Unused stubbings are expected for the other instruments.
        lenient().when(openInterestProvider.fetchOpenInterest(any(), any()))
            .thenReturn(OptionalLong.empty());

        newInitializer("202606", "202606", "202606", "202606").run(args);

        // All four instruments still resolved via property fallback.
        assertThat(registry.snapshot()).hasSize(4);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sanity: when IBKR returns a single contract, the snapshot source is IBKR_FRONT.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    void singleIbkrContract_persistsAsIbkrFront() {
        when(ibkrProperties.isEnabled()).thenReturn(true);
        when(resolver.resolveNextContracts(any())).thenReturn(List.of());
        when(resolver.resolveNextContracts(Instrument.E6))
            .thenReturn(List.of(contractFor(Instrument.E6, "202606")));

        newInitializer("202606", "202606", "202606", "202606").run(args);

        ArgumentCaptor<ActiveContractSnapshotStore.Source> sourceCap =
            ArgumentCaptor.forClass(ActiveContractSnapshotStore.Source.class);
        verify(snapshotStore).save(eq(Instrument.E6), eq("202606"), sourceCap.capture(), any());
        assertThat(sourceCap.getValue()).isEqualTo(ActiveContractSnapshotStore.Source.IBKR_FRONT);
    }

    // Sanity on the staleness guard boundary: a contract in the *current* month is accepted.
    @Test
    void currentMonthFallback_isAccepted() {
        when(ibkrProperties.isEnabled()).thenReturn(false);
        when(snapshotStore.load(any())).thenReturn(Optional.empty());

        // Current YearMonth under FIXED_CLOCK = 2026-04 — accept "202604".
        YearMonth now = YearMonth.now(FIXED_CLOCK);
        assertThat(now).isEqualTo(YearMonth.of(2026, 4));

        newInitializer("202604", "202604", "202604", "202604").run(args);

        assertThat(registry.snapshot()).hasSize(4);
    }
}
