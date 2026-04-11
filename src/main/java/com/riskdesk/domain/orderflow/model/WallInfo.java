package com.riskdesk.domain.orderflow.model;

/**
 * Information about a detected wall (large order cluster) in the order book.
 * A wall is a single price level with size > wallThreshold × average level size.
 */
public record WallInfo(
    double price,
    long size,
    int levelIndex
) {}
