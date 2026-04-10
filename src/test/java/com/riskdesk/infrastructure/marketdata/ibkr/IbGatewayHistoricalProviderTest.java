package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.Types.SecType;
import com.ib.controller.Bar;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link IbGatewayHistoricalProvider}.
 *
 * Focus on the critical parseBarTime() logic (PR #90 fix) and fetchHistory orchestration.
 * Tests cover:
 * - bar.time() epoch path (primary, safe)
 * - bar.timeStr() with embedded timezone suffix (e.g. "US/Central")
 * - bar.timeStr() with default zone fallback (America/Chicago)
 * - bar.timeStr() date-only format (daily bars)
 * - bar.timeStr() with invalid timezone suffix → fallback to default
 * - bar.timeStr() null/blank → Instant.now() fallback
 * - DXY (non-exchange-traded) returns empty
 * - supports() method for valid/invalid timeframes
 */
@DisplayName("IbGatewayHistoricalProvider")
class IbGatewayHistoricalProviderTest {

    private IbGatewayNativeClient nativeClient;
    private IbGatewayContractResolver contractResolver;
    private IbGatewayHistoricalProvider provider;

    @BeforeEach
    void setUp() {
        nativeClient = mock(IbGatewayNativeClient.class);
        contractResolver = mock(IbGatewayContractResolver.class);
        provider = new IbGatewayHistoricalProvider(nativeClient, contractResolver);
    }

    // -----------------------------------------------------------------------
    // fetchHistory()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("fetchHistory()")
    class FetchHistoryTests {

        @Test
        @DisplayName("Returns empty for DXY (non-exchange-traded)")
        void returnsEmptyForDxy() {
            List<Candle> result = provider.fetchHistory(Instrument.DXY, "5m", 100);
            assertThat(result).isEmpty();
            verifyNoInteractions(nativeClient);
        }

        @Test
        @DisplayName("Returns empty when no contract resolved")
        void returnsEmptyWhenNoContractResolved() {
            when(contractResolver.resolve(Instrument.MCL)).thenReturn(Optional.empty());

            List<Candle> result = provider.fetchHistory(Instrument.MCL, "5m", 100);
            assertThat(result).isEmpty();
        }

        @Test
        @DisplayName("Converts IBKR bars to Candle list sorted by timestamp")
        void convertsBarsToCandlesSorted() {
            setupMclResolver();

            long t1 = Instant.parse("2026-04-05T14:00:00Z").getEpochSecond();
            long t2 = Instant.parse("2026-04-05T14:05:00Z").getEpochSecond();
            // Return in reverse order to verify sorting
            // Bar(long time, double high, double low, double open, double close, Decimal wap, Decimal volume, int count)
            Bar bar2 = epochBar(t2, 62.80, 62.40, 62.50, 62.70, 1500);
            Bar bar1 = epochBar(t1, 62.30, 61.90, 62.00, 62.20, 1200);
            when(nativeClient.requestHistoricalBars(any(), anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(bar2, bar1));

            List<Candle> result = provider.fetchHistory(Instrument.MCL, "5m", 2);

            assertThat(result).hasSize(2);
            assertThat(result.get(0).getTimestamp()).isBefore(result.get(1).getTimestamp());
            assertThat(result.get(0).getOpen()).isEqualByComparingTo("62.00");
            assertThat(result.get(1).getOpen()).isEqualByComparingTo("62.50");
        }

        @Test
        @DisplayName("Parses bar with epoch seconds (primary path)")
        void parsesBarWithEpochSeconds() {
            setupMclResolver();

            long epoch = Instant.parse("2026-04-05T14:30:00Z").getEpochSecond();
            Bar bar = epochBar(epoch, 62.30, 61.90, 62.00, 62.20, 1000);
            when(nativeClient.requestHistoricalBars(any(), anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(bar));

            List<Candle> result = provider.fetchHistory(Instrument.MCL, "5m", 1);

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTimestamp()).isEqualTo(Instant.ofEpochSecond(epoch));
        }

        @Test
        @DisplayName("Parses bar.timeStr with embedded US/Central timezone (PR #90)")
        void parsesBarTimeStrWithEmbeddedTimezone() {
            setupMclResolver();

            // String constructor: bar.time() returns Long.MAX_VALUE
            // "20260405 09:30:00 US/Central" → CDT (UTC-5) → 2026-04-05T14:30:00Z
            Bar bar = timeStrBar("20260405 09:30:00 US/Central", 62.30, 61.90, 62.00, 62.20, 1000);
            when(nativeClient.requestHistoricalBars(any(), anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(bar));

            List<Candle> result = provider.fetchHistory(Instrument.MCL, "5m", 1);

            assertThat(result).hasSize(1);
            long expectedEpoch = LocalDateTime.of(2026, 4, 5, 9, 30, 0)
                .atZone(ZoneId.of("US/Central"))
                .toEpochSecond();
            assertThat(result.get(0).getTimestamp()).isEqualTo(Instant.ofEpochSecond(expectedEpoch));
        }

        @Test
        @DisplayName("Parses bar.timeStr without timezone — defaults to America/Chicago")
        void parsesBarTimeStrWithDefaultTimezone() {
            setupMclResolver();

            Bar bar = timeStrBar("20260405 09:30:00", 62.30, 61.90, 62.00, 62.20, 1000);
            when(nativeClient.requestHistoricalBars(any(), anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(bar));

            List<Candle> result = provider.fetchHistory(Instrument.MCL, "5m", 1);

            assertThat(result).hasSize(1);
            long expectedEpoch = LocalDateTime.of(2026, 4, 5, 9, 30, 0)
                .atZone(ZoneId.of("America/Chicago"))
                .toEpochSecond();
            assertThat(result.get(0).getTimestamp()).isEqualTo(Instant.ofEpochSecond(expectedEpoch));
        }

        @Test
        @DisplayName("Parses daily bar (date-only 8 chars) as exchange-local date")
        void parsesDailyBarDateOnly() {
            setupMclResolver();

            Bar bar = timeStrBar("20260405", 62.30, 61.90, 62.00, 62.20, 50000);
            when(nativeClient.requestHistoricalBars(any(), anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(bar));

            List<Candle> result = provider.fetchHistory(Instrument.MCL, "5m", 1);

            assertThat(result).hasSize(1);
            long expectedEpoch = LocalDate.of(2026, 4, 5)
                .atStartOfDay(ZoneId.of("America/Chicago"))
                .toEpochSecond();
            assertThat(result.get(0).getTimestamp()).isEqualTo(Instant.ofEpochSecond(expectedEpoch));
        }

        @Test
        @DisplayName("Invalid embedded timezone falls back to America/Chicago default")
        void invalidTimezoneDefaultsToChicago() {
            setupMclResolver();

            Bar bar = timeStrBar("20260405 09:30:00 Invalid/Zone", 62.30, 61.90, 62.00, 62.20, 1000);
            when(nativeClient.requestHistoricalBars(any(), anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(bar));

            List<Candle> result = provider.fetchHistory(Instrument.MCL, "5m", 1);

            assertThat(result).hasSize(1);
            long expectedEpoch = LocalDateTime.of(2026, 4, 5, 9, 30, 0)
                .atZone(ZoneId.of("America/Chicago"))
                .toEpochSecond();
            assertThat(result.get(0).getTimestamp()).isEqualTo(Instant.ofEpochSecond(expectedEpoch));
        }

        @Test
        @DisplayName("Parses bar.timeStr with US/Pacific timezone correctly")
        void parsesBarTimeStrWithPacificTimezone() {
            setupMclResolver();

            Bar bar = timeStrBar("20260405 07:30:00 US/Pacific", 62.30, 61.90, 62.00, 62.20, 1000);
            when(nativeClient.requestHistoricalBars(any(), anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(bar));

            List<Candle> result = provider.fetchHistory(Instrument.MCL, "5m", 1);

            assertThat(result).hasSize(1);
            long expectedEpoch = LocalDateTime.of(2026, 4, 5, 7, 30, 0)
                .atZone(ZoneId.of("US/Pacific"))
                .toEpochSecond();
            assertThat(result.get(0).getTimestamp()).isEqualTo(Instant.ofEpochSecond(expectedEpoch));
        }

        @Test
        @DisplayName("Unparseable bar.timeStr falls back to approximately Instant.now()")
        void unparseableBarTimeStrFallsBackToNow() {
            setupMclResolver();

            Instant before = Instant.now();
            Bar bar = timeStrBar("GARBAGE_VALUE", 62.30, 61.90, 62.00, 62.20, 1000);
            when(nativeClient.requestHistoricalBars(any(), anyInt(), any(), any(), any(), anyBoolean()))
                .thenReturn(List.of(bar));

            List<Candle> result = provider.fetchHistory(Instrument.MCL, "5m", 1);
            Instant after = Instant.now();

            assertThat(result).hasSize(1);
            assertThat(result.get(0).getTimestamp().getEpochSecond())
                .isBetween(before.getEpochSecond(), after.getEpochSecond() + 1);
        }
    }

    // -----------------------------------------------------------------------
    // supports()
    // -----------------------------------------------------------------------

    @Nested
    @DisplayName("supports()")
    class SupportsTests {

        @Test
        @DisplayName("Returns true for all valid timeframes: 5m, 10m, 1h, 4h, 1d")
        void supportedTimeframes() {
            for (String tf : List.of("5m", "10m", "1h", "4h", "1d")) {
                assertThat(provider.supports(Instrument.MCL, tf))
                    .as("Should support timeframe %s", tf)
                    .isTrue();
            }
        }

        @Test
        @DisplayName("Returns false for unsupported timeframe '30m'")
        void unsupportedTimeframe30m() {
            assertThat(provider.supports(Instrument.MCL, "30m")).isFalse();
        }

        @Test
        @DisplayName("Returns false for DXY regardless of timeframe")
        void returnsFalseForDxy() {
            assertThat(provider.supports(Instrument.DXY, "5m")).isFalse();
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private void setupMclResolver() {
        Contract contract = mclContract();
        when(contractResolver.resolve(Instrument.MCL)).thenReturn(
            Optional.of(new IbGatewayResolvedContract(Instrument.MCL, contract, null))
        );
    }

    private static Contract mclContract() {
        Contract contract = new Contract();
        contract.secType(SecType.FUT);
        contract.symbol("MCL");
        contract.exchange("NYMEX");
        contract.currency("USD");
        contract.lastTradeDateOrContractMonth("202605");
        return contract;
    }

    /**
     * Creates a Bar using the epoch-seconds constructor.
     * TWS API: Bar(long time, double high, double low, double open, double close, Decimal wap, Decimal volume, int count)
     */
    private static Bar epochBar(long epochSeconds, double high, double low, double open, double close, long volume) {
        return new Bar(epochSeconds, high, low, open, close,
            Decimal.ZERO, Decimal.get(volume), 0);
    }

    /**
     * Creates a Bar using the timeStr constructor (simulates bar.time() returning Long.MAX_VALUE).
     * TWS API: Bar(String timeStr, double high, double low, double open, double close, Decimal wap, Decimal volume, int count)
     */
    private static Bar timeStrBar(String timeStr, double high, double low, double open, double close, long volume) {
        return new Bar(timeStr, high, low, open, close,
            Decimal.ZERO, Decimal.get(volume), 0);
    }
}
