package com.riskdesk.domain.marketdata.model;

import com.riskdesk.domain.marketdata.model.SyntheticDollarIndexCalculator.DxyCalculationOutcome;
import com.riskdesk.domain.marketdata.model.SyntheticDollarIndexCalculator.DxyCalculationOutcome.CompleteSnapshot;
import com.riskdesk.domain.marketdata.model.SyntheticDollarIndexCalculator.DxyCalculationOutcome.IncompleteResult;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SyntheticDollarIndexCalculatorTest {

    private final SyntheticDollarIndexCalculator calculator = new SyntheticDollarIndexCalculator();

    @Test
    void calculate_usesMidpointFormulaForCompleteQuotes() {
        Instant ts = Instant.parse("2026-04-01T10:00:00Z");
        Map<FxPair, FxQuoteSnapshot> quotes = new EnumMap<>(FxPair.class);
        quotes.put(FxPair.EURUSD, quote(FxPair.EURUSD, "1.0810", "1.0812", "1.0815", ts));
        quotes.put(FxPair.USDJPY, quote(FxPair.USDJPY, "149.20", "149.24", "149.30", ts));
        quotes.put(FxPair.GBPUSD, quote(FxPair.GBPUSD, "1.2620", "1.2624", "1.2628", ts));
        quotes.put(FxPair.USDCAD, quote(FxPair.USDCAD, "1.3510", "1.3514", "1.3518", ts));
        quotes.put(FxPair.USDSEK, quote(FxPair.USDSEK, "10.4800", "10.4810", "10.4820", ts));
        quotes.put(FxPair.USDCHF, quote(FxPair.USDCHF, "0.9020", "0.9023", "0.9026", ts));

        DxyCalculationOutcome result = calculator.calculate(quotes);

        assertInstanceOf(CompleteSnapshot.class, result);
        CompleteSnapshot complete = (CompleteSnapshot) result;
        assertEquals("MID", complete.components().get(FxPair.EURUSD).pricingMethod());
        assertEquals(new BigDecimal("1.08110000"), complete.components().get(FxPair.EURUSD).effectivePrice());

        BigDecimal expected = expectedDxy(
            "1.08110000", "149.22000000", "1.26220000", "1.35120000", "10.48050000", "0.90215000"
        );
        assertEquals(0, expected.compareTo(complete.snapshot().dxyValue()));
        assertEquals(6, complete.snapshot().dxyValue().scale());
    }

    @Test
    void calculate_fallsBackToLastWhenBidAskAreIncomplete() {
        Instant ts = Instant.parse("2026-04-01T10:00:00Z");
        Map<FxPair, FxQuoteSnapshot> quotes = completeQuotes(ts);
        quotes.put(FxPair.USDJPY, new FxQuoteSnapshot(
            FxPair.USDJPY,
            new BigDecimal("149.20"),
            null,
            new BigDecimal("149.31"),
            null,
            ts,
            "LIVE_PROVIDER"
        ));

        DxyCalculationOutcome result = calculator.calculate(quotes);

        assertInstanceOf(CompleteSnapshot.class, result);
        assertEquals("LAST", result.components().get(FxPair.USDJPY).pricingMethod());
        assertEquals(0, new BigDecimal("149.31").compareTo(result.components().get(FxPair.USDJPY).effectivePrice()));
    }

    @Test
    void calculate_rejectsMissingComponent() {
        Map<FxPair, FxQuoteSnapshot> quotes = completeQuotes(Instant.parse("2026-04-01T10:00:00Z"));
        quotes.remove(FxPair.USDCHF);

        DxyCalculationOutcome result = calculator.calculate(quotes);

        assertInstanceOf(IncompleteResult.class, result);
        assertEquals("missing quote", result.components().get(FxPair.USDCHF).message());
    }

    @Test
    void calculate_rejectsInvalidNonPositiveComponent() {
        Map<FxPair, FxQuoteSnapshot> quotes = completeQuotes(Instant.parse("2026-04-01T10:00:00Z"));
        quotes.put(FxPair.GBPUSD, new FxQuoteSnapshot(
            FxPair.GBPUSD,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            null,
            Instant.parse("2026-04-01T10:00:00Z"),
            "LIVE_PROVIDER"
        ));

        DxyCalculationOutcome result = calculator.calculate(quotes);

        assertInstanceOf(IncompleteResult.class, result);
        assertEquals("no positive bid/ask midpoint or last price", result.components().get(FxPair.GBPUSD).message());
    }

    @Test
    void calculate_rejectsSkewBeyondFifteenSeconds() {
        Map<FxPair, FxQuoteSnapshot> quotes = completeQuotes(Instant.parse("2026-04-01T10:00:00Z"));
        quotes.put(FxPair.USDCHF, quote(
            FxPair.USDCHF,
            "0.9020",
            "0.9023",
            "0.9025",
            Instant.parse("2026-04-01T10:00:20Z")
        ));

        DxyCalculationOutcome result = calculator.calculate(quotes);

        assertInstanceOf(IncompleteResult.class, result);
        assertEquals(20L, result.maxSkewSeconds());
    }

    private Map<FxPair, FxQuoteSnapshot> completeQuotes(Instant ts) {
        Map<FxPair, FxQuoteSnapshot> quotes = new EnumMap<>(FxPair.class);
        quotes.put(FxPair.EURUSD, quote(FxPair.EURUSD, "1.0810", "1.0812", "1.0815", ts));
        quotes.put(FxPair.USDJPY, quote(FxPair.USDJPY, "149.20", "149.24", "149.30", ts));
        quotes.put(FxPair.GBPUSD, quote(FxPair.GBPUSD, "1.2620", "1.2624", "1.2628", ts));
        quotes.put(FxPair.USDCAD, quote(FxPair.USDCAD, "1.3510", "1.3514", "1.3518", ts));
        quotes.put(FxPair.USDSEK, quote(FxPair.USDSEK, "10.4800", "10.4810", "10.4820", ts));
        quotes.put(FxPair.USDCHF, quote(FxPair.USDCHF, "0.9020", "0.9023", "0.9026", ts));
        return quotes;
    }

    private FxQuoteSnapshot quote(FxPair pair, String bid, String ask, String last, Instant timestamp) {
        return new FxQuoteSnapshot(
            pair,
            new BigDecimal(bid),
            new BigDecimal(ask),
            new BigDecimal(last),
            null,
            timestamp,
            "LIVE_PROVIDER"
        );
    }

    private BigDecimal expectedDxy(String eurusd,
                                   String usdjpy,
                                   String gbpusd,
                                   String usdcad,
                                   String usdsek,
                                   String usdchf) {
        double value = 50.14348112
            * StrictMath.pow(Double.parseDouble(eurusd), -0.576)
            * StrictMath.pow(Double.parseDouble(usdjpy), 0.136)
            * StrictMath.pow(Double.parseDouble(gbpusd), -0.119)
            * StrictMath.pow(Double.parseDouble(usdcad), 0.091)
            * StrictMath.pow(Double.parseDouble(usdsek), 0.042)
            * StrictMath.pow(Double.parseDouble(usdchf), 0.036);
        return BigDecimal.valueOf(value).setScale(6, RoundingMode.HALF_UP);
    }
}
