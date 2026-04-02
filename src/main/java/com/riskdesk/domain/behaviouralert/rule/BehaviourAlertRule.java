package com.riskdesk.domain.behaviouralert.rule;

import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;

import java.util.List;

/**
 * Extension point for behaviour-based alert rules.
 *
 * <p>To add a new rule type (e.g. Chaikin divergence, MACD behaviour):
 * <ol>
 *   <li>Implement this interface with the rule logic.</li>
 *   <li>Declare it as a {@code @Bean} in {@code BehaviourAlertConfig}.</li>
 *   <li>It will be auto-injected into {@code BehaviourAlertEvaluator} — no other changes needed.</li>
 * </ol>
 *
 * <p>Implementations are stateful (track NEAR/FAR transitions per instrument+timeframe).
 * They are Spring-managed singletons instantiated via factory beans.
 */
public interface BehaviourAlertRule {

    /**
     * Evaluates the market context and returns any signals to emit.
     * Returns an empty list if no noteworthy condition is detected.
     */
    List<BehaviourAlertSignal> evaluate(BehaviourAlertContext context);
}
