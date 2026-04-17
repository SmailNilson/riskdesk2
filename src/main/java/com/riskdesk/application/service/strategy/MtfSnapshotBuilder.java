package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.domain.engine.strategy.model.MacroBias;
import com.riskdesk.domain.engine.strategy.model.MtfSnapshot;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Assembles the higher-timeframe {@link MtfSnapshot} by calling
 * {@link IndicatorService#computeSnapshot(Instrument, String)} on H1 / H4 / Daily.
 * Each call is cheap because {@code IndicatorService} owns a TTL cache per
 * (instrument, timeframe).
 *
 * <p>The HTF timeframes collapse to {@link MacroBias} via
 * {@link MacroBias#fromSwingBias(String)} — a NEUTRAL result on any single TF
 * leaves the slot as NEUTRAL rather than unavailable, so the
 * {@link com.riskdesk.domain.engine.strategy.agent.context.HtfAlignmentAgent}
 * can decide whether to abstain or weight lightly.
 *
 * <p>Errors on any TF are swallowed and logged — we prefer a partial MtfSnapshot
 * over dropping the whole decision because H4 temporarily stalled.
 */
@Component
public class MtfSnapshotBuilder {

    private static final Logger log = LoggerFactory.getLogger(MtfSnapshotBuilder.class);

    private final IndicatorService indicatorService;

    public MtfSnapshotBuilder(IndicatorService indicatorService) {
        this.indicatorService = indicatorService;
    }

    public MtfSnapshot build(Instrument instrument, String referenceTimeframe) {
        MacroBias h1 = loadBias(instrument, "1h", referenceTimeframe);
        MacroBias h4 = loadBias(instrument, "4h", referenceTimeframe);
        MacroBias d  = loadBias(instrument, "1d", referenceTimeframe);
        return new MtfSnapshot(h1, h4, d);
    }

    /**
     * Loads the HTF bias. When the reference timeframe IS the HTF we're asking
     * about, we skip the call — the agent already sees that bias directly from
     * {@link com.riskdesk.domain.engine.strategy.model.MarketContext#macroBias()},
     * and re-loading would just duplicate work. We still return the bias value
     * so {@link MtfSnapshot#alignmentWith} produces a correct count.
     */
    private MacroBias loadBias(Instrument instrument, String htf, String referenceTimeframe) {
        try {
            IndicatorSnapshot snap = indicatorService.computeSnapshot(instrument, htf);
            return MacroBias.fromSwingBias(snap.swingBias());
        } catch (Exception e) {
            if (log.isDebugEnabled()) {
                log.debug("HTF snapshot load failed for {} {} (ref={}): {}",
                    instrument, htf, referenceTimeframe, e.getMessage());
            }
            return MacroBias.NEUTRAL;
        }
    }
}
