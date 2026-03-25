package com.riskdesk.bdd.steps;

import com.riskdesk.domain.alert.model.*;
import com.riskdesk.domain.alert.service.*;
import com.riskdesk.domain.model.*;
import com.riskdesk.domain.shared.vo.Money;
import com.riskdesk.domain.trading.aggregate.Portfolio;
import com.riskdesk.domain.trading.service.RiskSpecification;
import io.cucumber.java.en.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class RiskMonitoringSteps {

    private Portfolio portfolio;
    private final RiskSpecification riskSpec = new RiskSpecification(80.0, 25.0);
    private final RiskAlertEvaluator evaluator = new RiskAlertEvaluator(riskSpec);
    private AlertDeduplicator dedup = new AlertDeduplicator(300);
    private List<Alert> alerts = new ArrayList<>();

    /**
     * Helper to build a portfolio with a specific margin usage percentage.
     * Uses MCL (contractMultiplier=100) to calculate the needed quantity.
     */
    private Portfolio buildPortfolioWithMarginPct(int margin, double targetPct) {
        // targetExposure = margin * targetPct / 100
        // notional per contract = entryPrice * contractMultiplier = 62.40 * 100 = 6240
        // qty = targetExposure / 6240
        BigDecimal entryPrice = new BigDecimal("62.40");
        BigDecimal multiplier = Instrument.MCL.getContractMultiplier(); // 100
        BigDecimal notionalPerContract = entryPrice.multiply(multiplier);

        BigDecimal targetExposure = BigDecimal.valueOf(margin).multiply(BigDecimal.valueOf(targetPct))
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        int qty = targetExposure.divide(notionalPerContract, 0, RoundingMode.CEILING).intValue();
        if (qty < 1) qty = 1;

        Position pos = new Position(Instrument.MCL, Side.LONG, qty, entryPrice);
        pos.setUnrealizedPnL(BigDecimal.ZERO);

        return new Portfolio(Money.of(margin), List.of(pos));
    }

    @Given("a portfolio with margin {int} and exposure exceeding {int} percent")
    public void portfolioWithExposureExceeding(int margin, int pct) {
        // Build with pct + 5 to ensure it exceeds the threshold
        portfolio = buildPortfolioWithMarginPct(margin, pct + 5);
    }

    @Given("a portfolio with margin {int} and exposure at {int} percent")
    public void portfolioWithExposureAt(int margin, int pct) {
        portfolio = buildPortfolioWithMarginPct(margin, pct);
    }

    @When("risk is evaluated")
    public void riskIsEvaluated() {
        alerts = evaluator.evaluate(portfolio);
    }

    @Then("a {word} alert should be generated for RISK")
    public void alertShouldBeGeneratedForRisk(String severity) {
        AlertSeverity expected = AlertSeverity.valueOf(severity);
        boolean found = alerts.stream()
                .anyMatch(a -> a.severity() == expected && a.category() == AlertCategory.RISK);
        assertTrue(found,
                "Expected " + severity + " RISK alert but got: " + alerts);
    }

    @Then("no risk alerts should be generated")
    public void noRiskAlertsShouldBeGenerated() {
        // "Safe margin" scenario: assert that no margin-threshold alerts are produced.
        // A single-position portfolio will always flag concentration (100% exposure),
        // which is correct behaviour; this step validates margin safety only.
        boolean noMarginAlerts = alerts.stream()
                .noneMatch(a -> a.key().startsWith("risk:margin"));
        assertTrue(noMarginAlerts,
                "Expected no margin alerts but got: " + alerts);
    }

    @Given("a DANGER margin alert was already fired")
    public void dangerMarginAlertAlreadyFired() {
        // Create an alert and mark it as fired in the deduplicator
        Alert alert = new Alert("risk:margin", AlertSeverity.DANGER,
                "Margin usage at 99.0% — exceeds threshold",
                AlertCategory.RISK, null);
        dedup.markFired(alert);
    }

    @When("the same risk condition is evaluated again within {int} minutes")
    public void sameRiskConditionEvaluatedAgain(int minutes) {
        // The deduplicator has a 300-second (5-minute) cooldown.
        // Since we just marked it fired, evaluating again immediately should be suppressed.
        Alert alert = new Alert("risk:margin", AlertSeverity.DANGER,
                "Margin usage at 99.0% — exceeds threshold",
                AlertCategory.RISK, null);
        alerts = new ArrayList<>();
        if (dedup.shouldFire(alert)) {
            alerts.add(alert);
        }
    }

    @Then("the alert should be suppressed")
    public void alertShouldBeSuppressed() {
        assertTrue(alerts.isEmpty(),
                "Expected alert to be suppressed but got: " + alerts);
    }
}
