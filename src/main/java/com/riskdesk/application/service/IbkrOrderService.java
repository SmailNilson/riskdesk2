package com.riskdesk.application.service;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.BrokerOrderLookup;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrBackendMode;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class IbkrOrderService {

    private final IbkrProperties ibkrProperties;
    private final Map<String, IbkrBrokerGateway> gatewaysByName;

    public IbkrOrderService(IbkrProperties ibkrProperties,
                            List<IbkrBrokerGateway> gateways) {
        this.ibkrProperties = ibkrProperties;
        this.gatewaysByName = gateways.stream()
            .collect(Collectors.toMap(IbkrBrokerGateway::backendName, Function.identity()));
    }

    public BrokerEntryOrderSubmission submitEntryOrder(BrokerEntryOrderRequest request) {
        if (!ibkrProperties.isEnabled()) {
            throw new IllegalStateException("IBKR is disabled in the backend configuration.");
        }
        return selectedGateway().submitEntryOrder(request);
    }

    /**
     * Cancels a working broker order by its IBKR order id. Throws {@code IllegalStateException}
     * when IBKR is disabled or the broker explicitly refuses the cancel (e.g. already filled);
     * the execution row is finalized asynchronously by the {@code Cancelled} orderStatus callback.
     */
    public String cancelOrder(int ibkrOrderId) {
        if (!ibkrProperties.isEnabled()) {
            throw new IllegalStateException("IBKR is disabled in the backend configuration.");
        }
        return selectedGateway().cancelOrder(ibkrOrderId);
    }

    /**
     * Looks up a broker order by its {@code orderRef} (the WTX {@code executionKey}) — live order
     * book first, then completed/historical orders. Tri-state: {@code UNAVAILABLE} when IBKR is
     * disabled or the gateway can't be queried, {@code NOT_FOUND} when the order is in neither set,
     * {@code FOUND} with the status otherwise. Used by the stale-entry reconciler, which must never
     * treat UNAVAILABLE as absence. {@code UNAVAILABLE} when IBKR is disabled.
     */
    public BrokerOrderLookup findOrder(String accountId, String orderRef) {
        if (!ibkrProperties.isEnabled()) {
            return BrokerOrderLookup.unavailable();
        }
        return selectedGateway().findOrder(accountId, orderRef);
    }

    private IbkrBrokerGateway selectedGateway() {
        String backend = ibkrProperties.getMode().name();
        IbkrBrokerGateway selected = gatewaysByName.get(backend);
        if (selected != null) {
            return selected;
        }

        IbkrBrokerGateway ibGateway = gatewaysByName.get(IbkrBackendMode.IB_GATEWAY.name());
        if (ibGateway != null) {
            return ibGateway;
        }

        throw new IllegalStateException("No IBKR broker gateway bean is available for mode " + backend);
    }
}
