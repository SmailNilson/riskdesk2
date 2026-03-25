package com.riskdesk.bdd.steps;

import com.riskdesk.presentation.dto.CreatePositionRequest;
import com.riskdesk.presentation.dto.ClosePositionRequest;
import com.riskdesk.domain.model.*;
import com.riskdesk.application.service.PositionService;
import com.riskdesk.domain.trading.port.PositionRepositoryPort;
import io.cucumber.java.Before;
import io.cucumber.java.en.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;

public class PositionSteps {

    @Autowired
    private PositionService positionService;

    @Autowired
    private PositionRepositoryPort positionRepository;

    private Position lastPosition;

    @Before
    public void cleanup() {
        positionRepository.deleteAll();
    }

    @When("I open a {word} position on {word} with {int} contract(s) at {double}")
    public void openPosition(String side, String inst, int qty, double price) {
        CreatePositionRequest req = new CreatePositionRequest(
                Instrument.valueOf(inst), Side.valueOf(side), qty,
                new BigDecimal(String.valueOf(price)), null, null, null);
        lastPosition = positionService.openPosition(req);
    }

    @Then("the position should be OPEN")
    public void positionShouldBeOpen() {
        assertNotNull(lastPosition);
        assertTrue(lastPosition.isOpen());
    }

    @Then("the position should be CLOSED")
    public void positionShouldBeClosed() {
        assertNotNull(lastPosition);
        assertFalse(lastPosition.isOpen());
    }

    @Then("the unrealized PnL should be {double}")
    public void unrealizedPnLShouldBe(double expected) {
        BigDecimal pnl = lastPosition.getUnrealizedPnL() != null
                ? lastPosition.getUnrealizedPnL()
                : BigDecimal.ZERO;
        assertEquals(expected, pnl.doubleValue(), 0.01);
    }

    @Then("the realized PnL should be {double}")
    public void realizedPnLShouldBe(double expected) {
        assertNotNull(lastPosition.getRealizedPnL());
        assertEquals(expected, lastPosition.getRealizedPnL().doubleValue(), 0.01);
    }

    @Given("an open {word} position on {word} at {double} with {int} contracts")
    public void givenOpenPosition(String side, String inst, double price, int qty) {
        CreatePositionRequest req = new CreatePositionRequest(
                Instrument.valueOf(inst), Side.valueOf(side), qty,
                new BigDecimal(String.valueOf(price)), null, null, null);
        lastPosition = positionService.openPosition(req);
    }

    @When("I close the position at {double}")
    public void closePosition(double exitPrice) {
        lastPosition = positionService.closePosition(lastPosition.getId(),
                new ClosePositionRequest(new BigDecimal(String.valueOf(exitPrice))));
    }

    @When("the market price updates to {double} for {word}")
    public void marketPriceUpdates(double price, String inst) {
        positionService.updateMarketPrice(Instrument.valueOf(inst),
                new BigDecimal(String.valueOf(price)));
        lastPosition = positionRepository.findById(lastPosition.getId()).orElseThrow();
    }
}
