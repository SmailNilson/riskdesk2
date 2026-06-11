package com.riskdesk.infrastructure.quant;

import com.riskdesk.domain.quant.engine.GateEvaluator;
import com.riskdesk.domain.quant.narrative.QuantNarrator;
import com.riskdesk.domain.quant.pattern.OrderFlowPatternDetector;
import com.riskdesk.domain.quant.structure.StructuralFilterEvaluator;
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
    public GateEvaluator gateEvaluator(QuantGateProperties gateProperties) {
        // Veto tiers, per-instrument delta thresholds and buy% bands come from
        // riskdesk.quant.{veto,gates}.* — converted to a framework-free value
        // object so the domain never touches Spring configuration.
        return new GateEvaluator(gateProperties.toDomainConfig());
    }

    @Bean
    public OrderFlowPatternDetector orderFlowPatternDetector() {
        return new OrderFlowPatternDetector();
    }

    @Bean
    public QuantNarrator quantNarrator() {
        return new QuantNarrator();
    }

    @Bean
    public StructuralFilterEvaluator structuralFilterEvaluator() {
        return new StructuralFilterEvaluator();
    }
}
