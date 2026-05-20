package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.ib.client.Decimal;
import com.ib.controller.Position;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the in-memory mirror that replaced the legacy
 * {@code reqAccountUpdates(true)} → {@code accountLatch.await} →
 * {@code reqAccountUpdates(false)} cycle inside {@link IbGatewayNativeClient}.
 *
 * <p>The legacy cycle raced with itself under concurrent load and triggered the
 * IBKR {@code code=2100 "client unsubscribed from account data"} bursts that
 * starved {@code orderStatus} callbacks (WTX ACK_PENDING incident 2026-05-20).
 * This cache is the structural fix: a single permanent handler, snapshots are
 * map copies.</p>
 */
class PersistentAccountSnapshotCacheTest {

    private static final String ACCOUNT_ID   = "U10670585";
    private static final String OTHER_ACCT   = "U99999999";
    private static final List<String> MANAGED = List.of(ACCOUNT_ID, OTHER_ACCT);

    @Test
    void notInitializedUntilAccountDownloadEndArrives() {
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);

        assertThat(cache.isInitialized()).isFalse();

        cache.accountDownloadEnd(ACCOUNT_ID);

        assertThat(cache.isInitialized()).isTrue();
    }

    @Test
    void accountDownloadEndForWrongAccountIsIgnored() {
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);

        cache.accountDownloadEnd(OTHER_ACCT);

        assertThat(cache.isInitialized()).isFalse();
    }

    @Test
    void awaitInitialReturnsFalseOnTimeout() throws InterruptedException {
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);

        boolean ready = cache.awaitInitial(Duration.ofMillis(50));

        assertThat(ready).isFalse();
    }

    @Test
    void awaitInitialReturnsTrueOnceDownloadEnd() throws InterruptedException {
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);
        cache.accountDownloadEnd(ACCOUNT_ID);

        assertThat(cache.awaitInitial(Duration.ofMillis(50))).isTrue();
        // Idempotent — repeated calls keep returning true without blocking.
        assertThat(cache.awaitInitial(Duration.ofMillis(50))).isTrue();
    }

    @Test
    void accountValuesFlowIntoSnapshotWithBaseCurrencyDefault() {
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);

        cache.accountValue(ACCOUNT_ID, "NetLiquidation", "9427.77", "USD");
        cache.accountValue(ACCOUNT_ID, "AvailableFunds", "9300.00", "USD");
        cache.accountTime("10:30");
        cache.accountDownloadEnd(ACCOUNT_ID);

        IbGatewayAccountSnapshot snap = cache.snapshot(MANAGED);

        assertThat(snap.accountId()).isEqualTo(ACCOUNT_ID);
        assertThat(snap.accounts()).containsExactly(ACCOUNT_ID, OTHER_ACCT);
        assertThat(snap.values())
            .containsEntry("NetLiquidation", "9427.77")
            .containsEntry("NetLiquidation:currency", "USD")
            .containsEntry("AvailableFunds", "9300.00")
            .containsEntry("AccountTime", "10:30")
            .containsEntry("BaseCurrency", "USD");
    }

    @Test
    void accountValueFromOtherAccountIsIgnored() {
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);

        cache.accountValue(OTHER_ACCT, "NetLiquidation", "999999.00", "USD");

        assertThat(cache.snapshot(MANAGED).values()).doesNotContainKey("NetLiquidation");
    }

    @Test
    void nullValueRemovesKey() {
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);
        cache.accountValue(ACCOUNT_ID, "BuyingPower", "62000.00", "USD");

        cache.accountValue(ACCOUNT_ID, "BuyingPower", null, "USD");

        assertThat(cache.snapshot(MANAGED).values()).doesNotContainKey("BuyingPower");
    }

    @Test
    void updatePortfolioStoresNonZeroPositionsAndDropsFlattened() {
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);

        cache.updatePortfolio(position(637533640, 1, ACCOUNT_ID));   // MNQ long 1
        cache.updatePortfolio(position(681157519, -1, ACCOUNT_ID));  // MCL short 1
        IbGatewayAccountSnapshot afterOpens = cache.snapshot(MANAGED);
        assertThat(afterOpens.positions()).hasSize(2);

        // MNQ flattens — broker re-sends with quantity 0; cache must drop it.
        cache.updatePortfolio(position(637533640, 0, ACCOUNT_ID));
        IbGatewayAccountSnapshot afterFlatten = cache.snapshot(MANAGED);
        assertThat(afterFlatten.positions())
            .hasSize(1)
            .extracting(Position::conid)
            .containsExactly(681157519);
    }

    @Test
    void positionFromOtherAccountIsIgnored() {
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);

        cache.updatePortfolio(position(637533640, 1, OTHER_ACCT));

        assertThat(cache.snapshot(MANAGED).positions()).isEmpty();
    }

    @Test
    void snapshotIsDefensiveCopy() {
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);
        cache.accountValue(ACCOUNT_ID, "NetLiquidation", "100.00", "USD");

        IbGatewayAccountSnapshot first = cache.snapshot(MANAGED);

        // Live update arrives after the snapshot was taken — must not mutate the prior copy.
        cache.accountValue(ACCOUNT_ID, "NetLiquidation", "200.00", "USD");

        assertThat(first.values()).containsEntry("NetLiquidation", "100.00");
        assertThat(cache.snapshot(MANAGED).values()).containsEntry("NetLiquidation", "200.00");
    }

    @Test
    void concurrentWritesAndReadsDoNotLoseUpdatesOrThrow() throws Exception {
        // Stress the ConcurrentHashMap-backed cache: parallel callback delivery
        // (IBKR EReader thread + retries on reconnect) + parallel readers (frontend
        // poll, WTX margin pre-flight). Verifies a snapshot mid-update is internally
        // consistent and the final state captures every distinct key.
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache(ACCOUNT_ID);
        int writers = 4;
        int readers = 4;
        int writesPerWriter = 250;

        ExecutorService pool = Executors.newFixedThreadPool(writers + readers);
        try {
            for (int w = 0; w < writers; w++) {
                final int writerIdx = w;
                pool.submit(() -> {
                    for (int i = 0; i < writesPerWriter; i++) {
                        cache.accountValue(ACCOUNT_ID, "k-" + writerIdx + "-" + i, String.valueOf(i), "USD");
                    }
                });
            }
            for (int r = 0; r < readers; r++) {
                pool.submit(() -> {
                    for (int i = 0; i < 500; i++) {
                        cache.snapshot(MANAGED); // must not throw ConcurrentModificationException
                    }
                });
            }
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            if (!pool.isTerminated()) pool.shutdownNow();
        }

        IbGatewayAccountSnapshot finalSnap = cache.snapshot(MANAGED);
        long businessKeys = finalSnap.values().keySet().stream()
            .filter(k -> k.startsWith("k-") && !k.endsWith(":currency"))
            .count();
        assertThat(businessKeys).isEqualTo((long) writers * writesPerWriter);
    }

    @Test
    void rejectsNullOrBlankAccountId() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new PersistentAccountSnapshotCache(null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> new PersistentAccountSnapshotCache("   "));
    }

    // ----------------------------------------------------------------------

    private static Position position(int conid, double qty, String account) {
        Contract contract = new Contract();
        contract.conid(conid);
        return new Position(contract, account, Decimal.get(qty), 0d, 0d, 0d, 0d, 0d);
    }
}
