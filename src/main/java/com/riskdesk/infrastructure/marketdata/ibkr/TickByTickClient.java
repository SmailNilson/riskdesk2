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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
    private volatile IbkrTickDataAdapter tickDataAdapter;
    private volatile IbGatewayNativeClient nativeClient;

    // Connection state
    private volatile EClientSocket client;
    private volatile EReader reader;
    private volatile EReaderSignal signal;
    private volatile boolean connected = false;

    // Fix 2: reqId management — NEVER reuse, always increment
    private final AtomicInteger nextReqId = new AtomicInteger(1000);

    // Subscription tracking
    private final ConcurrentHashMap<Integer, SubscriptionInfo> activeSubscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Long> lastTickTime = new ConcurrentHashMap<>();
    private final AtomicLong totalTicksReceived = new AtomicLong(0);

    // Fix 5: Watchdog scheduler
    private ScheduledExecutorService watchdog;

    private record SubscriptionInfo(Instrument instrument, Contract contract) {}

    public TickByTickClient(IbkrProperties properties) {
        this.properties = properties;
    }

    public void setTickDataAdapter(IbkrTickDataAdapter adapter) {
        this.tickDataAdapter = adapter;
    }

    public void setNativeClient(IbGatewayNativeClient nativeClient) {
        this.nativeClient = nativeClient;
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
        activeSubscriptions.forEach((reqId, info) -> {
            Long last = lastTickTime.get(reqId);
            if (last != null && (now - last) > STREAM_DEAD_THRESHOLD_MS) {
                log.warn("TickByTickClient: STREAM DEAD reqId={} {} — no tick for {}s. Resubscribing with new reqId.",
                         reqId, info.instrument(), (now - last) / 1000);
                resubscribe(reqId, info);
            }
        });
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

                // Log EVERY tick (for diagnosis — can reduce to DEBUG later)
                SubscriptionInfo info = activeSubscriptions.get(reqId);
                String instrument = info != null ? info.instrument().name() : "UNKNOWN";

                if (count <= 20 || count % 100 == 0) {
                    log.info("TICK #{} reqId={} {} price={} size={} time={} exchange={}",
                             count, reqId, instrument, price, sizeVal, timestamp, exchange);
                }

                // Get bid/ask from main connection for Lee-Ready classification
                double bid = 0;
                double ask = 0;
                if (info != null) {
                    IbGatewayNativeClient nc = nativeClient;
                    if (nc != null) {
                        var quote = nc.latestStreamingQuote(info.contract());
                        if (quote.isPresent()) {
                            IbGatewayNativeClient.NativeMarketQuote q = quote.get();
                            bid = q.bid() != null ? q.bid().doubleValue() : 0;
                            ask = q.ask() != null ? q.ask().doubleValue() : 0;
                        }
                    }

                    TickByTickAggregator.TickClassification classification =
                        IbkrTickDataAdapter.classifyTrade(price, bid, ask);

                    IbkrTickDataAdapter adapter = tickDataAdapter;
                    if (adapter != null) {
                        adapter.onTickByTickTrade(info.instrument(), price, sizeVal, classification, timestamp);
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
        }

        @Override
        public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
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
