package com.riskdesk.presentation.quant;

import com.riskdesk.application.quant.simulation.Quant7GatesSimulationService;
import com.riskdesk.application.quant.simulation.QuantSimExecutionProperties;
import com.riskdesk.application.quant.simulation.QuantSimExecutionState;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.presentation.quant.dto.Quant7GatesSimulationResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only HTTP surface for the Quant 7-Gates simulation harness.
 *
 * <ul>
 *   <li>{@code GET /api/quant/simulations} — list every open + closed
 *       simulated trade, newest first.</li>
 *   <li>{@code GET /api/quant/simulations/open} — same, restricted to OPEN
 *       rows (useful for the live tracker badge).</li>
 *   <li>{@code GET /api/quant/simulations/stats} — aggregate win-rate and
 *       net P&amp;L across closed rows.</li>
 * </ul>
 *
 * <p>Live updates stream via {@code /topic/quant/simulations} (STOMP).
 */
@RestController
@RequestMapping("/api/quant/simulations")
public class Quant7GatesSimulationController {

    private final Quant7GatesSimulationService service;
    private final QuantSimExecutionState execState;
    private final QuantSimExecutionProperties execProps;

    public Quant7GatesSimulationController(Quant7GatesSimulationService service,
                                           QuantSimExecutionState execState,
                                           QuantSimExecutionProperties execProps) {
        this.service = service;
        this.execState = execState;
        this.execProps = execProps;
    }

    @GetMapping
    public List<Quant7GatesSimulationResponse> listAll() {
        return service.listAll().stream().map(Quant7GatesSimulationResponse::from).toList();
    }

    @GetMapping("/open")
    public List<Quant7GatesSimulationResponse> listOpen() {
        return service.listOpen().stream().map(Quant7GatesSimulationResponse::from).toList();
    }

    @GetMapping("/stats")
    public StatsResponse stats() {
        Quant7GatesSimulationService.Stats s = service.stats();
        return new StatsResponse(
            s.closedCount(),
            s.wins(),
            s.losses(),
            s.winRatePct(),
            s.netPoints(),
            s.netUsd(),
            service.listOpen().size()
        );
    }

    /**
     * Arm / disarm the Auto-IBKR mirror for one instrument. 400 when the
     * instrument is unknown or not on the execution allowlist (MGC / 6E can never
     * route a live order). The per-instrument toggle is independent of the master
     * flag {@code riskdesk.quant.sim-exec.enabled} — both must be on to route.
     */
    @PutMapping("/{instrument}/auto-execution")
    public ExecStateResponse setAutoExecution(@PathVariable String instrument,
                                              @RequestBody AutoExecutionRequest request) {
        Instrument instr = parseInstrument(instrument);
        if (!execProps.isAllowed(instr.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                instr.name() + " is not on the sim-exec allowlist " + execProps.getInstruments());
        }
        execState.setEnabled(instr, request != null && request.enabled());
        return execStateResponse();
    }

    /** Current master flag + allowlist + per-instrument toggle state. */
    @GetMapping("/exec-state")
    public ExecStateResponse execState() {
        return execStateResponse();
    }

    private ExecStateResponse execStateResponse() {
        Map<String, Boolean> toggles = new LinkedHashMap<>();
        for (String name : execProps.getInstruments()) {
            try {
                toggles.put(name, execState.isEnabled(Instrument.valueOf(name)));
            } catch (IllegalArgumentException ignored) {
                // A misconfigured allowlist name — surface it as off rather than 500.
                toggles.put(name, false);
            }
        }
        return new ExecStateResponse(execProps.isEnabled(), execProps.getInstruments(), toggles);
    }

    private static Instrument parseInstrument(String name) {
        try {
            return Instrument.valueOf(name.toUpperCase());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown instrument: " + name);
        }
    }

    /** Body for {@code PUT /{instrument}/auto-execution}. */
    public record AutoExecutionRequest(boolean enabled) {}

    /** Master flag + allowlist + per-instrument toggle snapshot. */
    public record ExecStateResponse(boolean masterEnabled, List<String> allowlist, Map<String, Boolean> toggles) {}

    /** Public-facing aggregate. {@code winRatePct} is null when no rows are decided yet. */
    public record StatsResponse(
        int closedCount,
        int wins,
        int losses,
        Double winRatePct,
        double netPoints,
        double netUsd,
        int openCount
    ) {}
}
