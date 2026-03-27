'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, PortfolioSummary, IndicatorSnapshot, IbkrWatchlistInstrumentView, IbkrWatchlistView } from '@/app/lib/api';
import { PriceUpdate, useWebSocket } from '@/app/hooks/useWebSocket';
import MetricsBar from './MetricsBar';
import Chart from './Chart';
import IndicatorPanel from './IndicatorPanel';
import MentorPanel from './MentorPanel';
import MentorSignalPanel from './MentorSignalPanel';
import PositionForm from './PositionForm';
import BacktestPanel from './BacktestPanel';
import IbkrPortfolioPanel from './IbkrPortfolioPanel';
import { Instrument as MentorInstrument } from '@/app/lib/mentor';

const FALLBACK_INSTRUMENTS = ['MCL', 'MGC', 'E6', 'MNQ', 'DXY'] as const;
const MENTOR_SUPPORTED_INSTRUMENTS = ['MCL', 'MGC', 'E6', 'MNQ'] as const;
const TIMEFRAMES = ['5m', '10m', '1h', '1d'] as const;
type Timeframe = typeof TIMEFRAMES[number];

const TIMEZONES = [
  { label: 'UTC',        tz: 'UTC' },
  { label: 'Paris',      tz: 'Europe/Paris' },
  { label: 'London',     tz: 'Europe/London' },
  { label: 'New York',   tz: 'America/New_York' },
  { label: 'Chicago',    tz: 'America/Chicago' },
  { label: 'Tokyo',      tz: 'Asia/Tokyo' },
] as const;
type TzEntry = typeof TIMEZONES[number];

type DashboardInstrument = {
  key: string;
  code: string;
  label: string;
  description: string | null;
  assetClass: string | null;
  selectionKey: string;
};

type WatchlistListItem = {
  key: string;
  code: string | null;
  selectionKey: string | null;
  label: string;
  description: string | null;
  assetClass: string | null;
  selectable: boolean;
  visible: boolean;
};

type WatchlistListDisplayItem = WatchlistListItem & {
  displayLabel: string;
};

function normalizeInstrumentToken(value: string | null | undefined): string | null {
  if (!value) {
    return null;
  }
  const normalized = value.trim().toUpperCase();
  return normalized === '' ? null : normalized;
}

function deriveInstrumentCode(item: IbkrWatchlistInstrumentView): string | null {
  const explicitCode = normalizeInstrumentToken(item.instrumentCode);
  if (explicitCode) {
    return explicitCode;
  }

  const symbol = normalizeInstrumentToken(item.symbol);
  if (symbol) {
    return symbol;
  }

  const localSymbol = normalizeInstrumentToken(item.localSymbol);
  if (localSymbol) {
    return localSymbol.split(/\s+/)[0] ?? localSymbol;
  }

  return normalizeInstrumentToken(item.name);
}

function deriveSelectionKey(item: IbkrWatchlistInstrumentView): string | null {
  if (item.conid > 0) {
    return `conid:${item.conid}`;
  }

  const localSymbol = normalizeInstrumentToken(item.localSymbol);
  if (localSymbol) {
    return `local:${localSymbol}`;
  }

  const symbol = normalizeInstrumentToken(item.symbol);
  if (symbol) {
    return `symbol:${symbol}`;
  }

  const instrumentCode = normalizeInstrumentToken(item.instrumentCode);
  if (instrumentCode) {
    return `code:${instrumentCode}`;
  }

  return null;
}

function deriveWatchlistLabel(item: IbkrWatchlistInstrumentView, index: number): string {
  return normalizeInstrumentToken(item.symbol)
    ?? normalizeInstrumentToken(item.localSymbol)
    ?? normalizeInstrumentToken(item.name)
    ?? `LINE ${index + 1}`;
}

function deriveWatchlistDescription(item: IbkrWatchlistInstrumentView, fallbackLabel: string): string | null {
  const description = item.localSymbol ?? item.name ?? null;
  if (!description) {
    return null;
  }
  return description.trim().toUpperCase() === fallbackLabel ? null : description;
}

function uniqueWatchlistInstruments(watchlist: IbkrWatchlistView | null): DashboardInstrument[] {
  if (!watchlist) {
    return FALLBACK_INSTRUMENTS.map(code => ({
      key: code,
      code,
      label: code,
      description: null,
      assetClass: 'FUT',
      selectionKey: code,
    }));
  }

  const deduped = watchlist.instruments.reduce<DashboardInstrument[]>((acc, item) => {
    const code = deriveInstrumentCode(item);
    if (!code || acc.some(existing => existing.code === code)) {
      return acc;
    }

    acc.push({
      key: item.conid > 0 ? String(item.conid) : code,
      code,
      label: normalizeInstrumentToken(item.symbol) ?? code,
      description: item.localSymbol ?? item.name ?? null,
      assetClass: item.assetClass ?? null,
      selectionKey: code,
    });
    return acc;
  }, []);

  return deduped.length > 0
    ? deduped
    : FALLBACK_INSTRUMENTS.map(code => ({
        key: code,
        code,
        label: code,
        description: null,
        assetClass: 'FUT',
        selectionKey: code,
      }));
}

function watchlistListItems(watchlist: IbkrWatchlistView | null): WatchlistListItem[] {
  if (!watchlist) {
    return FALLBACK_INSTRUMENTS.map(code => ({
      key: code,
      code,
      selectionKey: code,
      label: code,
      description: null,
      assetClass: 'FUT',
      selectable: true,
      visible: true,
    }));
  }

  return watchlist.instruments.map((item, index) => {
    const code = deriveInstrumentCode(item);
    const selectionKey = deriveSelectionKey(item);
    const label = deriveWatchlistLabel(item, index);
    const visible = item.conid > 0
      || item.symbol != null
      || item.localSymbol != null
      || item.name != null
      || code != null;
    return {
      key: item.conid > 0 ? `${item.conid}-${index}` : `${watchlist.id}-${index}`,
      code,
      selectionKey,
      label,
      description: deriveWatchlistDescription(item, label),
      assetClass: item.assetClass ?? null,
      selectable: !!selectionKey,
      visible,
    };
  });
}

function asMentorInstrument(value: string): MentorInstrument | null {
  return MENTOR_SUPPORTED_INSTRUMENTS.includes(value as MentorInstrument)
    ? (value as MentorInstrument)
    : null;
}

function formatPrice(value: number, instrument: string) {
  return value.toFixed(instrument === 'E6' ? 5 : 2);
}

export default function Dashboard() {
  const [instrument, setInstrument] = useState<string>('MCL');
  const [selectedWatchlistItemKey, setSelectedWatchlistItemKey] = useState<string | null>(null);
  const [timeframe, setTimeframe] = useState<Timeframe>('10m');
  const [timezone, setTimezone] = useState<TzEntry>(TIMEZONES[1]); // default: Paris
  const [theme, setTheme] = useState<'dark' | 'light'>('dark');
  const [summary, setSummary] = useState<PortfolioSummary | null>(null);
  const [snapshot, setSnapshot] = useState<IndicatorSnapshot | null>(null);
  const [selectedIbkrAccountId, setSelectedIbkrAccountId] = useState<string | undefined>(undefined);
  const [watchlists, setWatchlists] = useState<IbkrWatchlistView[]>([]);
  const [selectedWatchlistId, setSelectedWatchlistId] = useState('');
  const [watchlistsLoading, setWatchlistsLoading] = useState(false);
  const [watchlistsError, setWatchlistsError] = useState<string | null>(null);
  const [fallbackPrices, setFallbackPrices] = useState<Record<string, PriceUpdate>>({});

  const { prices, alerts, mentorSignalReviews, connected } = useWebSocket();

  const activeWatchlist = watchlists.find(watchlist => watchlist.id === selectedWatchlistId)
    ?? watchlists[0]
    ?? null;
  const instruments = uniqueWatchlistInstruments(activeWatchlist);
  const watchlistItems = watchlistListItems(activeWatchlist);
  const visibleWatchlistItems = watchlistItems.filter(item => item.visible);
  const visibleWatchlistDisplayItems = visibleWatchlistItems.map<WatchlistListDisplayItem>(item => {
    const hasDuplicateCode = item.code != null
      && visibleWatchlistItems.filter(candidate => candidate.code === item.code).length > 1;
    return {
      ...item,
      displayLabel: hasDuplicateCode && item.description ? item.description : item.label,
    };
  });
  const hiddenWatchlistItemsCount = watchlistItems.length - visibleWatchlistItems.length;
  const selectedWatchlistItem = visibleWatchlistDisplayItems.find(item => item.key === selectedWatchlistItemKey) ?? null;
  const selectedInstrumentKey = selectedWatchlistItem?.selectionKey ?? instrument;
  const mentorInstrument = asMentorInstrument(instrument);
  const mergedPrices = { ...fallbackPrices, ...prices };

  const loadSummary = useCallback(async () => {
    try { setSummary(await api.getPortfolioSummary(selectedIbkrAccountId)); } catch {}
  }, [selectedIbkrAccountId]);

  const loadSnapshot = useCallback(async () => {
    try {
      setSnapshot(await api.getIndicators(selectedInstrumentKey, timeframe));
    } catch {
      setSnapshot(null);
    }
  }, [selectedInstrumentKey, timeframe]);

  const loadWatchlists = useCallback(async () => {
    setWatchlistsLoading(true);
    setWatchlistsError(null);
    try {
      const data = await api.getIbkrWatchlists();
      setWatchlists(data);
    } catch (error) {
      setWatchlists([]);
      setWatchlistsError(error instanceof Error ? error.message : 'Unable to load IBKR watchlists.');
    } finally {
      setWatchlistsLoading(false);
    }
  }, []);

  const importWatchlists = useCallback(async () => {
    setWatchlistsLoading(true);
    setWatchlistsError(null);
    try {
      const data = await api.importIbkrWatchlists();
      setWatchlists(data);
    } catch (error) {
      setWatchlistsError(error instanceof Error ? error.message : 'Unable to import IBKR watchlists.');
    } finally {
      setWatchlistsLoading(false);
    }
  }, []);

  // Initial load
  useEffect(() => { loadSummary(); }, [loadSummary]);
  useEffect(() => { loadSnapshot(); }, [loadSnapshot]);
  useEffect(() => { loadWatchlists(); }, [loadWatchlists]);

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

  useEffect(() => {
    if (activeWatchlist && selectedWatchlistId !== activeWatchlist.id) {
      setSelectedWatchlistId(activeWatchlist.id);
      return;
    }
    if (!activeWatchlist && selectedWatchlistId !== '') {
      setSelectedWatchlistId('');
    }
  }, [activeWatchlist, selectedWatchlistId]);

  useEffect(() => {
    if (!instruments.some(item => item.code === instrument)) {
      setInstrument(instruments[0]?.code ?? 'MCL');
    }
  }, [instrument, instruments]);

  useEffect(() => {
    if (visibleWatchlistDisplayItems.length === 0) {
      setSelectedWatchlistItemKey(null);
      return;
    }

    const selectedItemStillPresent = selectedWatchlistItemKey
      ? visibleWatchlistDisplayItems.some(item => item.key === selectedWatchlistItemKey)
      : false;

    if (selectedItemStillPresent) {
      return;
    }

    const preferred = visibleWatchlistDisplayItems.find(item => item.selectable && item.code === instrument)
      ?? visibleWatchlistDisplayItems.find(item => item.selectable)
      ?? visibleWatchlistDisplayItems[0];

    setSelectedWatchlistItemKey(preferred?.key ?? null);
  }, [instrument, selectedWatchlistItemKey, visibleWatchlistDisplayItems]);

  useEffect(() => {
    let cancelled = false;

    const fetchFallbackPrices = async () => {
      const targets = new Set<string>();
      instruments.forEach(item => {
        if (!prices[item.selectionKey]) {
          targets.add(item.selectionKey);
        }
      });
      if (!prices[selectedInstrumentKey]) {
        targets.add(selectedInstrumentKey);
      }
      const requestedTargets = Array.from(targets);

      if (requestedTargets.length === 0) {
        return;
      }

      const results = await Promise.allSettled(requestedTargets.map(code => api.getLivePrice(code)));
      if (cancelled) {
        return;
      }

      setFallbackPrices(prev => {
        const next = { ...prev };
        results.forEach((result, index) => {
          if (result.status !== 'fulfilled') {
            return;
          }
          const code = requestedTargets[index];
          next[code] = {
            instrument: code,
            displayName: code,
            price: result.value.price,
            timestamp: result.value.timestamp,
          };
        });
        return next;
      });
    };

    fetchFallbackPrices().catch(() => null);
    const id = setInterval(() => {
      fetchFallbackPrices().catch(() => null);
    }, 15_000);

    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [instruments, prices, selectedInstrumentKey]);

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
          <div className="flex max-w-[38rem] overflow-x-auto rounded-lg border border-zinc-800">
            {instruments.map(inst => (
              <button key={inst.key}
                onClick={() => {
                  setInstrument(inst.code);
                  const preferred = visibleWatchlistDisplayItems.find(item => item.selectable && item.code === inst.code);
                  setSelectedWatchlistItemKey(preferred?.key ?? null);
                }}
                className={`px-3 py-1.5 text-xs font-medium transition-colors ${
                  instrument === inst.code
                    ? 'bg-zinc-700 text-white'
                    : 'text-zinc-500 hover:text-zinc-300'
                }`}
                title={inst.description ?? inst.label}
              >{inst.label}</button>
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
              onChange={e => setTimezone(TIMEZONES.find(z => z.tz === e.target.value)!)}
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

      {/* Live price ticker */}
      <div className="flex gap-4 px-4 py-1.5 bg-zinc-900/50 border-b border-zinc-800/50 overflow-x-auto">
        {instruments.map(inst => {
          const p = mergedPrices[inst.code];
          return (
            <div key={inst.key} className="flex items-center gap-1.5 flex-shrink-0">
              <span className="text-[10px] text-zinc-500">{inst.label}</span>
              <span className="text-xs font-mono text-white">
                {p ? formatPrice(p.price, inst.code) : '—'}
              </span>
            </div>
          );
        })}
        {watchlistsError && (
          <div className="flex items-center flex-shrink-0 text-[10px] text-amber-400">
            {watchlistsError}
          </div>
        )}
      </div>

      {/* Main content — full width, padding bottom so content clears the fixed alerts bar */}
      <div className="flex-1 flex flex-col gap-3 p-3 pb-14">
        <div className="grid gap-3 xl:grid-cols-[minmax(0,1fr)_280px]">
          <Chart
            instrument={selectedInstrumentKey}
            timeframe={timeframe}
            timezone={timezone.tz}
            theme={theme}
            snapshot={snapshot}
            livePrice={mergedPrices[selectedInstrumentKey] ?? mergedPrices[instrument]}
          />

          <section className="flex h-[calc(100vh-13rem)] max-h-[760px] min-h-[420px] w-full min-w-0 flex-col self-start overflow-hidden rounded-lg border border-zinc-800 bg-zinc-900/80 p-3">
            <div className="flex items-start justify-between gap-3">
              <div>
                <h3 className="text-xs font-semibold tracking-[0.24em] text-zinc-400">WATCHLISTS</h3>
                <p className="mt-1 text-[11px] text-zinc-500">Importees depuis IBKR Client Portal.</p>
                <p className="mt-1 text-[11px] text-zinc-600">
                  {activeWatchlist ? `${watchlistItems.length} lignes importees, ${visibleWatchlistItems.length} affichables` : '0 ligne importee'}
                </p>
              </div>
              <button
                onClick={importWatchlists}
                disabled={watchlistsLoading}
                className="rounded-lg border border-zinc-700 px-2.5 py-1.5 text-[11px] text-zinc-200 transition-colors hover:border-zinc-500 disabled:cursor-not-allowed disabled:opacity-50"
                title="Import watchlists from IBKR Client Portal into PostgreSQL"
              >
                {watchlistsLoading ? 'Importing…' : 'Import'}
              </button>
            </div>

            {watchlists.length > 0 ? (
              <>
                <div className="mt-3">
                  <select
                    value={activeWatchlist?.id ?? ''}
                    onChange={e => setSelectedWatchlistId(e.target.value)}
                    className="w-full rounded-lg border border-zinc-800 bg-zinc-950 px-3 py-2 text-xs text-zinc-200 outline-none"
                  >
                    {watchlists.map(watchlist => (
                      <option key={watchlist.id} value={watchlist.id} className="bg-zinc-950 text-zinc-200">
                        {watchlist.name}
                      </option>
                    ))}
                  </select>
                </div>

                <div className="scrollbar-subtle mt-3 min-h-0 flex-1 overflow-y-auto overscroll-contain pr-1">
                  <div className="space-y-2">
                  {visibleWatchlistDisplayItems.map(inst => {
                    const selected = inst.key === selectedWatchlistItemKey;
                    return (
                      <button
                        key={inst.key}
                        onClick={() => {
                          setSelectedWatchlistItemKey(inst.key);
                          if (inst.code) {
                            setInstrument(inst.code);
                          }
                        }}
                        disabled={!inst.selectable}
                        className={`flex w-full items-center justify-between rounded-lg border px-3 py-2 text-left transition-colors ${
                          selected
                            ? 'border-emerald-500/60 bg-emerald-500/10 text-white'
                            : inst.selectable
                              ? 'border-zinc-800 bg-zinc-950 text-zinc-300 hover:border-zinc-600'
                              : 'cursor-not-allowed border-zinc-900 bg-zinc-950/60 text-zinc-500'
                        }`}
                        title={inst.description ?? inst.label}
                      >
                        <div className="min-w-0">
                          <div className="text-sm font-medium">{inst.displayLabel}</div>
                          {inst.description && (
                            <div className="truncate text-[11px] text-zinc-500">
                              {inst.displayLabel === inst.description ? inst.label : inst.description}
                            </div>
                          )}
                        </div>
                        <div className="ml-3 text-right">
                          <div className="text-[11px] text-zinc-500">{timeframe}</div>
                          {inst.assetClass && (
                            <div className="text-[10px] uppercase tracking-wide text-zinc-600">{inst.assetClass}</div>
                          )}
                          {!inst.selectable && (
                            <div className="text-[10px] uppercase tracking-wide text-zinc-700">Unavailable</div>
                          )}
                        </div>
                      </button>
                    );
                  })}
                  {hiddenWatchlistItemsCount > 0 && (
                    <div className="rounded-lg border border-dashed border-zinc-800 bg-zinc-950/60 px-3 py-2 text-[11px] text-zinc-500">
                      {hiddenWatchlistItemsCount} ligne{hiddenWatchlistItemsCount > 1 ? 's' : ''} vide{hiddenWatchlistItemsCount > 1 ? 's' : ''} renvoyee{hiddenWatchlistItemsCount > 1 ? 's' : ''} par IBKR masquee{hiddenWatchlistItemsCount > 1 ? 's' : ''}.
                    </div>
                  )}
                  </div>
                </div>
              </>
            ) : (
              <div className="mt-3 rounded-lg border border-dashed border-zinc-800 bg-zinc-950 px-3 py-4 text-xs text-zinc-500">
                Aucune watchlist importee.
              </div>
            )}

            {watchlistsError && (
              <div className="mt-3 rounded-lg border border-amber-900/60 bg-amber-950/30 px-3 py-2 text-[11px] text-amber-300">
                {watchlistsError}
              </div>
            )}
          </section>
        </div>

        {/* Indicators */}
        <IndicatorPanel snapshot={snapshot} />

        {mentorInstrument ? (
          <MentorPanel
            instrument={mentorInstrument}
            timeframe={timeframe}
            timezone={timezone}
            connected={connected}
            summary={summary}
            snapshot={snapshot}
            prices={mergedPrices}
            alerts={alerts}
          />
        ) : (
          <section className="rounded-lg border border-zinc-800 bg-zinc-900/80 p-4 text-sm text-zinc-400">
            <div className="text-xs font-semibold tracking-[0.24em] text-zinc-400">MENTOR</div>
            <p className="mt-2">
              Le dashboard accepte maintenant les instruments importes depuis les watchlists IBKR sans liste fixe.
              Le flux mentor reste borne a `MCL`, `MGC`, `E6` et `MNQ`.
            </p>
            <p className="mt-1 text-zinc-500">Instrument selectionne: {instrument}</p>
          </section>
        )}

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
        />
      </div>
    </div>
  );
}
