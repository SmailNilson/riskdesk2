package com.riskdesk.infrastructure.marketdata.ibkr;

import com.riskdesk.domain.marketdata.model.TickAggregation;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.OrderFlowProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static com.riskdesk.infrastructure.marketdata.ibkr.TickByTickAggregator.TickClassification.BUY;
import static com.riskdesk.infrastructure.marketdata.ibkr.TickByTickAggregator.TickClassification.SELL;
import static com.riskdesk.infrastructure.marketdata.ibkr.TickByTickAggregator.TickClassification.UNCLASSIFIED;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * L2 — quote-rule (Lee-Ready) classification, the trade-to-trade tick-rule fallback used when
 * Lee-Ready cannot classify, and the REAL_TICKS vs REAL_TICKS_TICKRULE provenance stamping.
 */
class IbkrTickDataAdapterTest {

    // -------------------------------------------------------------------------
    // Tick rule (static)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Quote rule (static) — pins that classification against a BBO is never circular:
    // a trade exactly at the BBO midpoint must stay UNCLASSIFIED by the quote rule
    // (and degrade to the explicitly-flagged tick rule), never silently become BUY/SELL.
    // -------------------------------------------------------------------------

    @Test
    void tradeExactlyAtBboMidpointIsUnclassifiedByQuoteRule() {
        // bid = price - 1 tick, ask = price + 1 tick (the signature a synthesized or
        // post-trade-widened book shows): the quote rule has no information here.
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyTrade(23000.25, 23000.00, 23000.50));
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyTrade(64.42, 64.41, 64.43));
    }

    @Test
    void missingOrOneSidedBboIsUnclassified() {
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyTrade(100.0, 0, 0));
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyTrade(100.0, 99.75, 0));
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyTrade(100.0, 0, 100.25));
    }

    @Test
    void invertedBookIsUnclassified() {
        assertEquals(UNCLASSIFIED, IbkrTickDataAdapter.classifyTrade(100.0, 100.50, 100.0));
    }

    @Test
    void tradeAtOrThroughAskIsBuyAtOrThroughBidIsSell() {
        assertEquals(BUY, IbkrTickDataAdapter.classifyTrade(100.50, 100.25, 100.50));
        assertEquals(BUY, IbkrTickDataAdapter.classifyTrade(100.75, 100.25, 100.50));
        assertEquals(SELL, IbkrTickDataAdapter.classifyTrade(100.25, 100.25, 100.50));
        assertEquals(SELL, IbkrTickDataAdapter.classifyTrade(100.00, 100.25, 100.50));
    }

    @Test
    void insideSpreadClassifiesByMidpointProximity() {
        assertEquals(BUY, IbkrTickDataAdapter.classifyTrade(100.40, 100.00, 100.50));
        assertEquals(SELL, IbkrTickDataAdapter.classifyTrade(100.10, 100.00, 100.50));
    }

    // -------------------------------------------------------------------------
    // Tick resolution + provenance stamping (instance)
    // -------------------------------------------------------------------------

    private IbkrFootprintAdapter footprint;

    private IbkrTickDataAdapter newAdapter(boolean tickRuleFallbackEnabled) {
        OrderFlowProperties props = new OrderFlowProperties();
        props.getTickByTick().setTickRuleFallbackEnabled(tickRuleFallbackEnabled);
        footprint = mock(IbkrFootprintAdapter.class);
        return new IbkrTickDataAdapter(footprint, mock(IbkrTickBarAdapter.class),
            mock(IbkrBigPrintAdapter.class), props);
    }

    @Test
    void midpointTradeResolvesViaTickRuleAndDegradesWindowSource() {
        IbkrTickDataAdapter adapter = newAdapter(true);
        Instant t = Instant.now();

        // Boot tick: no BBO and no prior trade — honestly UNCLASSIFIED, dropped, not counted.
        IbkrTickDataAdapter.TickResolution boot = adapter.onTickByTickTrade(
            Instrument.MNQ, 23000.00, 1, IbkrTickDataAdapter.classifyTrade(23000.00, 0, 0), t);
        assertEquals(UNCLASSIFIED, boot.classification());
        assertFalse(boot.tickRule());
        assertEquals(0, adapter.classifiedTicksReceived());

        // Trade exactly at the BBO midpoint: quote rule abstains, tick rule resolves the uptick
        // — and the resolution says so, so the diagnostic log can report the consumed class.
        TickByTickAggregator.TickClassification quoteClass =
            IbkrTickDataAdapter.classifyTrade(23000.25, 23000.00, 23000.50);
        assertEquals(UNCLASSIFIED, quoteClass);
        IbkrTickDataAdapter.TickResolution r = adapter.onTickByTickTrade(
            Instrument.MNQ, 23000.25, 2, quoteClass, t.plusMillis(100));
        assertEquals(BUY, r.classification());
        assertTrue(r.tickRule());
        assertEquals(1, adapter.classifiedTicksReceived());

        // Provenance honesty: a window whose volume is tick-rule-classified must NOT be
        // stamped REAL_TICKS — consumers see the reduced-confidence REAL_TICKS_TICKRULE.
        TickAggregation agg = adapter.currentAggregation(Instrument.MNQ).orElseThrow();
        assertEquals(TickAggregation.SOURCE_REAL_TICKS_TICKRULE, agg.source());
        assertEquals(2, agg.buyVolume());
    }

    @Test
    void quoteClassifiedVolumeKeepsRealTicksSource() {
        IbkrTickDataAdapter adapter = newAdapter(true);
        Instant t = Instant.now();

        // Quote-classified BUY at the ask (10 contracts) dominates...
        adapter.onTickByTickTrade(Instrument.MNQ, 23000.50, 10,
            IbkrTickDataAdapter.classifyTrade(23000.50, 23000.25, 23000.50), t);
        // ...one midpoint tick-rule tick (2 contracts) does not degrade the window.
        adapter.onTickByTickTrade(Instrument.MNQ, 23000.75, 2,
            IbkrTickDataAdapter.classifyTrade(23000.75, 23000.50, 23001.00), t.plusMillis(100));

        TickAggregation agg = adapter.currentAggregation(Instrument.MNQ).orElseThrow();
        assertEquals(TickAggregation.SOURCE_REAL_TICKS, agg.source());
        assertEquals(12, agg.buyVolume());
    }

    @Test
    void fallbackDisabledDropsMidpointTradeAsUnclassified() {
        IbkrTickDataAdapter adapter = newAdapter(false);
        Instant t = Instant.now();

        adapter.onTickByTickTrade(Instrument.MNQ, 23000.50, 5,
            IbkrTickDataAdapter.classifyTrade(23000.50, 23000.25, 23000.50), t);
        assertEquals(1, adapter.classifiedTicksReceived());

        IbkrTickDataAdapter.TickResolution r = adapter.onTickByTickTrade(Instrument.MNQ, 23000.75, 2,
            IbkrTickDataAdapter.classifyTrade(23000.75, 23000.50, 23001.00), t.plusMillis(100));
        assertEquals(UNCLASSIFIED, r.classification());
        assertFalse(r.tickRule());
        assertEquals(1, adapter.classifiedTicksReceived()); // unchanged — tick dropped
    }

    @Test
    void unclassifiedResolutionIsNotRoutedToFootprint() {
        IbkrTickDataAdapter adapter = newAdapter(true);
        Instant t = Instant.now();

        adapter.onTickByTickTrade(Instrument.MCL, 64.42, 1,
            IbkrTickDataAdapter.classifyTrade(64.42, 0, 0), t); // no BBO, no history → dropped
        verify(footprint, never()).onTick(any(), anyDouble(), anyLong(), anyString(), any());

        adapter.onTickByTickTrade(Instrument.MCL, 64.43, 1,
            IbkrTickDataAdapter.classifyTrade(64.43, 0, 0), t.plusMillis(50)); // tick-rule uptick
        verify(footprint).onTick(Instrument.MCL, 64.43, 1, "BUY", t.plusMillis(50));
    }
}
