package com.riskdesk.application.service.analysis;

/**
 * Thrown by {@code AnalysisSnapshotAggregator} when one of the source ports
 * returned data older than the staleness budget. Translated to HTTP 503 by
 * the controller — the caller should retry shortly.
 */
public class StaleSnapshotException extends RuntimeException {
    public StaleSnapshotException(String message) {
        super(message);
    }
}
