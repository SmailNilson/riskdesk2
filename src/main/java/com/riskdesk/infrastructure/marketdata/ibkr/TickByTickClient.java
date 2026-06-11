package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.DefaultEWrapper;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import com.ib.client.TickAttribLast;
import com.ib.client.TickType;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dedicated EClientSocket connection for tick-by-tick AllLast data.
 *
 * <p>Uses direct EClientSocket (bypasses ApiController) with 5 critical fixes:
 * <ol>
 *   <li>EReader loop catches ALL exceptions (not just IOException)</li>
 *   <li>reqId management — AtomicInteger, never reuse</li>
 *   <li>tickByTickAllLast is exception-safe (swallows, never propagates to EReader)</li>
 *   <li>Contract fully specified (symbol, secType, exchange, currency, lastTradeDate)</li>
 *   <li>Watchdog detects silent stream death and resubscribes with NEW reqId</li>
 * </ol>
 */
@Component
public class TickByTickClient {

    private static final Logger log = LoggerFactory.getLogger(TickByTickClient.class);
    private static final long STREAM_DEAD_THRESHOLD_MS = 60_000; // 60s no tick = dead
    private static final long WATCHDOG_INTERVAL_MS = 30_000; // check every 30s

    private final IbkrProperties properties;
    private final OrderFlowProperties orderFlowProperties;
    private volatile IbkrTickDataAdapter tickDataAdapter;
    private volatile IbGatewayNativeClient nativeClient;

    // Connection state
    private volatile EClientSocket client;
    private volatile EReader reader;
    private volatile EReaderSignal signal;
    private volatile boolean connected = false;

    /** Called when IBKR closes the connection — lets the orchestrator reset its subscription flags. */
    private volatile Runnable disconnectionCallback;

    // Fix 2: reqId management — NEVER reuse, always increment
    private final AtomicInteger nextReqId = new AtomicInteger(1000);

    // Subscription tracking
    private final ConcurrentHashMap<Integer, SubscriptionInfo> activeSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> lastTickTime = new ConcurrentHashMap<>();
    private final AtomicLong totalTicksReceived = new AtomicLong(0);

    /**
     * Last IBKR error surfaced per instrument (entitlement / competing-session / connectivity).
     * Populated in {@link TickEWrapper#error(int, long, int, String, String)} and cleared the
     * moment a real tick arrives, so {@code /api/order-flow/status} explains WHY a subscribed
     * instrument is delivering zero ticks instead of failing silently.
     */
    private final ConcurrentHashMap<Instrument, String> lastErrorByInstrument = new ConcurrentHashMap<>();
    /** Last connection-level error (id == -1), e.g. data-farm or auth failures. */
    private volatile String lastSystemError;

    /**
     * Per-instrument resubscribe timestamps for the shared rate limiter — bounds cancel/
     * reqTickByTickData churn across ALL watchdog loops (IBKR throttles rapid reqId churn).
     */
    private final ConcurrentHashMap<Instrument, Deque<Long>> resubTimes = new ConcurrentHashMap<>();

    // Fix 5: Watchdog scheduler
    private ScheduledExecutorService watchdog;

    private record SubscriptionInfo(Instrument instrument, Contract contract) {}

    public TickByTickClient(IbkrProperties properties, OrderFlowProperties orderFlowProperties) {
        this.properties = properties;
        this.orderFlowProperties = orderFlowProperties;
    }

    public void setTickDataAdapter(IbkrTickDataAdapter adapter) {
        this.tickDataAdapter = adapter;
    }

    public void setNativeClient(IbGatewayNativeClient nativeClient) {
        this.nativeClient = nativeClient;
    }

    public void setDisconnectionCallback(Runnable callback) {
        this.disconnectionCallback = callback;
    }

    /**
     * Connects to IBKR Gateway using a dedicated clientId.
     */
    public synchronized void connect() {
        if (connected && client != null && client.isConnected()) {
            return;
        }

        String host = properties.getNativeHost();
        int port = properties.getNativePort();
        int clientId = properties.getNativeTickClientId();

        try {
            signal = new EJavaSignal();
            TickEWrapper wrapper = new TickEWrapper();
            client = new EClientSocket(wrapper, signal);
            client.eConnect(host, port, clientId);

            if (!client.isConnected()) {
                log.warn("TickByTickClient: failed to connect to {}:{} clientId={}", host, port, clientId);
                return;
            }

            // Start EReader
            reader = new EReader(client, signal);
            reader.start();
            log.info("TickByTickClient: EReader thread started");

            // Fix 1: EReader processing loop catches ALL exceptions
            Thread processingThread = new Thread(() -> {
                log.info("TickByTickClient: processing thread started");
                while (client != null && client.isConnected()) {
                    signal.waitForSignal();
                    try {
                        reader.processMsgs();
                    } catch (Exception e) {
                        // CRITICAL: catch ALL exceptions, not just IOException
                        // Prevents silent thread death
                        log.error("TickByTickClient: EReader processing error (thread alive)", e);
                        // do NOT break — keep the loop alive
                    }
                }
                log.warn("TickByTickClient: processing thread exited (client disconnected)");
            }, "tick-by-tick-processor");
            processingThread.setDaemon(true);
            processingThread.start();

            connected = true;
            log.info("TickByTickClient: connected to {}:{} clientId={}", host, port, clientId);

            // Fix 5: Start watchdog to detect silent stream death
            startWatchdog();

        } catch (Exception e) {
            log.error("TickByTickClient: connection failed", e);
            connected = false;
        }
    }

    public synchronized void disconnect() {
        connected = false;
        stopWatchdog();
        activeSubscriptions.clear();
        lastTickTime.clear();
        if (client != null && client.isConnected()) {
            try { client.eDisconnect(); } catch (Exception e) { /* ignore */ }
        }
        client = null;
        reader = null;
        signal = null;
        log.info("TickByTickClient: disconnected");
    }

    public boolean isConnected() {
        return connected && client != null && client.isConnected();
    }

    // -------------------------------------------------------------------------
    // Fix 2: Subscription with unique reqId (never reuse)
    // -------------------------------------------------------------------------

    /**
     * Subscribe to tick-by-tick AllLast data. Uses a unique reqId every time.
     */
    public void subscribeTickByTick(Contract contract, Instrument instrument) {
        if (!isConnected()) {
            log.warn("TickByTickClient: cannot subscribe {} — not connected", instrument);
            return;
        }
        if (tickDataAdapter == null) {
            log.warn("TickByTickClient: cannot subscribe {} — tickDataAdapter not wired", instrument);
            return;
        }

        // Check if already subscribed for this instrument
        for (var entry : activeSubscriptions.entrySet()) {
            if (entry.getValue().instrument() == instrument) {
                return; // already subscribed
            }
        }

        int reqId = nextReqId.getAndIncrement(); // NEVER reuse
        activeSubscriptions.put(reqId, new SubscriptionInfo(instrument, contract));
        lastTickTime.put(reqId, System.currentTimeMillis()); // initial timestamp

        // Fresh subscription (resubscribe / contract rollover): clear the tick-rule reference price
        // so the first trade on this (possibly new) contract isn't classified against a stale price.
        tickDataAdapter.resetTickRuleState(instrument);

        client.reqTickByTickData(reqId, contract, "AllLast", 0, false);
        log.info("TickByTickClient: SUBSCRIBE reqId={} {} conId={} lastTradeDate={}",
                 reqId, instrument, contract.conid(), contract.lastTradeDateOrContractMonth());
    }

    public void cancelTickByTick(Instrument instrument) {
        activeSubscriptions.forEach((reqId, info) -> {
            if (info.instrument() == instrument) {
                client.cancelTickByTickData(reqId);
                activeSubscriptions.remove(reqId);
                lastTickTime.remove(reqId);
                log.info("TickByTickClient: CANCEL reqId={} {}", reqId, instrument);
            }
        });
    }

    public long getTotalTicksReceived() {
        return totalTicksReceived.get();
    }

    /** Last actionable IBKR error for an instrument's tick stream, or null when healthy. */
    public String lastError(Instrument instrument) {
        return lastErrorByInstrument.get(instrument);
    }

    /** Last connection-level error (id == -1), or null. */
    public String lastSystemError() {
        return lastSystemError;
    }

    /** Codes IBKR sends as connection/status notices — not real subscription failures. */
    private static boolean isInformationalCode(int code) {
        return switch (code) {
            case 2104, 2106, 2108, 2107, 2158, 1101, 1102 -> true;
            default -> false;
        };
    }

    /** Human-readable hint for the tick-by-tick failure codes that explain a 0-tick stream. */
    private static String formatTickError(int code, String msg) {
        String hint = switch (code) {
            case 354   -> "market data not subscribed — IBKR entitlement missing";
            case 10089 -> "tick-by-tick requires additional market-data subscription";
            case 10197 -> "no data during a competing live session (TWS/another app holds the line)";
            case 10167 -> "delayed data substituted — real-time tick-by-tick not entitled";
            case 1100  -> "connectivity to IBKR lost";
            default    -> null;
        };
        return "code " + code + (hint != null ? " (" + hint + ")" : "") + ": " + msg;
    }

    // -------------------------------------------------------------------------
    // Fix 5: Watchdog — detect silent stream death and resubscribe
    // -------------------------------------------------------------------------

    private void startWatchdog() {
        stopWatchdog();
        watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "tick-watchdog");
            t.setDaemon(true);
            return t;
        });
        watchdog.scheduleAtFixedRate(this::checkStreams, WATCHDOG_INTERVAL_MS, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("TickByTickClient: watchdog started (interval={}s, threshold={}s)",
                 WATCHDOG_INTERVAL_MS / 1000, STREAM_DEAD_THRESHOLD_MS / 1000);
    }

    private void stopWatchdog() {
        if (watchdog != null) {
            watchdog.shutdownNow();
            watchdog = null;
        }
    }

    private void checkStreams() {
        if (!isConnected()) return;

        long now = System.currentTimeMillis();
        boolean ownResub = orderFlowProperties.getTickByTick().isInternalWatchdogResubscribes();
        activeSubscriptions.forEach((reqId, info) -> {
            Long last = lastTickTime.get(reqId);
            if (last != null && (now - last) > STREAM_DEAD_THRESHOLD_MS) {
                long silentSec = (now - last) / 1000;
                // Single-owner resubscription: by default the orchestrator delta-freshness watchdog
                // (keyed on CLASSIFIED-tick yield, not raw arrival) owns recovery, so this internal
                // watchdog only raises an alarm and does NOT churn reqIds. Flip
                // internal-watchdog-resubscribes=true to restore the old self-healer.
                if (ownResub && allowResubscribe(info.instrument())) {
                    log.warn("TickByTickClient: STREAM DEAD reqId={} {} — no raw tick for {}s. Resubscribing with new reqId.",
                             reqId, info.instrument(), silentSec);
                    resubscribe(reqId, info);
                } else {
                    log.warn("TickByTickClient: STREAM SILENT reqId={} {} — no raw tick for {}s (alarm-only; orchestrator owns resubscribe)",
                             reqId, info.instrument(), silentSec);
                }
            }
        });
    }

    /**
     * Shared per-instrument resubscribe rate limiter. Bounds cancel/reqTickByTickData churn across
     * the internal stream watchdog, the orchestrator delta-freshness watchdog and connection-health
     * eviction — IBKR throttles rapid reqId churn. Records the attempt when it returns {@code true}.
     */
    public boolean allowResubscribe(Instrument instrument) {
        long now = System.currentTimeMillis();
        int max = Math.max(1, orderFlowProperties.getTickByTick().getMaxResubscribesPerMinute());
        Deque<Long> times = resubTimes.computeIfAbsent(instrument, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && now - times.peekFirst() > 60_000L) times.pollFirst();
            if (times.size() >= max) return false;
            times.addLast(now);
            return true;
        }
    }

    private void resubscribe(int oldReqId, SubscriptionInfo info) {
        try {
            // Cancel old subscription
            client.cancelTickByTickData(oldReqId);
            activeSubscriptions.remove(oldReqId);
            lastTickTime.remove(oldReqId);

            // Subscribe with a BRAND NEW reqId (never reuse)
            int newReqId = nextReqId.getAndIncrement();
            activeSubscriptions.put(newReqId, info);
            lastTickTime.put(newReqId, System.currentTimeMillis());

            client.reqTickByTickData(newReqId, info.contract(), "AllLast", 0, false);
            log.info("TickByTickClient: RESUBSCRIBE {} old reqId={} → new reqId={}",
                     info.instrument(), oldReqId, newReqId);
        } catch (Exception e) {
            log.error("TickByTickClient: resubscribe failed for {}", info.instrument(), e);
        }
    }

    /** How the tick ended up classified, for the sampled diagnostic log. */
    private static String classificationVia(IbkrTickDataAdapter.TickResolution resolution,
                                            IbkrTickDataAdapter adapter) {
        if (adapter == null) return "ADAPTER_UNWIRED";
        if (resolution.tickRule()) return "TICK_RULE";
        return resolution.classification() == TickByTickAggregator.TickClassification.UNCLASSIFIED
            ? "DROPPED" : "LEE_READY";
    }

    /** BBO provenance label, e.g. {@code BBO@120ms} / {@code QUOTE@45ms} / {@code NONE}. */
    private static String bboLabel(String source, Instant bboAt) {
        if (bboAt == null) return source;
        return source + "@" + (System.currentTimeMillis() - bboAt.toEpochMilli()) + "ms";
    }

    // -------------------------------------------------------------------------
    // Fix 3: Exception-safe EWrapper
    // -------------------------------------------------------------------------

    private class TickEWrapper extends DefaultEWrapper {

        @Override
        public void tickByTickAllLast(int reqId, int tickType, long time, double price,
                                       Decimal size, TickAttribLast attribs,
                                       String exchange, String specialConditions) {
            try {
                // Fix 3: entire handler wrapped in try/catch — NEVER propagate to EReader
                if (price <= 0 || size == null) return;

                long sizeVal = size.longValue();
                if (sizeVal <= 0) return;

                Instant timestamp = Instant.ofEpochSecond(time);
                long count = totalTicksReceived.incrementAndGet();

                // Update watchdog timestamp
                lastTickTime.put(reqId, System.currentTimeMillis());

                SubscriptionInfo info = activeSubscriptions.get(reqId);
                String instrument = info != null ? info.instrument().name() : "UNKNOWN";

                // A tick proves data is flowing — clear any stale error for this instrument.
                if (info != null) {
                    lastErrorByInstrument.remove(info.instrument());
                    lastSystemError = null;
                }

                // Bid/ask for Lee-Ready classification — prefer the real BBO (live or last-known-good
                // within the configured staleness), then the dedicated quote subscription. We no longer
                // synthesize a mid from the last trade (it mis-anchors classification and is freshness-
                // unguarded); when no BBO is available bid=ask=0 and the adapter falls back to the
                // trade-to-trade tick rule (L2) instead of dropping the tick as UNCLASSIFIED.
                double bid = 0;
                double ask = 0;
                String bboSource = "NONE";
                Instant bboAt = null;
                if (info != null) {
                    IbGatewayNativeClient nc = nativeClient;
                    if (nc != null) {
                        // 1) Real BBO from the price subscription (futures have no quote subscription).
                        var bbo = nc.latestStreamingBbo(info.contract(),
                                orderFlowProperties.getTickByTick().getBboMaxStalenessSeconds());
                        if (bbo.isPresent()) {
                            IbGatewayNativeClient.NativeMarketQuote q = bbo.get();
                            bid = q.bid() != null ? q.bid().doubleValue() : 0;
                            ask = q.ask() != null ? q.ask().doubleValue() : 0;
                            bboSource = "BBO";
                            bboAt = q.timestamp();
                        }
                        // 2) Dedicated quote subscription, if one exists (e.g. FX).
                        if (bid <= 0 || ask <= 0) {
                            var quote = nc.latestStreamingQuote(info.contract());
                            if (quote.isPresent()) {
                                IbGatewayNativeClient.NativeMarketQuote q = quote.get();
                                if (bid <= 0) bid = q.bid() != null ? q.bid().doubleValue() : 0;
                                if (ask <= 0) ask = q.ask() != null ? q.ask().doubleValue() : 0;
                                bboSource = "QUOTE";
                                bboAt = q.timestamp();
                            }
                        }
                        // 3) else bid=ask=0 → classifyTrade returns UNCLASSIFIED → tick-rule fallback.
                    }

                    TickByTickAggregator.TickClassification quoteRuleClass =
                        IbkrTickDataAdapter.classifyTrade(price, bid, ask);

                    // Resolve BEFORE logging: the adapter applies the tick-rule fallback when the
                    // quote rule can't classify (no BBO, or trade exactly at the BBO midpoint).
                    IbkrTickDataAdapter adapter = tickDataAdapter;
                    IbkrTickDataAdapter.TickResolution resolution = adapter != null
                        ? adapter.onTickByTickTrade(info.instrument(), price, sizeVal, quoteRuleClass, timestamp)
                        : new IbkrTickDataAdapter.TickResolution(quoteRuleClass, false);

                    // Sampled diagnostic log — class= is the classification the aggregator actually
                    // consumed. Logging the raw quote-rule result here used to print
                    // class=UNCLASSIFIED on every midpoint trade even though the tick was classified
                    // downstream by the tick rule, which read as total classification failure in
                    // production audits. via= says how it was classified; bbo= says which quote
                    // cache supplied bid/ask and how old it was at classification time.
                    if (count <= 20 || count % 100 == 0) {
                        log.info("TICK #{} reqId={} {} price={} size={} bid={} ask={} class={} via={} bbo={} time={}",
                                 count, reqId, instrument, price, sizeVal, bid, ask,
                                 resolution.classification(),
                                 classificationVia(resolution, adapter),
                                 bboLabel(bboSource, bboAt), timestamp);
                    }
                }
            } catch (Exception e) {
                // Fix 3: swallow ALL exceptions — NEVER propagate to EReader thread
                log.error("TickByTickClient: exception in tickByTickAllLast reqId={}", reqId, e);
            }
        }

        @Override
        public void connectAck() {
            if (client != null && client.isAsyncEConnect()) {
                client.startAPI();
            }
        }

        @Override
        public void connectionClosed() {
            log.warn("TickByTickClient: CONNECTION CLOSED by IBKR");
            connected = false;
            stopWatchdog();
            Runnable cb = disconnectionCallback;
            if (cb != null) {
                try { cb.run(); } catch (Exception e) { log.error("TickByTickClient: disconnection callback failed", e); }
            }
        }

        @Override
        public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
            // Capture actionable failures so /api/order-flow/status can explain a 0-tick stream
            // (entitlement, competing session, connectivity) instead of failing silently.
            if (!isInformationalCode(errorCode)) {
                String detail = formatTickError(errorCode, errorMsg);
                SubscriptionInfo info = activeSubscriptions.get(id);
                if (info != null) {
                    lastErrorByInstrument.put(info.instrument(), detail);
                } else if (id == -1) {
                    lastSystemError = detail;
                }
            }
            // Log ALL errors — especially the critical ones
            switch (errorCode) {
                case 2104, 2106, 2158, 2108, 2107 -> { /* noisy info */ }
                case 10197 -> log.warn("TickByTickClient: COMPETING SESSION stealing data (code 10197) reqId={}", id);
                case 10167 -> log.warn("TickByTickClient: DELAYED DATA substituted (code 10167) reqId={}", id);
                case 354   -> log.warn("TickByTickClient: NOT SUBSCRIBED to data (code 354) reqId={}", id);
                case 10089 -> log.warn("TickByTickClient: MISSING tick-by-tick subscription (code 10089) reqId={}", id);
                case 1100  -> log.warn("TickByTickClient: CONNECTIVITY LOST (code 1100)");
                case 1101  -> log.info("TickByTickClient: CONNECTIVITY RESTORED (code 1101)");
                case 1102  -> log.info("TickByTickClient: CONNECTIVITY RESTORED with data loss (code 1102)");
                default -> {
                    if (id == -1) {
                        log.debug("TickByTickClient: system code={} msg={}", errorCode, errorMsg);
                    } else {
                        log.warn("TickByTickClient: ERROR id={} code={} msg={}", id, errorCode, errorMsg);
                    }
                }
            }
        }

        @Override
        public void error(Exception e) {
            log.error("TickByTickClient: EXCEPTION", e);
        }

        @Override
        public void error(String str) {
            log.warn("TickByTickClient: ERROR — {}", str);
        }

        @Override
        public void nextValidId(int orderId) {
            log.info("TickByTickClient: nextValidId={} — connection ready", orderId);
        }
    }
}
