package com.riskdesk.infrastructure.marketdata.ibkr;

import org.junit.jupiter.api.Test;

import static com.riskdesk.infrastructure.marketdata.ibkr.TickByTickAggregator.TickClassification.BUY;
import static com.riskdesk.infrastructure.marketdata.ibkr.TickByTickAggregator.TickClassification.SELL;
import static com.riskdesk.infrastructure.marketdata.ibkr.TickByTickAggregator.TickClassification.UNCLASSIFIED;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * L2 — the trade-to-trade tick-rule fallback used when Lee-Ready cannot classify
 * (no fresh BBO/quote). uptick=BUY, downtick=SELL, flat carries the prior direction.
 */
class IbkrTickDataAdapterTest {

    @Test
    void uptickIsBuy() {
        assertEquals(BUY, IbkrTickDataAdapter.classifyByTickRule(101.0, 100.0, null));
    }

    @Test
    void downtickIsSell() {
        assertEquals(SELL, IbkrTickDataAdapter.classifyByTickRule(99.0, 100.0, null));
    }

    @Test
    void flatTickCarriesPriorDirection() {
        assertEquals(BUY, IbkrTickDataAdapter.classifyByTickRule(100.0, 100.0, BUY));
        assertEquals(SELL, IbkrTickDataAdapter.classifyByTickRule(100.0, 100.0, SELL));
    }

    @Test
    void noPreviousPriceIsUnclassified() {
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyByTickRule(100.0, null, null));
    }

    @Test
    void flatTickWithNoPriorDirectionIsUnclassified() {
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyByTickRule(100.0, 100.0, null));
    }

    @Test
    void nonPositivePreviousPriceIsUnclassified() {
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyByTickRule(100.0, 0.0, null));
        assertEquals(BUY, IbkrTickDataAdapter.classifyByTickRule(100.0, 0.0, BUY)); // carries prior
    }
}
