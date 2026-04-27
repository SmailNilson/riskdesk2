package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.TradeExecutionView;
import com.riskdesk.application.service.OrderFlowQuickExecutionCommand;
import com.riskdesk.application.service.OrderFlowQuickExecutionService;
import com.riskdesk.presentation.dto.OrderFlowQuickExecutionRequest;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Arms a trade directly from an order-flow signal, bypassing the Mentor review.
 * <p>
 * The endpoint never contacts IBKR itself — it creates a synthetic mentor review
 * + an execution row in state {@code PENDING_ENTRY_SUBMISSION}. The broker order
 * is only dispatched when a separate call hits
 * {@code POST /api/mentor/executions/{executionId}/submit-entry}.
 * <p>
 * Disabled by default. Enable with
 * {@code riskdesk.orderflow.quick-execution.enabled=true}.
 */
@RestController
@RequestMapping("/api/orderflow")
public class OrderFlowExecutionController {

    private static final Logger log = LoggerFactory.getLogger(OrderFlowExecutionController.class);

    private final OrderFlowQuickExecutionService service;

    public OrderFlowExecutionController(OrderFlowQuickExecutionService service) {
        this.service = service;
    }

    @GetMapping("/quick-execution/status")
    public Map<String, Object> status() {
        return Map.of(
            "enabled", service.isEnabled(),
            "note", service.isEnabled()
                ? "Direct order-flow execution is ENABLED — Mentor is bypassed."
                : "Direct order-flow execution is DISABLED. Set riskdesk.orderflow.quick-execution.enabled=true."
        );
    }

    @PostMapping("/quick-execution")
    public ResponseEntity<TradeExecutionView> quickExecution(
            @Valid @RequestBody OrderFlowQuickExecutionRequest request) {
        try {
            var execution = service.arm(new OrderFlowQuickExecutionCommand(
                request.instrument().toUpperCase(),
                request.timeframe(),
                request.action().toUpperCase(),
                request.entryPrice(),
                request.stopLoss(),
                request.takeProfit(),
                request.quantity(),
                request.brokerAccountId(),
                request.reason()
            ));
            return ResponseEntity.status(HttpStatus.CREATED).body(TradeExecutionView.from(execution));
        } catch (IllegalArgumentException e) {
            log.warn("quick-execution rejected: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage(), e);
        } catch (IllegalStateException e) {
            log.warn("quick-execution conflict or disabled: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage(), e);
        }
    }
}
