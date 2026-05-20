package com.riskdesk.infrastructure.marketdata.ibkr;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Targets the cache-invalidation contract that backs the
 * {@code code=2100 "API client has been unsubscribed from account data"} recovery
 * path on the persistent account subscription. The legacy
 * subscribe/unsubscribe-per-call cycle is gone (PR #338) so the only remaining
 * vector for a frozen snapshot is IBKR forcing an unsubscribe on our long-lived
 * handler — e.g. when another API client opens a competing
 * {@code reqAccountUpdates} on the same connection. Without invalidation the
 * fast-path in {@code ensurePersistentAccountSubscription} would return the
 * dead cache forever and the WTX margin pre-flight would decide on data that no
 * longer matches the broker. Tests below pin that contract.
 */
class IbGatewayNativeClientPersistentAccountTest {

    @Test
    void invalidatePersistentAccountCacheClearsTheField() throws Exception {
        IbGatewayNativeClient client = new IbGatewayNativeClient(new IbkrProperties());
        PersistentAccountSnapshotCache cache = new PersistentAccountSnapshotCache("U10670585");
        setPersistentCache(client, cache);
        assertThat(readPersistentCache(client)).isSameAs(cache);

        client.invalidatePersistentAccountCache("code=2100 simulated");

        assertThat(readPersistentCache(client)).isNull();
    }

    @Test
    void invalidatePersistentAccountCacheIsNoopWhenAlreadyNull() {
        IbGatewayNativeClient client = new IbGatewayNativeClient(new IbkrProperties());

        // Must not throw even when no subscription was ever started.
        client.invalidatePersistentAccountCache("spurious 2100 on startup");

        // Cannot read the field without reflection, but the absence of an exception
        // is the entire contract here — and the subsequent test re-checks the field
        // is still null after the no-op call.
        try {
            assertThat(readPersistentCache(client)).isNull();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void invalidatePersistentAccountCacheIsIdempotent() throws Exception {
        IbGatewayNativeClient client = new IbGatewayNativeClient(new IbkrProperties());
        setPersistentCache(client, new PersistentAccountSnapshotCache("U10670585"));

        client.invalidatePersistentAccountCache("first 2100");
        client.invalidatePersistentAccountCache("second 2100 before re-bootstrap");

        assertThat(readPersistentCache(client)).isNull();
    }

    // ----------------------------------------------------------------------

    private static void setPersistentCache(IbGatewayNativeClient client,
                                            PersistentAccountSnapshotCache cache) throws Exception {
        Field f = IbGatewayNativeClient.class.getDeclaredField("persistentAccountCache");
        f.setAccessible(true);
        f.set(client, cache);
    }

    private static PersistentAccountSnapshotCache readPersistentCache(IbGatewayNativeClient client) throws Exception {
        Field f = IbGatewayNativeClient.class.getDeclaredField("persistentAccountCache");
        f.setAccessible(true);
        return (PersistentAccountSnapshotCache) f.get(client);
    }
}
