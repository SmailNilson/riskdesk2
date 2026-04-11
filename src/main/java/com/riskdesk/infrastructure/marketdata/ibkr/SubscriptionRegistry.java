package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.model.Instrument;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks what IBKR subscriptions SHOULD be active. Used by the reconnection
 * resilience logic to restore subscriptions after a connection drop.
 *
 * <p>Each subscription is identified by an {@link Instrument} and {@link SubscriptionType}.
 * The registry is populated when subscriptions are successfully created and consulted
 * during resubscription after reconnect.</p>
 *
 * <p>Thread-safe: uses ConcurrentHashMap.newKeySet().</p>
 */
@Component
public class SubscriptionRegistry {

    private final Set<SubscriptionEntry> entries = ConcurrentHashMap.newKeySet();

    /**
     * Represents a single subscription that should be active.
     */
    public record SubscriptionEntry(Instrument instrument, SubscriptionType type) {}

    /**
     * The type of IBKR data subscription.
     */
    public enum SubscriptionType {
        PRICE,
        QUOTE,
        TICK_BY_TICK,
        DEPTH
    }

    /**
     * Registers a subscription as one that should be active.
     */
    public void register(Instrument instrument, SubscriptionType type) {
        entries.add(new SubscriptionEntry(instrument, type));
    }

    /**
     * Removes a subscription from the registry.
     */
    public void unregister(Instrument instrument, SubscriptionType type) {
        entries.remove(new SubscriptionEntry(instrument, type));
    }

    /**
     * Returns an immutable snapshot of all registered subscriptions.
     */
    public Set<SubscriptionEntry> allEntries() {
        return Set.copyOf(entries);
    }

    /**
     * Returns the number of registered subscriptions.
     */
    public int size() {
        return entries.size();
    }
}
