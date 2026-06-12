'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, PortfolioSummary, IndicatorSnapshot } from '@/app/lib/api';
import { useWebSocket } from '@/app/hooks/useWebSocket';
import { useIsMobile } from '@/app/hooks/useIsMobile';
import MetricsBar from './MetricsBar';
import RolloverBanner from './RolloverBanner';
import Chart from './Chart';
import DxyPanel from './DxyPanel';
import IndicatorPanel from './IndicatorPanel';
import StrategyPanel from './StrategyPanel';
import Quant7GatesSimulationPanel from './quant/Quant7GatesSimulationPanel';
import QuantSetupNotification from './quant/QuantSetupNotification';
import { QuantStreamProvider } from '@/app/hooks/useQuantStream';
import { QUANT_INSTRUMENTS } from './quant/types';
import AlertsFeed from './AlertsFeed';
import BacktestPanel from './BacktestPanel';
import IbkrPortfolioPanel from './IbkrPortfolioPanel';
import OrderFlowPanel from './OrderFlowPanel';
import PlaybookPanel from './PlaybookPanel';
import FootprintChart from './FootprintChart';
import TickChart from './TickChart';
import FlashCrashPanel from './FlashCrashPanel';
import PerfectSetupPanel from './PerfectSetupPanel';
import CorrelationPanel from './CorrelationPanel';
import ExternalSetupPanel from './ExternalSetupPanel';
import WtxStrategyPanel from './WtxStrategyPanel';
import WtxRsiStrategyPanel from './WtxRsiStrategyPanel';
import MarketableSettingsControl from './MarketableSettingsControl';
import CollapsibleZone, { useCollapsibleZoneState } from './layout/CollapsibleZone';
import MobileVitalStrip from './mobile/MobileVitalStrip';
import MobileInstrumentPills from './mobile/MobileInstrumentPills';
import MobileCollapse from './mobile/MobileCollapse';
import OrderTicketSheet from './mobile/OrderTicketSheet';
import MobilePositionsCard from './mobile/MobilePositionsCard';
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

  // Mobile layout — `null` until the viewport is known (first client render),
  // so neither panel tree mounts prematurely.
  const isMobile = useIsMobile();
  const [mobileTab, setMobileTab] = useState<MobileTab>('chart');
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false);
  const [ticketOpen, setTicketOpen] = useState(false);

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
    <QuantStreamProvider instruments={QUANT_INSTRUMENTS}>
    <div className={`min-h-screen bg-zinc-950 text-white flex flex-col ${theme === 'light' ? 'light' : ''}`}>
      {/* Header */}
      <header className="flex items-center justify-between px-3 lg:px-4 py-2.5 bg-zinc-900 border-b border-zinc-800">
        <div className="flex items-center gap-3">
          <span className="text-sm font-bold tracking-tight text-white">
            Risk<span className="text-emerald-400">Desk</span>
          </span>
          <span className="hidden sm:inline text-[10px] text-zinc-600">Futures Risk Dashboard</span>
        </div>

        {/* Desktop controls — full cluster */}
        <div className="hidden lg:flex items-center gap-2">
          <TabGroup options={INSTRUMENTS} value={instrument} onChange={setInstrument} />
          <TabGroup options={TIMEFRAMES} value={timeframe} onChange={setTimeframe} />
          <TimezoneSelect value={timezone} onChange={setTimezone} />
          <PurgeButton instrument={instrument} purging={purging} purgeMsg={purgeMsg} onPurge={handlePurge} />
          {/* Marketable execution policy (global, operator-controlled) */}
          <MarketableSettingsControl />
          <ThemeToggle theme={theme} onToggle={() => setTheme(t => t === 'dark' ? 'light' : 'dark')} />
        </div>

        {/* Mobile controls — theme + overflow menu */}
        <div className="flex lg:hidden items-center gap-2">
          <ThemeToggle theme={theme} onToggle={() => setTheme(t => t === 'dark' ? 'light' : 'dark')} />
          <button
            onClick={() => setMobileMenuOpen(v => !v)}
            aria-label="Plus de contrôles"
            aria-expanded={mobileMenuOpen}
            className={`px-3 py-1.5 rounded-lg border text-sm transition-colors select-none ${
              mobileMenuOpen
                ? 'border-zinc-600 text-zinc-200 bg-zinc-800'
                : 'border-zinc-800 text-zinc-400'
            }`}
          >⋯</button>
        </div>
      </header>

      {/* Mobile overflow menu — secondary controls */}
      {mobileMenuOpen && (
        <div className="lg:hidden flex flex-wrap items-center gap-2 px-3 py-2 bg-zinc-900 border-b border-zinc-800">
          <TimezoneSelect value={timezone} onChange={setTimezone} />
          <PurgeButton instrument={instrument} purging={purging} purgeMsg={purgeMsg} onPurge={handlePurge} />
          <MarketableSettingsControl />
        </div>
      )}

      {/* Mobile vital strip — status + total P&L always visible, expandable */}
      {isMobile && <MobileVitalStrip summary={summary} connected={connected} prices={prices} />}

      {/* Desktop metrics bar */}
      {isMobile === false && <MetricsBar summary={summary} connected={connected} prices={prices} />}

      {/* Rollover warning — visible only when a contract is near expiry */}
      <RolloverBanner />

      {/* Desktop live price ticker — on mobile the prices live inside the pills */}
      {isMobile === false && (
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
      )}

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
          fetch, poll, or subscribe. */}
      {isMobile && (
        <main className="flex-1 flex flex-col gap-3 p-3 min-w-0 pb-[calc(4.5rem_+_env(safe-area-inset-bottom))]">
          {mobileTab === 'chart' && (
            <>
              <TickChart selectedInstrument={instrument} snapshot={snapshot} />
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

      {/* Mobile bottom tab bar */}
      {isMobile && (
        <nav className="fixed bottom-0 inset-x-0 z-40 bg-zinc-900 border-t border-zinc-800 pb-[env(safe-area-inset-bottom)]">
          <div className="grid grid-cols-5">
            {MOBILE_TABS.map(tab => (
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
              </button>
            ))}
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
        className="flex-1 grid gap-3 p-3 pb-14 [grid-template-columns:var(--rd-grid-cols)]"
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
          <OrderFlowPanel selectedInstrument={instrument} />
          <TickChart selectedInstrument={instrument} snapshot={snapshot} />
          <FootprintChart selectedInstrument={instrument} />
          <FlashCrashPanel />
          <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
            <PerfectSetupPanel />
          </div>
          {/* PLAYBOOK anchored at the bottom of the center column */}
          <div className="rounded-lg border border-zinc-800 bg-zinc-900 p-4">
            <PlaybookPanel
              instrument={instrument}
              timeframe={timeframe}
              selectedBrokerAccountId={selectedIbkrAccountId}
              livePrice={prices[instrument]?.price ?? null}
            />
          </div>
        </section>

        {/* Right zone — AI Trade Desk */}
        <CollapsibleZone
          id="right-ai-desk"
          title="AI Trade Desk"
          side="right"
          collapsed={rightZone.collapsed}
          onCollapsedChange={rightZone.setCollapsed}
        >
          <WtxStrategyPanel instrument={instrument} timeframe="5m" liveSignals={wtxSignals} />
          <WtxStrategyPanel instrument={instrument} timeframe="10m" liveSignals={wtxSignals} />
          {/* Variant signal top-train-Z35 — backend-configured (riskdesk.wtx.variants) MNQ-10m
              parallel panel: zone-only entries ±35, WaveTrend 5/14/2, SL 4.0×ATR, key "10m-z35". */}
          {instrument === 'MNQ' && (
            <WtxStrategyPanel
              instrument={instrument}
              timeframe="10m-z35"
              displayName="top-train-Z35"
              liveSignals={wtxSignals}
            />
          )}
          <WtxRsiStrategyPanel
            instrument={instrument}
            timeframe="5m"
            liveSignals={wtxRsiSignals}
            liveState={wtxRsiStates[`${instrument}:5m`] ?? null}
          />
          <Quant7GatesSimulationPanel />
          <StrategyPanel instrument={instrument} timeframe={timeframe} />
          <ExternalSetupPanel />
        </CollapsibleZone>
      </div>
      )}

      {/* Manual order ticket — mobile bottom sheet, instrument follows the header selector */}
      {isMobile && (
        <OrderTicketSheet
          open={ticketOpen}
          instrument={instrument}
          lastPrice={prices[instrument]?.price ?? null}
          onClose={() => setTicketOpen(false)}
          onPlaced={() => setMobileTab('portfolio')}
        />
      )}

      {/* AlertsFeed is desktop-only — intentionally absent from the mobile UI. */}
      {isMobile === false && <AlertsFeed alerts={alerts} />}
      <QuantSetupNotification />
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
          className={`${grow ? 'flex-1 px-2 py-2' : 'px-3 py-1.5'} text-xs font-medium transition-colors ${
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
