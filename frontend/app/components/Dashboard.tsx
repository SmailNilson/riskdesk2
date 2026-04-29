'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, PortfolioSummary, IndicatorSnapshot } from '@/app/lib/api';
import { useWebSocket } from '@/app/hooks/useWebSocket';
import MetricsBar from './MetricsBar';
import RolloverBanner from './RolloverBanner';
import Chart from './Chart';
import DxyPanel from './DxyPanel';
import IndicatorPanel from './IndicatorPanel';
import AiMentorDesk from './AiMentorDesk';
import QuantGatePanel from './quant/QuantGatePanel';
import QuantSetupNotification from './quant/QuantSetupNotification';
import AlertsFeed from './AlertsFeed';
import BacktestPanel from './BacktestPanel';
import IbkrPortfolioPanel from './IbkrPortfolioPanel';
import OrderFlowPanel from './OrderFlowPanel';
import { LiveAnalysisPanel } from './LiveAnalysisPanel';
import { AnalysisReplayPanel } from './AnalysisReplayPanel';
import FootprintChart from './FootprintChart';
import FlashCrashPanel from './FlashCrashPanel';
import TrailingStopStatsPanel from './TrailingStopStatsPanel';
import CorrelationPanel from './CorrelationPanel';
import ExternalSetupPanel from './ExternalSetupPanel';
import CollapsibleZone, { useCollapsibleZoneState } from './layout/CollapsibleZone';
import { DEFAULT_TIMEZONE, findTimezoneByTz, TIMEZONES, type TzEntry } from '@/app/lib/timezones';

const INSTRUMENTS = ['MCL', 'MGC', 'E6', 'MNQ'] as const;
const TICKER_INSTRUMENTS = [...INSTRUMENTS, 'DXY'] as const;
type Instrument = typeof INSTRUMENTS[number];
const TIMEFRAMES = ['5m', '10m', '1h', '1d'] as const;
type Timeframe = typeof TIMEFRAMES[number];


export default function Dashboard() {
  const [instrument, setInstrument] = useState<Instrument>('MCL');
  const [timeframe, setTimeframe] = useState<Timeframe>('10m');
  const [timezone, setTimezone] = useState<TzEntry>(DEFAULT_TIMEZONE);
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');
  const [summary, setSummary] = useState<PortfolioSummary | null>(null);
  const [snapshot, setSnapshot] = useState<IndicatorSnapshot | null>(null);
  const [selectedIbkrAccountId, setSelectedIbkrAccountId] = useState<string | undefined>(undefined);
  const [purging, setPurging] = useState(false);
  const [purgeMsg, setPurgeMsg] = useState<string | null>(null);

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

  const { prices, alerts, mentorSignalReviews, connected, refresh } = useWebSocket();

  // Zone collapse state is hoisted so the grid's track widths follow the
  // actual zone state — without this, the `auto` tracks would shrink below
  // the zone's min-width because the center column (Chart + OrderFlow) forces
  // `1fr` to claim the remaining space and squeezes the sides.
  const leftZone = useCollapsibleZoneState('left-context');
  const rightZone = useCollapsibleZoneState('right-ai-desk');

  // Track widths (lg and up). The right zone is wider than the left because
  // AiMentorDesk renders a dense tab bar + verbose mentor review cards.
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

  return (
    <div className={`min-h-screen bg-zinc-950 text-white flex flex-col ${theme === 'light' ? 'light' : ''}`}>
      {/* Header */}
      <header className="flex items-center justify-between px-4 py-2.5 bg-zinc-900 border-b border-zinc-800">
        <div className="flex items-center gap-3">
          <span className="text-sm font-bold tracking-tight text-white">
            Risk<span className="text-emerald-400">Desk</span>
          </span>
          <span className="text-[10px] text-zinc-600">Futures Risk Dashboard</span>
        </div>

        <div className="flex items-center gap-2">
          {/* Instrument tabs */}
          <div className="flex rounded-lg overflow-hidden border border-zinc-800">
            {INSTRUMENTS.map(inst => (
              <button key={inst}
                onClick={() => setInstrument(inst)}
                className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                  instrument === inst
                    ? 'bg-zinc-700 text-white'
                    : 'text-zinc-500 hover:text-zinc-300'
                }`}
              >{inst}</button>
            ))}
          </div>

          {/* Timeframe tabs */}
          <div className="flex rounded-lg overflow-hidden border border-zinc-800">
            {TIMEFRAMES.map(tf => (
              <button key={tf}
                onClick={() => setTimeframe(tf)}
                className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                  timeframe === tf
                    ? 'bg-zinc-700 text-white'
                    : 'text-zinc-500 hover:text-zinc-300'
                }`}
              >{tf}</button>
            ))}
          </div>

          {/* Timezone selector */}
          <div className="flex items-center gap-1 rounded-lg border border-zinc-800 px-2 py-1 bg-zinc-900">
            <span className="text-[10px] text-zinc-600 select-none">🌐</span>
            <select
              value={timezone.tz}
              onChange={e => setTimezone(findTimezoneByTz(e.target.value))}
              className="bg-transparent text-xs text-zinc-400 outline-none cursor-pointer hover:text-zinc-200 transition-colors"
            >
              {TIMEZONES.map(z => (
                <option key={z.tz} value={z.tz} className="bg-zinc-900 text-zinc-300">
                  {z.label}
                </option>
              ))}
            </select>
          </div>

          {/* Purge + refill current instrument */}
          <button
            onClick={handlePurge}
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

          {/* Theme toggle */}
          <button
            onClick={() => setTheme(t => t === 'dark' ? 'light' : 'dark')}
            title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            className="px-2.5 py-1.5 rounded-lg border border-zinc-800 text-xs text-zinc-400 hover:text-zinc-200 hover:border-zinc-600 transition-colors select-none"
          >
            {theme === 'dark' ? '☀️' : '🌙'}
          </button>

        </div>
      </header>

      {/* Metrics bar */}
      <MetricsBar summary={summary} connected={connected} prices={prices} />

      {/* Rollover warning — visible only when a contract is near expiry */}
      <RolloverBanner />

      {/* Live price ticker */}
      <div className="flex gap-4 px-4 py-1.5 bg-zinc-900/50 border-b border-zinc-800/50 overflow-x-auto">
        {TICKER_INSTRUMENTS.map(inst => {
          const p = prices[inst];
          const decimals = inst === 'E6' ? 5 : inst === 'DXY' ? 3 : 2;
          return (
            <div key={inst} className="flex items-center gap-1.5 flex-shrink-0">
              <span className="text-[10px] text-zinc-500">{inst}</span>
              <span className={`text-xs font-mono ${p?.source === 'STALE' ? 'text-zinc-500' : 'text-white'}`}>
                {p ? p.price.toFixed(decimals) : '—'}
              </span>
            </div>
          );
        })}
      </div>

      {/* Trade Desk — 3-zone grid. The grid-template-columns is driven by the
          actual collapsed state of each side zone (controlled mode) so the
          track widths follow the UI instead of the other way around. Below
          `lg` the grid collapses to a single column. */}
      <div
        className="flex-1 grid grid-cols-1 gap-3 p-3 pb-14 lg:[grid-template-columns:var(--rd-grid-cols)]"
        style={{ ['--rd-grid-cols' as string]: gridTemplateColumns }}
      >
        {/* Left zone — Context */}
        <CollapsibleZone
          id="left-context"
          title="Context"
          side="left"
          collapsed={leftZone.collapsed}
          onCollapsedChange={leftZone.setCollapsed}
        >
          <DxyPanel />
          <IndicatorPanel snapshot={snapshot} currentPrice={prices[instrument]?.price ?? null} />
          <IbkrPortfolioPanel
            selectedAccountId={selectedIbkrAccountId}
            onAccountChange={setSelectedIbkrAccountId}
            onRefreshRequested={loadSummary}
          />
          <CorrelationPanel />
          <BacktestPanel />
        </CollapsibleZone>

        {/* Center zone — Chart + Order Flow (always visible). `min-w-0` is
            essential: without it the Chart would force the grid's `1fr`
            track to be at least as wide as its intrinsic content, and the
            side zones would lose their requested widths. */}
        <section className="flex flex-col gap-3 min-w-0">
          <Chart
            instrument={instrument}
            timeframe={timeframe}
            timezone={timezone.tz}
            theme={theme}
            snapshot={snapshot}
            livePrice={prices[instrument]}
          />
          <LiveAnalysisPanel instrument={instrument} timeframe={timeframe} />
          <AnalysisReplayPanel instrument={instrument} timeframe={timeframe} />
          <OrderFlowPanel selectedInstrument={instrument} />
          <FootprintChart selectedInstrument={instrument} />
          <FlashCrashPanel />
        </section>

        {/* Right zone — AI Trade Desk */}
        <CollapsibleZone
          id="right-ai-desk"
          title="AI Trade Desk"
          side="right"
          collapsed={rightZone.collapsed}
          onCollapsedChange={rightZone.setCollapsed}
        >
          <AiMentorDesk
            instrument={instrument}
            timeframe={timeframe}
            timezone={timezone}
            connected={connected}
            summary={summary}
            snapshot={snapshot}
            prices={prices}
            alerts={alerts}
            reviews={mentorSignalReviews}
            selectedBrokerAccountId={selectedIbkrAccountId}
            onRefresh={refresh}
          />
          <QuantGatePanel />
          <ExternalSetupPanel />
          <TrailingStopStatsPanel />
        </CollapsibleZone>
      </div>

      <AlertsFeed alerts={alerts} />
      <QuantSetupNotification />
    </div>
  );
}
