package com.riskdesk.application.service.analysis.adapter;

import com.riskdesk.application.service.DxyMarketService;
import com.riskdesk.domain.analysis.model.MacroContext;
import com.riskdesk.domain.analysis.port.MacroContextPort;
import com.riskdesk.domain.marketdata.model.DxySnapshot;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;

/**
 * Surfaces DXY-derived macro context for the scoring engine. Falls back to a
 * neutral context if DXY is not configured (e.g. in non-IB_GATEWAY mode).
 */
@Component
public class MacroContextAdapter implements MacroContextPort {

    private final ObjectProvider<DxyMarketService> dxyServiceProvider;

    public MacroContextAdapter(ObjectProvider<DxyMarketService> dxyServiceProvider) {
        this.dxyServiceProvider = dxyServiceProvider;
    }

    @Override
    public TimedMacro contextAsOf(Instant decisionAt) {
        DxyMarketService service = dxyServiceProvider.getIfAvailable();
        if (service == null) {
            return new TimedMacro(new MacroContext(null, null, "FLAT"), Instant.now());
        }
        Optional<DxySnapshot> latest = service.latestSnapshot();
        if (latest.isEmpty()) {
            return new TimedMacro(new MacroContext(null, null, "FLAT"), Instant.now());
        }
        DxySnapshot snap = latest.get();
        Double value = snap.dxyValue() != null ? snap.dxyValue().doubleValue() : null;

        // Direction inferred from change percent if available — DxyMarketService
        // exposes resolved snapshots that already carry change info; we keep this
        // simple and let the consumer treat null as FLAT.
        String direction = "FLAT";

        return new TimedMacro(new MacroContext(value, null, direction), snap.timestamp());
    }
}
