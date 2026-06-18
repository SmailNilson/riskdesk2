'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { api, PortfolioSummary, IndicatorSnapshot, type ActivePositionView } from '@/app/lib/api';
import { useWebSocket } from '@/app/hooks/useWebSocket';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import { useActivePositions } from '@/app/hooks/useActivePositions';
import { useMobileBadges } from '@/app/hooks/useMobileBadges';

// === Cockpit (Trade Desk V2) ===
import VitalHeader from '@/app/components/cockpit/VitalHeader';
import CommandPalette, { type PaletteAction } from '@/app/components/cockpit/CommandPalette';
import BottomRail, {
  type AlertItem as BottomRailAlertItem,
  type PositionItem as BottomRailPositionItem,
  type FillItem as BottomRailFillItem,
} from '@/app/components/cockpit/BottomRail';
import CockpitTabs from '@/app/components/cockpit/CockpitTabs';
import RailTabs from '@/app/components/cockpit/RailTabs';
import WtxFamilyPanel from '@/app/components/cockpit/WtxFamilyPanel';

// === Mobile cockpit additions ===
import OfflineBanner from '@/app/components/mobile/OfflineBanner';

// === Existing panels (unchanged imports) ===
import RolloverBanner from './RolloverBanner';
import Chart from './Chart';
import DxyPanel from './DxyPanel';
import IndicatorPanel from './IndicatorPanel';
import StrategyPanel from './StrategyPanel';
import Quant7GatesSimulationPanel from './quant/Quant7GatesSimulationPanel';
import QuantSetupNotification from './quant/QuantSetupNotification';
import { QuantStreamProvider } from '@/app/hooks/useQuantStream';
import { QUANT_INSTRUMENTS } from './quant/types';
import BacktestPanel from './BacktestPanel';
import IbkrPortfolioPanel from './IbkrPortfolioPanel';
import OrderFlowPanel from './OrderFlowPanel';
import PlaybookPanel from './PlaybookPanel';
import FootprintChart from './FootprintChart';
import TickChart from './TickChart';
import FlashCrashPanel from './FlashCrashPanel';
import CorrelationPanel from './CorrelationPanel';
import ExternalSetupPanel from './ExternalSetupPanel';
import MarketableSettingsControl from './MarketableSettingsControl';
import CollapsibleZone, { useCollapsibleZoneState } from './layout/CollapsibleZone';
import MobileVitalStrip from './mobile/MobileVitalStrip';
import MobileInstrumentPills from './mobile/MobileInstrumentPills';
import MobileCollapse from './mobile/MobileCollapse';
import OrderTicketSheet from './mobile/OrderTicketSheet';
import MobilePositionsCard from './mobile/MobilePositionsCard';
import WtxStrategyPanel from './WtxStrategyPanel';
import { CandlestickIcon, BoltIcon, FlaskIcon, TargetIcon, BriefcaseIcon } from './mobile/TabIcons';
import { DEFAULT_TIMEZONE, findTimezoneByTz, TIMEZONES, type TzEntry } from '@/app/lib/timezones';

const INSTRUMENTS = ['MCL', 'MGC', 'E6', 'MNQ'] as const;
const TICKER_INSTRUMENTS = [...INSTRUMENTS, 'DXY'] as const;
type Instrument = typeof INSTRUMENTS[number];
const TIMEFRAMES = ['5m', '10m', '1h', '1d'] as const;
type Timeframe = typeof TIMEFRAMES[number];

/** Mobile bottom-nav tabs — the focused feature set kept on small screens.
    Everything else (OrderFlow, Footprint, FlashCrash, Backtest, AlertsFeed,
    the full lightweight-charts Chart…) is desktop-only and never mounted on
    mobile, so its WS subscriptions and polling never start there. */
const MOBILE_TABS = [
  { key: 'chart', Icon: CandlestickIcon, label: 'Chart' },
  { key: 'wtx', Icon: BoltIcon, label: 'WTX' },
  { key: 'quant', Icon: FlaskIcon, label: 'Quant' },
  { key: 'playbook', Icon: TargetIcon, label: 'Playbook' },
  { key: 'portfolio', Icon: BriefcaseIcon, label: 'Portf' },
] as const;
type MobileTab = typeof MOBILE_TABS[number]['key'];

const WORKING_STATUSES = new Set(['PENDING_ENTRY_SUBMISSION', 'ENTRY_SUBMITTED', 'ENTRY_PARTIALLY_FILLED']);
const OPEN_STATUSES = new Set(['ACTIVE', 'VIRTUAL_EXIT_TRIGGERED']);
const FILL_RING_CAP = 20;

function toNum(v: number | string | null | undefined): number | null {
  if (v == null) return null;
  if (typeof v === 'number') return Number.isFinite(v) ? v : null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

export default function Dashboard() {
  const [instrument, setInstrument] = useState<Instrument>('MNQ');
  const [timeframe, setTimeframe] = useState<Timeframe>('10m');
  const [timezone, setTimezone] = useState<TzEntry>(DEFAULT_TIMEZONE);
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');
  const [summary, setSummary] = useState<PortfolioSummary | null>(null);
  const [snapshot, setSnapshot] = useState<IndicatorSnapshot | null>(null);
  const [selectedIbkrAccountId, setSelectedIbkrAccountId] = useState<string | undefined>(undefined);
  const [purging, setPurging] = useState(false);
  const [purgeMsg, setPurgeMsg] = useState<string | null>(null);

  // Mobile layout — `null` until the viewport is known (first client render),
  // so neither panel tree mounts prematurely.
  const isMobile = useIsMobile();
  const [mobileTab, setMobileTab] = useState<MobileTab>('chart');
  const [ticketOpen, setTicketOpen] = useState(false);

  // Trade Desk cockpit state
  const [commandPaletteOpen, setCommandPaletteOpen] = useState(false);
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  // Focus mode collapses both rails so the user can concentrate on the chart.
  // We snapshot the previous collapsed-state and restore it on exit.
  const [focusMode, setFocusMode] = useState(false);
  const focusRestoreRef = useRef<{ left: boolean; right: boolean } | null>(null);
  // BottomRail snooze ring (per-session, like the old AlertsFeed local filter).
  const [snoozedAlerts, setSnoozedAlerts] = useState<Set<string>>(new Set());

  const handlePurge = useCallback(async () => {
    if (purging) return;
    if (!window.confirm(
      `Purge all ${instrument} candles (all timeframes) and trigger IBKR re-backfill?\n\nThis is destructive — existing DB history for ${instrument} will be wiped.`
    )) return;
    setPurging(true);
    setPurgeMsg(null);
    try {
      const purgeRes = await api.purgeInstrument(instrument);
      if (purgeRes.error) {
        setPurgeMsg(`Error: ${purgeRes.error}`);
        return;
      }
      await api.refreshDb();
      setPurgeMsg(`Purged ${purgeRes.purged ?? 0} • Refill started`);
    } catch (e) {
      console.error(`Purge ${instrument} failed:`, e);
      setPurgeMsg('Error');
    } finally {
      setPurging(false);
      setTimeout(() => setPurgeMsg(null), 6000);
    }
  }, [instrument, purging]);

  const {
    prices, alerts, wtxSignals,
    wtxRsiSignals, wtxRsiStates,
    connected,
  } = useWebSocket();

  // Active positions — desktop BottomRail + mobile badges share the same hook
  // so we only open one /topic/positions subscription per page.
  const activePositions = useActivePositions();

  // Zone collapse state is hoisted so the grid's track widths follow the
  // actual zone state — without this, the `auto` tracks would shrink below
  // the zone's min-width because the center column (Chart + OrderFlow) forces
  // `1fr` to claim the remaining space and squeezes the sides.
  const leftZone = useCollapsibleZoneState('left-context');
  const rightZone = useCollapsibleZoneState('right-ai-desk');

  // Track widths (lg and up). The right zone is wider than the left because
  // it stacks several dense strategy/quant panels.
  const leftTrack = leftZone.collapsed ? '2.5rem' : '320px';
  const rightTrack = rightZone.collapsed ? '2.5rem' : '440px';
  const gridTemplateColumns = `${leftTrack} minmax(0, 1fr) ${rightTrack}`;

  const loadSummary = useCallback(async () => {
    try { setSummary(await api.getPortfolioSummary(selectedIbkrAccountId)); } catch {}
  }, [selectedIbkrAccountId]);

  const loadSnapshot = useCallback(async () => {
    try { setSnapshot(await api.getIndicators(instrument, timeframe)); } catch {}
  }, [instrument, timeframe]);

  // Initial load
  useEffect(() => { loadSummary(); }, [loadSummary]);
  useEffect(() => { loadSnapshot(); }, [loadSnapshot]);

  // Resolve the broker's default account up-front, independent of the IBKR
  // portfolio panel. That panel lifts its selected account to this state, but it
  // is UNMOUNTED when the left zone is collapsed (CollapsibleZone drops children),
  // so a user trading from the chart with the left zone closed would send a null
  // account and the backend 409s ("a brokerAccountId … is required"). Only fills
  // when the user hasn't already picked one, so a manual portfolio selection wins.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      try {
        const snap = await api.getIbkrPortfolio();
        if (cancelled) return;
        const acct = snap.selectedAccountId;
        if (acct) setSelectedIbkrAccountId(prev => prev ?? acct);
      } catch {
        /* IBKR unavailable — the chart-trading ticket guards on a null account. */
      }
    })();
    return () => { cancelled = true; };
  }, []);

  // Refresh summary every 5s (syncs with backend polling)
  useEffect(() => {
    const id = setInterval(loadSummary, 5000);
    return () => clearInterval(id);
  }, [loadSummary]);

  // Refresh indicators every 30s
  useEffect(() => {
    const id = setInterval(loadSnapshot, 30_000);
    return () => clearInterval(id);
  }, [loadSnapshot]);

  // ─── WTX signal split per timeframe variant ────────────────────────────────
  // WtxFamilyPanel expects 5 pre-filtered arrays so each underlying panel sees
  // only its own signal stream. The /topic/wtx-signals fan-out emits a single
  // ring buffer keyed by timeframe, so we partition on every change.
  const wtx5m = useMemo(() => wtxSignals.filter(s => s.timeframe === '5m'), [wtxSignals]);
  const wtx10m = useMemo(() => wtxSignals.filter(s => s.timeframe === '10m'), [wtxSignals]);
  const wtxZ35 = useMemo(() => wtxSignals.filter(s => s.timeframe === '10m-z35'), [wtxSignals]);
  const wtxZ40 = useMemo(() => wtxSignals.filter(s => s.timeframe === '10m-z40'), [wtxSignals]);

  // ─── Focus mode toggle ─────────────────────────────────────────────────────
  // Press F to collapse both rails; Esc (or F again) restores. We snapshot the
  // previous zone state in a ref so the user's actual preference comes back.
  const toggleFocusMode = useCallback(() => {
    setFocusMode(prev => {
      if (!prev) {
        focusRestoreRef.current = {
          left: leftZone.collapsed,
          right: rightZone.collapsed,
        };
        leftZone.setCollapsed(true);
        rightZone.setCollapsed(true);
        return true;
      }
      const snap = focusRestoreRef.current;
      if (snap) {
        leftZone.setCollapsed(snap.left);
        rightZone.setCollapsed(snap.right);
      }
      focusRestoreRef.current = null;
      return false;
    });
  }, [leftZone, rightZone]);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const isTypingTarget = (el: EventTarget | null): boolean => {
      if (!(el instanceof HTMLElement)) return false;
      const tag = el.tagName;
      return tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || el.isContentEditable;
    };
    const onKey = (e: KeyboardEvent) => {
      if (commandPaletteOpen) return; // Palette owns its keys; don't fight it.
      if (isTypingTarget(e.target)) return;
      if (e.metaKey || e.ctrlKey || e.altKey) return;
      if (e.key === 'f' || e.key === 'F') {
        e.preventDefault();
        toggleFocusMode();
      } else if (e.key === 'Escape' && focusMode) {
        e.preventDefault();
        toggleFocusMode();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [commandPaletteOpen, focusMode, toggleFocusMode]);

  // ─── BottomRail data aggregation ───────────────────────────────────────────
  // The BottomRail consumes generic shape; we adapt the WS/REST snapshots here
  // so the rail itself stays display-only.
  const bottomRailAlerts: BottomRailAlertItem[] = useMemo(() => {
    return alerts
      .map((a, idx): BottomRailAlertItem => ({
        key: a.key ?? `${a.timestamp}-${a.message ?? ''}-${idx}`,
        severity: a.severity,
        instrument: a.instrument,
        category: a.category,
        message: a.message,
        timestamp: a.timestamp,
      }))
      .filter(a => !snoozedAlerts.has(a.key));
  }, [alerts, snoozedAlerts]);

  const bottomRailPositions: BottomRailPositionItem[] = useMemo(() => {
    const rows = activePositions.positions;
    return rows
      .map((p: ActivePositionView): BottomRailPositionItem | null => {
        const status = p.status;
        const isWorking = WORKING_STATUSES.has(status);
        const isOpen = OPEN_STATUSES.has(status);
        if (!isWorking && !isOpen) return null;
        const side: 'LONG' | 'SHORT' = (p.direction || '').toUpperCase() === 'SHORT' ? 'SHORT' : 'LONG';
        const avg = toNum(isWorking ? (p.entryPrice ?? p.currentPrice) : p.entryPrice) ?? 0;
        return {
          instrument: p.instrument,
          side,
          qty: Math.abs(p.quantity ?? 0),
          avgPrice: avg,
          unrealizedPnl: toNum(p.pnlDollars),
          status: isWorking ? 'WORKING' : 'OPEN',
        };
      })
      .filter((p): p is BottomRailPositionItem => p !== null);
  }, [activePositions.positions]);

  // Recent fills — a rolling ring derived from /topic/positions transitions.
  // We watch executions whose status flips into a "filled-or-closed" terminal
  // bucket and prepend them to the ring; capped at FILL_RING_CAP so memory
  // stays bounded over a long session.
  const [recentFills, setRecentFills] = useState<BottomRailFillItem[]>([]);
  const lastStatusRef = useRef<Map<number, string>>(new Map());
  useEffect(() => {
    const map = lastStatusRef.current;
    const newFills: BottomRailFillItem[] = [];
    for (const p of activePositions.positions) {
      const prev = map.get(p.executionId);
      if (prev === p.status) continue;
      map.set(p.executionId, p.status);
      if (prev == null) continue; // first sighting — don't count as a fresh fill
      const transitioned = (() => {
        if (prev !== 'ACTIVE' && p.status === 'ACTIVE') return 'ENTRY';
        if (p.status === 'COMPLETED') return 'CLOSE';
        return null;
      })();
      if (!transitioned) continue;
      const side: BottomRailFillItem['side'] = transitioned === 'CLOSE'
        ? 'FLAT'
        : ((p.direction || '').toUpperCase() === 'SHORT' ? 'SHORT' : 'LONG');
      const price = toNum(p.currentPrice ?? p.entryPrice) ?? 0;
      newFills.push({
        timestamp: new Date().toISOString(),
        instrument: p.instrument,
        side,
        qty: Math.abs(p.quantity ?? 0),
        price,
        pnl: transitioned === 'CLOSE' ? toNum(p.pnlDollars) : null,
      });
    }
    if (newFills.length === 0) return;
    setRecentFills(prev => [...newFills, ...prev].slice(0, FILL_RING_CAP));
  }, [activePositions.positions]);

  // BottomRail groups alerts by (instrument, category) and emits the FIRST
  // alert's key when the user clicks a chip. To match user expectation
  // ("snooze that group"), we snooze every visible alert sharing the chip's
  // instrument+category — otherwise the chip's count would only drop by one.
  const handleSnoozeAlert = useCallback((firedKey: string) => {
    let target: typeof alerts[number] | undefined;
    for (let idx = 0; idx < alerts.length; idx += 1) {
      const a = alerts[idx];
      const k = a.key ?? `${a.timestamp}-${a.message ?? ''}-${idx}`;
      if (k === firedKey) { target = a; break; }
    }
    setSnoozedAlerts(prev => {
      const next = new Set(prev);
      if (!target) {
        next.add(firedKey);
        return next;
      }
      alerts.forEach((a, idx) => {
        if (a.instrument === target.instrument && a.category === target.category) {
          next.add(a.key ?? `${a.timestamp}-${a.message ?? ''}-${idx}`);
        }
      });
      return next;
    });
  }, [alerts]);

  const handleClearAlerts = useCallback(() => {
    setSnoozedAlerts(prev => {
      const next = new Set(prev);
      alerts.forEach((a, idx) => {
        // Must mirror bottomRailAlerts' fallback key (including idx) so the
        // synthesised key collides with the one shown in the rail.
        next.add(a.key ?? `${a.timestamp}-${a.message ?? ''}-${idx}`);
      });
      return next;
    });
  }, [alerts]);

  // ─── Mobile badges ─────────────────────────────────────────────────────────
  const mobilePositionsCount = useMemo(
    () => activePositions.positions.filter(p => WORKING_STATUSES.has(p.status) || OPEN_STATUSES.has(p.status)).length,
    [activePositions.positions],
  );
  const mobileBadges = useMobileBadges({
    alerts,
    wtxSignals,
    wtxRsiSignals,
    positionsCount: mobilePositionsCount,
  });
  const badgeForTab = useCallback((tab: MobileTab): number => {
    switch (tab) {
      case 'chart':     return mobileBadges.alertCount;
      case 'wtx':       return mobileBadges.wtxSignalCount + mobileBadges.wtxRsiSignalCount;
      case 'quant':     return mobileBadges.wtxRsiSignalCount;
      case 'playbook':  return 0;
      case 'portfolio': return mobileBadges.positionCount;
      default:          return 0;
    }
  }, [mobileBadges]);

  // ─── Command palette action catalog ────────────────────────────────────────
  const runAndClose = useCallback((fn: () => void) => {
    fn();
    setCommandPaletteOpen(false);
  }, []);

  const paletteActions = useMemo<PaletteAction[]>(() => {
    const acts: PaletteAction[] = [];
    INSTRUMENTS.forEach((inst, idx) => acts.push({
      id: `inst-${inst}`,
      group: 'Instrument',
      label: `Switch to ${inst}`,
      shortcut: `${idx + 1}`,
      run: () => runAndClose(() => setInstrument(inst)),
    }));
    TIMEFRAMES.forEach((tf, idx) => acts.push({
      id: `tf-${tf}`,
      group: 'Timeframe',
      label: `Timeframe ${tf}`,
      shortcut: `⇧${idx + 1}`,
      run: () => runAndClose(() => setTimeframe(tf)),
    }));
    acts.push({
      id: 'view-focus',
      group: 'View',
      label: focusMode ? 'Exit focus mode' : 'Enter focus mode',
      shortcut: 'F',
      run: () => runAndClose(toggleFocusMode),
    });
    acts.push({
      id: 'view-theme',
      group: 'View',
      label: theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme',
      run: () => runAndClose(() => setTheme(t => t === 'dark' ? 'light' : 'dark')),
    });
    acts.push({
      id: 'view-left',
      group: 'View',
      label: leftZone.collapsed ? 'Expand left rail' : 'Collapse left rail',
      run: () => runAndClose(() => leftZone.setCollapsed(!leftZone.collapsed)),
    });
    acts.push({
      id: 'view-right',
      group: 'View',
      label: rightZone.collapsed ? 'Expand right rail' : 'Collapse right rail',
      run: () => runAndClose(() => rightZone.setCollapsed(!rightZone.collapsed)),
    });
    // Timezones — too many to enumerate as top-level entries; expose the most
    // common ones (matches the legacy <select> first-page experience).
    for (const tz of TIMEZONES) {
      acts.push({
        id: `tz-${tz.tz}`,
        group: 'Timezone',
        label: tz.label,
        run: () => runAndClose(() => setTimezone(findTimezoneByTz(tz.tz))),
      });
    }
    acts.push({
      id: 'alerts-snooze',
      group: 'Alerts',
      label: 'Snooze every visible alert',
      run: () => runAndClose(handleClearAlerts),
    });
    acts.push({
      id: 'data-purge',
      group: 'Data',
      label: `Purge ${instrument} candles & re-backfill`,
      destructive: true,
      run: () => runAndClose(() => { void handlePurge(); }),
    });
    return acts;
  }, [
    focusMode, theme, leftZone, rightZone, instrument, handlePurge,
    handleClearAlerts, runAndClose, toggleFocusMode,
  ]);

  // VitalHeader displays a price ticker for the same instruments + DXY.
  // It's typed as Record<string, PriceCell> with optional `changePct`; our
  // PriceUpdate has extra fields and no changePct — structural subtyping
  // accepts both, the missing field renders as a dash.
  const headerPrices = prices as unknown as Parameters<typeof VitalHeader>[0]['prices'];

  return (
    <QuantStreamProvider instruments={QUANT_INSTRUMENTS}>
    <div className={`min-h-screen bg-zinc-950 text-white flex flex-col ${theme === 'light' ? 'light' : ''}`}>
      {/* Rollover warning — surfaces above the cockpit so it can't be hidden
          behind the sticky header on first load. */}
      <RolloverBanner />

      {/* VitalHeader replaces the legacy header + MetricsBar + price ticker.
          Desktop only — mobile uses MobileVitalStrip below (24px hero P&L). */}
      {isMobile === false && (
        <VitalHeader
          instrument={instrument}
          instruments={TICKER_INSTRUMENTS}
          onInstrumentChange={(i) => setInstrument(i as Instrument)}
          timeframe={timeframe}
          timeframes={TIMEFRAMES}
          onTimeframeChange={(tf) => setTimeframe(tf as Timeframe)}
          connected={connected}
          totalPnl={summary?.totalPnL ?? null}
          marginUsedPct={summary?.marginUsedPct ?? null}
          prices={headerPrices}
          onOpenCommandPalette={() => setCommandPaletteOpen(true)}
        />
      )}

      {/* Desktop secondary controls strip — timezone / marketable / purge / theme.
          VitalHeader's settings ⚙ is a v1 placeholder, so we keep these here. */}
      {isMobile === false && (
        <div className="hidden lg:flex items-center justify-end gap-2 px-3 py-1.5 bg-zinc-900/40 border-b border-zinc-800/50">
          <TimezoneSelect value={timezone} onChange={setTimezone} />
          <PurgeButton instrument={instrument} purging={purging} purgeMsg={purgeMsg} onPurge={handlePurge} />
          <MarketableSettingsControl />
          <ThemeToggle theme={theme} onToggle={() => setTheme(t => t === 'dark' ? 'light' : 'dark')} />
        </div>
      )}

      {/* Mobile top bar — logo + theme toggle + overflow menu. Restores access
          to timezone/purge/marketable controls that VitalHeader doesn't expose. */}
      {isMobile && (
        <header className="flex items-center justify-between px-3 py-2 bg-zinc-900 border-b border-zinc-800">
          <span className="text-sm font-mono font-medium tracking-wide text-zinc-300">RD</span>
          <div className="flex items-center gap-2">
            <ThemeToggle theme={theme} onToggle={() => setTheme(t => t === 'dark' ? 'light' : 'dark')} />
            <button
              onClick={() => setMobileMenuOpen(v => !v)}
              aria-label="Plus de contrôles"
              aria-expanded={mobileMenuOpen}
              className={`min-w-[44px] min-h-[44px] inline-flex items-center justify-center rounded-lg border text-base transition-colors select-none ${
                mobileMenuOpen
                  ? 'border-zinc-600 text-zinc-200 bg-zinc-800'
                  : 'border-zinc-800 text-zinc-400'
              }`}
            >⋯</button>
          </div>
        </header>
      )}

      {/* Mobile overflow menu — secondary operator controls. */}
      {isMobile && mobileMenuOpen && (
        <div className="flex flex-wrap items-center gap-2 px-3 py-2 bg-zinc-900 border-b border-zinc-800">
          <TimezoneSelect value={timezone} onChange={setTimezone} />
          <PurgeButton instrument={instrument} purging={purging} purgeMsg={purgeMsg} onPurge={handlePurge} />
          <MarketableSettingsControl />
        </div>
      )}

      {/* Mobile vital strip — status + total P&L always visible, expandable */}
      {isMobile && <OfflineBanner connected={connected} />}
      {isMobile && <MobileVitalStrip summary={summary} connected={connected} prices={prices} />}

      {/* Mobile instrument pills (live price inside) + timeframe selector */}
      {isMobile && (
        <div className="flex flex-col gap-2 px-3 py-2 bg-zinc-900 border-b border-zinc-800">
          <MobileInstrumentPills
            instruments={INSTRUMENTS}
            active={instrument}
            onChange={setInstrument}
            prices={prices}
          />
          <TabGroup options={TIMEFRAMES} value={timeframe} onChange={setTimeframe} grow />
        </div>
      )}

      {/* Mobile — focused single-panel view driven by the bottom tab bar.
          Only the active tab's panels are mounted, so hidden panels never
          fetch, poll, or subscribe. CockpitTabs / RailTabs are NEVER used
          on mobile — they would mount every child panel simultaneously,
          defeating the small-screen budget. */}
      {isMobile && (
        <main className="flex-1 flex flex-col gap-3 p-3 min-w-0 pb-[calc(4.5rem_+_env(safe-area-inset-bottom))]">
          {mobileTab === 'chart' && (
            <>
              <TickChart selectedInstrument={instrument} snapshot={snapshot} brokerAccountId={selectedIbkrAccountId} />
              <button
                onClick={() => setTicketOpen(true)}
                className="flex items-center justify-center gap-2 min-h-[48px] rounded-lg border border-emerald-900 bg-emerald-950/40 text-[13px] font-medium text-emerald-300 active:scale-[0.98] transition-transform"
              >
                Passer un ordre · {instrument}
              </button>
            </>
          )}
          {mobileTab === 'wtx' && (
            <>
              <WtxStrategyPanel instrument={instrument} timeframe="5m" liveSignals={wtxSignals} />
              <MobileCollapse title="WTX strategy" titleClassName="text-cyan-300" subtitle={`${instrument} · 10m`}>
                <WtxStrategyPanel instrument={instrument} timeframe="10m" liveSignals={wtxSignals} />
              </MobileCollapse>
              {instrument === 'MNQ' && (
                <MobileCollapse title="top-train-Z35" titleClassName="text-violet-300" subtitle={`${instrument} · 10m-z35`}>
                  <WtxStrategyPanel
                    instrument={instrument}
                    timeframe="10m-z35"
                    displayName="top-train-Z35"
                    liveSignals={wtxSignals}
                  />
                </MobileCollapse>
              )}
              {instrument === 'MNQ' && (
                <MobileCollapse title="top-train-Z40" titleClassName="text-violet-400" subtitle={`${instrument} · 10m-z40`}>
                  <WtxStrategyPanel
                    instrument={instrument}
                    timeframe="10m-z40"
                    displayName="top-train-Z40"
                    liveSignals={wtxSignals}
                  />
                </MobileCollapse>
              )}
            </>
          )}
          {mobileTab === 'quant' && <Quant7GatesSimulationPanel />}
          {mobileTab === 'playbook' && (
            <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
              <PlaybookPanel
                instrument={instrument}
                timeframe={timeframe}
                selectedBrokerAccountId={selectedIbkrAccountId}
                livePrice={prices[instrument]?.price ?? null}
              />
            </div>
          )}
          {mobileTab === 'portfolio' && (
            <>
              <MobilePositionsCard onNewOrder={() => setTicketOpen(true)} />
              <IbkrPortfolioPanel
                selectedAccountId={selectedIbkrAccountId}
                onAccountChange={setSelectedIbkrAccountId}
                onRefreshRequested={loadSummary}
              />
            </>
          )}
        </main>
      )}

      {/* Mobile bottom tab bar — badges sourced from useMobileBadges. */}
      {isMobile && (
        <nav className="fixed bottom-0 inset-x-0 z-40 bg-zinc-900 border-t border-zinc-800 pb-[env(safe-area-inset-bottom)]">
          <div className="grid grid-cols-5">
            {MOBILE_TABS.map(tab => {
              const count = badgeForTab(tab.key);
              return (
                <button
                  key={tab.key}
                  onClick={() => setMobileTab(tab.key)}
                  className={`relative flex flex-col items-center justify-center gap-1 h-14 text-[11px] font-medium transition-colors active:scale-[0.97] ${
                    mobileTab === tab.key ? 'text-emerald-400' : 'text-zinc-500'
                  }`}
                >
                  {mobileTab === tab.key && (
                    <span className="absolute top-0 w-5 h-0.5 rounded-full bg-emerald-400" />
                  )}
                  <tab.Icon />
                  {tab.label}
                  {count > 0 && (
                    <span className="absolute top-1 right-3 min-w-[16px] h-4 px-1 rounded-full bg-rose-500 text-white text-[9px] font-mono tabular-nums inline-flex items-center justify-center">
                      {count > 99 ? '99+' : count}
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        </nav>
      )}

      {/* Desktop — 3-zone grid. The grid-template-columns is driven by the
          actual collapsed state of each side zone (controlled mode) so the
          track widths follow the UI instead of the other way around. Mounted
          only once the viewport is known to be ≥ lg, so phones never pay for
          the heavy desktop panels. */}
      {isMobile === false && (
      <div
        className="flex-1 grid gap-3 p-3 min-h-0 [grid-template-columns:var(--rd-grid-cols)]"
        style={{ ['--rd-grid-cols' as string]: gridTemplateColumns }}
      >
        {/* Left zone — Context. RailTabs surface DXY / Indicators / Portfolio
            / Correlations / Backtest behind a compact strip, all mounted
            simultaneously so their internal state survives tab switches. */}
        <CollapsibleZone
          id="left-context"
          title="Context"
          side="left"
          collapsed={leftZone.collapsed}
          onCollapsedChange={leftZone.setCollapsed}
        >
          <RailTabs
            id="left-rail"
            side="left"
            defaultTab="ind"
            tabs={[
              { key: 'ind', label: 'Ind', content: (
                <IndicatorPanel snapshot={snapshot} currentPrice={prices[instrument]?.price ?? null} />
              ) },
              { key: 'dxy', label: 'DXY', content: <DxyPanel /> },
              { key: 'corr', label: 'Corr', content: <CorrelationPanel /> },
              { key: 'pos', label: 'Pos', content: (
                <IbkrPortfolioPanel
                  selectedAccountId={selectedIbkrAccountId}
                  onAccountChange={setSelectedIbkrAccountId}
                  onRefreshRequested={loadSummary}
                />
              ) },
              { key: 'bt', label: 'BT', content: <BacktestPanel /> },
            ]}
          />
        </CollapsibleZone>

        {/* Center zone — Chart + Order Flow (always visible). `min-w-0` is
            essential: without it the Chart would force the grid's `1fr`
            track to be at least as wide as its intrinsic content, and the
            side zones would lose their requested widths.

            CockpitTabs partitions the center into Price / Flow / Setup tabs;
            every panel stays mounted (display:none) so the lightweight-charts
            instance, WebSocket subscriptions, and panel-local state survive
            tab switches. MNQ keeps its preferred top-of-tab ordering. */}
        <section className="flex flex-col gap-3 min-w-0">
          <CockpitTabs
            id="center"
            defaultTab="price"
            tabs={[
              {
                key: 'price',
                label: 'Price',
                content: (
                  <div className="flex flex-col gap-3 min-w-0 p-3">
                    {instrument === 'MNQ' ? (
                      <>
                        {/* MNQ : Tick Chart → PLAYBOOK 10m → Candle chart. */}
                        <TickChart selectedInstrument={instrument} snapshot={snapshot} brokerAccountId={selectedIbkrAccountId} />
                        <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
                          <PlaybookPanel
                            instrument={instrument}
                            timeframe="10m"
                            selectedBrokerAccountId={selectedIbkrAccountId}
                            livePrice={prices[instrument]?.price ?? null}
                          />
                        </div>
                        <Chart
                          instrument={instrument}
                          timeframe={timeframe}
                          timezone={timezone.tz}
                          theme={theme}
                          snapshot={snapshot}
                          livePrice={prices[instrument]}
                        />
                      </>
                    ) : (
                      <>
                        <Chart
                          instrument={instrument}
                          timeframe={timeframe}
                          timezone={timezone.tz}
                          theme={theme}
                          snapshot={snapshot}
                          livePrice={prices[instrument]}
                        />
                        <TickChart selectedInstrument={instrument} snapshot={snapshot} brokerAccountId={selectedIbkrAccountId} />
                      </>
                    )}
                  </div>
                ),
              },
              {
                key: 'flow',
                label: 'Flow',
                content: (
                  <div className="flex flex-col gap-3 min-w-0 p-3">
                    <OrderFlowPanel selectedInstrument={instrument} />
                    <FootprintChart selectedInstrument={instrument} />
                    <FlashCrashPanel />
                  </div>
                ),
              },
              {
                key: 'setup',
                label: 'Setup',
                content: (
                  <div className="flex flex-col gap-3 min-w-0 p-3">
                    <Chart
                      instrument={instrument}
                      timeframe={timeframe}
                      timezone={timezone.tz}
                      theme={theme}
                      snapshot={snapshot}
                      livePrice={prices[instrument]}
                    />
                    {/* PLAYBOOK timeframe panel — for MNQ the 10m one already
                        lives in Price; here we render the current-timeframe
                        playbook so all instruments have a reachable view. */}
                    <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
                      <PlaybookPanel
                        instrument={instrument}
                        timeframe={timeframe}
                        selectedBrokerAccountId={selectedIbkrAccountId}
                        livePrice={prices[instrument]?.price ?? null}
                      />
                    </div>
                  </div>
                ),
              },
            ]}
          />
        </section>

        {/* Right zone — AI Trade Desk. Five rail tabs:
            - WTX : the four WTX variants + WTX-RSI behind a single
              WtxFamilyPanel (sub-tabbed, per-instrument persistence).
            - Quant : Quant 7-Gates simulation panel.
            - Play : current-instrument PlaybookPanel.
            - Strat : generic StrategyPanel.
            - Ext : ExternalSetupPanel. */}
        <CollapsibleZone
          id="right-ai-desk"
          title="AI Trade Desk"
          side="right"
          collapsed={rightZone.collapsed}
          onCollapsedChange={rightZone.setCollapsed}
        >
          <RailTabs
            id="right-rail"
            side="right"
            defaultTab="wtx"
            tabs={[
              { key: 'wtx', label: 'WTX', content: (
                <WtxFamilyPanel
                  instrument={instrument}
                  wtx5mSignals={wtx5m}
                  wtx10mSignals={wtx10m}
                  wtxZ35Signals={wtxZ35}
                  wtxZ40Signals={wtxZ40}
                  wtxRsiSignals={wtxRsiSignals}
                  wtxRsiLiveState={wtxRsiStates[`${instrument}:5m`] ?? null}
                  defaultTab={instrument === 'MNQ' ? '10m' : '5m'}
                />
              ) },
              { key: 'quant', label: 'Quant', content: <Quant7GatesSimulationPanel /> },
              { key: 'play', label: 'Play', content: (
                <div className="p-3">
                  <PlaybookPanel
                    instrument={instrument}
                    timeframe={timeframe}
                    selectedBrokerAccountId={selectedIbkrAccountId}
                    livePrice={prices[instrument]?.price ?? null}
                  />
                </div>
              ) },
              { key: 'strat', label: 'Strat', content: <StrategyPanel instrument={instrument} timeframe={timeframe} /> },
              { key: 'ext', label: 'Ext', content: <ExternalSetupPanel /> },
            ]}
          />
        </CollapsibleZone>
      </div>
      )}

      {/* Manual order ticket — mobile bottom sheet, instrument follows the header selector */}
      {isMobile && (
        <OrderTicketSheet
          open={ticketOpen}
          instrument={instrument}
          lastPrice={prices[instrument]?.price ?? null}
          brokerAccountId={selectedIbkrAccountId ?? null}
          onClose={() => setTicketOpen(false)}
          onPlaced={() => setMobileTab('portfolio')}
        />
      )}

      {/* BottomRail replaces AlertsFeed on desktop — three sticky columns
          (Alerts / Positions / Recent fills) with expand/collapse. The rail
          uses /topic/positions through useActivePositions and the rolling
          fills ring derived above. */}
      {isMobile === false && (
        <BottomRail
          alerts={bottomRailAlerts}
          positions={bottomRailPositions}
          fills={recentFills}
          onSnoozeAlert={handleSnoozeAlert}
          onClearAlerts={handleClearAlerts}
        />
      )}

      {/* Quant audio notification — keep at root so it survives tab switches. */}
      <QuantSetupNotification />

      {/* Command palette — Cmd/Ctrl+K. Mounted once at the root. */}
      <CommandPalette
        actions={paletteActions}
        open={commandPaletteOpen}
        onOpenChange={setCommandPaletteOpen}
        installShortcut={true}
      />
    </div>
    </QuantStreamProvider>
  );
}

/** Segmented button group shared by the desktop header and the mobile selector
    row. `grow` stretches it across the row with taller touch targets. */
function TabGroup<T extends string>({ options, value, onChange, grow = false }: {
  options: readonly T[];
  value: T;
  onChange: (value: T) => void;
  grow?: boolean;
}) {
  return (
    <div className={`flex rounded-lg overflow-hidden border border-zinc-800 ${grow ? 'flex-1' : ''}`}>
      {options.map(opt => (
        <button key={opt}
          onClick={() => onChange(opt)}
          className={`${grow ? 'flex-1 px-2 py-2 min-h-[44px] inline-flex items-center justify-center' : 'px-3 py-1.5'} text-xs font-medium transition-colors ${
            value === opt
              ? 'bg-zinc-700 text-white'
              : 'text-zinc-500 hover:text-zinc-300'
          }`}
        >{opt}</button>
      ))}
    </div>
  );
}

function TimezoneSelect({ value, onChange }: {
  value: TzEntry;
  onChange: (tz: TzEntry) => void;
}) {
  return (
    <div className="flex items-center gap-1 rounded-lg border border-zinc-800 px-2 py-1 bg-zinc-900">
      <span className="text-[10px] text-zinc-600 select-none">🌐</span>
      <select
        value={value.tz}
        onChange={e => onChange(findTimezoneByTz(e.target.value))}
        className="bg-transparent text-xs text-zinc-400 outline-none cursor-pointer hover:text-zinc-200 transition-colors"
      >
        {TIMEZONES.map(z => (
          <option key={z.tz} value={z.tz} className="bg-zinc-900 text-zinc-300">
            {z.label}
          </option>
        ))}
      </select>
    </div>
  );
}

/** Purge + refill the current instrument's candles. */
function PurgeButton({ instrument, purging, purgeMsg, onPurge }: {
  instrument: string;
  purging: boolean;
  purgeMsg: string | null;
  onPurge: () => void;
}) {
  return (
    <button
      onClick={onPurge}
      disabled={purging}
      title={`Wipe all ${instrument} candles (all timeframes) and re-backfill from IBKR`}
      className={`px-2.5 py-1.5 rounded-lg border text-xs transition-colors select-none ${
        purging
          ? 'border-zinc-800 text-zinc-500 cursor-wait'
          : purgeMsg
          ? 'border-emerald-700 text-emerald-300'
          : 'border-zinc-800 text-zinc-400 hover:text-rose-300 hover:border-rose-700'
      }`}
    >
      {purging ? '⟳ Purging…' : purgeMsg ?? `🗑 Purge ${instrument}`}
    </button>
  );
}

function ThemeToggle({ theme, onToggle }: {
  theme: 'dark' | 'light';
  onToggle: () => void;
}) {
  return (
    <button
      onClick={onToggle}
      title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
      className="px-2.5 py-1.5 rounded-lg border border-zinc-800 text-xs text-zinc-400 hover:text-zinc-200 hover:border-zinc-600 transition-colors select-none"
    >
      {theme === 'dark' ? '☀️' : '🌙'}
    </button>
  );
}
