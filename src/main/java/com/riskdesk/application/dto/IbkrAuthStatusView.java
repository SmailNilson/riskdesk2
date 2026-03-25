package com.riskdesk.application.dto;

public record IbkrAuthStatusView(
    boolean authenticated,
    boolean connected,
    boolean established,
    boolean competing,
    String endpoint,
    String message
) {
}
