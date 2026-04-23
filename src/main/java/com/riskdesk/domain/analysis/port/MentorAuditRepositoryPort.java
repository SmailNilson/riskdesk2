package com.riskdesk.domain.analysis.port;

import com.riskdesk.domain.model.MentorAudit;

import java.util.List;
import java.util.Optional;

public interface MentorAuditRepositoryPort {

    MentorAudit save(MentorAudit audit);

    /**
     * Loads a manual "Ask Mentor" audit by its primary key.
     *
     * <p>Introduced in Phase 3 of the Simulation Decoupling Rule so that
     * {@code TradeSimulationService} can resolve the trade plan owner
     * (entry/SL/TP still live on the audit) from a {@code TradeSimulation}
     * row without falling back to the legacy {@code findBySimulationStatuses}
     * query path. This is a legitimate hexagonal read concern on the audit
     * aggregate, not a simulation leak.
     */
    Optional<MentorAudit> findById(Long id);

    Optional<MentorAudit> findBySourceRef(String sourceRef);

    List<MentorAudit> findRecentBySourceRefPrefix(String prefix, int limit);
}
