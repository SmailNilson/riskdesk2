'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, PortfolioSummary, IndicatorSnapshot } from '@/app/lib/api';
import { useWebSocket } from '@/app/hooks/useWebSocket';
import MetricsBar from './MetricsBar';
import RolloverBanner from './RolloverBanner';
import Chart from './Chart';
import IndicatorPanel from './IndicatorPanel';
import MentorPanel from './MentorPanel';
import MentorSignalPanel from './MentorSignalPanel';
import PositionForm from './PositionForm';
import BacktestPanel from './BacktestPanel';
import IbkrPortfolioPanel from './IbkrPortfolioPanel';
import { DEFAULT_TIMEZONE, findTimezoneByTz, TIMEZONES, type TzEntry } from '@/app/lib/timezones';

const INSTRUMENTS = ['MCL', 'MGC', 'E6', 'MNQ'] as const;
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

  const { prices, alerts, mentorSignalReviews, connected } = useWebSocket();

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

          {/* Theme toggle */}
          <button
            onClick={() => setTheme(t => t === 'dark' ? 'light' : 'dark')}
            title={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}
            className="px-2.5 py-1.5 rounded-lg border border-zinc-800 text-xs text-zinc-400 hover:text-zinc-200 hover:border-zinc-600 transition-colors select-none"
          >
            {theme === 'dark' ? '☀️' : '🌙'}
          </button>

          <PositionForm onCreated={loadSummary} />

        </div>
      </header>

      {/* Metrics bar */}
      <MetricsBar summary={summary} connected={connected} />

      {/* Rollover warning — visible only when a contract is near expiry */}
      <RolloverBanner />

      {/* Live price ticker */}
      <div className="flex gap-4 px-4 py-1.5 bg-zinc-900/50 border-b border-zinc-800/50 overflow-x-auto">
        {INSTRUMENTS.map(inst => {
          const p = prices[inst];
          return (
            <div key={inst} className="flex items-center gap-1.5 flex-shrink-0">
              <span className="text-[10px] text-zinc-500">{inst}</span>
              <span className="text-xs font-mono text-white">
                {p ? p.price.toFixed(inst === 'E6' ? 5 : 2) : '—'}
              </span>
            </div>
          );
        })}
      </div>

      {/* Main content — full width, padding bottom so content clears the fixed alerts bar */}
      <div className="flex-1 flex flex-col gap-3 p-3 pb-14">
        {/* Chart */}
        <Chart
          instrument={instrument}
          timeframe={timeframe}
          timezone={timezone.tz}
          theme={theme}
          snapshot={snapshot}
          livePrice={prices[instrument]}
        />

        {/* Indicators */}
        <IndicatorPanel snapshot={snapshot} currentPrice={prices[instrument]?.price ?? null} />

        <MentorPanel
          instrument={instrument}
          timeframe={timeframe}
          timezone={timezone}
          connected={connected}
          summary={summary}
          snapshot={snapshot}
          prices={prices}
          alerts={alerts}
        />

        {/* Backtest */}
        <BacktestPanel />

        <IbkrPortfolioPanel
          selectedAccountId={selectedIbkrAccountId}
          onAccountChange={setSelectedIbkrAccountId}
          onRefreshRequested={loadSummary}
        />

        <MentorSignalPanel
          timezone={timezone}
          alerts={alerts}
          reviews={mentorSignalReviews}
          selectedBrokerAccountId={selectedIbkrAccountId}
        />
      </div>
    </div>
  );
}
