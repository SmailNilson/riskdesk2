package com.riskdesk.presentation.controller;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for the cursor-paginated range read endpoint
 * {@code GET /api/candles/{instrument}/{timeframe}/range} on {@link CandleController}.
 *
 * <p>Lightweight Mockito style (no Spring context) — the controller forwards to
 * {@link CandleRepositoryPort#findCandlesBetweenPaged} and assembles the cursor.</p>
 */
class CandleControllerRangeTest {

    private final CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
    private final ActiveContractRegistry contractRegistry = mock(ActiveContractRegistry.class);
    private final CandleController controller = build();

    private CandleController build() {
        CandleController c = new CandleController(candlePort, contractRegistry);
        ReflectionTestUtils.setField(c, "rangeDefaultPageSize", 5000);
        ReflectionTestUtils.setField(c, "rangeMaxPageSize", 50000);
        return c;
    }

    @Test
    void range_returnsCandlesAndNullCursorWhenPageNotFull() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to   = Instant.parse("2026-03-02T00:00:00Z");
        when(candlePort.findCandlesBetweenPaged(eq(Instrument.MNQ), eq("1m"), eq(from), eq(to), eq(5000)))
            .thenReturn(List.of(candle("2026-03-01T00:00:00Z"), candle("2026-03-01T00:01:00Z")));

        ResponseEntity<Map<String, Object>> resp =
            controller.getCandlesRange("mnq", "1m", from.toString(), to.toString(), null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        Map<String, Object> body = resp.getBody();
        assertEquals(2, body.get("count"));
        assertNull(body.get("nextFrom"), "partial page → no further cursor");
        assertEquals(from.getEpochSecond(), body.get("from"));
        assertEquals(to.getEpochSecond(), body.get("to"));
    }

    @Test
    void range_emitsNextCursorWhenPageIsFull() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to   = Instant.parse("2026-03-02T00:00:00Z");
        ReflectionTestUtils.setField(controller, "rangeDefaultPageSize", 2);
        Candle last = candle("2026-03-01T00:01:00Z");
        when(candlePort.findCandlesBetweenPaged(eq(Instrument.MNQ), eq("1m"), eq(from), eq(to), eq(2)))
            .thenReturn(List.of(candle("2026-03-01T00:00:00Z"), last));

        ResponseEntity<Map<String, Object>> resp =
            controller.getCandlesRange("mnq", "1m", from.toString(), to.toString(), null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals(last.getTimestamp().getEpochSecond() + 1, resp.getBody().get("nextFrom"));
    }

    @Test
    void range_doesNotEmitCursorBeyondTo_evenOnFullPage() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to   = Instant.parse("2026-03-01T00:01:00Z");
        ReflectionTestUtils.setField(controller, "rangeDefaultPageSize", 2);
        // Full page whose last bar sits exactly on `to` — no further window remains.
        when(candlePort.findCandlesBetweenPaged(eq(Instrument.MNQ), eq("1m"), eq(from), eq(to), eq(2)))
            .thenReturn(List.of(candle("2026-03-01T00:00:00Z"), candle("2026-03-01T00:01:00Z")));

        ResponseEntity<Map<String, Object>> resp =
            controller.getCandlesRange("mnq", "1m", from.toString(), to.toString(), null);

        assertNull(resp.getBody().get("nextFrom"), "cursor must not point past 'to'");
    }

    @Test
    void range_clampsLimitToConfiguredMax() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to   = Instant.parse("2026-03-02T00:00:00Z");
        when(candlePort.findCandlesBetweenPaged(eq(Instrument.MNQ), eq("1m"), eq(from), eq(to), eq(50000)))
            .thenReturn(List.of());

        controller.getCandlesRange("mnq", "1m", from.toString(), to.toString(), 999999);

        verify(candlePort).findCandlesBetweenPaged(Instrument.MNQ, "1m", from, to, 50000);
    }

    @Test
    void range_acceptsEpochSecondsParams() {
        Instant from = Instant.parse("2026-03-01T00:00:00Z");
        Instant to   = Instant.parse("2026-03-02T00:00:00Z");
        when(candlePort.findCandlesBetweenPaged(eq(Instrument.MNQ), eq("1m"), eq(from), eq(to), eq(5000)))
            .thenReturn(List.of());

        ResponseEntity<Map<String, Object>> resp = controller.getCandlesRange(
            "mnq", "1m", String.valueOf(from.getEpochSecond()), String.valueOf(to.getEpochSecond()), null);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        verify(candlePort).findCandlesBetweenPaged(Instrument.MNQ, "1m", from, to, 5000);
    }

    @Test
    void range_rejectsUnknownInstrument() {
        ResponseEntity<Map<String, Object>> resp = controller.getCandlesRange(
            "XYZ", "1m", "2026-03-01T00:00:00Z", "2026-03-02T00:00:00Z", null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void range_rejectsInvertedWindow() {
        ResponseEntity<Map<String, Object>> resp = controller.getCandlesRange(
            "mnq", "1m", "2026-03-02T00:00:00Z", "2026-03-01T00:00:00Z", null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void range_rejectsUnparseableInstant() {
        ResponseEntity<Map<String, Object>> resp = controller.getCandlesRange(
            "mnq", "1m", "not-a-date", "2026-03-01T00:00:00Z", null);
        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    private static Candle candle(String ts) {
        BigDecimal p = new BigDecimal("20000.00");
        return new Candle(Instrument.MNQ, "1m", Instant.parse(ts), p, p, p, p, 10L);
    }
}
