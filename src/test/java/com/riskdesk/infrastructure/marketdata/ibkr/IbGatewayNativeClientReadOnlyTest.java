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

    @Test
    void outsideRthDefaultsOn() {
        // RiskDesk trades ~24h CME Globex futures. outsideRth MUST default true so a DAY order placed
        // outside the day session is not held Inactive ("IBKR order Inactive") — which would block overnight
        // entries AND overnight stops / closes. placeEntryOrder applies properties.isOutsideRth() to the Order;
        // that submission is integration-level (needs a live socket), so this default-on invariant is the
        // unit-level guard, mirroring killSwitchDefaultsOff above.
        assertThat(new IbkrProperties().isOutsideRth()).isTrue();
    }

    // ---- cancel error-code classification: warnings must NOT fail a cancel -------------------
    // cancelOrderById only fails a cancel on a GENUINE order-error code; IBKR informational / warning
    // messages on the order channel (399 order warning, farm-connection notices, the 2100-band) must be
    // ignored so a cancellable resting order is not wrongly refused ("Annulation refusée"). The cancel
    // submission itself is integration-level (live socket), so this classifier is the unit-level guard.

    @Test
    void informationalCancelCodes_areNotFailures() {
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(399)).isTrue();   // order-message warning
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(2104)).isTrue();  // market-data farm OK
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(2106)).isTrue();  // HMDS farm OK
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(2158)).isTrue();  // sec-def farm OK
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(366)).isTrue();   // no historical data noise
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(2100)).isTrue();  // warning band lower bound
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(2169)).isTrue();  // warning band upper bound
    }

    @Test
    void genuineCancelFailureCodes_areNotInformational() {
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(202)).isFalse();   // success, handled separately
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(161)).isFalse();   // not in a cancellable state
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(135)).isFalse();   // can't find order id
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(10147)).isFalse(); // orderId not found
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(10148)).isFalse(); // cannot be cancelled
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(201)).isFalse();   // order rejected
        assertThat(IbGatewayNativeClient.isInformationalCancelCode(321)).isFalse();   // read-only / validation
    }

    // NOTE: the kill-switch gate (properties.isNativeReadOnly() -> reject) lives in placeLimitOrder
    // AFTER ensureConnected() and the existing-order idempotency lookup, so a retry/recovery for an
    // already-live orderRef still reuses the broker order id instead of being rejected (Codex review,
    // PR #374). Reaching that gate needs a live gateway connection, so it is exercised at integration
    // level rather than unit-tested here (a unit test would have to open a real socket). The
    // default-off invariant above is the unit-level guard.
}
