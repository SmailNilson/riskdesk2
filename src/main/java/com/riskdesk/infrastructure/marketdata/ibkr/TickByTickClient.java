package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.client.DefaultEWrapper;
import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;
import com.ib.client.TickAttribLast;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dedicated EClientSocket connection for tick-by-tick AllLast data.
 *
 * <p>Bypasses ApiController entirely because ApiController has a bug:
 * it calls {@code map.remove(reqId)} instead of {@code map.get(reqId)}
 * in its {@code tickByTickAllLast()} dispatch, killing the handler after
 * the first tick. This class uses a direct EClientSocket + custom EWrapper
 * with a handler registry that correctly uses {@code map.get()}.</p>
 *
 * <p>Runs on a separate IBKR clientId to avoid interfering with the main
 * ApiController connection (prices, quotes, depth, orders).</p>
 */
@Component
public class TickByTickClient {

    private static final Logger log = LoggerFactory.getLogger(TickByTickClient.class);

    private final IbkrProperties properties;
    private volatile IbkrTickDataAdapter tickDataAdapter;
    private volatile IbGatewayNativeClient nativeClient; // for bid/ask quotes (Lee-Ready)

    // Connection state
    private volatile EClientSocket client;
    private volatile EReader reader;
    private volatile EReaderSignal signal;
    private volatile boolean connected = false;

    // Handler registry — uses get() NOT remove()
    private final ConcurrentHashMap<Integer, TickHandler> handlers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> instrumentReqIds = new ConcurrentHashMap<>();
    private final AtomicInteger nextReqId = new AtomicInteger(1);

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
     * Connects to IBKR Gateway using a dedicated clientId for tick-by-tick only.
     * Uses EClientSocket directly — no ApiController.
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
            TickByTickEWrapper wrapper = new TickByTickEWrapper();
            client = new EClientSocket(wrapper, signal);
            client.eConnect(host, port, clientId);

            if (!client.isConnected()) {
                log.warn("TickByTickClient: failed to connect to {}:{} clientId={}", host, port, clientId);
                return;
            }

            // Start EReader thread
            reader = new EReader(client, signal);
            reader.start();

            // Start message processing thread with robust exception handling
            Thread processingThread = new Thread(() -> {
                while (client != null && client.isConnected()) {
                    signal.waitForSignal();
                    try {
                        reader.processMsgs();
                    } catch (Exception e) {
                        // CRITICAL: catch ALL exceptions, not just IOException
                        // Prevents silent thread death
                        log.warn("TickByTickClient processing error: {}", e.getMessage());
                    }
                }
                log.info("TickByTickClient processing thread exited");
            }, "tick-by-tick-processor");
            processingThread.setDaemon(true);
            processingThread.start();

            connected = true;
            log.info("TickByTickClient connected to {}:{} with clientId={}", host, port, clientId);

        } catch (Exception e) {
            log.error("TickByTickClient connection failed: {}", e.getMessage(), e);
            connected = false;
        }
    }

    public synchronized void disconnect() {
        connected = false;
        handlers.clear();
        instrumentReqIds.clear();
        if (client != null && client.isConnected()) {
            try {
                client.eDisconnect();
            } catch (Exception e) {
                log.debug("TickByTickClient disconnect error: {}", e.getMessage());
            }
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
     * Subscribe to tick-by-tick AllLast data for the given contract.
     * Uses EClientSocket directly — bypasses ApiController's buggy handler map.
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

        String key = instrumentKey(contract);
        if (instrumentReqIds.containsKey(key)) {
            return; // already subscribed
        }

        int reqId = nextReqId.getAndIncrement();
        TickHandler handler = new TickHandler(instrument, contract);
        handlers.put(reqId, handler);
        instrumentReqIds.put(key, reqId);

        // Direct EClientSocket call — no ApiController wrapper
        client.reqTickByTickData(reqId, contract, "AllLast", 0, false);
        log.info("TickByTickClient: subscribed AllLast for {} (reqId={}, conId={})",
                 instrument, reqId, contract.conid());
    }

    public void cancelTickByTick(Contract contract) {
        String key = instrumentKey(contract);
        Integer reqId = instrumentReqIds.remove(key);
        if (reqId != null) {
            handlers.remove(reqId);
            if (client != null && client.isConnected()) {
                try {
                    client.cancelTickByTickData(reqId);
                } catch (Exception e) {
                    log.debug("TickByTickClient cancel error: {}", e.getMessage());
                }
            }
        }
    }

    private String instrumentKey(Contract contract) {
        if (contract.conid() > 0) return "conid:" + contract.conid();
        return contract.symbol() + "|" + contract.exchange();
    }

    // -------------------------------------------------------------------------
    // Inner class: Tick handler per instrument
    // -------------------------------------------------------------------------

    private class TickHandler {
        private final Instrument instrument;
        private final Contract contract;
        private volatile boolean loggedFirstTick = false;

        TickHandler(Instrument instrument, Contract contract) {
            this.instrument = instrument;
            this.contract = contract;
        }

        void onTick(int tickType, long time, double price, Decimal size,
                     TickAttribLast attribs, String exchange, String specialConditions) {

            if (!loggedFirstTick) {
                loggedFirstTick = true;
                log.info("TickByTickClient: first tick for {} — price={}, size={}, exchange={}",
                         instrument, price, size, exchange);
            }

            long sizeVal = size != null ? size.longValue() : 0;
            if (sizeVal <= 0 || price <= 0) return;

            // Get bid/ask from the main connection (ApiController) for Lee-Ready classification
            double bid = 0;
            double ask = 0;
            IbGatewayNativeClient nc = nativeClient;
            if (nc != null) {
                var quote = nc.latestStreamingQuote(contract);
                if (quote.isPresent()) {
                    IbGatewayNativeClient.NativeMarketQuote q = quote.get();
                    bid = q.bid() != null ? q.bid().doubleValue() : 0;
                    ask = q.ask() != null ? q.ask().doubleValue() : 0;
                }
            }

            // Classify via Lee-Ready
            TickByTickAggregator.TickClassification classification =
                IbkrTickDataAdapter.classifyTrade(price, bid, ask);

            // Route to the adapter
            IbkrTickDataAdapter adapter = tickDataAdapter;
            if (adapter != null) {
                adapter.onTickByTickTrade(instrument, price, sizeVal, classification,
                                          Instant.ofEpochSecond(time));
            }
        }
    }

    // -------------------------------------------------------------------------
    // Inner class: EWrapper that dispatches ticks via handlers.get() (not remove)
    // -------------------------------------------------------------------------

    private class TickByTickEWrapper extends DefaultEWrapper {

        @Override
        public void tickByTickAllLast(int reqId, int tickType, long time, double price,
                                       Decimal size, TickAttribLast attribs,
                                       String exchange, String specialConditions) {
            // CRITICAL: use get() NOT remove() — this is the ApiController bug fix
            TickHandler handler = handlers.get(reqId);
            if (handler != null) {
                handler.onTick(tickType, time, price, size, attribs, exchange, specialConditions);
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
            if (errorCode == 2104 || errorCode == 2106 || errorCode == 2158) {
                return; // noisy info codes
            }
            if (id == -1) {
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
            log.info("TickByTickClient: nextValidId={} — connection ready", orderId);
        }
    }
}
