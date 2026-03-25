package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.HistoricalTradesDTO;
import com.riskdesk.domain.analysis.port.MentorAuditRepositoryPort;
import com.riskdesk.domain.model.MentorAudit;
import com.riskdesk.infrastructure.config.HistoricalTradeImportProperties;
import com.riskdesk.infrastructure.config.MentorProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HistoricalTradeImporterServiceTest {

    @Mock
    private MentorAuditRepositoryPort mentorAuditRepository;

    @Mock
    private MentorMemoryService mentorMemoryService;

    @Mock
    private GeminiEmbeddingClient embeddingClient;

    private HistoricalTradeImportProperties properties;
    private MentorProperties mentorProperties;
    private HistoricalTradeImporterService service;

    @BeforeEach
    void setUp() {
        properties = new HistoricalTradeImportProperties();
        properties.setEmbeddingModel("gemini-embedding-001");
        mentorProperties = new MentorProperties();
        mentorProperties.setEmbeddingsModel("gemini-embedding-001");
        service = new HistoricalTradeImporterService(
            new ObjectMapper(),
            mentorAuditRepository,
            mentorMemoryService,
            embeddingClient,
            properties,
            mentorProperties
        );
    }

    @Test
    void buildTextForEmbedding_createsSemanticSummary() throws Exception {
        HistoricalTradesDTO payload = new ObjectMapper().readValue("""
            {
              "instrument": "MGC",
              "trades": [
                {
                  "trade_id": "MGC_001_TRAPPED_BREAKOUT",
                  "instrument": "MGC",
                  "symbol_detected": "MGC1!",
                  "timestamp": "2026-01-21 09:13:17",
                  "session": "US Open",
                  "timeframe": "M5",
                  "direction": "Short",
                  "entry_price": 4846.9,
                  "stop_loss": null,
                  "take_profit": null,
                  "market_order": true,
                  "analysis_mode": "Review",
                  "ai_verdict": "Bad Trade",
                  "trade_quality": "Poor",
                  "confidence_score": 100,
                  "risk_reward": null,
                  "market_structure": {
                    "trend_focus": "Bullish",
                    "last_event": "Resistance Breakout",
                    "last_event_price": 4845.0,
                    "notes": "Price had just reclaimed upper bounds."
                  },
                  "reasons": [
                    "Counter-trend shorting on a new High of the Day.",
                    "Ignored risk-off flow."
                  ],
                  "outcome_if_known": "Loser",
                  "has_screenshot": false,
                  "raw_context_excerpt": "Shorted a breakout in a highly bullish environment.",
                  "dedupe_key": "MGC_20260121_0913_S",
                  "notes": "Loss of -157.74$ locked in quickly."
                }
              ]
            }
            """, HistoricalTradesDTO.class);

        String text = service.buildTextForEmbedding(payload.trades().get(0));

        assertThat(text)
            .contains("Trade: MGC Short on M5")
            .contains("Execution context: MGC1! | US Open | Review")
            .contains("Verdict: Bad Trade (Poor)")
            .contains("Confidence: 100/100")
            .contains("Structure: Bullish - Resistance Breakout")
            .contains("Structure trigger price: 4845.0000")
            .contains("Context: Shorted a breakout in a highly bullish environment.")
            .contains("Reasons: Counter-trend shorting on a new High of the Day. Ignored risk-off flow.")
            .contains("Outcome: Loser.")
            .contains("Dedupe key: MGC_20260121_0913_S.");
    }

    @Test
    void importTrades_persistsAndIndexesNewTrades_onlyOnce() throws Exception {
        HistoricalTradesDTO payload = new ObjectMapper().readValue("""
            {
              "instrument": "MGC",
              "trades": [
                {
                  "trade_id": "MGC_001_TRAPPED_BREAKOUT",
                  "instrument": "MGC",
                  "symbol_detected": "MGC1!",
                  "timestamp": "2026-01-21 09:13:17",
                  "session": "US Open",
                  "timeframe": "M5",
                  "direction": "Short",
                  "entry_price": 4846.9,
                  "stop_loss": null,
                  "take_profit": null,
                  "market_order": true,
                  "analysis_mode": "Review",
                  "ai_verdict": "Bad Trade",
                  "trade_quality": "Poor",
                  "confidence_score": 100,
                  "risk_reward": null,
                  "market_structure": {
                    "trend_focus": "Bullish",
                    "last_event": "Resistance Breakout",
                    "last_event_price": 4845.0,
                    "notes": "Price had just reclaimed upper bounds."
                  },
                  "reasons": [
                    "Counter-trend shorting on a new High of the Day."
                  ],
                  "outcome_if_known": "Loser",
                  "has_screenshot": false,
                  "raw_context_excerpt": "Shorted a breakout in a highly bullish environment.",
                  "dedupe_key": "MGC_20260121_0913_S",
                  "notes": "Loss of -157.74$ locked in quickly."
                }
              ]
            }
            """, HistoricalTradesDTO.class);

        when(mentorAuditRepository.findBySourceRef("historical-trade:MGC_20260121_0913_S"))
            .thenReturn(Optional.empty());
        when(embeddingClient.embed(anyString(), anyString())).thenReturn(List.of(0.1, 0.2, 0.3));
        when(mentorAuditRepository.save(any())).thenAnswer(invocation -> {
            MentorAudit audit = invocation.getArgument(0);
            audit.setId(44L);
            return audit;
        });

        HistoricalTradeImporterService.ImportSummary summary = service.importTrades(payload);

        assertThat(summary.imported()).isEqualTo(1);
        assertThat(summary.skipped()).isZero();
        assertThat(summary.failed()).isZero();

        ArgumentCaptor<MentorAudit> auditCaptor = ArgumentCaptor.forClass(MentorAudit.class);
        verify(mentorAuditRepository).save(auditCaptor.capture());
        verify(mentorMemoryService).indexAudit(any(MentorAudit.class), anyList(), anyString());

        MentorAudit savedAudit = auditCaptor.getValue();
        assertThat(savedAudit.getSourceRef()).isEqualTo("historical-trade:MGC_20260121_0913_S");
        assertThat(savedAudit.getInstrument()).isEqualTo("MGC");
        assertThat(savedAudit.getAction()).isEqualTo("SHORT");
        assertThat(savedAudit.getVerdict()).isEqualTo("Bad Trade");
        assertThat(savedAudit.getModel()).isEqualTo("historical-trade-import");
        assertThat(savedAudit.getSemanticText()).contains("Resistance Breakout");

        when(mentorAuditRepository.findBySourceRef("historical-trade:MGC_20260121_0913_S"))
            .thenReturn(Optional.of(new MentorAudit()));

        HistoricalTradeImporterService.ImportSummary secondRun = service.importTrades(payload);

        assertThat(secondRun.imported()).isZero();
        assertThat(secondRun.skipped()).isEqualTo(1);
        verify(mentorAuditRepository, never()).save(argThat(audit -> "historical-trade:MGC_20260121_0913_S".equals(audit.getSourceRef()) && audit.getId() == null));
    }
}
