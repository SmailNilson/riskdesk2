package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.alert.service.AlertDeduplicator;
import com.riskdesk.domain.alert.service.IndicatorAlertEvaluator;
import com.riskdesk.domain.alert.service.RiskAlertEvaluator;
import com.riskdesk.domain.alert.service.SignalPreFilterService;
import com.riskdesk.domain.trading.service.RiskSpecification;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires domain-layer alert services as Spring beans.
 * Keeps domain classes free of Spring annotations (pure Java).
 */
@Configuration
@EnableConfigurationProperties(RiskProperties.class)
public class AlertConfig {

    private static final long DEDUP_COOLDOWN_SECONDS = 300; // 5 minutes

    @Bean
    public RiskSpecification riskSpecification(RiskProperties props) {
        return new RiskSpecification(props.getMaxMarginUsagePct(), props.getMaxSinglePositionPct());
    }

    @Bean
    public RiskAlertEvaluator riskAlertEvaluator(RiskSpecification riskSpecification) {
        return new RiskAlertEvaluator(riskSpecification);
    }

    @Bean
    public IndicatorAlertEvaluator indicatorAlertEvaluator() {
        return new IndicatorAlertEvaluator();
    }

    @Bean
    public AlertDeduplicator alertDeduplicator() {
        return new AlertDeduplicator(DEDUP_COOLDOWN_SECONDS);
    }

    @Bean
    public SignalPreFilterService signalPreFilterService() {
        return new SignalPreFilterService();
    }
}
