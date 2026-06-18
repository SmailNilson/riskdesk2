'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import type { CSSProperties, ReactNode } from 'react';
import { useActivePositions } from '@/app/hooks/useActivePositions';
import type { ActivePositionView } from '@/app/lib/api';

const WORKING = new Set(['PENDING_ENTRY_SUBMISSION', 'ENTRY_SUBMITTED', 'ENTRY_PARTIALLY_FILLED']);
const OPEN = new Set(['ACTIVE', 'VIRTUAL_EXIT_TRIGGERED']);
const CLOSING = new Set(['EXIT_SUBMITTED']);

const SWIPE_HINT_KEY = 'riskdesk.mobile.swipe-hint-seen';
const SWIPE_THRESHOLD = 0.6;
const SWIPE_COMMIT_MS = 180;

function num(v: number | string | null | undefined): number | null {
  const n = typeof v === 'string' ? parseFloat(v) : v;
  return n == null || Number.isNaN(n) ? null : n;
}

function fmtUsd(v: number | null) {
  if (v == null) return '—';
  return `${v >= 0 ? '+' : '−'}$${Math.abs(v).toFixed(2)}`;
}

function SideBadge({ direction }: { direction: string }) {
  const long = direction === 'LONG';
  return (
    <span className={`text-[11px] font-medium rounded-md px-2 py-0.5 border ${
      long
        ? 'text-emerald-300 bg-emerald-950 border-emerald-800'
        : 'text-rose-300 bg-rose-950 border-rose-800'
    }`}>
      {long ? 'Long' : 'Short'} {''}
    </span>
  );
}

function StepDot({ on }: { on: boolean }) {
  return <span className={`w-2 h-2 rounded-full flex-shrink-0 ${on ? 'bg-emerald-400' : 'bg-zinc-700'}`} />;
}

/**
 * Wraps row JSX in a swipeable container. Swiping left past 60% width fires
 * `onCommit` (with a 20ms haptic blip on supported devices); release before
 * the threshold snaps back. Tap targets inside still fire — pointer capture
 * is only used during an active drag (after `pointermove` past a small
 * threshold), so a plain click on a child button is unaffected.
 */
function SwipeableRow({
  onCommit,
  actionLabel,
  disabled,
  children,
  className,
}: {
  onCommit: () => void;
  actionLabel: string;
  disabled?: boolean;
  children: ReactNode;
  className?: string;
}) {
  const wrapperRef = useRef<HTMLDivElement | null>(null);
  const [width, setWidth] = useState<number | null>(null);
  const [dx, setDx] = useState(0);
  const [committing, setCommitting] = useState(false);
  const dragRef = useRef<{
    x: number;
    y: number;
    pointerId: number;
    captured: boolean;
    locked: 'h' | 'v' | null;
  } | null>(null);

  useEffect(() => {
    const el = wrapperRef.current;
    if (!el) return;
    setWidth(el.getBoundingClientRect().width);
    if (typeof ResizeObserver === 'undefined') {
      const onResize = () => {
        if (wrapperRef.current) setWidth(wrapperRef.current.getBoundingClientRect().width);
      };
      window.addEventListener('resize', onResize);
      return () => window.removeEventListener('resize', onResize);
    }
    const ro = new ResizeObserver(entries => {
      const entry = entries[0];
      if (entry) setWidth(entry.contentRect.width);
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const reset = useCallback(() => {
    setDx(0);
    dragRef.current = null;
  }, []);

  const onPointerDown = (e: React.PointerEvent<HTMLDivElement>) => {
    if (disabled || committing) return;
    // Ignore non-primary pointers (right click, etc.)
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    dragRef.current = {
      x: e.clientX,
      y: e.clientY,
      pointerId: e.pointerId,
      captured: false,
      locked: null,
    };
  };

  const onPointerMove = (e: React.PointerEvent<HTMLDivElement>) => {
    const drag = dragRef.current;
    if (!drag || committing) return;
    const dxRaw = e.clientX - drag.x;
    const dyRaw = e.clientY - drag.y;
    if (drag.locked == null) {
      // Wait for a small movement before deciding axis. Lock to vertical if
      // the user is clearly scrolling — release without firing.
      if (Math.abs(dxRaw) < 6 && Math.abs(dyRaw) < 6) return;
      if (Math.abs(dyRaw) > Math.abs(dxRaw)) {
        drag.locked = 'v';
        return;
      }
      drag.locked = 'h';
      if (!drag.captured) {
        try {
          (e.currentTarget as Element).setPointerCapture?.(e.pointerId);
          drag.captured = true;
        } catch {
          /* not all targets support pointer capture */
        }
      }
    }
    if (drag.locked === 'v') return;
    // Only allow leftward translation; rubber-band a tiny amount on the right.
    const next = Math.min(0, dxRaw);
    setDx(next);
  };

  const finishDrag = useCallback(
    (e: React.PointerEvent<HTMLDivElement>) => {
      const drag = dragRef.current;
      if (!drag) return;
      if (drag.captured) {
        try {
          (e.currentTarget as Element).releasePointerCapture?.(drag.pointerId);
        } catch {
          /* ignore */
        }
      }
      if (drag.locked !== 'h' || width == null || width <= 0 || disabled) {
        reset();
        return;
      }
      const ratio = -dx / width;
      if (ratio >= SWIPE_THRESHOLD) {
        try {
          navigator.vibrate?.(20);
        } catch {
          /* navigator may be undefined in older webviews */
        }
        setCommitting(true);
        setDx(-width);
        dragRef.current = null;
        window.setTimeout(() => {
          onCommit();
          // Caller may unmount us; if not, reset so the row reappears.
          setCommitting(false);
          setDx(0);
        }, SWIPE_COMMIT_MS);
        return;
      }
      reset();
    },
    [dx, width, onCommit, disabled, reset],
  );

  const ratio = width && width > 0 ? Math.min(1, -dx / width) : 0;
  const armed = ratio >= SWIPE_THRESHOLD;

  const fgStyle: CSSProperties = {
    transform: `translateX(${dx}px)`,
    transition: dx === 0 || committing ? `transform ${committing ? SWIPE_COMMIT_MS : 200}ms ease-out` : 'none',
    touchAction: 'pan-y',
    willChange: 'transform',
  };

  return (
    <div ref={wrapperRef} className={`relative overflow-hidden ${className ?? ''}`}>
      <div
        aria-hidden="true"
        className={`absolute inset-y-0 right-0 w-24 flex items-center justify-center text-[11px] font-semibold text-white transition-colors ${
          armed ? 'bg-red-500' : 'bg-red-600'
        }`}
      >
        {actionLabel}
      </div>
      <div
        style={fgStyle}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={finishDrag}
        onPointerCancel={finishDrag}
        className="relative bg-zinc-900"
      >
        {children}
      </div>
    </div>
  );
}

/**
 * Mobile orders & positions manager (Portf tab). Live state comes from
 * useActivePositions (/topic/positions push + 5s heartbeat). Cancelling a
 * working entry is one tap (safe direction); closing an open position takes
 * an inline confirmation — the close goes out as a marketable limit. Each
 * row is also swipeable: swipe left past 60 % to fire the same close /
 * cancel path with a 20 ms haptic blip.
 */
export default function MobilePositionsCard({ onNewOrder }: { onNewOrder: () => void }) {
  const { positions, loading, error, close, cancelEntry } = useActivePositions();
  const [confirmId, setConfirmId] = useState<number | null>(null);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [hintSeen, setHintSeen] = useState(true);

  // Show the swipe hint until the user successfully swipes for the first time.
  useEffect(() => {
    try {
      setHintSeen(window.localStorage.getItem(SWIPE_HINT_KEY) === '1');
    } catch {
      setHintSeen(true);
    }
  }, []);

  const markHintSeen = useCallback(() => {
    setHintSeen(true);
    try {
      window.localStorage.setItem(SWIPE_HINT_KEY, '1');
    } catch {
      /* private mode / SSR */
    }
  }, []);

  const working = positions.filter(p => WORKING.has(p.status));
  const opened = positions.filter(p => OPEN.has(p.status));
  const closing = positions.filter(p => CLOSING.has(p.status));

  // Resting entries are cancelled (order pulled), live positions are closed
  // (marketable limit) — two different backend paths.
  const doClose = async (p: ActivePositionView) => {
    setBusyId(p.executionId);
    const resting = WORKING.has(p.status);
    await (resting ? cancelEntry(p.executionId) : close(p.executionId));
    setBusyId(null);
    setConfirmId(null);
  };

  // Swipe handlers — fire immediately (the swipe IS the confirmation gesture).
  // We still record hint dismissal so the eyebrow disappears next render.
  const onSwipeClose = useCallback(
    (p: ActivePositionView) => {
      markHintSeen();
      void doClose(p);
    },
    // doClose is stable enough for this — capturing close/cancelEntry would be noisy.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [markHintSeen],
  );
  const onSwipeCancel = useCallback(
    (p: ActivePositionView) => {
      markHintSeen();
      void doClose(p);
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [markHintSeen],
  );

  const onRowKeyDown = (e: React.KeyboardEvent<HTMLDivElement>, p: ActivePositionView) => {
    if ((e.key === 'Enter' || e.key === ' ') && p.closable) {
      e.preventDefault();
      setConfirmId(p.executionId);
    }
  };

  const showHint = !hintSeen && (opened.length > 0 || working.length > 0);

  return (
    <div className="flex flex-col gap-3">
      {error && (
        <p className="text-[11px] text-red-400 bg-red-950/50 border border-red-900 rounded-lg px-3 py-2">{error}</p>
      )}

      {showHint && (
        <p className="text-[10px] text-zinc-600 -mb-1">
          Glisser une position vers la gauche pour la fermer.
        </p>
      )}

      {working.length > 0 && (
        <div className="rounded-lg border border-zinc-800 bg-zinc-900 overflow-hidden">
          <div className="flex items-center justify-between px-3 py-2.5 border-b border-zinc-800">
            <span className="text-[13px] font-semibold text-zinc-200">Ordres en cours</span>
            <span className="text-[11px] text-zinc-500">{working.length}</span>
          </div>
          {working.map(p => {
            const presented = p.status !== 'PENDING_ENTRY_SUBMISSION';
            const body = (
              <div className="px-3 py-2.5 border-b border-zinc-800/60 last:border-b-0">
                <div className="flex items-center gap-2">
                  <SideBadge direction={p.direction} />
                  <span className="font-mono tabular-nums text-[13px] text-white">
                    {p.instrument} @ {num(p.entryPrice)?.toFixed(p.instrument === 'E6' ? 5 : 2) ?? '—'} × {p.quantity ?? 1}
                  </span>
                  <span className="text-[11px] text-amber-400 ml-auto">en attente</span>
                </div>
                <div className="flex items-center gap-1.5 mt-2.5">
                  <StepDot on />
                  <span className="text-[11px] text-zinc-500">Soumis</span>
                  <span className={`flex-1 h-px ${presented ? 'bg-emerald-400' : 'bg-zinc-800'}`} />
                  <StepDot on={presented} />
                  <span className="text-[11px] text-zinc-500">Présenté</span>
                  <span className="flex-1 h-px bg-zinc-800" />
                  <StepDot on={false} />
                  <span className="text-[11px] text-zinc-500">Exécuté</span>
                  {p.closable && (
                    <button
                      onClick={() => doClose(p)}
                      disabled={busyId === p.executionId}
                      className="ml-2.5 min-h-[36px] px-3 rounded-lg border border-rose-800 text-xs font-medium text-rose-300 disabled:opacity-50"
                    >
                      {busyId === p.executionId ? '…' : 'Annuler'}
                    </button>
                  )}
                </div>
              </div>
            );
            if (!p.closable) return <div key={p.executionId}>{body}</div>;
            return (
              <SwipeableRow
                key={p.executionId}
                actionLabel="Annuler"
                disabled={busyId === p.executionId}
                onCommit={() => onSwipeCancel(p)}
              >
                {body}
              </SwipeableRow>
            );
          })}
        </div>
      )}

      <div className="rounded-lg border border-zinc-800 bg-zinc-900 overflow-hidden">
        <div className="flex items-center justify-between px-3 py-2.5 border-b border-zinc-800">
          <span className="text-[13px] font-semibold text-zinc-200">Positions</span>
          <span className="text-[11px] text-zinc-500">
            {loading ? '…' : `${opened.length} ouverte${opened.length > 1 ? 's' : ''}`}
          </span>
        </div>

        {!loading && opened.length === 0 && closing.length === 0 && (
          <p className="px-3 py-4 text-xs text-zinc-600">Aucune position ouverte.</p>
        )}

        {opened.map(p => {
          const pnl = num(p.pnlDollars);
          const dec = p.instrument === 'E6' ? 5 : 2;
          const confirming = confirmId === p.executionId;
          const body = (
            <div
              role="button"
              tabIndex={p.closable ? 0 : -1}
              aria-label={`Position ${p.instrument} ${p.direction}`}
              onKeyDown={e => onRowKeyDown(e, p)}
              className="px-3 py-2.5 border-b border-zinc-800/60 last:border-b-0 focus:outline-none focus:ring-1 focus:ring-zinc-700"
            >
              <div className="flex items-center gap-2">
                <span className="text-[13px] font-semibold text-white">{p.instrument}</span>
                <SideBadge direction={p.direction} />
                <span className="font-mono tabular-nums text-[11px] text-zinc-500">
                  entrée {num(p.entryPrice)?.toFixed(dec) ?? '—'} × {p.quantity ?? 1}
                </span>
                <span className={`font-mono tabular-nums text-sm font-semibold ml-auto ${
                  pnl == null ? 'text-zinc-400' : pnl >= 0 ? 'text-emerald-400' : 'text-red-400'
                }`}>{fmtUsd(pnl)}</span>
              </div>
              <div className="flex items-center gap-3 mt-2">
                <span className="text-[11px] text-zinc-500">
                  SL <span className="font-mono tabular-nums text-red-400">{num(p.stopLoss)?.toFixed(dec) ?? '—'}</span>
                </span>
                <span className="text-[11px] text-zinc-500">
                  TP <span className="font-mono tabular-nums text-emerald-400">{num(p.takeProfit1)?.toFixed(dec) ?? '—'}</span>
                </span>
                {p.status === 'VIRTUAL_EXIT_TRIGGERED' && (
                  <span className="text-[11px] text-amber-400">sortie déclenchée</span>
                )}
                {p.closable && !confirming && (
                  <button
                    onClick={() => setConfirmId(p.executionId)}
                    className="ml-auto min-h-[36px] px-3 rounded-lg border border-rose-800 bg-rose-950 text-xs font-medium text-rose-300"
                  >Clôturer</button>
                )}
              </div>
              {confirming && (
                <div className="flex items-center gap-2 mt-2.5 bg-zinc-950 rounded-lg px-3 py-2">
                  <span className="text-[11px] text-zinc-400 flex-1">
                    Clôture en limite marketable — confirmer ?
                  </span>
                  <button
                    onClick={() => doClose(p)}
                    disabled={busyId === p.executionId}
                    className="min-h-[36px] px-3 rounded-lg bg-rose-500 text-xs font-semibold text-rose-950 disabled:opacity-50"
                  >{busyId === p.executionId ? '…' : 'Confirmer'}</button>
                  <button
                    onClick={() => setConfirmId(null)}
                    className="min-h-[36px] px-3 rounded-lg border border-zinc-700 text-xs text-zinc-300"
                  >Garder</button>
                </div>
              )}
            </div>
          );
          if (!p.closable) return <div key={p.executionId}>{body}</div>;
          return (
            <SwipeableRow
              key={p.executionId}
              actionLabel="Fermer"
              disabled={busyId === p.executionId || confirming}
              onCommit={() => onSwipeClose(p)}
            >
              {body}
            </SwipeableRow>
          );
        })}

        {closing.map(p => (
          <div key={p.executionId} className="flex items-center gap-2 px-3 py-2.5 border-b border-zinc-800/60 last:border-b-0">
            <span className="text-[13px] font-semibold text-white">{p.instrument}</span>
            <SideBadge direction={p.direction} />
            <span className="text-[11px] text-amber-400 ml-auto">clôture en cours…</span>
          </div>
        ))}
      </div>

      <button
        onClick={onNewOrder}
        className="flex items-center justify-center gap-2 min-h-[48px] rounded-lg border border-dashed border-zinc-700 text-[13px] font-medium text-emerald-400 active:scale-[0.98] transition-transform"
      >
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true"><path d="M5 12h14" /><path d="M12 5v14" /></svg>
        Nouvel ordre
      </button>
    </div>
  );
}
