package com.riskdesk.domain.engine.strategy.playbook;

import com.riskdesk.domain.engine.strategy.model.MarketContext;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

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
 *
 * <p><b>Diagnostic logging:</b> when no playbook matches, every candidate is logged
 * at FINE with a one-liner summary of the rejecting context fact (session, regime,
 * priceLocation). Run with {@code -Dlogging.level.com.riskdesk.domain.engine.strategy.playbook=FINE}
 * to investigate why a market state never produces a tradeable verdict.
 */
public final class PlaybookSelector {

    private static final Logger LOG = Logger.getLogger(PlaybookSelector.class.getName());

    private final List<Playbook> playbooks;

    public PlaybookSelector(List<Playbook> playbooks) {
        this.playbooks = List.copyOf(Objects.requireNonNull(playbooks, "playbooks"));
    }

    public Optional<Playbook> select(MarketContext context) {
        for (Playbook p : playbooks) {
            if (p.isApplicable(context)) {
                if (LOG.isLoggable(Level.FINE)) {
                    LOG.log(Level.FINE, () -> "playbook MATCH: " + p.id()
                        + " (" + summarise(context) + ")");
                }
                return Optional.of(p);
            }
        }
        if (LOG.isLoggable(Level.FINE)) {
            StringBuilder sb = new StringBuilder("no applicable playbook (");
            sb.append(summarise(context));
            sb.append("); rejected=");
            for (int i = 0; i < playbooks.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(playbooks.get(i).id());
            }
            LOG.log(Level.FINE, sb::toString);
        }
        return Optional.empty();
    }

    private static String summarise(MarketContext ctx) {
        return "session=" + (ctx.session() == null ? "?" : ctx.session().phase())
            + " killZone=" + (ctx.session() != null && ctx.session().killZone())
            + " regime=" + ctx.regime()
            + " bias=" + ctx.macroBias()
            + " loc=" + ctx.priceLocation()
            + " pd=" + ctx.pdZone();
    }
}
