package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.port.ActiveContractPersistencePort;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.ApplicationArguments;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the startup contract-resolution policy: a manual roll persisted in the DB must NOT be
 * dragged backward by IBKR's highest-OI pick on the next restart, while a normal forward
 * auto-advance must still be adopted.
 *
 * <p>Regression for: MNQ manually rolled Jun→Sep days before expiry (when June still carried ~10×
 * September's open interest) silently reverted to June on every restart, because
 * {@code resolveFromIbkr()} selects the highest-OI contract and overwrote the DB value.
 */
class ActiveContractRegistryInitializerTest {

    private ActiveContractPersistencePort port;
    private IbGatewayContractResolver resolver;
    private IbkrProperties ibkrProperties;
    private OpenInterestProvider oi;

    @BeforeEach
    void setUp() {
        port = Mockito.mock(ActiveContractPersistencePort.class);
        resolver = Mockito.mock(IbGatewayContractResolver.class);
        ibkrProperties = Mockito.mock(IbkrProperties.class);
        oi = Mockito.mock(OpenInterestProvider.class);
        when(ibkrProperties.isEnabled()).thenReturn(true);
    }

    private ActiveContractRegistryInitializer initializer(Map<Instrument, String> dbState) {
        when(port.loadAll()).thenReturn(dbState);
        ActiveContractRegistry registry = new ActiveContractRegistry(port);
        ActiveContractRegistryInitializer init =
            new ActiveContractRegistryInitializer(registry, resolver, ibkrProperties, oi);
        // @Value fallbacks aren't injected in a unit test; Map.of() in run() NPEs on null values.
        ReflectionTestUtils.setField(init, "fallbackMcl", "202606");
        ReflectionTestUtils.setField(init, "fallbackMgc", "202608");
        ReflectionTestUtils.setField(init, "fallbackMnq", "202606");
        ReflectionTestUtils.setField(init, "fallbackE6", "202606");
        return init;
    }

    private IbGatewayResolvedContract mnqContract(String lastTradeDate) {
        Contract c = new Contract();
        c.symbol("MNQ");
        c.lastTradeDateOrContractMonth(lastTradeDate);
        return new IbGatewayResolvedContract(Instrument.MNQ, c, null);
    }

    @Test
    void manualForwardRoll_isKept_whenIbkrPicksEarlierHighOiFrontMonth() {
        // DB holds the deliberate Sep roll; the other instruments are seeded so their (no-IBKR)
        // branch uses the DB value rather than the fallback — keeps the test focused on MNQ.
        Map<Instrument, String> db = Map.of(
            Instrument.MNQ, "202609",
            Instrument.MCL, "202606",
            Instrument.MGC, "202608",
            Instrument.E6,  "202606");
        ActiveContractRegistryInitializer init = initializer(db);
        ActiveContractRegistry registry =
            (ActiveContractRegistry) ReflectionTestUtils.getField(init, "registry");

        // IBKR offers June + September; June has ~10× the OI so resolveFromIbkr() picks June.
        when(resolver.resolveNextContracts(Instrument.MNQ))
            .thenReturn(List.of(mnqContract("20260619"), mnqContract("20260918")));
        when(oi.fetchOpenInterest(Instrument.MNQ, "202606")).thenReturn(OptionalLong.of(265_653));
        when(oi.fetchOpenInterest(Instrument.MNQ, "202609")).thenReturn(OptionalLong.of(23_152));

        init.run(Mockito.mock(ApplicationArguments.class));

        // September is kept (not reverted to June) and the resolver cache is realigned to it.
        assertThat(registry.getContractMonth(Instrument.MNQ)).contains("202609");
        verify(resolver).refreshToMonth(Instrument.MNQ, "202609");
    }

    @Test
    void normalAutoAdvance_adoptsLaterIbkrMonth() {
        // DB on June; the front month has rolled so IBKR now resolves September (a LATER month).
        Map<Instrument, String> db = Map.of(
            Instrument.MNQ, "202606",
            Instrument.MCL, "202606",
            Instrument.MGC, "202608",
            Instrument.E6,  "202606");
        ActiveContractRegistryInitializer init = initializer(db);
        ActiveContractRegistry registry =
            (ActiveContractRegistry) ReflectionTestUtils.getField(init, "registry");

        when(resolver.resolveNextContracts(Instrument.MNQ))
            .thenReturn(List.of(mnqContract("20260918")));   // single contract → adopted directly

        init.run(Mockito.mock(ApplicationArguments.class));

        // The later month is adopted (forward auto-advance preserved); no backward-keep realignment.
        assertThat(registry.getContractMonth(Instrument.MNQ)).contains("202609");
        verify(resolver, never()).refreshToMonth(eq(Instrument.MNQ), any());
    }

    @Test
    void isEarlierMonth_comparesYyyyMm() {
        assertThat(ActiveContractRegistryInitializer.isEarlierMonth("202606", "202609")).isTrue();
        assertThat(ActiveContractRegistryInitializer.isEarlierMonth("202612", "202703")).isTrue();
        assertThat(ActiveContractRegistryInitializer.isEarlierMonth("202609", "202606")).isFalse();
        assertThat(ActiveContractRegistryInitializer.isEarlierMonth("202609", "202609")).isFalse();
        assertThat(ActiveContractRegistryInitializer.isEarlierMonth(null, "202609")).isFalse();
        assertThat(ActiveContractRegistryInitializer.isEarlierMonth("202609", null)).isFalse();
    }
}
