'use client';

import type { AdviceView } from './types';

const VERDICT_STYLES: Record<AdviceView['verdict'], string> = {
  TRADE:       'bg-emerald-700 text-emerald-50 border-emerald-500',
  ATTENDRE:    'bg-amber-700 text-amber-50 border-amber-500',
  EVITER:      'bg-red-800 text-red-50 border-red-500',
  UNAVAILABLE: 'bg-slate-700 text-slate-300 border-slate-600',
};

const VERDICT_LABELS: Record<AdviceView['verdict'], string> = {
  TRADE:       '✅ TRADE',
  ATTENDRE:    '⏳ ATTENDRE',
  EVITER:      '⛔ EVITER',
  UNAVAILABLE: '— IA indispo.',
};

interface Props {
  advice: AdviceView | null | undefined;
  loading?: boolean;
}

/**
 * Compact verdict badge surfacing the AI advisor's verdict + reasoning. Clicks
 * are not interactive — the verdict is informative; the hover tooltip carries
 * the full rationale.
 */
export default function QuantAdvisorBadge({ advice, loading }: Props) {
  if (loading) {
    return (
      <span className="inline-flex items-center gap-1 px-2 py-1 text-xs rounded border border-slate-600 bg-slate-800 text-slate-300">
        <span className="animate-pulse">⌛</span> IA en cours…
      </span>
    );
  }
  if (!advice) {
    return null;
  }
  const style = VERDICT_STYLES[advice.verdict];
  const label = VERDICT_LABELS[advice.verdict];
  const tooltip = advice.verdict === 'UNAVAILABLE'
    ? advice.reasoning
    : `${advice.reasoning}\n\nRisque: ${advice.risk}\nConfiance: ${(advice.confidence * 100).toFixed(0)}%\nModèle: ${advice.model}`;
  return (
    <span
      className={`inline-flex items-center gap-1 px-2 py-1 text-xs rounded border font-semibold ${style}`}
      title={tooltip}
    >
      {label}
      {advice.verdict !== 'UNAVAILABLE' && (
        <span className="font-mono font-normal opacity-70">
          {(advice.confidence * 100).toFixed(0)}%
        </span>
      )}
    </span>
  );
}
