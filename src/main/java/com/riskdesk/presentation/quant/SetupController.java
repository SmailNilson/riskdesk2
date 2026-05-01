package com.riskdesk.presentation.quant;

import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.setup.SetupRecommendation;
import com.riskdesk.domain.quant.setup.port.SetupRepositoryPort;
import com.riskdesk.presentation.quant.dto.SetupView;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * REST entry point for setup recommendations.
 *
 * <ul>
 *   <li>{@code GET /api/quant/setups/{instrument}} — active setups for the instrument.</li>
 *   <li>{@code GET /api/quant/setups/{instrument}/{id}} — single setup by UUID.</li>
 * </ul>
 *
 * <p>Write operations (phase transitions) are internal — no POST endpoint is
 * exposed here.</p>
 */
@RestController
@RequestMapping("/api/quant/setups")
public class SetupController {

    private final SetupRepositoryPort repositoryPort;

    public SetupController(SetupRepositoryPort repositoryPort) {
        this.repositoryPort = repositoryPort;
    }

    @GetMapping("/{instrument}")
    public ResponseEntity<List<SetupView>> active(@PathVariable String instrument) {
        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        List<SetupView> body = repositoryPort.findActiveByInstrument(inst)
            .stream()
            .map(SetupView::from)
            .toList();
        return ResponseEntity.ok(body);
    }

    @GetMapping("/{instrument}/{id}")
    public ResponseEntity<SetupView> byId(@PathVariable String instrument,
                                           @PathVariable UUID id) {
        Instrument inst;
        try {
            inst = Instrument.valueOf(instrument.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().build();
        }
        // Filter by both id AND instrument so /api/quant/setups/MGC/{mcl-id}
        // returns 404 instead of leaking the MCL row across the URL contract.
        return repositoryPort.findById(id)
            .filter(s -> s.instrument() == inst)
            .map(SetupView::from)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }
}
