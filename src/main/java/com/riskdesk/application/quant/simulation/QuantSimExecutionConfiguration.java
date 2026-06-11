package com.riskdesk.application.quant.simulation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the {@link QuantSimExecutionProperties} and {@link QuantSimProperties}
 * bindings. Lives in the application layer so the
 * {@code infrastructure → application} ArchUnit rule is not violated (mirrors
 * {@code QuantAutomationConfiguration}).
 */
@Configuration
@EnableConfigurationProperties({QuantSimExecutionProperties.class, QuantSimProperties.class})
public class QuantSimExecutionConfiguration {
}
