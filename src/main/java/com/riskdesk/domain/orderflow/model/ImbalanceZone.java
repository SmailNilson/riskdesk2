package com.riskdesk.domain.orderflow.model;

/**
 * A stacked-imbalance zone within a footprint bar: at least three consecutive price
 * buckets flagged with a diagonal imbalance on the same side. Stacked buy zones mark
 * aggressive-buyer support; stacked sell zones mark aggressive-seller resistance.
 *
 * @param fromPrice lowest flagged bucket (lower bound)
 * @param toPrice   highest flagged bucket (lower bound)
 * @param buckets   number of consecutive flagged buckets in the zone
 */
public record ImbalanceZone(double fromPrice, double toPrice, int buckets) {}
