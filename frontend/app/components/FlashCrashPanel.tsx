'use client';

import { useState, useEffect, useCallback, useMemo } from 'react';
import { useOrderFlow, FlashCrashState } from '@/app/hooks/useOrderFlow';
import { api } from '@/app/lib/api';
import { API_BASE } from '@/app/lib/runtimeConfig';

const BASE = API_BASE ?? '';
const INSTRUMENTS = ['MCL', 'MGC', 'MNQ', 'E6'] as const;
const TIMEFRAMES = ['1m', '5m', '10m'] as const;

const PHASE_COLORS: Record<string, { bg: string; text: string; label: string }> = {
  NORMAL:       { bg: 'bg-emerald-900/40', text: 'text-emerald-400', label: 'Normal' },
  INITIATING:   { bg: 'bg-yellow-900/40',  text: 'text-yellow-400',  label: 'Initiating' },
  ACCELERATING: { bg: 'bg-red-900/40',     text: 'text-red-400',     label: 'Accelerating' },
  DECELERATING: { bg: 'bg-orange-900/40',  text: 'text-orange-400',  label: 'Decelerating' },
  REVERSING:    { bg: 'bg-blue-900/40',    text: 'text-blue-400',    label: 'Reversing' },
};

const CONDITION_LABELS = [
  'Price Drop',
  'Volume Spike',
  'Spread Widen',
  'Bid Collapse',
  'Delta Crash',
];

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface FlashCrashConfig {
  priceDropThreshold: number;
  volumeSpikeMultiplier: number;
  spreadWidenMultiplier: number;
  bidCollapseThreshold: number;
  deltaCrashThreshold: number;
}

interface SimulationEvent {
  timestamp: string;
  phase: string;
  conditionsMet: number;
  reversalScore: number;
}

interface SimulationResult {
  instrument: string;
  crashEventCount: number;
  events: SimulationEvent[];
}

// ---------------------------------------------------------------------------
// Tab 1: Config & Simulate
// ---------------------------------------------------------------------------

function ConfigSimulateTab() {
  const [instrument, setInstrument] = useState<string>('MCL');
  const [timeframe, setTimeframe] = useState<string>('5m');
  const [fromDate, setFromDate] = useState('');
  const [toDate, setToDate] = useState('');
  const [config, setConfig] = useState<FlashCrashConfig>({
    priceDropThreshold: 0.5,
    volumeSpikeMultiplier: 3.0,
    spreadWidenMultiplier: 2.0,
    bidCollapseThreshold: 0.3,
    deltaCrashThreshold: -500,
  });
  const [loading, setLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [simResult, setSimResult] = useState<SimulationResult | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Load config for selected instrument
  const loadConfig = useCallback(async () => {
    try {
      const res = await fetch(`${BASE}/api/order-flow/flash-crash/config/${instrument}`, { cache: 'no-store' });
      if (res.ok) {
        setConfig(await res.json());
      }
    } catch {
      // keep defaults
    }
  }, [instrument]);

  useEffect(() => { loadConfig(); }, [loadConfig]);

  const saveConfig = async () => {
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(`${BASE}/api/order-flow/flash-crash/config/${instrument}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(config),
      });
      if (!res.ok) throw new Error(`Save failed: ${res.status}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  };

  const runSimulation = async () => {
    setLoading(true);
    setError(null);
    setSimResult(null);
    try {
      const res = await fetch(`${BASE}/api/order-flow/flash-crash/simulate`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ instrument, timeframe, fromDate, toDate, ...config }),
      });
      if (!res.ok) throw new Error(`Simulation failed: ${res.status}`);
      setSimResult(await res.json());
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Simulation failed');
    } finally {
      setLoading(false);
    }
  };

  const updateField = (key: keyof FlashCrashConfig, value: string) => {
    setConfig(prev => ({ ...prev, [key]: parseFloat(value) || 0 }));
  };

  return (
    <div className="flex flex-col gap-3">
      {/* Instrument + Timeframe selectors */}
      <div className="flex gap-2">
        <select
          value={instrument}
          onChange={e => setInstrument(e.target.value)}
          className="flex-1 bg-zinc-800 text-zinc-200 text-xs rounded px-2 py-1.5 border border-zinc-700 focus:outline-none focus:border-zinc-500"
        >
          {INSTRUMENTS.map(i => <option key={i} value={i}>{i}</option>)}
        </select>
        <select
          value={timeframe}
          onChange={e => setTimeframe(e.target.value)}
          className="bg-zinc-800 text-zinc-200 text-xs rounded px-2 py-1.5 border border-zinc-700 focus:outline-none focus:border-zinc-500"
        >
          {TIMEFRAMES.map(tf => <option key={tf} value={tf}>{tf}</option>)}
        </select>
      </div>

      {/* Threshold inputs */}
      <div className="grid grid-cols-2 gap-2">
        {([
          ['priceDropThreshold',     'Price Drop %'],
          ['volumeSpikeMultiplier',  'Vol Spike x'],
          ['spreadWidenMultiplier',  'Spread Widen x'],
          ['bidCollapseThreshold',   'Bid Collapse %'],
          ['deltaCrashThreshold',    'Delta Crash'],
        ] as [keyof FlashCrashConfig, string][]).map(([key, label]) => (
          <label key={key} className="flex flex-col gap-0.5">
            <span className="text-[10px] text-zinc-500">{label}</span>
            <input
              type="number"
              step="any"
              value={config[key]}
              onChange={e => updateField(key, e.target.value)}
              className="bg-zinc-800 text-zinc-200 text-xs rounded px-2 py-1 border border-zinc-700 focus:outline-none focus:border-zinc-500"
            />
          </label>
        ))}
      </div>

      {/* Save config */}
      <button
        onClick={saveConfig}
        disabled={saving}
        className="text-xs px-3 py-1 rounded bg-zinc-700 hover:bg-zinc-600 text-zinc-300 disabled:opacity-50 transition-colors"
      >
        {saving ? 'Saving...' : 'Save Config'}
      </button>

      {/* Date range */}
      <div className="flex gap-2">
        <label className="flex flex-col gap-0.5 flex-1">
          <span className="text-[10px] text-zinc-500">From</span>
          <input
            type="date"
            value={fromDate}
            onChange={e => setFromDate(e.target.value)}
            className="bg-zinc-800 text-zinc-200 text-xs rounded px-2 py-1 border border-zinc-700 focus:outline-none focus:border-zinc-500"
          />
        </label>
        <label className="flex flex-col gap-0.5 flex-1">
          <span className="text-[10px] text-zinc-500">To</span>
          <input
            type="date"
            value={toDate}
            onChange={e => setToDate(e.target.value)}
            className="bg-zinc-800 text-zinc-200 text-xs rounded px-2 py-1 border border-zinc-700 focus:outline-none focus:border-zinc-500"
          />
        </label>
      </div>

      {/* Simulate button */}
      <button
        onClick={runSimulation}
        disabled={loading || !fromDate || !toDate}
        className="text-xs px-3 py-1.5 rounded bg-blue-700 hover:bg-blue-600 text-white font-medium disabled:opacity-50 transition-colors"
      >
        {loading ? 'Simulating...' : 'Simulate'}
      </button>

      {error && <p className="text-xs text-red-400">{error}</p>}

      {/* Results */}
      {simResult && (
        <div className="flex flex-col gap-2">
          <div className="flex items-center gap-2 text-xs">
            <span className="text-zinc-400">Crash events:</span>
            <span className="font-bold text-zinc-200">{simResult.crashEventCount}</span>
          </div>
          <div className="flex flex-col gap-0.5 max-h-48 overflow-y-auto scrollbar-thin scrollbar-thumb-zinc-700">
            {simResult.events.map((evt, i) => {
              const phase = PHASE_COLORS[evt.phase] ?? PHASE_COLORS.NORMAL;
              return (
                <div key={i} className="flex items-center gap-2 px-2 py-1 text-[11px] rounded bg-zinc-800/40">
                  <span className="text-zinc-600 shrink-0">
                    {new Date(evt.timestamp).toLocaleString()}
                  </span>
                  <span className={`px-1.5 py-0.5 rounded ${phase.bg} ${phase.text} text-[9px] font-bold`}>
                    {phase.label}
                  </span>
                  <span className="text-zinc-500">
                    Conditions: {evt.conditionsMet}/5
                  </span>
                  <span className="text-zinc-500">
                    Rev: {evt.reversalScore.toFixed(2)}
                  </span>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Tab 2: Live Monitor
// ---------------------------------------------------------------------------

function LiveMonitorTab() {
  const { flashCrashState, connected } = useOrderFlow();
  // REST seed: the backend persists every FSM phase transition, but
  // /topic/flash-crash only emits forward-going events. On a fresh page load
  // the hook's Map is empty — this pulls the last known phase per instrument
  // so the trader doesn't see "no data" when the FSM actually has state.
  // WebSocket pushes take precedence the moment they arrive.
  const [seed, setSeed] = useState<Record<string, FlashCrashState>>({});

  useEffect(() => {
    let cancelled = false;
    api.getFlashCrashStatus()
      .then(res => {
        if (cancelled) return;
        const next: Record<string, FlashCrashState> = {};
        for (const [inst, entry] of Object.entries(res.instruments ?? {})) {
          next[inst] = {
            instrument: entry.instrument,
            phase: entry.phase,
            conditionsMet: entry.conditionsMet,
            // REST payload has an empty conditions[] since individual booleans
            // aren't persisted — FlashCrashCard renders 5 grey dots for this.
            conditions: Array.isArray(entry.conditions) ? entry.conditions : [],
            reversalScore: entry.reversalScore,
          };
        }
        setSeed(next);
      })
      .catch(() => { /* 404 / unavailable → leave seed empty */ });
    return () => { cancelled = true; };
  }, []);

  // Merge seed with live WS state, WS wins on conflicts.
  const states = useMemo(() => {
    const merged = new Map<string, FlashCrashState>();
    for (const [k, v] of Object.entries(seed)) merged.set(k, v);
    flashCrashState.forEach((v, k) => merged.set(k, v));
    return Array.from(merged.values());
  }, [seed, flashCrashState]);

  if (!connected && states.length === 0) {
    return <p className="text-xs text-zinc-500 italic">WebSocket disconnected</p>;
  }

  if (states.length === 0) {
    return <p className="text-xs text-zinc-500 italic">No flash crash data yet</p>;
  }

  return (
    <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
      {states.map(state => (
        <FlashCrashCard key={state.instrument} state={state} />
      ))}
    </div>
  );
}

function FlashCrashCard({ state }: { state: FlashCrashState }) {
  const phase = PHASE_COLORS[state.phase] ?? PHASE_COLORS.NORMAL;
  const reversalPct = Math.min(Math.max(state.reversalScore * 100, 0), 100);

  return (
    <div className={`flex flex-col gap-2 p-2.5 rounded border ${phase.bg} border-zinc-700`}>
      {/* Header */}
      <div className="flex items-center justify-between">
        <span className="text-xs font-semibold text-zinc-200">{state.instrument}</span>
        <span className={`px-2 py-0.5 rounded text-[10px] font-bold ${phase.bg} ${phase.text}`}>
          {phase.label}
        </span>
      </div>

      {/* Conditions indicators */}
      <div className="flex items-center gap-1.5">
        {CONDITION_LABELS.map((label, i) => {
          const met = state.conditions[i] ?? false;
          return (
            <div key={i} className="flex flex-col items-center gap-0.5">
              <div className={`w-3 h-3 rounded-full border-2 transition-colors ${
                met
                  ? 'bg-red-500 border-red-400'
                  : 'bg-zinc-800 border-zinc-600'
              }`} />
              <span className="text-[8px] text-zinc-500 text-center leading-tight">{label}</span>
            </div>
          );
        })}
        <span className="ml-auto text-[10px] text-zinc-400">
          {state.conditionsMet}/5
        </span>
      </div>

      {/* Reversal score gauge */}
      <div className="flex flex-col gap-0.5">
        <div className="flex items-center justify-between text-[10px] text-zinc-500">
          <span>Reversal</span>
          <span>{reversalPct.toFixed(0)}%</span>
        </div>
        <div className="h-1.5 rounded-full bg-zinc-700 overflow-hidden">
          <div
            className="h-full rounded-full bg-blue-500 transition-all duration-500"
            style={{ width: `${reversalPct}%` }}
          />
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Panel
// ---------------------------------------------------------------------------

export default function FlashCrashPanel() {
  const [activeTab, setActiveTab] = useState<'config' | 'monitor'>('monitor');

  return (
    <div className="flex flex-col gap-3 p-3 rounded-lg bg-zinc-900 border border-zinc-800">
      {/* Header + Tabs */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-zinc-200">Flash Crash</h3>
        <div className="flex rounded-lg overflow-hidden border border-zinc-700">
          <button
            onClick={() => setActiveTab('monitor')}
            className={`px-3 py-1 text-[10px] font-medium transition-colors ${
              activeTab === 'monitor'
                ? 'bg-zinc-700 text-white'
                : 'text-zinc-500 hover:text-zinc-300'
            }`}
          >
            Live Monitor
          </button>
          <button
            onClick={() => setActiveTab('config')}
            className={`px-3 py-1 text-[10px] font-medium transition-colors ${
              activeTab === 'config'
                ? 'bg-zinc-700 text-white'
                : 'text-zinc-500 hover:text-zinc-300'
            }`}
          >
            Config & Simulate
          </button>
        </div>
      </div>

      {/* Tab content */}
      {activeTab === 'monitor' ? <LiveMonitorTab /> : <ConfigSimulateTab />}
    </div>
  );
}
