package com.riskdesk.presentation.quant;

import com.riskdesk.application.dto.ActivePositionView;
import com.riskdesk.application.quant.positions.ActivePositionsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
        } catch (IllegalStateException e) {
            log.warn("Close rejected for execution {} — {}", executionId, e.getMessage());
            return ResponseEntity.status(409).build();
        }
    }
}
