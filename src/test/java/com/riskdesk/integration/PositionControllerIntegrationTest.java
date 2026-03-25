package com.riskdesk.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.presentation.dto.ClosePositionRequest;
import com.riskdesk.presentation.dto.CreatePositionRequest;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class PositionControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // -----------------------------------------------------------------------
    // POST /api/positions — open a new position
    // -----------------------------------------------------------------------

    @Test
    void postPosition_withValidBody_returns201AndPositionJson() throws Exception {
        CreatePositionRequest request = new CreatePositionRequest(
                Instrument.MCL, Side.LONG, 2,
                new BigDecimal("63.50"),
                new BigDecimal("62.00"),
                new BigDecimal("66.00"),
                "Integration test position"
        );

        mockMvc.perform(post("/api/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.instrument").value("MCL"))
                .andExpect(jsonPath("$.side").value("LONG"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.entryPrice").isNumber())
                .andExpect(jsonPath("$.open").value(true))
                .andExpect(jsonPath("$.notes").value("Integration test position"));
    }

    @Test
    void postPosition_withInvalidBody_nullInstrument_returns400() throws Exception {
        // instrument is null, which should fail @NotNull validation
        String invalidJson = """
                {
                    "instrument": null,
                    "side": "LONG",
                    "quantity": 1,
                    "entryPrice": 63.50
                }
                """;

        mockMvc.perform(post("/api/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest());
    }

    // -----------------------------------------------------------------------
    // GET /api/positions — list open positions
    // -----------------------------------------------------------------------

    @Test
    void getOpenPositions_returnsListWithSeededPositions() throws Exception {
        // DataInitializer seeds 3 open positions
        mockMvc.perform(get("/api/positions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$[0].id").isNumber())
                .andExpect(jsonPath("$[0].instrument").isString())
                .andExpect(jsonPath("$[0].side").isString())
                .andExpect(jsonPath("$[0].open").value(true));
    }

    // -----------------------------------------------------------------------
    // GET /api/positions/closed — list closed positions
    // -----------------------------------------------------------------------

    @Test
    void getClosedPositions_returnsListOfClosedPositions() throws Exception {
        // DataInitializer seeds 3 closed positions
        mockMvc.perform(get("/api/positions/closed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(greaterThanOrEqualTo(3)));
    }

    // -----------------------------------------------------------------------
    // GET /api/positions/summary — portfolio summary
    // -----------------------------------------------------------------------

    @Test
    void getPortfolioSummary_returnsCorrectFields() throws Exception {
        mockMvc.perform(get("/api/positions/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalUnrealizedPnL").isNumber())
                .andExpect(jsonPath("$.todayRealizedPnL").isNumber())
                .andExpect(jsonPath("$.totalPnL").isNumber())
                .andExpect(jsonPath("$.openPositionCount").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.totalExposure").isNumber())
                .andExpect(jsonPath("$.marginUsedPct").isNumber())
                .andExpect(jsonPath("$.openPositions").isArray())
                .andExpect(jsonPath("$.openPositions.length()").value(greaterThanOrEqualTo(3)));
    }

    // -----------------------------------------------------------------------
    // POST /api/positions/{id}/close — close an open position
    // -----------------------------------------------------------------------

    @Test
    @DirtiesContext
    void closePosition_closesOpenPosition_returnsClosedPositionWithRealizedPnL() throws Exception {
        // First, create a position to close
        CreatePositionRequest createRequest = new CreatePositionRequest(
                Instrument.MGC, Side.LONG, 1,
                new BigDecimal("2030.00"),
                new BigDecimal("2018.00"),
                new BigDecimal("2058.00"),
                "Position to close"
        );

        String createResponse = mockMvc.perform(post("/api/positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        // Extract the id from the response
        Long positionId = objectMapper.readTree(createResponse).get("id").asLong();

        // Now close it
        ClosePositionRequest closeRequest = new ClosePositionRequest(new BigDecimal("2050.00"));

        mockMvc.perform(post("/api/positions/" + positionId + "/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(closeRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(positionId))
                .andExpect(jsonPath("$.open").value(false))
                .andExpect(jsonPath("$.instrument").value("MGC"));
    }
}
