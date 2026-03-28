package com.riskdesk.application.service;

import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
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
