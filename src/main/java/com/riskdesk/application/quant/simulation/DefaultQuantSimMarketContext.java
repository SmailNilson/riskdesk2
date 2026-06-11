package com.riskdesk.application.quant.simulation;

import com.riskdesk.domain.analysis.port.CandleRepositoryPort;
import com.riskdesk.domain.engine.indicators.AtrCalculator;
import com.riskdesk.domain.engine.indicators.EMAIndicator;
import com.riskdesk.domain.model.Candle;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.simulation.Quant7GatesSimulation;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Candle-store-backed {@link QuantSimMarketContext}. Computes the entry-time
 * ATR and HTF EMA alignment from persisted candles, with a short per-instrument
 * cache so the LONG and SHORT entry checks of one scan tick (and back-to-back
 * 60 s scans) don't re-query the store.
 *
 * <p>Reads use {@code findRecentCandles} (contract-month agnostic): around a
 * rollover the spliced series can briefly distort the ATR/EMA, which is
 * acceptable for a paper-policy input — the alternative (resolving the active
 * contract here) would couple the harness to the rollover workflow.</p>
 */
@Component
public class DefaultQuantSimMarketContext implements QuantSimMarketContext {

    /** Cache TTL — half the scan interval so each scan recomputes at most once. */
    private static final long CACHE_TTL_MS = 30_000;

    private final CandleRepositoryPort candlePort;
    private final QuantSimProperties props;

    private record Cached<T>(T value, long atMs) {}
    private final Map<Instrument, Cached<Double>> atrCache = new ConcurrentHashMap<>();
    private final Map<Instrument, Cached<boolean[]>> htfCache = new ConcurrentHashMap<>();

    public DefaultQuantSimMarketContext(CandleRepositoryPort candlePort, QuantSimProperties props) {
        this.candlePort = candlePort;
        this.props = props;
    }

    @Override
    public Double atr(Instrument instrument) {
        long now = System.currentTimeMillis();
        Cached<Double> hit = atrCache.get(instrument);
        if (hit != null && now - hit.atMs() < CACHE_TTL_MS) return hit.value();

        int period = props.atrPeriod(instrument.name());
        List<Candle> recent = ascending(
            candlePort.findRecentCandles(instrument, props.atrTimeframe(instrument.name()), period * 3 + 1));
        BigDecimal atr = AtrCalculator.compute(recent, period);
        Double value = atr == null ? null : atr.doubleValue();
        atrCache.put(instrument, new Cached<>(value, now));
        return value;
    }

    @Override
    public Boolean htfAligned(Instrument instrument, Quant7GatesSimulation.Direction direction) {
        long now = System.currentTimeMillis();
        Cached<boolean[]> hit = htfCache.get(instrument);
        boolean[] state = (hit != null && now - hit.atMs() < CACHE_TTL_MS) ? hit.value() : null;
        if (state == null) {
            state = computeHtfState(instrument);
            htfCache.put(instrument, new Cached<>(state, now));
        }
        if (state.length == 0) return null; // insufficient history
        boolean fastAboveSlow = state[0];
        return direction == Quant7GatesSimulation.Direction.LONG ? fastAboveSlow : !fastAboveSlow;
    }

    /** {@code []} = not computable; {@code [fastAboveSlow]} otherwise. */
    private boolean[] computeHtfState(Instrument instrument) {
        int slow = props.htfEmaSlow(instrument.name());
        List<Candle> recent = ascending(
            candlePort.findRecentCandles(instrument, props.htfTimeframe(instrument.name()), slow * 3));
        if (recent.size() < slow + 1) return new boolean[0];
        List<BigDecimal> fastSeries = new EMAIndicator(props.htfEmaFast(instrument.name())).calculate(recent);
        List<BigDecimal> slowSeries = new EMAIndicator(slow).calculate(recent);
        if (fastSeries.isEmpty() || slowSeries.isEmpty()) return new boolean[0];
        BigDecimal fast = fastSeries.get(fastSeries.size() - 1);
        BigDecimal slowVal = slowSeries.get(slowSeries.size() - 1);
        return new boolean[] { fast.compareTo(slowVal) > 0 };
    }

    /** Repo returns newest-first; indicator calculators expect oldest-first. */
    private static List<Candle> ascending(List<Candle> candles) {
        return candles.stream()
            .sorted(Comparator.comparing(Candle::getTimestamp))
            .toList();
    }

    /** Test hook — drops cached values so a follow-up call recomputes. */
    void clearCacheForTesting() {
        atrCache.clear();
        htfCache.clear();
    }
}
