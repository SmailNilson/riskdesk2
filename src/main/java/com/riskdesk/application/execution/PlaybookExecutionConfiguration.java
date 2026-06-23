package com.riskdesk.application.execution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Enables the {@code riskdesk.playbook.position-watch.*} configuration properties. Sits in the
 * application layer (alongside {@link PlaybookPositionReconciler} and the properties it binds) so the
 * {@code infrastructure → application} ArchUnit rule is not violated — mirrors
 * {@code QuantAutomationConfiguration} for the quant virtual-stop properties.
 */
@Configuration
@EnableConfigurationProperties(PlaybookPositionWatchProperties.class)
public class PlaybookExecutionConfiguration {
}
