package com.riskdesk.presentation.quant;

import com.riskdesk.application.quant.simulation.QuantSimExecutionProperties;
import com.riskdesk.application.quant.simulation.QuantSimExecutionState;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the Auto-IBKR toggle / exec-state endpoints. Drives the
 * controller methods directly with a real toggle state + properties.
 */
class Quant7GatesSimulationControllerExecTest {

    private QuantSimExecutionState state;
    private QuantSimExecutionProperties props;
    private Quant7GatesSimulationController controller;

    @BeforeEach
    void setUp() {
        state = new QuantSimExecutionState();
        props = new QuantSimExecutionProperties();
        props.setEnabled(true);
        props.setInstruments(List.of("MNQ", "MCL"));
        controller = new Quant7GatesSimulationController(null, state, props);
    }

    @Test
    void togglesAllowlistedInstrument() {
        var resp = controller.setAutoExecution("MNQ",
            new Quant7GatesSimulationController.AutoExecutionRequest(true));

        assertThat(state.isEnabled(Instrument.MNQ)).isTrue();
        assertThat(resp.masterEnabled()).isTrue();
        assertThat(resp.allowlist()).containsExactly("MNQ", "MCL");
        assertThat(resp.toggles()).containsEntry("MNQ", true).containsEntry("MCL", false);
    }

    @Test
    void disarmsInstrument() {
        state.setEnabled(Instrument.MNQ, true);
        controller.setAutoExecution("MNQ",
            new Quant7GatesSimulationController.AutoExecutionRequest(false));
        assertThat(state.isEnabled(Instrument.MNQ)).isFalse();
    }

    @Test
    void rejectsNonAllowlistedInstrument() {
        assertThatThrownBy(() -> controller.setAutoExecution("MGC",
            new Quant7GatesSimulationController.AutoExecutionRequest(true)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("allowlist");
        assertThat(state.isEnabled(Instrument.MGC)).isFalse();
    }

    @Test
    void rejectsUnknownInstrument() {
        assertThatThrownBy(() -> controller.setAutoExecution("XYZ",
            new Quant7GatesSimulationController.AutoExecutionRequest(true)))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("Unknown instrument");
    }

    @Test
    void execStateReportsMasterAndToggles() {
        state.setEnabled(Instrument.MCL, true);
        var resp = controller.execState();
        assertThat(resp.masterEnabled()).isTrue();
        assertThat(resp.toggles()).containsEntry("MNQ", false).containsEntry("MCL", true);
    }
}
