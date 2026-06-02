package com.riskdesk.infrastructure.marketdata.ibkr;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 0.1 — IBKR Read-Only detection. The TWS / IB Gateway "Read-Only API" box being checked is
 * the #1 silent cause of "entries not sent" (it rejects every order with error 321). These tests
 * lock down the detection helper and the stateful recorder so the condition can never fail silently.
 */
class IbGatewayNativeClientReadOnlyTest {

    private IbGatewayNativeClient newClient() {
        return new IbGatewayNativeClient(new IbkrProperties());
    }

    // ---- looksLikeReadOnly: text detection (the risky logic) --------------------------------

    @Test
    void detectsCanonicalReadOnlyMessage() {
        assertThat(IbGatewayNativeClient.looksLikeReadOnly(
            "Error validating request:-'xx' : cause - The API interface is currently in Read-Only mode."))
            .isTrue();
    }

    @Test
    void detectsReadOnlyCaseInsensitiveAndWithoutHyphen() {
        assertThat(IbGatewayNativeClient.looksLikeReadOnly("READ-ONLY")).isTrue();
        assertThat(IbGatewayNativeClient.looksLikeReadOnly("the api is in read only mode")).isTrue();
        assertThat(IbGatewayNativeClient.looksLikeReadOnly("Read-Only API enabled")).isTrue();
    }

    @Test
    void ignoresUnrelatedAndNullMessages() {
        assertThat(IbGatewayNativeClient.looksLikeReadOnly(null)).isFalse();
        assertThat(IbGatewayNativeClient.looksLikeReadOnly("")).isFalse();
        assertThat(IbGatewayNativeClient.looksLikeReadOnly("Order rejected: insufficient margin")).isFalse();
        assertThat(IbGatewayNativeClient.looksLikeReadOnly("ready to trade")).isFalse();
    }

    // ---- recordBrokerMessageForReadOnly: stateful flag -------------------------------------

    @Test
    void recordsAndExposesReadOnlyState() {
        IbGatewayNativeClient client = newClient();
        assertThat(client.isBrokerReadOnly()).isFalse();
        assertThat(client.brokerReadOnlyReason()).isNull();

        boolean detected = client.recordBrokerMessageForReadOnly(
            42, 321, "The API interface is currently in Read-Only mode.");

        assertThat(detected).isTrue();
        assertThat(client.isBrokerReadOnly()).isTrue();
        assertThat(client.brokerReadOnlyReason())
            .contains("READ-ONLY")
            .contains("321");
    }

    @Test
    void nonReadOnlyMessageLeavesStateClear() {
        IbGatewayNativeClient client = newClient();

        boolean detected = client.recordBrokerMessageForReadOnly(7, 201, "Insufficient margin");

        assertThat(detected).isFalse();
        assertThat(client.isBrokerReadOnly()).isFalse();
        assertThat(client.brokerReadOnlyReason()).isNull();
    }

    @Test
    void readOnlyDetectionToleratesSystemMessageId() {
        IbGatewayNativeClient client = newClient();

        // id <= 0 (system-level message, not tied to an order) is still detected and flagged.
        boolean detected = client.recordBrokerMessageForReadOnly(-1, 321, "currently in Read-Only mode");

        assertThat(detected).isTrue();
        assertThat(client.isBrokerReadOnly()).isTrue();
    }

    // ---- Phase 0.2: native-read-only kill-switch (enforced submission gate) -----------------

    @Test
    void killSwitchDefaultsOff() {
        // Phase 0.2 flipped the default to false (trading allowed) across all configs, now that the
        // flag is enforced as a submission gate. See docs/PLAN_AUTO_IBKR_EXECUTION.md.
        assertThat(new IbkrProperties().isNativeReadOnly()).isFalse();
    }

    // NOTE: the kill-switch gate (properties.isNativeReadOnly() -> reject) lives in placeLimitOrder
    // AFTER ensureConnected() and the existing-order idempotency lookup, so a retry/recovery for an
    // already-live orderRef still reuses the broker order id instead of being rejected (Codex review,
    // PR #374). Reaching that gate needs a live gateway connection, so it is exercised at integration
    // level rather than unit-tested here (a unit test would have to open a real socket). The
    // default-off invariant above is the unit-level guard.
}
