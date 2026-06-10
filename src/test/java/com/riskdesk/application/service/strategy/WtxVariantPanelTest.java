package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.domain.engine.strategy.wtx.WtxEnrichmentSnapshot;
import com.riskdesk.domain.engine.strategy.wtx.WtxParamOverride;
import com.riskdesk.domain.engine.strategy.wtx.WtxPosition;
import com.riskdesk.domain.engine.strategy.wtx.WtxStrategyState;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxParamOverridePort;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxSignalHistoryPort;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.domain.marketdata.event.CandleClosed;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The {@code top-train-Z35} variant panel must run IN PARALLEL with the legacy panel on the same
 * closed candle: its own state under the panel key ({@code 10m-z35}), a base config seeded from
 * the preset, no leakage into the legacy panel's config, and coverage by forceCloseAll.
 */
class WtxVariantPanelTest {

    private static final WtxStrategyProperties.Variant Z35 =
            new WtxStrategyProperties.Variant("top-train-Z35", "MNQ", "10m", "top-train-z35", "10m-z35");

    /** In-memory state port capturing saves per (instrument|timeframe) key. */
    private static final class FakeStatePort implements WtxStrategyStatePort {
        final Map<String, WtxStrategyState> store = new HashMap<>();
        @Override public Optional<WtxStrategyState> load(String instrument, String timeframe) {
            return Optional.ofNullable(store.get(instrument + "|" + timeframe));
        }
        @Override public void save(WtxStrategyState state) {
            store.put(state.instrument() + "|" + state.timeframe(), state);
        }
    }

    private static final class FakeOverridePort implements WtxParamOverridePort {
        final Map<String, WtxParamOverride> store = new HashMap<>();
        @Override public WtxParamOverride load(String instrument, String timeframe) {
            return store.getOrDefault(instrument + "|" + timeframe, WtxParamOverride.NONE);
        }
        @Override public void save(String instrument, String timeframe, WtxParamOverride override) {
            store.put(instrument + "|" + timeframe, override == null ? WtxParamOverride.NONE : override);
        }
    }

    private final FakeStatePort statePort = new FakeStatePort();
    private final FakeOverridePort overridePort = new FakeOverridePort();

    /** Flat 10m candles — enough bars for the WaveTrend warm-up, no cross → no signal, state still saved. */
    private static List<Candle> flatCandles(int n) {
        List<Candle> out = new ArrayList<>();
        Instant t = Instant.parse("2026-06-01T10:00:00Z");
        for (int i = 0; i < n; i++) {
            BigDecimal p = BigDecimal.valueOf(20_000);
            out.add(new Candle(Instrument.MNQ, "10m", t.plusSeconds(600L * i),
                    p, p.add(BigDecimal.ONE), p.subtract(BigDecimal.ONE), p, 10));
        }
        // Service expects DESC (newest first) from findRecentCandles.
        java.util.Collections.reverse(out);
        return out;
    }

    @SuppressWarnings("unchecked")
    private WtxStrategyService service(List<WtxStrategyProperties.Variant> variants) {
        WtxSignalHistoryPort historyPort = mock(WtxSignalHistoryPort.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        WtxEnrichmentBuilder enrichmentBuilder = mock(WtxEnrichmentBuilder.class);
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WtxStrategyProperties properties = mock(WtxStrategyProperties.class);
        ObjectProvider<WtxExecutionBridge> bridgeProvider = mock(ObjectProvider.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        WtxClosePnlSettler settler = mock(WtxClosePnlSettler.class);
        WtxPositionReconciler reconciler = mock(WtxPositionReconciler.class);

        lenient().when(properties.toConfig()).thenReturn(WtxConfig.defaults());
        lenient().when(properties.getInitialEquity()).thenReturn(BigDecimal.valueOf(10_000));
        lenient().when(properties.getVariants()).thenReturn(variants);
        lenient().when(historyPort.findRecent(anyString(), anyString(), anyInt())).thenReturn(List.of());
        lenient().when(candlePort.findRecentCandles(any(), anyString(), anyInt()))
                .thenAnswer(inv -> "10m".equals(inv.getArgument(1)) ? flatCandles(120) : List.of());
        lenient().when(enrichmentBuilder.build(anyString(), anyString()))
                .thenReturn(WtxEnrichmentSnapshot.empty());
        lenient().when(settler.settle(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(reconciler.reconcile(any(), any(), any(), any()))
                .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(bridgeProvider.getIfAvailable()).thenReturn(null);

        return new WtxStrategyService(statePort, historyPort, candlePort, enrichmentBuilder, ws,
                properties, bridgeProvider, publisher, settler, reconciler, overridePort);
    }

    @Test
    void candleClose_runsLegacyAndVariantPanels_underTheirOwnKeys() {
        WtxStrategyService svc = service(List.of(Z35));

        svc.onCandleClosed(new CandleClosed("MNQ", "10m", Instant.parse("2026-06-01T10:00:00Z")));

        assertTrue(statePort.store.containsKey("MNQ|10m"), "legacy panel state must be saved");
        assertTrue(statePort.store.containsKey("MNQ|10m-z35"), "variant panel state must be saved");
        assertEquals("10m-z35", statePort.store.get("MNQ|10m-z35").timeframe());
    }

    @Test
    void variantEffectiveConfig_isSeededFromPreset_withoutAnyStoredOverride() {
        WtxStrategyService svc = service(List.of(Z35));

        WtxConfig variant = svc.effectiveConfig("MNQ", "10m-z35");
        assertEquals(5, variant.n1());
        assertEquals(14, variant.n2());
        assertEquals(2, variant.signalPeriod());
        assertEquals(0, new BigDecimal("4.0").compareTo(variant.slAtrMult()));
        assertEquals(0, BigDecimal.valueOf(35).compareTo(variant.nsc()));
        assertFalse(variant.useCompra1());
        assertFalse(variant.useVenta1());

        // The legacy panel keeps the global config — the variant must not leak.
        WtxConfig legacy = svc.effectiveConfig("MNQ", "10m");
        assertEquals(WtxConfig.defaults().n1(), legacy.n1());
        assertEquals(0, WtxConfig.defaults().nsc().compareTo(legacy.nsc()));
        assertTrue(legacy.useCompra1() == WtxConfig.defaults().useCompra1());
    }

    @Test
    void variantPanel_acceptsItsOwnStoredOverride_onTopOfThePreset() {
        WtxStrategyService svc = service(List.of(Z35));
        svc.updateSlAtrMult("MNQ", "10m-z35", new BigDecimal("3.5"));

        WtxConfig variant = svc.effectiveConfig("MNQ", "10m-z35");
        assertEquals(0, new BigDecimal("3.5").compareTo(variant.slAtrMult()), "stored override wins");
        assertEquals(5, variant.n1(), "preset fields without override stay");
    }

    @Test
    void candleForAnotherInstrument_doesNotTouchTheVariant() {
        WtxStrategyService svc = service(List.of(Z35));

        svc.onCandleClosed(new CandleClosed("MCL", "10m", Instant.parse("2026-06-01T10:00:00Z")));

        assertNull(statePort.store.get("MNQ|10m-z35"));
    }

    @Test
    void forceCloseAll_flattensAnOpenVariantPosition() {
        WtxStrategyService svc = service(List.of(Z35));
        WtxStrategyState open = WtxStrategyState
                .initial("MNQ", "10m-z35", BigDecimal.valueOf(10_000))
                .withPosition(WtxPosition.LONG, BigDecimal.valueOf(19_990), BigDecimal.ONE, BigDecimal.TEN);
        statePort.save(open);

        svc.forceCloseAll("test");

        WtxStrategyState closed = statePort.store.get("MNQ|10m-z35");
        assertEquals(WtxPosition.FLAT, closed.currentPosition(), "variant position must be force-closed");
    }
}
