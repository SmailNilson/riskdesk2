package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.Types;
import com.riskdesk.domain.contract.event.ContractRolloverEvent;
import com.riskdesk.domain.marketdata.port.MarketDataProvider;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public class IbGatewayMarketDataProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayMarketDataProvider.class);

    private final IbGatewayNativeClient nativeClient;
    private final IbGatewayContractResolver contractResolver;
    private final SyntheticDxyCalculator dxyCalculator;

    public IbGatewayMarketDataProvider(IbGatewayNativeClient nativeClient,
                                       IbGatewayContractResolver contractResolver,
                                       SyntheticDxyCalculator dxyCalculator) {
        this.nativeClient = nativeClient;
        this.contractResolver = contractResolver;
        this.dxyCalculator = dxyCalculator;
    }

    @Override
    public Map<Instrument, BigDecimal> fetchPrices() {
        Map<Instrument, BigDecimal> prices = new EnumMap<>(Instrument.class);

        for (Instrument instrument : Instrument.exchangeTradedFutures()) {
            try {
                contractResolver.resolve(instrument).ifPresent(resolved -> {
                    nativeClient.registerInstrumentMapping(resolved.contract(), instrument);
                    nativeClient.ensureStreamingPriceSubscription(resolved.contract());
                    nativeClient.latestStreamingPrice(resolved.contract())
                        .ifPresent(price -> prices.put(instrument, price));
                });
            } catch (Exception e) {
                log.warn("IB Gateway live fetch failed for {}: {}", instrument, e.getMessage());
            }
        }

        // Synthetic DXY from FX pairs
        try {
            dxyCalculator.calculate().ifPresent(dxy -> prices.put(Instrument.DXY, dxy));
        } catch (Exception e) {
            log.warn("Synthetic DXY calculation failed: {}", e.getMessage());
        }

        return prices;
    }

    @Override
    public Optional<BigDecimal> fetchPrice(Instrument instrument) {
        if (instrument.isSynthetic()) {
            try {
                return dxyCalculator.calculate();
            } catch (Exception e) {
                log.warn("Synthetic DXY single fetch failed: {}", e.getMessage());
                return Optional.empty();
            }
        }

        try {
            return contractResolver.resolve(instrument)
                .flatMap(resolved -> {
                    nativeClient.registerInstrumentMapping(resolved.contract(), instrument);
                    nativeClient.ensureStreamingPriceSubscription(resolved.contract());
                    Optional<BigDecimal> live = nativeClient.latestStreamingPrice(resolved.contract());
                    if (live.isPresent()) {
                        return live;
                    }
                    return nativeClient.requestSnapshotPrice(resolved.contract());
                });
        } catch (Exception e) {
            log.warn("IB Gateway single snapshot fetch failed for {}: {}", instrument, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Subscribes to the VIX continuous futures contract (CFE: CONTFUT) and returns the latest
     * streaming price. IBKR handles rollover automatically for CONTFUT — no month management needed.
     */
    @Override
    public Optional<BigDecimal> fetchVixPrice() {
        try {
            nativeClient.ensureStreamingPriceSubscription(VIX_CONTFUT);
            return nativeClient.latestStreamingPrice(VIX_CONTFUT);
        } catch (Exception e) {
            log.warn("VIX CONTFUT fetch failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** VIX continuous futures contract on CFE (CBOE Futures Exchange). */
    private static final Contract VIX_CONTFUT = buildVixContract();

    private static Contract buildVixContract() {
        Contract c = new Contract();
        c.symbol("VIX");
        c.secType(Types.SecType.CONTFUT);
        c.exchange("CFE");
        c.currency("USD");
        return c;
    }

    /**
     * Reacts to a confirmed contract rollover by cancelling the old IBKR streaming
     * subscription and starting a new one for the rolled contract.
     */
    @EventListener
    public void onContractRollover(ContractRolloverEvent event) {
        Instrument instrument = event.instrument();
        if (instrument.isSynthetic()) return;

        log.info("Rollover event received for {} ({} → {}) — switching IBKR streams",
                instrument, event.oldContractMonth(), event.newContractMonth());

        try {
            // Resolve the new contract (cache was already refreshed by RolloverDetectionService)
            Contract newContract = contractResolver.resolve(instrument)
                    .map(IbGatewayResolvedContract::contract)
                    .orElse(null);

            // Build a synthetic old contract key for cancellation — the cache no longer holds it,
            // but the subscription key is deterministic from the contract fields.
            Contract oldContract = buildContractForMonth(instrument, event.oldContractMonth());

            nativeClient.cancelAndResubscribe(oldContract, newContract, instrument);
        } catch (Exception e) {
            log.error("Rollover IBKR stream switch failed for {}: {}", instrument, e.getMessage(), e);
        }
    }

    /**
     * Builds a minimal Contract object matching the subscription key format used by
     * the NativeClient, so the old subscription can be located and cancelled.
     */
    private Contract buildContractForMonth(Instrument instrument, String contractMonth) {
        Contract c = new Contract();
        c.secType(com.ib.client.Types.SecType.FUT);
        c.lastTradeDateOrContractMonth(contractMonth);
        switch (instrument) {
            case MCL -> { c.symbol("MCL"); c.exchange("NYMEX"); c.currency("USD"); c.tradingClass("MCL"); }
            case MGC -> { c.symbol("MGC"); c.exchange("COMEX"); c.currency("USD"); c.tradingClass("MGC"); }
            case MNQ -> { c.symbol("MNQ"); c.exchange("CME"); c.currency("USD"); c.tradingClass("MNQ"); }
            case E6  -> { c.symbol("EUR"); c.exchange("CME"); c.currency("USD"); c.tradingClass("6E"); }
            default  -> { return null; }
        }
        return c;
    }
}
