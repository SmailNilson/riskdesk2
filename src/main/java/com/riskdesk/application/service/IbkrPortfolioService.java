package com.riskdesk.application.service;

import com.riskdesk.application.dto.IbkrAuthStatusView;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrBackendMode;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class IbkrPortfolioService {
    private final IbkrProperties ibkrProperties;
    private final Map<String, IbkrBrokerGateway> gatewaysByName;

    public IbkrPortfolioService(IbkrProperties ibkrProperties,
                                List<IbkrBrokerGateway> gateways) {
        this.ibkrProperties = ibkrProperties;
        this.gatewaysByName = gateways.stream()
            .collect(Collectors.toMap(IbkrBrokerGateway::backendName, Function.identity()));
    }

    public IbkrAuthStatusView getAuthStatus() {
        if (!ibkrProperties.isEnabled()) {
            return new IbkrAuthStatusView(false, false, false, false, activeEndpoint(),
                "IBKR is disabled in the backend configuration.");
        }
        return selectedGateway().getAuthStatus();
    }

    public IbkrAuthStatusView refreshAuthStatus() {
        if (!ibkrProperties.isEnabled()) {
            return new IbkrAuthStatusView(false, false, false, false, activeEndpoint(),
                "IBKR is disabled in the backend configuration.");
        }
        return selectedGateway().refreshAuthStatus();
    }

    public IbkrPortfolioSnapshot getPortfolio(String requestedAccountId) {
        if (!ibkrProperties.isEnabled()) {
            return disabledSnapshot();
        }
        return selectedGateway().getPortfolio(requestedAccountId);
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

    private String activeEndpoint() {
        return ibkrProperties.getMode() == IbkrBackendMode.IB_GATEWAY
            ? "socket://" + ibkrProperties.getNativeHost() + ":" + ibkrProperties.getNativePort()
            : ibkrProperties.getGatewayUrl();
    }

    private IbkrPortfolioSnapshot disabledSnapshot() {
        return new IbkrPortfolioSnapshot(
            false,
            null,
            List.of(),
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP),
            "USD",
            List.of(),
            "IBKR is disabled in the backend configuration."
        );
    }
}
