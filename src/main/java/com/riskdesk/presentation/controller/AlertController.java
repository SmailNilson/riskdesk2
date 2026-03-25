package com.riskdesk.presentation.controller;

import com.riskdesk.application.service.AlertService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/alerts")
@CrossOrigin(origins = "*")
public class AlertController {

    private final AlertService alertService;

    public AlertController(AlertService alertService) {
        this.alertService = alertService;
    }

    @GetMapping("/recent")
    public List<Map<String, Object>> getRecentAlerts() {
        return alertService.getRecentAlerts();
    }
}
