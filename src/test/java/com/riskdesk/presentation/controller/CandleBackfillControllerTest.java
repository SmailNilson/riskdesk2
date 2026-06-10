package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.HistoricalDataService;
import com.riskdesk.application.service.HistoricalDataService.BackfillJob;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link CandleBackfillController} — the admin range-backfill trigger + status endpoints.
 * Verifies delegation, instant parsing, and HTTP status mapping. Lightweight Mockito style.
 */
class CandleBackfillControllerTest {

    private final HistoricalDataService service = mock(HistoricalDataService.class);
    private final CandleBackfillController controller = new CandleBackfillController(service);

    private static final Instant FROM = Instant.parse("2026-03-01T00:00:00Z");
    private static final Instant TO   = Instant.parse("2026-03-10T00:00:00Z");

    private static BackfillJob job(String state) {
        return new BackfillJob(Instrument.MNQ, "1m", state, FROM, TO, 0, 0, 0, 1L, null, state + " msg");
    }

    @Test
    void backfill_delegatesWithParsedInstants_andReturns202ForRunning() {
        when(service.startBackfillRange(eq(Instrument.MNQ), eq("1m"), eq(FROM), eq(TO), eq(true), eq(false), eq(false)))
            .thenReturn(job("RUNNING"));

        ResponseEntity<Map<String, Object>> resp =
            controller.backfill("mnq", "1m", FROM.toString(), TO.toString(), true, false, false);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        assertEquals("RUNNING", resp.getBody().get("state"));
        assertEquals(FROM.getEpochSecond(), resp.getBody().get("from"));
        verify(service).startBackfillRange(Instrument.MNQ, "1m", FROM, TO, true, false, false);
    }

    @Test
    void backfill_returns200ForSyncDone() {
        when(service.startBackfillRange(eq(Instrument.MNQ), eq("1m"), eq(FROM), eq(TO), eq(false), eq(false), eq(false)))
            .thenReturn(job("DONE"));

        ResponseEntity<Map<String, Object>> resp =
            controller.backfill("mnq", "1m", FROM.toString(), TO.toString(), false, false, false);

        assertEquals(HttpStatus.OK, resp.getStatusCode());
    }

    @Test
    void backfill_mapsRejectedTo400() {
        when(service.startBackfillRange(eq(Instrument.MNQ), eq("1m"), eq(FROM), eq(TO), eq(true), eq(false), eq(false)))
            .thenReturn(job("REJECTED"));

        ResponseEntity<Map<String, Object>> resp =
            controller.backfill("mnq", "1m", FROM.toString(), TO.toString(), true, false, false);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
    }

    @Test
    void backfill_mapsDisabledTo409() {
        when(service.startBackfillRange(eq(Instrument.MNQ), eq("1m"), eq(FROM), eq(TO), eq(true), eq(false), eq(false)))
            .thenReturn(job("DISABLED"));

        ResponseEntity<Map<String, Object>> resp =
            controller.backfill("mnq", "1m", FROM.toString(), TO.toString(), true, false, false);

        assertEquals(HttpStatus.CONFLICT, resp.getStatusCode());
    }

    @Test
    void backfill_rejectsUnknownInstrument_withoutTouchingService() {
        ResponseEntity<Map<String, Object>> resp =
            controller.backfill("XYZ", "1m", FROM.toString(), TO.toString(), true, false, false);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void backfill_rejectsUnparseableInstant_withoutTouchingService() {
        ResponseEntity<Map<String, Object>> resp =
            controller.backfill("mnq", "1m", "garbage", TO.toString(), true, false, false);

        assertEquals(HttpStatus.BAD_REQUEST, resp.getStatusCode());
        verifyNoInteractions(service);
    }

    @Test
    void backfill_passesContinuousAndReplaceThrough() {
        when(service.startBackfillRange(eq(Instrument.MNQ), eq("1m"), eq(FROM), eq(TO), eq(true), eq(true), eq(true)))
            .thenReturn(job("RUNNING"));

        ResponseEntity<Map<String, Object>> resp =
            controller.backfill("mnq", "1m", FROM.toString(), TO.toString(), true, true, true);

        assertEquals(HttpStatus.ACCEPTED, resp.getStatusCode());
        verify(service).startBackfillRange(Instrument.MNQ, "1m", FROM, TO, true, true, true);
    }

    @Test
    void status_returns200WhenJobExists() {
        when(service.backfillStatus(Instrument.MNQ, "1m")).thenReturn(Optional.of(job("DONE")));

        ResponseEntity<Map<String, Object>> resp = controller.status("mnq", "1m");

        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("DONE", resp.getBody().get("state"));
    }

    @Test
    void status_returns404WhenNoJob() {
        when(service.backfillStatus(Instrument.MNQ, "1m")).thenReturn(Optional.empty());

        ResponseEntity<Map<String, Object>> resp = controller.status("mnq", "1m");

        assertEquals(HttpStatus.NOT_FOUND, resp.getStatusCode());
    }
}
