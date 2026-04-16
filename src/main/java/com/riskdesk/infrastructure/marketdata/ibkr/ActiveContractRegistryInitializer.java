package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.contract.ActiveContractRegistry;
import com.riskdesk.domain.contract.port.ActiveContractSnapshotStore;
import com.riskdesk.domain.contract.port.OpenInterestProvider;
import com.riskdesk.domain.model.AssetClass;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Initializes the ActiveContractRegistry at startup (Order 1 — before HistoricalDataService).
 *
 * Resolution strategy (in order):
 *   1. Ask IBKR for available contracts via IbGatewayContractResolver, then:
 *      a. Compare OI across all contracts — pick the one with highest OI.
 *      b. If OI unavailable, compare volume — pick highest volume.
 *      c. If neither available, default to front-month (no calendar-roll).
 *      Each successful resolution persists the month to {@link ActiveContractSnapshotStore}
 *      so future cold boots without IBKR can restore the last-known-good state.
 *   2. If IBKR is unavailable or disabled, restore from the snapshot store.
 *   3. If no snapshot exists (first boot), fall back to application properties.
 *   4. Any fallback (snapshot or property) must not be older than the current month —
 *      a stale value is refused and the instrument is left uninitialized. Downstream
 *      services then fail loud rather than silently trading on an expired contract.
 */
@Component
@Order(1)
public class ActiveContractRegistryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ActiveContractRegistryInitializer.class);

    /** Business calendar — matches TradingSessionResolver projection. */
    private static final ZoneId NY_ZONE = ZoneId.of("America/New_York");

    private final ActiveContractRegistry       registry;
    private final IbGatewayContractResolver    resolver;
    private final IbkrProperties               ibkrProperties;
    private final OpenInterestProvider         openInterestProvider;
    private final ActiveContractSnapshotStore  snapshotStore;
    private final Clock                        clock;

    @Value("${riskdesk.active-contracts.MCL:202606}")
    private String fallbackMcl;

    @Value("${riskdesk.active-contracts.MGC:202606}")
    private String fallbackMgc;

    @Value("${riskdesk.active-contracts.MNQ:202606}")
    private String fallbackMnq;

    @Value("${riskdesk.active-contracts.E6:202606}")
    private String fallbackE6;

    // calendarDaysThreshold removed — caused Frankenstein charts by rolling the
    // fallback contract forward when IBKR was unavailable at startup.

    @Autowired
    public ActiveContractRegistryInitializer(ActiveContractRegistry registry,
                                             IbGatewayContractResolver resolver,
                                             IbkrProperties ibkrProperties,
                                             OpenInterestProvider openInterestProvider,
                                             ActiveContractSnapshotStore snapshotStore) {
        this(registry, resolver, ibkrProperties, openInterestProvider, snapshotStore, Clock.system(NY_ZONE));
    }

    // Visible for tests — lets us inject a fixed Clock to exercise the staleness guard.
    ActiveContractRegistryInitializer(ActiveContractRegistry registry,
                                      IbGatewayContractResolver resolver,
                                      IbkrProperties ibkrProperties,
                                      OpenInterestProvider openInterestProvider,
                                      ActiveContractSnapshotStore snapshotStore,
                                      Clock clock) {
        this.registry             = registry;
        this.resolver             = resolver;
        this.ibkrProperties       = ibkrProperties;
        this.openInterestProvider = openInterestProvider;
        this.snapshotStore        = snapshotStore;
        this.clock                = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<Instrument, String> fallbacks = Map.of(
            Instrument.MCL, fallbackMcl,
            Instrument.MGC, fallbackMgc,
            Instrument.MNQ, fallbackMnq,
            Instrument.E6,  fallbackE6
        );

        YearMonth currentMonth = YearMonth.now(clock);

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            String resolved = ibkrProperties.isEnabled()
                ? resolveFromIbkr(instrument)
                : null;

            if (resolved != null) {
                registry.initialize(instrument, resolved);
                log.info("ActiveContractRegistry: {} → {} (IBKR)", instrument, resolved);
                continue;
            }

            // IBKR unavailable: prefer the persisted last-known-good over the hardcoded property.
            // The snapshot reflects either a previous successful IBKR resolution or a confirmed
            // rollover — both strictly better signals than an operator-maintained property file.
            Optional<ActiveContractSnapshotStore.Snapshot> snapshot = loadSnapshot(instrument);
            if (snapshot.isPresent() && !isStale(snapshot.get().contractMonth(), currentMonth)) {
                String month = snapshot.get().contractMonth();
                registry.initialize(instrument, month);
                log.warn("ActiveContractRegistry: {} → {} (snapshot from {} @ {}, IBKR {})",
                    instrument, month, snapshot.get().source(), snapshot.get().resolvedAt(),
                    ibkrProperties.isEnabled() ? "returned empty" : "disabled");
                continue;
            }

            // No usable snapshot: fall back to the property value, but refuse anything expired.
            // Silently trading on an expired contract is worse than leaving the slot empty —
            // downstream services will see Optional.empty() and fail loud instead of writing
            // candles against a dead contract month.
            String fallback = fallbacks.get(instrument);
            if (isStale(fallback, currentMonth)) {
                log.error("ActiveContractRegistry: {} NOT initialized — fallback property '{}' is expired "
                    + "(current YearMonth={}). IBKR is unreachable and no usable snapshot is persisted. "
                    + "Update riskdesk.active-contracts.{} in application.properties or bring IBKR up. "
                    + "Downstream services depending on this instrument will fail until resolved.",
                    instrument, fallback, currentMonth, instrument.name());
                continue;
            }
            registry.initialize(instrument, fallback);
            log.warn("ActiveContractRegistry: {} → {} (property fallback — IBKR {}, no snapshot)",
                instrument, fallback,
                ibkrProperties.isEnabled() ? "returned empty" : "disabled");
        }

        log.info("ActiveContractRegistry ready: {}", registry.snapshot());
    }

    private Optional<ActiveContractSnapshotStore.Snapshot> loadSnapshot(Instrument instrument) {
        try {
            return snapshotStore.load(instrument);
        } catch (Exception e) {
            // Snapshot backing store is optional — do not block startup on a DB hiccup.
            log.warn("ActiveContractRegistryInitializer: snapshot load failed for {} — {}",
                instrument, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * A contract month is considered stale when its {@link YearMonth} is strictly before
     * the current month. Equal months (we are in the final weeks of the front contract)
     * are still accepted — the proper rollover path will pick up the next contract.
     */
    private static boolean isStale(String contractMonth, YearMonth now) {
        YearMonth ym = parseYearMonth(contractMonth);
        return ym == null || ym.isBefore(now);
    }

    private static YearMonth parseYearMonth(String contractMonth) {
        if (contractMonth == null || contractMonth.length() < 6) return null;
        try {
            int year  = Integer.parseInt(contractMonth.substring(0, 4));
            int month = Integer.parseInt(contractMonth.substring(4, 6));
            return YearMonth.of(year, month);
        } catch (NumberFormatException | java.time.DateTimeException e) {
            return null;
        }
    }

    private void persistSnapshot(Instrument instrument, String contractMonth,
                                 ActiveContractSnapshotStore.Source source) {
        try {
            snapshotStore.save(instrument, contractMonth, source, Instant.now(clock));
        } catch (Exception e) {
            // A failed snapshot write must not block startup — the in-memory registry
            // is the source of truth at runtime; persistence is a recovery aid.
            log.warn("ActiveContractRegistryInitializer: snapshot save failed for {} {} — {}",
                instrument, contractMonth, e.getMessage());
        }
    }

    private String resolveFromIbkr(Instrument instrument) {
        try {
            List<IbGatewayResolvedContract> contracts = resolver.resolveNextContracts(instrument);
            if (contracts.isEmpty()) return null;
            if (contracts.size() == 1) {
                resolver.setResolved(instrument, contracts.get(0));
                String month = normalizeMonth(contracts.get(0).contract().lastTradeDateOrContractMonth());
                if (month != null) persistSnapshot(instrument, month, ActiveContractSnapshotStore.Source.IBKR_FRONT);
                return month;
            }

            // Strategy 1: OI comparison — scan ALL available contracts (not just first 2)
            IbGatewayResolvedContract oiWinner = selectByOi(instrument, contracts);
            if (oiWinner != null) {
                resolver.setResolved(instrument, oiWinner);
                String month = normalizeMonth(oiWinner.contract().lastTradeDateOrContractMonth());
                if (month != null) persistSnapshot(instrument, month, ActiveContractSnapshotStore.Source.IBKR_OI);
                return month;
            }

            // Strategy 2: Volume comparison — scan ALL available contracts
            IbGatewayResolvedContract volWinner = selectByVolume(instrument, contracts);
            if (volWinner != null) {
                resolver.setResolved(instrument, volWinner);
                String month = normalizeMonth(volWinner.contract().lastTradeDateOrContractMonth());
                if (month != null) persistSnapshot(instrument, month, ActiveContractSnapshotStore.Source.IBKR_VOLUME);
                return month;
            }

            // Default: use front-month when OI+volume are both unavailable.
            // No calendar-roll — without OI data, we cannot know where liquidity sits.
            String frontMonth = normalizeMonth(contracts.get(0).contract().lastTradeDateOrContractMonth());
            log.info("ActiveContractRegistry: {} defaulting to front-month {} (OI+volume unavailable)", instrument, frontMonth);
            resolver.setResolved(instrument, contracts.get(0));
            if (frontMonth != null) persistSnapshot(instrument, frontMonth, ActiveContractSnapshotStore.Source.IBKR_FRONT);
            return frontMonth;
        } catch (Exception e) {
            log.debug("ActiveContractRegistryInitializer: IBKR resolution failed for {} — {}", instrument, e.getMessage());
            return null;
        }
    }

    /**
     * Compares OI across ALL available contracts.
     * Returns the contract with the highest OI, or null if OI is unavailable for all.
     *
     * Energy contracts (MCL) have a CME convention where the last trade date is one
     * month BEFORE the delivery month. IBKR's normalizeMonth("20260421") = "202604"
     * but that's the MAY contract. So when OI selects "202605" as the winner, it's
     * actually the JUNE contract. For MCL, we take the contract at index-1 (the one
     * that expires one month earlier = the real active delivery month).
     */
    private IbGatewayResolvedContract selectByOi(Instrument instrument, List<IbGatewayResolvedContract> contracts) {
        IbGatewayResolvedContract best = null;
        int bestIndex = -1;
        long bestOi = -1;
        boolean anyOiPresent = false;
        StringBuilder logDetail = new StringBuilder();

        for (int i = 0; i < contracts.size(); i++) {
            IbGatewayResolvedContract c = contracts.get(i);
            String month = normalizeMonth(c.contract().lastTradeDateOrContractMonth());
            OptionalLong oi = month != null ? openInterestProvider.fetchOpenInterest(instrument, month) : OptionalLong.empty();
            if (oi.isPresent()) {
                anyOiPresent = true;
                logDetail.append(month).append("=").append(oi.getAsLong()).append(" ");
                if (oi.getAsLong() > bestOi) {
                    bestOi = oi.getAsLong();
                    best = c;
                    bestIndex = i;
                }
            }
        }

        if (!anyOiPresent || best == null) return null;

        // Energy (MCL): IBKR lastTradeDateOrContractMonth gives the expiry month,
        // which is one month BEFORE the delivery month. The OI winner at index N
        // is actually the NEXT month's contract. The real active contract is at N-1.
        if (instrument.assetClass() == AssetClass.ENERGY && bestIndex > 0) {
            IbGatewayResolvedContract energyAdjusted = contracts.get(bestIndex - 1);
            log.info("ActiveContractRegistry: {} OI-select → index {} (OI={}, all: [{}]) — ENERGY adjust: using index {} (conId={}) instead of index {} (conId={})",
                instrument, bestIndex, bestOi, logDetail.toString().trim(),
                bestIndex - 1, energyAdjusted.contract().conid(),
                bestIndex, best.contract().conid());
            return energyAdjusted;
        }

        log.info("ActiveContractRegistry: {} OI-select → {} (OI={}, all: [{}])",
            instrument, normalizeMonth(best.contract().lastTradeDateOrContractMonth()),
            bestOi, logDetail.toString().trim());
        return best;
    }

    /**
     * Compares volume across ALL available contracts. Returns the one with highest volume,
     * or null if volume is unavailable for all. Fallback when OI is unavailable.
     */
    private IbGatewayResolvedContract selectByVolume(Instrument instrument, List<IbGatewayResolvedContract> contracts) {
        IbGatewayResolvedContract best = null;
        long bestVol = -1;
        boolean anyVolPresent = false;

        for (IbGatewayResolvedContract c : contracts) {
            String month = normalizeMonth(c.contract().lastTradeDateOrContractMonth());
            OptionalLong vol = month != null ? openInterestProvider.fetchVolume(instrument, month) : OptionalLong.empty();
            if (vol.isPresent()) {
                anyVolPresent = true;
                if (vol.getAsLong() > bestVol) {
                    bestVol = vol.getAsLong();
                    best = c;
                }
            }
        }

        if (best != null && anyVolPresent) {
            log.info("ActiveContractRegistry: {} volume-select → {} (vol={}, OI was unavailable)",
                instrument, normalizeMonth(best.contract().lastTradeDateOrContractMonth()), bestVol);
        }
        return anyVolPresent ? best : null;
    }

    /**
     * Computes the previous contract month based on instrument cycle.
     * E6/MNQ: quarterly (Mar, Jun, Sep, Dec)
     * MCL/MGC: monthly
     */
    static String previousContractMonth(Instrument instrument, String contractMonth) {
        if (contractMonth == null || contractMonth.length() < 6) return contractMonth;
        int year = Integer.parseInt(contractMonth.substring(0, 4));
        int month = Integer.parseInt(contractMonth.substring(4, 6));
        YearMonth ym = YearMonth.of(year, month);

        if (instrument == Instrument.E6 || instrument == Instrument.MNQ) {
            // Quarterly: Mar(3), Jun(6), Sep(9), Dec(12)
            do {
                ym = ym.minusMonths(1);
            } while (ym.getMonthValue() % 3 != 0);
        } else {
            // Monthly: MCL, MGC
            ym = ym.minusMonths(1);
        }

        return String.format("%04d%02d", ym.getYear(), ym.getMonthValue());
    }

    private static String normalizeMonth(String raw) {
        if (raw == null) return null;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.length() >= 6 ? digits.substring(0, 6) : null;
    }
}
