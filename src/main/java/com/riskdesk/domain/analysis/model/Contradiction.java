package com.riskdesk.domain.analysis.model;

/**
 * A detected conflict between signals across layers (or within one layer).
 * Each contradiction adds penalty to the directional bias confidence.
 */
public record Contradiction(String layerA, String layerB, String description) {
}
