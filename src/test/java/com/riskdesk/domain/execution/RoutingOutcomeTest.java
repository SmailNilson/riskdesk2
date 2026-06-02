package com.riskdesk.domain.execution;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingOutcomeTest {

    @Test
    void everyOutcomeBelongsToExactlyOneCategory() {
        for (RoutingOutcome o : RoutingOutcome.values()) {
            int trues = (o.isSuccess() ? 1 : 0) + (o.isSkipped() ? 1 : 0) + (o.isFailure() ? 1 : 0);
            assertThat(trues).as("exactly one category predicate true for %s", o).isEqualTo(1);
        }
    }

    @Test
    void categoriesAreCorrect() {
        assertThat(RoutingOutcome.ROUTED.isSuccess()).isTrue();
        assertThat(RoutingOutcome.ACK_PENDING.isSuccess()).isTrue();
        assertThat(RoutingOutcome.SKIPPED_RECONCILING.isSkipped()).isTrue();
        assertThat(RoutingOutcome.SKIPPED_AUTO_OFF.isSkipped()).isTrue();
        assertThat(RoutingOutcome.FAILED_READ_ONLY.isFailure()).isTrue();
        assertThat(RoutingOutcome.FAILED_INSUFFICIENT_MARGIN.isFailure()).isTrue();
    }

    @Test
    void orderReachedBrokerOnlyForSubmittedOutcomes() {
        assertThat(RoutingOutcome.ROUTED.orderReachedBroker()).isTrue();
        assertThat(RoutingOutcome.ROUTED_FLATTEN_ONLY.orderReachedBroker()).isTrue();
        assertThat(RoutingOutcome.ACK_PENDING.orderReachedBroker()).isTrue();
        assertThat(RoutingOutcome.SKIPPED_DUPLICATE.orderReachedBroker()).isFalse();
        assertThat(RoutingOutcome.SKIPPED_RECONCILING.orderReachedBroker()).isFalse();
        assertThat(RoutingOutcome.FAILED_BROKER_REJECT.orderReachedBroker()).isFalse();
    }
}
