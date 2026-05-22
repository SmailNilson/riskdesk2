package com.riskdesk.domain.playbook.automation.port;

import com.riskdesk.domain.playbook.automation.PlaybookDecision;

import java.util.List;
import java.util.Optional;

public interface PlaybookDecisionRepositoryPort {
    Optional<PlaybookDecision> findById(Long id);

    Optional<PlaybookDecision> findByDecisionKey(String decisionKey);

    List<PlaybookDecision> findRecent(String instrument, String timeframe, int limit);

    PlaybookDecision createIfAbsent(PlaybookDecision decision);

    PlaybookDecision save(PlaybookDecision decision);
}
