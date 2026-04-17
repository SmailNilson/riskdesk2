package com.riskdesk.domain.behaviouralert.service;

import com.riskdesk.domain.behaviouralert.model.BehaviourAlertCategory;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertContext;
import com.riskdesk.domain.behaviouralert.model.BehaviourAlertSignal;
import com.riskdesk.domain.behaviouralert.rule.BehaviourAlertRule;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class BehaviourAlertEvaluatorTest {

    private static final BehaviourAlertContext CTX = new BehaviourAlertContext(
        "MCL", "10m", new BigDecimal("72.50"),
        new BigDecimal("72.00"), new BigDecimal("71.00"),
        List.of(), Instant.now(), BigDecimal.ZERO, BigDecimal.ZERO
    );

    private static BehaviourAlertSignal signal(String key) {
        return new BehaviourAlertSignal(key, BehaviourAlertCategory.CHAIKIN_BEHAVIOUR, "test", "MCL", Instant.now());
    }

    @Test
    void evaluate_noRules_returnsEmptyList() {
        BehaviourAlertEvaluator evaluator = new BehaviourAlertEvaluator(List.of());

        List<BehaviourAlertSignal> signals = evaluator.evaluate(CTX);

        assertTrue(signals.isEmpty());
    }

    @Test
    void evaluate_singleRule_returnsItsSignals() {
        BehaviourAlertSignal sig = signal("s1");
        BehaviourAlertRule rule = mock(BehaviourAlertRule.class);
        when(rule.evaluate(CTX)).thenReturn(List.of(sig));

        BehaviourAlertEvaluator evaluator = new BehaviourAlertEvaluator(List.of(rule));

        List<BehaviourAlertSignal> signals = evaluator.evaluate(CTX);

        assertEquals(1, signals.size());
        assertSame(sig, signals.get(0));
    }

    @Test
    void evaluate_multipleRules_flattensResults() {
        BehaviourAlertRule rule1 = mock(BehaviourAlertRule.class);
        when(rule1.evaluate(CTX)).thenReturn(List.of(signal("s1")));

        BehaviourAlertRule rule2 = mock(BehaviourAlertRule.class);
        when(rule2.evaluate(CTX)).thenReturn(List.of(signal("s2"), signal("s3")));

        BehaviourAlertEvaluator evaluator = new BehaviourAlertEvaluator(List.of(rule1, rule2));

        List<BehaviourAlertSignal> signals = evaluator.evaluate(CTX);

        assertEquals(3, signals.size());
    }

    @Test
    void evaluate_ruleReturnsEmpty_doesNotAffectOtherRules() {
        BehaviourAlertRule silentRule = mock(BehaviourAlertRule.class);
        when(silentRule.evaluate(CTX)).thenReturn(List.of());

        BehaviourAlertRule activeRule = mock(BehaviourAlertRule.class);
        when(activeRule.evaluate(CTX)).thenReturn(List.of(signal("s1")));

        BehaviourAlertEvaluator evaluator = new BehaviourAlertEvaluator(List.of(silentRule, activeRule));

        List<BehaviourAlertSignal> signals = evaluator.evaluate(CTX);

        assertEquals(1, signals.size());
    }

    @Test
    void evaluate_invokesAllRules() {
        BehaviourAlertRule rule1 = mock(BehaviourAlertRule.class);
        when(rule1.evaluate(CTX)).thenReturn(List.of());
        BehaviourAlertRule rule2 = mock(BehaviourAlertRule.class);
        when(rule2.evaluate(CTX)).thenReturn(List.of());
        BehaviourAlertRule rule3 = mock(BehaviourAlertRule.class);
        when(rule3.evaluate(CTX)).thenReturn(List.of());

        BehaviourAlertEvaluator evaluator = new BehaviourAlertEvaluator(List.of(rule1, rule2, rule3));
        evaluator.evaluate(CTX);

        verify(rule1).evaluate(CTX);
        verify(rule2).evaluate(CTX);
        verify(rule3).evaluate(CTX);
    }
}
