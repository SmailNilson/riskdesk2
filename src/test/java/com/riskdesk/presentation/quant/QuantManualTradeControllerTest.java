package com.riskdesk.presentation.quant;

import com.riskdesk.application.dto.TradeExecutionView;
import com.riskdesk.application.quant.automation.QuantManualTradeService;
import com.riskdesk.application.quant.automation.QuantManualTradeService.ManualDirection;
import com.riskdesk.application.quant.automation.QuantManualTradeService.ManualEntryType;
import com.riskdesk.application.quant.automation.QuantManualTradeService.ManualTradeRequest;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QuantManualTradeControllerTest {

    private QuantManualTradeService service;
    private QuantManualTradeController controller;

    @BeforeEach
    void setUp() {
        service = mock(QuantManualTradeService.class);
        controller = new QuantManualTradeController(service);
    }

    @Test
    void place_returns_201_with_execution_view_on_success() {
        ManualTradeRequest body = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.LIMIT,
            new BigDecimal("20000"), new BigDecimal("19975"),
            new BigDecimal("20040"), null, 1
        );
        TradeExecutionRecord placed = newRec(7L, "BUY");
        when(service.place(eq(Instrument.MNQ), any(ManualTradeRequest.class), eq("operator"))).thenReturn(placed);

        ResponseEntity<TradeExecutionView> response = controller.place("mnq", body, "operator");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(7L);
        assertThat(response.getBody().action()).isEqualTo("BUY");
        assertThat(response.getBody().triggerSource()).isEqualTo(ExecutionTriggerSource.MANUAL_QUANT_PANEL);
    }

    @Test
    void place_returns_400_when_instrument_unknown() {
        ManualTradeRequest body = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.LIMIT,
            new BigDecimal("20000"), new BigDecimal("19975"),
            new BigDecimal("20040"), null, 1
        );
        assertThatThrownBy(() -> controller.place("BOGUS", body, "operator"))
            .isInstanceOf(ResponseStatusException.class)
            .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST);
    }

    @Test
    void place_returns_400_when_service_rejects_validation() {
        ManualTradeRequest body = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.LIMIT,
            new BigDecimal("20000"), null, new BigDecimal("20040"), null, 1
        );
        when(service.place(any(), any(), any())).thenThrow(new IllegalArgumentException("stopLoss is required"));

        assertThatThrownBy(() -> controller.place("MNQ", body, "operator"))
            .isInstanceOf(ResponseStatusException.class)
            .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
            .hasMessageContaining("stopLoss");
    }

    @Test
    void place_returns_409_when_ibkr_submission_fails() {
        ManualTradeRequest body = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.MARKET,
            null, new BigDecimal("19975"), new BigDecimal("20040"), null, 1
        );
        when(service.place(any(), any(), any())).thenThrow(new IllegalStateException("IBKR entry submission failed: timeout"));

        assertThatThrownBy(() -> controller.place("MNQ", body, "operator"))
            .isInstanceOf(ResponseStatusException.class)
            .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.CONFLICT)
            .hasMessageContaining("IBKR");
    }

    @Test
    void place_rejects_synthetic_instrument() {
        ManualTradeRequest body = new ManualTradeRequest(
            ManualDirection.LONG, ManualEntryType.LIMIT,
            new BigDecimal("100"), new BigDecimal("99"),
            new BigDecimal("101"), null, 1
        );
        assertThatThrownBy(() -> controller.place("DXY", body, "operator"))
            .isInstanceOf(ResponseStatusException.class)
            .matches(e -> ((ResponseStatusException) e).getStatusCode() == HttpStatus.BAD_REQUEST)
            .hasMessageContaining("synthetic");
    }

    private static TradeExecutionRecord newRec(long id, String action) {
        TradeExecutionRecord rec = new TradeExecutionRecord();
        rec.setId(id);
        rec.setInstrument("MNQ");
        rec.setAction(action);
        rec.setStatus(ExecutionStatus.PENDING_ENTRY_SUBMISSION);
        rec.setTriggerSource(ExecutionTriggerSource.MANUAL_QUANT_PANEL);
        rec.setCreatedAt(Instant.now());
        return rec;
    }
}
