package com.riskdesk.presentation.quant;

import com.riskdesk.application.quant.simulation.QuantSimExecutionProperties;
import com.riskdesk.application.quant.simulation.QuantSimExecutionState;
import com.riskdesk.application.quant.simulation.QuantSimInvertState;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.simulation.QuantSimInvertMode;
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
    private QuantSimInvertState invertState;
    private QuantSimExecutionProperties props;
    private Quant7GatesSimulationController controller;

    @BeforeEach
    void setUp() {
        state = new QuantSimExecutionState();
        invertState = new QuantSimInvertState();
        props = new QuantSimExecutionProperties();
        props.setEnabled(true);
        props.setInstruments(List.of("MNQ", "MCL"));
        controller = new Quant7GatesSimulationController(null, state, invertState, props,
            new com.riskdesk.application.quant.simulation.QuantSimProperties());
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
        // Inversion modes are reported for every instrument, default NONE.
        assertThat(resp.invertModes()).containsEntry("MNQ", "NONE").containsEntry("MCL", "NONE");
    }

    @Test
    void setsInvertMirrorMode() {
        var resp = controller.setInvert("MNQ",
            new Quant7GatesSimulationController.InvertRequest("MIRROR"));
        assertThat(invertState.mode(Instrument.MNQ)).isEqualTo(QuantSimInvertMode.MIRROR);
        assertThat(resp.invertModes()).containsEntry("MNQ", "MIRROR").containsEntry("MCL", "NONE");
    }

    @Test
    void invertAllowedForNonAllowlistedInstrument() {
        // Inversion is paper-DIRECTION only — NOT gated on the exec allowlist.
        controller.setInvert("MGC", new Quant7GatesSimulationController.InvertRequest("FADE"));
        assertThat(invertState.mode(Instrument.MGC)).isEqualTo(QuantSimInvertMode.FADE);
    }

    @Test
    void rejectsInvalidInvertMode() {
        assertThatThrownBy(() -> controller.setInvert("MNQ",
            new Quant7GatesSimulationController.InvertRequest("BOGUS")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("NONE");
        assertThat(invertState.mode(Instrument.MNQ)).isEqualTo(QuantSimInvertMode.NONE);
    }
}
