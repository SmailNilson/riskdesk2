package com.riskdesk.bdd.steps;

import io.cucumber.java.en.And;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Executable acceptance skeleton for the planned Playbook Auto-Simulation +
 * Auto-IBKR policy. It intentionally avoids production imports until the
 * routing policy/endpoints land; production-bound unit/controller tests should
 * replace this state machine once those symbols exist.
 */
public class PlaybookAutoRoutingSteps {

    private boolean autoSimulationEnabled;
    private boolean autoIbkrEnabled;
    private boolean actionablePlan;
    private boolean brokerPreflightAllowed;
    private boolean brokerPreflightEvaluated;
    private boolean ibkrBackendEnabled = true;

    private boolean simulationRequested;
    private boolean ibkrOrderRequested;
    private String routingReason;

    @Given("playbook auto-simulation is enabled")
    public void playbookAutoSimulationIsEnabled() {
        autoSimulationEnabled = true;
    }

    @And("playbook Auto-IBKR is disabled")
    public void playbookAutoIbkrIsDisabled() {
        autoIbkrEnabled = false;
    }

    @And("playbook Auto-IBKR is enabled")
    public void playbookAutoIbkrIsEnabled() {
        autoIbkrEnabled = true;
    }

    @And("the playbook verdict is actionable with complete entry risk and reward")
    public void playbookVerdictIsActionableWithCompleteEntryRiskAndReward() {
        actionablePlan = true;
    }

    @And("the playbook verdict is not actionable")
    public void playbookVerdictIsNotActionable() {
        actionablePlan = false;
    }

    @And("broker preflight allows the order")
    public void brokerPreflightAllowsTheOrder() {
        brokerPreflightEvaluated = true;
        brokerPreflightAllowed = true;
    }

    @And("broker preflight denies the order")
    public void brokerPreflightDeniesTheOrder() {
        brokerPreflightEvaluated = true;
        brokerPreflightAllowed = false;
    }

    @And("the IBKR backend is disabled")
    public void ibkrBackendIsDisabled() {
        ibkrBackendEnabled = false;
    }

    @When("the playbook routing policy is evaluated")
    public void playbookRoutingPolicyIsEvaluated() {
        simulationRequested = false;
        ibkrOrderRequested = false;

        if (!actionablePlan) {
            routingReason = "SKIPPED_NO_PLAN";
            return;
        }

        if (!autoSimulationEnabled) {
            routingReason = "AUTO_SIMULATION_DISABLED";
            return;
        }

        simulationRequested = true;

        if (!autoIbkrEnabled) {
            routingReason = "PAPER_ONLY";
            return;
        }

        if (!ibkrBackendEnabled) {
            routingReason = "SKIPPED_IBKR_DISABLED";
            return;
        }

        if (brokerPreflightEvaluated && !brokerPreflightAllowed) {
            routingReason = "SKIPPED_PREFLIGHT_DENIED";
            return;
        }

        ibkrOrderRequested = true;
        routingReason = "ROUTED";
    }

    @Then("a playbook simulation should be requested")
    public void playbookSimulationShouldBeRequested() {
        assertTrue(simulationRequested, "Expected a playbook simulation request");
    }

    @Then("no playbook simulation should be requested")
    public void noPlaybookSimulationShouldBeRequested() {
        assertFalse(simulationRequested, "Expected no playbook simulation request");
    }

    @And("an IBKR order should be requested")
    public void ibkrOrderShouldBeRequested() {
        assertTrue(ibkrOrderRequested, "Expected an IBKR order request");
    }

    @And("no IBKR order should be requested")
    public void noIbkrOrderShouldBeRequested() {
        assertFalse(ibkrOrderRequested, "Expected no IBKR order request");
    }

    @And("the routing reason should be {string}")
    public void routingReasonShouldBe(String expectedReason) {
        assertEquals(expectedReason, routingReason);
    }
}
