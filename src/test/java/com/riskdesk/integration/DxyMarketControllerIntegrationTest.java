package com.riskdesk.integration;

import com.riskdesk.application.dto.DxyHealthComponentView;
import com.riskdesk.application.dto.DxyHealthView;
import com.riskdesk.application.service.DxyMarketService;
import com.riskdesk.domain.marketdata.model.DxySnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DxyMarketControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DxyMarketService dxyMarketService;

    @Test
    void latest_returnsSnapshotWhenAvailable() throws Exception {
        DxySnapshot snapshot = new DxySnapshot(
            Instant.parse("2026-04-01T10:00:00Z"),
            new BigDecimal("1.08110000"),
            new BigDecimal("149.22000000"),
            new BigDecimal("1.26220000"),
            new BigDecimal("1.35120000"),
            new BigDecimal("10.48050000"),
            new BigDecimal("0.90215000"),
            new BigDecimal("103.456789"),
            "IBKR_SYNTHETIC",
            true
        );
        when(dxyMarketService.supported()).thenReturn(true);
        when(dxyMarketService.latestResolvedSnapshot()).thenReturn(
            Optional.of(new DxyMarketService.ResolvedSnapshot(snapshot, "IBKR_SYNTHETIC")));
        when(dxyMarketService.findBaselineSnapshot(any())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/market/dxy/latest"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.source").value("IBKR_SYNTHETIC"))
            .andExpect(jsonPath("$.dxyValue").value(103.456789));
    }

    @Test
    void history_rejectsInvalidRange() throws Exception {
        when(dxyMarketService.supported()).thenReturn(true);
        mockMvc.perform(get("/api/market/dxy/history?from=2026-04-01T10:00:00Z&to=2026-04-01T10:00:00Z"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void latest_returnsServiceUnavailableWhenModeIsUnsupported() throws Exception {
        when(dxyMarketService.supported()).thenReturn(false);

        mockMvc.perform(get("/api/market/dxy/latest"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void history_returnsServiceUnavailableWhenModeIsUnsupported() throws Exception {
        when(dxyMarketService.supported()).thenReturn(false);

        mockMvc.perform(get("/api/market/dxy/history?from=2026-04-01T09:00:00Z&to=2026-04-01T10:00:00Z"))
            .andExpect(status().isServiceUnavailable());
    }

    @Test
    void health_returnsCurrentStatus() throws Exception {
        when(dxyMarketService.health()).thenReturn(new DxyHealthView(
            "DEGRADED",
            Instant.parse("2026-04-01T09:59:00Z"),
            "FALLBACK_DB",
            0L,
            List.of(new DxyHealthComponentView(
                "EURUSD",
                new BigDecimal("1.0810"),
                new BigDecimal("1.0812"),
                new BigDecimal("1.0811"),
                new BigDecimal("1.08110000"),
                "MID",
                Instant.parse("2026-04-01T10:00:00Z"),
                "VALID",
                null
            ))
        ));

        mockMvc.perform(get("/api/market/dxy/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("DEGRADED"))
            .andExpect(jsonPath("$.source").value("FALLBACK_DB"))
            .andExpect(jsonPath("$.components[0].pair").value("EURUSD"));
    }
}
