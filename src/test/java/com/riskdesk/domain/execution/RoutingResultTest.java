package com.riskdesk.domain.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RoutingResultTest {

    @Test
    void ofSetsOutcomeOnly() {
        RoutingResult r = RoutingResult.of(RoutingOutcome.SKIPPED_AUTO_OFF);
        assertThat(r.outcome()).isEqualTo(RoutingOutcome.SKIPPED_AUTO_OFF);
        assertThat(r.message()).isNull();
        assertThat(r.executionId()).isNull();
        assertThat(r.brokerOrderId()).isNull();
    }

    @Test
    void trackedCarriesExecutionRowIdIndependentlyOfBrokerOrderId() {
        // brokerOrderId can be null (e.g. FAILED_TIMEOUT) while the execution row id still links the
        // caller back to the persisted trade_executions row.
        RoutingResult r = RoutingResult.tracked(RoutingOutcome.FAILED_TIMEOUT, 4242L, null);
        assertThat(r.outcome()).isEqualTo(RoutingOutcome.FAILED_TIMEOUT);
        assertThat(r.executionId()).isEqualTo(4242L);
        assertThat(r.brokerOrderId()).isNull();
    }

    @Test
    void trackedWithBothIdsAndMessage() {
        RoutingResult r = RoutingResult.tracked(RoutingOutcome.ROUTED, "ok", 7L, 99L);
        assertThat(r.executionId()).isEqualTo(7L);
        assertThat(r.brokerOrderId()).isEqualTo(99L);
        assertThat(r.message()).isEqualTo("ok");
    }

    @Test
    void rejectsNullOutcome() {
        assertThatThrownBy(() -> new RoutingResult(null, null, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("outcome");
    }
}
