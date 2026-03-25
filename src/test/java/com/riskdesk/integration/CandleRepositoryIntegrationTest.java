package com.riskdesk.integration;

import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.persistence.CandleRepository;
import com.riskdesk.infrastructure.persistence.CandleEntityMapper;
import com.riskdesk.infrastructure.persistence.entity.CandleEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@DataJpaTest
@ActiveProfiles("test")
class CandleRepositoryIntegrationTest {

    @Autowired
    private CandleRepository candleRepository;

    @BeforeEach
    void setUp() {
        candleRepository.deleteAll();
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private CandleEntity createCandle(Instrument instrument, String timeframe, Instant timestamp,
                                      double open, double high, double low, double close, long volume) {
        return CandleEntityMapper.toEntity(new Candle(
                instrument, timeframe, timestamp,
                new BigDecimal(String.valueOf(open)),
                new BigDecimal(String.valueOf(high)),
                new BigDecimal(String.valueOf(low)),
                new BigDecimal(String.valueOf(close)),
                volume
        ));
    }

    // -----------------------------------------------------------------------
    // save and retrieve
    // -----------------------------------------------------------------------

    @Test
    void saveAndRetrieve_candleWorksCorrectly() {
        Instant now = Instant.now();
        CandleEntity candle = createCandle(Instrument.MCL, "10m", now, 62.40, 62.80, 62.10, 62.55, 500);
        CandleEntity saved = candleRepository.save(candle);

        assertNotNull(saved.getId(), "Saved candle should have an ID");

        Optional<CandleEntity> found = candleRepository.findById(saved.getId());
        assertTrue(found.isPresent(), "Saved candle should be retrievable by ID");
        assertEquals(Instrument.MCL, found.get().getInstrument());
        assertEquals("10m", found.get().getTimeframe());
        assertEquals(now, found.get().getTimestamp());
        assertEquals(0, new BigDecimal("62.40").compareTo(found.get().getOpen()));
        assertEquals(0, new BigDecimal("62.80").compareTo(found.get().getHigh()));
        assertEquals(0, new BigDecimal("62.10").compareTo(found.get().getLow()));
        assertEquals(0, new BigDecimal("62.55").compareTo(found.get().getClose()));
        assertEquals(500, found.get().getVolume());
    }

    // -----------------------------------------------------------------------
    // findByInstrumentAndTimeframeOrderByTimestampDesc (Pageable)
    // -----------------------------------------------------------------------

    @Test
    void findTop500_returnsCandlesOrderedByTimestampDesc() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MINUTES);

        // Insert 5 candles for MCL/10m at different timestamps
        for (int i = 0; i < 5; i++) {
            Instant ts = base.minus(i * 10L, ChronoUnit.MINUTES);
            candleRepository.save(createCandle(
                    Instrument.MCL, "10m", ts,
                    62.00 + i * 0.10, 62.50 + i * 0.10,
                    61.80 + i * 0.10, 62.20 + i * 0.10,
                    100 + i * 50));
        }

        // Insert candles for a different instrument that should NOT appear
        candleRepository.save(createCandle(
                Instrument.MGC, "10m", base, 2040.00, 2045.00, 2035.00, 2042.00, 300));

        List<CandleEntity> result = candleRepository
                .findByInstrumentAndTimeframeOrderByTimestampDesc(Instrument.MCL, "10m", PageRequest.of(0, 500));

        assertEquals(5, result.size(), "Should return all 5 MCL/10m candles");

        // Verify descending timestamp order
        for (int i = 0; i < result.size() - 1; i++) {
            assertTrue(result.get(i).getTimestamp().isAfter(result.get(i + 1).getTimestamp())
                            || result.get(i).getTimestamp().equals(result.get(i + 1).getTimestamp()),
                    "Candles should be in descending timestamp order");
        }

        // Verify the most recent candle is first
        assertEquals(base, result.get(0).getTimestamp(),
                "First candle should have the most recent timestamp");
    }

    // -----------------------------------------------------------------------
    // findByInstrumentAndTimeframeAndTimestampGreaterThanEqual
    // -----------------------------------------------------------------------

    @Test
    void findByInstrumentAndTimeframeAndTimestamp_filtersCorrectly() {
        Instant base = Instant.now().truncatedTo(ChronoUnit.MINUTES);

        // Insert 5 candles for MCL/10m: at base, base-10m, base-20m, base-30m, base-40m
        for (int i = 0; i < 5; i++) {
            Instant ts = base.minus(i * 10L, ChronoUnit.MINUTES);
            candleRepository.save(createCandle(
                    Instrument.MCL, "10m", ts,
                    62.00, 62.50, 61.80, 62.20, 100));
        }

        // Insert candles for a different timeframe that should NOT appear
        candleRepository.save(createCandle(
                Instrument.MCL, "1h", base, 62.00, 63.00, 61.50, 62.50, 500));

        // Query for candles from base-20m onwards (should get 3: base-20m, base-10m, base)
        Instant fromTime = base.minus(20, ChronoUnit.MINUTES);
        List<CandleEntity> result = candleRepository
                .findByInstrumentAndTimeframeAndTimestampGreaterThanEqualOrderByTimestampAsc(
                        Instrument.MCL, "10m", fromTime);

        assertEquals(3, result.size(), "Should return 3 candles from base-20m onwards");

        // Verify ascending timestamp order
        for (int i = 0; i < result.size() - 1; i++) {
            assertTrue(result.get(i).getTimestamp().isBefore(result.get(i + 1).getTimestamp())
                            || result.get(i).getTimestamp().equals(result.get(i + 1).getTimestamp()),
                    "Candles should be in ascending timestamp order");
        }

        // All results should be MCL/10m
        assertTrue(result.stream().allMatch(c -> c.getInstrument() == Instrument.MCL),
                "All candles should be for MCL");
        assertTrue(result.stream().allMatch(c -> "10m".equals(c.getTimeframe())),
                "All candles should be for 10m timeframe");
    }
}
