package com.riskdesk.bdd.steps;

import com.riskdesk.domain.alert.model.IndicatorAlertSnapshot;
import com.riskdesk.domain.alert.model.*;
import com.riskdesk.domain.alert.service.IndicatorAlertEvaluator;
import com.riskdesk.domain.model.Instrument;
import io.cucumber.java.en.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IndicatorSignalSteps {
    private static final Instant CLOSED_CANDLE = Instant.parse("2026-03-28T16:00:00Z");

    private final IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator();
    private IndicatorAlertSnapshot snapshot;
    private Instrument instrument;
    private String timeframe;
    private List<Alert> alerts = new ArrayList<>();

    @Given("an indicator snapshot with EMA crossover {word} for {word} on {word}")
    public void indicatorSnapshotWithEmaCrossover(String crossover, String inst, String tf) {
        instrument = Instrument.valueOf(inst);
        timeframe = tf;
        snapshot = snapshot(
                crossover,
                null, null,
                null,
                null, null, null,
                null, null, null
        );
    }

    @Given("an indicator snapshot with RSI signal {word} and RSI value {double} for {word} on {word}")
    public void indicatorSnapshotWithRsiSignal(String signal, double rsiValue, String inst, String tf) {
        instrument = Instrument.valueOf(inst);
        timeframe = tf;
        snapshot = snapshot(
                null,
                new BigDecimal(String.valueOf(rsiValue)), signal,
                null,
                null, null, null,
                null, null, null
        );
    }

    @Given("an indicator snapshot with MACD crossover {word} for {word} on {word}")
    public void indicatorSnapshotWithMacdCrossover(String crossover, String inst, String tf) {
        instrument = Instrument.valueOf(inst);
        timeframe = tf;
        snapshot = snapshot(
                null,
                null, null,
                crossover,
                null, null, null,
                null, null, null
        );
    }

    @Given("an indicator snapshot with break type {word} for {word} on {word}")
    public void indicatorSnapshotWithBreakType(String breakType, String inst, String tf) {
        instrument = Instrument.valueOf(inst);
        timeframe = tf;
        snapshot = snapshot(
                null,
                null, null,
                null,
                breakType, breakType, null,
                null, null, null
        );
    }

    private IndicatorAlertSnapshot snapshot(
            String emaCrossover,
            BigDecimal rsi,
            String rsiSignal,
            String macdCrossover,
            String lastBreakType,
            String lastInternalBreakType,
            String lastSwingBreakType,
            BigDecimal wtWt1,
            String wtCrossover,
            String wtSignal
    ) {
        return new IndicatorAlertSnapshot(
                emaCrossover,
                rsi,
                rsiSignal,
                macdCrossover,
                lastBreakType,
                lastInternalBreakType,
                lastSwingBreakType,
                wtWt1,
                wtCrossover,
                wtSignal,
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                CLOSED_CANDLE,
                null, null, null, null, Collections.emptyList(), Collections.emptyList(),
                null, null, null, null, null, null, null, null,
                null, null
        );
    }

    @When("indicator alerts are evaluated")
    public void indicatorAlertsAreEvaluated() {
        alerts = evaluator.evaluate(instrument, timeframe, snapshot);
    }

    @Then("an {word} alert should be generated for {word} with message containing {string}")
    public void alertShouldBeGeneratedForWithMessage(String severity, String category, String substring) {
        AlertSeverity expectedSeverity = AlertSeverity.valueOf(severity);
        AlertCategory expectedCategory = AlertCategory.valueOf(category);

        boolean found = alerts.stream()
                .anyMatch(a -> a.severity() == expectedSeverity
                        && a.category() == expectedCategory
                        && a.message().contains(substring));
        assertTrue(found,
                "Expected " + severity + " " + category + " alert containing '" + substring
                        + "' but got: " + alerts);
    }

    @Then("a {word} alert should be generated for {word} with message containing {string}")
    public void warningAlertShouldBeGeneratedForWithMessage(String severity, String category, String substring) {
        AlertSeverity expectedSeverity = AlertSeverity.valueOf(severity);
        AlertCategory expectedCategory = AlertCategory.valueOf(category);

        boolean found = alerts.stream()
                .anyMatch(a -> a.severity() == expectedSeverity
                        && a.category() == expectedCategory
                        && a.message().contains(substring));
        assertTrue(found,
                "Expected " + severity + " " + category + " alert containing '" + substring
                        + "' but got: " + alerts);
    }
}
