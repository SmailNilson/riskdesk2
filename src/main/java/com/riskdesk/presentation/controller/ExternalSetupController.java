package com.riskdesk.presentation.controller;

import com.riskdesk.application.externalsetup.ExternalSetupRateLimiter;
import com.riskdesk.application.externalsetup.ExternalSetupService;
import com.riskdesk.application.externalsetup.ExternalSetupStatusView;
import com.riskdesk.application.externalsetup.ExternalSetupSubmissionCommand;
import com.riskdesk.application.externalsetup.ExternalSetupSummary;
import com.riskdesk.application.externalsetup.ExternalSetupValidationCommand;
import com.riskdesk.domain.externalsetup.ExternalSetup;
import com.riskdesk.domain.externalsetup.ExternalSetupConfidence;
import com.riskdesk.domain.externalsetup.ExternalSetupSource;
import com.riskdesk.domain.externalsetup.ExternalSetupStatus;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import com.riskdesk.presentation.dto.ExternalSetupRejectRequest;
import com.riskdesk.presentation.dto.ExternalSetupSubmitRequest;
import com.riskdesk.presentation.dto.ExternalSetupValidateRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.security.Principal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * External Setup REST surface.
 * <ul>
 *   <li>{@code POST /api/external-setups} — Claude wakeup loop submits a setup. API key required
 *       in {@code X-Setup-Token} header.</li>
 *   <li>{@code GET /api/external-setups?status=PENDING} — UI lists setups (session user).</li>
 *   <li>{@code POST /api/external-setups/{id}/validate} — UI validates and arms a trade.</li>
 *   <li>{@code POST /api/external-setups/{id}/reject} — UI rejects a setup.</li>
 * </ul>
 */
@RestController
@RequestMapping(value = "/api/external-setups", produces = MediaType.APPLICATION_JSON_VALUE)
public class ExternalSetupController {

    private static final Logger log = LoggerFactory.getLogger(ExternalSetupController.class);
    private static final String SETUP_TOKEN_HEADER = "X-Setup-Token";

    private final ExternalSetupService service;
    private final ExternalSetupRateLimiter rateLimiter;

    public ExternalSetupController(ExternalSetupService service,
                                   ExternalSetupRateLimiter rateLimiter) {
        this.service = service;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/status")
    public ExternalSetupStatusView status() {
        return service.statusView();
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ExternalSetupSummary> submit(
        @Valid @RequestBody ExternalSetupSubmitRequest request,
        HttpServletRequest httpRequest
    ) {
        ensureEnabled();
        authenticateToken(httpRequest);
        if (!rateLimiter.tryAcquire(SETUP_TOKEN_HEADER + ":" + safeTokenSuffix(httpRequest))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                "rate limit exceeded (>" + service.getRateLimitPerMinute() + " setups/min)");
        }

        Instrument instrument = parseInstrument(request.instrument());
        Side direction = parseSide(request.direction());
        ExternalSetupConfidence confidence = parseConfidence(request.confidence());
        ExternalSetupSource source = parseSource(request.source());

        try {
            ExternalSetup saved = service.submit(new ExternalSetupSubmissionCommand(
                instrument,
                direction,
                request.entry(),
                request.stopLoss(),
                request.takeProfit1(),
                request.takeProfit2(),
                confidence,
                request.triggerLabel(),
                request.payloadJson(),
                source,
                request.sourceRef()
            ));
            return ResponseEntity.status(HttpStatus.CREATED).body(ExternalSetupSummary.from(saved));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
        }
    }

    @GetMapping
    public List<ExternalSetupSummary> list(
        @RequestParam(value = "status", required = false) String statusCsv,
        @RequestParam(value = "limit", defaultValue = "50") int limit
    ) {
        ensureEnabled();
        List<ExternalSetupStatus> statuses = parseStatuses(statusCsv);
        return service.listByStatuses(statuses, limit).stream()
            .map(ExternalSetupSummary::from)
            .toList();
    }

    @GetMapping("/{id}")
    public Map<String, Object> getOne(@PathVariable("id") Long id) {
        ensureEnabled();
        ExternalSetup s = service.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "setup not found: " + id));
        Map<String, Object> body = new HashMap<>();
        body.put("summary", ExternalSetupSummary.from(s));
        body.put("payloadJson", s.getPayloadJson());
        return body;
    }

    @PostMapping(value = "/{id}/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ExternalSetupSummary validate(
        @PathVariable("id") Long id,
        @RequestBody(required = false) ExternalSetupValidateRequest request,
        Principal principal
    ) {
        ensureEnabled();
        String validator = resolveActor(principal,
            request == null ? null : request.validatedBy(), "ui");
        try {
            ExternalSetup saved = service.validate(new ExternalSetupValidationCommand(
                id,
                validator,
                request == null ? null : request.quantity(),
                request == null ? null : request.brokerAccountId(),
                request == null ? null : request.overrideEntryPrice()
            ));
            return ExternalSetupSummary.from(saved);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    @PostMapping(value = "/{id}/reject", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ExternalSetupSummary reject(
        @PathVariable("id") Long id,
        @Valid @RequestBody(required = false) ExternalSetupRejectRequest request,
        Principal principal
    ) {
        ensureEnabled();
        String rejecter = resolveActor(principal,
            request == null ? null : request.rejectedBy(), "ui");
        try {
            ExternalSetup saved = service.reject(id, rejecter,
                request == null ? null : request.reason());
            return ExternalSetupSummary.from(saved);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }

    // -------- helpers --------

    private void ensureEnabled() {
        if (!service.isEnabled()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "external setup pipeline is disabled (riskdesk.external-setup.enabled=false)");
        }
    }

    private void authenticateToken(HttpServletRequest req) {
        if (!service.isApiTokenConfigured()) {
            // misconfigured: refuse rather than fail open.
            log.warn("ExternalSetup submit rejected: server has no api-token configured");
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "external setup api-token is not configured server-side");
        }
        String header = req.getHeader(SETUP_TOKEN_HEADER);
        if (!service.tokenMatches(header)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "invalid or missing " + SETUP_TOKEN_HEADER + " header");
        }
    }

    private static String safeTokenSuffix(HttpServletRequest req) {
        String header = req.getHeader(SETUP_TOKEN_HEADER);
        if (header == null || header.length() < 4) {
            return "anon";
        }
        return header.substring(header.length() - 4);
    }

    private static Instrument parseInstrument(String raw) {
        try {
            return Instrument.valueOf(raw.toUpperCase());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown instrument: " + raw, e);
        }
    }

    private static Side parseSide(String raw) {
        try {
            return Side.valueOf(raw.toUpperCase());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "direction must be LONG or SHORT (got " + raw + ")", e);
        }
    }

    private static ExternalSetupConfidence parseConfidence(String raw) {
        if (raw == null || raw.isBlank()) return ExternalSetupConfidence.MEDIUM;
        try {
            return ExternalSetupConfidence.valueOf(raw.toUpperCase());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "confidence must be LOW/MEDIUM/HIGH (got " + raw + ")", e);
        }
    }

    private static ExternalSetupSource parseSource(String raw) {
        if (raw == null || raw.isBlank()) return ExternalSetupSource.CLAUDE_WAKEUP;
        try {
            return ExternalSetupSource.valueOf(raw.toUpperCase());
        } catch (RuntimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown source: " + raw, e);
        }
    }

    private static List<ExternalSetupStatus> parseStatuses(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of(ExternalSetupStatus.PENDING);
        }
        try {
            return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(String::toUpperCase)
                .map(ExternalSetupStatus::valueOf)
                .toList();
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown status in: " + csv, e);
        }
    }

    private static String resolveActor(Principal principal, String requestActor, String fallback) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        if (requestActor != null && !requestActor.isBlank()) {
            return requestActor;
        }
        return fallback;
    }
}
