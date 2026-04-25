package com.riskdesk.application.service.analysis.adapter;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.domain.analysis.model.SmcContext;
import com.riskdesk.domain.analysis.port.IndicatorSnapshotPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.vo.Timeframe;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the existing {@link IndicatorService} (DTO-based) to the
 * domain-side {@link IndicatorSnapshotPort}. Converts the wide DTO into the
 * two narrow domain models the scoring engine consumes.
 * <p>
 * The {@code IndicatorService} is already cached internally (see
 * {@code snapshotCache}) — we trust its as-of timestamp by stamping the result
 * with {@code Instant.now()} just after the call returns. If we add an
 * explicit {@code computedAt} field to {@code IndicatorSnapshot} later, we
 * should plumb it through here.
 */
@Component
public class IndicatorSnapshotAdapter implements IndicatorSnapshotPort {

    private final IndicatorService indicatorService;

    public IndicatorSnapshotAdapter(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    @Override
    public TimedIndicators indicatorsAsOf(Instrument instrument, Timeframe timeframe, Instant decisionAt) {
        IndicatorSnapshot src = indicatorService.computeSnapshot(instrument, timeframe.label());
        Instant asOf = Instant.now();

        var domain = new com.riskdesk.domain.analysis.model.IndicatorSnapshot(
            src.lastPrice(),
            doubleOf(src.rsi()),
            src.rsiSignal(),
            doubleOf(src.macdHistogram()),
            src.supertrendBullish(),
            doubleOf(src.vwap()),
            doubleOf(src.cmf()),
            doubleOf(src.bbPct()),
            src.bbTrendExpanding(),
            doubleOf(src.stochK()),
            doubleOf(src.stochD()),
            src.stochCrossover(),
            doubleOf(src.wtWt1()),
            doubleOf(src.wtWt2())
        );
        return new TimedIndicators(domain, asOf);
    }

    @Override
    public TimedSmc smcAsOf(Instrument instrument, Timeframe timeframe, Instant decisionAt) {
        IndicatorSnapshot src = indicatorService.computeSnapshot(instrument, timeframe.label());
        Instant asOf = Instant.now();

        Map<String, String> mr = new HashMap<>();
        if (src.multiResolutionBias() != null) {
            mr.put("swing50", src.multiResolutionBias().swing50());
            mr.put("swing25", src.multiResolutionBias().swing25());
            mr.put("swing9", src.multiResolutionBias().swing9());
            mr.put("internal5", src.multiResolutionBias().internal5());
            mr.put("micro1", src.multiResolutionBias().micro1());
        }

        List<SmcContext.ActiveOrderBlock> activeOBs = new ArrayList<>();
        if (src.activeOrderBlocks() != null) {
            for (var ob : src.activeOrderBlocks()) {
                activeOBs.add(new SmcContext.ActiveOrderBlock(
                    ob.type(),
                    doubleOrZero(ob.low()),
                    doubleOrZero(ob.high()),
                    doubleOrZero(ob.mid()),
                    ob.obLiveScore(),
                    ob.defended()));
            }
        }

        List<SmcContext.ActiveFairValueGap> activeFvgs = new ArrayList<>();
        if (src.activeFairValueGaps() != null) {
            for (var fvg : src.activeFairValueGaps()) {
                activeFvgs.add(new SmcContext.ActiveFairValueGap(
                    fvg.bias(),
                    doubleOrZero(fvg.bottom()),
                    doubleOrZero(fvg.top()),
                    fvg.fvgQualityScore()));
            }
        }

        List<SmcContext.RecentBreak> breaks = new ArrayList<>();
        if (src.recentBreaks() != null) {
            for (var br : src.recentBreaks()) {
                breaks.add(new SmcContext.RecentBreak(
                    br.type(), br.trend(),
                    doubleOrZero(br.level()),
                    br.breakConfidenceScore(),
                    br.confirmed()));
            }
        }

        SmcContext smc = new SmcContext(
            src.internalBias(),
            src.swingBias(),
            src.currentZone(),
            doubleOf(src.equilibriumLevel()),
            doubleOf(src.premiumZoneTop()),
            doubleOf(src.discountZoneBottom()),
            mr,
            doubleOf(src.strongHigh()),
            doubleOf(src.weakHigh()),
            doubleOf(src.strongLow()),
            doubleOf(src.weakLow()),
            src.lastBreakType(),
            activeOBs,
            activeFvgs,
            breaks
        );
        return new TimedSmc(smc, asOf);
    }

    private static Double doubleOf(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }

    private static double doubleOrZero(BigDecimal v) {
        return v == null ? 0.0 : v.doubleValue();
    }
}
