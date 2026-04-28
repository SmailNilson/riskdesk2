package com.riskdesk.application.externalsetup;

import com.riskdesk.infrastructure.config.ExternalSetupProperties;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory token-aware rate limiter for {@code POST /api/external-setups}.
 * <p>Sliding 60-second window per token. Cheap enough for the single-instance deployment;
 * if the SaaS ever scales out, replace with Redis-backed Bucket4j.
 */
@Component
public class ExternalSetupRateLimiter {

    private final ExternalSetupProperties properties;
    private final Clock clock;
    private final ConcurrentHashMap<String, Deque<Instant>> buckets = new ConcurrentHashMap<>();

    public ExternalSetupRateLimiter(ExternalSetupProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /** @return true when the request is admitted; false when over the per-minute limit. */
    public synchronized boolean tryAcquire(String tokenKey) {
        if (tokenKey == null) tokenKey = "anonymous";
        int limit = Math.max(properties.getRateLimitPerMinute(), 1);
        Instant now = Instant.now(clock);
        Instant cutoff = now.minusSeconds(60);
        Deque<Instant> q = buckets.computeIfAbsent(tokenKey, k -> new ArrayDeque<>());
        while (!q.isEmpty() && q.peekFirst().isBefore(cutoff)) {
            q.pollFirst();
        }
        if (q.size() >= limit) {
            return false;
        }
        q.addLast(now);
        return true;
    }
}
