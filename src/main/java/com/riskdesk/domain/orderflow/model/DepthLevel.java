package com.riskdesk.domain.orderflow.model;

/**
 * A single order book level (one side), as received from the L2 feed.
 * Levels are ordered best-first: bids descending from the best bid,
 * asks ascending from the best ask.
 */
public record DepthLevel(
    double price,
    long size,
    /** True when this level's size exceeds the wall threshold (N x average level size). */
    boolean wall
) {}
