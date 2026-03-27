package com.riskdesk.presentation.controller;

import com.riskdesk.application.dto.IbkrAuthStatusView;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrWatchlistView;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.application.service.IbkrWatchlistService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ibkr")
@CrossOrigin(origins = "*")
public class IbkrPortfolioController {

    private final IbkrPortfolioService ibkrPortfolioService;
    private final IbkrWatchlistService ibkrWatchlistService;

    public IbkrPortfolioController(IbkrPortfolioService ibkrPortfolioService,
                                   IbkrWatchlistService ibkrWatchlistService) {
        this.ibkrPortfolioService = ibkrPortfolioService;
        this.ibkrWatchlistService = ibkrWatchlistService;
    }

    @GetMapping("/portfolio")
    public IbkrPortfolioSnapshot getPortfolio(@RequestParam(required = false) String accountId) {
        return ibkrPortfolioService.getPortfolio(accountId);
    }

    @GetMapping("/auth/status")
    public IbkrAuthStatusView getAuthStatus() {
        return ibkrPortfolioService.getAuthStatus();
    }

    @GetMapping("/connection/status")
    public IbkrAuthStatusView getConnectionStatus() {
        return ibkrPortfolioService.getAuthStatus();
    }

    @PostMapping("/auth/refresh")
    public IbkrAuthStatusView refreshAuthStatus() {
        return ibkrPortfolioService.refreshAuthStatus();
    }

    @PostMapping("/connection/refresh")
    public IbkrAuthStatusView refreshConnectionStatus() {
        return ibkrPortfolioService.refreshAuthStatus();
    }

    @GetMapping("/watchlists")
    public ResponseEntity<?> getWatchlists() {
        try {
            return ResponseEntity.ok(ibkrWatchlistService.getStoredWatchlistsOrBootstrap());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(e.getMessage()));
        }
    }

    @PostMapping("/watchlists/import")
    public ResponseEntity<?> importWatchlists() {
        try {
            return ResponseEntity.ok(ibkrWatchlistService.importUserWatchlists());
        } catch (IllegalStateException e) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(new ErrorResponse(e.getMessage()));
        }
    }

    private record ErrorResponse(String message) {
    }
}
