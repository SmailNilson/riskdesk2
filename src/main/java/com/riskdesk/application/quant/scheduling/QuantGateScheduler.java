package com.riskdesk.application.quant.scheduling;

import com.riskdesk.application.quant.service.QuantGateService;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Fires {@link QuantGateService#scan(Instrument)} every 60 s for the
 * supported futures instruments. Per-instrument errors are caught so a
 * single bad scan does not stop the rest of the loop.
 */
@Component
@Profile("!test")
public class QuantGateScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuantGateScheduler.class);

    /** Instruments scanned each tick. MNQ first because it carries the highest signal density. */
    private static final List<Instrument> INSTRUMENTS =
        List.of(Instrument.MNQ, Instrument.MGC, Instrument.MCL);

    private final QuantGateService service;

    public QuantGateScheduler(QuantGateService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${riskdesk.quant.scan-interval-ms:60000}",
               initialDelayString = "${riskdesk.quant.scan-initial-delay-ms:30000}")
    public void scanAll() {
        List<CompletableFuture<Void>> futures = INSTRUMENTS.stream()
            .map(instr -> CompletableFuture.runAsync(() -> {
                try {
                    service.scan(instr);
                } catch (Exception ex) {
                    log.warn("quant scan failed for {}: {}", instr, ex.toString());
                }
            }))
            .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }
}
