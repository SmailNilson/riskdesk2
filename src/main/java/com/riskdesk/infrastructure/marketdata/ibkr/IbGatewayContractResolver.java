package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types.SecType;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IbGatewayContractResolver {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayContractResolver.class);
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final MathContext PRICE_CONTEXT = MathContext.DECIMAL64;
    private static final Map<Instrument, IbGatewayContractSelectionPolicy> POLICIES = Map.of(
        Instrument.MGC, new IbGatewayContractSelectionPolicy(35, 3, IbGatewayContractSelectionPreference.NEAREST_TRADABLE),
        Instrument.MCL, new IbGatewayContractSelectionPolicy(20, 3, IbGatewayContractSelectionPreference.NEAREST_TRADABLE),
        Instrument.E6, new IbGatewayContractSelectionPolicy(7, 3, IbGatewayContractSelectionPreference.LIQUIDITY_AWARE),
        Instrument.MNQ, new IbGatewayContractSelectionPolicy(7, 3, IbGatewayContractSelectionPreference.LIQUIDITY_AWARE),
        Instrument.DXY, new IbGatewayContractSelectionPolicy(7, 2, IbGatewayContractSelectionPreference.LIQUIDITY_AWARE)
    );

    private final IbGatewayNativeClient nativeClient;
    private final Clock clock;
    private final Map<Instrument, CachedResolution> cache = new ConcurrentHashMap<>();

    @Autowired
    public IbGatewayContractResolver(IbGatewayNativeClient nativeClient) {
        this(nativeClient, Clock.systemUTC());
    }

    IbGatewayContractResolver(IbGatewayNativeClient nativeClient, Clock clock) {
        this.nativeClient = nativeClient;
        this.clock = clock;
    }

    public Optional<IbGatewayResolvedContract> resolve(Instrument instrument) {
        CachedResolution cached = cache.get(instrument);
        if (cached != null && cached.isFresh(clock)) {
            return Optional.of(cached.resolved());
        }

        Optional<IbGatewayResolvedContract> refreshed = refresh(instrument);
        if (refreshed.isPresent()) {
            return refreshed;
        }

        if (cached != null) {
            return Optional.of(cached.resolved());
        }

        Optional<IbGatewayResolvedContract> fallback = preconfigured(instrument);
        fallback.ifPresent(resolved -> cache.put(instrument, new CachedResolution(resolved, clock.instant())));
        return fallback;
    }

    public Optional<IbGatewayResolvedContract> refresh(Instrument instrument) {
        List<ContractDetails> details = List.of();
        for (Contract query : buildQueries(instrument)) {
            details = nativeClient.requestContractDetails(query);
            if (!details.isEmpty()) {
                break;
            }
        }

        if (details.isEmpty()) {
            return Optional.empty();
        }

        Optional<IbGatewayResolvedContract> resolved = selectContract(instrument, details);
        resolved.ifPresent(value -> cache.put(instrument, new CachedResolution(value, clock.instant())));
        return resolved;
    }

    public void clearCache() {
        cache.clear();
    }

    public Optional<Instrument> detectInstrument(Contract contract) {
        String symbol = safe(contract.symbol());
        String localSymbol = safe(contract.localSymbol());
        String tradingClass = safe(contract.tradingClass());

        if (symbol.equals("MCL") || localSymbol.startsWith("MCL") || tradingClass.equals("MCL")) return Optional.of(Instrument.MCL);
        if (symbol.equals("MGC") || localSymbol.startsWith("MGC") || tradingClass.equals("MGC")) return Optional.of(Instrument.MGC);
        if (symbol.equals("MNQ") || localSymbol.startsWith("MNQ") || tradingClass.equals("MNQ")) return Optional.of(Instrument.MNQ);
        if (symbol.equals("6E") || localSymbol.startsWith("6E") || tradingClass.equals("6E")) return Optional.of(Instrument.E6);
        if (symbol.equals("DX") || localSymbol.startsWith("DX") || tradingClass.equals("DX")) return Optional.of(Instrument.DXY);

        return Optional.empty();
    }

    private List<Contract> buildQueries(Instrument instrument) {
        return switch (instrument) {
            case MCL -> List.of(
                buildQuery("MCL", "NYMEX", "USD", "100", "MCL"),
                buildQuery("MCL", "NYMEX", "USD", null, null)
            );
            case MGC -> List.of(
                buildQuery("MGC", "COMEX", "USD", "10", "MGC"),
                buildQuery("MGC", "COMEX", "USD", null, null)
            );
            case MNQ -> List.of(
                buildQuery("MNQ", "GLOBEX", "USD", "2", "MNQ"),
                buildQuery("MNQ", "GLOBEX", "USD", null, "MNQ"),
                buildQuery("MNQ", "CME", "USD", null, "MNQ"),
                buildQuery("MNQ", "CME", "USD", null, null)
            );
            case E6 -> List.of(
                buildQuery("EUR", "GLOBEX", "USD", "125000", "6E"),
                buildQuery("EUR", "GLOBEX", "USD", null, "6E"),
                buildQuery("EUR", "CME", "USD", null, "6E"),
                buildQuery("6E", "GLOBEX", "USD", null, "6E")
            );
            case DXY -> List.of(
                buildQuery("DX", "ICEUS", "USD", "1000", "DX"),
                buildQuery("DX", "ICEUS", "USD", null, "DX"),
                buildQuery("DX", "ICEUS", "USD", null, null)
            );
        };
    }

    private Optional<IbGatewayResolvedContract> preconfigured(Instrument instrument) {
        Contract contract = switch (instrument) {
            case MCL -> buildContract(661016514, "MCL", "NYMEX", "USD", "100", "MCL", "202605");
            case MGC -> buildContract(706903676, "MGC", "COMEX", "USD", "10", "MGC", "202604");
            case MNQ -> buildContract(770561201, "MNQ", "CME", "USD", "2", "MNQ", "202606");
            case E6 -> buildContract(496647057, "EUR", "CME", "USD", "125000", "6E", "202606");
            case DXY -> null;
        };
        return contract == null
            ? Optional.empty()
            : Optional.of(new IbGatewayResolvedContract(
                instrument,
                contract,
                null,
                normalizeContractMonth(contract.lastTradeDateOrContractMonth()),
                "preconfigured fallback"));
    }

    private Contract buildQuery(String symbol,
                                String exchange,
                                String currency,
                                String multiplier,
                                String tradingClass) {
        Contract contract = new Contract();
        contract.secType(SecType.FUT);
        contract.symbol(symbol);
        contract.exchange(exchange);
        contract.currency(currency);
        contract.includeExpired(false);
        if (multiplier != null) {
            contract.multiplier(multiplier);
        }
        if (tradingClass != null) {
            contract.tradingClass(tradingClass);
        }
        return contract;
    }

    private Contract buildContract(int conid,
                                   String symbol,
                                   String exchange,
                                   String currency,
                                   String multiplier,
                                   String tradingClass,
                                   String contractMonth) {
        Contract contract = new Contract();
        contract.conid(conid);
        contract.secType(SecType.FUT);
        contract.symbol(symbol);
        contract.exchange(exchange);
        contract.currency(currency);
        contract.multiplier(multiplier);
        contract.tradingClass(tradingClass);
        contract.lastTradeDateOrContractMonth(contractMonth);
        contract.includeExpired(false);
        return contract;
    }

    private Optional<IbGatewayResolvedContract> selectContract(Instrument instrument, List<ContractDetails> details) {
        List<ContractCandidate> candidates = details.stream()
            .map(this::toCandidate)
            .filter(Optional::isPresent)
            .map(Optional::get)
            .sorted(Comparator.comparing(ContractCandidate::expiry))
            .toList();

        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        IbGatewayContractSelectionPolicy policy = POLICIES.getOrDefault(
            instrument,
            new IbGatewayContractSelectionPolicy(7, 2, IbGatewayContractSelectionPreference.NEAREST_TRADABLE)
        );

        ContractCandidate frontMonth = candidates.get(0);
        List<ContractCandidate> eligible = candidates.stream()
            .filter(candidate -> candidate.daysToExpiry() >= policy.minimumDaysToExpiry())
            .toList();

        List<ContractCandidate> tradableCandidates = eligible.isEmpty() ? candidates : eligible;
        List<ContractCandidate> enriched = enrichCandidates(instrument, tradableCandidates, policy.liquidityProbeCount());

        ContractCandidate selected = switch (policy.preference()) {
            case NEAREST_TRADABLE -> chooseNearestTradable(enriched);
            case LIQUIDITY_AWARE -> chooseLiquidityAware(instrument, enriched);
        };

        String reason = buildSelectionReason(policy, frontMonth, selected, eligible.isEmpty());
        log.info("IB Gateway contract resolved for {} -> {} ({})",
            instrument,
            selected.contractMonth(),
            reason);

        return Optional.of(new IbGatewayResolvedContract(
            instrument,
            selected.details().contract(),
            selected.details(),
            selected.contractMonth(),
            reason));
    }

    private List<ContractCandidate> enrichCandidates(Instrument instrument,
                                                     List<ContractCandidate> tradableCandidates,
                                                     int liquidityProbeCount) {
        List<ContractCandidate> enriched = new ArrayList<>(tradableCandidates.size());
        int probes = Math.max(1, liquidityProbeCount);
        for (int index = 0; index < tradableCandidates.size(); index++) {
            ContractCandidate candidate = tradableCandidates.get(index);
            if (index < probes) {
                Optional<IbGatewayContractMarketSnapshot> snapshot =
                    nativeClient.requestContractMarketSnapshot(candidate.details().contract());
                enriched.add(candidate.withSnapshot(snapshot.orElse(null)));
            } else {
                enriched.add(candidate);
            }
        }
        return enriched;
    }

    private ContractCandidate chooseNearestTradable(List<ContractCandidate> tradableCandidates) {
        ContractCandidate nearest = tradableCandidates.get(0);
        if (nearest.hasAnyMarketData()) {
            return nearest;
        }
        return tradableCandidates.stream()
            .filter(ContractCandidate::hasAnyMarketData)
            .findFirst()
            .orElse(nearest);
    }

    private ContractCandidate chooseLiquidityAware(Instrument instrument, List<ContractCandidate> tradableCandidates) {
        ContractCandidate nearest = tradableCandidates.get(0);
        return tradableCandidates.stream()
            .max(Comparator.comparingDouble(candidate -> liquidityScore(instrument, candidate, nearest.daysToExpiry())))
            .orElse(nearest);
    }

    private double liquidityScore(Instrument instrument, ContractCandidate candidate, long nearestDaysToExpiry) {
        double score = 0.0;
        long distancePenalty = Math.max(0L, candidate.daysToExpiry() - nearestDaysToExpiry);
        score -= distancePenalty * 0.25d;

        if (candidate.snapshot() == null) {
            return score;
        }

        if (candidate.snapshot().hasPrice()) {
            score += 10.0d;
        }
        if (candidate.snapshot().hasVolume()) {
            score += Math.min(candidate.snapshot().volume(), 250_000L) / 1_000.0d;
        }
        if (candidate.snapshot().hasBidAsk()) {
            BigDecimal spread = candidate.snapshot().spread();
            BigDecimal tickSize = instrument.getTickSize();
            if (spread != null && tickSize != null && tickSize.signum() > 0) {
                double spreadTicks = spread.divide(tickSize, PRICE_CONTEXT).doubleValue();
                score += Math.max(0.0d, 40.0d - Math.min(spreadTicks, 40.0d));
            } else {
                score += 5.0d;
            }
        }
        return score;
    }

    private String buildSelectionReason(IbGatewayContractSelectionPolicy policy,
                                        ContractCandidate frontMonth,
                                        ContractCandidate selected,
                                        boolean noEligibleByBuffer) {
        if (!frontMonth.contractMonth().equals(selected.contractMonth())) {
            if (frontMonth.daysToExpiry() < policy.minimumDaysToExpiry()) {
                return "front month " + frontMonth.contractMonth()
                    + " is inside the " + policy.minimumDaysToExpiry()
                    + "-day close-out buffer; selected " + selected.contractMonth();
            }
            if (policy.preference() == IbGatewayContractSelectionPreference.LIQUIDITY_AWARE) {
                return "selected " + selected.contractMonth() + " for stronger live liquidity";
            }
        }
        if (noEligibleByBuffer) {
            return "no contract cleared the close-out buffer; selected nearest available month " + selected.contractMonth();
        }
        return "selected nearest tradable month " + selected.contractMonth();
    }

    private Optional<ContractCandidate> toCandidate(ContractDetails details) {
        LocalDate expiry = expiryKey(details);
        if (expiry == null) {
            return Optional.empty();
        }
        String contractMonth = normalizeContractMonth(details.contract().lastTradeDateOrContractMonth());
        long daysToExpiry = ChronoUnit.DAYS.between(LocalDate.now(clock), expiry);
        return Optional.of(new ContractCandidate(details, expiry, contractMonth, daysToExpiry, null));
    }

    private LocalDate expiryKey(ContractDetails details) {
        String raw = details.contract().lastTradeDateOrContractMonth();
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() >= 8) {
            return LocalDate.parse(digits.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
        }
        if (digits.length() == 6) {
            return YearMonth.parse(digits, DateTimeFormatter.ofPattern("yyyyMM")).atDay(1);
        }
        return null;
    }

    private String normalizeContractMonth(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() >= 6) {
            return digits.substring(0, 6);
        }
        return digits;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private record CachedResolution(IbGatewayResolvedContract resolved, java.time.Instant loadedAt) {
        private boolean isFresh(Clock clock) {
            return loadedAt.plus(CACHE_TTL).isAfter(clock.instant());
        }
    }

    private record ContractCandidate(ContractDetails details,
                                     LocalDate expiry,
                                     String contractMonth,
                                     long daysToExpiry,
                                     IbGatewayContractMarketSnapshot snapshot) {
        private ContractCandidate withSnapshot(IbGatewayContractMarketSnapshot snapshot) {
            return new ContractCandidate(details, expiry, contractMonth, daysToExpiry, snapshot);
        }

        private boolean hasAnyMarketData() {
            return snapshot != null && (snapshot.hasPrice() || snapshot.hasBidAsk() || snapshot.hasVolume());
        }
    }

}
