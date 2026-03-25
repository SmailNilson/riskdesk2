package com.riskdesk.integration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IndicatorControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // -----------------------------------------------------------------------
    // GET /api/indicators/MCL/10m — valid instrument and timeframe
    // -----------------------------------------------------------------------

    @Test
    void getIndicators_validInstrument_returns200WithIndicatorFields() throws Exception {
        // DataInitializer seeds 150 candles for MCL/10m, so indicators should compute
        mockMvc.perform(get("/api/indicators/MCL/10m"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrument").value("MCL"))
                .andExpect(jsonPath("$.timeframe").value("10m"))
                .andExpect(jsonPath("$.ema9").value(notNullValue()))
                .andExpect(jsonPath("$.rsi").value(notNullValue()))
                .andExpect(jsonPath("$.macdLine").value(notNullValue()))
                .andExpect(jsonPath("$.vwap").value(notNullValue()))
                .andExpect(jsonPath("$.bbUpper").value(notNullValue()))
                .andExpect(jsonPath("$.marketStructureTrend").value(notNullValue()));
    }

    @Test
    void getIndicators_validInstrument_responseContainsAllMajorSections() throws Exception {
        mockMvc.perform(get("/api/indicators/MGC/1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrument").value("MGC"))
                .andExpect(jsonPath("$.timeframe").value("1h"))
                // EMAs
                .andExpect(jsonPath("$.ema9").exists())
                .andExpect(jsonPath("$.ema50").exists())
                // RSI
                .andExpect(jsonPath("$.rsi").exists())
                .andExpect(jsonPath("$.rsiSignal").exists())
                // MACD
                .andExpect(jsonPath("$.macdLine").exists())
                .andExpect(jsonPath("$.macdSignal").exists())
                .andExpect(jsonPath("$.macdHistogram").exists())
                // Bollinger Bands
                .andExpect(jsonPath("$.bbMiddle").exists())
                .andExpect(jsonPath("$.bbUpper").exists())
                .andExpect(jsonPath("$.bbLower").exists())
                // VWAP
                .andExpect(jsonPath("$.vwap").exists())
                // SMC
                .andExpect(jsonPath("$.marketStructureTrend").exists())
                .andExpect(jsonPath("$.activeOrderBlocks").isArray());
    }

    @Test
    void getIndicatorSeries_validInstrument_returnsChartReadySeries() throws Exception {
        mockMvc.perform(get("/api/indicators/MCL/10m/series?limit=500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instrument").value("MCL"))
                .andExpect(jsonPath("$.timeframe").value("10m"))
                .andExpect(jsonPath("$.ema9").isArray())
                .andExpect(jsonPath("$.ema9[0].time").value(notNullValue()))
                .andExpect(jsonPath("$.ema9[0].value").value(notNullValue()))
                .andExpect(jsonPath("$.bollingerBands").isArray())
                .andExpect(jsonPath("$.bollingerBands[0].upper").value(notNullValue()))
                .andExpect(jsonPath("$.bollingerBands[0].lower").value(notNullValue()))
                .andExpect(jsonPath("$.waveTrend").isArray())
                .andExpect(jsonPath("$.waveTrend[0].wt1").value(notNullValue()))
                .andExpect(jsonPath("$.waveTrend[0].wt2").value(notNullValue()))
                .andExpect(jsonPath("$.waveTrend[0].diff").value(notNullValue()));
    }

    // -----------------------------------------------------------------------
    // GET /api/indicators/INVALID/10m — invalid instrument returns 400
    // -----------------------------------------------------------------------

    @Test
    void getIndicators_invalidInstrument_returns400() throws Exception {
        // "INVALID" is not a valid Instrument enum value, so Spring's
        // MethodArgumentTypeMismatchException will produce a 400 error
        mockMvc.perform(get("/api/indicators/INVALID/10m"))
                .andExpect(status().isBadRequest());
    }
}
