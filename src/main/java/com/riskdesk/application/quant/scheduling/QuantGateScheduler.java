package com.riskdesk.application.quant.scheduling;

import com.riskdesk.application.quant.service.QuantGateService;
import com.riskdesk.domain.model.Instrument;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Fires {@link QuantGateService#scan(Instrument)} every 60 s for the
 * supported futures instruments.
 *
 * <p>Each per-instrument scan is bounded by {@link #scanTimeoutMs} via
 * {@link CompletableFuture#orTimeout}: a hung port fetch or a stuck lock
 * cannot freeze the scheduler. Without this, {@code @Scheduled(fixedDelay)}
 * would never fire again until process restart, halting the evaluator for
 * every instrument. Per-scan failures and timeouts are recovered via
 * {@code exceptionally} so the final {@code allOf().join()} always
 * completes.</p>
 */
@Component
@Profile("!test")
public class QuantGateScheduler {

    private static final Logger log = LoggerFactory.getLogger(QuantGateScheduler.class);

    /** Instruments scanned each tick. MNQ first because it carries the highest signal density. */
    private static final List<Instrument> INSTRUMENTS =
        List.of(Instrument.MNQ, Instrument.MGC, Instrument.MCL);

    private final QuantGateService service;
    private final long scanTimeoutMs;

    public QuantGateScheduler(QuantGateService service,
                              @Value("${riskdesk.quant.scan-timeout-ms:30000}") long scanTimeoutMs) {
        this.service = service;
        this.scanTimeoutMs = scanTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${riskdesk.quant.scan-interval-ms:60000}",
               initialDelayString = "${riskdesk.quant.scan-initial-delay-ms:30000}")
    public void scanAll() {
        List<CompletableFuture<Void>> futures = INSTRUMENTS.stream()
            .map(this::startScanWithTimeout)
            .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
    }

    private CompletableFuture<Void> startScanWithTimeout(Instrument instr) {
        return CompletableFuture
            .runAsync(() -> {
                try {
                    service.scan(instr);
                } catch (Exception ex) {
                    log.warn("quant scan failed for {}: {}", instr, ex.toString());
                }
            })
            .orTimeout(scanTimeoutMs, TimeUnit.MILLISECONDS)
            .exceptionally(ex -> {
                // Covers both TimeoutException (from orTimeout) and any other
                // exception the runAsync block did not catch. The underlying
                // task may still be running on the common pool — that's OK,
                // the scheduler keeps ticking on time and the next tick will
                // start a fresh scan; a single hung scan no longer halts the
                // evaluator for every instrument.
                log.error("quant scan tick exceeded {}ms or failed for {}: {}",
                    scanTimeoutMs, instr, ex.toString());
                return null;
            });
    }
}
