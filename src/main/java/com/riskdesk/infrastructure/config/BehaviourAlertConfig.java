package com.riskdesk.infrastructure.config;

import com.riskdesk.domain.behaviouralert.rule.BehaviourAlertRule;
import com.riskdesk.domain.behaviouralert.rule.CmfDivergenceRule;
import com.riskdesk.domain.behaviouralert.rule.CmfPriceConfirmationRule;
import com.riskdesk.domain.behaviouralert.rule.Ema200ProximityRule;
import com.riskdesk.domain.behaviouralert.rule.Ema50ProximityRule;
import com.riskdesk.domain.behaviouralert.rule.ExtremeCmfZoneRule;
import com.riskdesk.domain.behaviouralert.rule.SupportResistanceTouchRule;
import com.riskdesk.domain.behaviouralert.service.BehaviourAlertEvaluator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Wires the behaviour alert domain as pure-Java beans.
 *
 * <p>To add a new rule (e.g. ChaikinDivergenceRule):
 * <pre>
 *   {@code @Bean public ChaikinDivergenceRule chaikinDivergenceRule() { return new ChaikinDivergenceRule(); }}
 * </pre>
 * Spring will inject it automatically into {@code behaviourAlertEvaluator} via the {@code List<BehaviourAlertRule>} parameter.
 */
@Configuration
public class BehaviourAlertConfig {

    @Bean
    public Ema50ProximityRule ema50ProximityRule() {
        return new Ema50ProximityRule();
    }

    @Bean
    public Ema200ProximityRule ema200ProximityRule() {
        return new Ema200ProximityRule();
    }

    @Bean
    public SupportResistanceTouchRule supportResistanceTouchRule() {
        return new SupportResistanceTouchRule();
    }

    @Bean
    public ExtremeCmfZoneRule extremeCmfZoneRule() {
        return new ExtremeCmfZoneRule();
    }

    @Bean
    public CmfDivergenceRule cmfDivergenceRule() {
        return new CmfDivergenceRule();
    }

    @Bean
    public CmfPriceConfirmationRule cmfPriceConfirmationRule() {
        return new CmfPriceConfirmationRule();
    }

    @Bean
    public BehaviourAlertEvaluator behaviourAlertEvaluator(List<BehaviourAlertRule> rules) {
        return new BehaviourAlertEvaluator(rules);
    }
}
