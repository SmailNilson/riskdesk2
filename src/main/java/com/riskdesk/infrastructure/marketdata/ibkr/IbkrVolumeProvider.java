package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Types.SecType;
import com.riskdesk.domain.marketdata.port.VolumeProvider;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.OptionalLong;

/**
 * {@link VolumeProvider} adapter that retrieves daily trading volume from IBKR
 * via a one-shot market-data snapshot.
 * <p>
 * Unlike the cached {@code resolve()} path, this provider queries IBKR for
 * <em>all</em> available contract months and picks the one matching the
 * requested month — allowing volume lookups for <strong>any</strong> month,
 * not just the front-month.
 */
public class IbkrVolumeProvider implements VolumeProvider {

    private static final Logger log = LoggerFactory.getLogger(IbkrVolumeProvider.class);

    private final IbGatewayNativeClient nativeClient;
    private final IbGatewayContractResolver resolver;

    public IbkrVolumeProvider(IbGatewayNativeClient nativeClient, IbGatewayContractResolver resolver) {
        this.nativeClient = nativeClient;
        this.resolver = resolver;
    }

    @Override
    public OptionalLong volumeFor(Instrument instrument, String contractMonth) {
        // First try the cached resolution (fast path for front-month)
        var cached = resolver.resolve(instrument)
            .filter(r -> contractMonthMatches(r.contract(), contractMonth));
        if (cached.isPresent()) {
            return nativeClient.requestSnapshotVolume(cached.get().contract());
        }

        // Slow path: query IBKR for all contracts and find the matching month
        Contract match = resolver.nearestContracts(instrument, 4).stream()
            .filter(c -> contractMonth.equals(c.contractMonth()))
            .findFirst()
            .flatMap(candidate -> resolveContractForMonth(instrument, contractMonth))
            .orElse(null);

        if (match == null) {
            log.debug("VolumeProvider: no IBKR contract found for {} {}", instrument, contractMonth);
            return OptionalLong.empty();
        }

        return nativeClient.requestSnapshotVolume(match);
    }

    private java.util.Optional<Contract> resolveContractForMonth(Instrument instrument, String contractMonth) {
        // Build a targeted query for the specific contract month
        return resolver.resolve(instrument).map(r -> {
            Contract c = new Contract();
            c.secType(SecType.FUT);
            c.symbol(r.contract().symbol());
            c.exchange(r.contract().exchange());
            c.currency(r.contract().currency());
            if (r.contract().multiplier() != null) c.multiplier(r.contract().multiplier());
            if (r.contract().tradingClass() != null) c.tradingClass(r.contract().tradingClass());
            c.lastTradeDateOrContractMonth(contractMonth);
            c.includeExpired(false);

            // Resolve to get the actual conid
            List<ContractDetails> details = nativeClient.requestContractDetails(c);
            if (details.isEmpty()) return null;
            return details.get(0).contract();
        });
    }

    private static boolean contractMonthMatches(Contract contract, String targetMonth) {
        String raw = contract.lastTradeDateOrContractMonth();
        if (raw == null) return false;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.startsWith(targetMonth);
    }
}
