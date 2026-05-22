'use client';

import { useState, useEffect, useCallback, useRef, type ReactNode } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import {
  api,
  PlaybookEvaluation,
  FinalVerdict,
  ChecklistItem,
  SetupCandidate,
  PlaybookAutomationView,
  PlaybookAutomationDecisionView,
  PlaybookAutomationRoutingOutcome,
  PlaybookAutomationSimulationStatus,
  PlaybookAutomationProfitabilitySummaryView,
} from '../lib/api';
import { API_BASE, WS_BASE } from '../lib/runtimeConfig';

interface PlaybookPanelProps {
  instrument: string;
  timeframe: string;
  selectedBrokerAccountId?: string | null;
}

// Auto-run agents when the setup crosses this checklist score.
// Below this, the setup is weak enough that spending Gemini budget is wasteful.
const AUTO_TRIGGER_MIN_SCORE = 5;
// Delay after a setup becomes "good" before auto-triggering, so that a setup that
// flickers in and out doesn't burn calls every tick.
const AUTO_TRIGGER_DEBOUNCE_MS = 2_000;
const AUTOMATION_POLL_MS = 5_000;
const PLAYBOOK_WS_URL = buildWsUrl(WS_BASE, API_BASE);
const DEFAULT_AUTOMATION: PlaybookAutomationView = {
  instrument: '',
  timeframe: '',
  paperThreshold: 4,
  liveThreshold: 5,
  autoIbkrEnabled: false,
  quantity: 1,
  brokerAccountId: null,
  updatedAt: null,
};

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined) {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

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

export default function PlaybookPanel({ instrument, timeframe, selectedBrokerAccountId }: PlaybookPanelProps) {
  const [playbook, setPlaybook] = useState<PlaybookEvaluation | null>(null);
  const [automation, setAutomation] = useState<PlaybookAutomationView | null>(null);
  const [automationDecisions, setAutomationDecisions] = useState<PlaybookAutomationDecisionView[]>([]);
  const [fullVerdict, setFullVerdict] = useState<FinalVerdict | null>(null);
  const [verdictRunAt, setVerdictRunAt] = useState<number | null>(null);
  const [agentsCollapsed, setAgentsCollapsed] = useState(false);
  const [, setNowTick] = useState(0); // forces re-render for the relative timestamp
  const [loading, setLoading] = useState(false);
  const [agentsLoading, setAgentsLoading] = useState(false);
  const [automationBusy, setAutomationBusy] = useState(false);
  const [qtyDraft, setQtyDraft] = useState('1');
  const [error, setError] = useState<string | null>(null);
  // Remembers the last (zoneName|score|direction) that was auto-run so we don't
  // re-trigger Gemini on every 30s poll while nothing material has changed.
  const lastAutoKeyRef = useRef<string | null>(null);
  // The setup identity the currently-cached fullVerdict was run against.
  // When the setup identity changes, the cached verdict is stale and cleared.
  const verdictSetupKeyRef = useRef<string | null>(null);
  const automationDraftPanelRef = useRef<string>('');

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

  const fetchAutomation = useCallback(async () => {
    try {
      const [config, decisions] = await Promise.all([
        api.getPlaybookAutomation(instrument, timeframe),
        api.getPlaybookAutomationDecisions(instrument, timeframe, 10),
      ]);
      setAutomation(config ?? {
        ...DEFAULT_AUTOMATION,
        instrument,
        timeframe,
      });
      setAutomationDecisions(decisions);
    } catch {
      setAutomation({
        ...DEFAULT_AUTOMATION,
        instrument,
        timeframe,
      });
      setAutomationDecisions([]);
    }
  }, [instrument, timeframe]);

  useEffect(() => {
    fetchPlaybook();
    const interval = setInterval(fetchPlaybook, 30000);
    return () => clearInterval(interval);
  }, [fetchPlaybook]);

  useEffect(() => {
    fetchAutomation();
    const interval = setInterval(fetchAutomation, AUTOMATION_POLL_MS);
    return () => clearInterval(interval);
  }, [fetchAutomation]);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(PLAYBOOK_WS_URL),
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe(`/topic/playbook-decisions/${instrument}/${timeframe}`, () => {
          void fetchAutomation();
        });
      },
    });
    client.activate();
    return () => {
      void client.deactivate();
    };
  }, [fetchAutomation, instrument, timeframe]);

  // Reset everything when the user switches instrument/timeframe — the cached
  // verdict is for a different context and must not be shown.
  useEffect(() => {
    lastAutoKeyRef.current = null;
    verdictSetupKeyRef.current = null;
    setFullVerdict(null);
    setVerdictRunAt(null);
    setAgentsCollapsed(false);
    setAutomation(null);
    setAutomationDecisions([]);
    setQtyDraft('1');
    automationDraftPanelRef.current = '';
  }, [instrument, timeframe]);

  useEffect(() => {
    if (!automation) return;
    if (automation.instrument !== instrument || automation.timeframe !== timeframe) return;
    const panelId = `${automation.instrument}:${automation.timeframe}`;
    if (automationDraftPanelRef.current !== panelId) {
      automationDraftPanelRef.current = panelId;
      setQtyDraft(String(automation.quantity));
    }
  }, [automation, instrument, timeframe]);

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

  const updateAutomation = useCallback(async (patch: Partial<PlaybookAutomationView>) => {
    const current = automation ?? { ...DEFAULT_AUTOMATION, instrument, timeframe };
    const next = { ...current, ...patch };
    setAutomationBusy(true);
    try {
      const updated = await api.updatePlaybookAutomation(instrument, timeframe, {
        autoIbkrEnabled: next.autoIbkrEnabled,
        quantity: next.quantity,
        brokerAccountId: next.brokerAccountId ?? null,
      });
      setAutomation(updated ?? next);
    } finally {
      setAutomationBusy(false);
    }
  }, [automation, instrument, timeframe]);

  const onToggleAutoIbkr = useCallback(async () => {
    const current = automation ?? { ...DEFAULT_AUTOMATION, instrument, timeframe };
    const turningOn = !current.autoIbkrEnabled;
    const brokerAccountId = current.brokerAccountId ?? selectedBrokerAccountId ?? null;
    if (turningOn) {
      if (!brokerAccountId) {
        setError('Select an IBKR account before enabling PLAYBOOK Auto-IBKR.');
        return;
      }
      const confirmed = window.confirm(
        `Enable PLAYBOOK Auto-IBKR for ${instrument} ${timeframe}?\n\n` +
        `Eligible playbook decisions at ${current.liveThreshold}/7 or better may submit REAL IBKR orders.\n\n` +
        `Paper simulation starts at ${current.paperThreshold}/7. Auto-IBKR stays off by default and remains active until you turn it off.`
      );
      if (!confirmed) return;
    }
    setError(null);
    await updateAutomation({ autoIbkrEnabled: turningOn, brokerAccountId });
  }, [automation, instrument, timeframe, selectedBrokerAccountId, updateAutomation]);

  const commitQty = useCallback(async () => {
    const current = automation ?? { ...DEFAULT_AUTOMATION, instrument, timeframe };
    const parsed = Number(qtyDraft.trim());
    if (!Number.isFinite(parsed) || !Number.isInteger(parsed) || parsed <= 0 || parsed > 100) {
      setQtyDraft(String(current.quantity));
      return;
    }
    if (parsed === current.quantity) return;
    await updateAutomation({ quantity: parsed });
  }, [automation, instrument, timeframe, qtyDraft, updateAutomation]);

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
  const automationState = automation ?? { ...DEFAULT_AUTOMATION, instrument, timeframe };
  const latestAutomationDecision = automationDecisions[0] ?? null;
  const summary = latestAutomationDecision?.profitabilitySummary ?? summarizeAutomationDecisions(automationDecisions);
  const autoIbkrOn = automationState.autoIbkrEnabled === true;
  const activeBrokerAccountId = automationState.brokerAccountId ?? selectedBrokerAccountId ?? null;

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
      {error && (
        <div className="text-xs rounded border border-red-800 bg-red-950/30 p-2 text-red-300">
          {error}
        </div>
      )}

      {/* Automation controls */}
      <div className={`border rounded p-2 space-y-2 ${
        autoIbkrOn ? 'border-red-700/70 bg-red-950/10' : 'border-cyan-900/40 bg-zinc-950/40'
      }`}>
        <div className="flex items-center justify-between gap-2">
          <div>
            <div className="text-[10px] font-semibold text-cyan-300 uppercase tracking-wider">Automation</div>
            <div className="text-[10px] text-zinc-500">
              Paper {automationState.paperThreshold}/7 · Live {automationState.liveThreshold}/7 · Acct {activeBrokerAccountId ?? 'none'}
            </div>
          </div>
          <button
            type="button"
            onClick={onToggleAutoIbkr}
            disabled={automationBusy}
            aria-pressed={autoIbkrOn}
            title={autoIbkrOn ? 'Disable PLAYBOOK Auto-IBKR routing' : 'Enable PLAYBOOK Auto-IBKR routing (confirmation required)'}
            className={`rounded border px-2 py-0.5 text-[10px] font-semibold transition-colors disabled:opacity-50 ${
              autoIbkrOn
                ? 'border-red-600/70 bg-red-950/40 text-red-300 hover:bg-red-950/60'
                : 'border-zinc-700 text-zinc-400 hover:border-emerald-700 hover:text-emerald-300'
            }`}
          >
            Auto-IBKR: {autoIbkrOn ? 'ON' : 'OFF'}
          </button>
        </div>

        <div className="flex items-center gap-2 flex-wrap">
          <ThresholdControl
            label="Paper"
            value={automationState.paperThreshold}
          />
          <ThresholdControl
            label="Live"
            value={automationState.liveThreshold}
          />
          <label className="flex items-center gap-1.5 text-[10px] text-zinc-400" title="Contracts submitted for PLAYBOOK Auto-IBKR live decisions">
            <span className="text-zinc-500">Qty</span>
            <input
              type="number"
              min={1}
              max={100}
              step={1}
              value={qtyDraft}
              onChange={event => setQtyDraft(event.target.value)}
              onBlur={commitQty}
              onKeyDown={event => {
                if (event.key === 'Enter') {
                  event.preventDefault();
                  (event.target as HTMLInputElement).blur();
                }
                if (event.key === 'Escape') {
                  setQtyDraft(String(automationState.quantity));
                  (event.target as HTMLInputElement).blur();
                }
              }}
              disabled={automationBusy}
              className="w-12 rounded border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[10px] text-zinc-200 hover:border-cyan-700 focus:border-cyan-600 focus:outline-none disabled:opacity-50"
            />
          </label>
        </div>

        <div className="grid grid-cols-2 gap-2">
          <StatusBox label="Routing">
            <RoutingChip outcome={latestAutomationDecision?.routingOutcome ?? null} errorMessage={latestAutomationDecision?.routingErrorMessage ?? null} />
          </StatusBox>
          <StatusBox label="Simulation">
            <SimulationChip status={latestAutomationDecision?.simulationStatus ?? null} />
          </StatusBox>
        </div>

        <ProfitabilitySummary summary={summary} />
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

function ThresholdControl({
  label,
  value,
}: {
  label: string;
  value: number;
}) {
  return (
    <label className="flex items-center gap-1.5 text-[10px] text-zinc-400">
      <span className="text-zinc-500">{label}</span>
      <span className="rounded border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[10px] text-zinc-200">
        {value}/7
      </span>
    </label>
  );
}

function StatusBox({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="rounded border border-zinc-800 bg-zinc-950/50 px-2 py-1">
      <div className="text-[9px] text-zinc-600 uppercase tracking-wider">{label}</div>
      <div className="mt-1 min-h-5 flex items-center">
        {children || <span className="text-[10px] text-zinc-600">No decision</span>}
      </div>
    </div>
  );
}

function RoutingChip({
  outcome,
  errorMessage,
}: {
  outcome: PlaybookAutomationRoutingOutcome | null;
  errorMessage?: string | null;
}) {
  if (!outcome) return <span className="text-[10px] text-zinc-600">No routing yet</span>;
  const normalized = outcome.toUpperCase();
  const style =
    normalized === 'ROUTED' || normalized === 'PAPER_ONLY'
      ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60'
      : normalized === 'ACK_PENDING'
        ? 'bg-cyan-950/70 text-cyan-300 border-cyan-800/60'
        : normalized.startsWith('FAILED')
          ? 'bg-red-950/70 text-red-300 border-red-800/60'
          : 'bg-amber-950/70 text-amber-300 border-amber-800/60';
  const label =
    normalized === 'ROUTED'
      ? 'IBKR OK'
      : normalized === 'PAPER_ONLY'
        ? 'PAPER'
        : normalized.replace(/^SKIPPED_/, '').replace(/_/g, ' ');
  return (
    <span
      title={errorMessage ? `${outcome}\n${errorMessage}` : outcome}
      className={`text-[9px] font-semibold px-1.5 py-0.5 rounded border ${style}`}
    >
      {label}
    </span>
  );
}

function SimulationChip({ status }: { status: PlaybookAutomationSimulationStatus | null }) {
  if (!status) return <span className="text-[10px] text-zinc-600">No simulation yet</span>;
  const normalized = status.toUpperCase();
  const style =
    normalized === 'WIN'
      ? 'bg-emerald-950/70 text-emerald-300 border-emerald-800/60'
      : normalized === 'LOSS' || normalized === 'CANCELLED'
        ? 'bg-red-950/70 text-red-300 border-red-800/60'
        : normalized === 'ACTIVE'
          ? 'bg-cyan-950/70 text-cyan-300 border-cyan-800/60'
          : 'bg-zinc-800 text-zinc-300 border-zinc-700';
  return (
    <span className={`text-[9px] font-semibold px-1.5 py-0.5 rounded border ${style}`}>
      {normalized.replace(/_/g, ' ')}
    </span>
  );
}

function ProfitabilitySummary({
  summary,
}: {
  summary: PlaybookAutomationProfitabilitySummaryView | null;
}) {
  if (!summary) {
    return <div className="text-[10px] text-zinc-600 italic">No PLAYBOOK automation results yet</div>;
  }
  return (
    <div className="grid grid-cols-4 gap-2 text-[10px]">
      <Metric label="Decisions" value={formatMetric(summary.totalDecisions)} />
      <Metric label="Win rate" value={formatPct(summary.winRate)} />
      <Metric label="P&L" value={formatCurrency(summary.totalPnl)} accent={(summary.totalPnl ?? 0) >= 0 ? 'good' : 'bad'} />
      <Metric label="PF" value={formatMetric(summary.profitFactor, 2)} />
    </div>
  );
}

function Metric({
  label,
  value,
  accent,
}: {
  label: string;
  value: string;
  accent?: 'good' | 'bad';
}) {
  const color = accent === 'good' ? 'text-emerald-300' : accent === 'bad' ? 'text-red-300' : 'text-zinc-200';
  return (
    <div className="rounded border border-zinc-800 bg-zinc-950/50 px-2 py-1">
      <div className="text-[9px] text-zinc-600 uppercase tracking-wider">{label}</div>
      <div className={`font-mono ${color}`}>{value}</div>
    </div>
  );
}

function summarizeAutomationDecisions(
  decisions: PlaybookAutomationDecisionView[],
): PlaybookAutomationProfitabilitySummaryView | null {
  if (decisions.length === 0) return null;
  const resolved = decisions.filter(d => d.simulationStatus === 'WIN' || d.simulationStatus === 'LOSS');
  const wins = resolved.filter(d => d.simulationStatus === 'WIN').length;
  const losses = resolved.filter(d => d.simulationStatus === 'LOSS').length;
  const pnlValues = decisions
    .map(d => d.simulationPnl ?? d.pnl)
    .filter((value): value is number => typeof value === 'number' && Number.isFinite(value));
  const totalPnl = pnlValues.reduce((sum, value) => sum + value, 0);
  const grossWins = pnlValues.filter(value => value > 0).reduce((sum, value) => sum + value, 0);
  const grossLosses = Math.abs(pnlValues.filter(value => value < 0).reduce((sum, value) => sum + value, 0));
  return {
    totalDecisions: decisions.length,
    wins,
    losses,
    winRate: resolved.length > 0 ? wins / resolved.length : null,
    totalPnl: pnlValues.length > 0 ? totalPnl : null,
    averagePnl: pnlValues.length > 0 ? totalPnl / pnlValues.length : null,
    profitFactor: grossLosses > 0 ? grossWins / grossLosses : grossWins > 0 ? Infinity : null,
  };
}

function formatMetric(value: number | null | undefined, digits = 0): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return value.toFixed(digits);
}

function formatPct(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  const pct = value <= 1 ? value * 100 : value;
  return `${pct.toFixed(0)}%`;
}

function formatCurrency(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '—';
  return `${value >= 0 ? '+' : ''}${value.toFixed(0)}$`;
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
