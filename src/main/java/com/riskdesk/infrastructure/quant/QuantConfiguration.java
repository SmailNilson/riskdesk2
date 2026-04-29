package com.riskdesk.infrastructure.quant;

import com.riskdesk.domain.quant.engine.GateEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free {@link GateEvaluator} into the Spring container.
 * Kept here (infrastructure layer) so the domain classes stay independent of Spring.
 */
@Configuration
public class QuantConfiguration {

    @Bean
    public GateEvaluator gateEvaluator() {
        return new GateEvaluator();
    }
}
