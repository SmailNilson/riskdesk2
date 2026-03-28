package com.riskdesk.application.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.application.dto.BrokerEntryOrderRequest;
import com.riskdesk.application.dto.BrokerEntryOrderSubmission;
import com.riskdesk.application.dto.IbkrAccountView;
import com.riskdesk.application.dto.IbkrAuthStatusView;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrRestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Service
public class ClientPortalIbkrBrokerGateway implements IbkrBrokerGateway {

    private static final Logger log = LoggerFactory.getLogger(ClientPortalIbkrBrokerGateway.class);

    private final Optional<IbkrRestClient> ibkrClient;
    private final IbkrProperties ibkrProperties;
    private final ObjectMapper objectMapper;

    public ClientPortalIbkrBrokerGateway(Optional<IbkrRestClient> ibkrClient,
                                         IbkrProperties ibkrProperties,
                                         ObjectMapper objectMapper) {
        this.ibkrClient = ibkrClient;
        this.ibkrProperties = ibkrProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public String backendName() {
        return "CLIENT_PORTAL";
    }

    @Override
    public IbkrAuthStatusView getAuthStatus() {
        if (ibkrClient.isEmpty()) {
            return new IbkrAuthStatusView(false, false, false, false, ibkrProperties.getGatewayUrl(),
                "IBKR Client Portal is not available in the backend configuration.");
        }

        JsonNode status = safeReadJson("/iserver/auth/status");
        boolean authenticated = status.path("authenticated").asBoolean(false);
        boolean connected = status.path("connected").asBoolean(false);
        boolean established = status.path("established").asBoolean(false);
        boolean competing = status.path("competing").asBoolean(false);
        String message = authenticated && connected
            ? "IBKR Client Portal authenticated"
            : "Open the Client Portal Gateway and complete the login flow.";

        return new IbkrAuthStatusView(
            authenticated,
            connected,
            established,
            competing,
            ibkrProperties.getGatewayUrl(),
            message
        );
    }

    @Override
    public IbkrAuthStatusView refreshAuthStatus() {
        if (ibkrClient.isPresent()) {
            try {
                ibkrClient.get().restTemplate().postForObject(ibkrClient.get().baseUrl() + "/tickle", null, String.class);
            } catch (Exception e) {
                log.debug("IBKR auth refresh tickle failed: {}", e.getMessage());
            }
            try {
                ibkrClient.get().restTemplate().getForObject(ibkrClient.get().baseUrl() + "/sso/validate", String.class);
            } catch (Exception e) {
                log.debug("IBKR SSO validate failed: {}", e.getMessage());
            }
            try {
                ibkrClient.get().restTemplate().getForObject(ibkrClient.get().baseUrl() + "/iserver/accounts", String.class);
            } catch (Exception e) {
                log.debug("IBKR brokerage bridge refresh failed: {}", e.getMessage());
            }
        }
        return getAuthStatus();
    }

    @Override
    public IbkrPortfolioSnapshot getPortfolio(String requestedAccountId) {
        if (ibkrClient.isEmpty()) {
            return disconnected("IBKR Client Portal is not available in the backend configuration.");
        }

        try {
            JsonNode iserverAccounts = safeReadJson("/iserver/accounts");
            JsonNode portfolioAccounts = readJson("/portfolio/accounts");

            String selectedAccountId = resolveSelectedAccountId(iserverAccounts, portfolioAccounts, requestedAccountId);
            if (selectedAccountId == null || selectedAccountId.isBlank()) {
                return disconnected("No IBKR brokerage account is available in the current Client Portal session.");
            }

            JsonNode summary = readJson("/portfolio/" + selectedAccountId + "/summary");
            JsonNode positionsNode = readJson("/portfolio/" + selectedAccountId + "/positions/0");

            List<IbkrPositionView> positions = extractPositions(positionsNode);
            BigDecimal totalUnrealizedPnl = positions.stream()
                .map(IbkrPositionView::unrealizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
            BigDecimal totalRealizedPnl = positions.stream()
                .map(IbkrPositionView::realizedPnl)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

            return new IbkrPortfolioSnapshot(
                true,
                selectedAccountId,
                extractAccounts(iserverAccounts, portfolioAccounts, selectedAccountId),
                extractAmount(summary, "netliquidation"),
                extractAmount(summary, "initmarginreq"),
                extractAmount(summary, "availablefunds"),
                extractAmount(summary, "buyingpower"),
                extractAmount(summary, "grosspositionvalue"),
                totalUnrealizedPnl,
                totalRealizedPnl,
                extractCurrency(summary, "netliquidation"),
                positions,
                null
            );
        } catch (Exception e) {
            log.warn("IBKR Client Portal portfolio fetch failed: {}", e.getMessage());
            return disconnected("IBKR portfolio unavailable: " + e.getMessage());
        }
    }

    @Override
    public BrokerEntryOrderSubmission submitEntryOrder(BrokerEntryOrderRequest request) {
        throw new UnsupportedOperationException("IBKR order placement is not implemented for Client Portal mode.");
    }

    private JsonNode readJson(String path) throws Exception {
        String payload = ibkrClient.orElseThrow().restTemplate()
            .getForObject(ibkrClient.orElseThrow().baseUrl() + path, String.class);
        if (payload == null || payload.isBlank()) {
            return objectMapper.nullNode();
        }
        return objectMapper.readTree(payload);
    }

    private JsonNode safeReadJson(String path) {
        try {
            return readJson(path);
        } catch (Exception e) {
            log.debug("IBKR optional endpoint {} unavailable: {}", path, e.getMessage());
            return objectMapper.nullNode();
        }
    }

    private String resolveSelectedAccountId(JsonNode iserverAccounts, JsonNode portfolioAccounts, String requestedAccountId) {
        if (requestedAccountId != null && !requestedAccountId.isBlank()) {
            return requestedAccountId;
        }

        String selected = iserverAccounts.path("selectedAccount").asText(null);
        if (selected != null && !selected.isBlank() && !"All".equalsIgnoreCase(selected)) {
            return selected;
        }

        if (portfolioAccounts.isArray() && !portfolioAccounts.isEmpty()) {
            return portfolioAccounts.get(0).path("id").asText(null);
        }

        JsonNode accounts = iserverAccounts.path("accounts");
        if (accounts.isArray()) {
            for (JsonNode node : accounts) {
                String account = node.asText();
                if (account != null && !account.isBlank() && !"All".equalsIgnoreCase(account)) {
                    return account;
                }
            }
        }

        return null;
    }

    private List<IbkrAccountView> extractAccounts(JsonNode iserverAccounts, JsonNode portfolioAccounts, String selectedAccountId) {
        List<IbkrAccountView> accounts = new ArrayList<>();
        if (!portfolioAccounts.isArray()) {
            return accounts;
        }

        for (JsonNode account : portfolioAccounts) {
            String id = account.path("id").asText();
            if (id == null || id.isBlank()) {
                continue;
            }
            String displayName = account.path("displayName").asText(account.path("accountTitle").asText(id));
            String currency = account.path("currency").asText("USD");
            accounts.add(new IbkrAccountView(id, displayName, currency, id.equals(selectedAccountId)));
        }

        accounts.sort(Comparator.comparing(IbkrAccountView::id));
        return accounts;
    }

    private List<IbkrPositionView> extractPositions(JsonNode positionsNode) {
        List<IbkrPositionView> positions = new ArrayList<>();
        if (!positionsNode.isArray()) {
            return positions;
        }

        for (JsonNode node : positionsNode) {
            BigDecimal position = decimal(node, "position");
            if (position.compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }

            positions.add(new IbkrPositionView(
                node.path("acctId").asText(),
                node.path("conid").asLong(),
                node.path("contractDesc").asText(),
                node.path("assetClass").asText(),
                position,
                decimal(node, "mktPrice"),
                decimal(node, "mktValue"),
                decimal(node, "avgCost"),
                decimal(node, "avgPrice"),
                decimal(node, "realizedPnl"),
                decimal(node, "unrealizedPnl"),
                node.path("currency").asText("USD")
            ));
        }

        positions.sort(Comparator.comparing(IbkrPositionView::marketValue).reversed());
        return positions;
    }

    private BigDecimal extractAmount(JsonNode summary, String field) {
        JsonNode node = summary.path(field);
        return decimal(node, "amount");
    }

    private String extractCurrency(JsonNode summary, String field) {
        String currency = summary.path(field).path("currency").asText();
        return currency == null || currency.isBlank() ? "USD" : currency;
    }

    private BigDecimal decimal(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(value.asDouble(0)).setScale(2, RoundingMode.HALF_UP);
    }

    private IbkrPortfolioSnapshot disconnected(String message) {
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
            message
        );
    }
}
