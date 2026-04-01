package com.riskdesk.application.service;

import com.riskdesk.application.dto.MentorIntermarketSnapshot;
import com.riskdesk.domain.marketdata.model.DxySnapshot;
import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
                "UNAVAILABLE"
            );
        }

        DxySignal dxySignal = loadDxySignal();
        return new MentorIntermarketSnapshot(
            dxySignal.pctChange(),
            dxySignal.trend(),
            null,
            null,
            null,
            dxySignal.available() ? "DXY_AVAILABLE" : "UNAVAILABLE"
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

        return new DxySignal(
            pct.doubleValue(),
            trendFor(current.dxyValue().compareTo(baseline.dxyValue())),
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

    private record DxySignal(Double pctChange, String trend, boolean available) {
        private static DxySignal unavailable() {
            return new DxySignal(null, "UNAVAILABLE", false);
        }
    }
}
