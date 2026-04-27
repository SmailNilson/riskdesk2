'use client';

/**
 * TradeDecisionPanel — surfaces decisions captured by the agent orchestrator
 * ({@code /api/decisions/by-instrument/{instrument}}).
 *
 * <p>Distinct from {@link StrategyPanel} (current probabilistic engine snapshot)
 * and {@link MentorSignalPanel} (Mentor reviews): this panel is the read model for
 * the persisted {@code TradeDecision} history produced by the orchestrator + narrator.
 *
 * <p>Lifecycle:
 * <ul>
 *   <li>On mount and whenever {@code instrument}/{@code timeframe} change, fetches
 *       the 30 most recent decisions for that instrument (backend order: newest first).</li>
 *   <li>Re-polls every 15s. The interval resets when instrument/timeframe change.</li>
 *   <li>The {@code timeframe} prop is used only for client-side filtering — the API
 *       is not timeframe-scoped on this endpoint.</li>
 * </ul>
 */

import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, TradeDecision, TradeDecisionAgentVerdict } from '../lib/api';

const POLL_INTERVAL_MS = 15_000;
const DEFAULT_LIMIT = 30;

interface TradeDecisionPanelProps {
  instrument: string;
  timeframe?: string;
}

export default function TradeDecisionPanel({ instrument, timeframe }: TradeDecisionPanelProps) {
  const [decisions, setDecisions] = useState<TradeDecision[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [expandedIds, setExpandedIds] = useState<Set<number>>(new Set());

  const fetchDecisions = useCallback(async () => {
    if (!instrument) return;
    setLoading(true);
    setError(null);
    try {
      const result = await api.getDecisionsByInstrument(instrument, DEFAULT_LIMIT);
      setDecisions(result);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load trade decisions');
    } finally {
      setLoading(false);
    }
  }, [instrument]);

  useEffect(() => {
    fetchDecisions();
    const id = setInterval(fetchDecisions, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [fetchDecisions]);

  // Optional client-side timeframe filter — show all when timeframe is undefined.
  const visible = useMemo(() => {
    if (!timeframe) return decisions;
    return decisions.filter(d => d.timeframe === timeframe);
  }, [decisions, timeframe]);

  const toggleExpanded = useCallback((id: number | null) => {
    if (id == null) return;
    setExpandedIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }, []);

  if (error) {
    return (
      <div className="p-4 text-rose-400 text-sm">
        Error loading trade decisions: {error}
      </div>
    );
  }

  if (decisions.length === 0) {
    return (
      <div className="p-4 text-zinc-400 text-sm">
        {loading ? 'Loading decisions...' : `No decisions recorded for ${instrument} yet`}
      </div>
    );
  }

  return (
    <div className="p-4 space-y-3 text-sm max-h-[720px] overflow-y-auto">
      <div className="flex items-center justify-between">
        <div className="text-[10px] uppercase tracking-widest text-zinc-500">
          {instrument}
          {timeframe ? ` / ${timeframe}` : ''}
          {' - '}
          {visible.length} decision{visible.length === 1 ? '' : 's'}
        </div>
        {loading && (
          <span className="text-[10px] text-zinc-500 animate-pulse">Refreshing...</span>
        )}
      </div>

      {visible.length === 0 && timeframe && (
        <div className="p-3 text-zinc-500 text-xs border border-zinc-800 rounded bg-zinc-900/50">
          No decisions match the {timeframe} timeframe in the latest {decisions.length}
          {' '}recorded for {instrument}.
        </div>
      )}

      <div className="space-y-2">
        {visible.map(d => (
          <DecisionCard
            key={d.id ?? `${d.createdAt}-${d.revision}`}
            decision={d}
            expanded={d.id != null && expandedIds.has(d.id)}
            onToggle={() => toggleExpanded(d.id)}
          />
        ))}
      </div>
    </div>
  );
}

// ── Card ──────────────────────────────────────────────────────────────────

interface DecisionCardProps {
  decision: TradeDecision;
  expanded: boolean;
  onToggle: () => void;
}

function DecisionCard({ decision, expanded, onToggle }: DecisionCardProps) {
  const verdictLabel = pickVerdictLabel(decision);
  const score = decision.sizePercent ?? 0;
  const warnings = parseWarnings(decision.warningsJson);
  const agentVerdicts = parseAgentVerdicts(decision.agentVerdictsJson);
  const playbook = derivePlaybook(decision);

  return (
    <div className="border border-zinc-800 rounded bg-zinc-900/60 p-3 space-y-2">
      {/* Top row: timestamp + verdict badge + direction + score + playbook */}
      <div className="flex items-center gap-2 flex-wrap">
        <span className="text-[10px] font-mono text-zinc-500">
          {formatRelativeTime(decision.createdAt)}
        </span>
        <span className={`px-2 py-0.5 rounded text-[10px] font-semibold uppercase tracking-wider ${verdictTone(verdictLabel)}`}>
          {verdictLabel}
        </span>
        {decision.direction && decision.direction !== 'FLAT' && (
          <span className={`font-mono text-[10px] px-2 py-0.5 rounded border ${
            decision.direction === 'LONG'
              ? 'border-emerald-500 text-emerald-400'
              : 'border-rose-500 text-rose-400'
          }`}>
            {decision.direction}
          </span>
        )}
        {playbook && (
          <span className="text-[10px] font-mono px-2 py-0.5 rounded bg-zinc-800 text-zinc-300 uppercase tracking-wider">
            {playbook}
          </span>
        )}
        <span className={`ml-auto font-mono text-xs font-semibold ${sizeColor(score)}`}>
          {formatSize(score)}
        </span>
        {decision.revision > 1 && (
          <span className="text-[10px] font-mono text-sky-400 px-1.5 py-0.5 rounded bg-sky-950/40 border border-sky-600">
            rev {decision.revision}
          </span>
        )}
      </div>

      {/* Verdict line */}
      <div className="text-xs text-zinc-300 font-mono">
        {decision.verdict || `${decision.setupType ?? 'Setup'} on ${decision.timeframe}`}
      </div>

      {/* Zone info line */}
      {(decision.zoneName || decision.setupType) && (
        <div className="text-[10px] text-zinc-500 font-mono">
          {decision.timeframe}
          {decision.setupType ? ` - ${decision.setupType}` : ''}
          {decision.zoneName ? ` - ${decision.zoneName}` : ''}
        </div>
      )}

      {/* Warnings / veto */}
      {warnings.length > 0 && (
        <div className="border border-rose-600/70 bg-rose-950/30 rounded p-2">
          <div className="text-[10px] font-semibold text-rose-400 uppercase mb-1">
            {decision.eligibility === 'BLOCKED' ? 'Veto' : 'Warnings'}
          </div>
          <ul className="text-[11px] text-rose-300 space-y-0.5">
            {warnings.map((w, i) => (
              <li key={i}>- {w}</li>
            ))}
          </ul>
        </div>
      )}

      {/* Plan (compact) */}
      {decision.entryPrice != null && (
        <div className="border border-zinc-700 rounded p-2 font-mono text-[11px] grid grid-cols-4 gap-2">
          <PlanCell label="Entry" value={formatPrice(decision.entryPrice)} />
          <PlanCell label="Stop" value={formatPrice(decision.stopLoss)} tone="text-rose-400" />
          <PlanCell label="TP1" value={formatPrice(decision.takeProfit1)} tone="text-emerald-400" />
          <PlanCell
            label="R:R"
            value={decision.rrRatio != null ? decision.rrRatio.toFixed(2) : '-'}
          />
        </div>
      )}

      {/* Narrative */}
      {decision.narrative && (
        <div className="text-[11px] text-zinc-300 italic border-l-2 border-zinc-700 pl-2">
          {decision.narrative}
        </div>
      )}

      {/* Evidence toggle */}
      {(agentVerdicts.length > 0 || decision.status === 'ERROR') && (
        <button
          type="button"
          onClick={onToggle}
          className="text-[10px] uppercase tracking-wider text-zinc-500 hover:text-zinc-300"
        >
          {expanded ? 'Hide evidence' : `Show evidence (${agentVerdicts.length})`}
        </button>
      )}

      {expanded && (
        <div className="space-y-1 pl-2 border-l border-zinc-800">
          {agentVerdicts.map((v, i) => (
            <AgentVerdictRow key={`${v.agentName}-${i}`} verdict={v} />
          ))}
          {decision.status === 'ERROR' && decision.errorMessage && (
            <div className="text-[10px] text-rose-400 font-mono">
              Narrator error: {decision.errorMessage}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

function PlanCell({ label, value, tone }: { label: string; value: string; tone?: string }) {
  return (
    <div>
      <div className="text-[9px] text-zinc-500 uppercase tracking-wider">{label}</div>
      <div className={tone ?? 'text-zinc-200'}>{value}</div>
    </div>
  );
}

function AgentVerdictRow({ verdict }: { verdict: TradeDecisionAgentVerdict }) {
  const biasTone = verdict.bias === 'LONG'
    ? 'text-emerald-400'
    : verdict.bias === 'SHORT'
      ? 'text-rose-400'
      : 'text-zinc-400';
  const confTone = verdict.confidence === 'HIGH'
    ? 'text-emerald-400'
    : verdict.confidence === 'MEDIUM'
      ? 'text-amber-400'
      : 'text-zinc-500';
  return (
    <div className="py-0.5">
      <div className="flex items-center gap-2 font-mono text-[11px]">
        <span className="w-40 truncate text-zinc-300">{verdict.agentName}</span>
        <span className={`w-16 text-[10px] ${confTone}`}>{verdict.confidence}</span>
        <span className={`w-14 text-[10px] ${biasTone}`}>
          {verdict.bias ?? '-'}
        </span>
      </div>
      {verdict.reasoning && (
        <div className="text-[10px] text-zinc-400 pl-2">
          {verdict.reasoning}
        </div>
      )}
    </div>
  );
}

// ── Formatting + parsing helpers ──────────────────────────────────────────

function formatRelativeTime(iso: string): string {
  const then = Date.parse(iso);
  if (Number.isNaN(then)) return iso;
  const deltaMs = Date.now() - then;
  const s = Math.round(deltaMs / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  return `${d}d ago`;
}

function formatPrice(v: number | null): string {
  if (v == null) return '-';
  if (!Number.isFinite(v)) return '-';
  return Math.abs(v) >= 1000 ? v.toFixed(2) : v.toFixed(4);
}

function formatSize(size: number): string {
  const sign = size > 0 ? '+' : '';
  return `${sign}${(size * 100).toFixed(0)}%`;
}

function sizeColor(size: number): string {
  if (size > 0.75) return 'text-emerald-400';
  if (size > 0) return 'text-emerald-500/80';
  if (size < 0) return 'text-rose-400';
  return 'text-zinc-500';
}

/**
 * Derive a normalized verdict label from the TradeDecision fields. The backend
 * stores eligibility + sizePercent separately; for display we collapse them to
 * one of the known decision buckets so the badge is consistent with StrategyPanel.
 */
function pickVerdictLabel(d: TradeDecision): string {
  const eligibility = (d.eligibility ?? '').toUpperCase();
  const direction = (d.direction ?? '').toUpperCase();
  if (eligibility === 'BLOCKED') return 'NO_TRADE';
  if (eligibility === 'INELIGIBLE') {
    return direction === 'LONG' || direction === 'SHORT' ? 'MONITORING' : 'NO_TRADE';
  }
  // ELIGIBLE path — size-based refinement
  if (d.sizePercent >= 1) return 'FULL_SIZE';
  if (d.sizePercent >= 0.5) return 'HALF_SIZE';
  if (d.sizePercent > 0) return 'PAPER_TRADE';
  // Eligible but zero size — direction still gives a hint
  if (direction === 'LONG') return 'LONG';
  if (direction === 'SHORT') return 'SHORT';
  return 'NO_TRADE';
}

function verdictTone(label: string): string {
  switch (label) {
    case 'FULL_SIZE':
    case 'LONG':
      return 'bg-emerald-600 text-white';
    case 'HALF_SIZE':
      return 'bg-emerald-500 text-white';
    case 'PAPER_TRADE':
      return 'bg-amber-500 text-white';
    case 'MONITORING':
      return 'bg-sky-500 text-white';
    case 'SHORT':
      return 'bg-rose-600 text-white';
    case 'NO_TRADE':
    default:
      return 'bg-zinc-600 text-white';
  }
}

/**
 * Collapse {@code setupType} to a short playbook tag. The orchestrator does not
 * record the candidate playbook id directly on TradeDecision, so we infer it from
 * the setup family.
 */
function derivePlaybook(d: TradeDecision): string | null {
  if (!d.setupType) return null;
  switch (d.setupType) {
    case 'LIQUIDITY_SWEEP':
      return 'LSAR';
    case 'ZONE_RETEST':
      return 'SBDR';
    case 'BREAK_RETEST':
      return 'BR';
    default:
      return d.setupType;
  }
}

function parseWarnings(json: string | null): string[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    if (Array.isArray(parsed)) {
      return parsed.filter(
        (x): x is string => typeof x === 'string' && x.trim().length > 0,
      );
    }
  } catch {
    // fallthrough
  }
  return [];
}

function parseAgentVerdicts(json: string | null): TradeDecisionAgentVerdict[] {
  if (!json) return [];
  try {
    const parsed = JSON.parse(json);
    if (!Array.isArray(parsed)) return [];
    return parsed
      .filter((v): v is Record<string, unknown> => typeof v === 'object' && v !== null)
      .map(v => ({
        agentName: typeof v.agentName === 'string' ? v.agentName : 'unknown',
        confidence:
          v.confidence === 'HIGH' || v.confidence === 'MEDIUM' || v.confidence === 'LOW'
            ? v.confidence
            : 'LOW',
        bias: v.bias === 'LONG' || v.bias === 'SHORT' ? v.bias : null,
        reasoning: typeof v.reasoning === 'string' ? v.reasoning : '',
        adjustments: v.adjustments,
      }));
  } catch {
    return [];
  }
}
