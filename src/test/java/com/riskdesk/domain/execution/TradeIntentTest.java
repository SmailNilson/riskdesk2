package com.riskdesk.domain.execution;

import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TradeIntentTest {

    private static final BigDecimal PX = new BigDecimal("100.25");
    private static final ExecutionTriggerSource SRC = ExecutionTriggerSource.WTX_AUTO;

    @Test
    void openFactorySetsKindAndFields() {
        TradeIntent i = TradeIntent.open("wtx:MNQ:5m:1:OPEN_LONG", SRC, Instrument.MNQ, "5m", Side.LONG, 2, PX, "DU123");
        assertThat(i.kind()).isEqualTo(IntentKind.OPEN);
        assertThat(i.side()).isEqualTo(Side.LONG);
        assertThat(i.quantity()).isEqualTo(2);
        assertThat(i.instrument()).isEqualTo(Instrument.MNQ);
        assertThat(i.limitPrice()).isEqualByComparingTo(PX);
        assertThat(i.brokerAccountId()).isEqualTo("DU123");
    }

    @Test
    void reverseAndCloseFactoriesSetKind() {
        assertThat(TradeIntent.reverse("k", SRC, Instrument.MNQ, "5m", Side.SHORT, 1, PX, null).kind())
            .isEqualTo(IntentKind.REVERSE);
        assertThat(TradeIntent.close("k", SRC, Instrument.MNQ, "5m", Side.LONG, 1, PX, null).kind())
            .isEqualTo(IntentKind.CLOSE);
    }

    @Test
    void flattenAllowsNullSide() {
        TradeIntent i = TradeIntent.flatten("k", SRC, Instrument.MNQ, "5m", 1, PX, null);
        assertThat(i.kind()).isEqualTo(IntentKind.FLATTEN);
        assertThat(i.side()).isNull();
    }

    @Test
    void nullBrokerAccountIsAllowed() {
        // the gateway resolves the default account when none is supplied
        assertThat(TradeIntent.open("k", SRC, Instrument.MNQ, "5m", Side.LONG, 1, PX, null).brokerAccountId()).isNull();
    }

    @Test
    void rejectsBlankIdempotencyKey() {
        assertThatThrownBy(() -> TradeIntent.open(" ", SRC, Instrument.MNQ, "5m", Side.LONG, 1, PX, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("idempotencyKey");
    }

    @Test
    void rejectsNonPositiveQuantity() {
        assertThatThrownBy(() -> TradeIntent.open("k", SRC, Instrument.MNQ, "5m", Side.LONG, 0, PX, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("quantity");
    }

    @Test
    void rejectsNonPositiveOrNullLimitPrice() {
        assertThatThrownBy(() -> TradeIntent.open("k", SRC, Instrument.MNQ, "5m", Side.LONG, 1, BigDecimal.ZERO, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limitPrice");
        assertThatThrownBy(() -> TradeIntent.open("k", SRC, Instrument.MNQ, "5m", Side.LONG, 1, null, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("limitPrice");
    }

    @Test
    void rejectsMissingSideForDirectionalKinds() {
        assertThatThrownBy(() -> new TradeIntent("k", SRC, Instrument.MNQ, "5m", IntentKind.OPEN, null, 1, PX, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("side");
        assertThatThrownBy(() -> new TradeIntent("k", SRC, Instrument.MNQ, "5m", IntentKind.CLOSE, null, 1, PX, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("side");
    }

    @Test
    void rejectsNullRequiredRefs() {
        assertThatThrownBy(() -> TradeIntent.open("k", null, Instrument.MNQ, "5m", Side.LONG, 1, PX, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("source");
        assertThatThrownBy(() -> TradeIntent.open("k", SRC, null, "5m", Side.LONG, 1, PX, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("instrument");
        assertThatThrownBy(() -> TradeIntent.open("k", SRC, Instrument.MNQ, " ", Side.LONG, 1, PX, null))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("timeframe");
    }
}
