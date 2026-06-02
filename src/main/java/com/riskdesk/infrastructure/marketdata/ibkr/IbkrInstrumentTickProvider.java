package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.execution.port.InstrumentTickProvider;
import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * {@link InstrumentTickProvider} backed by IBKR contract details. Prefers the broker's authoritative
 * {@code ContractDetails.minTick} (cache-only — never blocks the order path) and falls back to the
 * instrument's hardcoded {@link Instrument#getTickSize()} when the contract has not been resolved yet
 * (cold cache) or the broker minTick is unusable. The result is therefore always a valid positive tick.
 */
@Component
public class IbkrInstrumentTickProvider implements InstrumentTickProvider {

    private final IbGatewayContractResolver contractResolver;

    public IbkrInstrumentTickProvider(IbGatewayContractResolver contractResolver) {
        this.contractResolver = contractResolver;
    }

    @Override
    public BigDecimal minTick(Instrument instrument) {
        return contractResolver.cachedMinTick(instrument).orElseGet(instrument::getTickSize);
    }
}
