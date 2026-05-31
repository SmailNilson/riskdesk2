package com.riskdesk.domain.orderflow.perfectsetup;

import com.riskdesk.domain.model.Instrument;

import java.time.Instant;
import java.util.List;

/**
 * Fully-gathered, infrastructure-free snapshot fed to {@link PerfectSetupDetector}.
 * The application layer maps live ports/read-services into this record so the
 * detector stays a pure function.
 *
 * <p>All boxed fields may be {@code null} (cold start / missing data); the
 * detector defends against absence by failing the relevant axes.</p>
 *
 * @param instrument       the futures instrument
 * @param price            latest price, or {@code null}
 * @param tickSize         instrument tick size (points)
 * @param atr              ATR(14) on 5m candles (points), or {@code null}
 * @param distType         most recent regime: {@code ACCUMULATION} / {@code DISTRIBUTION} / {@code null}
 * @param distConf         confidence (0-100) of {@code distType}, or {@code null}
 * @param cycleType        most recent smart-money cycle type ({@code BULLISH_CYCLE} / {@code BEARISH_CYCLE}), or {@code null}
 * @param absDominantSide  dominant absorption side over the window: {@code BULL} / {@code BEAR} / {@code MIX} / {@code null}
 * @param absMaxScore      max absorption score over the window
 * @param recentAbsMagnitudes absolute aggressive-delta magnitudes, newest first (for the climax-decay note)
 * @param iceberg          nearest icebergs each side
 * @param flash            latest flash-crash phase
 * @param vwap             session VWAP, or {@code null}
 * @param vwapLowerBand    VWAP lower band, or {@code null}
 * @param vwapUpperBand    VWAP upper band, or {@code null}
 * @param bbPct            Bollinger %B in [0,1], or {@code null}
 * @param now              evaluation time (UTC)
 */
public record PerfectSetupInputs(
    Instrument instrument,
    Double price,
    double tickSize,
    Double atr,
    String distType,
    Integer distConf,
    String cycleType,
    String absDominantSide,
    double absMaxScore,
    List<Long> recentAbsMagnitudes,
    IcebergContext iceberg,
    FlashCrashContext flash,
    Double vwap,
    Double vwapLowerBand,
    Double vwapUpperBand,
    Double bbPct,
    Instant now
) {
    public PerfectSetupInputs {
        if (instrument == null) throw new IllegalArgumentException("instrument is required");
        if (now == null) throw new IllegalArgumentException("now is required");
        recentAbsMagnitudes = recentAbsMagnitudes == null ? List.of() : List.copyOf(recentAbsMagnitudes);
        iceberg = iceberg == null ? IcebergContext.empty() : iceberg;
        flash = flash == null ? FlashCrashContext.none() : flash;
    }
}
