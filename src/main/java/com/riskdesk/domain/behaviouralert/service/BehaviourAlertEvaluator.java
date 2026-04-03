package com.riskdesk.domain.behaviouralert.service;

import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;
import com.riskdesk.domain.behaviouralert.rule.BehaviourAlertRule;

import java.util.List;

/**
 * Delegates evaluation to the registered {@link BehaviourAlertRule} implementations.
 * New rules are registered by declaring a {@code @Bean} in {@code BehaviourAlertConfig} —
 * no changes to this class are needed.
 */
public class BehaviourAlertEvaluator {

    private final List<BehaviourAlertRule> rules;

    public BehaviourAlertEvaluator(List<BehaviourAlertRule> rules) {
        this.rules = rules;
    }

    public List<BehaviourAlertSignal> evaluate(BehaviourAlertContext context) {
        return rules.stream()
                .flatMap(rule -> rule.evaluate(context).stream())
                .toList();
    }
}
