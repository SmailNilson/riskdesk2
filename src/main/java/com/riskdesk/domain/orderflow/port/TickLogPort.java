package com.riskdesk.domain.orderflow.port;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;

/**
 * Domain port for persisting raw tick-by-tick trades and depth snapshots.
 * Used by the Tick Logger (UC-OF-008) to collect calibration data for
 * absorption, spoofing, and flash crash detection thresholds.
 */
public interface TickLogPort {

    /**
     * Persist a single classified trade tick.
     *
     * @param instrument  the instrument that was traded
     * @param price       execution price
     * @param size        number of contracts
     * @param bidPrice    best bid at time of trade (for Lee-Ready classification audit)
     * @param askPrice    best ask at time of trade
     * @param classification BUY, SELL, or UNCLASSIFIED (Lee-Ready result)
     * @param exchange    exchange where the trade occurred (nullable)
     * @param timestamp   trade timestamp (UTC)
     */
    void saveTick(Instrument instrument, double price, long size,
                  double bidPrice, double askPrice,
                  String classification, String exchange, Instant timestamp);

    /**
     * Persist a depth snapshot (aggregated order book state).
     *
     * @param instrument      the instrument
     * @param totalBidSize    sum of all bid level sizes
     * @param totalAskSize    sum of all ask level sizes
     * @param depthImbalance  (totalBid - totalAsk) / (totalBid + totalAsk)
     * @param bestBid         best bid price
     * @param bestAsk         best ask price
     * @param spread          bestAsk - bestBid
     * @param timestamp       snapshot timestamp (UTC)
     */
    void saveDepthSnapshot(Instrument instrument,
                           long totalBidSize, long totalAskSize, double depthImbalance,
                           double bestBid, double bestAsk, double spread,
                           Instant timestamp);

    /**
     * Delete ticks and depth snapshots older than the given cutoff.
     * Called by the scheduled purge job (30-day retention).
     *
     * @param cutoff  delete records with timestamp before this instant
     */
    void purgeOlderThan(Instant cutoff);
}
