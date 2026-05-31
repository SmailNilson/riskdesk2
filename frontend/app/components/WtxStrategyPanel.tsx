'use client';

import { useEffect, useState, useCallback, useRef } from 'react';
import {
  getWtxState,
  getWtxRecentSignals,
  updateWtxProfile,
  updateWtxAutoExecution,
  updateWtxSwingBiasFilter,
  updateWtxOrderQty,
  updateWtxTelegramNotifications,
} from '@/app/lib/api';
import type {
  WtxStrategyStateView,
  WtxSignalView,
  WtxEnrichmentView,
  WtxProfile,
  WtxRoutingOutcome,
} from '@/app/lib/api';
import DayGroupedSignals from '@/app/components/strategy/DayGroupedSignals';

const POLL_MS = 5000;
const PROFILE_OPTIONS: WtxProfile[] = ['BASELINE', 'SESSION_ATR', 'HTF', 'STRICT'];

function DirectionChip({ dir }: { dir: 'FLAT' | 'LONG' | 'SHORT' }) {
  const style =
    dir === 'LONG'  ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60' :
    dir === 'SHORT' ? 'bg-red-950/70 text-red-300 border-red-800/60' :
                     'bg-zinc-800 text-zinc-400 border-zinc-700';
  return (
    <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded border ${style}`}>{dir}</span>
  );
}

function SignalChip({ type }: { type: WtxSignalView['signalType'] }) {
  const style = type.startsWith('COMPRA')
    ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60'
    : 'bg-red-950/70 text-red-300 border-red-800/60';
  return (
    <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded border ${style}`}>{type}</span>
  );
}

function RoutingChip({ outcome, errorMessage }: { outcome: WtxRoutingOutcome | null; errorMessage?: string | null }) {
  if (!outcome) return null;
  const isAckPending = outcome === 'ACK_PENDING';
  const isFlattenOnly = outcome === 'ROUTED_FLATTEN_ONLY';
  const isMargin = outcome === 'SKIPPED_INSUFFICIENT_MARGIN';
  const isTimeout = outcome === 'FAILED_TIMEOUT';
  const isReject = outcome === 'FAILED_BROKER_REJECT' || outcome === 'FAILED';
  // ACK_PENDING is "broker has the order, ack was late" — cyan signals "in flight,
  // awaiting confirmation", which is conceptually different from the amber SKIPPED_*
  // fallback ("not attempted / not eligible"). Keeping the same amber would make the
  // two indistinguishable at a glance. ROUTED_FLATTEN_ONLY (teal) is "the reverse
  // flattened you to FLAT but couldn't open the new leg" — a protected partial success.
  const style =
    outcome === 'ROUTED'  ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60' :
    isFlattenOnly         ? 'bg-teal-950/70 text-teal-300 border-teal-800/60' :
    isAckPending          ? 'bg-cyan-950/70 text-cyan-300 border-cyan-800/60' :
    isMargin              ? 'bg-orange-950/70 text-orange-300 border-orange-800/60' :   // distinct from red
    (isTimeout || isReject) ? 'bg-red-950/70 text-red-300 border-red-800/60' :
                            'bg-amber-950/70 text-amber-300 border-amber-800/60';
  const label =
    outcome === 'ROUTED'  ? 'IBKR ✓' :
    isFlattenOnly         ? 'FLAT ✓' :
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

function SwingBiasControl({
  enabled,
  direction,
  busy,
  onToggle,
}: {
  enabled: boolean;
  direction: 'BULLISH' | 'BEARISH' | null;
  busy: boolean;
  onToggle: () => void;
}) {
  const dirLabel = direction === 'BULLISH' ? '▲ BULL' : direction === 'BEARISH' ? '▼ BEAR' : '—';
  const dirStyle =
    direction === 'BULLISH' ? 'text-emerald-300' :
    direction === 'BEARISH' ? 'text-red-300' :
                              'text-zinc-500';
  return (
    <button
      type="button"
      onClick={onToggle}
      disabled={busy}
      aria-pressed={enabled}
      title={
        enabled
          ? `Filtre Swing-Bias actif — seules les entrées alignées avec ${direction ?? '—'} sont autorisées. Cliquer pour désactiver.`
          : `Filtre Swing-Bias inactif — cliquer pour n'autoriser que les trades alignés avec le swing-bias SMC. Direction courante : ${direction ?? '—'}.`
      }
      className={`flex items-center gap-1.5 rounded border px-2 py-0.5 text-[10px] font-semibold transition-colors disabled:opacity-50 ${
        enabled
          ? 'border-cyan-600/70 bg-cyan-950/40 text-cyan-200 hover:bg-cyan-950/60'
          : 'border-zinc-700 text-zinc-400 hover:border-cyan-700 hover:text-cyan-300'
      }`}
    >
      <span>Swing : {enabled ? 'ON' : 'OFF'}</span>
      <span className={`font-mono ${enabled ? dirStyle : 'text-zinc-600'}`}>{dirLabel}</span>
    </button>
  );
}

function Stat({ label, value }: { label: string; value: string | undefined }) {
  return (
    <div className="flex flex-col">
      <span className="text-zinc-600 text-[9px]">{label}</span>
      <span className="font-mono text-zinc-200">{value ?? '—'}</span>
    </div>
  );
}

function PnlBar({ pnl, max }: { pnl: number; max: number }) {
  const pct = Math.min(Math.abs(pnl) / max * 100, 100);
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

function EnrichmentRow({ label, value, accent }: { label: string; value: string | null; accent?: boolean }) {
  if (!value) return null;
  return (
    <div className="flex justify-between text-[10px]">
      <span className="text-zinc-500">{label}</span>
      <span className={accent ? 'text-cyan-300 font-medium' : 'text-zinc-300'}>{value}</span>
    </div>
  );
}

function EnrichmentSection({ e }: { e: WtxEnrichmentView }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="mt-1 border border-zinc-800 rounded bg-zinc-950/50">
      <button
        onClick={() => setOpen(o => !o)}
        className="w-full flex justify-between items-center px-2 py-1 text-[10px] text-zinc-500 hover:text-zinc-300 transition-colors"
      >
        <span>Enrichissement (informatif)</span>
        <span>{open ? '▴' : '▾'}</span>
      </button>
      {open && (
        <div className="px-2 pb-2 space-y-0.5">
          {/* Filter results — shown first when profile uses filters */}
          {(e.htfBias || e.structureReason) && (
            <>
              <div className="text-[9px] text-zinc-600 uppercase tracking-wider pt-1 pb-0.5">Filtres profil</div>
              <EnrichmentRow label="HTF bias" value={e.htfBias} accent />
              <EnrichmentRow
                label="Structure"
                value={e.structureReason ? `${e.structurePassed === false ? '✗ ' : ''}${e.structureReason.replace(/_/g, ' ')}` : null}
                accent={e.structurePassed !== false}
              />
            </>
          )}
          {/* Order Flow */}
          <div className="text-[9px] text-zinc-600 uppercase tracking-wider pt-1 pb-0.5">Order Flow</div>
          <EnrichmentRow label="Delta" value={e.deltaDirection ? `${e.deltaDirection}${e.deltaValue != null ? ` Δ${e.deltaValue > 0 ? '+' : ''}${e.deltaValue.toFixed(0)}` : ''}` : null} accent />
          <EnrichmentRow label="Source" value={e.orderFlowSource} />
          <EnrichmentRow label="Absorption" value={e.absorptionSignal?.replace('_ABSORPTION', '') ?? null} accent />
          {e.absorptionScore != null && (
            <EnrichmentRow label="Score absorb." value={e.absorptionScore.toFixed(2)} />
          )}
          {/* Bollinger Bands */}
          <div className="text-[9px] text-zinc-600 uppercase tracking-wider pt-1 pb-0.5">Bollinger Bands</div>
          <EnrichmentRow label="%B" value={e.bbPct != null ? e.bbPct.toFixed(2) : null} />
          <EnrichmentRow label="BB" value={e.bbExpanding ? 'Expanding' : 'Contracting'} />
          {/* VWAP */}
          <div className="text-[9px] text-zinc-600 uppercase tracking-wider pt-1 pb-0.5">VWAP</div>
          <EnrichmentRow label="Position" value={e.priceVsVwap != null ? `${e.priceVsVwap}${e.vwapDistancePct != null ? ` (${e.vwapDistancePct.toFixed(2)}%)` : ''}` : null} />
          {/* SMC */}
          <div className="text-[9px] text-zinc-600 uppercase tracking-wider pt-1 pb-0.5">SMC</div>
          <EnrichmentRow label="Internal" value={e.smcInternalBias} />
          <EnrichmentRow label="Swing" value={e.smcSwingBias} />
          {/* Order Block */}
          {e.nearestObType && (
            <>
              <div className="text-[9px] text-zinc-600 uppercase tracking-wider pt-1 pb-0.5">Order Block</div>
              <EnrichmentRow label="Type" value={e.nearestObType} />
              {e.nearestObDistancePct != null && (
                <EnrichmentRow label="Distance" value={`${e.nearestObDistancePct.toFixed(2)}%`} />
              )}
            </>
          )}
          {/* CMF */}
          <div className="text-[9px] text-zinc-600 uppercase tracking-wider pt-1 pb-0.5">CMF / Session</div>
          <EnrichmentRow label="CMF" value={e.cmf != null ? e.cmf.toFixed(3) : null} />
          <EnrichmentRow label="Session" value={e.sessionPhase != null ? `${e.sessionPhase}${e.inKillZone ? ' [KILL ZONE]' : ''}` : null} />
        </div>
      )}
    </div>
  );
}

function SignalCard({ sig }: { sig: WtxSignalView }) {
  const ts = new Date(sig.signalTs);
  const timeStr = `${ts.getHours().toString().padStart(2, '0')}:${ts.getMinutes().toString().padStart(2, '0')}`;
  return (
    <div className="border border-zinc-800/60 rounded p-2 space-y-1">
      <div className="flex items-center gap-1.5 flex-wrap">
        <SignalChip type={sig.signalType} />
        <span className="text-[10px] text-zinc-400">{sig.instrument} {sig.timeframe}</span>
        <span className="text-[10px] text-zinc-600 ml-auto">{timeStr}</span>
      </div>
      <div className="flex items-center gap-2 text-[10px]">
        <span className="text-zinc-500">wt1</span>
        <span className="font-mono text-zinc-200">{sig.wt1Value.toFixed(2)}</span>
        <span className="text-zinc-500">action</span>
        <span className="text-zinc-300">{sig.actionTaken.replace(/_/g, ' ')}</span>
        <span className="ml-auto flex items-center gap-1.5">
          <RoutingChip outcome={sig.routingOutcome} errorMessage={sig.routingErrorMessage} />
          {!sig.canTrade && (
            <span className="text-rose-400 text-[9px]">BLOQUÉ</span>
          )}
        </span>
      </div>
      {sig.enrichment && <EnrichmentSection e={sig.enrichment} />}
    </div>
  );
}

interface Props {
  instrument: string;
  timeframe: string;
  liveSignals: WtxSignalView[];
}

export default function WtxStrategyPanel({ instrument, timeframe, liveSignals }: Props) {
  const [state, setState] = useState<WtxStrategyStateView | null>(null);
  const [signals, setSignals] = useState<WtxSignalView[]>([]);
  const [collapsed, setCollapsed] = useState(false);
  const [profileBusy, setProfileBusy] = useState(false);
  const [autoExecBusy, setAutoExecBusy] = useState(false);
  const [swingBiasBusy, setSwingBiasBusy] = useState(false);
  const [telegramBusy, setTelegramBusy] = useState(false);
  const [qtyBusy, setQtyBusy] = useState(false);
  const [qtyDraft, setQtyDraft] = useState<string>('');
  // Tracks the (instrument, timeframe) tuple the qty draft is currently tied to. When the
  // component is reused for a different panel, the new server state's qty overwrites the draft
  // exactly once; subsequent polls for the same panel leave the user's in-progress edits alone.
  const draftPanelRef = useRef<string>('');

  const loadState = useCallback(async () => {
    const s = await getWtxState(instrument, timeframe);
    if (s) setState(s);
    else setState(null);
  }, [instrument, timeframe]);

  const loadSignals = useCallback(async () => {
    const s = await getWtxRecentSignals(instrument, 20, timeframe);
    setSignals(s.filter(sig => sig.timeframe === timeframe));
  }, [instrument, timeframe]);

  useEffect(() => {
    loadState();
    loadSignals();
    const id = setInterval(loadState, POLL_MS);
    return () => clearInterval(id);
  }, [loadState, loadSignals]);

  const onProfileChange = useCallback(async (next: WtxProfile) => {
    if (!state || state.activeProfile === next) return;
    setProfileBusy(true);
    try {
      const updated = await updateWtxProfile(instrument, timeframe, next);
      if (updated) setState(updated);
    } finally {
      setProfileBusy(false);
    }
  }, [instrument, timeframe, state]);

  const onToggleSwingBias = useCallback(async () => {
    if (!state) return;
    setSwingBiasBusy(true);
    try {
      const updated = await updateWtxSwingBiasFilter(instrument, timeframe, !state.swingBiasFilterEnabled);
      if (updated) setState(updated);
    } finally {
      setSwingBiasBusy(false);
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
      const updated = await updateWtxOrderQty(instrument, timeframe, parsed);
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

  // When the (instrument, timeframe) props change, blank the draft right away so a stale
  // in-progress edit from the previous panel can't be committed against the new panel during
  // the brief window before its state payload arrives.
  useEffect(() => {
    setQtyDraft('');
  }, [instrument, timeframe]);

  // Sync the qty draft from server state exactly once per panel identity, and only when the
  // state payload actually matches the props we're rendering for. Subsequent polls for the
  // same panel skip this branch so the user's in-progress typing isn't clobbered.
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
        `⚠️ Activer l'exécution auto IBKR pour ${instrument} ${timeframe} ?\n\n` +
        `Chaque signal WTX ${timeframe} (${state.activeProfile}) qui passe les filtres déclenchera un ordre RÉEL sur IBKR.\n\n` +
        `Ce réglage est propre au timeframe ${timeframe} et reste actif jusqu'à désactivation manuelle.`
      );
      if (!confirmed) return;
    }
    setAutoExecBusy(true);
    try {
      const updated = await updateWtxAutoExecution(instrument, timeframe, turningOn);
      if (updated) setState(updated);
    } finally {
      setAutoExecBusy(false);
    }
  }, [instrument, timeframe, state]);

  const onToggleTelegram = useCallback(async () => {
    if (!state) return;
    const turningOn = !state.telegramNotificationsEnabled;
    setTelegramBusy(true);
    try {
      const updated = await updateWtxTelegramNotifications(instrument, timeframe, turningOn);
      if (updated) setState(updated);
    } finally {
      setTelegramBusy(false);
    }
  }, [instrument, timeframe, state]);

  // Merge live WS signals (already filtered by TF) on top of server-side filtered history
  const merged = [
    ...liveSignals.filter(s => s.instrument === instrument && s.timeframe === timeframe),
    ...signals,
  ].filter((s, i, arr) => arr.findIndex(x => x.signalTs === s.signalTs) === i).slice(0, 20);

  const autoExecOn = state?.autoExecutionEnabled === true;
  const telegramOn = state?.telegramNotificationsEnabled === true;

  return (
    <div className={`border rounded-lg p-3 space-y-2 ${autoExecOn ? 'border-red-700/70 bg-red-950/10' : 'border-cyan-900/40 bg-zinc-900/80'}`}>
      {/* Header */}
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-cyan-300">WTX STRATEGY</span>
          <span className="text-[10px] text-zinc-500">{instrument} · {timeframe}</span>
        </div>
        <div className="flex items-center gap-2">
          {state && (
            <div className="flex items-center gap-1.5">
              <DirectionChip dir={state.currentDirection} />
              <span className="flex items-center gap-1 text-[10px]">
                <span className={`w-1.5 h-1.5 rounded-full ${state.canTrade ? 'bg-emerald-400' : 'bg-red-500'}`} />
                <span className={state.canTrade ? 'text-emerald-400' : 'text-red-400'}>
                  {state.canTrade ? 'ACTIF' : 'BLOQUÉ'}
                </span>
              </span>
            </div>
          )}
          <button
            type="button"
            onClick={() => setCollapsed(isCollapsed => !isCollapsed)}
            aria-expanded={!collapsed}
            className="rounded border border-zinc-700 px-2 py-1 text-[10px] font-semibold text-zinc-400 hover:border-cyan-700 hover:text-cyan-300 transition-colors"
          >
            {collapsed ? 'Afficher' : 'Cacher'}
          </button>
        </div>
      </div>

      {!collapsed && (
        <>
          {/* Profile + Auto-IBKR controls */}
          {state && (
            <div className="flex items-center gap-2 flex-wrap">
              <label className="flex items-center gap-1.5 text-[10px] text-zinc-400">
                <span className="text-zinc-500">Profil</span>
                <select
                  value={state.activeProfile}
                  onChange={e => onProfileChange(e.target.value as WtxProfile)}
                  disabled={profileBusy}
                  className="rounded border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[10px] text-zinc-200 hover:border-cyan-700 focus:border-cyan-600 focus:outline-none disabled:opacity-50"
                >
                  {PROFILE_OPTIONS.map(p => (
                    <option key={p} value={p}>{p.replace('_', ' ')}</option>
                  ))}
                </select>
              </label>
              <label className="flex items-center gap-1.5 text-[10px] text-zinc-400" title="Nombre de contrats envoyés à IBKR sur chaque OPEN / REVERSE (panneau spécifique)">
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
                  className="w-12 rounded border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[10px] text-zinc-200 hover:border-cyan-700 focus:border-cyan-600 focus:outline-none disabled:opacity-50"
                />
              </label>
              <button
                type="button"
                onClick={onToggleAutoExec}
                disabled={autoExecBusy}
                aria-pressed={autoExecOn}
                title={autoExecOn ? 'Désactiver le routage IBKR' : 'Activer le routage IBKR (confirmation requise)'}
                className={`rounded border px-2 py-0.5 text-[10px] font-semibold transition-colors disabled:opacity-50 ${
                  autoExecOn
                    ? 'border-red-600/70 bg-red-950/40 text-red-300 hover:bg-red-950/60'
                    : 'border-zinc-700 text-zinc-400 hover:border-emerald-700 hover:text-emerald-300'
                }`}
              >
                Auto-IBKR : {autoExecOn ? 'ON' : 'OFF'}
              </button>
              <button
                type="button"
                onClick={onToggleTelegram}
                disabled={telegramBusy}
                aria-pressed={telegramOn}
                title={telegramOn ? 'Désactiver les notifications Telegram pour ce panneau' : 'Activer les notifications Telegram pour ce panneau'}
                className={`rounded border px-2 py-0.5 text-[10px] font-semibold transition-colors disabled:opacity-50 ${
                  telegramOn
                    ? 'border-sky-600/70 bg-sky-950/40 text-sky-300 hover:bg-sky-950/60'
                    : 'border-zinc-700 text-zinc-400 hover:border-sky-700 hover:text-sky-300'
                }`}
              >
                Telegram : {telegramOn ? 'ON' : 'OFF'}
              </button>
              <SwingBiasControl
                enabled={state.swingBiasFilterEnabled}
                direction={state.currentSwingBias}
                busy={swingBiasBusy}
                onToggle={onToggleSwingBias}
              />
            </div>
          )}

          {/* P&L bar */}
          {state && (
            <div>
              <div className="flex justify-between text-[10px] text-zinc-500">
                <span>Daily P&L</span>
                <span>max -{state.maxDailyLossUsd.toFixed(0)}$</span>
              </div>
              <PnlBar pnl={state.dailyPnl} max={state.maxDailyLossUsd} />
            </div>
          )}

          {/* Open-position summary (mirrors WTX+RSI). SL = live trailing-exit stop. */}
          {state && state.currentDirection !== 'FLAT' && (
            <div className="grid grid-cols-3 gap-2 text-[10px] border border-zinc-800/60 rounded p-2 bg-zinc-950/30">
              <Stat label="Entry" value={state.entryPrice != null ? state.entryPrice.toFixed(2) : undefined} />
              <Stat label="Qty" value={state.entryQty ? String(state.entryQty) : undefined} />
              <Stat label="SL" value={state.stopLoss != null ? state.stopLoss.toFixed(2) : undefined} />
            </div>
          )}

          {/* Signals list — grouped by trading day, newest day expanded */}
          <div className="space-y-1.5">
            <span className="text-[10px] text-zinc-600 uppercase tracking-wider">Signaux récents</span>
            <DayGroupedSignals
              signals={merged}
              getTs={sig => sig.signalTs}
              getKey={sig => sig.signalTs + sig.instrument}
              renderSignal={sig => <SignalCard sig={sig} />}
              accent="cyan"
            />
          </div>
        </>
      )}
    </div>
  );
}
