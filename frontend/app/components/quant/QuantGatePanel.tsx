'use client';

import { useEffect, useState } from 'react';
import { api } from '@/app/lib/api';
import { useQuantStream } from '@/app/hooks/useQuantStream';
import QuantAdvisorBadge from './QuantAdvisorBadge';
import QuantNarrationPanel from './QuantNarrationPanel';
import {
  GATE_LABELS,
  LONG_GATES,
  QUANT_INSTRUMENTS,
  SHORT_GATES,
  type AdviceView,
  type QuantGateView,
  type QuantInstrument,
  type QuantSnapshotView,
  type StructuralBlockView,
  type StructuralWarningView,
} from './types';

function scoreClass(score: number): string {
  if (score >= 7) return 'bg-emerald-600 text-white';
  if (score >= 6) return 'bg-amber-500 text-white';
  if (score >= 4) return 'bg-slate-600 text-white';
  return 'bg-slate-700 text-slate-300';
}

function formatPrice(p: number | null | undefined): string {
  if (p === null || p === undefined || Number.isNaN(p)) return '—';
  return p.toFixed(2);
}

interface DirectionSectionProps {
  label: 'SHORT' | 'LONG';
  score: number;
  finalScore: number | undefined;
  blocked: boolean | undefined;
  setup7_7: boolean;
  alert6_7: boolean;
  scoreModifier: number | undefined;
  blocks: StructuralBlockView[] | undefined;
  warnings: StructuralWarningView[] | undefined;
  gates: QuantGateView[];
  entry: number | null;
  sl: number | null;
  tp1: number | null;
  tp2: number | null;
  advisorSlot?: React.ReactNode;
}

/**
 * Renders one direction's section (gates list, structural blocks/warnings,
 * suggested plan). Shared between SHORT and LONG so both sides have an
 * identical presentation.
 */
function DirectionSection(props: DirectionSectionProps) {
  const blocks = props.blocks ?? [];
  const warnings = props.warnings ?? [];
  return (
    <div>
      <div className="flex items-center gap-2 mb-2">
        <span className={`px-2 py-1 rounded text-xs font-bold ${scoreClass(props.score)}`}>
          {props.label} {props.score}/7
        </span>
        {props.scoreModifier !== undefined && props.scoreModifier !== 0 && (
          <span className="text-xs text-slate-400">
            → final{' '}
            <span className="font-mono text-slate-200">{props.finalScore ?? props.score}</span>
            <span className="font-mono ml-1">
              ({props.scoreModifier >= 0 ? '+' : ''}
              {props.scoreModifier})
            </span>
          </span>
        )}
      </div>

      <ul className="space-y-1 text-sm font-mono">
        {props.gates.map((g) => (
          <li
            key={g.gate}
            className={`flex items-start gap-2 px-2 py-1 rounded ${
              g.ok ? 'bg-emerald-950/40' : 'bg-red-950/30'
            }`}
          >
            <span className="w-5 shrink-0 text-base">{g.ok ? '✅' : '❌'}</span>
            <div className="flex-1">
              <div className="text-slate-200">{GATE_LABELS[g.gate] ?? g.gate}</div>
              <div className="text-xs text-slate-400">{g.reason}</div>
            </div>
          </li>
        ))}
      </ul>

      {blocks.length > 0 && (
        <ul className="mt-3 space-y-1 text-xs font-mono">
          {blocks.map((b) => (
            <li
              key={`block-${props.label}-${b.code}`}
              className="flex items-start gap-2 px-2 py-1 rounded bg-red-950/60 border border-red-800"
              title={b.evidence}
            >
              <span className="text-red-400">🚫</span>
              <span className="text-red-200 font-semibold">{b.code}</span>
              <span className="text-red-300/80 truncate">{b.evidence}</span>
            </li>
          ))}
        </ul>
      )}
      {warnings.length > 0 && (
        <ul className="mt-2 space-y-1 text-xs font-mono">
          {warnings.map((w, idx) => (
            <li
              key={`warn-${props.label}-${w.code}-${idx}`}
              className="flex items-start gap-2 px-2 py-1 rounded bg-amber-950/40 border border-amber-900/60"
              title={w.evidence}
            >
              <span className="text-amber-400">⚠️</span>
              <span className="text-amber-200 font-semibold w-10 shrink-0">
                {w.scoreModifier >= 0 ? `+${w.scoreModifier}` : w.scoreModifier}
              </span>
              <span className="text-amber-100">{w.code}</span>
              <span className="text-amber-300/80 truncate">{w.evidence}</span>
            </li>
          ))}
        </ul>
      )}

      {props.blocked && (
        <div className="mt-3 rounded border border-red-700 bg-red-950/40 p-3 text-sm">
          <div className="font-semibold text-red-300">
            ❌ {props.label} bloqué — {blocks.length} block{blocks.length === 1 ? '' : 's'} structurel(s)
          </div>
          <div className="text-xs text-red-200/80 mt-1">
            Le score quant est suffisant mais la structure de marché veto le {props.label}.
          </div>
        </div>
      )}

      {!props.blocked && props.score >= 6 && props.entry !== null && props.sl !== null && (
        <div className="mt-3 rounded border border-amber-700 bg-amber-950/40 p-3 text-sm">
          <div className="font-semibold mb-2 flex items-center gap-2 flex-wrap">
            {props.setup7_7 ? (
              <span className="text-emerald-400">🔔 {props.label} 7/7 — full setup</span>
            ) : (
              <span className="text-amber-400">⚠️ {props.label} 6/7 — early warning</span>
            )}
            {props.advisorSlot}
          </div>
          <div className="grid grid-cols-4 gap-2 text-xs font-mono">
            <div>
              <span className="text-slate-400">ENTRY</span>
              <br />
              {formatPrice(props.entry)}
            </div>
            <div>
              <span className="text-slate-400">SL</span>
              <br />
              {formatPrice(props.sl)}
            </div>
            <div>
              <span className="text-slate-400">TP1</span>
              <br />
              {formatPrice(props.tp1)}
            </div>
            <div>
              <span className="text-slate-400">TP2</span>
              <br />
              {formatPrice(props.tp2)}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * Live view of the Quant 7-Gates evaluator. Subscribes to the per-instrument
 * snapshot WebSocket topic and renders pass/fail for each gate (SHORT and
 * LONG side-by-side) plus the suggested plan when the corresponding score
 * reaches 6/7.
 */
export default function QuantGatePanel() {
  const [active, setActive] = useState<QuantInstrument>('MNQ');
  const { snapshots, narrations, advice: streamedAdvice, connected } = useQuantStream();
  const [bootstrap, setBootstrap] = useState<Record<string, QuantSnapshotView>>({});
  const [manualAdvice, setManualAdvice] = useState<Record<string, AdviceView>>({});
  const [askingAi, setAskingAi] = useState<Record<string, boolean>>({});

  useEffect(() => {
    let cancelled = false;
    Promise.all(
      QUANT_INSTRUMENTS.map(async (instr) => {
        try {
          const snap = await api.getQuantSnapshot(instr);
          return [instr, snap] as const;
        } catch {
          return [instr, null] as const;
        }
      })
    ).then((entries) => {
      if (cancelled) return;
      const next: Record<string, QuantSnapshotView> = {};
      for (const [instr, snap] of entries) {
        if (snap) next[instr] = snap;
      }
      setBootstrap(next);
    });
    return () => {
      cancelled = true;
    };
  }, []);

  const snapshot = snapshots[active] ?? bootstrap[active] ?? null;
  const narration = narrations[active] ?? null;
  const advice = manualAdvice[active] ?? streamedAdvice[active] ?? null;

  const askAi = async () => {
    setAskingAi((prev) => ({ ...prev, [active]: true }));
    try {
      const result = await api.askQuantAiAdvice(active);
      setManualAdvice((prev) => ({ ...prev, [active]: result }));
    } catch (err) {
      console.warn('quant Ask AI failed', err);
    } finally {
      setAskingAi((prev) => ({ ...prev, [active]: false }));
    }
  };

  const shortGates = (snapshot?.gates ?? []).filter((g) => SHORT_GATES.includes(g.gate));
  const longGates = (snapshot?.gates ?? []).filter((g) => LONG_GATES.includes(g.gate));

  return (
    <section className="rounded-lg border border-slate-700 bg-slate-900 p-4 text-slate-100">
      <header className="mb-3 flex items-center justify-between">
        <div className="flex items-center gap-2">
          <h2 className="text-lg font-semibold">Quant 7-Gates</h2>
          <span
            className={`text-xs px-2 py-0.5 rounded ${connected ? 'bg-emerald-700' : 'bg-slate-700'}`}
            title={connected ? 'WebSocket connected' : 'WebSocket disconnected'}
          >
            {connected ? 'live' : 'offline'}
          </span>
        </div>
        <div className="flex gap-1">
          {QUANT_INSTRUMENTS.map((instr) => (
            <button
              key={instr}
              type="button"
              onClick={() => setActive(instr)}
              className={`px-2 py-1 text-xs rounded ${
                instr === active
                  ? 'bg-slate-700 text-white'
                  : 'bg-slate-800 text-slate-400 hover:bg-slate-700'
              }`}
            >
              {instr}
            </button>
          ))}
        </div>
      </header>

      {snapshot ? (
        <>
          <div className="flex items-center justify-between mb-3 text-sm text-slate-300">
            <div className="flex items-center gap-3">
              <span>
                px <span className="font-mono">{formatPrice(snapshot.price)}</span>{' '}
                <span className="text-slate-500">[{snapshot.priceSource || '—'}]</span>
              </span>
              <span className="text-slate-400">
                Δjour{' '}
                <span className="font-mono">
                  {snapshot.dayMove >= 0 ? '+' : ''}
                  {snapshot.dayMove.toFixed(0)}pts
                </span>
              </span>
            </div>
            <span className="text-xs text-slate-500">{snapshot.scanTime ?? '—'}</span>
          </div>

          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
            <DirectionSection
              label="SHORT"
              score={snapshot.score}
              finalScore={snapshot.finalScore}
              blocked={snapshot.shortBlocked}
              setup7_7={snapshot.shortSetup7_7}
              alert6_7={snapshot.shortAlert6_7}
              scoreModifier={snapshot.structuralScoreModifier}
              blocks={snapshot.structuralBlocks}
              warnings={snapshot.structuralWarnings}
              gates={shortGates}
              entry={snapshot.entry}
              sl={snapshot.sl}
              tp1={snapshot.tp1}
              tp2={snapshot.tp2}
              advisorSlot={
                <>
                  <QuantAdvisorBadge advice={advice} loading={askingAi[active]} />
                  <button
                    type="button"
                    onClick={askAi}
                    disabled={askingAi[active]}
                    className="ml-auto px-2 py-0.5 text-xs rounded bg-slate-800 hover:bg-slate-700 border border-slate-600 disabled:opacity-50"
                    title="Demande un second avis IA basé sur la mémoire long-terme + multi-instrument"
                  >
                    Ask AI
                  </button>
                </>
              }
            />

            <DirectionSection
              label="LONG"
              score={snapshot.longScore}
              finalScore={snapshot.longFinalScore}
              blocked={snapshot.longBlocked}
              setup7_7={snapshot.longSetup7_7}
              alert6_7={snapshot.longAlert6_7}
              scoreModifier={snapshot.longStructuralScoreModifier}
              blocks={snapshot.longStructuralBlocks}
              warnings={snapshot.longStructuralWarnings}
              gates={longGates}
              entry={snapshot.longEntry}
              sl={snapshot.longSl}
              tp1={snapshot.longTp1}
              tp2={snapshot.longTp2}
            />
          </div>

          <details className="mt-3 text-xs">
            <summary className="cursor-pointer text-slate-400 hover:text-slate-200">
              Narration markdown {narration?.pattern ? `· ${narration.pattern.label}` : ''}
            </summary>
            <div className="mt-2 p-2 bg-slate-950 rounded border border-slate-800">
              <QuantNarrationPanel narration={narration} />
            </div>
          </details>
        </>
      ) : (
        <p className="text-sm text-slate-400">No snapshot yet. The scheduler runs every 60 seconds.</p>
      )}
    </section>
  );
}
