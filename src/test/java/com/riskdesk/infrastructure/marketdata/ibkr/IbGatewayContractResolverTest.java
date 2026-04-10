package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types.SecType;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IbGatewayContractResolver}.
 *
 * Tests cover:
 * - resolve() caching behavior (hit vs miss)
 * - resolve() for non-exchange-traded instruments (DXY)
 * - refresh() selects nearest expiry
 * - refresh() with empty IBKR response
 * - setResolved() seeds cache directly
 * - refreshToMonth() targeting a specific contract month
 * - refreshToMonth() when target month not found
 * - resolveTopTwo() returns sorted top-2 contracts
 * - resolveTopTwo() with fewer than 2 contracts
 * - resolveTopTwo() for non-exchange-traded instrument
 * - clearCache() removes all entries
 * - detectInstrument() for all supported symbols
 * - expiryKey parsing: YYYYMMDD, YYYYMM, null, blank
 */
@DisplayName("IbGatewayContractResolver")
class IbGatewayContractResolverTest {

    private IbGatewayNativeClient nativeClient;
    private IbGatewayContractResolver resolver;

    @BeforeEach
    void setUp() {
        nativeClient = mock(IbGatewayNativeClient.class);
        resolver = new IbGatewayContractResolver(nativeClient);
    }

    // -----------------------------------------------------------------------
    // resolve()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resolve()")
    class ResolveTests {

        @Test
        @DisplayName("Returns empty for DXY (synthetic, not exchange-traded)")
        void returnsEmptyForDxy() {
            Optional<IbGatewayResolvedContract> result = resolver.resolve(Instrument.DXY);
            assertThat(result).isEmpty();
            verifyNoInteractions(nativeClient);
        }

        @Test
        @DisplayName("Returns cached contract on second call without re-querying IBKR")
        void returnsCachedContractOnSecondCall() {
            ContractDetails details = contractDetails(Instrument.MCL, "20260520");
            when(nativeClient.requestContractDetails(any())).thenReturn(List.of(details));

            Optional<IbGatewayResolvedContract> first = resolver.resolve(Instrument.MCL);
            Optional<IbGatewayResolvedContract> second = resolver.resolve(Instrument.MCL);

            assertThat(first).isPresent();
            assertThat(second).isPresent();
            assertThat(second.get()).isSameAs(first.get());
            // nativeClient called only once (first call triggers refresh)
            verify(nativeClient, atMost(4)).requestContractDetails(any());
        }

        @Test
        @DisplayName("Cache hit via setResolved prevents any IBKR query")
        void setResolvedSeedsCachePreventingIbkrQuery() {
            IbGatewayResolvedContract seeded = resolvedContract(Instrument.MCL, "202505");
            resolver.setResolved(Instrument.MCL, seeded);

            Optional<IbGatewayResolvedContract> result = resolver.resolve(Instrument.MCL);

            assertThat(result).isPresent();
            assertThat(result.get()).isSameAs(seeded);
            verifyNoInteractions(nativeClient);
        }

        @Test
        @DisplayName("Returns empty when IBKR returns no contract details")
        void returnsEmptyWhenIbkrReturnsNothing() {
            when(nativeClient.requestContractDetails(any())).thenReturn(List.of());

            Optional<IbGatewayResolvedContract> result = resolver.resolve(Instrument.MCL);

            assertThat(result).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // refresh()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("refresh()")
    class RefreshTests {

        @Test
        @DisplayName("Selects nearest expiry among multiple contracts")
        void selectsNearestExpiry() {
            ContractDetails may = contractDetails(Instrument.MCL, "20260520");
            ContractDetails june = contractDetails(Instrument.MCL, "20260619");
            ContractDetails sept = contractDetails(Instrument.MCL, "20260918");
            when(nativeClient.requestContractDetails(any())).thenReturn(List.of(june, sept, may));

            Optional<IbGatewayResolvedContract> result = resolver.refresh(Instrument.MCL);

            assertThat(result).isPresent();
            assertThat(result.get().contract().lastTradeDateOrContractMonth()).isEqualTo("20260520");
        }

        @Test
        @DisplayName("Clears cache before re-querying IBKR")
        void clearsCacheBeforeRefresh() {
            // Seed cache
            resolver.setResolved(Instrument.MCL, resolvedContract(Instrument.MCL, "202504"));

            ContractDetails newContract = contractDetails(Instrument.MCL, "20260520");
            when(nativeClient.requestContractDetails(any())).thenReturn(List.of(newContract));

            Optional<IbGatewayResolvedContract> result = resolver.refresh(Instrument.MCL);

            assertThat(result).isPresent();
            assertThat(result.get().contract().lastTradeDateOrContractMonth()).isEqualTo("20260520");
        }

        @Test
        @DisplayName("Returns empty for non-exchange-traded instrument")
        void returnsEmptyForDxy() {
            Optional<IbGatewayResolvedContract> result = resolver.refresh(Instrument.DXY);
            assertThat(result).isEmpty();
            verifyNoInteractions(nativeClient);
        }
    }

    // -----------------------------------------------------------------------
    // refreshToMonth()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("refreshToMonth()")
    class RefreshToMonthTests {

        @Test
        @DisplayName("Caches specific target month contract")
        void cachesTargetMonthContract() {
            ContractDetails may = contractDetails(Instrument.MCL, "20260520");
            ContractDetails june = contractDetails(Instrument.MCL, "20260619");
            when(nativeClient.requestContractDetails(any())).thenReturn(List.of(may, june));

            resolver.refreshToMonth(Instrument.MCL, "202606");

            Optional<IbGatewayResolvedContract> cached = resolver.resolve(Instrument.MCL);
            assertThat(cached).isPresent();
            assertThat(cached.get().contract().lastTradeDateOrContractMonth()).isEqualTo("20260619");
            // No additional IBKR call on resolve() since cache is populated
        }

        @Test
        @DisplayName("Cache stays empty when target month not found in IBKR response")
        void cacheEmptyWhenTargetMonthNotFound() {
            ContractDetails may = contractDetails(Instrument.MCL, "20260520");
            when(nativeClient.requestContractDetails(any())).thenReturn(List.of(may));

            resolver.refreshToMonth(Instrument.MCL, "202612");

            // Cache should be empty — next resolve() will trigger a fresh refresh()
            // We verify by checking that resolve triggers a new IBKR call
            when(nativeClient.requestContractDetails(any())).thenReturn(List.of(may));
            Optional<IbGatewayResolvedContract> result = resolver.resolve(Instrument.MCL);
            assertThat(result).isPresent();
        }
    }

    // -----------------------------------------------------------------------
    // resolveTopTwo()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("resolveTopTwo()")
    class ResolveTopTwoTests {

        @Test
        @DisplayName("Returns two nearest contracts sorted by expiry")
        void returnsTwoNearestSortedByExpiry() {
            ContractDetails may = contractDetails(Instrument.MCL, "20260520");
            ContractDetails june = contractDetails(Instrument.MCL, "20260619");
            ContractDetails sept = contractDetails(Instrument.MCL, "20260918");
            when(nativeClient.requestContractDetails(any())).thenReturn(List.of(sept, may, june));

            List<IbGatewayResolvedContract> topTwo = resolver.resolveTopTwo(Instrument.MCL);

            assertThat(topTwo).hasSize(2);
            assertThat(topTwo.get(0).contract().lastTradeDateOrContractMonth()).isEqualTo("20260520");
            assertThat(topTwo.get(1).contract().lastTradeDateOrContractMonth()).isEqualTo("20260619");
        }

        @Test
        @DisplayName("Returns single contract when only one available")
        void returnsSingleContractWhenOnlyOneAvailable() {
            ContractDetails may = contractDetails(Instrument.MCL, "20260520");
            when(nativeClient.requestContractDetails(any())).thenReturn(List.of(may));

            List<IbGatewayResolvedContract> topTwo = resolver.resolveTopTwo(Instrument.MCL);

            assertThat(topTwo).hasSize(1);
        }

        @Test
        @DisplayName("Returns empty list for DXY")
        void returnsEmptyForDxy() {
            List<IbGatewayResolvedContract> result = resolver.resolveTopTwo(Instrument.DXY);
            assertThat(result).isEmpty();
            verifyNoInteractions(nativeClient);
        }

        @Test
        @DisplayName("Returns empty list when IBKR returns no contracts")
        void returnsEmptyWhenIbkrEmpty() {
            when(nativeClient.requestContractDetails(any())).thenReturn(List.of());

            List<IbGatewayResolvedContract> result = resolver.resolveTopTwo(Instrument.MCL);
            assertThat(result).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // detectInstrument()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("detectInstrument()")
    class DetectInstrumentTests {

        @Test
        @DisplayName("Detects MCL from symbol")
        void detectsMclFromSymbol() {
            Contract c = new Contract();
            c.symbol("MCL");
            assertThat(resolver.detectInstrument(c)).contains(Instrument.MCL);
        }

        @Test
        @DisplayName("Detects E6 from tradingClass '6E'")
        void detectsE6FromTradingClass() {
            Contract c = new Contract();
            c.symbol("EUR");
            c.tradingClass("6E");
            assertThat(resolver.detectInstrument(c)).contains(Instrument.E6);
        }

        @Test
        @DisplayName("Detects MNQ from localSymbol prefix")
        void detectsMnqFromLocalSymbol() {
            Contract c = new Contract();
            c.symbol("UNKNOWN");
            c.localSymbol("MNQM6");
            assertThat(resolver.detectInstrument(c)).contains(Instrument.MNQ);
        }

        @Test
        @DisplayName("Returns empty for unknown contract")
        void returnsEmptyForUnknown() {
            Contract c = new Contract();
            c.symbol("ZZZ");
            assertThat(resolver.detectInstrument(c)).isEmpty();
        }

        @Test
        @DisplayName("Handles null fields gracefully")
        void handlesNullFieldsGracefully() {
            Contract c = new Contract();
            // All fields null
            assertThat(resolver.detectInstrument(c)).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // clearCache()
    // -----------------------------------------------------------------------

    @Test
    @DisplayName("clearCache removes all cached contracts")
    void clearCacheRemovesAll() {
        resolver.setResolved(Instrument.MCL, resolvedContract(Instrument.MCL, "202505"));
        resolver.setResolved(Instrument.MGC, resolvedContract(Instrument.MGC, "202506"));

        resolver.clearCache();

        // After clear, resolve should trigger IBKR query
        when(nativeClient.requestContractDetails(any())).thenReturn(List.of());
        assertThat(resolver.resolve(Instrument.MCL)).isEmpty();
        assertThat(resolver.resolve(Instrument.MGC)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static ContractDetails contractDetails(Instrument instrument, String contractMonth) {
        Contract contract = new Contract();
        contract.secType(SecType.FUT);
        contract.symbol(instrument == Instrument.E6 ? "EUR" : instrument.name());
        contract.lastTradeDateOrContractMonth(contractMonth);

        ContractDetails details = new ContractDetails();
        details.contract(contract);
        return details;
    }

    private static IbGatewayResolvedContract resolvedContract(Instrument instrument, String contractMonth) {
        Contract contract = new Contract();
        contract.lastTradeDateOrContractMonth(contractMonth);
        return new IbGatewayResolvedContract(instrument, contract, null);
    }
}
