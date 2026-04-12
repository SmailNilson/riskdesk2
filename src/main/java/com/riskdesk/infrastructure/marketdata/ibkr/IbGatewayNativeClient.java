package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.ContractDetails;
import com.ib.client.Decimal;
import com.ib.client.HistoricalTick;
import com.ib.client.HistoricalTickBidAsk;
import com.ib.client.HistoricalTickLast;
import com.ib.client.Order;
import com.ib.client.OrderState;
import com.ib.client.OrderStatus;
import com.ib.client.OrderType;
import com.ib.client.TickAttrib;
import com.ib.client.TickAttribLast;
import com.ib.client.TickType;
import com.ib.client.Types.Action;
import com.ib.client.Types.BarSize;
import com.ib.client.Types.DurationUnit;
import com.ib.client.Types.WhatToShow;
import com.ib.controller.AccountSummaryTag;
import com.ib.controller.ApiConnection;
import com.ib.controller.ApiController;
import com.ib.controller.Bar;
import com.ib.controller.Position;
import com.riskdesk.domain.marketdata.port.StreamingPriceListener;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.shared.TradingSessionResolver;
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
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class IbGatewayNativeClient {

    private static final Logger log = LoggerFactory.getLogger(IbGatewayNativeClient.class);
    private static final Set<Integer> NOISY_INFO_CODES = Set.of(366, 2104, 2106, 2158, 2108, 2107);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration SNAPSHOT_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration ORDER_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RECONNECT_COOLDOWN = Duration.ofSeconds(5);
    /** Subscriptions that produce no price tick for this long are considered stale. */
    private static final long STALE_PRICE_SECONDS = 60L;
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

    /**
     * Single-threaded executor used to cancel IBKR subscriptions and disconnect the controller
     * OUTSIDE the connectionLock, preventing long I/O holds under the lock.
     */
    private static final ExecutorService CLEANUP_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ibkr-cleanup");
        t.setDaemon(true);
        return t;
    });

    private final IbkrProperties properties;
    private final Object connectionLock = new Object();
    private final Object accountSnapshotLock = new Object();
    private final Object streamingLock = new Object();
    private final Object orderPlacementLock = new Object();
    private volatile ApiController controller;
    private volatile CompletableFuture<Void> connectedFuture = new CompletableFuture<>();
    private volatile CompletableFuture<List<String>> accountsFuture = new CompletableFuture<>();
    private volatile List<String> managedAccounts = List.of();
    private volatile Instant reconnectBlockedUntil = Instant.EPOCH;
    private volatile String lastConnectionFailure = "uninitialized";
    private final Map<String, StreamingPriceSubscription> streamingSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, StreamingQuoteSubscription> streamingQuoteSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, TickByTickSubscription> tickByTickSubscriptions = new ConcurrentHashMap<>();
    private final Map<String, Instrument> contractKeyToInstrument = new ConcurrentHashMap<>();
    private volatile StreamingPriceListener priceListener;
    private volatile IbkrTickDataAdapter tickDataAdapter;
    private volatile SubscriptionRegistry subscriptionRegistry;
    private volatile IbGatewayContractResolver contractResolverRef;
    private volatile int depthNumRows = 10;

    public IbGatewayNativeClient(IbkrProperties properties) {
        this.properties = properties;
    }

    /**
     * Registers the tick data adapter that will receive classified ticks from
     * tick-by-tick AllLast subscriptions. Must be called before subscribing.
     */
    public void setTickDataAdapter(IbkrTickDataAdapter adapter) {
        this.tickDataAdapter = adapter;
    }

    /**
     * Registers the subscription registry that tracks intended subscriptions
     * for reconnection resilience (UC-OF-015).
     */
    public void setSubscriptionRegistry(SubscriptionRegistry registry) {
        this.subscriptionRegistry = registry;
    }

    /**
     * Registers the contract resolver used by {@link #resubscribeAll()} to map
     * instruments back to IBKR contracts after a reconnection.
     */
    public void setContractResolver(IbGatewayContractResolver resolver) {
        this.contractResolverRef = resolver;
    }

    /**
     * Sets the default depth row count for resubscription.
     */
    public void setDepthNumRows(int numRows) {
        this.depthNumRows = numRows;
    }

    /**
     * Registers a listener that will be called on the IBKR EReader thread whenever a
     * new price tick or 5-second bar arrives. The listener MUST be non-blocking.
     */
    public void setPriceListener(StreamingPriceListener listener) {
        this.priceListener = listener;
    }

    /**
     * Associates a subscription key with its domain Instrument so that push callbacks
     * can identify which instrument a tick belongs to.
     */
    public void registerInstrumentMapping(Contract contract, Instrument instrument) {
        contractKeyToInstrument.put(subscriptionKey(contract), instrument);
    }

    /**
     * Atomically cancels all streaming subscriptions for {@code oldContract} and
     * starts fresh subscriptions for {@code newContract}. Used during contract
     * rollover to prevent orphaned IBKR data streams and stale reqId mappings.
     *
     * Thread-safe: all mutations happen under {@link #streamingLock}.
     */
    public void cancelAndResubscribe(Contract oldContract, Contract newContract, Instrument instrument) {
        if (oldContract == null && newContract == null) return;

        synchronized (streamingLock) {
            // --- Cancel old subscriptions ---
            if (oldContract != null) {
                String oldKey = subscriptionKey(oldContract);
                ApiController ctrl = controller;

                StreamingPriceSubscription oldPrice = streamingSubscriptions.remove(oldKey);
                if (oldPrice != null && ctrl != null) {
                    try { ctrl.cancelTopMktData(oldPrice); } catch (Exception ignored) {}
                    try { ctrl.cancelRealtimeBars(oldPrice); } catch (Exception ignored) {}
                    log.info("Rollover: cancelled price stream for old contract {}", describeContract(oldContract));
                }

                StreamingQuoteSubscription oldQuote = streamingQuoteSubscriptions.remove(oldKey);
                if (oldQuote != null && ctrl != null) {
                    try { ctrl.cancelTopMktData(oldQuote); } catch (Exception ignored) {}
                    log.info("Rollover: cancelled quote stream for old contract {}", describeContract(oldContract));
                }

                contractKeyToInstrument.remove(oldKey);

                // Cancel tick-by-tick subscription for old contract
                TickByTickSubscription oldTick = tickByTickSubscriptions.remove(oldKey);
                if (oldTick != null && ctrl != null) {
                    try { ctrl.cancelTickByTickData(oldTick); } catch (Exception ignored) {}
                    log.info("Rollover: cancelled tick-by-tick for old contract {}", describeContract(oldContract));
                }

                // Cancel depth subscription for old contract
                DepthSubscription oldDepth = depthSubscriptions.remove(oldKey);
                if (oldDepth != null && ctrl != null) {
                    try { ctrl.cancelDeepMktData(false, oldDepth); } catch (Exception ignored) {}
                    log.info("Rollover: cancelled depth for old contract {}", describeContract(oldContract));
                }
            }
        }

        // --- Subscribe to new contract (outside streamingLock — ensureStreaming acquires it internally) ---
        if (newContract != null) {
            if (instrument != null) {
                registerInstrumentMapping(newContract, instrument);
            }
            ensureStreamingPriceSubscription(newContract);
            ensureStreamingQuoteSubscription(newContract);
            // Re-subscribe tick-by-tick if adapter is wired
            if (tickDataAdapter != null && instrument != null) {
                subscribeTickByTick(newContract, instrument);
            }
            // Re-subscribe depth if adapter is wired
            if (depthAdapter != null && instrument != null) {
                subscribeDepth(newContract, instrument, depthNumRows);
            }
            log.info("Rollover: subscribed to new contract {}", describeContract(newContract));
        }
    }

    // -------------------------------------------------------------------------
    // Connection management
    // -------------------------------------------------------------------------

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
            DisconnectContext ctx = null;
            synchronized (connectionLock) {
                if (connectedFuture == localConnectedFuture) {
                    markReconnectCooldownLocked(e.getMessage());
                    ctx = clearStateLocked();
                }
            }
            if (ctx != null) {
                submitCleanup(ctx, false);
            }
            return false;
        }
    }

    public void disconnect() {
        DisconnectContext ctx;
        synchronized (connectionLock) {
            ctx = clearStateLocked();
            reconnectBlockedUntil = Instant.EPOCH;
            lastConnectionFailure = "manual disconnect";
        }
        submitCleanup(ctx, true);
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

    // -------------------------------------------------------------------------
    // Account & position data
    // -------------------------------------------------------------------------

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

    public Optional<NativeOrderSnapshot> findOrderByOrderRef(String requestedAccountId, String orderRef) {
        if (orderRef == null || orderRef.isBlank() || !ensureConnected()) {
            return Optional.empty();
        }

        String accountId = resolveAccountId(requestedAccountId);
        if (accountId == null) {
            return Optional.empty();
        }

        Optional<NativeOrderSnapshot> openOrder = findOpenOrderByOrderRef(accountId, orderRef);
        return openOrder.isPresent() ? openOrder : findCompletedOrderByOrderRef(accountId, orderRef);
    }

    public NativeOrderSubmission placeLimitOrder(Contract contract,
                                                 String requestedAccountId,
                                                 Action action,
                                                 int quantity,
                                                 BigDecimal limitPrice,
                                                 String orderRef) {
        if (contract == null) {
            throw new IllegalArgumentException("contract is required");
        }
        if (quantity < 1) {
            throw new IllegalArgumentException("quantity must be >= 1");
        }
        if (limitPrice == null || limitPrice.signum() <= 0) {
            throw new IllegalArgumentException("limitPrice must be > 0");
        }
        if (orderRef == null || orderRef.isBlank()) {
            throw new IllegalArgumentException("orderRef is required");
        }
        if (!ensureConnected()) {
            throw new IllegalStateException("IB Gateway native API is not connected.");
        }

        String accountId = resolveAccountId(requestedAccountId);
        if (accountId == null) {
            throw new IllegalStateException("No IBKR account is available for order placement.");
        }

        Optional<NativeOrderSnapshot> existing = findOrderByOrderRef(accountId, orderRef);
        if (existing.isPresent()) {
            NativeOrderSnapshot snapshot = existing.get();
            return new NativeOrderSubmission(snapshot.orderId(), snapshot.status(), snapshot.orderRef(), Instant.now());
        }

        Order order = new Order();
        order.account(accountId);
        order.orderRef(orderRef);
        order.action(action);
        order.orderType(OrderType.LMT);
        order.totalQuantity(Decimal.get(quantity));
        order.lmtPrice(limitPrice.doubleValue());
        order.tif("DAY");
        order.transmit(true);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<String> acceptedStatus = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();

        ApiController.IOrderHandler handler = new ApiController.IOrderHandler() {
            @Override
            public void orderState(OrderState orderState, Order liveOrder) {
                if (liveOrder != null
                    && orderRef.equals(liveOrder.orderRef())
                    && orderState != null
                    && orderState.rejectReason() != null
                    && !orderState.rejectReason().isBlank()) {
                    error.compareAndSet(null, orderState.rejectReason());
                    latch.countDown();
                }
            }

            @Override
            public void orderStatus(OrderStatus status,
                                    Decimal filled,
                                    Decimal remaining,
                                    double avgFillPrice,
                                    long permId,
                                    int parentId,
                                    double lastFillPrice,
                                    int clientId,
                                    String whyHeld,
                                    double mktCapPrice) {
                if (status == null) {
                    return;
                }
                if (status == OrderStatus.Submitted
                    || status == OrderStatus.PreSubmitted
                    || status == OrderStatus.PendingSubmit
                    || status == OrderStatus.Filled) {
                    acceptedStatus.compareAndSet(null, status.name());
                    latch.countDown();
                    return;
                }
                if (status == OrderStatus.Cancelled || status == OrderStatus.ApiCancelled || status == OrderStatus.Inactive) {
                    String suffix = whyHeld == null || whyHeld.isBlank() ? "" : " (" + whyHeld + ")";
                    error.compareAndSet(null, "IBKR order " + status.name() + suffix);
                    latch.countDown();
                }
            }

            @Override
            public void handle(int orderId, String errorMsg) {
                error.compareAndSet(null, errorMsg == null || errorMsg.isBlank()
                    ? "IBKR order submission failed."
                    : errorMsg);
                latch.countDown();
            }
        };

        synchronized (orderPlacementLock) {
            controller.placeOrModifyOrder(contract, order, handler);
        }

        boolean completed = false;
        try {
            completed = latch.await(ORDER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            error.compareAndSet(null, "Interrupted while waiting for IBKR order acknowledgement.");
        } finally {
            try {
                controller.removeOrderHandler(handler);
            } catch (Exception ignored) {
            }
        }

        if (acceptedStatus.get() != null) {
            return new NativeOrderSubmission((long) order.orderId(), acceptedStatus.get(), orderRef, Instant.now());
        }

        Optional<NativeOrderSnapshot> recovered = findOrderByOrderRef(accountId, orderRef);
        if (recovered.isPresent()) {
            NativeOrderSnapshot snapshot = recovered.get();
            return new NativeOrderSubmission(snapshot.orderId(), snapshot.status(), snapshot.orderRef(), Instant.now());
        }

        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
        if (!completed) {
            throw new IllegalStateException("IBKR order submission timed out without acknowledgement.");
        }
        throw new IllegalStateException("IBKR order submission did not yield a confirmed status.");
    }


    // -------------------------------------------------------------------------
    // Contract details
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Market data: snapshot
    // -------------------------------------------------------------------------

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

    /**
     * One-shot snapshot request for Futures Open Interest.
     * Uses reqTopMktData with snapshot=true and captures TickType.FUTURES_OPEN_INTEREST
     * via the tickSize() callback.
     */
    public OptionalLong requestSnapshotOpenInterest(Contract contract) {
        if (!ensureConnected()) {
            return OptionalLong.empty();
        }

        CountDownLatch latch = new CountDownLatch(1);
        OpenInterestCollector collector = new OpenInterestCollector(latch);

        // Request with generic tick "588" (FUTURES_OPEN_INTEREST) in non-snapshot mode
        // so IBKR sends the OI tick. We cancel after receiving it or after timeout.
        controller.reqTopMktData(contract, "588", false, false, collector);
        awaitLatch(latch, "open interest snapshot", REQUEST_TIMEOUT);
        controller.cancelTopMktData(collector);

        return collector.openInterest();
    }

    /**
     * One-shot snapshot to get the trading volume for a contract.
     * Used as fallback when OI is unavailable for contract selection.
     */
    public OptionalLong requestSnapshotVolume(Contract contract) {
        if (!ensureConnected()) {
            return OptionalLong.empty();
        }

        CountDownLatch latch = new CountDownLatch(1);
        VolumeCollector collector = new VolumeCollector(latch);

        // Default ticks include volume (TickType.VOLUME = tick 8)
        controller.reqTopMktData(contract, "", false, false, collector);
        awaitLatch(latch, "volume snapshot", REQUEST_TIMEOUT);
        controller.cancelTopMktData(collector);

        return collector.volume();
    }

    public Optional<NativeMarketQuote> requestSnapshotQuote(Contract contract) {
        if (!ensureConnected()) {
            return Optional.empty();
        }

        CountDownLatch latch = new CountDownLatch(1);
        QuoteCollector collector = new QuoteCollector(latch);

        controller.reqTopMktData(contract, "", true, false, collector);
        awaitLatch(latch, "market quote snapshot", SNAPSHOT_TIMEOUT);
        controller.cancelTopMktData(collector);

        return collector.quote();
    }

    // -------------------------------------------------------------------------
    // Market data: streaming subscriptions
    // -------------------------------------------------------------------------

    /**
     * Ensures a live streaming subscription exists for the given contract.
     * If an existing subscription has gone stale (no price tick for {@value STALE_PRICE_SECONDS}s),
     * the old handler is cancelled and a fresh subscription is created.
     */
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

            // Cancel stale/dead subscription before creating a fresh one.
            if (existing != null) {
                ApiController ctrl = controller;
                if (ctrl != null) {
                    try { ctrl.cancelTopMktData(existing); } catch (Exception ignored) {}
                    try { ctrl.cancelRealtimeBars(existing); } catch (Exception ignored) {}
                }
                log.warn("IB Gateway detected stale stream for {} — re-subscribing", describeContract(contract));
            }

            StreamingPriceSubscription subscription = new StreamingPriceSubscription(contract);
            controller.reqTopMktData(contract, "", false, false, subscription);
            controller.reqRealTimeBars(contract, WhatToShow.TRADES, false, subscription);
            streamingSubscriptions.put(key, subscription);
            log.info("IB Gateway live stream subscribed for {}", describeContract(contract));

            // Register in subscription registry for reconnection resilience
            SubscriptionRegistry reg = subscriptionRegistry;
            Instrument inst = contractKeyToInstrument.get(key);
            if (reg != null && inst != null) {
                reg.register(inst, SubscriptionRegistry.SubscriptionType.PRICE);
            }
        }
    }

    public Optional<BigDecimal> latestStreamingPrice(Contract contract) {
        if (contract == null) {
            return Optional.empty();
        }
        StreamingPriceSubscription subscription = streamingSubscriptions.get(subscriptionKey(contract));
        return subscription == null ? Optional.empty() : subscription.bestPrice();
    }

    /**
     * Cancels all streaming subscriptions (price + quote) for the given instrument,
     * regardless of contract month. Called during contract rollover to prevent orphaned
     * subscriptions on expired months from producing stale prices.
     */
    public void cancelInstrumentSubscriptions(Instrument instrument) {
        List<String> keysToRemove = contractKeyToInstrument.entrySet().stream()
            .filter(e -> e.getValue() == instrument)
            .map(Map.Entry::getKey)
            .toList();

        if (keysToRemove.isEmpty()) return;

        synchronized (streamingLock) {
            ApiController ctrl = controller;
            for (String key : keysToRemove) {
                StreamingPriceSubscription priceSub = streamingSubscriptions.remove(key);
                if (priceSub != null && ctrl != null) {
                    try { ctrl.cancelTopMktData(priceSub); } catch (Exception ignored) {}
                    try { ctrl.cancelRealtimeBars(priceSub); } catch (Exception ignored) {}
                }
                StreamingQuoteSubscription quoteSub = streamingQuoteSubscriptions.remove(key);
                if (quoteSub != null && ctrl != null) {
                    try { ctrl.cancelTopMktData(quoteSub); } catch (Exception ignored) {}
                }
                contractKeyToInstrument.remove(key);
            }
        }

        log.info("IB Gateway cancelled {} subscription(s) for {} (contract rollover cleanup)",
            keysToRemove.size(), instrument);
    }

    public void ensureStreamingQuoteSubscription(Contract contract) {
        if (contract == null || !ensureConnected()) {
            return;
        }

        String key = subscriptionKey(contract);
        synchronized (streamingLock) {
            StreamingQuoteSubscription existing = streamingQuoteSubscriptions.get(key);
            if (existing != null && existing.isActive()) {
                return;
            }

            if (existing != null) {
                ApiController ctrl = controller;
                if (ctrl != null) {
                    try { ctrl.cancelTopMktData(existing); } catch (Exception ignored) {}
                }
                log.warn("IB Gateway detected stale quote stream for {} — re-subscribing", describeContract(contract));
            }

            StreamingQuoteSubscription subscription = new StreamingQuoteSubscription(contract);
            controller.reqTopMktData(contract, "", false, false, subscription);
            streamingQuoteSubscriptions.put(key, subscription);
            log.info("IB Gateway live quote stream subscribed for {}", describeContract(contract));
        }
    }

    public Optional<NativeMarketQuote> latestStreamingQuote(Contract contract) {
        if (contract == null) {
            return Optional.empty();
        }
        StreamingQuoteSubscription subscription = streamingQuoteSubscriptions.get(subscriptionKey(contract));
        return subscription == null ? Optional.empty() : subscription.quote();
    }

    // -------------------------------------------------------------------------
    // Tick-by-tick data (AllLast) — UC-OF-001
    // -------------------------------------------------------------------------

    /**
     * Subscribes to tick-by-tick AllLast data for the given contract.
     * Each trade tick is classified via Lee-Ready rule and routed to
     * {@link IbkrTickDataAdapter#onTickByTickTrade}.
     *
     * <p>Follows the same idempotence pattern as {@link #ensureStreamingPriceSubscription}:
     * if a subscription already exists and is active, this is a no-op.
     * IBKR supports max ~5 concurrent tick-by-tick subscriptions per connection.</p>
     *
     * @param contract   the IBKR contract to subscribe
     * @param instrument the domain instrument for routing
     */
    public void subscribeTickByTick(Contract contract, Instrument instrument) {
        if (contract == null || !ensureConnected()) {
            return;
        }
        if (tickDataAdapter == null) {
            log.warn("Cannot subscribe tick-by-tick for {} — tickDataAdapter not wired", instrument);
            return;
        }

        String key = subscriptionKey(contract);
        synchronized (streamingLock) {
            TickByTickSubscription existing = tickByTickSubscriptions.get(key);
            if (existing != null && existing.isActive()) {
                return;
            }

            // Cancel stale subscription before creating a fresh one
            if (existing != null) {
                ApiController ctrl = controller;
                if (ctrl != null) {
                    try { ctrl.cancelTickByTickData(existing); } catch (Exception ignored) {}
                }
                log.warn("IB Gateway detected stale tick-by-tick stream for {} — re-subscribing", instrument);
            }

            TickByTickSubscription subscription = new TickByTickSubscription(contract, instrument);
            controller.reqTickByTickData(contract, "AllLast", 0, false, subscription);
            tickByTickSubscriptions.put(key, subscription);
            log.info("IB Gateway tick-by-tick AllLast subscribed for {} ({})", instrument, describeContract(contract));

            // Register in subscription registry for reconnection resilience
            SubscriptionRegistry reg = subscriptionRegistry;
            if (reg != null) {
                reg.register(instrument, SubscriptionRegistry.SubscriptionType.TICK_BY_TICK);
            }
        }
    }

    /**
     * Cancels tick-by-tick subscription for a specific contract.
     * Called during rollover cleanup.
     */
    public void cancelTickByTickSubscription(Contract contract) {
        if (contract == null) return;
        String key = subscriptionKey(contract);
        synchronized (streamingLock) {
            TickByTickSubscription sub = tickByTickSubscriptions.remove(key);
            if (sub != null) {
                ApiController ctrl = controller;
                if (ctrl != null) {
                    try { ctrl.cancelTickByTickData(sub); } catch (Exception ignored) {}
                }
                log.info("IB Gateway cancelled tick-by-tick for {}", sub.instrument);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Market depth (Level 2) — UC-OF-003
    // -------------------------------------------------------------------------

    private volatile IbkrMarketDepthAdapter depthAdapter;
    private final Map<String, DepthSubscription> depthSubscriptions = new ConcurrentHashMap<>();

    public void setDepthAdapter(IbkrMarketDepthAdapter adapter) {
        this.depthAdapter = adapter;
    }

    /**
     * Subscribes to Level 2 market depth for the given contract.
     * Only for MNQ, MCL, MGC (max 3 concurrent depth subscriptions on IBKR).
     */
    public void subscribeDepth(Contract contract, Instrument instrument, int numRows) {
        if (contract == null || !ensureConnected()) return;
        if (depthAdapter == null) {
            log.warn("Cannot subscribe depth for {} — depthAdapter not wired", instrument);
            return;
        }

        String key = subscriptionKey(contract);
        synchronized (streamingLock) {
            if (depthSubscriptions.containsKey(key)) return;

            DepthSubscription subscription = new DepthSubscription(instrument);
            controller.reqDeepMktData(contract, numRows, false, subscription);
            depthSubscriptions.put(key, subscription);
            log.info("IB Gateway market depth subscribed for {} ({} rows)", instrument, numRows);

            // Register in subscription registry for reconnection resilience
            SubscriptionRegistry reg = subscriptionRegistry;
            if (reg != null) {
                reg.register(instrument, SubscriptionRegistry.SubscriptionType.DEPTH);
            }
        }
    }

    private final class DepthSubscription implements ApiController.IDeepMktDataHandler {
        private final Instrument instrument;

        DepthSubscription(Instrument instrument) {
            this.instrument = instrument;
        }

        @Override
        public void updateMktDepth(int position, String marketMaker,
                                    com.ib.client.Types.DeepType operation,
                                    com.ib.client.Types.DeepSide side,
                                    double price, com.ib.client.Decimal size) {
            IbkrMarketDepthAdapter adapter = depthAdapter;
            if (adapter != null) {
                adapter.onDepthUpdate(instrument, position,
                    operation.name(), side.name(), price,
                    size != null ? size.longValue() : 0);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Reconnection resilience — UC-OF-015
    // -------------------------------------------------------------------------

    /**
     * Re-subscribes all entries from the {@link SubscriptionRegistry}.
     * Called after a reconnection to restore price, quote, tick-by-tick, and depth streams.
     *
     * <p>Requires a contract resolver to have been set via {@link #setContractResolver}.
     * Entries whose contracts cannot be resolved are skipped with a warning.</p>
     */
    public void resubscribeAll() {
        SubscriptionRegistry reg = subscriptionRegistry;
        IbGatewayContractResolver resolver = contractResolverRef;

        if (reg == null || resolver == null) {
            log.warn("resubscribeAll: registry or contract resolver not wired — skipping");
            return;
        }

        if (!isConnected()) {
            log.warn("resubscribeAll: not connected — skipping");
            return;
        }

        var entries = reg.allEntries();
        log.info("resubscribeAll: restoring {} subscriptions after reconnect", entries.size());

        for (var entry : entries) {
            Instrument instrument = entry.instrument();
            if (!instrument.isExchangeTradedFuture()) continue;

            Optional<IbGatewayResolvedContract> resolved = resolver.resolve(instrument);
            if (resolved.isEmpty()) {
                log.warn("resubscribeAll: cannot resolve contract for {} — skipping", instrument);
                continue;
            }

            Contract contract = resolved.get().contract();
            registerInstrumentMapping(contract, instrument);

            try {
                switch (entry.type()) {
                    case PRICE -> ensureStreamingPriceSubscription(contract);
                    case QUOTE -> ensureStreamingQuoteSubscription(contract);
                    case TICK_BY_TICK -> subscribeTickByTick(contract, instrument);
                    case DEPTH -> subscribeDepth(contract, instrument, depthNumRows);
                }
            } catch (Exception e) {
                log.warn("resubscribeAll: failed to resubscribe {} {} — {}",
                    entry.type(), instrument, e.getMessage());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Historical data
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

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

    /**
     * Nulls out connection state atomically while holding {@code connectionLock}.
     * Returns a snapshot of what was cleared so the caller can clean up OUTSIDE the lock.
     */
    private DisconnectContext clearStateLocked() {
        ApiController prev = controller;
        List<StreamingPriceSubscription> subs = List.copyOf(streamingSubscriptions.values());
        List<StreamingQuoteSubscription> quoteSubs = List.copyOf(streamingQuoteSubscriptions.values());
        controller = null;
        managedAccounts = List.of();
        streamingSubscriptions.clear();
        streamingQuoteSubscriptions.clear();
        return new DisconnectContext(prev, subs, quoteSubs);
    }

    /**
     * Submits cancellation of streaming subscriptions and controller disconnect
     * to the single-threaded cleanup executor, keeping that I/O off the connectionLock.
     *
     * @param ctx              resources to clean up
     * @param cancelSubs       true for a clean shutdown (active session), false when IBKR
     *                         already dropped the connection (callbacks are meaningless)
     */
    private void submitCleanup(DisconnectContext ctx, boolean cancelSubs) {
        CLEANUP_EXECUTOR.submit(() -> {
            if (ctx.controller() == null) return;
            if (cancelSubs) {
                for (StreamingPriceSubscription sub : ctx.subscriptions()) {
                    try { ctx.controller().cancelTopMktData(sub); } catch (Exception ignored) {}
                    try { ctx.controller().cancelRealtimeBars(sub); } catch (Exception ignored) {}
                }
                for (StreamingQuoteSubscription sub : ctx.quoteSubscriptions()) {
                    try { ctx.controller().cancelTopMktData(sub); } catch (Exception ignored) {}
                }
            }
            try { ctx.controller().disconnect(); } catch (Exception ignored) {}
        });
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

    private Optional<NativeOrderSnapshot> findOpenOrderByOrderRef(String accountId, String orderRef) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<NativeOrderSnapshot> found = new AtomicReference<>();

        ApiController.ILiveOrderHandler handler = new ApiController.ILiveOrderHandler() {
            @Override
            public void openOrder(Contract contract, Order order, OrderState orderState) {
                if (order == null || found.get() != null) {
                    return;
                }
                if (!accountId.equals(order.account()) || !orderRef.equals(order.orderRef())) {
                    return;
                }
                String status = orderState != null && orderState.status() != null
                    ? orderState.status().name()
                    : "OPEN";
                found.compareAndSet(null, new NativeOrderSnapshot((long) order.orderId(), order.orderRef(), order.account(), status));
            }

            @Override
            public void openOrderEnd() {
                latch.countDown();
            }

            @Override
            public void orderStatus(int orderId,
                                    OrderStatus status,
                                    Decimal filled,
                                    Decimal remaining,
                                    double avgFillPrice,
                                    long permId,
                                    int parentId,
                                    double lastFillPrice,
                                    int clientId,
                                    String whyHeld,
                                    double mktCapPrice) {
                // no-op
            }

            @Override
            public void handle(int orderId, int errorCode, String errorMsg) {
                log.debug("IB Gateway live order lookup error code={} msg={}", errorCode, errorMsg);
                latch.countDown();
            }
        };

        controller.reqLiveOrders(handler);
        awaitLatch(latch, "live orders", ORDER_TIMEOUT);
        try {
            controller.removeLiveOrderHandler(handler);
        } catch (Exception ignored) {
        }
        return Optional.ofNullable(found.get());
    }

    private Optional<NativeOrderSnapshot> findCompletedOrderByOrderRef(String accountId, String orderRef) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<NativeOrderSnapshot> found = new AtomicReference<>();

        ApiController.ICompletedOrdersHandler handler = new ApiController.ICompletedOrdersHandler() {
            @Override
            public void completedOrder(Contract contract, Order order, OrderState orderState) {
                if (order == null || found.get() != null) {
                    return;
                }
                if (!accountId.equals(order.account()) || !orderRef.equals(order.orderRef())) {
                    return;
                }
                String status = orderState != null && orderState.completedStatus() != null && !orderState.completedStatus().isBlank()
                    ? orderState.completedStatus()
                    : orderState != null && orderState.status() != null
                        ? orderState.status().name()
                        : "COMPLETED";
                found.compareAndSet(null, new NativeOrderSnapshot((long) order.orderId(), order.orderRef(), order.account(), status));
            }

            @Override
            public void completedOrdersEnd() {
                latch.countDown();
            }
        };

        controller.reqCompletedOrders(handler);
        awaitLatch(latch, "completed orders", ORDER_TIMEOUT);
        return Optional.ofNullable(found.get());
    }


    // -------------------------------------------------------------------------
    // Internal record: cleanup context
    // -------------------------------------------------------------------------

    private record DisconnectContext(ApiController controller,
                                     List<StreamingPriceSubscription> subscriptions,
                                     List<StreamingQuoteSubscription> quoteSubscriptions) {}

    public record NativeOrderSnapshot(Long orderId, String orderRef, String accountId, String status) {}

    public record NativeOrderSubmission(Long orderId, String status, String orderRef, Instant submittedAt) {}


    // -------------------------------------------------------------------------
    // IBKR connection callback handler
    // -------------------------------------------------------------------------

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
            DisconnectContext ctx;
            synchronized (connectionLock) {
                managedAccounts = List.of();
                markReconnectCooldownLocked("IB Gateway disconnected");
                if (!connectedFuture.isDone()) {
                    connectedFuture.completeExceptionally(new IllegalStateException("IB Gateway disconnected"));
                }
                if (!accountsFuture.isDone()) {
                    accountsFuture.completeExceptionally(new IllegalStateException("IB Gateway disconnected"));
                }
                ctx = clearStateLocked();
            }
            // IBKR session is gone — no point cancelling subscriptions, just disconnect cleanly.
            submitCleanup(ctx, false);
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
                DisconnectContext ctx;
                synchronized (connectionLock) {
                    if (!connectedFuture.isDone()) {
                        connectedFuture.completeExceptionally(new IllegalStateException(errorMsg));
                    }
                    markReconnectCooldownLocked(errorMsg);
                    ctx = clearStateLocked();
                }
                // Connection never completed — nothing active to cancel.
                submitCleanup(ctx, false);
            }
            log.warn("IB Gateway message id={} code={} msg={}", id, errorCode, errorMsg);
        }

        @Override
        public void show(String string) {
            log.debug("IB Gateway show: {}", string);
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot price collector (one-shot)
    // -------------------------------------------------------------------------

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
                    bestPrice = spreadGuardedMid(bid, ask);
                }
            } else if (tickType == TickType.ASK || tickType == TickType.DELAYED_ASK) {
                ask = price;
                if (bestPrice == null && bid != null && bid > 0) {
                    bestPrice = spreadGuardedMid(bid, ask);
                }
            } else if (bestPrice == null && (tickType == TickType.CLOSE || tickType == TickType.DELAYED_CLOSE)) {
                bestPrice = price;
            }
        }

        private static Double spreadGuardedMid(double bidVal, double askVal) {
            double mid = (bidVal + askVal) / 2.0;
            if (mid <= 0) return null;
            double spread = Math.abs(askVal - bidVal);
            return (spread / mid < 0.005) ? mid : null;
        }

        @Override
        public void tickSnapshotEnd() {
            latch.countDown();
        }

        private Optional<BigDecimal> bestPrice() {
            return bestPrice == null ? Optional.empty() : Optional.of(BigDecimal.valueOf(bestPrice));
        }
    }

    // -------------------------------------------------------------------------
    // Snapshot Open Interest collector (one-shot)
    // -------------------------------------------------------------------------

    private static final class OpenInterestCollector extends ApiController.TopMktDataAdapter {
        private final CountDownLatch latch;
        private volatile Long oi;

        private OpenInterestCollector(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void tickSize(TickType tickType, Decimal size) {
            if ((tickType == TickType.FUTURES_OPEN_INTEREST || tickType == TickType.OPEN_INTEREST)
                    && size != null && size.longValue() >= 0) {
                oi = size.longValue();
                latch.countDown();
            }
        }

        @Override
        public void tickSnapshotEnd() {
            latch.countDown();
        }

        private OptionalLong openInterest() {
            return oi == null ? OptionalLong.empty() : OptionalLong.of(oi);
        }
    }

    private static final class VolumeCollector extends ApiController.TopMktDataAdapter {
        private final CountDownLatch latch;
        private volatile Long vol;

        private VolumeCollector(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void tickSize(TickType tickType, Decimal size) {
            if (tickType == TickType.VOLUME && size != null && size.longValue() >= 0) {
                vol = size.longValue();
                latch.countDown();
            }
        }

        @Override
        public void tickSnapshotEnd() {
            latch.countDown();
        }

        private OptionalLong volume() {
            return vol == null ? OptionalLong.empty() : OptionalLong.of(vol);
        }
    }

    private static final class QuoteCollector extends ApiController.TopMktDataAdapter {
        private final CountDownLatch latch;
        private volatile Double bid;
        private volatile Double ask;
        private volatile Double last;
        private volatile Double close;
        private volatile Instant timestamp;

        private QuoteCollector(CountDownLatch latch) {
            this.latch = latch;
        }

        @Override
        public void tickPrice(TickType tickType, double price, TickAttrib attribs) {
            if (price <= 0) return;
            if (tickType == TickType.LAST || tickType == TickType.DELAYED_LAST) {
                last = price;
                timestamp = Instant.now();
            } else if (tickType == TickType.BID || tickType == TickType.DELAYED_BID) {
                bid = price;
                timestamp = Instant.now();
            } else if (tickType == TickType.ASK || tickType == TickType.DELAYED_ASK) {
                ask = price;
                timestamp = Instant.now();
            } else if (tickType == TickType.CLOSE || tickType == TickType.DELAYED_CLOSE) {
                close = price;
                timestamp = Instant.now();
            }
        }

        @Override
        public void tickSnapshotEnd() {
            latch.countDown();
        }

        private Optional<NativeMarketQuote> quote() {
            if (bid == null && ask == null && last == null && close == null) {
                return Optional.empty();
            }
            return Optional.of(new NativeMarketQuote(
                toBigDecimal(bid),
                toBigDecimal(ask),
                toBigDecimal(last),
                toBigDecimal(close),
                timestamp
            ));
        }
    }

    // -------------------------------------------------------------------------
    // Live streaming subscription handler
    // -------------------------------------------------------------------------

    private final class StreamingPriceSubscription implements ApiController.ITopMktDataHandler, ApiController.IRealTimeBarHandler {
        private final Contract contract;
        private volatile Double bestPrice;
        private volatile Double bid;
        private volatile Double ask;
        private volatile boolean active = true;
        private volatile boolean loggedFirstPrice = false;
        /**
         * Timestamp of the most recent price tick received from IBKR.
         * Null means the subscription is new and has not yet received any data.
         * Used by {@link #isActive()} to detect zombie subscriptions.
         */
        private volatile Instant lastPriceAt = null;

        /** Maximum spread-to-mid ratio for mid-price to be used as bestPrice fallback. */
        private static final double MAX_SPREAD_RATIO = 0.005; // 0.5%
        /** Seconds of silence before stale bid/ask quotes are cleared on the next tick. */
        private static final long SESSION_GAP_SECONDS = 30L;

        private StreamingPriceSubscription(Contract contract) {
            this.contract = contract;
        }

        @Override
        public void tickPrice(TickType tickType, double price, TickAttrib attribs) {
            if (price <= 0) return;

            // After a prolonged gap (maintenance halt, reconnect), clear stale
            // bid/ask so we don't combine yesterday's quotes with today's trades.
            Instant last = lastPriceAt;
            if (last != null && Instant.now().getEpochSecond() - last.getEpochSecond() > SESSION_GAP_SECONDS) {
                bid = null;
                ask = null;
            }

            if (tickType == TickType.LAST || tickType == TickType.DELAYED_LAST) {
                bestPrice = price;
            } else if (tickType == TickType.BID || tickType == TickType.DELAYED_BID) {
                bid = price;
                if (bestPrice == null && ask != null && ask > 0) {
                    bestPrice = spreadGuardedMid(bid, ask);
                }
            } else if (tickType == TickType.ASK || tickType == TickType.DELAYED_ASK) {
                ask = price;
                if (bestPrice == null && bid != null && bid > 0) {
                    bestPrice = spreadGuardedMid(bid, ask);
                }
            } else if (bestPrice == null && (tickType == TickType.CLOSE || tickType == TickType.DELAYED_CLOSE)) {
                bestPrice = price;
            }

            if (bestPrice != null) {
                lastPriceAt = Instant.now();
                logFirstPrice("tickPrice");
                notifyPriceListener(bestPrice, lastPriceAt);
            }
        }

        /**
         * Returns the mid-price only if the bid-ask spread is within {@value MAX_SPREAD_RATIO}
         * of the mid.  At session open the spread can be extremely wide; using a wild mid-price
         * would create spike candles on the chart.
         */
        private static Double spreadGuardedMid(double bidVal, double askVal) {
            double mid = (bidVal + askVal) / 2.0;
            if (mid <= 0) return null;
            double spread = Math.abs(askVal - bidVal);
            return (spread / mid < MAX_SPREAD_RATIO) ? mid : null;
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
            lastPriceAt = Instant.now();
            logFirstPrice("realtimeBar");
            notifyPriceListener(bestPrice, lastPriceAt);
        }

        private Optional<BigDecimal> bestPrice() {
            return bestPrice == null ? Optional.empty() : Optional.of(BigDecimal.valueOf(bestPrice));
        }

        /**
         * Returns true if this subscription is considered alive.
         * A subscription is stale — and should be replaced — if it has previously received
         * at least one price tick but then went silent for more than {@value STALE_PRICE_SECONDS}
         * seconds. New subscriptions (lastPriceAt == null) are given an unlimited grace period
         * until the first tick arrives. When the market is closed (weekends), staleness is
         * not checked to avoid a cancel/resubscribe loop.
         */
        private boolean isActive() {
            if (!active) return false;
            if (!TradingSessionResolver.isMarketOpen(Instant.now())) return true;
            Instant last = lastPriceAt;
            if (last == null) {
                // Not yet received any data; still warming up.
                return true;
            }
            return last.isAfter(Instant.now().minusSeconds(STALE_PRICE_SECONDS));
        }

        private void logFirstPrice(String source) {
            if (!loggedFirstPrice) {
                loggedFirstPrice = true;
                log.info("IB Gateway first live price for {} received via {}", describeContract(contract), source);
            }
        }

        private void notifyPriceListener(double price, Instant timestamp) {
            StreamingPriceListener listener = priceListener;
            if (listener == null) return;
            Instrument instrument = contractKeyToInstrument.get(subscriptionKey(contract));
            if (instrument == null) return;
            try {
                listener.onLivePriceUpdate(instrument, BigDecimal.valueOf(price), timestamp);
            } catch (Exception e) {
                log.debug("Price listener error for {}: {}", instrument, e.getMessage());
            }
        }
    }

    private final class StreamingQuoteSubscription implements ApiController.ITopMktDataHandler {
        private final Contract contract;
        private volatile Double bid;
        private volatile Double ask;
        private volatile Double last;
        private volatile Double close;
        private volatile boolean active = true;
        private volatile boolean loggedFirstQuote = false;
        private volatile Instant lastQuoteAt = null;

        private StreamingQuoteSubscription(Contract contract) {
            this.contract = contract;
        }

        @Override
        public void tickPrice(TickType tickType, double price, TickAttrib attribs) {
            if (price <= 0) {
                return;
            }

            if (tickType == TickType.LAST || tickType == TickType.DELAYED_LAST) {
                last = price;
            } else if (tickType == TickType.BID || tickType == TickType.DELAYED_BID) {
                bid = price;
            } else if (tickType == TickType.ASK || tickType == TickType.DELAYED_ASK) {
                ask = price;
            } else if (tickType == TickType.CLOSE || tickType == TickType.DELAYED_CLOSE) {
                close = price;
            }

            lastQuoteAt = Instant.now();
            logFirstQuote();
        }

        @Override
        public void marketDataType(int marketDataType) {
            active = true;
            log.info("IB Gateway quote market data type {} for {}", marketDataType, describeContract(contract));
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
            // no-op
        }

        private Optional<NativeMarketQuote> quote() {
            if (bid == null && ask == null && last == null && close == null) {
                return Optional.empty();
            }
            return Optional.of(new NativeMarketQuote(
                toBigDecimal(bid),
                toBigDecimal(ask),
                toBigDecimal(last),
                toBigDecimal(close),
                lastQuoteAt
            ));
        }

        private boolean isActive() {
            if (!active) return false;
            if (!TradingSessionResolver.isMarketOpen(Instant.now())) return true;
            Instant last = lastQuoteAt;
            if (last == null) {
                return true;
            }
            return last.isAfter(Instant.now().minusSeconds(STALE_PRICE_SECONDS));
        }

        private void logFirstQuote() {
            if (!loggedFirstQuote) {
                loggedFirstQuote = true;
                log.info("IB Gateway first live quote for {} received", describeContract(contract));
            }
        }
    }

    private static BigDecimal toBigDecimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    public record NativeMarketQuote(BigDecimal bid,
                                    BigDecimal ask,
                                    BigDecimal last,
                                    BigDecimal close,
                                    Instant timestamp) {
    }

    // -------------------------------------------------------------------------
    // Tick-by-tick AllLast subscription handler — UC-OF-001
    // -------------------------------------------------------------------------

    /**
     * Handles tick-by-tick AllLast callbacks from IBKR. Each trade tick is
     * classified via Lee-Ready and routed to the IbkrTickDataAdapter.
     *
     * <p>Called on the IBKR EReader thread. Must be non-blocking.</p>
     */
    private final class TickByTickSubscription implements ApiController.ITickByTickDataHandler {

        private final Contract contract;
        private final Instrument instrument;
        private volatile boolean active = true;
        private volatile Instant lastTickAt = null;
        private volatile boolean loggedFirstTick = false;

        TickByTickSubscription(Contract contract, Instrument instrument) {
            this.contract = contract;
            this.instrument = instrument;
        }

        @Override
        public void tickByTickAllLast(int reqId, int tickType, long time, double price,
                                       Decimal size, TickAttribLast attribs,
                                       String exchange, String specialConditions) {
            lastTickAt = Instant.now();

            if (!loggedFirstTick) {
                loggedFirstTick = true;
                log.info("IB Gateway first tick-by-tick trade for {} — price={}, size={}, exchange={}",
                         instrument, price, size, exchange);
            }

            long sizeVal = size != null ? size.longValue() : 0;
            if (sizeVal <= 0 || price <= 0) return;

            // Retrieve current bid/ask from the quote subscription cache for Lee-Ready
            double bid = 0;
            double ask = 0;
            String key = subscriptionKey(contract);
            StreamingQuoteSubscription quotesSub = streamingQuoteSubscriptions.get(key);
            if (quotesSub != null) {
                var quote = quotesSub.quote();
                if (quote.isPresent()) {
                    NativeMarketQuote q = quote.get();
                    bid = q.bid() != null ? q.bid().doubleValue() : 0;
                    ask = q.ask() != null ? q.ask().doubleValue() : 0;
                }
            }

            // Classify the trade via Lee-Ready
            TickByTickAggregator.TickClassification classification =
                IbkrTickDataAdapter.classifyTrade(price, bid, ask);

            // Route to the adapter (which manages per-instrument aggregators)
            IbkrTickDataAdapter adapter = tickDataAdapter;
            if (adapter != null) {
                adapter.onTickByTickTrade(instrument, price, sizeVal, classification,
                                          Instant.ofEpochSecond(time));
            }
        }

        @Override
        public void tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice,
                                      Decimal bidSize, Decimal askSize,
                                      com.ib.client.TickAttribBidAsk attribs) {
            // Not used for AllLast subscription
        }

        @Override
        public void tickByTickMidPoint(int reqId, long time, double midPoint) {
            // Not used for AllLast subscription
        }

        @Override
        public void tickByTickHistoricalTickAllLast(int reqId, java.util.List<HistoricalTickLast> ticks) {
            // Historical ticks sent once at subscription start — ignored
        }

        @Override
        public void tickByTickHistoricalTickBidAsk(int reqId, java.util.List<HistoricalTickBidAsk> ticks) {
            // Not applicable
        }

        @Override
        public void tickByTickHistoricalTick(int reqId, java.util.List<HistoricalTick> ticks) {
            // Not applicable
        }

        boolean isActive() {
            if (!active) return false;
            if (!TradingSessionResolver.isMarketOpen(Instant.now())) return true;
            if (lastTickAt == null) return true; // new subscription, grace period
            return lastTickAt.isAfter(Instant.now().minusSeconds(STALE_PRICE_SECONDS));
        }
    }
}
