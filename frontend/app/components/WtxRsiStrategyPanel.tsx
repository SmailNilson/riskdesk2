'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  getWtxRsiState,
  getWtxRsiRecentSignals,
  updateWtxRsiAutoExecution,
  updateWtxRsiSwingBiasFilter,
  updateWtxRsiChaikinRequired,
  updateWtxRsiOrderQty,
} from '@/app/lib/api';
import DayGroupedSignals from '@/app/components/strategy/DayGroupedSignals';
import type {
  WtxRsiAction,
  WtxRsiBiasSource,
  WtxRsiPosition,
  WtxRsiSignalView,
  WtxRsiStrategyStateView,
  WtxRsiSwingBias,
  WtxRoutingOutcome,
} from '@/app/lib/api';

const POLL_MS = 5000;

interface Props {
  instrument: string;
  timeframe: string;
  liveSignals: WtxRsiSignalView[];
  liveState?: WtxRsiStrategyStateView | null;
}

function PositionChip({ pos }: { pos: WtxRsiPosition }) {
  const style =
    pos === 'LONG'  ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60' :
    pos === 'SHORT' ? 'bg-red-950/70 text-red-300 border-red-800/60' :
                      'bg-zinc-800 text-zinc-400 border-zinc-700';
  return <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded border ${style}`}>{pos}</span>;
}

function BiasBadge({ bias }: { bias: WtxRsiSwingBias | null }) {
  if (!bias) return <span className="text-[10px] text-zinc-500">—</span>;
  const style =
    bias === 'BULLISH' ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60' :
    bias === 'BEARISH' ? 'bg-red-950/70 text-red-300 border-red-800/60' :
                         'bg-zinc-800 text-zinc-400 border-zinc-700';
  const icon = bias === 'BULLISH' ? '▲' : bias === 'BEARISH' ? '▼' : '·';
  return (
    <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded border ${style}`}>
      {icon} {bias}
    </span>
  );
}

function BiasSourceChip({ source }: { source: WtxRsiBiasSource | null | undefined }) {
  if (!source) return null;
  const label = source === 'SMC_ENGINE' ? 'SMC' : 'FRACTAL';
  const tip = source === 'SMC_ENGINE'
    ? 'Bias issu du moteur SMC (BOS/CHoCH, partagé avec WTx). Configuré via riskdesk.wtxrsi.bias-source.'
    : 'Bias dérivé des Williams fractals (HH+HL / LH+LL). Configuré via riskdesk.wtxrsi.bias-source.';
  return (
    <span
      title={tip}
      className="text-[9px] font-mono px-1.5 py-0.5 rounded border border-zinc-700 text-zinc-400"
    >
      bias: {label}
    </span>
  );
}

function ActionChip({ action }: { action: WtxRsiAction }) {
  if (action === 'NONE') {
    return <span className="text-[9px] px-1.5 py-0.5 rounded border border-zinc-700 text-zinc-500">SKIP</span>;
  }
  const isLong = action.endsWith('LONG');
  const isOpen = action.startsWith('OPEN');
  const style = isLong
    ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60'
    : 'bg-red-950/70 text-red-300 border-red-800/60';
  return (
    <span className={`text-[9px] font-semibold px-1.5 py-0.5 rounded border ${style}`}>
      {isOpen ? 'OPEN' : 'CLOSE'} {isLong ? 'L' : 'S'}
    </span>
  );
}

function RoutingChip({
  outcome,
  errorMessage,
}: {
  outcome: WtxRoutingOutcome | null;
  errorMessage?: string | null;
}) {
  if (!outcome) return null;
  const isFlattenOnly = outcome === 'ROUTED_FLATTEN_ONLY';
  const isMargin = outcome === 'SKIPPED_INSUFFICIENT_MARGIN';
  const isFail = outcome === 'FAILED' || outcome === 'FAILED_BROKER_REJECT' || outcome === 'FAILED_TIMEOUT';
  const style =
    outcome === 'ROUTED'       ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60' :
    isFlattenOnly              ? 'bg-teal-950/70 text-teal-300 border-teal-800/60' :
    outcome === 'ACK_PENDING'  ? 'bg-cyan-950/70 text-cyan-300 border-cyan-800/60' :
    isMargin                   ? 'bg-orange-950/70 text-orange-300 border-orange-800/60' :
    isFail                     ? 'bg-red-950/70 text-red-300 border-red-800/60' :
                                 'bg-amber-950/70 text-amber-300 border-amber-800/60';
  const label =
    outcome === 'ROUTED'              ? 'IBKR ✓' :
    isFlattenOnly                     ? 'FLAT ✓' :
    outcome === 'ACK_PENDING'         ? 'ACK ?' :
    outcome === 'SKIPPED_AUTO_OFF'    ? 'AUTO OFF' :
    outcome === 'SKIPPED_BRIDGE_UNAVAILABLE' ? 'NO BRIDGE' :
    isMargin                          ? 'NO MARGIN' :
    outcome === 'FAILED_TIMEOUT'      ? 'TIMEOUT' :
    outcome === 'FAILED_BROKER_REJECT' ? 'REJECT' :
                                        outcome.replace(/^SKIPPED_/, '').replace(/_/g, ' ');
  const tooltip = errorMessage
    ? `Routage IBKR : ${outcome}\n${errorMessage}`
    : `Routage IBKR : ${outcome}`;
  return (
    <span title={tooltip} className={`text-[9px] font-semibold px-1.5 py-0.5 rounded border ${style}`}>
      {label}
    </span>
  );
}

function SignalCard({ sig }: { sig: WtxRsiSignalView }) {
  const ts = new Date(sig.signalTs);
  const timeStr = `${ts.getHours().toString().padStart(2, '0')}:${ts.getMinutes().toString().padStart(2, '0')}`;
  return (
    <div className="border border-zinc-800/60 rounded p-2 space-y-1">
      <div className="flex items-center gap-1.5 flex-wrap">
        <ActionChip action={sig.action} />
        {sig.chaikinConfirmed && (
          <span title="Chaikin oscillator a confirmé la direction → qty × multiplier" className="text-[9px] px-1.5 py-0.5 rounded border border-cyan-700/60 text-cyan-300">×2</span>
        )}
        <span className="text-[10px] text-zinc-400">{sig.instrument} {sig.timeframe}</span>
        <span className="text-[10px] text-zinc-600 ml-auto">{timeStr}</span>
      </div>
      <div className="grid grid-cols-4 gap-2 text-[10px]">
        <Stat label="WT1" value={sig.wt1?.toFixed(2)} />
        <Stat label="RSI" value={sig.rsi?.toFixed(1)} />
        <Stat label="Entry" value={sig.entryPrice?.toFixed(2)} />
        <Stat label="SL" value={sig.stopLoss?.toFixed(2)} />
      </div>
      <div className="flex items-center gap-1.5">
        <RoutingChip outcome={sig.routingOutcome} errorMessage={sig.routingErrorMessage} />
        {sig.contracts > 0 && (
          <span className="text-[10px] text-zinc-500">×{sig.contracts}</span>
        )}
      </div>
    </div>
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

export default function WtxRsiStrategyPanel({
  instrument, timeframe, liveSignals, liveState,
}: Props) {
  const [state, setState] = useState<WtxRsiStrategyStateView | null>(null);
  const [signals, setSignals] = useState<WtxRsiSignalView[]>([]);
  const [collapsed, setCollapsed] = useState(false);
  const [autoExecBusy, setAutoExecBusy] = useState(false);
  const [swingBiasBusy, setSwingBiasBusy] = useState(false);
  const [chaikinBusy, setChaikinBusy] = useState(false);
  const [qtyBusy, setQtyBusy] = useState(false);
  const [qtyDraft, setQtyDraft] = useState<string>('');
  const draftPanelRef = useRef<string>('');

  const loadState = useCallback(async () => {
    const s = await getWtxRsiState(instrument, timeframe);
    setState(s);
  }, [instrument, timeframe]);

  const loadSignals = useCallback(async () => {
    const s = await getWtxRsiRecentSignals(instrument, 20, timeframe);
    setSignals(s);
  }, [instrument, timeframe]);

  useEffect(() => {
    loadState();
    loadSignals();
    const id = setInterval(loadState, POLL_MS);
    return () => clearInterval(id);
  }, [loadState, loadSignals]);

  // Live state from WebSocket trumps the polled REST snapshot when both available.
  useEffect(() => {
    if (liveState && liveState.instrument === instrument && liveState.timeframe === timeframe) {
      setState(liveState);
    }
  }, [liveState, instrument, timeframe]);

  // Qty draft synchronisation — same pattern as WtxStrategyPanel.
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
        `⚠️ Activer l'exécution auto IBKR pour ${instrument} ${timeframe} (WTX+RSI) ?\n\n` +
        `Chaque signal WTX+RSI ${timeframe} qui passe les filtres déclenchera un ordre RÉEL sur IBKR.\n\n` +
        `Ce réglage est propre au timeframe ${timeframe} et reste actif jusqu'à désactivation manuelle.`,
      );
      if (!confirmed) return;
    }
    setAutoExecBusy(true);
    try {
      const updated = await updateWtxRsiAutoExecution(instrument, timeframe, turningOn);
      if (updated) setState(updated);
    } finally {
      setAutoExecBusy(false);
    }
  }, [instrument, timeframe, state]);

  const onToggleSwingBias = useCallback(async () => {
    if (!state) return;
    setSwingBiasBusy(true);
    try {
      const updated = await updateWtxRsiSwingBiasFilter(
        instrument, timeframe, !state.swingBiasFilterEnabled,
      );
      if (updated) setState(updated);
    } finally {
      setSwingBiasBusy(false);
    }
  }, [instrument, timeframe, state]);

  const onToggleChaikinRequired = useCallback(async () => {
    if (!state) return;
    setChaikinBusy(true);
    try {
      const updated = await updateWtxRsiChaikinRequired(
        instrument, timeframe, !state.chaikinRequired,
      );
      if (updated) setState(updated);
    } finally {
      setChaikinBusy(false);
    }
  }, [instrument, timeframe, state]);

  const commitQty = useCallback(async () => {
    if (!state || qtyBusy) return;
    const trimmed = qtyDraft.trim();
    if (trimmed === '') { setQtyDraft(String(state.configuredOrderQty)); return; }
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
      const updated = await updateWtxRsiOrderQty(instrument, timeframe, parsed);
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

  // Merge live WS signals with the polled history.
  const mergedSignals = [
    ...liveSignals.filter(s => s.instrument === instrument && s.timeframe === timeframe),
    ...signals,
  ]
    .filter((s, i, arr) => arr.findIndex(x => x.signalTs === s.signalTs && x.action === s.action) === i)
    .slice(0, 20);

  const autoExecOn = state?.autoExecutionEnabled === true;

  return (
    <div
      data-testid="wtxrsi-panel"
      className={`border rounded-lg p-3 space-y-2 ${
        autoExecOn ? 'border-red-700/70 bg-red-950/10' : 'border-fuchsia-900/40 bg-zinc-900/80'
      }`}
    >
      {/* Header */}
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-fuchsia-300">WTX + RSI</span>
          <span className="text-[10px] text-zinc-500">{instrument} · {timeframe}</span>
          <BiasSourceChip source={state?.biasSource} />
        </div>
        <div className="flex items-center gap-2">
          {state && (
            <div className="flex items-center gap-1.5">
              <PositionChip pos={state.currentPosition} />
              <BiasBadge bias={state.lastSwingBias} />
            </div>
          )}
          <button
            type="button"
            onClick={() => setCollapsed(isCollapsed => !isCollapsed)}
            aria-expanded={!collapsed}
            className="rounded border border-zinc-700 px-2 py-1 text-[10px] font-semibold text-zinc-400 hover:border-fuchsia-700 hover:text-fuchsia-300 transition-colors"
          >
            {collapsed ? 'Afficher' : 'Cacher'}
          </button>
        </div>
      </div>

      {!collapsed && (
        <>
          {/* Toggles */}
          {state && (
            <div className="flex items-center gap-2 flex-wrap">
              <label className="flex items-center gap-1.5 text-[10px] text-zinc-400" title="Nombre de contrats par défaut envoyés à IBKR sur chaque OPEN. Doublé si Chaikin confirme.">
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
                    if (e.key === 'Enter') { e.preventDefault(); (e.target as HTMLInputElement).blur(); }
                    if (e.key === 'Escape') {
                      setQtyDraft(String(state.configuredOrderQty));
                      (e.target as HTMLInputElement).blur();
                    }
                  }}
                  disabled={qtyBusy}
                  className="w-12 rounded border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[10px] text-zinc-200 hover:border-fuchsia-700 focus:border-fuchsia-600 focus:outline-none disabled:opacity-50"
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
                onClick={onToggleSwingBias}
                disabled={swingBiasBusy}
                aria-pressed={state.swingBiasFilterEnabled}
                title={
                  state.swingBiasFilterEnabled
                    ? `Filtre swing-bias actif — seuls les trades alignés avec ${state.lastSwingBias ?? '—'} sont autorisés.`
                    : `Filtre swing-bias inactif — toutes les entrées WTX+RSI sont autorisées. Bias courant : ${state.lastSwingBias ?? '—'}.`
                }
                className={`flex items-center gap-1.5 rounded border px-2 py-0.5 text-[10px] font-semibold transition-colors disabled:opacity-50 ${
                  state.swingBiasFilterEnabled
                    ? 'border-cyan-600/70 bg-cyan-950/40 text-cyan-200 hover:bg-cyan-950/60'
                    : 'border-zinc-700 text-zinc-400 hover:border-cyan-700 hover:text-cyan-300'
                }`}
              >
                <span>Swing-bias : {state.swingBiasFilterEnabled ? 'ON' : 'OFF'}</span>
              </button>
              <button
                type="button"
                onClick={onToggleChaikinRequired}
                disabled={chaikinBusy}
                aria-pressed={state.chaikinRequired}
                title={
                  state.chaikinRequired
                    ? 'Gate Chaikin actif — seuls les signaux confirmés par le Chaikin oscillator peuvent OUVRIR. Les sorties (reversal / SL / TP) ne sont pas affectées.'
                    : 'Gate Chaikin inactif — tous les signaux qualifiés peuvent ouvrir (le Chaikin ne fait que doubler la taille quand il confirme).'
                }
                className={`flex items-center gap-1.5 rounded border px-2 py-0.5 text-[10px] font-semibold transition-colors disabled:opacity-50 ${
                  state.chaikinRequired
                    ? 'border-fuchsia-600/70 bg-fuchsia-950/40 text-fuchsia-200 hover:bg-fuchsia-950/60'
                    : 'border-zinc-700 text-zinc-400 hover:border-fuchsia-700 hover:text-fuchsia-300'
                }`}
              >
                <span>Chaikin-req : {state.chaikinRequired ? 'ON' : 'OFF'}</span>
              </button>
            </div>
          )}

          {/* Position summary */}
          {state && state.currentPosition !== 'FLAT' && (
            <div className="grid grid-cols-4 gap-2 text-[10px] border border-zinc-800/60 rounded p-2 bg-zinc-950/30">
              <Stat label="Entry" value={state.entryPrice?.toFixed(2)} />
              <Stat label="Qty" value={state.entryQty.toString()} />
              <Stat label="SL" value={state.stopLoss?.toFixed(2)} />
              <Stat label="TP" value={state.takeProfit?.toFixed(2)} />
            </div>
          )}

          {/* Cumulative realized P&L */}
          {state && (
            <div className="flex justify-between text-[10px]">
              <span className="text-zinc-500">P&L cumulé (réalisé)</span>
              <span
                className={`font-mono ${
                  state.cumulativeRealizedPnl > 0 ? 'text-emerald-300' :
                  state.cumulativeRealizedPnl < 0 ? 'text-red-300' : 'text-zinc-400'
                }`}
              >
                {state.cumulativeRealizedPnl > 0 ? '+' : ''}
                {state.cumulativeRealizedPnl.toFixed(2)}$
              </span>
            </div>
          )}

          {/* Recent signals — grouped by trading day, newest day expanded */}
          <div className="space-y-1.5">
            <span className="text-[10px] text-zinc-600 uppercase tracking-wider">Signaux récents</span>
            <DayGroupedSignals
              signals={mergedSignals}
              getTs={sig => sig.signalTs}
              getKey={sig => sig.signalTs + sig.action}
              renderSignal={sig => <SignalCard sig={sig} />}
              accent="fuchsia"
            />
          </div>
        </>
      )}
    </div>
  );
}
