package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(PlaybookStrategyProperties.class)
public class PlaybookStrategyConfig {
}
