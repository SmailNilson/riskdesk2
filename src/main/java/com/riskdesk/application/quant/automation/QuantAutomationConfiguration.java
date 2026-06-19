package com.riskdesk.application.quant.automation;

import com.riskdesk.application.quant.positions.QuantVirtualStopProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Spring config that enables the auto-arm / auto-submit / virtual-stop configuration
 * properties. Sits in the application layer so the
 * {@code infrastructure → application} ArchUnit rule is not violated.
 */
@Configuration
@EnableConfigurationProperties({
    QuantAutoArmProperties.class,
    QuantAutoSubmitProperties.class,
    QuantVirtualStopProperties.class
})
public class QuantAutomationConfiguration {
}
