package com.riskdesk.domain.engine.playbook.model;

public record ChecklistItem(
    int step,
    String label,
    ChecklistStatus status,
    String detail
) {}
