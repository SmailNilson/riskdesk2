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
        // Behavioural skip preserved from WtxRoutingOutcome — caller reverts virtual state.
        assertThat(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT.isSkipped()).isTrue();
        assertThat(RoutingOutcome.SKIPPED_INSUFFICIENT_MARGIN.isSkipped()).isTrue();
        // Disabled reasons kept distinct (diagnosable in signal history / UI).
        assertThat(RoutingOutcome.SKIPPED_BRIDGE_UNAVAILABLE.isSkipped()).isTrue();
        assertThat(RoutingOutcome.SKIPPED_IBKR_DISABLED.isSkipped()).isTrue();
        assertThat(RoutingOutcome.FAILED_READ_ONLY.isFailure()).isTrue();
        assertThat(RoutingOutcome.FAILED_INSUFFICIENT_MARGIN.isFailure()).isTrue();
    }

    @Test
    void mustTrackExecutionRowIncludesTimeoutNotRejects() {
        // Submitted (or possibly-live) outcomes need a persisted row.
        assertThat(RoutingOutcome.ROUTED.mustTrackExecutionRow()).isTrue();
        assertThat(RoutingOutcome.ROUTED_FLATTEN_ONLY.mustTrackExecutionRow()).isTrue();
        assertThat(RoutingOutcome.ACK_PENDING.mustTrackExecutionRow()).isTrue();
        // Timeout = broker state UNKNOWN → keep the row non-terminal for late reconcile / dedup.
        assertThat(RoutingOutcome.FAILED_TIMEOUT.mustTrackExecutionRow()).isTrue();
        // Explicit reject / blocked / skip → no row to track.
        assertThat(RoutingOutcome.FAILED_BROKER_REJECT.mustTrackExecutionRow()).isFalse();
        assertThat(RoutingOutcome.FAILED_READ_ONLY.mustTrackExecutionRow()).isFalse();
        assertThat(RoutingOutcome.SKIPPED_DUPLICATE.mustTrackExecutionRow()).isFalse();
        assertThat(RoutingOutcome.SKIPPED_ENTRY_IN_FLIGHT.mustTrackExecutionRow()).isFalse();
    }
}
