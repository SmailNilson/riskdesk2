package com.riskdesk.domain.analysis.model;

/**
 * A single contribution to a layer score, kept for explainability.
 *
 * @param name        short identifier (e.g. "multiResBias", "obLiveScore")
 * @param contribution signed value added to the layer score
 * @param rationale    human-readable explanation, surfaced in the UI factor list
 */
public record ScoreComponent(String name, double contribution, String rationale) {
}
