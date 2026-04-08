package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types.SecType;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IbGatewayContractResolver {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayContractResolver.class);
    private final IbGatewayNativeClient nativeClient;
    private final Map<Instrument, IbGatewayResolvedContract> cache = new ConcurrentHashMap<>();

    public IbGatewayContractResolver(IbGatewayNativeClient nativeClient) {
        this.nativeClient = nativeClient;
    }

    public Optional<IbGatewayResolvedContract> resolve(Instrument instrument) {
        if (!instrument.isExchangeTradedFuture()) {
            return Optional.empty();
        }
        IbGatewayResolvedContract cached = cache.get(instrument);
        if (cached != null) {
            return Optional.of(cached);
        }
        return refresh(instrument);
    }

    /**
     * Directly seeds the cache with an already-resolved contract.
     * Used by ActiveContractRegistryInitializer after OI-based startup selection.
     *
     * Always updates the cache (the initializer's OI-based selection is authoritative).
     * If the conId changes, the old IBKR stream is cancelled and a new one started.
     * If the conId is the same, the cache is updated silently (no stream disruption).
     */
    public void setResolved(Instrument instrument, IbGatewayResolvedContract resolved) {
        IbGatewayResolvedContract previous = cache.put(instrument, resolved);
        if (previous != null && previous.contract().conid() != resolved.contract().conid()) {
            log.info("ContractResolver: {} conId changed {} → {} — switching IBKR stream",
                instrument, previous.contract().conid(), resolved.contract().conid());
            nativeClient.cancelAndResubscribe(previous.contract(), resolved.contract(), instrument);
        }
    }

    /**
     * Clears the cache for one instrument and re-resolves from IBKR targeting a specific month.
     * Used by confirmRollover() so the new month is immediately cached without falling through
     * to a stale cached fallback.
     */
    public void refreshToMonth(Instrument instrument, String targetMonth) {
        // Cancel old IBKR streaming subscriptions BEFORE updating the cache,
        // so orphaned subscriptions on the expired contract month don't keep
        // pushing stale prices alongside the new contract.
        nativeClient.cancelInstrumentSubscriptions(instrument);
        cache.remove(instrument);
        for (Contract query : buildQueries(instrument)) {
            try {
                List<ContractDetails> details = nativeClient.requestContractDetails(query);
                if (details.isEmpty()) continue;
                Optional<ContractDetails> match = details.stream()
                    .filter(d -> targetMonth.equals(normalizeMonth(d.contract().lastTradeDateOrContractMonth())))
                    .findFirst();
                if (match.isPresent()) {
                    ContractDetails d = match.get();
                    cache.put(instrument, new IbGatewayResolvedContract(instrument, d.contract(), d));
                    return;
                }
            } catch (Exception e) {
                // IBKR may be unavailable — continue to fallback
            }
        }
        // IBKR didn't return the target month — build a synthetic contract from the query
        // so the cache is seeded and resolve() doesn't fall back to front-month
        List<Contract> queries = buildQueries(instrument);
        if (!queries.isEmpty()) {
            Contract synthetic = queries.get(0);
            synthetic.lastTradeDateOrContractMonth(targetMonth);
            cache.put(instrument, new IbGatewayResolvedContract(instrument, synthetic, null));
            log.warn("refreshToMonth: {} → {} (synthetic fallback — IBKR did not return this month)",
                instrument, targetMonth);
        }
    }

    public Optional<IbGatewayResolvedContract> refresh(Instrument instrument) {
        if (!instrument.isExchangeTradedFuture()) {
            return Optional.empty();
        }
        cache.remove(instrument);

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

        ContractDetails selected = details.stream()
            .filter(detailsItem -> expiryKey(detailsItem) != null)
            .min(Comparator.comparing(this::expiryKey, Comparator.nullsLast(Comparator.naturalOrder())))
            .orElse(details.get(0));

        IbGatewayResolvedContract resolved = new IbGatewayResolvedContract(instrument, selected.contract(), selected);
        cache.put(instrument, resolved);
        return Optional.of(resolved);
    }

    /**
     * Returns the nearest contract months (up to 3) for an instrument,
     * sorted by expiry date ascending. Used for OI and volume comparison.
     */
    public List<IbGatewayResolvedContract> resolveNextContracts(Instrument instrument) {
        if (!instrument.isExchangeTradedFuture()) {
            return List.of();
        }

        List<ContractDetails> details = List.of();
        for (Contract query : buildQueries(instrument)) {
            details = nativeClient.requestContractDetails(query);
            if (!details.isEmpty()) {
                break;
            }
        }

        if (details.isEmpty()) {
            return List.of();
        }

        return details.stream()
            .filter(d -> expiryKey(d) != null)
            .sorted(Comparator.comparing(this::expiryKey, Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(3)
            .map(d -> new IbGatewayResolvedContract(instrument, d.contract(), d))
            .toList();
    }

    /**
     * Resolves an expired contract for a specific month (e.g. "202503").
     * Uses includeExpired=true so IBKR returns historical contracts.
     * Used by deep backfill to walk across prior contract months.
     */
    public Optional<IbGatewayResolvedContract> resolveExpiredMonth(Instrument instrument, String targetMonth) {
        if (!instrument.isExchangeTradedFuture()) return Optional.empty();

        for (Contract query : buildQueries(instrument)) {
            query.includeExpired(true);
            query.lastTradeDateOrContractMonth(targetMonth);
            try {
                List<ContractDetails> details = nativeClient.requestContractDetails(query);
                if (!details.isEmpty()) {
                    ContractDetails d = details.get(0);
                    return Optional.of(new IbGatewayResolvedContract(instrument, d.contract(), d));
                }
            } catch (Exception e) {
                log.debug("resolveExpiredMonth: {} {} query failed — {}", instrument, targetMonth, e.getMessage());
            }
        }
        return Optional.empty();
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
            case DXY -> List.of();
        };
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

    private static String safe(String value) {
        return value == null ? "" : value.trim().toUpperCase();
    }

    private static String normalizeMonth(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
    }

}
