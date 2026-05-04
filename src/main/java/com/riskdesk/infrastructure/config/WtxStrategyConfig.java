package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(WtxStrategyProperties.class)
public class WtxStrategyConfig {
}
