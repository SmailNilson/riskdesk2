package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.alert.port.AlertStateStore;
import com.riskdesk.domain.alert.service.AlertDeduplicator;
import com.riskdesk.domain.alert.service.IndicatorAlertEvaluator;
import com.riskdesk.domain.alert.service.RiskAlertEvaluator;
import com.riskdesk.domain.alert.service.SignalPreFilterService;
import com.riskdesk.domain.calendar.NewsBlackoutCalendar;
import com.riskdesk.domain.calendar.NewsEvent;
import com.riskdesk.domain.trading.service.RiskSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * Wires domain-layer alert services as Spring beans.
 * Keeps domain classes free of Spring annotations (pure Java).
 */
@Configuration
@EnableConfigurationProperties({RiskProperties.class, NewsBlackoutProperties.class})
public class AlertConfig {

    private static final Logger log = LoggerFactory.getLogger(AlertConfig.class);
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
    public IndicatorAlertEvaluator indicatorAlertEvaluator(AlertStateStore alertStateStore) {
        return new IndicatorAlertEvaluator(alertStateStore);
    }

    @Bean
    public AlertDeduplicator alertDeduplicator() {
        return new AlertDeduplicator(DEDUP_COOLDOWN_SECONDS);
    }

    @Bean
    public NewsBlackoutCalendar newsBlackoutCalendar(NewsBlackoutProperties props) {
        if (!props.isEnabled()) {
            log.info("News blackout disabled — Rule 6 is a no-op (riskdesk.news-blackout.enabled=false).");
            return NewsBlackoutCalendar.disabled();
        }
        List<NewsEvent> events = props.getEvents().stream()
            .filter(e -> e.getTimestamp() != null && e.getName() != null && !e.getName().isBlank())
            .map(NewsBlackoutProperties.Event::toDomain)
            .toList();
        NewsBlackoutCalendar calendar = new NewsBlackoutCalendar(
            true,
            events,
            Duration.ofMinutes(Math.max(0, props.getPreEventMinutes())),
            Duration.ofMinutes(Math.max(0, props.getPostEventMinutes()))
        );
        log.info("News blackout enabled — {} event(s), pre={}min post={}min.",
            events.size(), props.getPreEventMinutes(), props.getPostEventMinutes());
        return calendar;
    }

    @Bean
    public SignalPreFilterService signalPreFilterService(NewsBlackoutCalendar newsBlackoutCalendar) {
        return new SignalPreFilterService(newsBlackoutCalendar);
    }
}
