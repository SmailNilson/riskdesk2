package com.riskdesk.application.service;

import com.riskdesk.application.dto.MacroCorrelationSnapshot;
import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.domain.marketdata.model.DxySnapshot;
import com.riskdesk.domain.marketdata.model.FxComponentContribution;
import com.riskdesk.domain.model.AssetClass;
import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Intermarket values must come from the internal IBKR -> PostgreSQL pipeline only.
 */
@Service
public class MentorIntermarketService {

    private final DxyMarketService dxyMarketService;

    public MentorIntermarketService(DxyMarketService dxyMarketService) {
        this.dxyMarketService = dxyMarketService;
    }

    public MentorIntermarketSnapshot current(Instrument focusInstrument) {
        if (focusInstrument != null && !focusInstrument.isDollarSensitive()) {
            return new MentorIntermarketSnapshot(
                null,
                "NOT_REQUIRED",
                null,
                null,
                null,
                null,
                "UNAVAILABLE"
            );
        }

        DxySignal dxySignal = loadDxySignal();
        return new MentorIntermarketSnapshot(
            dxySignal.pctChange(),
            dxySignal.trend(),
            dxySignal.breakdown(),
            null,
            null,
            null,
            dxySignal.available() ? "DXY_AVAILABLE" : "UNAVAILABLE"
        );
    }

    /**
     * Returns an asset-class-aware macro correlation snapshot.
     * Currently only DXY data is populated; other correlations return null with
     * dataAvailability = "DXY_ONLY" until IBKR subscriptions for VIX/SI/US10Y are added.
     */
    public MacroCorrelationSnapshot currentForAssetClass(Instrument instrument, AssetClass assetClass) {
        if (assetClass == null || !instrument.isDollarSensitive()) {
            return new MacroCorrelationSnapshot(
                null, "NOT_REQUIRED", null,
                null, null, null,
                null, null,
                "NOT_REQUIRED", "NOT_REQUIRED"
            );
        }

        DxySignal dxy = loadDxySignal();

        String sectorLeaderSymbol = assetClass.sectorLeaderSymbol().orElse(null);
        // VIX and sector leader data will be populated in a future slice when IBKR subscriptions are added
        Double vixPctChange = null;
        Double sectorLeaderPctChange = null;
        String sectorLeaderTrend = null;
        Double us10yYieldPctChange = null;

        String dataAvailability = dxy.available() ? "DXY_ONLY" : "UNAVAILABLE";

        return new MacroCorrelationSnapshot(
            dxy.available() ? dxy.pctChange().doubleValue() : null,
            dxy.available() ? dxy.trend() : "UNAVAILABLE",
            dxy.available() ? dxy.breakdown() : null,
            sectorLeaderSymbol != null ? sectorLeaderSymbol + "1!" : null,
            sectorLeaderPctChange,
            sectorLeaderTrend,
            vixPctChange,
            us10yYieldPctChange,
            "UNAVAILABLE",
            dataAvailability
        );
    }

    private DxySignal loadDxySignal() {
        Optional<DxySnapshot> latest = dxyMarketService.latestSnapshot();
        if (latest.isEmpty() || latest.get().dxyValue() == null) {
            return DxySignal.unavailable();
        }

        DxySnapshot current = latest.get();
        DxySnapshot baseline = dxyMarketService.findBaselineSnapshot(current.timestamp())
            .orElse(null);

        if (baseline == null || baseline.dxyValue() == null || baseline.dxyValue().compareTo(BigDecimal.ZERO) <= 0) {
            return DxySignal.unavailable();
        }

        BigDecimal pct = current.dxyValue().subtract(baseline.dxyValue())
            .divide(baseline.dxyValue(), 6, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(100))
            .setScale(3, RoundingMode.HALF_UP);

        List<FxComponentContribution> breakdown = dxyMarketService.computeComponentContributions(current, baseline);

        return new DxySignal(
            pct.doubleValue(),
            trendFor(current.dxyValue().compareTo(baseline.dxyValue())),
            breakdown,
            true
        );
    }

    private String trendFor(int comparison) {
        if (comparison > 0) {
            return "BULLISH";
        }
        if (comparison < 0) {
            return "BEARISH";
        }
        return "FLAT";
    }

    private record DxySignal(Double pctChange, String trend, List<FxComponentContribution> breakdown, boolean available) {
        private static DxySignal unavailable() {
            return new DxySignal(null, "UNAVAILABLE", null, false);
        }
    }
}
