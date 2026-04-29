package com.riskdesk.application.quant.adapter;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.marketdata.port.TickDataPort;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.DeltaSnapshot;
import com.riskdesk.domain.quant.port.DeltaPort;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Bridges the Quant {@link DeltaPort} onto the existing {@link TickDataPort}.
 * Wrapped in {@link ObjectProvider} so the bean is optional (no IBKR mode → empty).
 */
@Component
public class DeltaPortAdapter implements DeltaPort {

    private final ObjectProvider<TickDataPort> tickDataPortProvider;

    public DeltaPortAdapter(ObjectProvider<TickDataPort> tickDataPortProvider) {
        this.tickDataPortProvider = tickDataPortProvider;
    }

    @Override
    public Optional<DeltaSnapshot> current(Instrument instrument) {
        TickDataPort port = tickDataPortProvider.getIfAvailable();
        if (port == null) return Optional.empty();
        Optional<TickAggregation> agg = port.currentAggregation(instrument);
        return agg.map(a -> new DeltaSnapshot(
            a.cumulativeDelta(),
            a.buyRatioPct(),
            a.windowEnd(),
            a.source()
        ));
    }
}
