'use client';

import { useMemo } from 'react';
import type { AlertMessage } from '@/app/hooks/useWebSocket';
import type {
  WtxSignalView,
  WtxRsiSignalView,
  WtxRoutingOutcome,
} from '@/app/lib/api';

/**
 * Input contract for {@link useMobileBadges}.
 *
 * <p>Design note: this hook is a pure aggregator. It deliberately does NOT
 * call {@code useWebSocket()} or {@code useActivePositions()} itself —
 * both of those hooks open their own STOMP/SockJS connection on every
 * instantiation, so a self-subscribing variant would double the broker
 * traffic (once for the existing Dashboard subscription, once for the
 * mobile bottom tab bar). Callers must pass the already-subscribed state
 * down as props.</p>
 *
 * <p>Playbook 7/7 status is intentionally absent: the
 * {@code PlaybookDecision} payload has no client-side "qualified" flag and
 * {@code Quant7GatesSimulationPanel} only renders OPEN/CLOSED simulation
 * rows. There is no `score`, `sevenOfSeven`, or `qualified` field on the
 * decision DTO today — see {@code PlaybookPanel.tsx} for the current
 * shape. Adding a Playbook badge would require a backend field first.</p>
 */
export interface MobileBadgesInput {
  /** Recent alerts feed (last 50 from {@code /topic/alerts}). */
  alerts: AlertMessage[] | undefined;
  /** Recent WTX strategy signals (last 50 from {@code /topic/wtx-signals}). */
  wtxSignals: WtxSignalView[] | undefined;
  /** Recent WTX+RSI strategy signals (last 50 from {@code /topic/wtxrsi-signals}). */
  wtxRsiSignals: WtxRsiSignalView[] | undefined;
  /**
   * Count of currently open positions + resting working orders. Caller is
   * responsible for filtering {@code ActivePositionView.status} into the
   * WORKING/OPEN buckets (matches {@code MobilePositionsCard} semantics).
   */
  positionsCount: number | undefined;
}

/**
 * Badge counts surfaced on the mobile bottom tab bar.
 *
 * <p>Each count is intentionally a "best-effort live" snapshot — clearing
 * an alert / executing a signal / closing a position immediately drops
 * the corresponding count once the upstream hook emits its next update.</p>
 */
export interface MobileBadges {
  /** Recent alerts (size of the alerts buffer — proxy for "unread"). */
  alertCount: number;
  /** WTX signals that fired an actionable intent but are not yet routed to IBKR. */
  wtxSignalCount: number;
  /** WTX+RSI signals that fired an actionable intent but are not yet routed to IBKR. */
  wtxRsiSignalCount: number;
  /** Working orders + open positions (caller-side filtered). */
  positionCount: number;
}

// Routing outcomes that mean the signal already reached the broker — we
// do NOT badge these because the user can't act on them any further.
const ROUTED_OUTCOMES: ReadonlySet<WtxRoutingOutcome> = new Set<WtxRoutingOutcome>([
  'ROUTED',
  'ROUTED_FLATTEN_ONLY',
  'ACK_PENDING',
]);

// WTX actionTaken values that represent a real intent — anything else is
// either a pure indicator print or an exit that doesn't need user attention.
const WTX_ACTIONABLE_ACTIONS: ReadonlySet<WtxSignalView['actionTaken']> = new Set<
  WtxSignalView['actionTaken']
>(['OPEN_LONG', 'OPEN_SHORT', 'REVERSE_TO_LONG', 'REVERSE_TO_SHORT']);

// WTX+RSI uses {@code action} (not {@code actionTaken}) for the same semantic.
const WTX_RSI_ACTIONABLE_ACTIONS: ReadonlySet<WtxRsiSignalView['action']> = new Set<
  WtxRsiSignalView['action']
>(['OPEN_LONG', 'OPEN_SHORT']);

/**
 * "Acted on" = the strategy fired an OPEN/REVERSE intent AND it was
 * accepted by the IBKR bridge. We count the inverse — actionable intent
 * that has NOT been routed yet (or was rejected/skipped). This matches
 * the UX intent: the badge tells the user "you have N signals worth
 * looking at on this tab".
 */
function isUnexecutedWtx(sig: WtxSignalView): boolean {
  return WTX_ACTIONABLE_ACTIONS.has(sig.actionTaken)
    && (sig.routingOutcome == null || !ROUTED_OUTCOMES.has(sig.routingOutcome));
}

function isUnexecutedWtxRsi(sig: WtxRsiSignalView): boolean {
  return WTX_RSI_ACTIONABLE_ACTIONS.has(sig.action)
    && (sig.routingOutcome == null || !ROUTED_OUTCOMES.has(sig.routingOutcome));
}

/**
 * Aggregates badge counts for the mobile bottom tab bar.
 *
 * <p>Pure function of the inputs — no side effects, no subscriptions.
 * Wrapped in {@code useMemo} so the returned object identity is stable
 * across renders that don't touch the inputs (avoids triggering child
 * re-renders in the tab bar).</p>
 *
 * <p>Each {@code undefined} input collapses to zero so the hook is safe
 * to call before the parent's WS connection has produced its first
 * frame.</p>
 */
export function useMobileBadges(input: MobileBadgesInput): MobileBadges {
  const { alerts, wtxSignals, wtxRsiSignals, positionsCount } = input;
  return useMemo<MobileBadges>(() => ({
    alertCount: alerts?.length ?? 0,
    wtxSignalCount: wtxSignals?.filter(isUnexecutedWtx).length ?? 0,
    wtxRsiSignalCount: wtxRsiSignals?.filter(isUnexecutedWtxRsi).length ?? 0,
    positionCount: positionsCount ?? 0,
  }), [alerts, wtxSignals, wtxRsiSignals, positionsCount]);
}
