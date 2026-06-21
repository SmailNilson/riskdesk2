package com.riskdesk.presentation.quant;

import com.riskdesk.application.dto.ActivePositionView;
import com.riskdesk.application.quant.positions.ActivePositionsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

/**
 * REST surface for the Active Positions panel.
 *
 * <ul>
 *   <li>{@code GET  /api/quant/positions/active} — every non-terminal
 *       execution, enriched with a server-side PnL snapshot.</li>
 *   <li>{@code POST /api/quant/positions/{executionId}/close} — request a
 *       close. Idempotent: closing an already-terminal row returns the
 *       existing terminal view.</li>
 *   <li>{@code POST /api/quant/positions/{executionId}/cancel-entry} — cancel an
 *       entry order that has not filled (local cancel for PENDING rows, real broker
 *       cancel for a resting ENTRY_SUBMITTED limit). 409 when fills exist.</li>
 *   <li>{@code POST /api/quant/positions/{executionId}/reverse} — flip a live position
 *       to the opposite side at the same size via the unified router (REVERSE intent).
 *       409 when there is no live position to reverse.</li>
 *   <li>{@code POST /api/quant/positions/{executionId}/modify-protection} — update the
 *       VIRTUAL stop-loss / take-profit of a non-terminal position (no broker order).
 *       400 on bad geometry, 409 on a terminal row.</li>
 *   <li>{@code POST /api/quant/positions/{executionId}/reduce?qty=N} — partial close
 *       (scale-out): reduce a live position by N contracts, keeping the remainder. A full-size
 *       N is a full close. 400 on qty&lt;1, 409 when not a live position.</li>
 * </ul>
 *
 * <p>Live updates after the initial load come through {@code /topic/positions}
 * via {@code ActivePositionsWebSocketAdapter} — the panel does not need to
 * poll this endpoint.</p>
 */
@RestController
@RequestMapping("/api/quant/positions")
public class ActivePositionsController {

    private static final Logger log = LoggerFactory.getLogger(ActivePositionsController.class);

    private final ActivePositionsService activePositionsService;

    public ActivePositionsController(ActivePositionsService activePositionsService) {
        this.activePositionsService = activePositionsService;
    }

    @GetMapping("/active")
    public ResponseEntity<List<ActivePositionView>> active() {
        return ResponseEntity.ok(activePositionsService.listActive());
    }

    @PostMapping("/{executionId}/close")
    public ResponseEntity<ActivePositionView> close(@PathVariable Long executionId,
                                                    @RequestHeader(value = "X-Requested-By", required = false) String requestedBy) {
        try {
            Optional<ActivePositionView> result = activePositionsService.closePosition(executionId, requestedBy);
            return result
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Close conflicted for execution {} (concurrent update) — retry", executionId);
            return ResponseEntity.status(409).build();
        } catch (IllegalStateException e) {
            log.warn("Close rejected for execution {} — {}", executionId, e.getMessage());
            return ResponseEntity.status(409).build();
        }
    }

    @PostMapping("/{executionId}/cancel-entry")
    public ResponseEntity<ActivePositionView> cancelEntry(@PathVariable Long executionId,
                                                          @RequestHeader(value = "X-Requested-By", required = false) String requestedBy) {
        try {
            Optional<ActivePositionView> result = activePositionsService.cancelEntry(executionId, requestedBy);
            return result
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Cancel-entry conflicted for execution {} (concurrent update) — retry", executionId);
            return ResponseEntity.status(409).build();
        } catch (IllegalStateException e) {
            log.warn("Cancel-entry rejected for execution {} — {}", executionId, e.getMessage());
            return ResponseEntity.status(409).build();
        }
    }

    @PostMapping("/{executionId}/reverse")
    public ResponseEntity<ActivePositionView> reverse(@PathVariable Long executionId,
                                                      @RequestHeader(value = "X-Requested-By", required = false) String requestedBy) {
        try {
            Optional<ActivePositionView> result = activePositionsService.reversePosition(executionId, requestedBy);
            return result
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Reverse conflicted for execution {} (concurrent update) — retry", executionId);
            return ResponseEntity.status(409).build();
        } catch (IllegalStateException e) {
            log.warn("Reverse rejected for execution {} — {}", executionId, e.getMessage());
            return ResponseEntity.status(409).build();
        }
    }

    @PostMapping("/{executionId}/modify-protection")
    public ResponseEntity<ActivePositionView> modifyProtection(
            @PathVariable Long executionId,
            @RequestBody(required = false) ModifyProtectionRequest body,
            @RequestHeader(value = "X-Requested-By", required = false) String requestedBy) {
        try {
            Optional<ActivePositionView> result = activePositionsService.modifyProtection(
                executionId,
                body == null ? null : body.stopLoss(),
                body == null ? null : body.takeProfit(),
                requestedBy);
            return result
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            log.warn("Modify-protection bad request for execution {} — {}", executionId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Modify-protection conflicted for execution {} (concurrent update) — retry", executionId);
            return ResponseEntity.status(409).build();
        } catch (IllegalStateException e) {
            log.warn("Modify-protection rejected for execution {} — {}", executionId, e.getMessage());
            return ResponseEntity.status(409).build();
        }
    }

    @PostMapping("/{executionId}/reduce")
    public ResponseEntity<ActivePositionView> reduce(
            @PathVariable Long executionId,
            @RequestParam int qty,
            @RequestHeader(value = "X-Requested-By", required = false) String requestedBy) {
        try {
            Optional<ActivePositionView> result = activePositionsService.reducePosition(executionId, qty, requestedBy);
            return result
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
        } catch (IllegalArgumentException e) {
            log.warn("Reduce bad request for execution {} — {}", executionId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (ObjectOptimisticLockingFailureException e) {
            log.warn("Reduce conflicted for execution {} (concurrent update) — retry", executionId);
            return ResponseEntity.status(409).build();
        } catch (IllegalStateException e) {
            log.warn("Reduce rejected for execution {} — {}", executionId, e.getMessage());
            return ResponseEntity.status(409).build();
        }
    }

    /** Body for {@code POST /{executionId}/modify-protection} — either level may be null (update one). */
    public record ModifyProtectionRequest(BigDecimal stopLoss, BigDecimal takeProfit) {
    }
}
