package com.riskdesk.bdd.steps;

import com.riskdesk.presentation.dto.CreatePositionRequest;
import com.riskdesk.presentation.dto.PortfolioSummary;
import com.riskdesk.domain.model.*;
import com.riskdesk.application.service.PositionService;
import io.cucumber.datatable.DataTable;
import io.cucumber.java.en.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PortfolioSteps {

    @Autowired
    private PositionService positionService;

    private PortfolioSummary lastSummary;

    @Given("these positions are open:")
    public void thesePositionsAreOpen(DataTable dataTable) {
        // Cleanup is handled by PositionSteps @Before
        List<Map<String, String>> rows = dataTable.asMaps(String.class, String.class);
        for (Map<String, String> row : rows) {
            CreatePositionRequest req = new CreatePositionRequest(
                    Instrument.valueOf(row.get("instrument")),
                    Side.valueOf(row.get("side")),
                    Integer.parseInt(row.get("quantity")),
                    new BigDecimal(row.get("entryPrice")),
                    null, null, null);
            positionService.openPosition(req);
        }
    }

    @When("I request the portfolio summary via API")
    public void requestPortfolioSummary() {
        lastSummary = positionService.getPortfolioSummary();
    }

    @When("I request the portfolio summary via API with no positions")
    public void requestPortfolioSummaryWithNoPositions() {
        // Positions are already cleaned by @Before in PositionSteps
        lastSummary = positionService.getPortfolioSummary();
    }

    @Then("the response should have {int} open positions")
    public void responseShouldHaveOpenPositions(int count) {
        assertNotNull(lastSummary);
        assertEquals(count, lastSummary.openPositionCount());
    }

    @Then("the total exposure should be greater than {int}")
    public void totalExposureShouldBeGreaterThan(int value) {
        assertNotNull(lastSummary);
        assertTrue(lastSummary.totalExposure().compareTo(BigDecimal.valueOf(value)) > 0,
                "Expected total exposure > " + value + " but was " + lastSummary.totalExposure());
    }
}
