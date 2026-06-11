package com.riskdesk.domain.marketdata.model;

/**
 * Speed-of-tape measure over a trailing window of classified trade ticks.
 *
 * <p>Note on units: a "trade" here is an IBKR AllLast print, which pre-aggregates
 * same-price simultaneous executions — so trades/sec measures match-event intensity,
 * not literal order count.</p>
 *
 * @param trades          number of classified prints in the window
 * @param contracts       total contracts traded in the window
 * @param tradesPerSec    prints per second over the window
 * @param contractsPerSec contracts per second over the window
 */
public record TapeSpeed(long trades, long contracts, double tradesPerSec, double contractsPerSec) {}
