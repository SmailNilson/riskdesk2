package com.riskdesk.presentation.quant;

import com.riskdesk.application.quant.simulation.Quant7GatesSimulationService;
import com.riskdesk.application.quant.simulation.QuantSimExecutionProperties;
import com.riskdesk.application.quant.simulation.QuantSimExecutionState;
import com.riskdesk.application.quant.simulation.QuantSimInvertState;
import com.riskdesk.application.quant.simulation.QuantSimProperties;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.simulation.QuantSimInvertMode;
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
    private final QuantSimInvertState invertState;
    private final QuantSimExecutionProperties execProps;
    private final QuantSimProperties simProps;

    public Quant7GatesSimulationController(Quant7GatesSimulationService service,
                                           QuantSimExecutionState execState,
                                           QuantSimInvertState invertState,
                                           QuantSimExecutionProperties execProps,
                                           QuantSimProperties simProps) {
        this.service = service;
        this.execState = execState;
        this.invertState = invertState;
        this.execProps = execProps;
        this.simProps = simProps;
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

        // Per-instrument open counts — an instrument with only open rows must
        // still appear in the breakdown.
        Map<String, Integer> openByInstrument = new LinkedHashMap<>();
        for (var open : service.listOpen()) {
            openByInstrument.merge(open.instrument().name(), 1, Integer::sum);
        }
        Map<String, InstrumentStats> byInstrument = new LinkedHashMap<>();
        service.statsByInstrument().forEach((name, st) -> byInstrument.put(name, new InstrumentStats(
            st.closedCount(), st.wins(), st.losses(), st.winRatePct(),
            st.netPoints(), st.netUsd(), openByInstrument.getOrDefault(name, 0))));
        openByInstrument.forEach((name, count) -> byInstrument.computeIfAbsent(name,
            k -> new InstrumentStats(0, 0, 0, null, 0.0, 0.0, count)));

        return new StatsResponse(
            s.closedCount(),
            s.wins(),
            s.losses(),
            s.winRatePct(),
            s.netPoints(),
            s.netUsd(),
            service.listOpen().size(),
            byInstrument,
            simProps.getStatsSince()
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

    /**
     * Set the per-instrument direction-inversion mode (NONE / MIRROR / FADE).
     * Paper-DIRECTION only — it picks which way the simulated trade opens and is
     * INDEPENDENT of the Auto-IBKR mirror, so it is NOT gated on the exec
     * allowlist: an instrument can be inverted in paper without ever routing a
     * real order. When the mirror IS armed for the instrument, the inverted
     * trade routes live like any other.
     */
    @PutMapping("/{instrument}/invert")
    public ExecStateResponse setInvert(@PathVariable String instrument,
                                       @RequestBody InvertRequest request) {
        Instrument instr = parseInstrument(instrument);
        QuantSimInvertMode mode;
        try {
            mode = request == null || request.mode() == null
                ? QuantSimInvertMode.NONE
                : QuantSimInvertMode.valueOf(request.mode().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "mode must be NONE | MIRROR | FADE");
        }
        invertState.setMode(instr, mode);
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
        // Inversion modes for every instrument (independent of the exec
        // allowlist — an instrument can be paper-inverted without being routable).
        Map<String, String> invertModes = new LinkedHashMap<>();
        invertState.snapshot().forEach((instr, mode) -> invertModes.put(instr.name(), mode.name()));
        return new ExecStateResponse(execProps.isEnabled(), execProps.getInstruments(), toggles, invertModes);
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

    /** Body for {@code PUT /{instrument}/invert} — one of NONE | MIRROR | FADE. */
    public record InvertRequest(String mode) {}

    /**
     * Master flag + allowlist + per-instrument Auto-IBKR toggles + per-instrument
     * inversion modes ({@code instrument -> NONE | MIRROR | FADE}).
     */
    public record ExecStateResponse(boolean masterEnabled, List<String> allowlist,
                                    Map<String, Boolean> toggles, Map<String, String> invertModes) {}

    /**
     * Public-facing aggregate. {@code winRatePct} is null when no rows are
     * decided yet. {@code byInstrument} breaks the same numbers down per
     * instrument (key = enum name, sorted) so each market is judged on its
     * own P&amp;L. {@code statsSince} is the stats baseline — rows opened
     * before it are excluded from every aggregate here (known-bad entry-data
     * era, e.g. pre-delta-fix); null = full history.
     */
    public record StatsResponse(
        int closedCount,
        int wins,
        int losses,
        Double winRatePct,
        double netPoints,
        double netUsd,
        int openCount,
        Map<String, InstrumentStats> byInstrument,
        java.time.Instant statsSince
    ) {}

    /** Per-instrument slice of {@link StatsResponse}. */
    public record InstrumentStats(
        int closedCount,
        int wins,
        int losses,
        Double winRatePct,
        double netPoints,
        double netUsd,
        int openCount
    ) {}
}
