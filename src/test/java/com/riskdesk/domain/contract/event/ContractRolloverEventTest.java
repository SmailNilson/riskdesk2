package com.riskdesk.domain.contract.event;

import com.riskdesk.domain.model.Instrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class ContractRolloverEventTest {

    @Test
    void recordFieldsAreAccessible() {
        Instant now = Instant.now();
        ContractRolloverEvent event = new ContractRolloverEvent(
                Instrument.MCL, "202606", "202609", now);

        assertEquals(Instrument.MCL, event.instrument());
        assertEquals("202606", event.oldContractMonth());
        assertEquals("202609", event.newContractMonth());
        assertEquals(now, event.timestamp());
    }

    @Test
    void equalityBasedOnAllFields() {
        Instant now = Instant.now();
        ContractRolloverEvent a = new ContractRolloverEvent(Instrument.MGC, "202604", "202606", now);
        ContractRolloverEvent b = new ContractRolloverEvent(Instrument.MGC, "202604", "202606", now);
        assertEquals(a, b);
    }
}
