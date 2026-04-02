package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.riskdesk.domain.marketdata.port.VolumeProvider;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.OptionalLong;

/**
 * {@link VolumeProvider} adapter that retrieves daily trading volume from IBKR
 * via a one-shot market-data snapshot.
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
        return resolver.resolve(instrument)
            .filter(r -> contractMonthMatches(r.contract(), contractMonth))
            .map(r -> nativeClient.requestSnapshotVolume(r.contract()))
            .orElseGet(() -> {
                log.debug("VolumeProvider: no resolved contract for {} {}", instrument, contractMonth);
                return OptionalLong.empty();
            });
    }

    private static boolean contractMonthMatches(Contract contract, String targetMonth) {
        String raw = contract.lastTradeDateOrContractMonth();
        if (raw == null) return false;
        String digits = raw.replaceAll("[^0-9]", "");
        return digits.startsWith(targetMonth);
    }
}
