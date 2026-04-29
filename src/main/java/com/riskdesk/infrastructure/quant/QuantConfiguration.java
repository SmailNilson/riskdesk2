package com.riskdesk.infrastructure.quant;

import com.riskdesk.domain.quant.engine.GateEvaluator;
import com.riskdesk.domain.quant.narrative.QuantNarrator;
import com.riskdesk.domain.quant.pattern.OrderFlowPatternDetector;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the framework-free domain classes ({@link GateEvaluator},
 * {@link OrderFlowPatternDetector}, {@link QuantNarrator}) into the Spring
 * container. Kept here (infrastructure layer) so the domain classes stay
 * independent of Spring annotations.
 */
@Configuration
public class QuantConfiguration {

    @Bean
    public GateEvaluator gateEvaluator() {
        return new GateEvaluator();
    }

    @Bean
    public OrderFlowPatternDetector orderFlowPatternDetector() {
        return new OrderFlowPatternDetector();
    }

    @Bean
    public QuantNarrator quantNarrator() {
        return new QuantNarrator();
    }
}
