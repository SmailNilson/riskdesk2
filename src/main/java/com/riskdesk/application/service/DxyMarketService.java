package com.riskdesk.application.service;

import com.riskdesk.application.dto.DxyHealthComponentView;
import com.riskdesk.application.dto.DxyHealthView;
import com.riskdesk.application.dto.DxySnapshotView;
import com.riskdesk.domain.marketdata.model.DxySnapshot;
import com.riskdesk.domain.marketdata.model.FxPair;
import com.riskdesk.domain.marketdata.model.SyntheticDollarIndexCalculator;
import com.riskdesk.domain.marketdata.model.SyntheticDollarIndexCalculator.DxyCalculationOutcome;
import com.riskdesk.domain.marketdata.model.SyntheticDollarIndexCalculator.DxyCalculationOutcome.CompleteSnapshot;
import com.riskdesk.domain.marketdata.model.SyntheticDollarIndexCalculator.DxyCalculationOutcome.IncompleteResult;
import com.riskdesk.domain.marketdata.model.SyntheticDollarIndexCalculator.EvaluatedComponent;
import com.riskdesk.domain.marketdata.port.DxySnapshotRepositoryPort;
import com.riskdesk.domain.marketdata.port.FxQuoteProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class DxyMarketService {

    private static final Logger log = LoggerFactory.getLogger(DxyMarketService.class);
    private static final String LIVE_SOURCE = "IBKR_SYNTHETIC";
    private static final String FALLBACK_SOURCE = "FALLBACK_DB";
    private static final String UNAVAILABLE_SOURCE = "UNAVAILABLE";
    private static final Duration TEN_MINUTES = Duration.ofMinutes(10);
    private static final Duration ONE_HOUR = Duration.ofHours(1);

    private final ObjectProvider<FxQuoteProvider> fxQuoteProvider;
    private final DxySnapshotRepositoryPort repository;
    private final DxyPricePublisher pricePublisher;
    private final boolean supportedMode;
    private final SyntheticDollarIndexCalculator calculator = new SyntheticDollarIndexCalculator();
    private final ReentrantLock refreshLock = new ReentrantLock();

    private volatile DxySnapshot lastLiveSnapshot;
    private volatile DxySnapshot lastPersistedSnapshot;
    private volatile DxySnapshot lastAvailableSnapshot;
    private volatile DxyHealthView lastHealth = new DxyHealthView("DOWN", null, UNAVAILABLE_SOURCE, 0L, List.of());
    private volatile String lastIssue;

    @Autowired
    public DxyMarketService(ObjectProvider<FxQuoteProvider> fxQuoteProvider,
                            DxySnapshotRepositoryPort repository,
                            DxyPricePublisher pricePublisher,
                            @Value("${riskdesk.ibkr.mode:IB_GATEWAY}") String ibkrMode) {
        this(fxQuoteProvider, repository, pricePublisher, !"CLIENT_PORTAL".equalsIgnoreCase(ibkrMode));
    }

    DxyMarketService(ObjectProvider<FxQuoteProvider> fxQuoteProvider,
                     DxySnapshotRepositoryPort repository,
                     DxyPricePublisher pricePublisher,
                     boolean supportedMode) {
        this.fxQuoteProvider = fxQuoteProvider;
        this.repository = repository;
        this.pricePublisher = pricePublisher;
        this.supportedMode = supportedMode;
    }

    public void refreshSyntheticDxy() {
        if (!refreshLock.tryLock()) {
            log.debug("Synthetic DXY refresh skipped — previous cycle still in progress.");
            return;
        }
        try {
            doRefresh();
        } finally {
            refreshLock.unlock();
        }
    }

    private void doRefresh() {
        if (!supportedMode) {
            lastLiveSnapshot = null;
            lastAvailableSnapshot = null;
            degrade("Synthetic DXY is only available in IB_GATEWAY mode.", 0L, List.of());
            return;
        }

        FxQuoteProvider provider = fxQuoteProvider.getIfAvailable();
        if (provider == null) {
            degrade("FX quote provider unavailable for the active IBKR mode.", 0L, List.of());
            return;
        }

        DxyCalculationOutcome outcome = calculator.calculate(provider.fetchQuotes());
        List<DxyHealthComponentView> components = toHealthComponents(outcome);

        switch (outcome) {
            case CompleteSnapshot complete -> handleComplete(complete, components);
            case IncompleteResult incomplete -> handleIncomplete(incomplete, components);
        }
    }

    private void handleComplete(CompleteSnapshot complete, List<DxyHealthComponentView> components) {
        DxySnapshot snapshot = complete.snapshot();
        lastLiveSnapshot = snapshot;
        lastAvailableSnapshot = snapshot;

        if (shouldPersist(snapshot)) {
            lastPersistedSnapshot = repository.save(snapshot);
        }

        lastHealth = new DxyHealthView("UP", snapshot.timestamp(), LIVE_SOURCE, complete.maxSkewSeconds(), components);

        if (lastIssue != null) {
            log.info("Synthetic DXY live pricing recovered.");
            lastIssue = null;
        }

        pricePublisher.publishIfChanged(snapshot);
    }

    private void handleIncomplete(IncompleteResult incomplete, List<DxyHealthComponentView> components) {
        lastLiveSnapshot = null;

        DxySnapshot fallback = latestPersistedSnapshot().orElse(null);
        if (fallback != null) {
            lastAvailableSnapshot = fallback;
            lastHealth = new DxyHealthView("DEGRADED", fallback.timestamp(), FALLBACK_SOURCE,
                incomplete.maxSkewSeconds(), components);
            warnOnce(incomplete.message());
            pricePublisher.publishIfChanged(fallback);
            return;
        }

        lastAvailableSnapshot = null;
        degrade(incomplete.message(), incomplete.maxSkewSeconds(), components);
    }

    public Optional<DxySnapshot> latestSnapshot() {
        return latestResolvedSnapshot().map(ResolvedSnapshot::snapshot);
    }

    public Optional<ResolvedSnapshot> latestResolvedSnapshot() {
        if (!supportedMode) {
            return Optional.empty();
        }
        if (lastLiveSnapshot != null) {
            return Optional.of(new ResolvedSnapshot(lastLiveSnapshot, LIVE_SOURCE));
        }
        if (lastAvailableSnapshot != null) {
            return Optional.of(new ResolvedSnapshot(lastAvailableSnapshot, FALLBACK_SOURCE));
        }
        return latestPersistedSnapshot().map(s -> new ResolvedSnapshot(s, FALLBACK_SOURCE));
    }

    public Optional<DxySnapshotView> latestView() {
        return latestResolvedSnapshot().map(resolved -> new DxySnapshotView(
            resolved.snapshot().timestamp(),
            resolved.snapshot().eurusd(),
            resolved.snapshot().usdjpy(),
            resolved.snapshot().gbpusd(),
            resolved.snapshot().usdcad(),
            resolved.snapshot().usdsek(),
            resolved.snapshot().usdchf(),
            resolved.snapshot().dxyValue(),
            resolved.servedSource(),
            resolved.snapshot().complete()
        ));
    }

    public List<DxySnapshotView> history(Instant from, Instant to) {
        if (!supportedMode) {
            return List.of();
        }
        return repository.findCompleteBetween(from, to).stream()
            .map(DxySnapshotView::from)
            .toList();
    }

    public DxyHealthView health() {
        return lastHealth;
    }

    public boolean supported() {
        return supportedMode;
    }

    public Optional<DxySnapshot> findBaselineSnapshot(Instant referenceTime) {
        return repository.findLatestCompleteAtOrBefore(referenceTime.minus(TEN_MINUTES))
            .or(() -> repository.findLatestCompleteAtOrBefore(referenceTime.minus(ONE_HOUR)));
    }

    private boolean shouldPersist(DxySnapshot candidate) {
        DxySnapshot current = latestPersistedSnapshot().orElse(null);
        if (current == null) {
            return true;
        }
        return differs(current.eurusd(), candidate.eurusd())
            || differs(current.usdjpy(), candidate.usdjpy())
            || differs(current.gbpusd(), candidate.gbpusd())
            || differs(current.usdcad(), candidate.usdcad())
            || differs(current.usdsek(), candidate.usdsek())
            || differs(current.usdchf(), candidate.usdchf())
            || differs(current.dxyValue(), candidate.dxyValue());
    }

    private static boolean differs(BigDecimal left, BigDecimal right) {
        if (left == null && right == null) return false;
        if (left == null || right == null) return true;
        return left.compareTo(right) != 0;
    }

    private Optional<DxySnapshot> latestPersistedSnapshot() {
        if (lastPersistedSnapshot != null) {
            return Optional.of(lastPersistedSnapshot);
        }
        lastPersistedSnapshot = repository.findLatestComplete().orElse(null);
        return Optional.ofNullable(lastPersistedSnapshot);
    }

    private void degrade(String message, long maxSkewSeconds, List<DxyHealthComponentView> components) {
        lastHealth = new DxyHealthView("DOWN", null, UNAVAILABLE_SOURCE, maxSkewSeconds, components);
        warnOnce(message);
    }

    private void warnOnce(String message) {
        String normalized = (message == null || message.isBlank())
            ? "Synthetic DXY computation is unavailable."
            : message;
        if (!normalized.equals(lastIssue)) {
            log.warn("Synthetic DXY unavailable: {}", normalized);
            lastIssue = normalized;
        }
    }

    private List<DxyHealthComponentView> toHealthComponents(DxyCalculationOutcome outcome) {
        return Arrays.stream(FxPair.values())
            .map(pair -> {
                EvaluatedComponent component = outcome.components().get(pair);
                if (component == null) {
                    return new DxyHealthComponentView(pair.name(), null, null, null, null, null, null, "INVALID", "missing quote");
                }
                return new DxyHealthComponentView(
                    pair.name(),
                    component.bid(),
                    component.ask(),
                    component.last(),
                    component.effectivePrice(),
                    component.pricingMethod(),
                    component.timestamp(),
                    component.valid() ? "VALID" : "INVALID",
                    component.message()
                );
            })
            .toList();
    }

    public record ResolvedSnapshot(DxySnapshot snapshot, String servedSource) {}
}
