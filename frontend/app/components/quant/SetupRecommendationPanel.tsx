'use client';

import React from 'react';
import { useSetupStream, SetupView } from '@/app/hooks/useSetupStream';
import { QUANT_INSTRUMENTS } from './types';

const TEMPLATE_LABELS: Record<string, string> = {
  A_DAY_REVERSAL: 'A · Day Reversal',
  B_SCALP_MR:    'B · Scalp MR',
  C_NAKED_POC:   'C · Naked POC',
  D_MTF_ALIGN:   'D · MTF Align',
  E_FVG_SWEEP:   'E · FVG Sweep',
  UNKNOWN:       '—',
};

const PHASE_COLOR: Record<string, string> = {
  DETECTED:    'text-yellow-400',
  CONFIRMED:   'text-blue-400',
  TRIGGERED:   'text-purple-400',
  ACTIVE:      'text-green-400',
  CLOSED:      'text-zinc-400',
  INVALIDATED: 'text-red-400',
};

const DIR_COLOR: Record<string, string> = {
  LONG:  'text-green-400',
  SHORT: 'text-red-400',
};

function fmt(v: number | null | undefined): string {
  if (v == null) return '—';
  return v.toFixed(2);
}

function SetupCard({ setup }: { setup: SetupView }) {
  const phaseColor = PHASE_COLOR[setup.phase] ?? 'text-zinc-400';
  const dirColor   = DIR_COLOR[setup.direction] ?? 'text-zinc-300';

  return (
    <div className="rounded-lg border border-zinc-700 bg-zinc-800/60 p-3 text-xs space-y-2">
      <div className="flex items-center justify-between">
        <span className="font-semibold text-zinc-100">
          {TEMPLATE_LABELS[setup.template] ?? setup.template}
        </span>
        <div className="flex items-center gap-2">
          <span className={`font-bold ${dirColor}`}>{setup.direction}</span>
          <span className={`${phaseColor}`}>{setup.phase}</span>
        </div>
      </div>

      <div className="grid grid-cols-4 gap-1 text-zinc-300">
        <div className="flex flex-col items-center rounded bg-zinc-900/70 p-1.5">
          <span className="text-zinc-500 text-[10px] mb-0.5">ENTRY</span>
          <span className="font-mono font-semibold">{fmt(setup.entryPrice)}</span>
        </div>
        <div className="flex flex-col items-center rounded bg-zinc-900/70 p-1.5">
          <span className="text-zinc-500 text-[10px] mb-0.5">SL</span>
          <span className="font-mono font-semibold text-red-400">{fmt(setup.slPrice)}</span>
        </div>
        <div className="flex flex-col items-center rounded bg-zinc-900/70 p-1.5">
          <span className="text-zinc-500 text-[10px] mb-0.5">TP1</span>
          <span className="font-mono font-semibold text-green-400">{fmt(setup.tp1Price)}</span>
        </div>
        <div className="flex flex-col items-center rounded bg-zinc-900/70 p-1.5">
          <span className="text-zinc-500 text-[10px] mb-0.5">R:R</span>
          <span className="font-mono font-semibold text-blue-300">1:{setup.rrRatio.toFixed(1)}</span>
        </div>
      </div>

      <div className="flex items-center gap-2 text-zinc-400">
        <span className="text-zinc-500">Score</span>
        <span className="font-mono text-zinc-200">{setup.finalScore}/7</span>
        <span className="text-zinc-500 ml-2">Regime</span>
        <span className="text-zinc-300">{setup.regime}</span>
        <span className="text-zinc-500 ml-2">Style</span>
        <span className="text-zinc-300">{setup.style}</span>
      </div>
    </div>
  );
}

export default function SetupRecommendationPanel() {
  const [activeInstrument, setActiveInstrument] = React.useState<string>(
    QUANT_INSTRUMENTS[0] ?? 'MCL'
  );
  const setups = useSetupStream(activeInstrument);

  const active = setups.filter(s => s.phase !== 'CLOSED' && s.phase !== 'INVALIDATED');

  return (
    <div className="bg-zinc-900 rounded-xl border border-zinc-800 p-4 space-y-3">
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-zinc-100">Setup Recommendations</h3>
        <div className="flex gap-1">
          {QUANT_INSTRUMENTS.map(inst => (
            <button
              key={inst}
              onClick={() => setActiveInstrument(inst)}
              className={`px-2 py-0.5 rounded text-xs font-mono transition-colors ${
                inst === activeInstrument
                  ? 'bg-blue-600 text-white'
                  : 'bg-zinc-800 text-zinc-400 hover:bg-zinc-700'
              }`}
            >
              {inst}
            </button>
          ))}
        </div>
      </div>

      {active.length === 0 ? (
        <p className="text-xs text-zinc-500 py-2 text-center">
          No active setups — gates running every 60 s
        </p>
      ) : (
        <div className="space-y-2">
          {active.map(s => (
            <SetupCard key={s.id} setup={s} />
          ))}
        </div>
      )}
    </div>
  );
}
