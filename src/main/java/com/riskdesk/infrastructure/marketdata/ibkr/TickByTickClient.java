package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.DefaultEWrapper;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dedicated EClientSocket connection for real-time trade data via RTVolume (tick type 233).
 *
 * <p>Uses {@code reqMktData} with generic tick "233" (RTVolume) instead of
 * {@code reqTickByTickData} because the latter requires an API-eligible market data
 * subscription that is not available on "Trader Workstation" channel subscriptions.
 * RTVolume delivers trade data at ~250ms aggregation via the {@code tickString()} callback,
 * which is sufficient for delta calculation, absorption detection, and order flow scoring.</p>
 *
 * <p>Runs on a separate IBKR clientId to avoid interfering with the main
 * ApiController connection (prices, quotes, depth, orders).</p>
 *
 * <p>RTVolume format: {@code price;size;timeMs;totalVolume;vwap;singleTradeFlag}</p>
 */
@Component
public class TickByTickClient {

    private static final Logger log = LoggerFactory.getLogger(TickByTickClient.class);
    private static final int TICK_TYPE_RT_VOLUME = 48; // TickType.RT_VOLUME

    private final IbkrProperties properties;
    private volatile IbkrTickDataAdapter tickDataAdapter;
    private volatile IbGatewayNativeClient nativeClient;

    // Connection state
    private volatile EClientSocket client;
    private volatile EReader reader;
    private volatile EReaderSignal signal;
    private volatile boolean connected = false;

    // Subscription tracking: reqId → instrument
    private final ConcurrentHashMap<Integer, SubscriptionInfo> subscriptions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> instrumentReqIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextReqId = new AtomicInteger(1);
    private final AtomicLong totalTicksReceived = new AtomicLong(0);

    private record SubscriptionInfo(Instrument instrument, Contract contract, boolean loggedFirstTick) {}

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
            RTVolumeEWrapper wrapper = new RTVolumeEWrapper();
            client = new EClientSocket(wrapper, signal);
            client.eConnect(host, port, clientId);

            if (!client.isConnected()) {
                log.warn("TickByTickClient: failed to connect to {}:{} clientId={}", host, port, clientId);
                return;
            }

            reader = new EReader(client, signal);
            reader.start();

            Thread processingThread = new Thread(() -> {
                while (client != null && client.isConnected()) {
                    signal.waitForSignal();
                    try {
                        reader.processMsgs();
                    } catch (Exception e) {
                        log.warn("TickByTickClient processing error: {}", e.getMessage());
                    }
                }
                log.info("TickByTickClient processing thread exited");
            }, "tick-by-tick-processor");
            processingThread.setDaemon(true);
            processingThread.start();

            connected = true;
            log.info("TickByTickClient connected to {}:{} with clientId={} (RTVolume mode)", host, port, clientId);

        } catch (Exception e) {
            log.error("TickByTickClient connection failed: {}", e.getMessage(), e);
            connected = false;
        }
    }

    public synchronized void disconnect() {
        connected = false;
        subscriptions.clear();
        instrumentReqIds.clear();
        if (client != null && client.isConnected()) {
            try { client.eDisconnect(); } catch (Exception e) { /* ignore */ }
        }
        client = null;
        reader = null;
        signal = null;
        log.info("TickByTickClient disconnected");
    }

    public boolean isConnected() {
        return connected && client != null && client.isConnected();
    }

    /**
     * Subscribe to RTVolume (tick type 233) for real-time trade data.
     * Uses reqMktData instead of reqTickByTickData — works with all subscription types.
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

        String key = instrumentKey(instrument);
        if (instrumentReqIds.containsKey(key)) {
            return;
        }

        int reqId = nextReqId.getAndIncrement();
        subscriptions.put(reqId, new SubscriptionInfo(instrument, contract, false));
        instrumentReqIds.put(key, reqId);

        // reqMktData with generic tick "233" (RTVolume) — continuous trade stream
        client.reqMktData(reqId, contract, "233", false, false, null);
        log.info("TickByTickClient: subscribed RTVolume for {} (reqId={}, conId={})",
                 instrument, reqId, contract.conid());
    }

    public void cancelTickByTick(Instrument instrument) {
        String key = instrumentKey(instrument);
        Integer reqId = instrumentReqIds.remove(key);
        if (reqId != null) {
            subscriptions.remove(reqId);
            if (client != null && client.isConnected()) {
                try { client.cancelMktData(reqId); } catch (Exception e) { /* ignore */ }
            }
        }
    }

    public long getTotalTicksReceived() {
        return totalTicksReceived.get();
    }

    private String instrumentKey(Instrument instrument) {
        return instrument.name();
    }

    // -------------------------------------------------------------------------
    // RTVolume parsing
    // -------------------------------------------------------------------------

    /**
     * Parse RTVolume string: "price;size;timeMs;totalVolume;vwap;singleTradeFlag"
     */
    private void processRTVolume(int reqId, String rtVolumeStr) {
        SubscriptionInfo info = subscriptions.get(reqId);
        if (info == null) return;

        try {
            String[] parts = rtVolumeStr.split(";");
            if (parts.length < 6) return;

            String priceStr = parts[0].trim();
            String sizeStr = parts[1].trim();
            String timeMsStr = parts[2].trim();

            if (priceStr.isEmpty() || sizeStr.isEmpty() || timeMsStr.isEmpty()) return;

            double price = Double.parseDouble(priceStr);
            long size = (long) Double.parseDouble(sizeStr);
            long timeMs = Long.parseLong(timeMsStr);

            if (price <= 0 || size <= 0) return;

            long count = totalTicksReceived.incrementAndGet();

            if (!info.loggedFirstTick()) {
                subscriptions.put(reqId, new SubscriptionInfo(info.instrument(), info.contract(), true));
                log.info("TickByTickClient: first RTVolume tick for {} — price={}, size={}, time={}",
                         info.instrument(), price, size, Instant.ofEpochMilli(timeMs));
            }

            if (count % 1000 == 0) {
                log.info("TickByTickClient: {} total ticks received across all instruments", count);
            }

            // Get bid/ask from main connection for Lee-Ready classification
            double bid = 0;
            double ask = 0;
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
                adapter.onTickByTickTrade(info.instrument(), price, size, classification,
                                          Instant.ofEpochMilli(timeMs));
            }

        } catch (NumberFormatException e) {
            log.debug("TickByTickClient: malformed RTVolume: {}", rtVolumeStr);
        } catch (Exception e) {
            log.warn("TickByTickClient: error processing RTVolume: {}", e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // EWrapper — RTVolume via tickString()
    // -------------------------------------------------------------------------

    private class RTVolumeEWrapper extends DefaultEWrapper {

        @Override
        public void tickString(int tickerId, int tickType, String value) {
            if (tickType == TICK_TYPE_RT_VOLUME && value != null && !value.isEmpty()) {
                processRTVolume(tickerId, value);
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
            log.warn("TickByTickClient: connection closed by IBKR");
            connected = false;
        }

        @Override
        public void error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson) {
            if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158 || errorCode == 2108 || errorCode == 2107) {
                return;
            }
            if (errorCode == 10197) {
                log.warn("TickByTickClient: no market data during competing session (code 10197) reqId={}", id);
            } else if (errorCode == 10167) {
                log.warn("TickByTickClient: delayed market data (code 10167) reqId={}", id);
            } else if (errorCode == 354) {
                log.warn("TickByTickClient: not subscribed to market data (code 354) reqId={}", id);
            } else if (id == -1) {
                log.debug("TickByTickClient: system message code={} msg={}", errorCode, errorMsg);
            } else {
                log.warn("TickByTickClient: error id={} code={} msg={}", id, errorCode, errorMsg);
            }
        }

        @Override
        public void error(Exception e) {
            log.warn("TickByTickClient: exception — {}", e.getMessage());
        }

        @Override
        public void error(String str) {
            log.warn("TickByTickClient: error — {}", str);
        }

        @Override
        public void nextValidId(int orderId) {
            log.info("TickByTickClient: nextValidId={} — connection ready (RTVolume mode)", orderId);
        }
    }
}
