package com.riskdesk.application.service.strategy;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.service.AbsorptionCache;
import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.domain.engine.strategy.wtx.WtxEnrichmentSnapshot;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.orderflow.model.AbsorptionSignal;
import com.riskdesk.domain.shared.TradingSessionResolver;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Optional;

/**
 * Builds a {@link WtxEnrichmentSnapshot} from existing indicator infrastructure.
 * All data is INFORMATIONAL — never used to filter or block WTX signals.
 * Order flow fields are computed first as they are most time-sensitive.
 */
@Component
public class WtxEnrichmentBuilder {

    private final IndicatorService indicatorService;
    private final AbsorptionCache absorptionCache;

    public WtxEnrichmentBuilder(IndicatorService indicatorService, AbsorptionCache absorptionCache) {
        this.indicatorService = indicatorService;
        this.absorptionCache = absorptionCache;
    }

    public WtxEnrichmentSnapshot build(String instrumentName, String timeframe) {
        Instrument instrument;
        try {
            instrument = Instrument.valueOf(instrumentName.replace("-", "_"));
        } catch (IllegalArgumentException e) {
            return WtxEnrichmentSnapshot.empty();
        }

        IndicatorSnapshot snap;
        try {
            snap = indicatorService.computeSnapshot(instrument, timeframe);
        } catch (Exception e) {
            return WtxEnrichmentSnapshot.empty();
        }

        // ── Order Flow ──────────────────────────────────────────────────────
        String deltaDirection = snap.deltaFlowBias(); // "BUYING" | "SELLING" | "NEUTRAL"
        BigDecimal deltaValue = snap.deltaFlow();
        String orderFlowSource = "CLV_ESTIMATED";

        // Absorption signal from in-memory cache
        Optional<AbsorptionSignal> absorption = absorptionCache.latest(instrument);
        String absorptionSignal = null;
        Double absorptionScore = null;
        if (absorption.isPresent()) {
            absorptionSignal = absorption.get().side().name(); // BULLISH_ABSORPTION or BEARISH_ABSORPTION
            absorptionScore = absorption.get().absorptionScore();
        }

        // ── Bollinger Bands ─────────────────────────────────────────────────
        BigDecimal bbPct = snap.bbPct();
        boolean bbExpanding = snap.bbTrendExpanding();

        // ── VWAP ────────────────────────────────────────────────────────────
        String priceVsVwap = null;
        BigDecimal vwapDistancePct = null;
        if (snap.vwap() != null && snap.lastPrice() != null) {
            BigDecimal price = snap.lastPrice();
            BigDecimal vwap = snap.vwap();
            int cmp = price.compareTo(vwap);
            priceVsVwap = cmp > 0 ? "ABOVE" : cmp < 0 ? "BELOW" : "AT";
            if (vwap.compareTo(BigDecimal.ZERO) != 0) {
                vwapDistancePct = price.subtract(vwap).abs()
                        .divide(vwap, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100));
            }
        }

        // ── SMC ──────────────────────────────────────────────────────────────
        String smcInternalBias = snap.internalBias();
        String smcSwingBias = snap.swingBias();

        // ── Order Block (nearest active OB) ─────────────────────────────────
        String nearestObType = null;
        BigDecimal nearestObDistancePct = null;
        if (snap.activeOrderBlocks() != null && !snap.activeOrderBlocks().isEmpty()
                && snap.lastPrice() != null) {
            BigDecimal price = snap.lastPrice();
            var bestOb = snap.activeOrderBlocks().stream()
                    .min(java.util.Comparator.comparingDouble(ob -> {
                        BigDecimal mid = ob.high().add(ob.low())
                                .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
                        return price.subtract(mid).abs().doubleValue();
                    }));
            if (bestOb.isPresent()) {
                var ob = bestOb.get();
                nearestObType = ob.type();
                BigDecimal mid = ob.high().add(ob.low())
                        .divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
                if (price.compareTo(BigDecimal.ZERO) != 0) {
                    nearestObDistancePct = price.subtract(mid).abs()
                            .divide(price, 6, RoundingMode.HALF_UP)
                            .multiply(BigDecimal.valueOf(100));
                }
            }
        }

        // ── CMF ──────────────────────────────────────────────────────────────
        BigDecimal cmf = snap.cmf();

        // ── Session ──────────────────────────────────────────────────────────
        Instant now = Instant.now();
        String sessionPhase = TradingSessionResolver.currentPhase(now).name();
        boolean inKillZone = TradingSessionResolver.isWithinKillZone(now);

        return new WtxEnrichmentSnapshot(
                deltaDirection, deltaValue, orderFlowSource, absorptionSignal, absorptionScore,
                bbPct, bbExpanding,
                priceVsVwap, vwapDistancePct,
                smcInternalBias, smcSwingBias,
                nearestObType, nearestObDistancePct,
                cmf,
                sessionPhase, inKillZone
        );
    }
}
