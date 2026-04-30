package com.riskdesk.presentation.quant;

import com.riskdesk.application.quant.automation.QuantAutoArmService;
import com.riskdesk.application.service.ExecutionManagerService;
import com.riskdesk.application.service.SubmitEntryOrderCommand;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.presentation.quant.dto.AutoArmStatusResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

/**
 * Operator-facing controller for the auto-arm pipeline.
 *
 * <ul>
 *   <li>{@code POST /api/quant/auto-arm/{executionId}/fire} — submit
 *       immediately (skips the cancel window). Idempotent: re-firing an
 *       already-submitted execution returns the current status.</li>
 *   <li>{@code POST /api/quant/auto-arm/{executionId}/cancel} — cancel the
 *       arm. Idempotent: cancelling a non-pending execution is a no-op.</li>
 *   <li>{@code GET  /api/quant/auto-arm/active} — list all currently armed
 *       (PENDING + QUANT_AUTO_ARM) executions for the dashboard tray.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/quant/auto-arm")
public class QuantAutoArmController {

    private static final Logger log = LoggerFactory.getLogger(QuantAutoArmController.class);

    private final QuantAutoArmService autoArmService;
    private final ExecutionManagerService executionManager;
    private final TradeExecutionRepositoryPort tradeExecutionRepository;
    private final Clock clock;

    public QuantAutoArmController(QuantAutoArmService autoArmService,
                                   ExecutionManagerService executionManager,
                                   TradeExecutionRepositoryPort tradeExecutionRepository,
                                   Clock clock) {
        this.autoArmService = autoArmService;
        this.executionManager = executionManager;
        this.tradeExecutionRepository = tradeExecutionRepository;
        this.clock = clock;
    }

    @PostMapping("/{executionId}/fire")
    public ResponseEntity<AutoArmStatusResponse> fire(@PathVariable Long executionId,
                                                       @RequestHeader(value = "X-Requested-By", required = false) String requestedBy) {
        Optional<TradeExecutionRecord> opt = tradeExecutionRepository.findById(executionId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        TradeExecutionRecord exec = opt.get();

        // Idempotence — don't re-submit a non-pending row. Return the live
        // status so the UI can correctly reflect ENTRY_SUBMITTED / ACTIVE etc.
        if (exec.getStatus() != ExecutionStatus.PENDING_ENTRY_SUBMISSION) {
            log.debug("auto-arm fire ignored — executionId={} status={}", executionId, exec.getStatus());
            return ResponseEntity.ok(toResponse(exec));
        }

        try {
            TradeExecutionRecord submitted = executionManager.submitEntryOrder(new SubmitEntryOrderCommand(
                executionId,
                clock.instant(),
                requestedBy == null || requestedBy.isBlank() ? "operator-fire" : requestedBy
            ));
            return ResponseEntity.ok(toResponse(submitted));
        } catch (IllegalStateException e) {
            // ExecutionManagerService raises IllegalStateException for race
            // conditions and IBKR failures. Surface the latest known state.
            return tradeExecutionRepository.findById(executionId)
                .map(updated -> ResponseEntity.ok(toResponse(updated)))
                .orElseGet(() -> ResponseEntity.status(409).build());
        }
    }

    @PostMapping("/{executionId}/cancel")
    public ResponseEntity<AutoArmStatusResponse> cancel(@PathVariable Long executionId,
                                                         @RequestHeader(value = "X-Requested-By", required = false) String requestedBy) {
        Optional<TradeExecutionRecord> opt = autoArmService.cancel(executionId, requestedBy);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(toResponse(opt.get()));
    }

    @GetMapping("/active")
    public ResponseEntity<List<AutoArmStatusResponse>> active() {
        List<TradeExecutionRecord> pending =
            tradeExecutionRepository.findPendingByTriggerSource(ExecutionTriggerSource.QUANT_AUTO_ARM);
        List<AutoArmStatusResponse> body = pending.stream()
            .map(this::toResponse)
            .toList();
        return ResponseEntity.ok(body);
    }

    private AutoArmStatusResponse toResponse(TradeExecutionRecord exec) {
        return AutoArmStatusResponse.from(exec, autoArmService.computeAutoSubmitAt(exec), clock.instant());
    }
}
