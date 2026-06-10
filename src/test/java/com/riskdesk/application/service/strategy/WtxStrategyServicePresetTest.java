package com.riskdesk.application.service.strategy;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.wtx.WtxConfig;
import com.riskdesk.domain.engine.strategy.wtx.WtxParamOverride;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxParamOverridePort;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxSignalHistoryPort;
import com.riskdesk.domain.engine.strategy.wtx.port.WtxStrategyStatePort;
import com.riskdesk.infrastructure.config.WtxStrategyProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Applying the {@code top-train-Z35} preset must persist the full override set and the panel's
 * effective config must reflect it (periods, SL multiple AND the zone-entry fields). Later partial
 * edits (n1/n2/sig or SL from the panel) must NOT wipe the zone override.
 */
class WtxStrategyServicePresetTest {

    /** In-memory WtxParamOverridePort — save/load semantics without a DB. */
    private static final class FakeOverridePort implements WtxParamOverridePort {
        private final Map<String, WtxParamOverride> store = new HashMap<>();
        @Override public WtxParamOverride load(String instrument, String timeframe) {
            return store.getOrDefault(instrument + "|" + timeframe, WtxParamOverride.NONE);
        }
        @Override public void save(String instrument, String timeframe, WtxParamOverride override) {
            store.put(instrument + "|" + timeframe, override == null ? WtxParamOverride.NONE : override);
        }
    }

    private final FakeOverridePort overridePort = new FakeOverridePort();

    private WtxStrategyService service() {
        return service(WtxConfig.defaults());
    }

    @SuppressWarnings("unchecked")
    private WtxStrategyService service(WtxConfig globalConfig) {
        WtxStrategyStatePort statePort = mock(WtxStrategyStatePort.class);
        WtxSignalHistoryPort historyPort = mock(WtxSignalHistoryPort.class);
        CandleRepositoryPort candlePort = mock(CandleRepositoryPort.class);
        WtxEnrichmentBuilder enrichmentBuilder = mock(WtxEnrichmentBuilder.class);
        SimpMessagingTemplate ws = mock(SimpMessagingTemplate.class);
        WtxStrategyProperties properties = mock(WtxStrategyProperties.class);
        ObjectProvider<WtxExecutionBridge> bridgeProvider = mock(ObjectProvider.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        WtxClosePnlSettler settler = mock(WtxClosePnlSettler.class);
        WtxPositionReconciler reconciler = mock(WtxPositionReconciler.class);

        lenient().when(properties.toConfig()).thenReturn(globalConfig);
        lenient().when(properties.getInitialEquity()).thenReturn(BigDecimal.valueOf(10_000));
        lenient().when(statePort.load(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(historyPort.findRecent(anyString(), anyString(), anyInt())).thenReturn(List.of());
        lenient().when(candlePort.findRecentCandles(any(), anyString(), anyInt())).thenReturn(List.of());

        return new WtxStrategyService(statePort, historyPort, candlePort, enrichmentBuilder, ws,
                properties, bridgeProvider, publisher, settler, reconciler, overridePort);
    }

    @Test
    void applyPreset_persistsFullOverride_andEffectiveConfigReflectsIt() {
        WtxStrategyService svc = service();

        svc.applyPreset("MNQ", "10m", WtxParamOverride.TOP_TRAIN_Z35);

        assertEquals(WtxParamOverride.TOP_TRAIN_Z35, overridePort.load("MNQ", "10m"));
        WtxConfig eff = svc.effectiveConfig("MNQ", "10m");
        assertEquals(5, eff.n1());
        assertEquals(14, eff.n2());
        assertEquals(2, eff.signalPeriod());
        assertEquals(0, new BigDecimal("4.0").compareTo(eff.slAtrMult()));
        assertEquals(0, BigDecimal.valueOf(35).compareTo(eff.nsc()));
        assertEquals(0, BigDecimal.valueOf(-35).compareTo(eff.nsv()));
        assertEquals(false, eff.useCompra1());
        assertEquals(false, eff.useVenta1());
        // Non-overridden knobs keep their global values.
        assertEquals(WtxConfig.defaults().trailingMode(), eff.trailingMode());
    }

    @Test
    void laterIndicatorParamEdit_preservesTheZoneOverride() {
        WtxStrategyService svc = service();
        svc.applyPreset("MNQ", "10m", WtxParamOverride.TOP_TRAIN_Z35);

        svc.updateIndicatorParams("MNQ", "10m", 7, null, null);

        WtxParamOverride stored = overridePort.load("MNQ", "10m");
        assertEquals(7, stored.n1());
        assertEquals(0, BigDecimal.valueOf(35).compareTo(stored.nsc()));
        assertEquals(Boolean.FALSE, stored.useCompra1());
        WtxConfig eff = svc.effectiveConfig("MNQ", "10m");
        assertEquals(7, eff.n1());
        assertEquals(false, eff.useCompra1());
    }

    @Test
    void laterSlEdit_preservesTheZoneOverride() {
        WtxStrategyService svc = service();
        svc.applyPreset("MNQ", "10m", WtxParamOverride.TOP_TRAIN_Z35);

        svc.updateSlAtrMult("MNQ", "10m", new BigDecimal("3.5"));

        WtxParamOverride stored = overridePort.load("MNQ", "10m");
        assertEquals(0, new BigDecimal("3.5").compareTo(stored.slAtrMult()));
        assertEquals(0, BigDecimal.valueOf(-35).compareTo(stored.nsv()));
        assertEquals(Boolean.FALSE, stored.useVenta1());
    }

    @Test
    void preset_disablesTheSessionFilter_forThisPanelOnly() {
        // Global config WITH the 03:00-08:00 ET entry block (the prod shape).
        WtxStrategyService svc = service(WtxConfig.defaults().withSessionFilter(true, 180, 480));
        svc.applyPreset("MNQ", "10m-z35", WtxParamOverride.TOP_TRAIN_Z35);

        // The preset carries the validated session-OFF shape for ITS panel...
        org.junit.jupiter.api.Assertions.assertFalse(
                svc.effectiveConfig("MNQ", "10m-z35").sessionFilterEnabled(),
                "preset panel must trade around the clock (validated shape)");
        // ...while the legacy panel keeps the global protection.
        org.junit.jupiter.api.Assertions.assertTrue(
                svc.effectiveConfig("MNQ", "10m").sessionFilterEnabled(),
                "legacy panel must keep the global session block");
    }

    @Test
    void applyingClearPreset_restoresGlobalConfig() {
        WtxStrategyService svc = service();
        svc.applyPreset("MNQ", "10m", WtxParamOverride.TOP_TRAIN_Z35);
        svc.applyPreset("MNQ", "10m", WtxParamOverride.NONE);

        WtxConfig eff = svc.effectiveConfig("MNQ", "10m");
        WtxConfig global = WtxConfig.defaults();
        assertEquals(global.n1(), eff.n1());
        assertEquals(0, global.nsc().compareTo(eff.nsc()));
        assertEquals(global.useCompra1(), eff.useCompra1());
    }
}
