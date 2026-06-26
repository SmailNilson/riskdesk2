package com.riskdesk.infrastructure.marketdata.ibkr;

import com.ib.client.Contract;
import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the silent-feed-death detection and the rate-limited reconnect owner introduced to
 * fix "live data dies after a few hours". {@code isStreamingPriceFeedStale(now)} is the sole gate for
 * the 300s backstop reconnect, so its all-silent invariant and session/maintenance guards are pinned
 * here; {@code forceReconnect}'s cooldown short-circuit is pinned so neither watchdog can storm.
 */
class IbGatewayNativeClientFeedHealthTest {

    // Monday 2026-03-30 14:00:00Z = 10:00 EDT — market open, outside both maintenance windows.
    private static final Instant OPEN = Instant.parse("2026-03-30T14:00:00Z");

    @Test
    void feedStale_allShouldBeLiveSubsSilent_isTrue() {
        IbGatewayNativeClient client = new IbGatewayNativeClient(new IbkrProperties());
        putSubscription(client, "k_MNQ", Instrument.MNQ, OPEN.minusSeconds(200));
        putSubscription(client, "k_MGC", Instrument.MGC, OPEN.minusSeconds(300));

        assertThat(client.isStreamingPriceFeedStale(OPEN)).isTrue();
    }

    @Test
    void feedStale_oneSubStillFresh_isFalse() {   // pins silent==shouldBeLive (guards vs silent>=1)
        IbGatewayNativeClient client = new IbGatewayNativeClient(new IbkrProperties());
        putSubscription(client, "k_MNQ", Instrument.MNQ, OPEN.minusSeconds(200));
        putSubscription(client, "k_MGC", Instrument.MGC, OPEN.minusSeconds(5));   // fresh

        assertThat(client.isStreamingPriceFeedStale(OPEN)).isFalse();
    }

    @Test
    void feedStale_marketClosedWeekend_isFalse() {
        IbGatewayNativeClient client = new IbGatewayNativeClient(new IbkrProperties());
        Instant weekend = Instant.parse("2026-03-28T14:00:00Z");   // Saturday
        putSubscription(client, "k_MNQ", Instrument.MNQ, weekend.minusSeconds(5000));

        assertThat(client.isStreamingPriceFeedStale(weekend)).isFalse();
    }

    @Test
    void feedStale_duringStandardMaintenance_isFalse() {
        IbGatewayNativeClient client = new IbGatewayNativeClient(new IbkrProperties());
        Instant maint = Instant.parse("2026-03-30T21:30:00Z");   // 17:30 EDT — standard halt
        putSubscription(client, "k_MNQ", Instrument.MNQ, maint.minusSeconds(500));

        assertThat(client.isStreamingPriceFeedStale(maint)).isFalse();
    }

    @Test
    void feedStale_subWarmingUp_isFalse() {
        IbGatewayNativeClient client = new IbGatewayNativeClient(new IbkrProperties());
        putSubscription(client, "k_MNQ", Instrument.MNQ, null);   // no tick yet → not judgeable

        assertThat(client.isStreamingPriceFeedStale(OPEN)).isFalse();
    }

    @Test
    void feedStale_noSubscriptions_isFalse() {
        IbGatewayNativeClient client = new IbGatewayNativeClient(new IbkrProperties());
        assertThat(client.isStreamingPriceFeedStale(OPEN)).isFalse();
    }

    @Test
    void forceReconnect_withinCooldown_shortCircuitsToFalse() {
        IbGatewayNativeClient client = new IbGatewayNativeClient(new IbkrProperties());
        // Arm the cooldown into the future — the next forceReconnect must bail at the throttle check
        // BEFORE any disconnect()/ensureConnected() I/O.
        ReflectionTestUtils.setField(client, "forceReconnectBlockedUntil", Instant.now().plusSeconds(60));

        assertThat(client.forceReconnect("test")).isFalse();
    }

    // -- helpers --------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static void putSubscription(IbGatewayNativeClient client, String key,
                                        Instrument instrument, Instant lastPriceAt) {
        Object sub = newSubscription(client);
        ReflectionTestUtils.setField(sub, "lastPriceAt", lastPriceAt);
        ((Map<String, Object>) ReflectionTestUtils.getField(client, "streamingSubscriptions")).put(key, sub);
        ((Map<String, Instrument>) ReflectionTestUtils.getField(client, "contractKeyToInstrument")).put(key, instrument);
    }

    private static Object newSubscription(IbGatewayNativeClient client) {
        try {
            Class<?> inner = Class.forName(
                "com.riskdesk.infrastructure.marketdata.ibkr.IbGatewayNativeClient$StreamingPriceSubscription");
            Constructor<?> ctor = inner.getDeclaredConstructor(IbGatewayNativeClient.class, Contract.class);
            ctor.setAccessible(true);
            return ctor.newInstance(client, new Contract());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("cannot build StreamingPriceSubscription", e);
        }
    }
}
