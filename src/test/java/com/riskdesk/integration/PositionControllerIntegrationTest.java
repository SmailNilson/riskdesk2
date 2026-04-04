package com.riskdesk.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

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
}
