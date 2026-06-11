package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.marketdata.event.MarketPriceUpdated;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.model.LivePriceSnapshot;
import com.riskdesk.domain.quant.port.LivePricePort;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

/**
 * Bridges the live market-data updates (~3 s poll + debounced IBKR pushes,
 * {@link MarketPriceUpdated}) to the Quant 7-Gates simulation fast exit path
 * ({@link Quant7GatesSimulationService#onPriceTick}).
 *
 * <p>Without this, exits are only evaluated by the 60 s gate scan and a fast
 * move can blow far through the SL/TP level inside one window (sim #903:
 * -93 pts past the SL during the 2026-06-11 squeeze). The listener adds no
 * state and no scheduling — it rides the existing market-data thread; the
 * service no-ops immediately when no row is open for the instrument.</p>
 *
 * <p>{@code MarketPriceUpdated} does not carry provenance and IS also
 * published for DB-fallback / stale prices, so the listener re-reads the
 * price through {@link LivePricePort} (already updated when the event fires —
 * the publisher sets its caches first) and only acts on genuinely live
 * sources. Fallback/stale-priced exits stay on the 60 s scan path, exactly
 * as before. Non-simulated feeds on the same event stream (VIX, DXY) don't
 * resolve to an {@link Instrument} and are skipped. Disable with
 * {@code riskdesk.quant.sim.fast-exit-enabled=false} for scan-only exits.</p>
 */
@Component
public class QuantSimFastExitListener {

    /** Sources considered live — fallback (FALLBACK_DB) and STALE never drive a fast exit. */
    private static final Set<String> LIVE_SOURCES = Set.of("LIVE_PUSH", "LIVE_PROVIDER");

    private final Quant7GatesSimulationService simulationService;
    private final LivePricePort livePricePort;
    private final QuantSimProperties props;

    public QuantSimFastExitListener(Quant7GatesSimulationService simulationService,
                                    LivePricePort livePricePort,
                                    QuantSimProperties props) {
        this.simulationService = simulationService;
        this.livePricePort = livePricePort;
        this.props = props;
    }

    @EventListener
    public void onPriceUpdate(MarketPriceUpdated event) {
        if (!props.isFastExitEnabled()) return;
        if (event == null) return;
        Instrument instrument;
        try {
            instrument = Instrument.valueOf(event.instrument());
        } catch (IllegalArgumentException | NullPointerException e) {
            return; // VIX / DXY / unknown feed — not simulated
        }
        Optional<LivePriceSnapshot> snapshot = livePricePort.current(instrument);
        if (snapshot.isEmpty()) return;
        LivePriceSnapshot live = snapshot.get();
        if (live.source() == null || !LIVE_SOURCES.contains(live.source())) return;
        simulationService.onPriceTick(instrument, live.price(), live.source(), live.timestamp());
    }
}
