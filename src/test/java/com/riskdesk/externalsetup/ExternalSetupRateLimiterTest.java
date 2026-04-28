package com.riskdesk.externalsetup;

import com.riskdesk.application.externalsetup.ExternalSetupRateLimiter;
import com.riskdesk.infrastructure.config.ExternalSetupProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class ExternalSetupRateLimiterTest {

    @Test
    void admitsUpToLimitThenRejects() {
        ExternalSetupProperties p = new ExternalSetupProperties();
        p.setRateLimitPerMinute(3);
        Clock clock = Clock.fixed(Instant.parse("2026-04-28T10:00:00Z"), ZoneOffset.UTC);
        ExternalSetupRateLimiter rl = new ExternalSetupRateLimiter(p, clock);

        assertThat(rl.tryAcquire("token-A")).isTrue();
        assertThat(rl.tryAcquire("token-A")).isTrue();
        assertThat(rl.tryAcquire("token-A")).isTrue();
        assertThat(rl.tryAcquire("token-A")).isFalse();

        // separate token has its own bucket
        assertThat(rl.tryAcquire("token-B")).isTrue();
    }
}
