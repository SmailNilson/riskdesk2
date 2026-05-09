'use client';

import { useEffect, useState, useCallback } from 'react';
import { getWtxState, getWtxRecentSignals } from '@/app/lib/api';
import type { WtxStrategyStateView, WtxSignalView, WtxEnrichmentView } from '@/app/lib/api';

const POLL_MS = 5000;

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
          {/* Order Flow — first */}
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
        {!sig.canTrade && (
          <span className="ml-auto text-rose-400 text-[9px]">BLOQUÉ</span>
        )}
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

  const loadState = useCallback(async () => {
    const s = await getWtxState(instrument);
    if (s) setState(s);
    else setState(null);
  }, [instrument]);

  const loadSignals = useCallback(async () => {
    const s = await getWtxRecentSignals(instrument, 20, timeframe);
    setSignals(s);
  }, [instrument, timeframe]);

  useEffect(() => {
    loadState();
    loadSignals();
    const id = setInterval(loadState, POLL_MS);
    return () => clearInterval(id);
  }, [loadState, loadSignals]);

  // Merge live WS signals (already filtered by TF) on top of server-side filtered history
  const merged = [
    ...liveSignals.filter(s => s.instrument === instrument && s.timeframe === timeframe),
    ...signals,
  ].filter((s, i, arr) => arr.findIndex(x => x.signalTs === s.signalTs) === i).slice(0, 20);

  return (
    <div className="border border-cyan-900/40 bg-zinc-900/80 rounded-lg p-3 space-y-2">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-cyan-300">WTX STRATEGY</span>
          <span className="text-[10px] text-zinc-500">{instrument} · {timeframe}</span>
        </div>
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
      </div>

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

      {/* Signals list */}
      <div className="space-y-1.5">
        <span className="text-[10px] text-zinc-600 uppercase tracking-wider">Signaux récents</span>
        {merged.length === 0 ? (
          <p className="text-[10px] text-zinc-600 italic">Aucun signal</p>
        ) : (
          merged.map(sig => <SignalCard key={sig.signalTs + sig.instrument} sig={sig} />)
        )}
      </div>
    </div>
  );
}
