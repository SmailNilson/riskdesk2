package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiBacktestEngine;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiConfig;
import com.riskdesk.domain.engine.strategy.wtxrsi.WtxRsiMetrics;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.infrastructure.config.WtxRsiStrategyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates a backtest of the WTX+RSI strategy on stored candles.
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Load 1m candles for {@code [from, to]} from the {@link CandleRepositoryPort}.</li>
 *   <li>Resample to the requested timeframe in memory via {@link CandleResampler}.</li>
 *   <li>Build a {@link WtxRsiConfig} from the {@code application.properties}
 *       defaults plus any per-request overrides.</li>
 *   <li>Run {@link WtxRsiBacktestEngine}, compute metrics, return.</li>
 * </ol>
 *
 * No state, no persistence — this is an on-demand analytical service.
 */
@Service
public class WtxRsiBacktestService {

    private static final Logger log = LoggerFactory.getLogger(WtxRsiBacktestService.class);

    private final CandleRepositoryPort candleRepository;
    private final WtxRsiStrategyProperties properties;

    public WtxRsiBacktestService(
            CandleRepositoryPort candleRepository,
            WtxRsiStrategyProperties properties) {
        this.candleRepository = candleRepository;
        this.properties = properties;
    }

    public WtxRsiBacktestResponse run(WtxRsiBacktestRequest request) {
        Instrument instrument = Instrument.valueOf(request.instrument());
        // 1m is the only timeframe we trust as the canonical source.
        List<Candle> oneMinute = candleRepository.findCandlesBetween(
                instrument, "1m", request.from(), request.to());
        log.info("WTX-RSI backtest: loaded {} 1m candles for {} {} → {}",
                oneMinute.size(), request.instrument(), request.from(), request.to());

        List<Candle> bars = CandleResampler.resample(oneMinute, request.timeframe());
        WtxRsiConfig config = mergeConfig(request);
        WtxRsiBacktestEngine.Result result = new WtxRsiBacktestEngine(config).run(bars);
        WtxRsiMetrics metrics = WtxRsiMetrics.compute(result.trades(), result.equityCurve());

        return new WtxRsiBacktestResponse(
                request.instrument(),
                request.timeframe(),
                bars.size(),
                config,
                metrics,
                result.trades(),
                result.equityCurve()
        );
    }

    private WtxRsiConfig mergeConfig(WtxRsiBacktestRequest req) {
        WtxRsiConfig base = properties.toConfig();
        return new WtxRsiConfig(
                base.wtN1(), base.wtN2(), base.wtSignalPeriod(),
                base.wtOverbought(), base.wtOversold(),
                base.rsiLength(), base.rsiSmaLength(),
                req.syncLookbackBars() != null ? req.syncLookbackBars() : base.syncLookbackBars(),
                req.zoneMode() != null ? req.zoneMode() : base.zoneMode(),
                req.zoneLookbackBars() != null ? req.zoneLookbackBars() : base.zoneLookbackBars(),
                req.fractalLeftRight() != null ? req.fractalLeftRight() : base.fractalLeftRight(),
                req.fractalMaxLookback() != null ? req.fractalMaxLookback() : base.fractalMaxLookback(),
                req.swingBufferTicks() != null ? req.swingBufferTicks() : base.swingBufferTicks(),
                base.tickSize(), base.tickValueUsd(),
                base.baseContracts(), base.confirmedMultiplier(),
                req.tpMode() != null ? req.tpMode() : base.tpMode(),
                req.tpRMultiple() != null ? req.tpRMultiple() : base.tpRMultiple(),
                base.chaikinFast(), base.chaikinSlow(),
                req.chaikinEnabled() != null ? req.chaikinEnabled() : base.chaikinEnabled(),
                req.biasSource() != null ? req.biasSource() : base.biasSource()
        );
    }
}
