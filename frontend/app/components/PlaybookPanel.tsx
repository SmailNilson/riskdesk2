'use client';

import { useState, useEffect, useCallback, useRef } from 'react';
import { api, PlaybookEvaluation, FinalVerdict, ChecklistItem, SetupCandidate } from '../lib/api';

interface PlaybookPanelProps {
  instrument: string;
  timeframe: string;
}

// Auto-run agents when the setup crosses this checklist score.
// Below this, the setup is weak enough that spending Gemini budget is wasteful.
const AUTO_TRIGGER_MIN_SCORE = 5;
// Delay after a setup becomes "good" before auto-triggering, so that a setup that
// flickers in and out doesn't burn calls every tick.
const AUTO_TRIGGER_DEBOUNCE_MS = 2_000;

// Key that identifies a "materially same" setup. Score drift (6/7 → 7/7 on the
// same zone) does NOT invalidate a cached agent verdict; a different zone or
// different direction does.
function setupIdentity(
  instrument: string,
  timeframe: string,
  playbook: PlaybookEvaluation | null,
): string | null {
  if (!playbook?.bestSetup) return null;
  const direction = playbook.filters?.tradeDirection ?? 'UNKNOWN';
  return [instrument, timeframe, playbook.bestSetup.zoneName, direction].join('|');
}

export default function PlaybookPanel({ instrument, timeframe }: PlaybookPanelProps) {
  const [playbook, setPlaybook] = useState<PlaybookEvaluation | null>(null);
  const [fullVerdict, setFullVerdict] = useState<FinalVerdict | null>(null);
  const [verdictRunAt, setVerdictRunAt] = useState<number | null>(null);
  const [agentsCollapsed, setAgentsCollapsed] = useState(false);
  const [, setNowTick] = useState(0); // forces re-render for the relative timestamp
  const [loading, setLoading] = useState(false);
  const [agentsLoading, setAgentsLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Remembers the last (zoneName|score|direction) that was auto-run so we don't
  // re-trigger Gemini on every 30s poll while nothing material has changed.
  const lastAutoKeyRef = useRef<string | null>(null);
  // The setup identity the currently-cached fullVerdict was run against.
  // When the setup identity changes, the cached verdict is stale and cleared.
  const verdictSetupKeyRef = useRef<string | null>(null);

  const fetchPlaybook = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.getPlaybook(instrument, timeframe);
      setPlaybook(result);
      // NOTE: we intentionally do NOT clear fullVerdict here. The 30s poll
      // refreshes the lightweight playbook state — the expensive agent verdict
      // must persist until either (a) instrument/timeframe changes, or
      // (b) the best-setup identity changes. See the dedicated effects below.
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to load playbook');
    } finally {
      setLoading(false);
    }
  }, [instrument, timeframe]);

  useEffect(() => {
    fetchPlaybook();
    const interval = setInterval(fetchPlaybook, 30000);
    return () => clearInterval(interval);
  }, [fetchPlaybook]);

  // Reset everything when the user switches instrument/timeframe — the cached
  // verdict is for a different context and must not be shown.
  useEffect(() => {
    lastAutoKeyRef.current = null;
    verdictSetupKeyRef.current = null;
    setFullVerdict(null);
    setVerdictRunAt(null);
    setAgentsCollapsed(false);
  }, [instrument, timeframe]);

  // Clear the cached verdict when the best-setup identity changes to a new one.
  // Setup temporarily disappearing (bestSetup === null) does NOT clear the
  // cache — the user might still want to see the last verdict as context.
  useEffect(() => {
    const currentKey = setupIdentity(instrument, timeframe, playbook);
    if (currentKey != null && verdictSetupKeyRef.current != null
        && currentKey !== verdictSetupKeyRef.current) {
      setFullVerdict(null);
      setVerdictRunAt(null);
      verdictSetupKeyRef.current = null;
    }
  }, [playbook, instrument, timeframe]);

  // Tick once a minute so the "run 2m ago" label refreshes without a full poll.
  useEffect(() => {
    if (verdictRunAt == null) return;
    const timer = setInterval(() => setNowTick(t => t + 1), 30_000);
    return () => clearInterval(timer);
  }, [verdictRunAt]);

  const runAgents = useCallback(async () => {
    setAgentsLoading(true);
    try {
      const result = await api.getFullPlaybook(instrument, timeframe);
      setFullVerdict(result);
      setVerdictRunAt(Date.now());
      setAgentsCollapsed(false);
      verdictSetupKeyRef.current = setupIdentity(instrument, timeframe, playbook);
    } catch (e: unknown) {
      setError(e instanceof Error ? e.message : 'Failed to run agents');
    } finally {
      setAgentsLoading(false);
    }
  }, [instrument, timeframe, playbook]);

  // ── Auto-trigger the 4-agent gate when a qualified setup appears ──────
  // Fires once per (instrument, timeframe, zoneName, score, direction) combination
  // so we don't burn Gemini budget on the 30s polling cadence. Rate-limited by a
  // 2s debounce: a flickering setup won't spam requests.
  useEffect(() => {
    if (!playbook || !playbook.bestSetup) return;
    if (playbook.checklistScore < AUTO_TRIGGER_MIN_SCORE) return;
    if (agentsLoading) return;

    const direction = playbook.filters?.tradeDirection ?? 'UNKNOWN';
    const key = [
      instrument,
      timeframe,
      playbook.bestSetup.zoneName,
      playbook.checklistScore,
      direction,
    ].join('|');
    if (lastAutoKeyRef.current === key) return;

    const timer = setTimeout(() => {
      lastAutoKeyRef.current = key;
      runAgents();
    }, AUTO_TRIGGER_DEBOUNCE_MS);

    return () => clearTimeout(timer);
  }, [playbook, instrument, timeframe, agentsLoading, runAgents]);

  if (loading && !playbook) {
    return <div className="text-zinc-500 text-sm p-4">Loading playbook...</div>;
  }
  if (error && !playbook) {
    return <div className="text-red-400 text-sm p-4">{error}</div>;
  }
  if (!playbook) return null;

  const f = playbook.filters;

  return (
    <div className="space-y-3">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-zinc-200">
          PLAYBOOK — {instrument} {timeframe}
        </h3>
        <span className={`text-xs font-mono px-2 py-0.5 rounded ${
          playbook.checklistScore >= 6 ? 'bg-emerald-900/50 text-emerald-400' :
          playbook.checklistScore >= 4 ? 'bg-amber-900/50 text-amber-400' :
          'bg-red-900/50 text-red-400'
        }`}>
          {playbook.checklistScore}/7
        </span>
      </div>

      {/* Verdict */}
      <div className={`text-xs font-mono p-2 rounded border ${
        playbook.verdict.startsWith('NO TRADE') ? 'border-red-800 bg-red-950/30 text-red-400' :
        playbook.verdict.includes('WAIT') ? 'border-amber-800 bg-amber-950/30 text-amber-400' :
        'border-emerald-800 bg-emerald-950/30 text-emerald-400'
      }`}>
        {playbook.verdict}
      </div>

      {/* Filters */}
      <div className="border border-zinc-800 rounded p-2 space-y-1">
        <div className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider">Filters</div>
        <FilterRow label="Bias" pass={f.biasAligned} detail={`${f.swingBias || '?'} → ${f.tradeDirection}`} />
        <FilterRow label="Structure" pass={f.structureClean}
          detail={`${f.validBreaks}/${f.totalBreaks} OK${f.fakeBreaks > 0 ? ` (${f.fakeBreaks} FAKE?)` : ''} → ${f.sizeMultiplier < 1 ? 'half-size' : 'full-size'}`}
          warn={!f.structureClean} />
        <FilterRow label="VA Position" pass={f.vaPositionOk}
          detail={`${f.vaPosition.replace('_', ' ')} ${f.vaPositionOk ? '' : '(wrong side)'}`} />
      </div>

      {/* Best Setup */}
      {playbook.bestSetup && (
        <div className="border border-cyan-900/50 rounded p-2">
          <div className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider mb-1">Setup Detected</div>
          <SetupRow setup={playbook.bestSetup} />
        </div>
      )}

      {/* Checklist */}
      {playbook.checklist.length > 0 && (
        <div className="border border-zinc-800 rounded p-2 space-y-0.5">
          <div className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider mb-1">Checklist</div>
          {playbook.checklist.map(item => (
            <ChecklistRow key={item.step} item={item} />
          ))}
        </div>
      )}

      {/* Mechanical Plan */}
      {playbook.plan && (
        <div className="border border-zinc-800 rounded p-2">
          <div className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider mb-1">Mechanical Plan</div>
          <div className="grid grid-cols-2 gap-x-4 gap-y-0.5 text-xs font-mono">
            <PlanRow label="Entry" value={playbook.plan.entryPrice} />
            <PlanRow label="SL" value={playbook.plan.stopLoss} className="text-red-400" />
            <PlanRow label="TP1" value={playbook.plan.takeProfit1} className="text-emerald-400" />
            <PlanRow label="TP2" value={playbook.plan.takeProfit2} className="text-emerald-400/60" />
            <div className="col-span-2 text-zinc-400 mt-1">
              R:R {playbook.plan.rrRatio.toFixed(1)}:1 — Size {(playbook.plan.riskPercent * 100).toFixed(2)}%
            </div>
          </div>
        </div>
      )}

      {/* Agent verdicts — cached until instrument/timeframe or setup identity changes */}
      {fullVerdict && (
        <div className="border border-indigo-900/50 rounded p-2 space-y-1">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-2">
              <span className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider">Agents</span>
              {verdictRunAt != null && (
                <span className="text-[10px] text-zinc-600 font-mono" title={new Date(verdictRunAt).toLocaleString()}>
                  run {formatRelative(verdictRunAt)}
                </span>
              )}
            </div>
            <button
              type="button"
              onClick={() => setAgentsCollapsed(c => !c)}
              className="text-[10px] text-zinc-500 hover:text-zinc-300 font-mono px-1.5 py-0.5 rounded hover:bg-zinc-800 transition-colors"
              aria-expanded={!agentsCollapsed}
              title={agentsCollapsed ? 'Show cached agent verdict' : 'Hide agent verdict (stays cached)'}
            >
              {agentsCollapsed ? 'show ⌄' : 'hide ⌃'}
            </button>
          </div>
          {!agentsCollapsed && (
            <>
              {fullVerdict.agentVerdicts.map(v => (
                <div key={v.agentName} className="flex items-start gap-2 text-xs">
                  <span className={`font-mono px-1 rounded ${
                    v.confidence === 'HIGH' ? 'bg-emerald-900/40 text-emerald-400' :
                    v.confidence === 'MEDIUM' ? 'bg-amber-900/40 text-amber-400' :
                    'bg-red-900/40 text-red-400'
                  }`}>{v.confidence}</span>
                  <span className="text-zinc-400 w-24 shrink-0">{v.agentName}</span>
                  <span className="text-zinc-300">{v.reasoning}</span>
                </div>
              ))}
              {fullVerdict.warnings.length > 0 && (
                <div className="text-xs text-amber-400 mt-1">
                  {fullVerdict.warnings.map((w, i) => <div key={i}>{w}</div>)}
                </div>
              )}
              <div className={`text-xs font-mono mt-1 ${
                fullVerdict.eligibility === 'ELIGIBLE' ? 'text-emerald-400' : 'text-red-400'
              }`}>
                {fullVerdict.verdict}
              </div>
            </>
          )}
        </div>
      )}

      {/* Actions */}
      <div className="flex gap-2">
        <button onClick={fetchPlaybook}
          className="text-xs px-3 py-1.5 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-300 transition-colors">
          Refresh
        </button>
        {playbook.bestSetup && (
          <button onClick={runAgents} disabled={agentsLoading}
            className="text-xs px-3 py-1.5 rounded bg-indigo-900/50 hover:bg-indigo-800/50 text-indigo-300 transition-colors disabled:opacity-50">
            {agentsLoading ? 'Running...' : fullVerdict ? 'Re-run Agents' : 'Run Agents'}
          </button>
        )}
      </div>

      {/* Other setups */}
      {playbook.setups.length > 1 && (
        <details className="text-xs text-zinc-500">
          <summary className="cursor-pointer hover:text-zinc-400">
            {playbook.setups.length - 1} other setup(s)
          </summary>
          <div className="mt-1 space-y-1">
            {playbook.setups.slice(1).map((s, i) => (
              <SetupRow key={i} setup={s} />
            ))}
          </div>
        </details>
      )}
    </div>
  );
}

function FilterRow({ label, pass, detail, warn }: { label: string; pass: boolean; detail: string; warn?: boolean }) {
  const icon = pass ? (warn ? '!' : '+') : '-';
  const color = pass ? (warn ? 'text-amber-400' : 'text-emerald-400') : 'text-red-400';
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className={`font-mono w-3 ${color}`}>{icon}</span>
      <span className="text-zinc-400 w-20">{label}</span>
      <span className="text-zinc-300">{detail}</span>
    </div>
  );
}

function SetupRow({ setup }: { setup: SetupCandidate }) {
  const typeLabel = setup.type.replace(/_/g, ' ');
  return (
    <div className="text-xs">
      <span className="text-cyan-400 font-mono">{typeLabel}</span>
      <span className="text-zinc-500 mx-1">—</span>
      <span className="text-zinc-300">{setup.zoneName}</span>
      {setup.priceInZone && <span className="text-emerald-400 ml-1">(in zone)</span>}
      {!setup.priceInZone && <span className="text-zinc-500 ml-1">(dist: {setup.distanceFromPrice.toFixed(2)})</span>}
      {setup.rrRatio > 0 && <span className="text-zinc-400 ml-1">R:R {setup.rrRatio.toFixed(1)}:1</span>}
    </div>
  );
}

function ChecklistRow({ item }: { item: ChecklistItem }) {
  const icon = item.status === 'PASS' ? '+' : item.status === 'WAITING' ? '~' : '-';
  const color = item.status === 'PASS' ? 'text-emerald-400' : item.status === 'WAITING' ? 'text-amber-400' : 'text-red-400';
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className={`font-mono w-3 ${color}`}>{icon}</span>
      <span className="text-zinc-400 w-4">{item.step}.</span>
      <span className="text-zinc-300 w-28">{item.label}</span>
      <span className="text-zinc-500">{item.detail}</span>
    </div>
  );
}

function PlanRow({ label, value, className }: { label: string; value: number | null; className?: string }) {
  return (
    <div className="flex justify-between">
      <span className="text-zinc-500">{label}</span>
      <span className={className || 'text-zinc-200'}>{value != null ? value.toFixed(2) : '—'}</span>
    </div>
  );
}

// Human-readable "run 2m ago" / "run 45s ago" for the cached agent verdict.
// Buckets (0-60s, <1h, <24h, ≥1d) keep the label compact in the Agents header.
function formatRelative(runAt: number): string {
  const seconds = Math.max(0, Math.floor((Date.now() - runAt) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}
