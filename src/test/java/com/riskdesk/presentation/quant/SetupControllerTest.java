package com.riskdesk.presentation.quant;

import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.setup.SetupPhase;
import com.riskdesk.domain.quant.setup.SetupRecommendation;
import com.riskdesk.domain.quant.setup.SetupStyle;
import com.riskdesk.domain.quant.setup.SetupTemplate;
import com.riskdesk.domain.quant.setup.port.SetupRepositoryPort;
import com.riskdesk.presentation.quant.dto.SetupView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SetupControllerTest {

    private SetupRepositoryPort repo;
    private SetupController controller;

    @BeforeEach
    void setup() {
        repo = mock(SetupRepositoryPort.class);
        controller = new SetupController(repo);
    }

    private SetupRecommendation rec(Instrument inst) {
        return new SetupRecommendation(
            UUID.randomUUID(), inst, SetupTemplate.D_MTF_ALIGN, SetupStyle.DAY,
            SetupPhase.DETECTED, MarketRegime.RANGING, Direction.SHORT,
            6.0, BigDecimal.valueOf(20_000), BigDecimal.valueOf(20_025),
            BigDecimal.valueOf(19_960), BigDecimal.valueOf(19_920),
            2.0, null, List.of(), Instant.now(), Instant.now()
        );
    }

    @Test
    void byId_returns_setup_when_instrument_matches() {
        SetupRecommendation mcl = rec(Instrument.MCL);
        when(repo.findById(mcl.id())).thenReturn(Optional.of(mcl));

        ResponseEntity<SetupView> resp = controller.byId("MCL", mcl.id());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().instrument()).isEqualTo("MCL");
    }

    @Test
    void byId_returns_404_on_instrument_mismatch() {
        SetupRecommendation mcl = rec(Instrument.MCL);
        when(repo.findById(mcl.id())).thenReturn(Optional.of(mcl));

        // Asking for the same id under a different instrument must NOT leak
        // the row across the URL contract.
        ResponseEntity<SetupView> resp = controller.byId("MGC", mcl.id());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void byId_returns_400_on_unknown_instrument() {
        ResponseEntity<SetupView> resp = controller.byId("XXX", UUID.randomUUID());
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void byId_returns_404_when_id_unknown() {
        UUID id = UUID.randomUUID();
        when(repo.findById(id)).thenReturn(Optional.empty());

        ResponseEntity<SetupView> resp = controller.byId("MCL", id);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
