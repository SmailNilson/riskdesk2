package com.riskdesk.application.service.strategy.wtxrsi;

import com.riskdesk.application.service.IndicatorService;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Thin adapter that pulls the SMC swing bias string out of {@link IndicatorService}.
 *
 * <p>Exists for one reason: it lets us test {@link WtxRsiBiasResolver} without
 * mocking the final {@code IndicatorSnapshot} record (Mockito 3 can't mock
 * finals). Production wires the {@link IndicatorService} bean; tests inject a
 * subclass / lambda-like override.
 */
@Component
public class SmcBiasSource {

    private static final Logger log = LoggerFactory.getLogger(SmcBiasSource.class);

    private final ObjectProvider<IndicatorService> indicatorServiceProvider;

    public SmcBiasSource(ObjectProvider<IndicatorService> indicatorServiceProvider) {
        this.indicatorServiceProvider = indicatorServiceProvider;
    }

    /**
     * Returns the swing-bias string from the SMC snapshot ({@code BULLISH} /
     * {@code BEARISH} / null) for the given (instrument, timeframe).
     *
     * <p>Returns {@code Optional.empty()} when the indicator service bean is
     * absent (typical in unit tests) or when the snapshot lookup throws —
     * the caller is responsible for falling back to a different source.
     */
    public Optional<String> readSwingBias(Instrument instrument, String timeframe) {
        IndicatorService svc = indicatorServiceProvider.getIfAvailable();
        if (svc == null) return Optional.empty();
        try {
            var snap = svc.computeSnapshot(instrument, timeframe);
            return snap != null ? Optional.ofNullable(snap.swingBias()) : Optional.empty();
        } catch (RuntimeException e) {
            log.warn("SMC swing-bias lookup failed for {} {} — {}",
                    instrument, timeframe, e.getMessage());
            return Optional.empty();
        }
    }
}
