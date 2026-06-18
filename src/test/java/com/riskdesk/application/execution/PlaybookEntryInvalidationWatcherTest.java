package com.riskdesk.application.execution;

import com.riskdesk.application.service.IbkrOrderService;
import com.riskdesk.application.service.MarketDataService;
import com.riskdesk.domain.execution.port.TradeExecutionRepositoryPort;
import com.riskdesk.domain.model.ExecutionStatus;
import com.riskdesk.domain.model.ExecutionTriggerSource;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.TradeExecutionRecord;
import com.riskdesk.domain.playbook.automation.PlaybookDecision;
import com.riskdesk.domain.playbook.automation.PlaybookRoutingOutcome;
import com.riskdesk.domain.playbook.automation.port.PlaybookDecisionRepositoryPort;
import com.riskdesk.infrastructure.marketdata.ibkr.IbkrProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The live-parity invalidation watcher: it cancels a resting PLAYBOOK_AUTO STOP entry when price
 * breaches the decision's invalidation level before the entry fills, mirroring the paper sim's
 * {@code touchesInvalidation} CANCELLED behaviour — and stays its hand on a fill that raced in,
 * on stale prices, on filled rows, and when IBKR is disabled.
 */
class PlaybookEntryInvalidationWatcherTest {

    private static final Instant TS = Instant.parse("2026-06-18T14:00:00Z");
    private static final String KEY = "playbook:MNQ:10m:1:SHORT:OB:demand_zone:CONF";

    private TradeExecutionRepositoryPort repo;
    private PlaybookDecisionRepositoryPort decisions;
    private IbkrOrderService orderService;
    private IbkrProperties props;
    private MarketDataService marketData;
    private PlaybookEntryInvalidationWatcher watcher;

    @BeforeEach
    void setUp() {
        repo = mock(TradeExecutionRepositoryPort.class);
        decisions = mock(PlaybookDecisionRepositoryPort.class);
        orderService = mock(IbkrOrderService.class);
        marketData = mock(MarketDataService.class);
        props = new IbkrProperties();
        props.setEnabled(true);

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<MarketDataService> mdp =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        lenient().when(mdp.getIfAvailable()).thenReturn(marketData);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<org.springframework.messaging.simp.SimpMessagingTemplate> msgp =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        lenient().when(msgp.getIfAvailable()).thenReturn(null);

        watcher = new PlaybookEntryInvalidationWatcher(repo, decisions, orderService, props, mdp, msgp, true);

        lenient().when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private TradeExecutionRecord restingShort() {
        TradeExecutionRecord r = new TradeExecutionRecord();
        r.setId(7L);
        r.setExecutionKey(KEY + ":MNQ_10M_CONFIRMATION");
        r.setReviewAlertKey(KEY);
        r.setTriggerSource(ExecutionTriggerSource.PLAYBOOK_AUTO);
        r.setStatus(ExecutionStatus.ENTRY_SUBMITTED);
        r.setInstrument("MNQ");
        r.setTimeframe("10m");
        r.setAction("SHORT");
        r.setQuantity(1);
        r.setIbkrOrderId(4242);
        r.setNormalizedEntryPrice(new BigDecimal("30413.50"));
        return r;
    }

    /** STOP-entry decision with the given direction + invalidation level. */
    private PlaybookDecision stopDecision(String direction, String invalidationPrice) {
        return new PlaybookDecision(
            1L, KEY, "MNQ", "10m", "MNQ:10m:1:" + direction + ":OB:demand_zone", "OB", "demand_zone",
            direction, 5, "CONFIRMATION", new BigDecimal("30413.50"), new BigDecimal("30478.77"),
            new BigDecimal("30315.60"), null, new BigDecimal("1.5"), new BigDecimal("0.01"), false,
            "LIVE_IBKR", TS, TS, TS, PlaybookRoutingOutcome.ROUTED, null, 7L,
            PlaybookDecision.ENTRY_TYPE_STOP, new BigDecimal(invalidationPrice));
    }

    private void liveQuote(String price) {
        // a fresh tick — the watcher rejects quotes older than MAX_PRICE_AGE_SECONDS
        when(marketData.currentPrice(Instrument.MNQ))
            .thenReturn(new MarketDataService.StoredPrice(new BigDecimal(price), Instant.now(), "LIVE_IBKR"));
    }

    private void onlyResting(TradeExecutionRecord row) {
        when(repo.findByTriggerSourceAndStatus(
            ExecutionTriggerSource.PLAYBOOK_AUTO, ExecutionStatus.ENTRY_SUBMITTED))
            .thenReturn(List.of(row));
    }

    @Test
    void shortBreach_cancelsRestingOrderAndMarksCancelled() {
        TradeExecutionRecord row = restingShort();
        onlyResting(row);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(stopDecision("SHORT", "30543.00")));
        when(repo.findById(7L)).thenReturn(Optional.of(row));
        liveQuote("30545.00"); // rose through invalidation (30543) before the sell-stop triggered

        watcher.cancelInvalidatedEntries();

        verify(orderService).cancelOrder(4242);
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
        assertThat(row.getStatusReason()).contains("invalidated");
        verify(repo).save(row);
    }

    @Test
    void longBreach_cancelsWhenPriceFallsThroughLevel() {
        TradeExecutionRecord row = restingShort();
        row.setAction("LONG");
        onlyResting(row);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(stopDecision("LONG", "30350.00")));
        when(repo.findById(7L)).thenReturn(Optional.of(row));
        liveQuote("30349.00"); // fell through invalidation (30350) before the buy-stop triggered

        watcher.cancelInvalidatedEntries();

        verify(orderService).cancelOrder(4242);
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.CANCELLED);
    }

    @Test
    void noBreach_leavesRestingOrderAlone() {
        TradeExecutionRecord row = restingShort();
        onlyResting(row);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(stopDecision("SHORT", "30543.00")));
        liveQuote("30500.00"); // still below invalidation — zone not (yet) broken

        watcher.cancelInvalidatedEntries();

        verify(orderService, never()).cancelOrder(anyInt());
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
    }

    @Test
    void racedToFill_doesNotCancelLivePosition() {
        TradeExecutionRecord row = restingShort();
        onlyResting(row);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(stopDecision("SHORT", "30543.00")));
        // between the price read and the cancel, the stop triggered and filled → fill tracker set ACTIVE
        TradeExecutionRecord filled = restingShort();
        filled.setStatus(ExecutionStatus.ACTIVE);
        filled.setFilledQuantity(BigDecimal.ONE);
        when(repo.findById(7L)).thenReturn(Optional.of(filled));
        liveQuote("30545.00");

        watcher.cancelInvalidatedEntries();

        verify(orderService).cancelOrder(4242);          // we still attempt the cancel
        assertThat(filled.getStatus()).isEqualTo(ExecutionStatus.ACTIVE); // but never force CANCELLED
        verify(repo, never()).save(any());
    }

    @Test
    void alreadyFilledRow_isSkipped() {
        TradeExecutionRecord row = restingShort();
        row.setFilledQuantity(BigDecimal.ONE);
        onlyResting(row);
        liveQuote("30545.00");

        watcher.cancelInvalidatedEntries();

        verify(orderService, never()).cancelOrder(anyInt());
    }

    @Test
    void noBrokerOrderId_isSkipped() {
        TradeExecutionRecord row = restingShort();
        row.setIbkrOrderId(null);
        onlyResting(row);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(stopDecision("SHORT", "30543.00")));
        liveQuote("30545.00");

        watcher.cancelInvalidatedEntries();

        verify(orderService, never()).cancelOrder(anyInt());
    }

    @Test
    void nonLivePriceSource_neverCancels() {
        TradeExecutionRecord row = restingShort();
        onlyResting(row);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(stopDecision("SHORT", "30543.00")));
        when(marketData.currentPrice(Instrument.MNQ))
            .thenReturn(new MarketDataService.StoredPrice(new BigDecimal("30545.00"), TS, "FALLBACK_DB"));

        watcher.cancelInvalidatedEntries();

        verify(orderService, never()).cancelOrder(anyInt());
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
    }

    @Test
    void stalePriceSource_neverCancels() {
        TradeExecutionRecord row = restingShort();
        onlyResting(row);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(stopDecision("SHORT", "30543.00")));
        // LIVE source but the quote is older than MAX_PRICE_AGE_SECONDS — the market may have moved on
        when(marketData.currentPrice(Instrument.MNQ)).thenReturn(new MarketDataService.StoredPrice(
            new BigDecimal("30545.00"), Instant.now().minusSeconds(30), "LIVE_IBKR"));

        watcher.cancelInvalidatedEntries();

        verify(orderService, never()).cancelOrder(anyInt());
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED);
    }

    @Test
    void failedCancel_doesNotMarkCancelled_andWillRetry() {
        TradeExecutionRecord row = restingShort();
        onlyResting(row);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(stopDecision("SHORT", "30543.00")));
        liveQuote("30545.00");
        // gateway hiccup / already-filled reject — the cancel did NOT go through
        when(orderService.cancelOrder(4242)).thenThrow(new RuntimeException("IBKR 161: order already filled"));

        watcher.cancelInvalidatedEntries();

        verify(orderService).cancelOrder(4242);
        assertThat(row.getStatus()).isEqualTo(ExecutionStatus.ENTRY_SUBMITTED); // NOT CANCELLED — broker may still hold it
        verify(repo, never()).save(any());
        verify(repo, never()).findById(anyLong()); // returned before the re-read/terminal write
    }

    @Test
    void rowNotFoundOnReread_doesNotWriteCancelledBlind() {
        TradeExecutionRecord row = restingShort();
        onlyResting(row);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(stopDecision("SHORT", "30543.00")));
        when(repo.findById(7L)).thenReturn(Optional.empty()); // can't confirm current state
        liveQuote("30545.00");

        watcher.cancelInvalidatedEntries();

        verify(orderService).cancelOrder(4242);
        verify(repo, never()).save(any());
    }

    @Test
    void unrecognizedAction_isSkipped() {
        TradeExecutionRecord row = restingShort();
        row.setAction("FLATTEN"); // neither LONG/BUY nor SHORT/SELL
        onlyResting(row);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(stopDecision("SHORT", "30543.00")));
        liveQuote("30545.00");

        watcher.cancelInvalidatedEntries();

        verify(orderService, never()).cancelOrder(anyInt());
    }

    @Test
    void legacyLimitDecision_isSkipped() {
        TradeExecutionRecord row = restingShort();
        onlyResting(row);
        // a non-STOP decision (entryType null → LIMIT) carries no invalidation level
        PlaybookDecision limit = stopDecision("SHORT", "30543.00").withRouting(PlaybookRoutingOutcome.ROUTED, null, 7L);
        when(decisions.findByDecisionKey(KEY)).thenReturn(Optional.of(new PlaybookDecision(
            1L, KEY, "MNQ", "10m", "id", "OB", "z", "SHORT", 5, "v",
            new BigDecimal("30413.50"), new BigDecimal("30478.77"), new BigDecimal("30315.60"), null,
            new BigDecimal("1.5"), new BigDecimal("0.01"), false, "LIVE_IBKR", TS, TS, TS,
            PlaybookRoutingOutcome.ROUTED, null, 7L)));
        liveQuote("30545.00");

        watcher.cancelInvalidatedEntries();

        verify(orderService, never()).cancelOrder(anyInt());
    }

    @Test
    void ibkrDisabled_isNoOp() {
        props.setEnabled(false);

        watcher.cancelInvalidatedEntries();

        verify(repo, never()).findByTriggerSourceAndStatus(any(), any());
        verify(orderService, never()).cancelOrder(anyInt());
    }

    @Test
    void watcherDisabled_isNoOp() {
        PlaybookEntryInvalidationWatcher off = new PlaybookEntryInvalidationWatcher(
            repo, decisions, orderService, props,
            providerOf(marketData), providerOfNull(), false);

        off.cancelInvalidatedEntries();

        verify(repo, never()).findByTriggerSourceAndStatus(any(), any());
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOf(T value) {
        org.springframework.beans.factory.ObjectProvider<T> p =
            mock(org.springframework.beans.factory.ObjectProvider.class);
        lenient().when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static <T> org.springframework.beans.factory.ObjectProvider<T> providerOfNull() {
        return mock(org.springframework.beans.factory.ObjectProvider.class);
    }
}
