'use client';

import { useEffect, useMemo, useState } from 'react';
import { useOrderFlow, PerfectSetupSignal } from '@/app/hooks/useOrderFlow';
import { api } from '@/app/lib/api';

// State → badge styling.
const STATE_STYLES: Record<string, { bg: string; text: string; label: string }> = {
  IDLE:        { bg: 'bg-zinc-800',        text: 'text-zinc-400',    label: 'Idle' },
  LONG_ARMED:  { bg: 'bg-emerald-900/50',  text: 'text-emerald-300', label: 'LONG armé' },
  SHORT_ARMED: { bg: 'bg-red-900/50',      text: 'text-red-300',     label: 'SHORT armé' },
  TRIGGERED:   { bg: 'bg-blue-900/50',     text: 'text-blue-300',    label: 'Déclenché' },
  INVALIDATED: { bg: 'bg-orange-900/40',   text: 'text-orange-300',  label: 'Invalidé' },
  EXPIRED:     { bg: 'bg-zinc-800',        text: 'text-zinc-500',    label: 'Expiré' },
};

function fmt(n: number | null | undefined, digits = 2): string {
  return n === null || n === undefined ? '—' : n.toFixed(digits);
}

function PerfectSetupCard({ s }: { s: PerfectSetupSignal }) {
  const style = STATE_STYLES[s.state] ?? STATE_STYLES.IDLE;
  const pct = s.maxScore > 0 ? Math.round((s.score / s.maxScore) * 100) : 0;
  const dirArrow = s.direction === 'LONG' ? '▲' : s.direction === 'SHORT' ? '▼' : '·';
  const dirColor = s.direction === 'LONG' ? 'text-emerald-400'
    : s.direction === 'SHORT' ? 'text-red-400' : 'text-zinc-500';
  const showPlan = s.state === 'LONG_ARMED' || s.state === 'SHORT_ARMED' || s.state === 'TRIGGERED';

  return (
    <div className={`flex flex-col gap-2 p-2.5 rounded border ${style.bg} border-zinc-700`}>
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-zinc-200">{s.instrument}</span>
          <span className={`text-xs font-bold ${dirColor}`}>{dirArrow} {s.direction}</span>
        </div>
        <span className={`text-[11px] font-semibold px-2 py-0.5 rounded ${style.text} ${style.bg}`}>
          {style.label}
        </span>
      </div>

      {/* Confluence score */}
      <div className="flex flex-col gap-1">
        <div className="flex items-center justify-between text-[11px]">
          <span className="text-zinc-400">Confluence</span>
          <span className="font-bold text-zinc-200">{s.score}/{s.maxScore}</span>
        </div>
        <div className="h-1.5 w-full rounded bg-zinc-700 overflow-hidden">
          <div
            className={`h-full ${s.score >= 4 ? 'bg-emerald-500' : 'bg-zinc-500'}`}
            style={{ width: `${pct}%` }}
          />
        </div>
      </div>

      {/* 6-axis checklist */}
      <div className="flex flex-col gap-0.5">
        {s.axes.map(a => (
          <div key={a.axis} className="flex items-start gap-1.5 text-[11px]">
            <span className={a.passed ? 'text-emerald-400' : 'text-zinc-600'}>
              {a.passed ? '✅' : '⬜'}
            </span>
            <span className="text-zinc-300 shrink-0">{a.label}</span>
            <span className="text-zinc-500 truncate">— {a.detail}</span>
          </div>
        ))}
      </div>

      {/* Trade plan */}
      {showPlan && (
        <div className="grid grid-cols-2 gap-x-3 gap-y-0.5 text-[11px] border-t border-zinc-700 pt-1.5">
          <PlanRow label="Entrée" value={`${fmt(s.entryLow)}–${fmt(s.entryHigh)}`} />
          <PlanRow label="R:R" value={fmt(s.riskReward, 1)} highlight />
          <PlanRow label="SL" value={fmt(s.stop)} className="text-red-400" />
          <PlanRow label="TP1" value={fmt(s.tp1)} className="text-emerald-400" />
          <PlanRow label="TP2" value={fmt(s.tp2)} className="text-emerald-400" />
        </div>
      )}

      {s.reasoning && (
        <p className="text-[10px] text-zinc-500 leading-snug">{s.reasoning}</p>
      )}
    </div>
  );
}

function PlanRow({ label, value, className, highlight }:
  { label: string; value: string; className?: string; highlight?: boolean }) {
  return (
    <div className="flex items-center justify-between">
      <span className="text-zinc-500">{label}</span>
      <span className={`font-mono ${highlight ? 'font-bold text-zinc-100' : className ?? 'text-zinc-300'}`}>
        {value}
      </span>
    </div>
  );
}

export default function PerfectSetupPanel() {
  const { perfectSetups, connected } = useOrderFlow();
  const [seed, setSeed] = useState<Map<string, PerfectSetupSignal>>(new Map());

  // REST seed on mount — the WebSocket only pushes forward-going updates.
  useEffect(() => {
    let cancelled = false;
    api.getPerfectSetups()
      .then(list => {
        if (cancelled || !Array.isArray(list)) return;
        const m = new Map<string, PerfectSetupSignal>();
        list.forEach(v => m.set(v.instrument, v as PerfectSetupSignal));
        setSeed(m);
      })
      .catch(() => { /* panel degrades to live-only */ });
    return () => { cancelled = true; };
  }, []);

  // Merge REST seed with live WS pushes (WS takes precedence).
  const merged = useMemo(() => {
    const m = new Map(seed);
    perfectSetups.forEach((v, k) => m.set(k, v));
    return Array.from(m.values()).sort((a, b) => a.instrument.localeCompare(b.instrument));
  }, [seed, perfectSetups]);

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-zinc-200">Perfect Setup</h3>
        {!connected && <span className="text-[10px] text-zinc-500 italic">déconnecté</span>}
      </div>
      {merged.length === 0 ? (
        <p className="text-xs text-zinc-500 italic">Aucun setup évalué pour le moment</p>
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-2">
          {merged.map(s => <PerfectSetupCard key={s.instrument} s={s} />)}
        </div>
      )}
    </div>
  );
}
