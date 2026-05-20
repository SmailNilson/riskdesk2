package com.riskdesk.application.quant.automation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring config that enables the auto-arm / auto-submit configuration
 * properties. Sits in the application layer so the
 * {@code infrastructure → application} ArchUnit rule is not violated.
 */
@Configuration
@EnableConfigurationProperties({QuantAutoArmProperties.class, QuantAutoSubmitProperties.class})
public class QuantAutomationConfiguration {
}
