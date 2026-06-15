package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.PortfolioSummary;
import com.riskdesk.application.service.PositionService;
import com.riskdesk.domain.engine.strategy.model.PortfolioState;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The AGENTS panel block reason is formatted from the drawdown this builder
 * (and its twin in AgentOrchestratorService) feeds to the risk gates. A small
 * dollar loss on a micro-contract account must come out as a fraction of a
 * percent of equity — not 83.7% of a near-zero exposure denominator.
 */
class PortfolioStateBuilderDrawdownTest {

    private final PositionService positionService = mock(PositionService.class);
    private final PortfolioStateBuilder builder = new PortfolioStateBuilder(positionService);

    @Test
    @DisplayName("Small unrealized loss maps to a sub-3% drawdown, below the kill switch")
    void smallLossStaysBelowKillSwitch() {
        when(positionService.getPortfolioSummary()).thenReturn(new PortfolioSummary(
            new BigDecimal("-167.40"), BigDecimal.ZERO, new BigDecimal("-167.40"),
            1, new BigDecimal("200.00"), new BigDecimal("8.0"),
            new BigDecimal("25000"), List.of()));

        PortfolioState state = builder.build(Instrument.MNQ);

        assertThat(state.isKnown()).isTrue();
        assertThat(state.dailyDrawdownPct()).isCloseTo(0.6696, within(0.0001));
        assertThat(state.dailyDrawdownPct()).isLessThan(3.0);
    }

    @Test
    @DisplayName("Genuine >3% day loss still trips the gate input")
    void genuineLossStillBreaches() {
        when(positionService.getPortfolioSummary()).thenReturn(new PortfolioSummary(
            new BigDecimal("-400"), new BigDecimal("-500"), new BigDecimal("-900"),
            1, new BigDecimal("84000"), new BigDecimal("8.0"),
            new BigDecimal("25000"), List.of()));

        PortfolioState state = builder.build(Instrument.MNQ);

        assertThat(state.dailyDrawdownPct()).isCloseTo(3.6, within(0.0001));
        assertThat(state.dailyDrawdownPct()).isGreaterThan(3.0);
    }
}
