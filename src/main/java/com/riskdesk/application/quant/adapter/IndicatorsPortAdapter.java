package com.riskdesk.application.quant.adapter;

import com.riskdesk.application.dto.IndicatorSnapshot;
import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.structure.IndicatorsPort;
import com.riskdesk.domain.quant.structure.IndicatorsSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Bridges the Quant {@link IndicatorsPort} onto the existing
 * {@link IndicatorService}. Always queries the {@code 5m} timeframe — the
 * only one used by the structural filters (matches the Python reference
 * {@code multi_scan_v4.py}).
 *
 * <p>Wrapped in {@link ObjectProvider} so the bean is optional during tests
 * that don't bring up the indicator stack.</p>
 */
@Component
public class IndicatorsPortAdapter implements IndicatorsPort {

    private static final Logger log = LoggerFactory.getLogger(IndicatorsPortAdapter.class);
    private static final String STRUCTURAL_TIMEFRAME = "5m";

    private final ObjectProvider<IndicatorService> indicatorServiceProvider;

    public IndicatorsPortAdapter(ObjectProvider<IndicatorService> indicatorServiceProvider) {
        this.indicatorServiceProvider = indicatorServiceProvider;
    }

    @Override
    public Optional<IndicatorsSnapshot> snapshot5m(Instrument instrument) {
        IndicatorService svc = indicatorServiceProvider.getIfAvailable();
        if (svc == null) return Optional.empty();
        try {
            IndicatorSnapshot raw = svc.computeSnapshot(instrument, STRUCTURAL_TIMEFRAME);
            if (raw == null) return Optional.empty();
            return Optional.of(map(raw));
        } catch (RuntimeException e) {
            log.debug("indicators 5m unavailable for {}: {}", instrument, e.toString());
            return Optional.empty();
        }
    }

    private static IndicatorsSnapshot map(IndicatorSnapshot s) {
        Double lastPrice = toDouble(s.lastPrice());
        Map<String, String> mtf = new LinkedHashMap<>();
        IndicatorSnapshot.MultiResolutionBias m = s.multiResolutionBias();
        if (m != null) {
            putIfNotBlank(mtf, "swing50",   m.swing50());
            putIfNotBlank(mtf, "swing25",   m.swing25());
            putIfNotBlank(mtf, "swing9",    m.swing9());
            putIfNotBlank(mtf, "internal5", m.internal5());
            putIfNotBlank(mtf, "micro1",    m.micro1());
        }

        List<IndicatorsSnapshot.OrderBlockView> obs = new ArrayList<>();
        if (s.activeOrderBlocks() != null) {
            for (IndicatorSnapshot.OrderBlockView ob : s.activeOrderBlocks()) {
                obs.add(new IndicatorsSnapshot.OrderBlockView(
                    ob.type(), ob.status(), toDouble(ob.low()), toDouble(ob.high())));
            }
        }
        List<IndicatorsSnapshot.EqualLowView> els = new ArrayList<>();
        if (s.equalLows() != null) {
            for (IndicatorSnapshot.EqualLevelView el : s.equalLows()) {
                els.add(new IndicatorsSnapshot.EqualLowView(toDouble(el.price()), el.touchCount()));
            }
        }
        List<IndicatorsSnapshot.EqualHighView> ehs = new ArrayList<>();
        if (s.equalHighs() != null) {
            for (IndicatorSnapshot.EqualLevelView eh : s.equalHighs()) {
                ehs.add(new IndicatorsSnapshot.EqualHighView(toDouble(eh.price()), eh.touchCount()));
            }
        }

        // The lastPrice reference is exposed primarily so the ports can stay
        // self-sufficient if a caller ever needs it; the structural evaluator
        // takes the live price separately and ignores the field here.
        if (lastPrice == null) {
            // not strictly needed for the evaluator but logged for diagnostics
            log.trace("indicators snapshot has no lastPrice for downstream use");
        }

        return new IndicatorsSnapshot(
            toDouble(s.vwap()),
            toDouble(s.vwapLowerBand()),
            toDouble(s.vwapUpperBand()),
            toDouble(s.bbPct()),
            toDouble(s.cmf()),
            s.currentZone(),
            s.swingBias(),
            s.lastInternalBreakType(),
            mtf,
            obs,
            els,
            ehs
        );
    }

    private static void putIfNotBlank(Map<String, String> out, String key, String val) {
        if (val == null || val.isBlank()) return;
        out.put(key, val);
    }

    private static Double toDouble(BigDecimal v) {
        return v == null ? null : v.doubleValue();
    }
}
