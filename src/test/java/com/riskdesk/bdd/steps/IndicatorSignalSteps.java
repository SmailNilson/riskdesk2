package com.riskdesk.bdd.steps;

import com.riskdesk.domain.alert.model.*;
import com.riskdesk.domain.alert.service.IndicatorAlertEvaluator;
import com.riskdesk.presentation.dto.IndicatorSnapshot;
import com.riskdesk.domain.model.Instrument;
import io.cucumber.java.en.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class IndicatorSignalSteps {

    private final IndicatorAlertEvaluator evaluator = new IndicatorAlertEvaluator();
    private IndicatorSnapshot snapshot;
    private Instrument instrument;
    private String timeframe;
    private List<Alert> alerts = new ArrayList<>();

    @Given("an indicator snapshot with EMA crossover {word} for {word} on {word}")
    public void indicatorSnapshotWithEmaCrossover(String crossover, String inst, String tf) {
        instrument = Instrument.valueOf(inst);
        timeframe = tf;
        snapshot = new IndicatorSnapshot(
                inst, tf,
                null, null, null, crossover,   // EMA fields: ema9, ema50, ema200, emaCrossover
                null, null,                      // RSI fields: rsi, rsiSignal
                null, null, null, null,          // MACD fields: macdLine, macdSignal, macdHistogram, macdCrossover
                null, false,                     // Supertrend fields: supertrendValue, supertrendBullish
                null, null, null,                // VWAP fields: vwap, vwapUpperBand, vwapLowerBand
                null, null,                      // Chaikin fields: chaikinOscillator, cmf
                null, null, null, null, null,    // Bollinger: bbMiddle, bbUpper, bbLower, bbWidth, bbPct
                null, false, null,               // BBTrend: bbTrendValue, bbTrendExpanding, bbTrendSignal
                null, null, null, null,          // DeltaFlow: deltaFlow, cumulativeDelta, buyRatio, deltaFlowBias
                null, null, null, null, null,    // WaveTrend: wtWt1, wtWt2, wtDiff, wtCrossover, wtSignal
                null, null, null, null, null, null, // SMC: trend, strongHigh, strongLow, weakHigh, weakLow, lastBreakType
                null, null, null, null,          // SMC timestamps
                Collections.emptyList(),         // activeOrderBlocks
                Collections.emptyList(),         // activeFairValueGaps
                Collections.emptyList()          // recentBreaks
        );
    }

    @Given("an indicator snapshot with RSI signal {word} and RSI value {double} for {word} on {word}")
    public void indicatorSnapshotWithRsiSignal(String signal, double rsiValue, String inst, String tf) {
        instrument = Instrument.valueOf(inst);
        timeframe = tf;
        snapshot = new IndicatorSnapshot(
                inst, tf,
                null, null, null, null,          // EMA fields
                new BigDecimal(String.valueOf(rsiValue)), signal, // RSI fields
                null, null, null, null,          // MACD fields
                null, false,                     // Supertrend fields
                null, null, null,                // VWAP fields
                null, null,                      // Chaikin fields
                null, null, null, null, null,    // Bollinger
                null, false, null,               // BBTrend
                null, null, null, null,          // DeltaFlow
                null, null, null, null, null,    // WaveTrend: wtWt1, wtWt2, wtDiff, wtCrossover, wtSignal
                null, null, null, null, null, null, // SMC
                null, null, null, null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    @Given("an indicator snapshot with MACD crossover {word} for {word} on {word}")
    public void indicatorSnapshotWithMacdCrossover(String crossover, String inst, String tf) {
        instrument = Instrument.valueOf(inst);
        timeframe = tf;
        snapshot = new IndicatorSnapshot(
                inst, tf,
                null, null, null, null,          // EMA fields
                null, null,                      // RSI fields
                null, null, null, crossover,     // MACD fields
                null, false,                     // Supertrend fields
                null, null, null,                // VWAP fields
                null, null,                      // Chaikin fields
                null, null, null, null, null,    // Bollinger
                null, false, null,               // BBTrend
                null, null, null, null,          // DeltaFlow
                null, null, null, null, null,    // WaveTrend: wtWt1, wtWt2, wtDiff, wtCrossover, wtSignal
                null, null, null, null, null, null, // SMC
                null, null, null, null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
        );
    }

    @Given("an indicator snapshot with break type {word} for {word} on {word}")
    public void indicatorSnapshotWithBreakType(String breakType, String inst, String tf) {
        instrument = Instrument.valueOf(inst);
        timeframe = tf;
        snapshot = new IndicatorSnapshot(
                inst, tf,
                null, null, null, null,          // EMA fields
                null, null,                      // RSI fields
                null, null, null, null,          // MACD fields
                null, false,                     // Supertrend fields
                null, null, null,                // VWAP fields
                null, null,                      // Chaikin fields
                null, null, null, null, null,    // Bollinger
                null, false, null,               // BBTrend
                null, null, null, null,          // DeltaFlow
                null, null, null, null, null,    // WaveTrend: wtWt1, wtWt2, wtDiff, wtCrossover, wtSignal
                null, null, null, null, null, breakType, // SMC (lastBreakType is the last field)
                null, null, null, null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList()
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
