package com.riskdesk.infrastructure.quant.setup.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskdesk.domain.engine.playbook.model.Direction;
import com.riskdesk.domain.engine.strategy.model.MarketRegime;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.quant.setup.GateCheckResult;
import com.riskdesk.domain.quant.setup.SetupPhase;
import com.riskdesk.domain.quant.setup.SetupRecommendation;
import com.riskdesk.domain.quant.setup.SetupStyle;
import com.riskdesk.domain.quant.setup.SetupTemplate;
import com.riskdesk.domain.quant.setup.port.SetupRepositoryPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class SetupJpaAdapter implements SetupRepositoryPort {

    private static final Logger log = LoggerFactory.getLogger(SetupJpaAdapter.class);

    private static final List<String> TERMINAL_PHASES =
        List.of(SetupPhase.CLOSED.name(), SetupPhase.INVALIDATED.name());

    private final SetupJpaRepository repository;
    private final ObjectMapper mapper;

    public SetupJpaAdapter(SetupJpaRepository repository, ObjectMapper mapper) {
        this.repository = repository;
        this.mapper     = mapper;
    }

    @Override
    @Transactional
    public void save(SetupRecommendation r) {
        SetupEntity e = repository.findById(r.id())
            .orElseGet(() -> new SetupEntity(r.id()));
        e.setInstrument(r.instrument().name());
        e.setTemplate(r.template().name());
        e.setStyle(r.style().name());
        e.setPhase(r.phase().name());
        e.setRegime(r.regime().name());
        e.setDirection(r.direction().name());
        e.setFinalScore(r.finalScore());
        e.setEntryPrice(r.entryPrice());
        e.setSlPrice(r.slPrice());
        e.setTp1Price(r.tp1Price());
        e.setTp2Price(r.tp2Price());
        e.setRrRatio(r.rrRatio());
        e.setPlaybookId(r.playbookId());
        e.setGateResultsJson(serialiseGates(r.gateResults()));
        e.setDetectedAt(r.detectedAt());
        e.setUpdatedAt(r.updatedAt());
        repository.save(e);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SetupRecommendation> findById(UUID id) {
        return repository.findById(id).map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<SetupRecommendation> findActiveByInstrument(Instrument instrument) {
        return repository
            .findByInstrumentAndPhaseNotIn(instrument.name(), TERMINAL_PHASES)
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<SetupRecommendation> findByInstrumentAndPhaseSince(Instrument instrument,
                                                                    SetupPhase phase,
                                                                    Instant since) {
        return repository
            .findByInstrumentAndPhaseAndUpdatedAtGreaterThanEqual(
                instrument.name(), phase.name(), since)
            .stream()
            .map(this::toDomain)
            .toList();
    }

    @Override
    @Transactional
    public void updatePhase(UUID id, SetupPhase phase, Instant updatedAt) {
        repository.findById(id).ifPresent(e -> {
            e.setPhase(phase.name());
            e.setUpdatedAt(updatedAt);
            repository.save(e);
        });
    }

    // ── Mapping ────────────────────────────────────────────────────────────

    private SetupRecommendation toDomain(SetupEntity e) {
        return new SetupRecommendation(
            e.getId(),
            safeInstrument(e.getInstrument()),
            safeTemplate(e.getTemplate()),
            safeStyle(e.getStyle()),
            safePhase(e.getPhase()),
            safeRegime(e.getRegime()),
            safeDirection(e.getDirection()),
            e.getFinalScore(),
            e.getEntryPrice(),
            e.getSlPrice(),
            e.getTp1Price(),
            e.getTp2Price(),
            e.getRrRatio(),
            e.getPlaybookId(),
            parseGates(e.getGateResultsJson()),
            e.getDetectedAt(),
            e.getUpdatedAt()
        );
    }

    // ── Serialisation helpers ──────────────────────────────────────────────

    private record GateJson(String gateName, boolean passed, String reason) {}

    private String serialiseGates(List<GateCheckResult> gates) {
        try {
            List<GateJson> list = gates.stream()
                .map(g -> new GateJson(g.gateName(), g.passed(), g.reason()))
                .toList();
            return mapper.writeValueAsString(list);
        } catch (Exception ex) {
            log.warn("failed to serialise gate results: {}", ex.toString());
            return "[]";
        }
    }

    private List<GateCheckResult> parseGates(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<GateJson> list = mapper.readValue(json, new TypeReference<>() {});
            List<GateCheckResult> out = new ArrayList<>(list.size());
            for (GateJson g : list) out.add(new GateCheckResult(g.gateName(), g.passed(), g.reason()));
            return out;
        } catch (Exception ex) {
            log.warn("failed to parse gate results: {}", ex.toString());
            return List.of();
        }
    }

    private Instrument safeInstrument(String s) {
        try { return Instrument.valueOf(s); } catch (Exception e) { return Instrument.MCL; }
    }
    private SetupTemplate safeTemplate(String s) {
        try { return SetupTemplate.valueOf(s); } catch (Exception e) { return SetupTemplate.UNKNOWN; }
    }
    private SetupStyle safeStyle(String s) {
        try { return SetupStyle.valueOf(s); } catch (Exception e) { return SetupStyle.DAY; }
    }
    private SetupPhase safePhase(String s) {
        try { return SetupPhase.valueOf(s); } catch (Exception e) { return SetupPhase.DETECTED; }
    }
    private MarketRegime safeRegime(String s) {
        try { return MarketRegime.valueOf(s); } catch (Exception e) { return MarketRegime.UNKNOWN; }
    }
    private Direction safeDirection(String s) {
        try { return Direction.valueOf(s); } catch (Exception e) { return Direction.LONG; }
    }
}
