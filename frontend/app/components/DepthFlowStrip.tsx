'use client';

import { DepthFlowMetrics } from '@/app/hooks/useOrderFlow';

interface DepthFlowStripProps {
  instrument: string;
  metrics?: DepthFlowMetrics;
}

/** Signed OFI z-score bar: centered at 0, clipped at ±4. Amber |z|≥2, red |z|≥3. */
function OfiZBar({ z }: { z: number }) {
  const clipped = Math.max(-4, Math.min(4, z));
  const pct = (Math.abs(clipped) / 4) * 50; // half-width from center
  const color =
    Math.abs(z) >= 3 ? 'bg-red-500' : Math.abs(z) >= 2 ? 'bg-amber-400' : 'bg-zinc-500';
  const textColor =
    Math.abs(z) >= 3 ? 'text-red-400' : Math.abs(z) >= 2 ? 'text-amber-400' : 'text-zinc-400';
  return (
    <div
      className="flex items-center gap-1.5"
      title={`OFI (Cont-Kukanov-Stoikov) — z-score du flux 10s vs distribution 5 min. Gauge de TIMING d'entrée (le résultat canonique est contemporain, ~65% R² sur 10s), pas un signal autonome. Amber |z|≥2, rouge |z|≥3.`}
    >
      <span className="text-zinc-600">OFI</span>
      <div className="relative w-16 h-2.5 bg-zinc-800 rounded-sm overflow-hidden">
        <div className="absolute left-1/2 top-0 bottom-0 w-px bg-zinc-600" />
        <div
          className={`absolute top-0 bottom-0 ${color} opacity-80`}
          style={
            clipped >= 0
              ? { left: '50%', width: `${pct}%` }
              : { right: '50%', width: `${pct}%` }
          }
        />
      </div>
      <span className={`font-semibold ${textColor}`}>
        z{z >= 0 ? '+' : ''}{z.toFixed(1)}
      </span>
    </div>
  );
}

/** Queue-imbalance lean: ▲/▼ with lean ≥0.4 / strong ≥0.6, grey under min queue mass. */
function QueueLean({ value, valid }: { value: number; valid: boolean }) {
  const abs = Math.abs(value);
  const strong = abs >= 0.6;
  const lean = abs >= 0.4;
  const arrow = value >= 0 ? '▲' : '▼';
  const color = !valid
    ? 'text-zinc-600'
    : !lean
      ? 'text-zinc-400'
      : value >= 0
        ? 'text-emerald-400'
        : 'text-red-400';
  return (
    <div
      className="flex items-center gap-1"
      title={
        valid
          ? `Queue imbalance (best level, EMA ~3s): I=${value.toFixed(2)} — ${strong ? 'lean fort' : lean ? 'lean' : 'neutre'}`
          : 'Queue imbalance non significative — masse au best level sous le seuil min'
      }
    >
      <span className="text-zinc-600">Queue</span>
      <span className={`font-semibold ${color}`}>
        {valid ? `${arrow} ${value.toFixed(2)}${strong ? ' fort' : lean ? ' lean' : ''}` : '—'}
      </span>
    </div>
  );
}

function VacuumChip({ state }: { state: DepthFlowMetrics['vacuumState'] }) {
  const cls =
    state === 'NORMAL'
      ? 'bg-zinc-800 text-zinc-400'
      : state === 'THIN'
        ? 'bg-amber-900/60 text-amber-300'
        : 'bg-red-900/60 text-red-300';
  const label = state === 'VACUUM_BID' ? 'VACUUM BID' : state === 'VACUUM_ASK' ? 'VACUUM ASK' : state;
  const title =
    state === 'NORMAL'
      ? 'Profondeur des deux côtés proche de sa baseline 5 min'
      : state === 'THIN'
        ? 'Les deux côtés < 50% de leur baseline — retrait global de liquidité'
        : state === 'VACUUM_BID'
          ? 'Bid < 40% de sa baseline depuis ≥3s, asks tiennent — vide sous le prix'
          : 'Ask < 40% de sa baseline depuis ≥3s, bids tiennent — vide au-dessus du prix';
  return (
    <span className={`px-1.5 py-0.5 rounded text-[9px] font-semibold ${cls}`} title={title}>
      {label}
    </span>
  );
}

/**
 * Compact one-row readout of the continuous depth-flow signals (/topic/depth-flow):
 * OFI z-score, queue-imbalance lean, micro-price offset, vacuum state and the
 * pull/stack net. Renders nothing actionable until the backend analyzer is warm.
 */
export default function DepthFlowStrip({ instrument, metrics }: DepthFlowStripProps) {
  if (!metrics) {
    return (
      <div className="flex items-center gap-2 px-2 py-1.5 rounded bg-zinc-800/40 text-[10px] text-zinc-600 italic">
        Depth flow — waiting for {instrument} snapshots…
      </div>
    );
  }

  const net =
    (metrics.bidStacked10s - metrics.bidPulled10s) -
    (metrics.askStacked10s - metrics.askPulled10s);
  const netColor = net > 0 ? 'text-emerald-400' : net < 0 ? 'text-red-400' : 'text-zinc-400';
  const offset = metrics.microPriceOffsetTicks;

  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 px-2 py-1.5 rounded bg-zinc-800/60 text-[10px] font-mono">
      <OfiZBar z={metrics.ofiZ10s} />

      <QueueLean value={metrics.queueImbalance} valid={metrics.queueImbalanceValid} />

      <div
        className="flex items-center gap-1"
        title="Micro-price (Stoikov) moins le mid, en ticks. Positif = le prix « juste » penche vers l'ask."
      >
        <span className="text-zinc-600">µP</span>
        <span className={offset >= 0.05 ? 'text-emerald-400' : offset <= -0.05 ? 'text-red-400' : 'text-zinc-400'}>
          {offset >= 0 ? '+' : ''}{offset.toFixed(1)}t
        </span>
      </div>

      <VacuumChip state={metrics.vacuumState} />

      <div
        className="flex items-center gap-1"
        title={`Pull/stack net 10s (approximation : variations de taille au repos, sans attribution des trades) — bids: +${metrics.bidStacked10s}/-${metrics.bidPulled10s}, asks: +${metrics.askStacked10s}/-${metrics.askPulled10s}`}
      >
        <span className="text-zinc-600">P/S</span>
        <span className={`font-semibold ${netColor}`}>
          {net >= 0 ? '+' : ''}{net}
        </span>
      </div>
    </div>
  );
}
