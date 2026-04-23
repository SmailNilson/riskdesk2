'use client';

/**
 * StrategyPanel — visualizes the new probabilistic strategy engine decision.
 *
 * Coexists with {@link PlaybookPanel} (legacy 7/7 checklist). Shows:
 *   - Selected playbook candidate (LSAR / SBDR / none)
 *   - Gauge for finalScore on [-100, +100]
 *   - Three layer-score bars (CONTEXT 50%, ZONE 30%, TRIGGER 20%)
 *   - Agent vote table with evidence + abstain/veto markers
 *   - Mechanical plan (entry/SL/TP) when decision is not NO_TRADE
 *
 * No side effects — the endpoint is read-only, the panel only displays.
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  api,
  ComparisonAgreement,
  DecisionComparisonView,
  StrategyAgentVote,
  StrategyDecisionView,
  StrategyLayer,
  DecisionType,
} from '../lib/api';

interface StrategyPanelProps {
  instrument: string;
  timeframe: string;
}

const POLL_INTERVAL_MS = 15_000;

const LAYER_ORDER: StrategyLayer[] = ['CONTEXT', 'ZONE', 'TRIGGER'];
const LAYER_WEIGHTS: Record<StrategyLayer, number> = {
  CONTEXT: 0.5,
  ZONE: 0.3,
  TRIGGER: 0.2,
};

function decisionColor(d: DecisionType): string {
  switch (d) {
    case 'FULL_SIZE':  return 'bg-emerald-600 text-white';
    case 'HALF_SIZE':  return 'bg-emerald-500 text-white';
    case 'PAPER_TRADE': return 'bg-amber-500 text-white';
    case 'MONITORING': return 'bg-sky-500 text-white';
    case 'NO_TRADE':   return 'bg-zinc-600 text-white';
  }
}

function scoreColor(score: number): string {
  if (score >= 70) return 'text-emerald-500';
  if (score >= 30) return 'text-emerald-400';
  if (score <= -70) return 'text-rose-500';
  if (score <= -30) return 'text-rose-400';
  return 'text-zinc-400';
}

function formatScore(score: number): string {
  const sign = score > 0 ? '+' : '';
  return `${sign}${score.toFixed(1)}`;
}

/** Maps a score on [-100, +100] to a 0..100 gauge position. */
function gaugePct(score: number): number {
  return Math.max(0, Math.min(100, (score + 100) / 2));
}

export default function StrategyPanel({ instrument, timeframe }: StrategyPanelProps) {
  const [decision, setDecision] = useState<StrategyDecisionView | null>(null);
  const [comparison, setComparison] = useState<DecisionComparisonView | null>(null);
  const [showCompare, setShowCompare] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const fetchDecision = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      if (showCompare) {
        const result = await api.getStrategyComparison(instrument, timeframe);
        setComparison(result);
        setDecision(result.strategyDecision);
      } else {
        const result = await api.getStrategyDecision(instrument, timeframe);
        setDecision(result);
      }
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load strategy');
    } finally {
      setLoading(false);
    }
  }, [instrument, timeframe, showCompare]);

  useEffect(() => {
    fetchDecision();
    const id = setInterval(fetchDecision, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [fetchDecision]);

  // Separate abstain/veto/active votes for readability
  const voteGroups = useMemo(() => {
    if (!decision) return { active: [], abstained: [], vetoed: [] };
    const active: StrategyAgentVote[] = [];
    const abstained: StrategyAgentVote[] = [];
    const vetoed: StrategyAgentVote[] = [];
    for (const v of decision.votes) {
      if (v.vetoReason) vetoed.push(v);
      else if (v.abstain) abstained.push(v);
      else active.push(v);
    }
    return { active, abstained, vetoed };
  }, [decision]);

  if (error) {
    return (
      <div className="p-4 text-rose-400 text-sm">
        Error loading strategy engine: {error}
      </div>
    );
  }

  if (!decision) {
    return (
      <div className="p-4 text-zinc-400 text-sm">
        {loading ? 'Loading strategy…' : 'No decision yet'}
      </div>
    );
  }

  return (
    <div className="p-4 space-y-4 text-sm">
      {/* Header: playbook candidate + decision bucket + final score + compare toggle */}
      <div className="flex items-center gap-3 flex-wrap">
        <div className="font-mono text-xs uppercase tracking-widest text-zinc-400">
          {decision.candidatePlaybookId ?? 'NO PLAYBOOK'}
        </div>
        <span className={`px-2 py-0.5 rounded text-xs font-semibold ${decisionColor(decision.decision)}`}>
          {decision.decision}
        </span>
        <span className={`font-mono text-lg font-bold ${scoreColor(decision.finalScore)}`}>
          {formatScore(decision.finalScore)}
        </span>
        {decision.direction && (
          <span className={`font-mono text-xs px-2 py-0.5 rounded border ${
            decision.direction === 'LONG'
              ? 'border-emerald-500 text-emerald-400'
              : 'border-rose-500 text-rose-400'
          }`}>
            {decision.direction}
          </span>
        )}
        <button
          type="button"
          onClick={() => setShowCompare(v => !v)}
          className={`ml-auto text-[10px] uppercase tracking-wider px-2 py-0.5 rounded border ${
            showCompare
              ? 'border-sky-500 text-sky-400 bg-sky-950/40'
              : 'border-zinc-600 text-zinc-400 hover:text-zinc-200'
          }`}
        >
          {showCompare ? 'Hide A/B' : 'Compare vs legacy'}
        </button>
      </div>

      {/* Comparison summary bar */}
      {showCompare && comparison && (
        <ComparisonBanner comparison={comparison} />
      )}

      {/* Gauge -100 .. +100 */}
      <div>
        <div className="relative h-2 bg-zinc-800 rounded overflow-hidden">
          <div className="absolute inset-y-0 left-1/2 w-px bg-zinc-600" />
          <div
            className={`absolute inset-y-0 ${decision.finalScore >= 0 ? 'bg-emerald-500' : 'bg-rose-500'}`}
            style={{
              left: decision.finalScore >= 0 ? '50%' : `${gaugePct(decision.finalScore)}%`,
              width: `${Math.abs(decision.finalScore) / 2}%`,
            }}
          />
        </div>
        <div className="flex justify-between text-[10px] text-zinc-500 mt-1">
          <span>-100</span><span>0</span><span>+100</span>
        </div>
      </div>

      {/* Layer scores */}
      <div className="space-y-1">
        {LAYER_ORDER.map((layer) => {
          const score = decision.layerScores[layer] ?? 0;
          const weight = LAYER_WEIGHTS[layer];
          return (
            <div key={layer} className="flex items-center gap-2">
              <span className="w-20 text-xs text-zinc-400">{layer}</span>
              <span className="w-10 text-[10px] text-zinc-500">×{weight.toFixed(2)}</span>
              <div className="flex-1 h-1.5 bg-zinc-800 rounded overflow-hidden relative">
                <div className="absolute inset-y-0 left-1/2 w-px bg-zinc-600" />
                <div
                  className={`absolute inset-y-0 ${score >= 0 ? 'bg-emerald-400/70' : 'bg-rose-400/70'}`}
                  style={{
                    left: score >= 0 ? '50%' : `${gaugePct(score)}%`,
                    width: `${Math.abs(score) / 2}%`,
                  }}
                />
              </div>
              <span className={`w-14 text-right font-mono text-xs ${scoreColor(score)}`}>
                {formatScore(score)}
              </span>
            </div>
          );
        })}
      </div>

      {/* Veto reasons (red block) */}
      {decision.vetoReasons.length > 0 && (
        <div className="border border-rose-600 bg-rose-950/40 rounded p-2">
          <div className="text-xs font-semibold text-rose-400 uppercase mb-1">Veto</div>
          <ul className="text-xs text-rose-300 space-y-0.5">
            {decision.vetoReasons.map((r, i) => <li key={i}>• {r}</li>)}
          </ul>
        </div>
      )}

      {/* Mechanical plan */}
      {decision.plan && (
        <div className="border border-zinc-700 rounded p-3 space-y-1 font-mono text-xs">
          <div className="text-zinc-400 uppercase text-[10px] tracking-widest">Mechanical plan</div>
          <div className="grid grid-cols-2 gap-x-4 gap-y-1">
            <div className="text-zinc-500">Entry</div><div>{decision.plan.entry}</div>
            <div className="text-zinc-500">Stop</div><div className="text-rose-400">{decision.plan.stopLoss}</div>
            <div className="text-zinc-500">TP1</div><div className="text-emerald-400">{decision.plan.takeProfit1}</div>
            <div className="text-zinc-500">TP2</div><div className="text-emerald-400">{decision.plan.takeProfit2}</div>
            <div className="text-zinc-500">R:R</div><div>{decision.plan.rrRatio.toFixed(2)}</div>
          </div>
        </div>
      )}

      {/* Agent votes */}
      <div>
        <div className="text-xs font-semibold text-zinc-300 uppercase mb-1">
          Agent votes ({voteGroups.active.length} active, {voteGroups.abstained.length} abstained
          {voteGroups.vetoed.length > 0 ? `, ${voteGroups.vetoed.length} vetoed` : ''})
        </div>
        <div className="space-y-1">
          {[...voteGroups.vetoed, ...voteGroups.active].map((v) => (
            <VoteRow key={v.agentId} vote={v} />
          ))}
        </div>
        {voteGroups.abstained.length > 0 && (
          <details className="mt-2">
            <summary className="text-[10px] text-zinc-500 cursor-pointer">
              Abstained agents ({voteGroups.abstained.length})
            </summary>
            <div className="mt-1 space-y-1 opacity-60">
              {voteGroups.abstained.map((v) => <VoteRow key={v.agentId} vote={v} />)}
            </div>
          </details>
        )}
      </div>

      <div className="text-[10px] text-zinc-500">
        Evaluated at {new Date(decision.evaluatedAt).toLocaleTimeString()}
      </div>
    </div>
  );
}

function agreementTone(a: ComparisonAgreement): string {
  switch (a) {
    case 'BOTH_TRADEABLE_SAME_DIRECTION':     return 'bg-emerald-600 text-white';
    case 'BOTH_TRADEABLE_OPPOSITE_DIRECTION': return 'bg-rose-600 text-white';
    case 'LEGACY_ONLY_TRADEABLE':             return 'bg-amber-600 text-white';
    case 'NEW_ONLY_TRADEABLE':                return 'bg-sky-600 text-white';
    case 'BOTH_NO_TRADE':                     return 'bg-zinc-700 text-zinc-200';
    case 'INCONCLUSIVE':                      return 'bg-zinc-800 text-zinc-400';
  }
}

function agreementLabel(a: ComparisonAgreement): string {
  switch (a) {
    case 'BOTH_TRADEABLE_SAME_DIRECTION':     return 'Both tradeable — same direction';
    case 'BOTH_TRADEABLE_OPPOSITE_DIRECTION': return 'Both tradeable — OPPOSITE directions';
    case 'LEGACY_ONLY_TRADEABLE':             return 'Legacy says trade, new says wait';
    case 'NEW_ONLY_TRADEABLE':                return 'New engine sees setup, legacy rejected';
    case 'BOTH_NO_TRADE':                     return 'Both no trade';
    case 'INCONCLUSIVE':                      return 'Inconclusive';
  }
}

function ComparisonBanner({ comparison }: { comparison: DecisionComparisonView }) {
  const legacy = comparison.legacyPlaybook;
  const legacyScore = legacy?.checklistScore ?? 0;
  const legacyVerdict = legacy?.verdict ?? '—';
  const legacyDir = legacy?.filters?.tradeDirection ?? null;
  return (
    <div className="border border-zinc-700 rounded p-2 space-y-2 bg-zinc-900/50">
      <div className="flex items-center gap-2 flex-wrap">
        <span className={`px-2 py-0.5 rounded text-[10px] font-semibold uppercase ${agreementTone(comparison.agreement)}`}>
          {agreementLabel(comparison.agreement)}
        </span>
      </div>
      <div className="grid grid-cols-2 gap-2 text-xs">
        <div>
          <div className="text-[10px] text-zinc-500 uppercase tracking-widest">Legacy 7/7</div>
          <div className="font-mono">
            <span className={legacyScore >= 6 ? 'text-emerald-400' : legacyScore >= 4 ? 'text-amber-400' : 'text-rose-400'}>
              {legacyScore}/7
            </span>
            {legacyDir && (
              <span className="ml-2 text-zinc-400">{legacyDir}</span>
            )}
          </div>
          <div className="text-[10px] text-zinc-400 truncate" title={legacyVerdict}>{legacyVerdict}</div>
        </div>
        <div>
          <div className="text-[10px] text-zinc-500 uppercase tracking-widest">New engine</div>
          <div className="font-mono">
            {comparison.strategyDecision?.decision ?? '—'}
            {comparison.strategyDecision?.direction && (
              <span className="ml-2 text-zinc-400">{comparison.strategyDecision.direction}</span>
            )}
          </div>
          <div className="text-[10px] text-zinc-400">
            {comparison.strategyDecision?.candidatePlaybookId ?? 'No playbook'}
            {' — score '}
            {comparison.strategyDecision ? formatScore(comparison.strategyDecision.finalScore) : '—'}
          </div>
        </div>
      </div>
    </div>
  );
}

function VoteRow({ vote }: { vote: StrategyAgentVote }) {
  const confOpacity = Math.max(0.3, vote.confidence);
  const borderColor = vote.vetoReason
    ? 'border-rose-600'
    : vote.abstain
      ? 'border-zinc-700'
      : vote.directionalVote > 0
        ? 'border-emerald-500/60'
        : vote.directionalVote < 0
          ? 'border-rose-500/60'
          : 'border-zinc-600';
  return (
    <div
      className={`border-l-2 pl-2 py-0.5 ${borderColor}`}
      style={{ opacity: vote.abstain ? 0.55 : confOpacity }}
    >
      <div className="flex items-center gap-2 font-mono text-xs">
        <span className="w-32 truncate text-zinc-300">{vote.agentId}</span>
        <span className="w-16 text-[10px] text-zinc-500">{vote.layer}</span>
        {vote.vetoReason ? (
          <span className="text-rose-400 font-semibold">VETO</span>
        ) : vote.abstain ? (
          <span className="text-zinc-500">abstain</span>
        ) : (
          <>
            <span className={`w-12 text-right ${scoreColor(vote.directionalVote)}`}>
              {formatScore(vote.directionalVote)}
            </span>
            <span className="w-10 text-right text-zinc-500 text-[10px]">
              ·{vote.confidence.toFixed(2)}
            </span>
          </>
        )}
      </div>
      {vote.evidence.length > 0 && (
        <div className="text-[10px] text-zinc-400 ml-34 pl-1">
          {vote.evidence.join(' — ')}
        </div>
      )}
    </div>
  );
}
