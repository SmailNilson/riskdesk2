package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.controller.ApiController;
import com.ib.controller.Position;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * In-memory cache fed by a single, long-lived {@code reqAccountUpdates(true, accountId)}
 * subscription. Replaces the per-call subscribe/unsubscribe cycle that was racing with
 * itself under load — every concurrent {@code requestAccountSnapshot} used to fire its
 * own subscribe/unsubscribe pair, which IBKR responds to with {@code code=2100
 * "API client has been unsubscribed from account data"} when the cycles overlap, leaving
 * the latch unlatched and the request to time out.
 *
 * <p>The cache implements {@link ApiController.IAccountHandler} directly: callbacks from
 * the IBKR controller flow straight into the maps. Readers (snapshot consumers) get a
 * defensive copy via {@link #snapshot(List)} and never block on a controller round-trip.</p>
 *
 * <p>Thread-safety: {@link ConcurrentHashMap} for both {@code values} and {@code positions}.
 * The {@link CountDownLatch} signals when the first {@code accountDownloadEnd} has been
 * observed (initial bootstrap complete), so callers arriving before that can wait briefly
 * rather than returning empty.</p>
 *
 * <p>Lifecycle is owned by {@link IbGatewayNativeClient}: it constructs one instance per
 * subscribed {@code accountId}, registers it via {@code reqAccountUpdates(true, ...)} once
 * on connect, and unsubscribes via {@code reqAccountUpdates(false, ...)} on disconnect.</p>
 */
final class PersistentAccountSnapshotCache implements ApiController.IAccountHandler {

    private final String accountId;
    private final Map<String, String> values = new ConcurrentHashMap<>();
    private final Map<Integer, Position> positions = new ConcurrentHashMap<>();
    private final CountDownLatch initialDownload = new CountDownLatch(1);

    PersistentAccountSnapshotCache(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new IllegalArgumentException("accountId is required");
        }
        this.accountId = accountId;
    }

    String accountId() {
        return accountId;
    }

    /** True once the IBKR controller has fired the initial {@code accountDownloadEnd}. */
    boolean isInitialized() {
        return initialDownload.getCount() == 0;
    }

    /**
     * Wait for the initial download to complete. Returns true if it arrived within the
     * timeout, false otherwise. Idempotent: subsequent calls return immediately once
     * initialized.
     */
    boolean awaitInitial(Duration timeout) throws InterruptedException {
        return initialDownload.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Defensive snapshot — readers get a stable map even if updates are flowing in.
     * {@code BaseCurrency} is defaulted to USD to match the legacy contract used by
     * downstream consumers (IbGatewayBrokerGateway).
     */
    IbGatewayAccountSnapshot snapshot(List<String> managedAccounts) {
        Map<String, String> copy = new HashMap<>(values);
        copy.putIfAbsent("BaseCurrency", "USD");
        return new IbGatewayAccountSnapshot(accountId, managedAccounts, copy, List.copyOf(positions.values()));
    }

    // ----------------------------------------------------------------------
    // IAccountHandler callbacks (called from IBKR EReader thread)
    // ----------------------------------------------------------------------

    @Override
    public void accountValue(String account, String key, String value, String currency) {
        if (!accountId.equals(account) || key == null) {
            return;
        }
        if (value == null) {
            values.remove(key);
        } else {
            values.put(key, value);
        }
        if (currency != null && !currency.isBlank()) {
            values.put(key + ":currency", currency);
        }
    }

    @Override
    public void accountTime(String timeStamp) {
        if (timeStamp != null) {
            values.put("AccountTime", timeStamp);
        }
    }

    @Override
    public void accountDownloadEnd(String account) {
        if (accountId.equals(account)) {
            initialDownload.countDown();
        }
    }

    /**
     * Position updates flow continuously. A zero/invalid position is the broker's way
     * of saying "this conid is now flat" — drop it from the map so the snapshot reflects
     * the current open positions only. Matches the filter used by the legacy one-shot
     * handler in {@link IbGatewayNativeClient#requestAccountSnapshot(String)}.
     */
    @Override
    public void updatePortfolio(Position position) {
        if (position == null
            || !accountId.equals(position.account())
            || position.position() == null
            || !position.position().isValid()) {
            return;
        }
        if (position.position().isZero()) {
            positions.remove(position.conid());
            return;
        }
        positions.put(position.conid(), position);
    }
}
