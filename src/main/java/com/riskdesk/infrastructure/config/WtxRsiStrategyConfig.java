package com.riskdesk.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link WtxRsiStrategyProperties} so that {@code riskdesk.wtxrsi.*}
 * keys in application.properties / application-local.properties bind into a
 * Spring bean. Keeping this lightweight stub avoids polluting unrelated configs.
 */
@Configuration
@EnableConfigurationProperties(WtxRsiStrategyProperties.class)
public class WtxRsiStrategyConfig {
}
