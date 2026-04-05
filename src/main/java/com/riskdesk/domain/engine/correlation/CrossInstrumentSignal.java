package com.riskdesk.domain.engine.correlation;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Immutable domain event emitted by {@link CrossInstrumentCorrelationEngine} when both
 * the leader (MCL breakout) and the follower (MNQ VWAP rejection) conditions are met
 * within the correlation time window.
 *
 * <p>This record is published via Spring {@code ApplicationEventPublisher} by the
 * application-layer orchestrator ({@code CrossInstrumentAlertService}) and consumed
 * by the WebSocket broadcaster and the alert store.
 *
 * <p>All price fields carry BigDecimal precision consistent with the rest of the domain.
 */
public record CrossInstrumentSignal(

        /**
         * The instrument whose breakout acted as the leading signal (always "MCL").
         */
        String leaderInstrument,

        /**
         * The instrument whose VWAP rejection confirmed the signal (always "MNQ").
         */
        String followerInstrument,

        /**
         * The 5m close price of MCL at the time the breakout was detected.
         */
        BigDecimal leaderBreakoutPrice,

        /**
         * The N-period channel high that MCL closed above (the resistance level broken).
         */
        BigDecimal leaderResistanceLevel,

        /**
         * The VWAP value for MNQ at the time of the rejection candle.
         */
        BigDecimal followerVwap,

        /**
         * The 5m close price of MNQ at the time of the rejection (should be < vwap).
         */
        BigDecimal followerClosePrice,

        /**
         * Elapsed seconds between the MCL breakout and the MNQ VWAP rejection.
         * Useful for gauging the speed of cross-instrument transmission.
         */
        long lagSeconds,

        /**
         * UTC timestamp when the MNQ VWAP rejection candle closed (confirmation time).
         */
        Instant confirmedAt
) {}
