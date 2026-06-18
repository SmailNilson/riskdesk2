package com.riskdesk.application.marketdata;

import java.util.Set;

/**
 * The canonical set of {@code StoredPrice.source()} values that represent a genuinely live IBKR
 * quote (as opposed to a stale cache fallback or a DB backfill).
 *
 * <p>{@code MarketDataService} only ever stamps two live sources — {@code LIVE_PUSH} (streaming
 * tick) and {@code LIVE_PROVIDER} (synchronous instant fetch); everything else is {@code CACHE},
 * {@code FALLBACK_DB}, or {@code UNKNOWN}. This is the single source of truth so the routing gate,
 * the fast-exit listener, the order router, and the invalidation watcher cannot drift apart.</p>
 *
 * <p>Note: this asserts only that the source is live, not that it is <em>fresh</em>. Callers acting
 * on an irreversible side effect (e.g. cancelling a resting order) must additionally check the
 * quote timestamp — a live source can still be served from a short-lived cache.</p>
 */
public final class LivePriceSource {

    public static final Set<String> SOURCES = Set.of("LIVE_PUSH", "LIVE_PROVIDER");

    private LivePriceSource() {
    }

    public static boolean isLive(String source) {
        return source != null && SOURCES.contains(source);
    }
}
