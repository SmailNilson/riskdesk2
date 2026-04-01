package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.riskdesk.domain.marketdata.model.FxPair;
import com.riskdesk.domain.marketdata.model.FxQuoteSnapshot;
import com.riskdesk.domain.marketdata.port.FxQuoteProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

public class IbGatewayFxQuoteProvider implements FxQuoteProvider {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayFxQuoteProvider.class);

    private final IbGatewayNativeClient nativeClient;
    private final IbGatewayFxContractResolver contractResolver;

    public IbGatewayFxQuoteProvider(IbGatewayNativeClient nativeClient,
                                    IbGatewayFxContractResolver contractResolver) {
        this.nativeClient = nativeClient;
        this.contractResolver = contractResolver;
    }

    @Override
    public Map<FxPair, FxQuoteSnapshot> fetchQuotes() {
        Map<FxPair, FxQuoteSnapshot> quotes = new EnumMap<>(FxPair.class);
        for (FxPair pair : FxPair.values()) {
            fetchQuote(pair).ifPresent(snapshot -> quotes.put(pair, snapshot));
        }
        return quotes;
    }

    @Override
    public Optional<FxQuoteSnapshot> fetchQuote(FxPair pair) {
        try {
            Optional<Contract> contract = contractResolver.resolve(pair);
            if (contract.isEmpty()) {
                return Optional.empty();
            }

            nativeClient.ensureStreamingQuoteSubscription(contract.get());
            Optional<IbGatewayNativeClient.NativeMarketQuote> live = nativeClient.latestStreamingQuote(contract.get());
            if (live.isPresent()) {
                return Optional.of(toQuoteSnapshot(pair, live.get(), "LIVE_PROVIDER"));
            }

            return nativeClient.requestSnapshotQuote(contract.get())
                .map(snapshot -> toQuoteSnapshot(pair, snapshot, "SNAPSHOT_PROVIDER"));
        } catch (Exception e) {
            log.warn("IB Gateway FX quote fetch failed for {}: {}", pair, e.getMessage());
            return Optional.empty();
        }
    }

    private FxQuoteSnapshot toQuoteSnapshot(FxPair pair,
                                            IbGatewayNativeClient.NativeMarketQuote quote,
                                            String source) {
        return new FxQuoteSnapshot(
            pair,
            quote.bid(),
            quote.ask(),
            quote.last(),
            quote.close(),
            quote.timestamp(),
            source
        );
    }
}
