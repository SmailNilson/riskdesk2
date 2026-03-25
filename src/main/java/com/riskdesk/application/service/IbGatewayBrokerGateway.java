package com.riskdesk.application.service;

import com.ib.controller.Position;
import com.riskdesk.application.dto.IbkrAccountView;
import com.riskdesk.application.dto.IbkrAuthStatusView;
import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayAccountSnapshot;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayContractResolver;
import com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayNativeClient;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class IbGatewayBrokerGateway implements IbkrBrokerGateway {
    private static final Duration PORTFOLIO_CACHE_TTL = Duration.ofSeconds(5);

    private final IbGatewayNativeClient nativeClient;
    private final IbGatewayContractResolver contractResolver;
    private final IbkrProperties ibkrProperties;
    private final ConcurrentMap<String, CachedPortfolio> portfolioCache = new ConcurrentHashMap<>();

    public IbGatewayBrokerGateway(IbGatewayNativeClient nativeClient,
                                  IbGatewayContractResolver contractResolver,
                                  IbkrProperties ibkrProperties) {
        this.nativeClient = nativeClient;
        this.contractResolver = contractResolver;
        this.ibkrProperties = ibkrProperties;
    }

    @Override
    public String backendName() {
        return "IB_GATEWAY";
    }

    @Override
    public IbkrPortfolioSnapshot getPortfolio(String requestedAccountId) {
        String cacheKey = requestedAccountId == null || requestedAccountId.isBlank() ? "__default__" : requestedAccountId;
        CachedPortfolio cached = portfolioCache.get(cacheKey);
        if (cached != null && cached.isFresh()) {
            return cached.snapshot();
        }

        Optional<IbGatewayAccountSnapshot> snapshotOpt = nativeClient.requestAccountSnapshot(requestedAccountId);
        if (snapshotOpt.isEmpty()) {
            return cached != null
                ? cached.snapshot()
                : disconnected("IB Gateway native API is selected, but no account snapshot could be loaded.");
        }

        IbGatewayAccountSnapshot snapshot = snapshotOpt.get();
        List<String> accounts = snapshot.accounts();
        String selectedAccountId = snapshot.accountId();

        List<IbkrPositionView> positions = snapshot.positions().stream()
            .map(this::toPositionView)
            .toList();

        BigDecimal totalUnrealized = value(snapshot, "UnrealizedPnL",
            positions.stream().map(IbkrPositionView::unrealizedPnl).reduce(zero(), BigDecimal::add));
        BigDecimal totalRealized = value(snapshot, "RealizedPnL",
            positions.stream().map(IbkrPositionView::realizedPnl).reduce(zero(), BigDecimal::add));

        IbkrPortfolioSnapshot result = new IbkrPortfolioSnapshot(
            true,
            selectedAccountId,
            accounts.stream()
                .map(id -> new IbkrAccountView(id, id, currency(snapshot), id.equals(selectedAccountId)))
                .toList(),
            value(snapshot, "NetLiquidation"),
            value(snapshot, "InitMarginReq"),
            value(snapshot, "AvailableFunds"),
            value(snapshot, "BuyingPower"),
            value(snapshot, "GrossPositionValue", positions.stream().map(IbkrPositionView::marketValue).reduce(zero(), BigDecimal::add)),
            totalUnrealized,
            totalRealized,
            currency(snapshot),
            positions,
            null
        );
        portfolioCache.put(cacheKey, new CachedPortfolio(result, Instant.now()));
        return result;
    }

    @Override
    public IbkrAuthStatusView getAuthStatus() {
        boolean connected = nativeClient.ensureConnected();
        String endpoint = "socket://" + ibkrProperties.getNativeHost() + ":" + ibkrProperties.getNativePort();
        String message = connected
            ? "IB Gateway native API connected"
            : "IB Gateway socket is not reachable. Start TWS or IB Gateway and enable the API socket.";

        return new IbkrAuthStatusView(connected, connected, connected, false, endpoint, message);
    }

    @Override
    public IbkrAuthStatusView refreshAuthStatus() {
        nativeClient.disconnect();
        portfolioCache.clear();
        return getAuthStatus();
    }

    private IbkrPositionView toPositionView(Position position) {
        String assetClass = position.contract().getSecType();
        String contractDesc = position.contract().localSymbol() != null && !position.contract().localSymbol().isBlank()
            ? position.contract().localSymbol()
            : position.contract().symbol();
        BigDecimal rawAveragePrice = entryPrice(position);
        BigDecimal quantity = decimal(position.position().value());
        BigDecimal marketPrice = decimal(position.marketPrice());
        if (marketPrice.compareTo(BigDecimal.ZERO) == 0) {
            marketPrice = rawAveragePrice;
        }
        BigDecimal averagePrice = rawAveragePrice.compareTo(BigDecimal.ZERO) > 0 ? rawAveragePrice : marketPrice;
        BigDecimal marketValue = decimal(position.marketValue());
        if (marketValue.compareTo(BigDecimal.ZERO) == 0 && marketPrice.compareTo(BigDecimal.ZERO) > 0) {
            marketValue = marketPrice.multiply(quantity.abs()).setScale(2, RoundingMode.HALF_UP);
        }
        BigDecimal unrealizedPnl = decimal(position.unrealPnl());
        if (unrealizedPnl.compareTo(BigDecimal.ZERO) == 0
            && rawAveragePrice.compareTo(BigDecimal.ZERO) > 0
            && marketPrice.compareTo(BigDecimal.ZERO) > 0) {
            unrealizedPnl = marketPrice.subtract(averagePrice)
                .multiply(quantity)
                .setScale(2, RoundingMode.HALF_UP);
        }

        return new IbkrPositionView(
            position.account(),
            position.conid(),
            contractDesc,
            assetClass,
            quantity,
            marketPrice,
            marketValue,
            decimal(position.averageCost()),
            averagePrice,
            decimal(position.realPnl()),
            unrealizedPnl,
            position.contract().currency() == null || position.contract().currency().isBlank() ? "USD" : position.contract().currency()
        );
    }

    private String currency(IbGatewayAccountSnapshot snapshot) {
        return snapshot.values().getOrDefault("NetLiquidation:currency",
            snapshot.values().getOrDefault("BaseCurrency", "USD"));
    }

    private BigDecimal value(IbGatewayAccountSnapshot snapshot, String key) {
        return value(snapshot, key, zero());
    }

    private BigDecimal value(IbGatewayAccountSnapshot snapshot, String key, BigDecimal fallback) {
        String raw = snapshot.values().get(key);
        if (raw == null || raw.isBlank()) {
            return fallback.setScale(2, RoundingMode.HALF_UP);
        }
        try {
            return new BigDecimal(raw).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException ex) {
            return fallback.setScale(2, RoundingMode.HALF_UP);
        }
    }

    private BigDecimal entryPrice(Position position) {
        return contractResolver.detectInstrument(position.contract())
            .map(instrument -> {
                BigDecimal multiplier = instrument.getContractMultiplier();
                if (multiplier.compareTo(BigDecimal.ZERO) == 0) {
                    return decimal(position.averageCost());
                }
                return decimal(position.averageCost()).divide(multiplier, instrument.getTickSize().scale() + 4, RoundingMode.HALF_UP);
            })
            .orElseGet(() -> decimal(position.averageCost()));
    }

    private BigDecimal decimal(String value) {
        if (value == null || value.isBlank()) {
            return zero();
        }
        try {
            return new BigDecimal(value).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            return zero();
        }
    }

    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private IbkrPortfolioSnapshot disconnected(String message) {
        return new IbkrPortfolioSnapshot(
            false,
            null,
            List.of(),
            zero(),
            zero(),
            zero(),
            zero(),
            zero(),
            zero(),
            zero(),
            "USD",
            List.of(),
            message
        );
    }

    private BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
    }

    private record CachedPortfolio(IbkrPortfolioSnapshot snapshot, Instant loadedAt) {
        private boolean isFresh() {
            return loadedAt.plus(PORTFOLIO_CACHE_TTL).isAfter(Instant.now());
        }
    }
}
