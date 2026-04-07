package com.riskdesk.domain.marketdata.model;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public final class SyntheticDollarIndexCalculator {

    public static final long MAX_COMPONENT_SKEW_SECONDS = 15L;
    private static final MathContext CALC_CONTEXT = MathContext.DECIMAL64;
    private static final BigDecimal DXY_FACTOR = new BigDecimal("50.14348112", CALC_CONTEXT);
    private static final BigDecimal TWO = new BigDecimal("2");

    // -------------------------------------------------------------------------
    // Sealed outcome types (Java 21)
    // -------------------------------------------------------------------------

    public sealed interface DxyCalculationOutcome {
        Map<FxPair, EvaluatedComponent> components();
        long maxSkewSeconds();

        record CompleteSnapshot(
            DxySnapshot snapshot,
            Map<FxPair, EvaluatedComponent> components,
            long maxSkewSeconds
        ) implements DxyCalculationOutcome {}

        record IncompleteResult(
            Map<FxPair, EvaluatedComponent> components,
            long maxSkewSeconds,
            String message
        ) implements DxyCalculationOutcome {}
    }

    // -------------------------------------------------------------------------
    // Sealed pricing result per component
    // -------------------------------------------------------------------------

    public sealed interface PricingResult {
        record MidPrice(BigDecimal value) implements PricingResult {}
        record LastPrice(BigDecimal value) implements PricingResult {}
        record InvalidPrice(String reason) implements PricingResult {}
    }

    // -------------------------------------------------------------------------
    // Main calculation
    // -------------------------------------------------------------------------

    public DxyCalculationOutcome calculate(Map<FxPair, FxQuoteSnapshot> quotes) {
        Map<FxPair, EvaluatedComponent> evaluated = new EnumMap<>(FxPair.class);
        for (FxPair pair : FxPair.values()) {
            evaluated.put(pair, evaluate(pair, quotes.get(pair)));
        }

        var validComponents = evaluated.values().stream()
            .filter(c -> c.pricing() instanceof PricingResult.MidPrice || c.pricing() instanceof PricingResult.LastPrice)
            .sorted(Comparator.comparing(EvaluatedComponent::timestamp))
            .toList();

        Map<FxPair, EvaluatedComponent> immutable = Map.copyOf(evaluated);

        if (validComponents.size() != FxPair.values().length) {
            return new DxyCalculationOutcome.IncompleteResult(
                immutable, 0L, "One or more FX components are missing or invalid.");
        }

        Instant oldest = validComponents.getFirst().timestamp();
        Instant newest = validComponents.getLast().timestamp();
        long maxSkew = Duration.between(oldest, newest).abs().getSeconds();

        if (maxSkew > MAX_COMPONENT_SKEW_SECONDS) {
            return new DxyCalculationOutcome.IncompleteResult(
                immutable, maxSkew,
                "FX components are too far apart in time to compute a coherent synthetic DXY.");
        }

        BigDecimal dxyValue = computeValue(evaluated).setScale(6, RoundingMode.HALF_UP);

        DxySnapshot snapshot = new DxySnapshot(
            oldest,
            effectivePrice(evaluated.get(FxPair.EURUSD)),
            effectivePrice(evaluated.get(FxPair.USDJPY)),
            effectivePrice(evaluated.get(FxPair.GBPUSD)),
            effectivePrice(evaluated.get(FxPair.USDCAD)),
            effectivePrice(evaluated.get(FxPair.USDSEK)),
            effectivePrice(evaluated.get(FxPair.USDCHF)),
            dxyValue,
            "IBKR_SYNTHETIC",
            true
        );
        return new DxyCalculationOutcome.CompleteSnapshot(snapshot, immutable, maxSkew);
    }

    /**
     * Computes a DXY snapshot from previous-day close prices (IBKR TickType.CLOSE).
     * Skips bid/ask evaluation and time-skew checks — close prices are inherently synchronised.
     * Returns empty if any close price is missing or non-positive.
     */
    public Optional<DxySnapshot> calculateFromCloses(Map<FxPair, FxQuoteSnapshot> quotes) {
        Map<FxPair, BigDecimal> closes = new EnumMap<>(FxPair.class);
        for (FxPair pair : FxPair.values()) {
            FxQuoteSnapshot q = quotes.get(pair);
            if (q == null || q.close() == null || q.close().compareTo(BigDecimal.ZERO) <= 0) {
                return Optional.empty();
            }
            closes.put(pair, q.close());
        }

        BigDecimal dxyValue = multiply(
            DXY_FACTOR,
            pow(closes.get(FxPair.EURUSD), -0.576),
            pow(closes.get(FxPair.USDJPY), 0.136),
            pow(closes.get(FxPair.GBPUSD), -0.119),
            pow(closes.get(FxPair.USDCAD), 0.091),
            pow(closes.get(FxPair.USDSEK), 0.042),
            pow(closes.get(FxPair.USDCHF), 0.036)
        ).setScale(6, RoundingMode.HALF_UP);

        return Optional.of(new DxySnapshot(
            Instant.EPOCH, // close has no meaningful timestamp
            closes.get(FxPair.EURUSD),
            closes.get(FxPair.USDJPY),
            closes.get(FxPair.GBPUSD),
            closes.get(FxPair.USDCAD),
            closes.get(FxPair.USDSEK),
            closes.get(FxPair.USDCHF),
            dxyValue,
            "IBKR_CLOSE",
            true
        ));
    }

    // -------------------------------------------------------------------------
    // Internals
    // -------------------------------------------------------------------------

    private BigDecimal effectivePrice(EvaluatedComponent component) {
        return switch (component.pricing()) {
            case PricingResult.MidPrice mid -> mid.value();
            case PricingResult.LastPrice last -> last.value();
            case PricingResult.InvalidPrice ip -> null;
        };
    }

    private BigDecimal computeValue(Map<FxPair, EvaluatedComponent> evaluated) {
        return multiply(
            DXY_FACTOR,
            pow(effectivePrice(evaluated.get(FxPair.EURUSD)), -0.576),
            pow(effectivePrice(evaluated.get(FxPair.USDJPY)), 0.136),
            pow(effectivePrice(evaluated.get(FxPair.GBPUSD)), -0.119),
            pow(effectivePrice(evaluated.get(FxPair.USDCAD)), 0.091),
            pow(effectivePrice(evaluated.get(FxPair.USDSEK)), 0.042),
            pow(effectivePrice(evaluated.get(FxPair.USDCHF)), 0.036)
        );
    }

    private BigDecimal multiply(BigDecimal... factors) {
        return Arrays.stream(factors).reduce(BigDecimal.ONE, (a, b) -> a.multiply(b, CALC_CONTEXT));
    }

    private BigDecimal pow(BigDecimal value, double exponent) {
        return BigDecimal.valueOf(StrictMath.pow(value.doubleValue(), exponent));
    }

    private EvaluatedComponent evaluate(FxPair pair, FxQuoteSnapshot quote) {
        if (quote == null) {
            return new EvaluatedComponent(pair, null, null, null, null,
                new PricingResult.InvalidPrice("missing quote"));
        }
        if (quote.timestamp() == null) {
            return new EvaluatedComponent(pair, quote.bid(), quote.ask(), quote.last(), null,
                new PricingResult.InvalidPrice("missing timestamp"));
        }

        if (positive(quote.bid()) && positive(quote.ask())) {
            BigDecimal midpoint = quote.bid().add(quote.ask(), CALC_CONTEXT)
                .divide(TWO, 8, RoundingMode.HALF_UP);
            return new EvaluatedComponent(pair, quote.bid(), quote.ask(), quote.last(),
                quote.timestamp(), new PricingResult.MidPrice(midpoint));
        }

        if (positive(quote.last())) {
            return new EvaluatedComponent(pair, quote.bid(), quote.ask(), quote.last(),
                quote.timestamp(), new PricingResult.LastPrice(quote.last()));
        }

        return new EvaluatedComponent(pair, quote.bid(), quote.ask(), quote.last(),
            quote.timestamp(), new PricingResult.InvalidPrice("no positive bid/ask midpoint or last price"));
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }

    // -------------------------------------------------------------------------
    // Component record
    // -------------------------------------------------------------------------

    public record EvaluatedComponent(
        FxPair pair,
        BigDecimal bid,
        BigDecimal ask,
        BigDecimal last,
        Instant timestamp,
        PricingResult pricing
    ) {
        public BigDecimal effectivePrice() {
            return switch (pricing) {
                case PricingResult.MidPrice mid -> mid.value();
                case PricingResult.LastPrice lp -> lp.value();
                case PricingResult.InvalidPrice ip -> null;
            };
        }

        public String pricingMethod() {
            return switch (pricing) {
                case PricingResult.MidPrice m -> "MID";
                case PricingResult.LastPrice l -> "LAST";
                case PricingResult.InvalidPrice ip -> null;
            };
        }

        public boolean valid() {
            return !(pricing instanceof PricingResult.InvalidPrice);
        }

        public String message() {
            return pricing instanceof PricingResult.InvalidPrice ip ? ip.reason() : null;
        }
    }
}
