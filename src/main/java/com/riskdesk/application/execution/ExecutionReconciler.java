package com.riskdesk.application.execution;

import com.riskdesk.application.dto.IbkrPortfolioSnapshot;
import com.riskdesk.application.dto.IbkrPositionView;
import com.riskdesk.application.service.IbkrPortfolioService;
import com.riskdesk.domain.execution.IntentKind;
import com.riskdesk.domain.execution.RoutingOutcome;
import com.riskdesk.domain.execution.TradeIntent;
import com.riskdesk.domain.model.Instrument;
import com.riskdesk.domain.model.Side;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Reconciles a {@link TradeIntent} against live IBKR position truth — generalises
 * {@code WtxExecutionBridge.readIbkrPositionState} + {@code reconcileWithIbkr} with no WTX coupling.
 * Two responsibilities:
 * <ul>
 *   <li>{@link #readPositionState} — read broker position truth (net + confirmedFlat) for an instrument;</li>
 *   <li>{@link #reconcile} — decide the resolved {@link ReconcilePlan} (open / reverse / close / flatten / skip).</li>
 * </ul>
 * The decision is <b>pure</b> (intent + position-state in, plan out) so it can be exhaustively unit-tested.
 */
@Component
public class ExecutionReconciler {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReconciler.class);

    private final IbkrPortfolioService portfolioService;

    public ExecutionReconciler(IbkrPortfolioService portfolioService) {
        this.portfolioService = portfolioService;
    }

    /**
     * Live broker net position for {@code instrument} on {@code accountId}, or
     * {@link BrokerPositionState#unavailable()} when the snapshot can't be read. Account-scoped so a
     * multi-account gateway doesn't read account A's positions while orders route to account B.
     */
    public BrokerPositionState readPositionState(String accountId, Instrument instrument) {
        if (portfolioService == null) {
            return BrokerPositionState.unavailable();
        }
        IbkrPortfolioSnapshot snapshot;
        try {
            snapshot = portfolioService.getPortfolio(accountId);
        } catch (RuntimeException e) {
            log.debug("reconcile: portfolio snapshot unavailable for {} (account={}) — {}",
                instrument, accountId, e.getMessage());
            return BrokerPositionState.unavailable();
        }
        if (snapshot == null || !snapshot.connected() || snapshot.positions() == null) {
            return BrokerPositionState.unavailable();
        }
        String symbol = ibkrSymbol(instrument);
        BigDecimal total = BigDecimal.ZERO;
        boolean anyNonzeroLeg = false;
        for (IbkrPositionView pos : snapshot.positions()) {
            if (pos == null || pos.position() == null) continue;
            // Account filter: some gateways return positions across every account on the session even
            // when getPortfolio is given a specific id, so filter again here. No-op when account is null.
            if (accountId != null && pos.accountId() != null && !accountId.equals(pos.accountId())) {
                continue;
            }
            if (matchesSymbol(pos.contractDesc(), symbol)) {
                total = total.add(pos.position());
                if (pos.position().signum() != 0) anyNonzeroLeg = true;
            }
        }
        // Confirmed flat = connected snapshot with NO nonzero matching leg. Offsetting legs that net to
        // zero (rollover / calendar overlap) are LIVE positions, hence NOT flat.
        return new BrokerPositionState(total, !anyNonzeroLeg);
    }

    /** Pure decision: resolve the intent against position truth. No side effects, no I/O. */
    public ReconcilePlan reconcile(TradeIntent intent, BrokerPositionState pos) {
        return switch (intent.kind()) {
            case OPEN, REVERSE -> reconcileEntry(intent, pos);
            // REDUCE is a directional partial close — same reconciliation as CLOSE. (The router's REDUCE
            // path resolves broker truth directly and does not call reconcile; this keeps the switch total.)
            case CLOSE, REDUCE -> reconcileClose(intent, pos);
            case FLATTEN -> reconcileFlatten(pos);
        };
    }

    private ReconcilePlan reconcileEntry(TradeIntent intent, BrokerPositionState pos) {
        Side side = intent.side();
        boolean reverse = intent.kind() == IntentKind.REVERSE;
        if (!pos.available()) {
            // No broker truth — pass the intent through unchanged.
            return reverse ? new ReconcilePlan.Reverse(side) : new ReconcilePlan.Open(side);
        }
        if (pos.isNetZero()) {
            if (pos.confirmedFlat()) {
                // Truly flat (no nonzero leg) — open fresh; a REVERSE downgrades to a plain OPEN.
                return new ReconcilePlan.Open(side);
            }
            // Net zero but NOT flat = offsetting LIVE legs (rollover / calendar overlap). Keep a REVERSE
            // (its executor skips offsetting legs rather than stacking); a plain OPEN must NOT stack a fresh
            // entry on top of the live legs — skip until they are managed/flattened.
            return reverse
                ? new ReconcilePlan.Reverse(side)
                : new ReconcilePlan.Skip(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                    "IBKR holds offsetting live legs (net 0, not flat) — open skipped to avoid stacking");
        }
        boolean wantLong = side == Side.LONG;
        if (wantLong && pos.isLong()) {
            return new ReconcilePlan.Skip(RoutingOutcome.SKIPPED_DUPLICATE, "IBKR already long " + pos.net().abs());
        }
        if (!wantLong && pos.isShort()) {
            return new ReconcilePlan.Skip(RoutingOutcome.SKIPPED_DUPLICATE, "IBKR already short " + pos.net().abs());
        }
        // Opposite side held → flip. Upgrades an OPEN to a REVERSE; keeps a REVERSE as-is.
        return new ReconcilePlan.Reverse(side);
    }

    private ReconcilePlan reconcileClose(TradeIntent intent, BrokerPositionState pos) {
        if (pos.available() && pos.confirmedFlat()) {
            return new ReconcilePlan.Skip(RoutingOutcome.SKIPPED_NO_OPEN_ROW, "IBKR already flat — close skipped");
        }
        return new ReconcilePlan.Close(intent.side());
    }

    private ReconcilePlan reconcileFlatten(BrokerPositionState pos) {
        if (!pos.available()) {
            return new ReconcilePlan.Skip(RoutingOutcome.SKIPPED_NO_OPEN_ROW,
                "position truth unavailable — flatten skipped");
        }
        if (pos.confirmedFlat() || pos.isNetZero()) {
            return new ReconcilePlan.Skip(RoutingOutcome.SKIPPED_NO_OPEN_ROW, "IBKR already flat — nothing to flatten");
        }
        return new ReconcilePlan.Flatten(pos.isLong() ? Side.LONG : Side.SHORT);
    }

    /** IBKR root symbol for the instrument (6E for E6, else the enum name). */
    static String ibkrSymbol(Instrument instrument) {
        return switch (instrument) {
            case E6 -> "6E";
            default -> instrument.name();
        };
    }

    /** Match an IBKR contract description (localSymbol, e.g. "MNQH6") against an instrument root symbol. */
    static boolean matchesSymbol(String contractDesc, String symbol) {
        if (contractDesc == null || symbol == null) return false;
        return contractDesc.toUpperCase().trim().startsWith(symbol);
    }
}
