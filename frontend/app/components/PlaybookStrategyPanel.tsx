'use client';

import { useEffect, useState, useCallback, useRef } from 'react';
import {
  getPlaybookStrategyState,
  getPlaybookStrategyRecentSignals,
  updatePlaybookStrategyProfile,
  updatePlaybookStrategyAutoExecution,
  updatePlaybookStrategyOrderQty,
} from '@/app/lib/api';
import type {
  PlaybookStrategyStateView,
  PlaybookSignalView,
  PlaybookProfileType,
  WtxRoutingOutcome,
} from '@/app/lib/api';

const POLL_MS = 5000;
const PROFILE_OPTIONS: PlaybookProfileType[] = ['BASELINE', 'SESSION_ATR', 'STRICT'];

function DirectionChip({ dir }: { dir: 'FLAT' | 'LONG' | 'SHORT' }) {
  const style =
    dir === 'LONG'  ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60' :
    dir === 'SHORT' ? 'bg-red-950/70 text-red-300 border-red-800/60' :
                     'bg-zinc-800 text-zinc-400 border-zinc-700';
  return (
    <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded border ${style}`}>{dir}</span>
  );
}

function SignalChip({ direction }: { direction: string }) {
  const isLong = direction === 'LONG' || direction === 'COMPRA' || direction.startsWith('BUY');
  const style = isLong
    ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60'
    : 'bg-red-950/70 text-red-300 border-red-800/60';
  return (
    <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded border ${style}`}>{direction}</span>
  );
}

function RoutingChip({ outcome, errorMessage }: { outcome: WtxRoutingOutcome | null; errorMessage?: string | null }) {
  if (!outcome) return null;
  const isAckPending = outcome === 'ACK_PENDING';
  const isMargin = outcome === 'SKIPPED_INSUFFICIENT_MARGIN';
  const isTimeout = outcome === 'FAILED_TIMEOUT';
  const isReject = outcome === 'FAILED_BROKER_REJECT' || outcome === 'FAILED';
  const style =
    outcome === 'ROUTED'  ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60' :
    isAckPending          ? 'bg-cyan-950/70 text-cyan-300 border-cyan-800/60' :
    isMargin              ? 'bg-orange-950/70 text-orange-300 border-orange-800/60' :
    (isTimeout || isReject) ? 'bg-red-950/70 text-red-300 border-red-800/60' :
                            'bg-amber-950/70 text-amber-300 border-amber-800/60';
  const label =
    outcome === 'ROUTED'  ? 'IBKR ✓' :
    isAckPending          ? 'ACK ?' :
    isMargin              ? 'NO MARGIN' :
    isTimeout             ? 'TIMEOUT' :
    outcome === 'FAILED_BROKER_REJECT' ? 'REJECT' :
                            outcome.replace(/^SKIPPED_/, '').replace(/_/g, ' ');
  const tooltip = errorMessage
    ? `Routage IBKR : ${outcome}\n${errorMessage}`
    : `Routage IBKR : ${outcome}`;
  return (
    <span
      title={tooltip}
      className={`text-[9px] font-semibold px-1.5 py-0.5 rounded border ${style}`}
    >
      {label}
    </span>
  );
}

function PnlBar({ pnl, max }: { pnl: number; max: number }) {
  const pct = Math.min(Math.abs(pnl) / (max || 1) * 100, 100);
  const color = pnl >= 0 ? 'bg-emerald-500' : 'bg-red-500';
  return (
    <div className="flex items-center gap-2 mt-0.5">
      <div className="flex-1 h-1.5 bg-zinc-800 rounded-full overflow-hidden">
        <div className={`h-full rounded-full transition-all ${color}`} style={{ width: `${pct}%` }} />
      </div>
      <span className={`text-[10px] font-mono ${pnl >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
        {pnl >= 0 ? '+' : ''}{pnl.toFixed(0)}$
      </span>
    </div>
  );
}

function SignalCard({ sig }: { sig: PlaybookSignalView }) {
  const ts = new Date(sig.evaluatedAt);
  const timeStr = isNaN(ts.getTime())
    ? '—'
    : `${ts.getHours().toString().padStart(2, '0')}:${ts.getMinutes().toString().padStart(2, '0')}`;
  
  return (
    <div className="border border-zinc-800/60 rounded p-2 space-y-1 bg-zinc-950/20 hover:border-zinc-700/60 transition-colors">
      <div className="flex items-center gap-1.5 flex-wrap">
        <SignalChip direction={sig.direction} />
        <span className="text-[10px] text-zinc-400">{sig.instrument} {sig.timeframe}</span>
        <span className="text-[10.5px] font-semibold text-cyan-400 font-mono bg-cyan-950/30 border border-cyan-900/40 rounded px-1.5 py-0.2 ml-1.5">
          Score: {sig.checklistScore}/7
        </span>
        <span className="text-[10px] text-zinc-600 ml-auto">{timeStr}</span>
      </div>
      
      <div className="text-[10px] flex items-center justify-between text-zinc-400 font-mono pt-0.5">
        <div>
          <span className="text-zinc-500 mr-1">Setup:</span>
          <span className="text-zinc-300 font-medium">{sig.setupType.replace(/_/g, ' ')}</span>
        </div>
        <div className="flex items-center gap-1.5">
          <RoutingChip outcome={sig.routingOutcome} errorMessage={sig.routingErrorMessage} />
        </div>
      </div>

      <div className="grid grid-cols-4 gap-1.5 text-[10px] font-mono border-t border-zinc-900/60 pt-1 mt-1 text-center">
        <div>
          <div className="text-zinc-500 text-[9px]">Entry</div>
          <div className="text-zinc-300 font-semibold">{sig.entryPrice.toFixed(2)}</div>
        </div>
        <div>
          <div className="text-zinc-500 text-[9px]">SL</div>
          <div className="text-red-400/90 font-semibold">{sig.stopLoss.toFixed(2)}</div>
        </div>
        <div>
          <div className="text-zinc-500 text-[9px]">TP1</div>
          <div className="text-emerald-400/90 font-semibold">{sig.takeProfit1.toFixed(2)}</div>
        </div>
        <div>
          <div className="text-zinc-500 text-[9px]">TP2</div>
          <div className="text-emerald-300/70 font-semibold">{sig.takeProfit2.toFixed(2)}</div>
        </div>
      </div>
    </div>
  );
}

interface Props {
  instrument: string;
  timeframe: string;
  liveSignals: PlaybookSignalView[];
}

export default function PlaybookStrategyPanel({ instrument, timeframe, liveSignals }: Props) {
  const [state, setState] = useState<PlaybookStrategyStateView | null>(null);
  const [signals, setSignals] = useState<PlaybookSignalView[]>([]);
  const [collapsed, setCollapsed] = useState(false);
  const [profileBusy, setProfileBusy] = useState(false);
  const [autoExecBusy, setAutoExecBusy] = useState(false);
  const [qtyBusy, setQtyBusy] = useState(false);
  const [qtyDraft, setQtyDraft] = useState<string>('');
  
  // Tracks the panel identity so we overwrite the qty draft only once per new panel load
  const draftPanelRef = useRef<string>('');

  const loadState = useCallback(async () => {
    const s = await getPlaybookStrategyState(instrument, timeframe);
    if (s) setState(s);
    else setState(null);
  }, [instrument, timeframe]);

  const loadSignals = useCallback(async () => {
    const s = await getPlaybookStrategyRecentSignals(instrument, 20, timeframe);
    setSignals(s.filter(sig => sig.timeframe === timeframe));
  }, [instrument, timeframe]);

  useEffect(() => {
    loadState();
    loadSignals();
    const id = setInterval(loadState, POLL_MS);
    return () => clearInterval(id);
  }, [loadState, loadSignals]);

  const onProfileChange = useCallback(async (next: PlaybookProfileType) => {
    if (!state || state.activeProfile === next) return;
    setProfileBusy(true);
    try {
      const updated = await updatePlaybookStrategyProfile(instrument, timeframe, next);
      if (updated) setState(updated);
    } finally {
      setProfileBusy(false);
    }
  }, [instrument, timeframe, state]);

  const commitQty = useCallback(async () => {
    if (!state || qtyBusy) return;
    const trimmed = qtyDraft.trim();
    if (trimmed === '') {
      setQtyDraft(String(state.configuredOrderQty));
      return;
    }
    const parsed = Number(trimmed);
    if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed <= 0 || parsed > 100) {
      setQtyDraft(String(state.configuredOrderQty));
      return;
    }
    if (parsed === state.configuredOrderQty) {
      setQtyDraft(String(state.configuredOrderQty));
      return;
    }
    setQtyBusy(true);
    try {
      const updated = await updatePlaybookStrategyOrderQty(instrument, timeframe, parsed);
      if (updated) {
        setState(updated);
        setQtyDraft(String(updated.configuredOrderQty));
      } else {
        setQtyDraft(String(state.configuredOrderQty));
      }
    } finally {
      setQtyBusy(false);
    }
  }, [instrument, timeframe, qtyBusy, qtyDraft, state]);

  // Sync draft panel when props change
  useEffect(() => {
    setQtyDraft('');
  }, [instrument, timeframe]);

  useEffect(() => {
    if (!state) return;
    if (state.instrument !== instrument || state.timeframe !== timeframe) return;
    const panelId = `${state.instrument}:${state.timeframe}`;
    if (draftPanelRef.current !== panelId) {
      draftPanelRef.current = panelId;
      setQtyDraft(String(state.configuredOrderQty));
    }
  }, [state, instrument, timeframe]);

  const onToggleAutoExec = useCallback(async () => {
    if (!state) return;
    const turningOn = !state.autoExecutionEnabled;
    if (turningOn) {
      const confirmed = window.confirm(
        `⚠️ Activer l'exécution auto IBKR pour ${instrument} ${timeframe} (PLAYBOOK) ?\n\n` +
        `Chaque signal Playbook ${timeframe} (${state.activeProfile}) qui remplit le score minimum du profil déclenchera un ordre RÉEL (Limit order au prix d'entrée) sur IBKR.\n\n` +
        `Ce réglage est propre au timeframe ${timeframe} et reste actif jusqu'à désactivation manuelle.`
      );
      if (!confirmed) return;
    }
    setAutoExecBusy(true);
    try {
      const updated = await updatePlaybookStrategyAutoExecution(instrument, timeframe, turningOn);
      if (updated) setState(updated);
    } finally {
      setAutoExecBusy(false);
    }
  }, [instrument, timeframe, state]);

  // Merge live STOMP signals on top of polled historical signals
  const merged = [
    ...liveSignals.filter(s => s.instrument === instrument && s.timeframe === timeframe),
    ...signals,
  ].filter((s, i, arr) => arr.findIndex(x => x.id === s.id) === i).slice(0, 20);

  const autoExecOn = state?.autoExecutionEnabled === true;

  return (
    <div className={`border rounded-lg p-3 space-y-2 transition-all duration-300 ${autoExecOn ? 'border-red-700/70 bg-red-950/10' : 'border-indigo-900/40 bg-zinc-900/80'}`}>
      {/* Header */}
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-indigo-300 tracking-wider">PLAYBOOK STRATEGY</span>
          <span className="text-[10px] text-zinc-500">{instrument} · {timeframe}</span>
        </div>
        <div className="flex items-center gap-2">
          {state && (
            <div className="flex items-center gap-1.5">
              <DirectionChip dir={state.currentDirection} />
              <span className="flex items-center gap-1 text-[10px]">
                <span className={`w-1.5 h-1.5 rounded-full ${state.canTrade ? 'bg-emerald-400 animate-pulse' : 'bg-red-500'}`} />
                <span className={state.canTrade ? 'text-emerald-400 font-medium' : 'text-red-400 font-medium'}>
                  {state.canTrade ? 'ACTIF' : 'BLOQUÉ'}
                </span>
              </span>
            </div>
          )}
          <button
            type="button"
            onClick={() => setCollapsed(isCollapsed => !isCollapsed)}
            aria-expanded={!collapsed}
            className="rounded border border-zinc-700 px-2 py-1 text-[10px] font-semibold text-zinc-400 hover:border-indigo-700 hover:text-indigo-300 transition-colors bg-zinc-950/30"
          >
            {collapsed ? 'Afficher' : 'Cacher'}
          </button>
        </div>
      </div>

      {!collapsed && (
        <>
          {/* Controls */}
          {state && (
            <div className="flex items-center gap-2 flex-wrap bg-zinc-950/40 p-1.5 rounded border border-zinc-800/40">
              <label className="flex items-center gap-1.5 text-[10px] text-zinc-400">
                <span className="text-zinc-500">Profil</span>
                <select
                  value={state.activeProfile}
                  onChange={e => onProfileChange(e.target.value as PlaybookProfileType)}
                  disabled={profileBusy}
                  className="rounded border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[10px] text-zinc-200 hover:border-indigo-700 focus:border-indigo-600 focus:outline-none disabled:opacity-50 cursor-pointer"
                >
                  {PROFILE_OPTIONS.map(p => (
                    <option key={p} value={p}>{p.replace('_', ' ')}</option>
                  ))}
                </select>
              </label>
              
              <label className="flex items-center gap-1.5 text-[10px] text-zinc-400" title="Nombre de contrats envoyés à IBKR pour chaque signal déclenché">
                <span className="text-zinc-500">Qty</span>
                <input
                  type="number"
                  min={1}
                  max={100}
                  step={1}
                  value={qtyDraft}
                  onChange={e => setQtyDraft(e.target.value)}
                  onBlur={commitQty}
                  onKeyDown={e => {
                    if (e.key === 'Enter') {
                      e.preventDefault();
                      (e.target as HTMLInputElement).blur();
                    }
                    if (e.key === 'Escape') {
                      setQtyDraft(String(state.configuredOrderQty));
                      (e.target as HTMLInputElement).blur();
                    }
                  }}
                  disabled={qtyBusy}
                  className="w-12 rounded border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[10px] text-zinc-200 hover:border-indigo-700 focus:border-indigo-600 focus:outline-none disabled:opacity-50"
                />
              </label>

              <button
                type="button"
                onClick={onToggleAutoExec}
                disabled={autoExecBusy}
                aria-pressed={autoExecOn}
                title={autoExecOn ? 'Désactiver le routage IBKR auto' : 'Activer le routage IBKR auto (confirmation requise)'}
                className={`rounded border px-2 py-0.5 text-[10px] font-semibold transition-colors disabled:opacity-50 ${
                  autoExecOn
                    ? 'border-red-600/70 bg-red-950/40 text-red-300 hover:bg-red-950/60'
                    : 'border-zinc-700 text-zinc-400 hover:border-emerald-700 hover:text-emerald-300 bg-zinc-900'
                }`}
              >
                Auto-IBKR : {autoExecOn ? 'ON' : 'OFF'}
              </button>
            </div>
          )}

          {/* Daily P&L bar */}
          {state && (
            <div className="bg-zinc-950/35 p-2 rounded border border-zinc-800/40 space-y-1">
              <div className="flex justify-between text-[10px] text-zinc-500">
                <span>Daily P&L (Sim / Live)</span>
                <span>max -{state.maxDailyLossUsd.toFixed(0)}$</span>
              </div>
              <PnlBar pnl={state.dailyPnl} max={state.maxDailyLossUsd} />
            </div>
          )}

          {/* Signals list */}
          <div className="space-y-1.5 pt-1">
            <span className="text-[10px] text-zinc-500 uppercase tracking-wider font-semibold">Signaux Déclenchés</span>
            {merged.length === 0 ? (
              <p className="text-[10px] text-zinc-600 italic pl-1">Aucun signal capturé</p>
            ) : (
              <div className="space-y-2 max-h-[380px] overflow-y-auto custom-scrollbar pr-1">
                {merged.map(sig => <SignalCard key={sig.id} sig={sig} />)}
              </div>
            )}
          </div>
        </>
      )}
    </div>
  );
}
