package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.strategy.model.MarketContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Picks at most one applicable playbook for a given context. The selection order is
 * the order of the list passed at construction — callers who want priority control
 * provide the list in the order they want consulted.
 *
 * <p><b>Why one playbook at a time?</b> Running two playbooks against the same input
 * and then somehow merging the decisions is the exact anti-pattern the new engine is
 * replacing. A single candidate eliminates cross-setup contradictions by
 * construction; if the market meaningfully supports multiple setups, the caller
 * should evaluate separately.
 */
public final class PlaybookSelector {

    private final List<Playbook> playbooks;

    public PlaybookSelector(List<Playbook> playbooks) {
        this.playbooks = List.copyOf(Objects.requireNonNull(playbooks, "playbooks"));
    }

    public Optional<Playbook> select(MarketContext context) {
        for (Playbook p : playbooks) {
            if (p.isApplicable(context)) return Optional.of(p);
        }
        return Optional.empty();
    }
}
