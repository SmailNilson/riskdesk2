package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.riskdesk.domain.execution.port.MarketQuoteProvider;
import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * {@link MarketQuoteProvider} backed by the IB Gateway native client. Resolves the instrument's active
 * contract, prefers the (non-blocking) live streaming quote, and falls back to a one-shot snapshot quote
 * when the stream has not ticked yet. Mirrors {@link IbGatewayFxQuoteProvider}'s streaming-then-snapshot
 * pattern. Returns empty when the contract is unresolved or the gateway is not connected, so the router
 * degrades to a passive limit (no regression).
 */
@Component
public class IbkrMarketQuoteProvider implements MarketQuoteProvider {

    private final IbGatewayContractResolver contractResolver;
    private final IbGatewayNativeClient nativeClient;

    public IbkrMarketQuoteProvider(IbGatewayContractResolver contractResolver, IbGatewayNativeClient nativeClient) {
        this.contractResolver = contractResolver;
        this.nativeClient = nativeClient;
    }

    @Override
    public Optional<Quote> currentQuote(Instrument instrument) {
        return contractResolver.resolve(instrument).flatMap(resolved -> {
            Contract contract = resolved.contract();
            // Idempotent — establishes the stream on first use; subsequent close legs read it instantly.
            nativeClient.ensureStreamingQuoteSubscription(contract);
            Optional<IbGatewayNativeClient.NativeMarketQuote> q = nativeClient.latestStreamingQuote(contract);
            if (q.isEmpty()) {
                // Stream not warm yet (first call) — one-shot snapshot (briefly blocking) so the very
                // first exit after a cold start still prices marketable instead of resting.
                q = nativeClient.requestSnapshotQuote(contract);
            }
            return q.map(n -> new Quote(n.bid(), n.ask(), n.last()));
        });
    }
}
