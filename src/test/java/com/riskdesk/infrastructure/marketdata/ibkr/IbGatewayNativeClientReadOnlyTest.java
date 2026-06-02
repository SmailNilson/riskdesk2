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

    // ---- documents the current (dead) default so the Phase 0.2 flip is intentional ----------

    @Test
    void nativeReadOnlyConfigDefaultsTrueButIsAdvisoryOnly() {
        // The flag defaults TRUE everywhere (application.properties, docker-compose.release.yml) but
        // is NOT yet enforced as a submission gate — see docs/PLAN_AUTO_IBKR_EXECUTION.md Phase 0.2.
        assertThat(new IbkrProperties().isNativeReadOnly()).isTrue();
    }
}
