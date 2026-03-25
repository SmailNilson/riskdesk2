package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.presentation.dto.IbkrAuthStatusView;
import com.riskdesk.presentation.dto.IbkrPortfolioSnapshot;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ibkr")
@CrossOrigin(origins = "*")
public class IbkrPortfolioController {

    private final IbkrPortfolioService ibkrPortfolioService;

    public IbkrPortfolioController(IbkrPortfolioService ibkrPortfolioService) {
        this.ibkrPortfolioService = ibkrPortfolioService;
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
}
