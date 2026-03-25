package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.TickAttrib;
import com.ib.client.TickType;
import com.ib.client.Types.BarSize;
import com.ib.client.Types.DurationUnit;
import com.ib.client.Types.WhatToShow;
import com.ib.controller.AccountSummaryTag;
import com.ib.controller.ApiConnection;
import com.ib.controller.ApiController;
import com.ib.controller.Bar;
import com.ib.controller.Position;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@Service
public class IbGatewayNativeClient {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayNativeClient.class);
    private static final Set<Integer> NOISY_INFO_CODES = Set.of(366, 2104, 2106, 2158, 2108, 2107);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration RECONNECT_COOLDOWN = Duration.ofSeconds(5);
    private static final DateTimeFormatter IB_END_DATE_TIME =
        DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss").withZone(ZoneOffset.UTC);
    private static final AccountSummaryTag[] ACCOUNT_SUMMARY_TAGS = {
        AccountSummaryTag.NetLiquidation,
        AccountSummaryTag.InitMarginReq,
        AccountSummaryTag.AvailableFunds,
        AccountSummaryTag.BuyingPower,
        AccountSummaryTag.GrossPositionValue,
        AccountSummaryTag.TotalCashValue
    };

    private final IbkrProperties properties;
    private final Object connectionLock = new Object();
    private final Object accountSnapshotLock = new Object();
    private final Object streamingLock = new Object();
    private volatile ApiController controller;
    private volatile CompletableFuture<Void> connectedFuture = new CompletableFuture<>();
    private volatile CompletableFuture<List<String>> accountsFuture = new CompletableFuture<>();
    private volatile List<String> managedAccounts = List.of();
    private volatile Instant reconnectBlockedUntil = Instant.EPOCH;
    private volatile String lastConnectionFailure = "uninitialized";
    private final Map<String, StreamingPriceSubscription> streamingSubscriptions = new ConcurrentHashMap<>();

    public IbGatewayNativeClient(IbkrProperties properties) {
        this.properties = properties;
    }

    public boolean ensureConnected() {
        CompletableFuture<Void> localConnectedFuture;
        CompletableFuture<List<String>> localAccountsFuture;

        synchronized (connectionLock) {
            if (isConnected() && !managedAccounts.isEmpty()) {
                return true;
            }

            if (controller == null) {
                if (Instant.now().isBefore(reconnectBlockedUntil)) {
                    log.debug("IB Gateway reconnect throttled until {} after failure: {}",
                        reconnectBlockedUntil, lastConnectionFailure);
                    return false;
                }
                startConnectionAttemptLocked();
            }

            localConnectedFuture = connectedFuture;
            localAccountsFuture = accountsFuture;
        }

        try {
            localConnectedFuture.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            localAccountsFuture.get(CONNECT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return isConnected() && !managedAccounts.isEmpty();
        } catch (Exception e) {
            log.warn("IB Gateway native connect failed: {}", e.getMessage());
            synchronized (connectionLock) {
                if (connectedFuture == localConnectedFuture) {
                    markReconnectCooldownLocked(e.getMessage());
                    disconnectLocked();
                }
            }
            return false;
        }
    }

    public void disconnect() {
        synchronized (connectionLock) {
            disconnectLocked();
            reconnectBlockedUntil = Instant.EPOCH;
            lastConnectionFailure = "manual disconnect";
        }
    }

    public boolean isConnected() {
        ApiController current = controller;
        return current != null && current.client() != null && current.client().isConnected();
    }

    public List<String> getManagedAccounts() {
        if (!ensureConnected()) {
            return List.of();
        }
        return managedAccounts;
    }

    public Optional<IbGatewayAccountSnapshot> requestAccountSnapshot(String requestedAccountId) {
        if (!ensureConnected()) {
            return Optional.empty();
        }

        String accountId = resolveAccountId(requestedAccountId);
        if (accountId == null) {
            return Optional.empty();
        }

        synchronized (accountSnapshotLock) {
            CountDownLatch accountLatch = new CountDownLatch(1);
            Map<String, String> values = new ConcurrentHashMap<>();
            Map<Integer, Position> positions = new ConcurrentHashMap<>();

            ApiController.IAccountHandler accountHandler = new ApiController.IAccountHandler() {
                @Override
                public void accountValue(String account, String key, String value, String currency) {
                    if (!accountId.equals(account)) {
                        return;
                    }
                    values.put(key, value);
                    if (currency != null && !currency.isBlank()) {
                        values.put(key + ":currency", currency);
                    }
                }

                @Override
                public void accountTime(String timeStamp) {
                    values.put("AccountTime", timeStamp);
                }

                @Override
                public void accountDownloadEnd(String account) {
                    if (accountId.equals(account)) {
                        accountLatch.countDown();
                    }
                }

                @Override
                public void updatePortfolio(Position position) {
                    if (position == null
                        || !accountId.equals(position.account())
                        || position.position() == null
                        || !position.position().isValid()
                        || position.position().isZero()) {
                        return;
                    }
                    positions.put(position.conid(), position);
                }
            };

            try {
                controller.reqAccountUpdates(true, accountId, accountHandler);
                boolean completed = accountLatch.await(REQUEST_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                controller.reqAccountUpdates(false, accountId, accountHandler);

                if (!completed) {
                    log.warn("IB Gateway account snapshot timed out for {}", accountId);
                }

                values.putIfAbsent("BaseCurrency", "USD");
                return Optional.of(new IbGatewayAccountSnapshot(accountId, managedAccounts, values, List.copyOf(positions.values())));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
    }

    public List<Position> requestPositions(String requestedAccountId) {
        return requestAccountSnapshot(requestedAccountId)
            .map(IbGatewayAccountSnapshot::positions)
            .orElseGet(List::of);
    }

    public List<ContractDetails> requestContractDetails(Contract contract) {
        if (!ensureConnected()) {
            return List.of();
        }

        CountDownLatch latch = new CountDownLatch(1);
        List<ContractDetails> result = Collections.synchronizedList(new ArrayList<>());

        controller.reqContractDetails(contract, list -> {
            result.addAll(list);
            latch.countDown();
        });

        awaitLatch(latch, "contract details");
        return List.copyOf(result);
    }

    public Optional<BigDecimal> requestSnapshotPrice(Contract contract) {
        if (!ensureConnected()) {
            return Optional.empty();
        }

        CountDownLatch latch = new CountDownLatch(1);
        PriceCollector collector = new PriceCollector(latch);

        controller.reqTopMktData(contract, "", true, false, collector);
        awaitLatch(latch, "market snapshot", SNAPSHOT_TIMEOUT);
        controller.cancelTopMktData(collector);

        return collector.bestPrice();
    }

    public void ensureStreamingPriceSubscription(Contract contract) {
        if (contract == null || !ensureConnected()) {
            return;
        }

        String key = subscriptionKey(contract);
        synchronized (streamingLock) {
            StreamingPriceSubscription existing = streamingSubscriptions.get(key);
            if (existing != null && existing.isActive()) {
                return;
            }

            StreamingPriceSubscription subscription = new StreamingPriceSubscription(contract);
            controller.reqTopMktData(contract, "", false, false, subscription);
            controller.reqRealTimeBars(contract, WhatToShow.TRADES, false, subscription);
            streamingSubscriptions.put(key, subscription);
            log.info("IB Gateway live stream subscribed for {}", describeContract(contract));
        }
    }

    public Optional<BigDecimal> latestStreamingPrice(Contract contract) {
        if (contract == null) {
            return Optional.empty();
        }
        StreamingPriceSubscription subscription = streamingSubscriptions.get(subscriptionKey(contract));
        return subscription == null ? Optional.empty() : subscription.bestPrice();
    }

    public List<Bar> requestHistoricalBars(Contract contract,
                                           int duration,
                                           DurationUnit durationUnit,
                                           BarSize barSize,
                                           WhatToShow whatToShow,
                                           boolean rthOnly) {
        return requestHistoricalBars(contract, null, duration, durationUnit, barSize, whatToShow, rthOnly);
    }

    public List<Bar> requestHistoricalBars(Contract contract,
                                           Instant endDateTime,
                                           int duration,
                                           DurationUnit durationUnit,
                                           BarSize barSize,
                                           WhatToShow whatToShow,
                                           boolean rthOnly) {
        if (!ensureConnected()) {
            return List.of();
        }

        CountDownLatch latch = new CountDownLatch(1);
        List<Bar> bars = Collections.synchronizedList(new ArrayList<>());

        ApiController.IHistoricalDataHandler handler = new ApiController.IHistoricalDataHandler() {
            @Override
            public void historicalData(Bar bar) {
                bars.add(bar);
            }

            @Override
            public void historicalDataEnd() {
                latch.countDown();
            }
        };

        controller.reqHistoricalData(
            contract,
            endDateTime == null ? "" : IB_END_DATE_TIME.format(endDateTime),
            duration,
            durationUnit,
            barSize,
            whatToShow,
            rthOnly,
            false,
            handler
        );
        awaitLatch(latch, "historical data");
        controller.cancelHistoricalData(handler);
        return List.copyOf(bars);
    }

    private String resolveAccountId(String requestedAccountId) {
        if (requestedAccountId != null && !requestedAccountId.isBlank() && managedAccounts.contains(requestedAccountId)) {
            return requestedAccountId;
        }
        return managedAccounts.isEmpty() ? null : managedAccounts.get(0);
    }

    private void startConnectionAttemptLocked() {
        connectedFuture = new CompletableFuture<>();
        accountsFuture = new CompletableFuture<>();
        managedAccounts = List.of();

        controller = new ApiController(new ConnectionHandler(), silentLogger(), silentLogger());
        controller.connect(properties.getNativeHost(), properties.getNativePort(), properties.getNativeClientId(), "");
    }

    private void disconnectLocked() {
        ApiController current = controller;
        controller = null;
        managedAccounts = List.of();
        if (current != null) {
            streamingSubscriptions.values().forEach(subscription -> {
                try {
                    current.cancelTopMktData(subscription);
                } catch (Exception ignored) {
                }
                try {
                    current.cancelRealtimeBars(subscription);
                } catch (Exception ignored) {
                }
            });
            try {
                current.disconnect();
            } catch (Exception ignored) {
            }
        }
        streamingSubscriptions.clear();
    }

    private void markReconnectCooldownLocked(String reason) {
        reconnectBlockedUntil = Instant.now().plus(RECONNECT_COOLDOWN);
        lastConnectionFailure = reason == null || reason.isBlank() ? "unknown failure" : reason;
    }

    private void awaitLatch(CountDownLatch latch, String label) {
        awaitLatch(latch, label, REQUEST_TIMEOUT);
    }

    private void awaitLatch(CountDownLatch latch, String label, Duration timeout) {
        try {
            boolean completed = latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                if ("market snapshot".equals(label)) {
                    log.debug("IB Gateway {} request timed out.", label);
                } else {
                    log.warn("IB Gateway {} request timed out.", label);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ApiConnection.ILogger silentLogger() {
        return value -> { };
    }

    private String subscriptionKey(Contract contract) {
        int conid = contract.conid();
        if (conid > 0) {
            return "conid:" + conid;
        }
        return String.join("|",
            nullSafe(contract.symbol()),
            nullSafe(contract.exchange()),
            nullSafe(contract.currency()),
            nullSafe(contract.lastTradeDateOrContractMonth()),
            nullSafe(contract.tradingClass()));
    }

    private String describeContract(Contract contract) {
        if (contract == null) {
            return "unknown-contract";
        }
        if (contract.conid() > 0) {
            return contract.symbol() + " [" + contract.conid() + "]";
        }
        return contract.symbol() + "@" + contract.exchange();
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private final class ConnectionHandler implements ApiController.IConnectionHandler {
        @Override
        public void connected() {
            connectedFuture.complete(null);
            reconnectBlockedUntil = Instant.EPOCH;
            lastConnectionFailure = "connected";
            try {
                controller.reqMktDataType(1);
            } catch (Exception e) {
                log.debug("Unable to request IB Gateway live market data type: {}", e.getMessage());
            }
            log.info("IB Gateway native API connected on {}:{} with clientId={}",
                properties.getNativeHost(), properties.getNativePort(), properties.getNativeClientId());
        }

        @Override
        public void disconnected() {
            log.warn("IB Gateway native API disconnected.");
            synchronized (connectionLock) {
                managedAccounts = List.of();
                markReconnectCooldownLocked("IB Gateway disconnected");
                if (!connectedFuture.isDone()) {
                    connectedFuture.completeExceptionally(new IllegalStateException("IB Gateway disconnected"));
                }
                if (!accountsFuture.isDone()) {
                    accountsFuture.completeExceptionally(new IllegalStateException("IB Gateway disconnected"));
                }
                controller = null;
            }
        }

        @Override
        public void accountList(List<String> list) {
            managedAccounts = List.copyOf(list);
            accountsFuture.complete(managedAccounts);
        }

        @Override
        public void error(Exception e) {
            log.warn("IB Gateway native error: {}", e.getMessage());
            synchronized (connectionLock) {
                if (!connectedFuture.isDone()) {
                    connectedFuture.completeExceptionally(e);
                }
                markReconnectCooldownLocked(e.getMessage());
            }
        }

        @Override
        public void message(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
            if (NOISY_INFO_CODES.contains(errorCode)) {
                log.debug("IB Gateway {}: {}", errorCode, errorMsg);
                return;
            }
            if (errorCode == 326 || errorCode == 502 || errorCode == 504) {
                synchronized (connectionLock) {
                    if (!connectedFuture.isDone()) {
                        connectedFuture.completeExceptionally(new IllegalStateException(errorMsg));
                    }
                    markReconnectCooldownLocked(errorMsg);
                    disconnectLocked();
                }
            }
            log.warn("IB Gateway message id={} code={} msg={}", id, errorCode, errorMsg);
        }

        @Override
        public void show(String string) {
            log.debug("IB Gateway show: {}", string);
        }
    }

    private static final class PriceCollector extends ApiController.TopMktDataAdapter {
        private final CountDownLatch latch;
        private volatile Double bestPrice;
        private volatile Double bid;
        private volatile Double ask;

        private PriceCollector(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void tickPrice(TickType tickType, double price, TickAttrib attribs) {
            if (price <= 0) return;
            if (tickType == TickType.LAST || tickType == TickType.DELAYED_LAST) {
                bestPrice = price;
            } else if (tickType == TickType.BID || tickType == TickType.DELAYED_BID) {
                bid = price;
                if (bestPrice == null && ask != null && ask > 0) {
                    bestPrice = (bid + ask) / 2.0;
                }
            } else if (tickType == TickType.ASK || tickType == TickType.DELAYED_ASK) {
                ask = price;
                if (bestPrice == null && bid != null && bid > 0) {
                    bestPrice = (bid + ask) / 2.0;
                }
            } else if (bestPrice == null && (tickType == TickType.CLOSE || tickType == TickType.DELAYED_CLOSE)) {
                bestPrice = price;
            }
        }

        @Override
        public void tickSnapshotEnd() {
            latch.countDown();
        }

        private Optional<BigDecimal> bestPrice() {
            return bestPrice == null ? Optional.empty() : Optional.of(BigDecimal.valueOf(bestPrice));
        }
    }

    private final class StreamingPriceSubscription implements ApiController.ITopMktDataHandler, ApiController.IRealTimeBarHandler {
        private final Contract contract;
        private volatile Double bestPrice;
        private volatile Double bid;
        private volatile Double ask;
        private volatile boolean active = true;
        private volatile boolean loggedFirstPrice = false;

        private StreamingPriceSubscription(Contract contract) {
            this.contract = contract;
        }

        @Override
        public void tickPrice(TickType tickType, double price, TickAttrib attribs) {
            if (price <= 0) return;

            if (tickType == TickType.LAST || tickType == TickType.DELAYED_LAST) {
                bestPrice = price;
            } else if (tickType == TickType.BID || tickType == TickType.DELAYED_BID) {
                bid = price;
                if (bestPrice == null && ask != null && ask > 0) {
                    bestPrice = (bid + ask) / 2.0;
                }
            } else if (tickType == TickType.ASK || tickType == TickType.DELAYED_ASK) {
                ask = price;
                if (bestPrice == null && bid != null && bid > 0) {
                    bestPrice = (bid + ask) / 2.0;
                }
            } else if (bestPrice == null && (tickType == TickType.CLOSE || tickType == TickType.DELAYED_CLOSE)) {
                bestPrice = price;
            }

            if (bestPrice != null) {
                logFirstPrice("tickPrice");
            }
        }

        @Override
        public void marketDataType(int marketDataType) {
            active = true;
            log.info("IB Gateway market data type {} for {}", marketDataType, describeContract(contract));
        }

        @Override
        public void tickSize(TickType tickType, Decimal size) {
            // no-op
        }

        @Override
        public void tickString(TickType tickType, String value) {
            // no-op
        }

        @Override
        public void tickReqParams(int tickerId, double minTick, String bboExchange, int snapshotPermissions) {
            // no-op
        }

        @Override
        public void tickReqParamsProtoBuf(com.ib.client.protobuf.TickReqParamsProto.TickReqParams ignored) {
            // no-op
        }

        @Override
        public void tickSnapshotEnd() {
            // Streaming subscriptions should not complete, but IB may still emit this callback.
        }

        @Override
        public void realtimeBar(com.ib.controller.Bar bar) {
            if (bar == null || bar.close() <= 0) {
                return;
            }
            bestPrice = bar.close();
            logFirstPrice("realtimeBar");
        }

        private Optional<BigDecimal> bestPrice() {
            return bestPrice == null ? Optional.empty() : Optional.of(BigDecimal.valueOf(bestPrice));
        }

        private boolean isActive() {
            return active;
        }

        private void logFirstPrice(String source) {
            if (!loggedFirstPrice) {
                loggedFirstPrice = true;
                log.info("IB Gateway first live price for {} received via {}", describeContract(contract), source);
            }
        }
    }
}
