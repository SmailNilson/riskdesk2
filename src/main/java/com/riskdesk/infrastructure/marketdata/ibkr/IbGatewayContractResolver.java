package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types.SecType;
import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IbGatewayContractResolver {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayContractResolver.class);

    /** Minimum delay between two re-resolution attempts of a provisional (conId=0) contract. */
    private static final long PROVISIONAL_RETRY_INTERVAL_MS = 60_000;

    private final IbGatewayNativeClient nativeClient;
    private final ActiveContractRegistry registry;
    private final Map<Instrument, IbGatewayResolvedContract> cache = new ConcurrentHashMap<>();
    private final Map<Instrument, Long> provisionalRetryAt = new ConcurrentHashMap<>();

    public IbGatewayContractResolver(IbGatewayNativeClient nativeClient, ActiveContractRegistry registry) {
        this.nativeClient = nativeClient;
        this.registry = registry;
    }

    public Optional<IbGatewayResolvedContract> resolve(Instrument instrument) {
        if (!instrument.isExchangeTradedFuture()) {
            return Optional.empty();
        }
        IbGatewayResolvedContract cached = cache.get(instrument);
        if (cached != null) {
            // A provisional contract (conId=0, synthetic fallback from refreshToMonth) was cached
            // because IBKR was unreachable at resolution time. IBKR rejects subscriptions on it
            // ("No security definition", code 200), so retry the real resolution periodically
            // instead of serving the poison forever.
            if (isProvisional(cached)) {
                return Optional.of(retryProvisional(instrument, cached));
            }
            return Optional.of(cached);
        }
        // Prefer the registry's month — the registry is the Single Source of Truth.
        // Without this step, refresh() picks min(expiry), which for ENERGY contracts
        // (CME convention: expiry = 1 month before delivery) selects the contract
        // about to expire instead of the active delivery month.
        String registryMonth = registry.getContractMonth(instrument).orElse(null);
        if (registryMonth != null) {
            refreshToMonth(instrument, registryMonth);
            IbGatewayResolvedContract hydrated = cache.get(instrument);
            if (hydrated != null) {
                return Optional.of(hydrated);
            }
        }
        // No registry value — fall back to legacy min-expiry behavior (should be rare in prod).
        return refresh(instrument);
    }

    /**
     * The broker's {@code ContractDetails.minTick} for an already-resolved instrument — CACHE-ONLY, never
     * triggers a contract fetch (safe to call on the order-submission path). Empty when the contract is not
     * yet cached or the broker minTick is not a positive finite value; callers fall back to the
     * instrument's hardcoded tick.
     */
    public Optional<BigDecimal> cachedMinTick(Instrument instrument) {
        IbGatewayResolvedContract resolved = cache.get(instrument);
        if (resolved == null || resolved.details() == null) {
            return Optional.empty();
        }
        double minTick = resolved.details().minTick();
        if (Double.isNaN(minTick) || Double.isInfinite(minTick) || minTick <= 0.0) {
            return Optional.empty();
        }
        return Optional.of(BigDecimal.valueOf(minTick));
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
        // so the cache is seeded and resolve() doesn't fall back to front-month.
        // The synthetic has conId=0 and is therefore PROVISIONAL: resolve() keeps retrying
        // the real resolution (see retryProvisional) until IBKR confirms the contract.
        List<Contract> queries = buildQueries(instrument);
        if (!queries.isEmpty()) {
            Contract synthetic = queries.get(0);
            synthetic.lastTradeDateOrContractMonth(targetMonth);
            cache.put(instrument, new IbGatewayResolvedContract(instrument, synthetic, null));
            log.warn("refreshToMonth: {} → {} (synthetic fallback — IBKR did not return this month; "
                + "will re-resolve every {}s until confirmed)",
                instrument, targetMonth, PROVISIONAL_RETRY_INTERVAL_MS / 1000);
        }
    }

    /**
     * True when the cached contract is the synthetic fallback seeded by
     * {@link #refreshToMonth} while IBKR was unreachable (no conId). IBKR rejects all
     * subscriptions on such a contract with error code 200 ("No security definition").
     */
    private static boolean isProvisional(IbGatewayResolvedContract resolved) {
        return resolved.contract().conid() == 0;
    }

    /**
     * Attempts to replace a provisional (conId=0) cached contract with the real IBKR
     * contract for the same target month. Rate-limited to one attempt per
     * {@link #PROVISIONAL_RETRY_INTERVAL_MS}. On success the cache is updated and live
     * IBKR streams (price/quote/depth) are switched to the real contract; tick-by-tick
     * re-subscription follows on the orchestrator's next ensure/watchdog cycle.
     *
     * <p>This self-heals the startup race where contract resolution runs before the
     * IB Gateway connection is up: without it, the poisoned synthetic contract was
     * served from the cache forever and every subscription died with error code 200.</p>
     */
    private IbGatewayResolvedContract retryProvisional(Instrument instrument, IbGatewayResolvedContract provisional) {
        long now = System.currentTimeMillis();
        Long lastAttempt = provisionalRetryAt.get(instrument);
        if (lastAttempt != null && now - lastAttempt < PROVISIONAL_RETRY_INTERVAL_MS) {
            return provisional;
        }
        provisionalRetryAt.put(instrument, now);

        String targetMonth = normalizeMonth(provisional.contract().lastTradeDateOrContractMonth());
        if (targetMonth == null) {
            return provisional;
        }

        for (Contract query : buildQueries(instrument)) {
            try {
                List<ContractDetails> details = nativeClient.requestContractDetails(query);
                if (details.isEmpty()) continue;
                Optional<ContractDetails> match = details.stream()
                    .filter(d -> targetMonth.equals(normalizeMonth(d.contract().lastTradeDateOrContractMonth())))
                    .findFirst();
                if (match.isPresent()) {
                    ContractDetails d = match.get();
                    IbGatewayResolvedContract real = new IbGatewayResolvedContract(instrument, d.contract(), d);
                    cache.put(instrument, real);
                    provisionalRetryAt.remove(instrument);
                    log.info("ContractResolver: {} provisional month {} re-resolved to real conId={} — switching IBKR streams",
                        instrument, targetMonth, d.contract().conid());
                    nativeClient.cancelAndResubscribe(provisional.contract(), d.contract(), instrument);
                    return real;
                }
            } catch (Exception e) {
                log.debug("retryProvisional: {} {} query failed — {}", instrument, targetMonth, e.getMessage());
            }
        }
        return provisional;
    }

    /**
     * Legacy last-resort path: picks the contract with min(expiry) from IBKR.
     * For ENERGY products this selects the contract about to expire (wrong).
     * {@link #resolve(Instrument)} now prefers {@link ActiveContractRegistry}
     * and only falls through to this method when the registry has no value
     * (rare in prod — ActiveContractRegistryInitializer runs at @Order(1)).
     */
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
                buildQuery("MNQ", "CME", "USD", "2", "MNQ"),
                buildQuery("MNQ", "CME", "USD", null, "MNQ"),
                buildQuery("MNQ", "CME", "USD", null, null)
            );
            case E6 -> List.of(
                buildQuery("EUR", "CME", "USD", "125000", "6E"),
                buildQuery("EUR", "CME", "USD", null, "6E"),
                buildQuery("6E", "CME", "USD", null, "6E")
            );
            case DXY -> List.of();
        };
    }

    /**
     * Builds the IBKR <em>continuous-futures</em> contract (secType {@code CONTFUT}) for an
     * instrument. IBKR stitches the series itself: at every past date the bars come from the
     * contract that was actually front-month at that date — the TradingView-style continuous
     * series. Historical-data only: a CONTFUT contract cannot back live subscriptions or orders,
     * so this is intentionally NOT cached in {@link #resolve}'s front-month cache.
     */
    public Optional<Contract> continuousContract(Instrument instrument) {
        if (!instrument.isExchangeTradedFuture()) {
            return Optional.empty();
        }
        Contract contract = switch (instrument) {
            case MCL -> buildQuery("MCL", "NYMEX", "USD", null, "MCL");
            case MGC -> buildQuery("MGC", "COMEX", "USD", null, "MGC");
            case MNQ -> buildQuery("MNQ", "CME", "USD", null, "MNQ");
            case E6  -> buildQuery("EUR", "CME", "USD", null, "6E");
            default  -> null;
        };
        if (contract == null) {
            return Optional.empty();
        }
        contract.secType(SecType.CONTFUT);
        return Optional.of(contract);
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
