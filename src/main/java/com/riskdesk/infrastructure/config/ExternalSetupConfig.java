package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Spring wiring for the External Setup pipeline.
 * <p>The {@link Clock} bean is exposed here only if no other configuration provides it; tests
 * can override with a fixed clock via {@code @MockBean Clock}.
 */
@Configuration
@EnableConfigurationProperties(ExternalSetupProperties.class)
public class ExternalSetupConfig {

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
    public Clock externalSetupClock() {
        return Clock.systemUTC();
    }
}
