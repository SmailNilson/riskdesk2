'use client';

import { useState, useEffect, useCallback, useRef, useMemo } from 'react';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import {
  api,
  PlaybookEvaluation,
  FinalVerdict,
  ChecklistItem,
  SetupCandidate,
  PlaybookPlan,
  PlaybookAutomationView,
  PlaybookAutomationDecisionView,
  PlaybookAutomationRoutingOutcome,
  PlaybookAutomationSimulationStatus,
  PlaybookAutomationProfitabilitySummaryView,
  PlaybookExecutionProfile,
} from '../lib/api';
import { API_BASE, WS_BASE } from '../lib/runtimeConfig';

interface PlaybookPanelProps {
  instrument: string;
  timeframe: string;
  selectedBrokerAccountId?: string | null;
  // Live spot price for the active instrument. Drives the moving cursor on the
  // R:R progress bar and the live P&L readout. May be null while the first
  // WebSocket frame is in flight.
  livePrice?: number | null;
}

// Auto-run agents when the setup crosses this checklist score.
const AUTO_TRIGGER_MIN_SCORE = 5;
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
  armedProfile: 'LEGACY',
  scalpProfileValidated: false,
  updatedAt: null,
};

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined) {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

function setupIdentity(
  instrument: string,
  timeframe: string,
  playbook: PlaybookEvaluation | null,
): string | null {
  if (!playbook?.bestSetup) return null;
  const direction = playbook.filters?.tradeDirection ?? 'UNKNOWN';
  return [instrument, timeframe, playbook.bestSetup.zoneName, direction].join('|');
}

export default function PlaybookPanel({ instrument, timeframe, selectedBrokerAccountId, livePrice }: PlaybookPanelProps) {
  const [playbook, setPlaybook] = useState<PlaybookEvaluation | null>(null);
  const [automation, setAutomation] = useState<PlaybookAutomationView | null>(null);
  const [automationDecisions, setAutomationDecisions] = useState<PlaybookAutomationDecisionView[]>([]);
  const [fullVerdict, setFullVerdict] = useState<FinalVerdict | null>(null);
  const [verdictRunAt, setVerdictRunAt] = useState<number | null>(null);
  const [agentsCollapsed, setAgentsCollapsed] = useState(false);
  const [automationCollapsed, setAutomationCollapsed] = useState(true);
  const [, setNowTick] = useState(0);
  const [loading, setLoading] = useState(false);
  const [agentsLoading, setAgentsLoading] = useState(false);
  const [automationBusy, setAutomationBusy] = useState(false);
  const [qtyDraft, setQtyDraft] = useState('1');
  const [error, setError] = useState<string | null>(null);
  const lastAutoKeyRef = useRef<string | null>(null);
  const verdictSetupKeyRef = useRef<string | null>(null);
  const automationDraftPanelRef = useRef<string>('');

  const fetchPlaybook = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      const result = await api.getPlaybook(instrument, timeframe);
      setPlaybook(result);
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
        api.getPlaybookAutomationDecisions(instrument, timeframe, 50),
      ]);
      setAutomation(config ?? { ...DEFAULT_AUTOMATION, instrument, timeframe });
      setAutomationDecisions(decisions);
    } catch {
      setAutomation({ ...DEFAULT_AUTOMATION, instrument, timeframe });
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

  useEffect(() => {
    lastAutoKeyRef.current = null;
    verdictSetupKeyRef.current = null;
    setFullVerdict(null);
    setVerdictRunAt(null);
    setAgentsCollapsed(false);
    setAutomationCollapsed(true);
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

  useEffect(() => {
    const currentKey = setupIdentity(instrument, timeframe, playbook);
    if (currentKey != null && verdictSetupKeyRef.current != null
        && currentKey !== verdictSetupKeyRef.current) {
      setFullVerdict(null);
      setVerdictRunAt(null);
      verdictSetupKeyRef.current = null;
    }
  }, [playbook, instrument, timeframe]);

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
        armedProfile: next.armedProfile ?? 'LEGACY',
        scalpProfileValidated: next.scalpProfileValidated ?? false,
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
      if (current.armedProfile === 'MGC_10M_SCALP_0_5R' && !current.scalpProfileValidated) {
        setError('Manual validation is required before enabling PLAYBOOK Auto-IBKR for MGC 10m Scalp 0.5R.');
        return;
      }
      const confirmed = window.confirm(
        `Enable PLAYBOOK Auto-IBKR for ${instrument} ${timeframe}?\n\n` +
        `Armed profile: ${formatProfileLabel(current.armedProfile ?? 'LEGACY')}.\n\n` +
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

  useEffect(() => {
    if (!playbook || !playbook.bestSetup) return;
    if (playbook.checklistScore < AUTO_TRIGGER_MIN_SCORE) return;
    if (agentsLoading) return;

    const direction = playbook.filters?.tradeDirection ?? 'UNKNOWN';
    const key = [instrument, timeframe, playbook.bestSetup.zoneName, playbook.checklistScore, direction].join('|');
    if (lastAutoKeyRef.current === key) return;

    const timer = setTimeout(() => {
      lastAutoKeyRef.current = key;
      runAgents();
    }, AUTO_TRIGGER_DEBOUNCE_MS);

    return () => clearTimeout(timer);
  }, [playbook, instrument, timeframe, agentsLoading, runAgents]);

  const automationState = automation ?? { ...DEFAULT_AUTOMATION, instrument, timeframe };
  const latestAutomationDecision = automationDecisions[0] ?? null;
  const summary = latestAutomationDecision?.profitabilitySummary ?? summarizeAutomationDecisions(automationDecisions);
  const autoIbkrOn = automationState.autoIbkrEnabled === true;
  const activeBrokerAccountId = automationState.brokerAccountId ?? selectedBrokerAccountId ?? null;

  const tradeStats = useMemo(
    () => computeTradeStats(playbook?.plan ?? null, playbook?.filters?.tradeDirection ?? null, automationState.quantity, livePrice ?? null),
    [playbook?.plan, playbook?.filters?.tradeDirection, automationState.quantity, livePrice],
  );
  const profileControlsAvailable =
    (instrument === 'MGC' || instrument === 'MNQ') && timeframe === '10m';
  const currentSetupSupportsProfileTargets =
    profileControlsAvailable
    && playbook?.bestSetup?.type === 'BREAK_RETEST';
  const profileTargets = useMemo(
    () => currentSetupSupportsProfileTargets
      ? computeProfileTargets(playbook?.plan ?? null, playbook?.filters?.tradeDirection ?? null)
      : null,
    [currentSetupSupportsProfileTargets, playbook?.plan, playbook?.filters?.tradeDirection],
  );

  const onChangeExecutionProfile = useCallback(async (profile: PlaybookExecutionProfile) => {
    setError(null);
    await updateAutomation({ armedProfile: profile });
  }, [updateAutomation]);

  const onValidateScalpProfile = useCallback(async () => {
    const confirmed = window.confirm(
      'Manually validate MGC 10m Scalp 0.5R for PLAYBOOK Auto-IBKR?\n\n' +
      'This does not enable Auto-IBKR by itself. It only allows the selected profile to route after you turn Auto-IBKR ON.'
    );
    if (!confirmed) return;
    setError(null);
    await updateAutomation({ scalpProfileValidated: true });
  }, [updateAutomation]);

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
        <span
          className={`text-xs font-mono px-2 py-0.5 rounded ${
            playbook.checklistScore >= 6 ? 'bg-emerald-900/50 text-emerald-400' :
            playbook.checklistScore >= 4 ? 'bg-amber-900/50 text-amber-400' :
            'bg-red-900/50 text-red-400'
          }`}
          title="Checklist score for the detected setup"
        >
          Setup {playbook.checklistScore}/7
        </span>
      </div>

      {/* Verdict */}
      <div className={`text-xs font-mono p-2 rounded border ${
        playbook.verdict.startsWith('NO TRADE') ? 'border-red-800 bg-red-950/30 text-red-400' :
        playbook.verdict.includes('WAIT') ? 'border-amber-800 bg-amber-950/30 text-amber-400' :
        'border-emerald-800 bg-emerald-950/30 text-emerald-400'
      }`}>
        {playbook.verdict}
        {playbook.plan && playbook.plan.rrRatio > 0 && playbook.plan.rrRatio < 1 && (
          <div className="mt-1 text-red-400">
            ⚠ R:R {playbook.plan.rrRatio.toFixed(2)}:1 — setup invalide
          </div>
        )}
      </div>
      {error && (
        <div className="text-xs rounded border border-red-800 bg-red-950/30 p-2 text-red-300">
          {error}
        </div>
      )}

      {/* Trade Blueprint — the most actionable block, surfaced near the top */}
      {playbook.plan && playbook.filters?.tradeDirection && (
        <TradeBlueprint
          plan={playbook.plan}
          direction={playbook.filters.tradeDirection}
          livePrice={livePrice ?? null}
          stats={tradeStats}
        />
      )}

      {/* Setup + Filters merged into one block */}
      {playbook.bestSetup && (
        <div className="border border-cyan-900/50 rounded p-2 space-y-1.5">
          <div className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider">Setup</div>
          <SetupRow setup={playbook.bestSetup} />
          <div className="flex items-center gap-1.5 pt-1 flex-wrap">
            <FilterChip label="Bias" pass={f.biasAligned} tooltip={`${f.swingBias || '?'} → ${f.tradeDirection}`} />
            <FilterChip
              label="Structure"
              pass={f.structureClean}
              warn={!f.structureClean}
              tooltip={`${f.validBreaks}/${f.totalBreaks} OK${f.fakeBreaks > 0 ? ` (${f.fakeBreaks} FAKE?)` : ''} → ${f.sizeMultiplier < 1 ? 'half-size' : 'full-size'}`}
            />
            <FilterChip
              label="VA"
              pass={f.vaPositionOk}
              tooltip={`${f.vaPosition.replace('_', ' ')}${f.vaPositionOk ? '' : ' (wrong side)'}`}
            />
          </div>
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

      {/* Agent verdicts */}
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

      {/* Re-run agents action (Refresh removed — auto-poll every 30s covers it) */}
      {playbook.bestSetup && (
        <div className="flex gap-2">
          <button onClick={runAgents} disabled={agentsLoading}
            className="text-xs px-3 py-1.5 rounded bg-indigo-900/50 hover:bg-indigo-800/50 text-indigo-300 transition-colors disabled:opacity-50">
            {agentsLoading ? 'Running...' : fullVerdict ? 'Re-run Agents' : 'Run Agents'}
          </button>
        </div>
      )}

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

      {/* Automation footer — condensed to one line, drawer reveals full controls */}
      <AutomationFooter
        instrument={instrument}
        autoIbkrOn={autoIbkrOn}
        automationBusy={automationBusy}
        automation={automationState}
        qtyDraft={qtyDraft}
        setQtyDraft={setQtyDraft}
        commitQty={commitQty}
        onToggleAutoIbkr={onToggleAutoIbkr}
        latestDecision={latestAutomationDecision}
        decisions={automationDecisions}
        summary={summary}
        collapsed={automationCollapsed}
        setCollapsed={setAutomationCollapsed}
        activeBrokerAccountId={activeBrokerAccountId}
        profileControlsAvailable={profileControlsAvailable}
        profileTargets={profileTargets}
        onChangeExecutionProfile={onChangeExecutionProfile}
        onValidateScalpProfile={onValidateScalpProfile}
      />
    </div>
  );
}

// ── Trade Blueprint ──────────────────────────────────────────────────────────

interface TradeStats {
  riskPts: number;
  rewardPts: number;
  rrRatio: number;
  riskUsd: number | null;
  notionalUsd: number | null;
  livePnlPts: number | null;
  livePnlUsd: number | null;
}

interface ProfileTargets {
  legacyTp: number;
  scalp05RTp: number;
  benchmark1RTp: number;
}

function computeTradeStats(
  plan: PlaybookPlan | null,
  direction: 'LONG' | 'SHORT' | null,
  quantity: number,
  livePrice: number | null,
): TradeStats | null {
  if (!plan || !direction) return null;
  const { entryPrice, stopLoss, takeProfit1, contractMultiplier } = plan;
  const riskPts = Math.abs(entryPrice - stopLoss);
  const rewardPts = Math.abs(takeProfit1 - entryPrice);
  const rrRatio = riskPts > 0 ? rewardPts / riskPts : 0;
  const mult = contractMultiplier ?? null;
  const qty = Math.max(1, quantity);
  const riskUsd = mult != null ? riskPts * mult * qty : null;
  const notionalUsd = mult != null ? entryPrice * mult * qty : null;
  let livePnlPts: number | null = null;
  let livePnlUsd: number | null = null;
  if (livePrice != null && Number.isFinite(livePrice)) {
    livePnlPts = direction === 'LONG' ? (livePrice - entryPrice) : (entryPrice - livePrice);
    livePnlUsd = mult != null ? livePnlPts * mult * qty : null;
  }
  return { riskPts, rewardPts, rrRatio, riskUsd, notionalUsd, livePnlPts, livePnlUsd };
}

function computeProfileTargets(
  plan: PlaybookPlan | null,
  direction: 'LONG' | 'SHORT' | null,
): ProfileTargets | null {
  if (!plan || !direction) return null;
  const riskPts = Math.abs(plan.entryPrice - plan.stopLoss);
  if (riskPts <= 0) return null;
  const sign = direction === 'LONG' ? 1 : -1;
  return {
    legacyTp: plan.takeProfit1,
    scalp05RTp: plan.entryPrice + sign * riskPts * 0.5,
    benchmark1RTp: plan.entryPrice + sign * riskPts,
  };
}

function TradeBlueprint({
  plan,
  direction,
  livePrice,
  stats,
}: {
  plan: PlaybookPlan;
  direction: 'LONG' | 'SHORT';
  livePrice: number | null;
  stats: TradeStats | null;
}) {
  const dirBadge = direction === 'LONG' ? 'bg-emerald-900/50 text-emerald-300' : 'bg-red-900/50 text-red-300';
  const dirArrow = direction === 'LONG' ? '▲' : '▼';
  const rr = stats?.rrRatio ?? plan.rrRatio;
  const rrColor =
    rr >= 2 ? 'text-emerald-400'
    : rr >= 1 ? 'text-amber-400'
    : 'text-red-400 line-through';

  return (
    <div className="border border-zinc-700 rounded p-2.5 space-y-2 bg-zinc-950/30">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2">
          <span className={`text-[10px] font-semibold uppercase tracking-wider px-1.5 py-0.5 rounded ${dirBadge}`}>
            {dirArrow} {direction}
          </span>
          <span className="text-[10px] font-semibold text-zinc-500 uppercase tracking-wider">Trade Blueprint</span>
        </div>
        <span className={`text-xs font-mono font-semibold ${rrColor}`} title="Risk-to-reward ratio (Entry→TP1 vs Entry→SL)">
          R:R {rr.toFixed(2)}:1
        </span>
      </div>

      {/* SL · Entry · TP1 · TP2 — horizontal price row */}
      <div className="grid grid-cols-4 gap-2 text-center">
        <PricePillar label="SL" value={plan.stopLoss} accent="bad" rationale={plan.slRationale} sub={stats ? `−${stats.riskPts.toFixed(2)} pts` : undefined} />
        <PricePillar label="ENTRY" value={plan.entryPrice} accent="neutral" />
        <PricePillar label="TP1" value={plan.takeProfit1} accent="good" rationale={plan.tp1Rationale} sub={stats ? `+${stats.rewardPts.toFixed(2)} pts` : undefined} />
        <PricePillar label="TP2" value={plan.takeProfit2} accent="goodMuted" />
      </div>

      {/* R:R progress bar with live cursor */}
      <RRProgressBar
        sl={plan.stopLoss}
        entry={plan.entryPrice}
        tp1={plan.takeProfit1}
        tp2={plan.takeProfit2}
        livePrice={livePrice}
        direction={direction}
      />

      {/* Live P&L + risk/notional readout */}
      <div className="flex items-center justify-between text-[11px] font-mono pt-0.5">
        <div className="text-zinc-400">
          {livePrice != null ? (
            <>
              Spot <span className="text-zinc-200">{livePrice.toFixed(2)}</span>
              {stats?.livePnlPts != null && (
                <span className={stats.livePnlPts >= 0 ? 'text-emerald-400 ml-2' : 'text-red-400 ml-2'}>
                  {stats.livePnlPts >= 0 ? '+' : ''}{stats.livePnlPts.toFixed(2)} pts
                  {stats.livePnlUsd != null && (
                    <> ({stats.livePnlUsd >= 0 ? '+' : ''}${Math.abs(stats.livePnlUsd).toFixed(0)})</>
                  )}
                </span>
              )}
            </>
          ) : (
            <span className="text-zinc-600">awaiting spot…</span>
          )}
        </div>
        <div className="text-zinc-500">
          Risk <span className="text-zinc-300">{stats?.riskUsd != null ? `$${stats.riskUsd.toFixed(0)}` : `${stats?.riskPts.toFixed(2) ?? '—'} pts`}</span>
          {stats?.notionalUsd != null && (
            <> · Notional <span className="text-zinc-300">${formatLarge(stats.notionalUsd)}</span></>
          )}
        </div>
      </div>
    </div>
  );
}

function PricePillar({
  label, value, accent, rationale, sub,
}: {
  label: string;
  value: number;
  accent: 'good' | 'goodMuted' | 'bad' | 'neutral';
  rationale?: string;
  sub?: string;
}) {
  const color =
    accent === 'good' ? 'text-emerald-400'
    : accent === 'goodMuted' ? 'text-emerald-500/70'
    : accent === 'bad' ? 'text-red-400'
    : 'text-zinc-100';
  return (
    <div className="rounded bg-zinc-900/40 px-2 py-1.5" title={rationale}>
      <div className="text-[9px] text-zinc-500 uppercase tracking-wider">{label}</div>
      <div className={`text-sm font-mono font-semibold ${color}`}>{value.toFixed(2)}</div>
      {sub && <div className="text-[9px] text-zinc-500 font-mono">{sub}</div>}
    </div>
  );
}

function RRProgressBar({
  sl, entry, tp1, tp2, livePrice, direction,
}: {
  sl: number; entry: number; tp1: number; tp2: number;
  livePrice: number | null;
  direction: 'LONG' | 'SHORT';
}) {
  // Normalize bar axis: always min → max so the visual is left-to-right
  // regardless of LONG/SHORT. Profit direction is encoded by color, not by
  // axis direction (matches TradingView's convention).
  const bounds = [sl, entry, tp1, tp2];
  const min = Math.min(...bounds);
  const max = Math.max(...bounds);
  const range = max - min;
  if (range <= 0) return null;

  const pct = (p: number) => `${Math.max(0, Math.min(100, ((p - min) / range) * 100))}%`;
  const entryPct = pct(entry);
  const tp1Pct = pct(tp1);

  // Live price may be outside [min, max] — clamp visually but show indicator
  const liveInRange = livePrice != null && livePrice >= min && livePrice <= max;
  const livePct = livePrice != null ? pct(livePrice) : null;

  // Profit segment: from entry to livePrice, green if profitable, red if losing
  let fillLeft = entryPct;
  let fillWidth = '0%';
  let fillColor = 'bg-zinc-700';
  if (livePrice != null && range > 0) {
    const livePos = ((Math.max(min, Math.min(max, livePrice)) - min) / range) * 100;
    const entryPos = ((entry - min) / range) * 100;
    const profitable =
      direction === 'LONG' ? livePrice >= entry : livePrice <= entry;
    fillLeft = `${Math.min(livePos, entryPos)}%`;
    fillWidth = `${Math.abs(livePos - entryPos)}%`;
    fillColor = profitable ? 'bg-emerald-500/40' : 'bg-red-500/40';
  }

  return (
    <div className="relative h-6 select-none" aria-label="R:R progress bar">
      {/* Base track */}
      <div className="absolute inset-x-0 top-1/2 -translate-y-1/2 h-1.5 rounded bg-zinc-800" />
      {/* Profit/loss fill from entry to live */}
      <div
        className={`absolute top-1/2 -translate-y-1/2 h-1.5 rounded ${fillColor}`}
        style={{ left: fillLeft, width: fillWidth }}
      />
      {/* SL tick (left edge or right edge depending on direction) */}
      <Tick pct={pct(sl)} color="bg-red-500" label="SL" />
      {/* Entry tick */}
      <Tick pct={entryPct} color="bg-zinc-100" label="E" tall />
      {/* TP1 tick */}
      <Tick pct={tp1Pct} color="bg-emerald-500" label="TP" />
      {/* TP2 tick (muted) */}
      <Tick pct={pct(tp2)} color="bg-emerald-500/40" />
      {/* Live cursor */}
      {livePct != null && (
        <div
          className={`absolute top-0 h-full w-0.5 ${liveInRange ? 'bg-cyan-400' : 'bg-amber-400'}`}
          style={{ left: livePct }}
          title={`Spot ${livePrice!.toFixed(2)}${liveInRange ? '' : ' (out of range)'}`}
        />
      )}
    </div>
  );
}

function Tick({ pct, color, label, tall }: { pct: string; color: string; label?: string; tall?: boolean }) {
  return (
    <>
      <div
        className={`absolute top-1/2 -translate-y-1/2 -translate-x-1/2 ${color} rounded-sm ${
          tall ? 'h-4 w-1' : 'h-3 w-0.5'
        }`}
        style={{ left: pct }}
      />
      {label && (
        <div
          className="absolute top-full mt-0.5 -translate-x-1/2 text-[8px] font-mono text-zinc-500 uppercase tracking-wider"
          style={{ left: pct }}
        >
          {label}
        </div>
      )}
    </>
  );
}

// ── Automation Footer ────────────────────────────────────────────────────────

function AutomationFooter({
  instrument,
  autoIbkrOn, automationBusy, automation, qtyDraft, setQtyDraft, commitQty,
  onToggleAutoIbkr, latestDecision, decisions, summary, collapsed, setCollapsed, activeBrokerAccountId,
  profileControlsAvailable, profileTargets, onChangeExecutionProfile, onValidateScalpProfile,
}: {
  instrument: string;
  autoIbkrOn: boolean;
  automationBusy: boolean;
  automation: PlaybookAutomationView;
  qtyDraft: string;
  setQtyDraft: (v: string) => void;
  commitQty: () => Promise<void>;
  onToggleAutoIbkr: () => Promise<void>;
  latestDecision: PlaybookAutomationDecisionView | null;
  decisions: PlaybookAutomationDecisionView[];
  summary: PlaybookAutomationProfitabilitySummaryView | null;
  collapsed: boolean;
  setCollapsed: (next: boolean) => void;
  activeBrokerAccountId: string | null;
  profileControlsAvailable: boolean;
  profileTargets: ProfileTargets | null;
  onChangeExecutionProfile: (profile: PlaybookExecutionProfile) => Promise<void>;
  onValidateScalpProfile: () => Promise<void>;
}) {
  const armedProfile = automation.armedProfile ?? 'LEGACY';
  return (
    <div className={`border rounded text-[10px] ${autoIbkrOn ? 'border-red-700/70 bg-red-950/10' : 'border-zinc-800 bg-zinc-950/40'}`}>
      <div className="flex items-center justify-between gap-2 px-2 py-1.5">
        <div className="flex items-center gap-2 flex-wrap">
          <button
            type="button"
            onClick={onToggleAutoIbkr}
            disabled={automationBusy}
            aria-pressed={autoIbkrOn}
            title={autoIbkrOn ? 'Disable PLAYBOOK Auto-IBKR routing' : 'Enable PLAYBOOK Auto-IBKR routing (confirmation required)'}
            className={`rounded border px-1.5 py-0.5 font-semibold transition-colors disabled:opacity-50 ${
              autoIbkrOn
                ? 'border-red-600/70 bg-red-950/40 text-red-300 hover:bg-red-950/60'
                : 'border-zinc-700 text-zinc-400 hover:border-emerald-700 hover:text-emerald-300'
            }`}
          >
            ● Auto-IBKR {autoIbkrOn ? 'ON' : 'OFF'}
          </button>
          <span className="text-zinc-500">
            {formatProfileLabel(armedProfile)} · Paper {automation.paperThreshold} · Live {automation.liveThreshold} · {automation.quantity}ct · {activeBrokerAccountId ?? 'no acct'}
          </span>
          {latestDecision && (
            <>
              <span className="text-zinc-700">|</span>
              <span className="text-zinc-500">Last:</span>
              <RoutingChip outcome={latestDecision.routingOutcome ?? null} errorMessage={latestDecision.routingErrorMessage ?? null} />
              <SimulationChip status={latestDecision.simulationStatus ?? null} />
            </>
          )}
        </div>
        <button
          type="button"
          onClick={() => setCollapsed(!collapsed)}
          className="text-zinc-500 hover:text-zinc-300 font-mono px-1.5 py-0.5 rounded hover:bg-zinc-800 transition-colors"
          aria-expanded={!collapsed}
        >
          {collapsed ? '▾ stats' : '▴ hide'}
        </button>
      </div>
      {!collapsed && (
        <div className="border-t border-zinc-800 px-2 py-2 space-y-2">
          {profileControlsAvailable && (
            <div className="rounded border border-cyan-900/50 bg-cyan-950/10 px-2 py-2 space-y-2">
              <div className="flex items-center gap-2 flex-wrap">
                <span className="text-zinc-500">Execution profile</span>
                <select
                  value={armedProfile === 'MGC_10M_NORMAL_1R_BENCHMARK' ? 'LEGACY' : armedProfile}
                  onChange={event => {
                    void onChangeExecutionProfile(event.target.value as PlaybookExecutionProfile);
                  }}
                  disabled={automationBusy || autoIbkrOn}
                  className="rounded border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-zinc-200 hover:border-cyan-700 focus:border-cyan-600 focus:outline-none disabled:opacity-50"
                  title={autoIbkrOn ? 'Disable Auto-IBKR before changing execution profile' : 'Profile armed for PLAYBOOK Auto-IBKR'}
                >
                  <option value="LEGACY">Legacy</option>
                  {instrument === 'MGC' && (
                    <option value="MGC_10M_SCALP_0_5R">MGC 10m Scalp 0.5R</option>
                  )}
                  {instrument === 'MNQ' && (
                    <option value="MNQ_10M_CONFIRMATION">MNQ 10m Confirmation (paper)</option>
                  )}
                </select>
                {armedProfile === 'MGC_10M_SCALP_0_5R' && !automation.scalpProfileValidated && (
                  <button
                    type="button"
                    onClick={() => { void onValidateScalpProfile(); }}
                    disabled={automationBusy}
                    className="rounded border border-amber-700/70 bg-amber-950/30 px-1.5 py-0.5 font-semibold text-amber-300 hover:bg-amber-950/50 disabled:opacity-50"
                  >
                    Validate manually
                  </button>
                )}
                {armedProfile === 'MGC_10M_SCALP_0_5R' && automation.scalpProfileValidated && (
                  <span className="rounded border border-emerald-800/60 bg-emerald-950/40 px-1.5 py-0.5 font-semibold text-emerald-300">
                    Manual validation OK
                  </span>
                )}
              </div>
              {profileTargets && (
                <div className="grid grid-cols-3 gap-2">
                  <Metric label="Legacy TP" value={profileTargets.legacyTp.toFixed(2)} />
                  <Metric label="0.5R TP" value={profileTargets.scalp05RTp.toFixed(2)} accent="good" />
                  <Metric label="1R benchmark" value={profileTargets.benchmark1RTp.toFixed(2)} />
                </div>
              )}
              {!profileTargets && (
                <div className="text-[10px] text-zinc-500">
                  Targets appear when the current best setup is BREAK_RETEST.
                </div>
              )}
              <div className="text-[10px] text-zinc-500">
                1R is benchmark-only until candle replay is implemented.
              </div>
            </div>
          )}
          <div className="flex items-center gap-2 flex-wrap">
            <label className="flex items-center gap-1.5 text-zinc-400" title="Contracts submitted for Auto-IBKR live decisions">
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
                    setQtyDraft(String(automation.quantity));
                    (event.target as HTMLInputElement).blur();
                  }
                }}
                disabled={automationBusy}
                className="w-12 rounded border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-zinc-200 hover:border-cyan-700 focus:border-cyan-600 focus:outline-none disabled:opacity-50"
              />
            </label>
            <span className="text-zinc-600">Thresholds are read-only (driven by backend config)</span>
          </div>
          <StreamedResults decisions={decisions} summary={summary} />
        </div>
      )}
    </div>
  );
}

function StreamedResults({
  decisions,
  summary,
}: {
  decisions: PlaybookAutomationDecisionView[];
  summary: PlaybookAutomationProfitabilitySummaryView | null;
}) {
  const confirmation = decisions.filter(d => d.entryType === 'STOP');
  const legacy = decisions.filter(d => d.entryType !== 'STOP');
  // Single-stream panels (legacy-only, or confirmation-only once the challenger is retired)
  // render one unlabelled block. Both present (transitional history) → two labelled blocks.
  if (confirmation.length === 0 || legacy.length === 0) {
    return (
      <>
        <ProfitabilitySummary summary={summary} />
        <RecentSimulationResults decisions={decisions} />
      </>
    );
  }
  return (
    <>
      <div className="flex items-center gap-2">
        <span className="rounded border border-cyan-800/60 bg-cyan-950/40 px-1.5 py-0.5 text-[9px] font-semibold text-cyan-300">
          CONFIRMATION
        </span>
        <span className="text-[9px] text-zinc-600">stop à la sortie de zone · brackets ATR</span>
      </div>
      <ProfitabilitySummary summary={summarizeAutomationDecisions(confirmation)} />
      <RecentSimulationResults decisions={confirmation} />
      <div className="flex items-center gap-2 pt-1">
        <span className="rounded border border-zinc-700 bg-zinc-900 px-1.5 py-0.5 text-[9px] font-semibold text-zinc-400">
          LEGACY · retiré
        </span>
        <span className="text-[9px] text-zinc-600">historique paper résiduel</span>
      </div>
      <ProfitabilitySummary summary={summarizeAutomationDecisions(legacy)} />
      <RecentSimulationResults decisions={legacy} />
    </>
  );
}

// ── Atoms (preserved from previous version) ──────────────────────────────────

function FilterChip({ label, pass, tooltip, warn }: { label: string; pass: boolean; tooltip: string; warn?: boolean }) {
  const icon = pass ? (warn ? '!' : '✓') : '✗';
  const style = pass
    ? warn ? 'bg-amber-950/50 text-amber-300 border-amber-800/50' : 'bg-emerald-950/50 text-emerald-300 border-emerald-800/50'
    : 'bg-red-950/50 text-red-300 border-red-800/50';
  return (
    <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded border ${style}`} title={tooltip}>
      {icon} {label}
    </span>
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

function RecentSimulationResults({
  decisions,
}: {
  decisions: PlaybookAutomationDecisionView[];
}) {
  if (decisions.length === 0) {
    return null;
  }
  return (
    <div className="rounded border border-zinc-800 bg-zinc-950/40">
      <div className="grid grid-cols-[3.5rem_4rem_1fr_1fr_1fr_1fr_3rem_4rem] gap-1 border-b border-zinc-800 px-2 py-1 text-[9px] uppercase tracking-wider text-zinc-600">
        <span>Result</span>
        <span>Side</span>
        <span>E</span>
        <span>SL</span>
        <span>TP1</span>
        <span>TP2</span>
        <span>R:R</span>
        <span>P&L</span>
      </div>
      {decisions.slice(0, 5).map(decision => (
        <div
          key={decision.id ?? `${decision.createdAt}-${decision.direction}`}
          className="grid grid-cols-[3.5rem_4rem_1fr_1fr_1fr_1fr_3rem_4rem] gap-1 border-b border-zinc-900 px-2 py-1 font-mono text-[10px] last:border-b-0"
          title={decision.verdict ?? undefined}
        >
          <span className={simulationTextColor(decision.simulationStatus)}>
            {shortSimulationStatus(decision.simulationStatus)}
          </span>
          <span className={decision.direction === 'LONG' ? 'text-emerald-300' : decision.direction === 'SHORT' ? 'text-red-300' : 'text-zinc-500'}>
            {decision.direction ?? '-'}
          </span>
          <span className="text-zinc-300">{formatPrice(decision.entryPrice)}</span>
          <span className="text-red-300">{formatPrice(decision.stopLoss)}</span>
          <span className="text-emerald-300">{formatPrice(decision.takeProfit1)}</span>
          <span className="text-emerald-500/80">{formatPrice(decision.takeProfit2)}</span>
          <span className={(decision.rrRatio ?? 0) >= 1 ? 'text-amber-300' : 'text-red-300'}>
            {formatMetric(decision.rrRatio, 2)}
          </span>
          <span className={(decision.pnl ?? decision.simulationPnl ?? 0) >= 0 ? 'text-emerald-300' : 'text-red-300'}>
            {formatCurrency(decision.pnl ?? decision.simulationPnl)}
          </span>
        </div>
      ))}
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

function formatPrice(value: number | null | undefined): string {
  if (value == null || !Number.isFinite(value)) return '-';
  return value.toFixed(2);
}

function shortSimulationStatus(status: PlaybookAutomationSimulationStatus | null): string {
  if (!status) return '-';
  const normalized = status.toUpperCase();
  if (normalized === 'PENDING_ENTRY') return 'PEND';
  return normalized.slice(0, 5);
}

function simulationTextColor(status: PlaybookAutomationSimulationStatus | null): string {
  const normalized = status?.toUpperCase();
  if (normalized === 'WIN') return 'text-emerald-300';
  if (normalized === 'LOSS' || normalized === 'CANCELLED') return 'text-red-300';
  if (normalized === 'ACTIVE') return 'text-cyan-300';
  if (normalized === 'MISSED' || normalized === 'PENDING_ENTRY') return 'text-amber-300';
  return 'text-zinc-500';
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

function formatProfileLabel(profile: PlaybookExecutionProfile): string {
  switch (profile) {
    case 'MGC_10M_SCALP_0_5R':
      return 'Scalp 0.5R';
    case 'MGC_10M_NORMAL_1R_BENCHMARK':
      return '1R benchmark';
    case 'MNQ_10M_CONFIRMATION':
      return 'Confirmation (paper)';
    case 'LEGACY':
    default:
      return 'Legacy';
  }
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

function formatLarge(value: number): string {
  if (Math.abs(value) >= 1_000_000) return `${(value / 1_000_000).toFixed(2)}M`;
  if (Math.abs(value) >= 1_000) return `${(value / 1_000).toFixed(1)}k`;
  return value.toFixed(0);
}

function formatRelative(runAt: number): string {
  const seconds = Math.max(0, Math.floor((Date.now() - runAt) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.floor(hours / 24)}d ago`;
}
