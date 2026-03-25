'use client';
import { useEffect, useRef, useState } from 'react';
import { api, BacktestResult } from '../lib/api';

export default function BacktestPanel() {
  const stopLossOptions = [0, 100, 200, 300, 400, 500];
  const bollingerLengthOptions = [10, 20, 30, 50];
  const quantityOptions = [1, 2, 3, 5, 10];
  const [original, setOriginal] = useState<BacktestResult | null>(null);
  const [filtered, setFiltered] = useState<BacktestResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [showTrades, setShowTrades] = useState(false);
  const [showDebug, setShowDebug] = useState(false);
  const [quantity, setQuantity] = useState(2);
  const [pyramiding, setPyramiding] = useState(0);
  const [stopLoss, setStopLoss] = useState(300);
  const [useAtr, setUseAtr] = useState(false);
  const [emaPeriod, setEmaPeriod] = useState(0);
  const [useBbTakeProfit, setUseBbTakeProfit] = useState(true);
  const [bbLength, setBbLength] = useState(20);
  const [closeEod, setCloseEod] = useState(false);
  const [closeEow, setCloseEow] = useState(false);
  const [entryOn, setEntryOn] = useState(1);
  const [debugMode, setDebugMode] = useState(true);
  const [dataSource, setDataSource] = useState<'ibkr' | '6m' | '3m'>('ibkr');
  const hasRunRef = useRef(false);
  const autoRunTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const loadingRef = useRef(false);
  const runRef = useRef<() => Promise<void>>(async () => {});

  const dataSources: Record<string, { label: string; fromDate?: string; desc: string }> = {
    ibkr: { label: 'DB', fromDate: '2025-06-20T00:00:00Z', desc: 'PostgreSQL coverage auto-audited' },
    '6m': { label: '6M', fromDate: new Date(Date.now() - 182 * 86400000).toISOString(), desc: '6 months' },
    '3m': { label: '3M', fromDate: new Date(Date.now() - 91 * 86400000).toISOString(), desc: '3 months' },
  };

  const loadBest = () => {
    setQuantity(2);
    setPyramiding(0);
    setStopLoss(300);
    setUseAtr(false);
    setEmaPeriod(0);
    setUseBbTakeProfit(true);
    setBbLength(20);
    setCloseEod(false);
    setCloseEow(true);
    setEntryOn(2);
    setDebugMode(true);
    setDataSource('ibkr');
  };

  const run = async () => {
    setLoading(true);
    loadingRef.current = true;
    try {
      const src = dataSources[dataSource];
      const base = {
        instrument: 'MNQ',
        timeframe: '1h',
        pyramiding,
        continuous: false,
        qty: quantity,
        capital: 10000,
        pointValue: 2,
        nextBarEntry: false,
        debug: debugMode,
        fromDate: src.fromDate,
      };
      const [r1, r2] = await Promise.all([
        api.runBacktest(base),
        api.runBacktest({
          ...base,
          emaFilterPeriod: emaPeriod > 0 ? emaPeriod : undefined,
          stopLossPoints: stopLoss > 0 ? stopLoss : undefined,
          atrTrailingStop: useAtr,
          atrMultiplier: 2.0,
          atrPeriod: 14,
          bollingerTakeProfit: useBbTakeProfit || undefined,
          bollingerLength: useBbTakeProfit ? bbLength : undefined,
          closeEndOfDay: closeEod || undefined,
          closeEndOfWeek: closeEow || undefined,
          entryOnSignal: entryOn > 1 ? entryOn : undefined,
        }),
      ]);
      setOriginal(r1);
      setFiltered(r2);
      hasRunRef.current = true;
    } catch (e) {
      console.error('Backtest failed:', e);
    } finally {
      setLoading(false);
      loadingRef.current = false;
    }
  };

  runRef.current = run;

  useEffect(() => {
    if (!hasRunRef.current || loadingRef.current) {
      return;
    }

    if (autoRunTimerRef.current) {
      clearTimeout(autoRunTimerRef.current);
    }

    autoRunTimerRef.current = setTimeout(() => {
      void runRef.current();
    }, 250);

    return () => {
      if (autoRunTimerRef.current) {
        clearTimeout(autoRunTimerRef.current);
      }
    };
  }, [
    quantity,
    pyramiding,
    stopLoss,
    useAtr,
    emaPeriod,
    useBbTakeProfit,
    bbLength,
    closeEod,
    closeEow,
    entryOn,
    debugMode,
    dataSource
  ]);

  const pnlColor = (v: number) => v >= 0 ? 'text-emerald-400' : 'text-red-400';
  const fmt = (v: number) => v.toLocaleString('en-US', { minimumFractionDigits: 0, maximumFractionDigits: 2 });
  const filteredStopHits = filtered ? countExitReason(filtered, 'STOP_LOSS') : 0;
  const filteredBbHits = filtered ? countExitReason(filtered, 'BB_TAKE_PROFIT') : 0;

  return (
    <div className="bg-zinc-900/80 border border-zinc-800 rounded-lg p-3">
      {/* Header */}
      <div className="flex items-center justify-between mb-2">
        <div className="flex items-center gap-2">
          <span className="text-[11px] font-bold uppercase tracking-widest text-zinc-400">Backtest</span>
          <span className="text-[9px] bg-zinc-800 text-zinc-500 rounded px-1.5 py-0.5">Original vs Filtered</span>
          <button
            onClick={loadBest}
            className="text-[9px] font-semibold px-2 py-0.5 rounded bg-emerald-700 hover:bg-emerald-600 text-emerald-100 transition-colors"
          >
            Best
          </button>
        </div>
        <button
          onClick={run}
          disabled={loading}
          className="text-[10px] font-semibold px-3 py-1 rounded bg-blue-600 hover:bg-blue-500 disabled:bg-zinc-700 disabled:text-zinc-500 transition-colors"
        >
          {loading ? 'Running...' : 'Run Comparison'}
        </button>
      </div>

      {/* Config bar */}
      <div className="flex flex-wrap items-center gap-3 mb-3 py-1.5 px-2 bg-zinc-800/50 rounded text-[10px]">
        <div className="flex items-center gap-1.5">
          <span className="text-zinc-500">Data:</span>
          {Object.entries(dataSources).map(([k, v]) => (
            <button
              key={k}
              onClick={() => setDataSource(k as typeof dataSource)}
              className={`px-1.5 py-0.5 rounded text-[9px] font-semibold transition-colors ${
                dataSource === k ? 'bg-green-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {v.label}
            </button>
          ))}
          <span className="text-zinc-600 text-[8px]">({dataSources[dataSource].desc})</span>
        </div>
        <div className="w-px h-4 bg-zinc-700" />
        <span className="text-[9px] font-semibold text-zinc-400">MNQ <span className="text-zinc-600">($2/pt)</span></span>
        <div className="w-px h-4 bg-zinc-700" />
        <div className="flex items-center gap-1.5">
          <span className="text-zinc-500">Qty:</span>
          {quantityOptions.map(q => (
            <button
              key={q}
              onClick={() => setQuantity(q)}
              className={`px-1.5 py-0.5 rounded text-[9px] font-semibold transition-colors ${
                quantity === q ? 'bg-blue-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {q}
            </button>
          ))}
        </div>
        <div className="w-px h-4 bg-zinc-700" />
        <div className="flex items-center gap-1.5">
          <span className="text-zinc-500">Pyramiding:</span>
          {[0, 1, 3, 5, 10].map(p => (
            <button
              key={p}
              onClick={() => setPyramiding(p)}
              className={`px-1.5 py-0.5 rounded text-[9px] font-semibold transition-colors ${
                pyramiding === p ? 'bg-blue-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {p}
            </button>
          ))}
        </div>
        <div className="w-px h-4 bg-zinc-700" />
        <div className="flex items-center gap-1.5">
          <span className="text-zinc-500">SL pts:</span>
          {stopLossOptions.map(sl => (
            <button
              key={sl}
              onClick={() => setStopLoss(sl)}
              className={`px-1.5 py-0.5 rounded text-[9px] font-semibold transition-colors ${
                stopLoss === sl ? 'bg-blue-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {sl === 0 ? 'Off' : sl}
            </button>
          ))}
          <span className="text-zinc-600 text-[8px]">MNQ: 100 pts = $200/contract</span>
        </div>
        <div className="w-px h-4 bg-zinc-700" />
        <div className="flex items-center gap-1.5">
          <span className="text-zinc-500">EMA:</span>
          {[0, 9, 50, 200].map(p => (
            <button
              key={p}
              onClick={() => setEmaPeriod(p)}
              className={`px-1.5 py-0.5 rounded text-[9px] font-semibold transition-colors ${
                emaPeriod === p ? 'bg-amber-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {p === 0 ? 'Off' : p}
            </button>
          ))}
        </div>
        <div className="w-px h-4 bg-zinc-700" />
        <div className="flex items-center gap-1.5">
          <button
            onClick={() => setUseBbTakeProfit(!useBbTakeProfit)}
            className={`px-2 py-0.5 rounded text-[9px] font-semibold transition-colors ${
              useBbTakeProfit ? 'bg-teal-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
            }`}
          >
            BB TP {useBbTakeProfit ? 'ON' : 'OFF'}
          </button>
          {bollingerLengthOptions.map(length => (
            <button
              key={length}
              onClick={() => setBbLength(length)}
              disabled={!useBbTakeProfit}
              className={`px-1.5 py-0.5 rounded text-[9px] font-semibold transition-colors ${
                bbLength === length && useBbTakeProfit ? 'bg-teal-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
              } disabled:opacity-40 disabled:hover:text-zinc-400`}
            >
              {length}
            </button>
          ))}
        </div>
        <div className="w-px h-4 bg-zinc-700" />
        <button
          onClick={() => setUseAtr(!useAtr)}
          className={`px-2 py-0.5 rounded text-[9px] font-semibold transition-colors ${
            useAtr ? 'bg-purple-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
          }`}
        >
          ATR-TS {useAtr ? 'ON' : 'OFF'}
        </button>
        <div className="w-px h-4 bg-zinc-700" />
        <button
          onClick={() => setDebugMode(!debugMode)}
          className={`px-2 py-0.5 rounded text-[9px] font-semibold transition-colors ${
            debugMode ? 'bg-fuchsia-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
          }`}
        >
          Debug {debugMode ? 'ON' : 'OFF'}
        </button>
        <div className="w-px h-4 bg-zinc-700" />
        <button
          onClick={() => setCloseEod(!closeEod)}
          className={`px-2 py-0.5 rounded text-[9px] font-semibold transition-colors ${
            closeEod ? 'bg-orange-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
          }`}
        >
          EOD {closeEod ? 'ON' : 'OFF'}
        </button>
        <button
          onClick={() => setCloseEow(!closeEow)}
          className={`px-2 py-0.5 rounded text-[9px] font-semibold transition-colors ${
            closeEow ? 'bg-orange-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
          }`}
        >
          EOW {closeEow ? 'ON' : 'OFF'}
        </button>
        <div className="w-px h-4 bg-zinc-700" />
        <div className="flex items-center gap-1.5">
          <span className="text-zinc-500">Entry@:</span>
          {[1, 2, 3, 4].map(n => (
            <button
              key={n}
              onClick={() => setEntryOn(n)}
              className={`px-1.5 py-0.5 rounded text-[9px] font-semibold transition-colors ${
                entryOn === n ? 'bg-cyan-600 text-white' : 'bg-zinc-700 text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {n === 1 ? '1st' : n === 2 ? '2nd' : n === 3 ? '3rd' : '4th'}
            </button>
          ))}
        </div>
      </div>

      {!original ? (
        <p className="text-[10px] text-zinc-600 py-4 text-center">
          WT_X Original vs Filtered (EMA + SL + ATR + EOD/EOW) | Configure &amp; Run
        </p>
      ) : (
        <>
          {/* Comparison Header */}
          <div className="grid grid-cols-2 gap-3 mb-3">
            <div className="text-center">
              <span className="text-[9px] font-bold uppercase tracking-widest text-blue-400">WT_X Original</span>
              <span className="text-[8px] text-zinc-600 ml-1">(sans filtre)</span>
            </div>
            <div className="text-center">
              <span className="text-[9px] font-bold uppercase tracking-widest text-emerald-400">WT_X Filtered</span>
              <span className="text-[8px] text-zinc-600 ml-1">
                ({[
                  entryOn > 1 && `Entry@${entryOn}`,
                  emaPeriod > 0 && `EMA${emaPeriod}`,
                  stopLoss > 0 && `SL${stopLoss}${filtered ? ` (${filteredStopHits} hit${filteredStopHits > 1 ? 's' : ''})` : ''}`,
                  useBbTakeProfit && `BBTP${bbLength}${filtered ? ` (${filteredBbHits} hit${filteredBbHits > 1 ? 's' : ''})` : ''}`,
                  useAtr && 'ATR',
                  closeEod && 'EOD',
                  closeEow && 'EOW'
                ].filter(Boolean).join('+') || 'same'})
              </span>
            </div>
          </div>

          {/* Side-by-side Stats */}
          <div className="grid grid-cols-2 gap-3 mb-3">
            <StatsBlock result={original} pnlColor={pnlColor} fmt={fmt} accent="blue" />
            <StatsBlock result={filtered!} pnlColor={pnlColor} fmt={fmt} accent="emerald" />
          </div>

          {/* Delta summary */}
          {filtered && (
            <div className="flex items-center justify-center gap-4 mb-3 py-1.5 bg-zinc-800/50 rounded">
              <DeltaStat label="PnL Diff" value={filtered.totalPnl - original.totalPnl} fmt={fmt} prefix="$" />
              <DeltaStat label="Return Diff" value={filtered.totalReturnPct - original.totalReturnPct} fmt={fmt} suffix="%" />
              <DeltaStat label="Win Rate Diff" value={filtered.winRate - original.winRate} fmt={fmt} suffix="%" />
              <DeltaStat label="PF Diff" value={filtered.profitFactor - original.profitFactor} fmt={fmt} />
              <DeltaStat label="DD Diff" value={-(filtered.maxDrawdown - original.maxDrawdown)} fmt={fmt} prefix="$" />
            </div>
          )}

          {/* Strategy name */}
          {filtered && (
            <div className="text-center mb-2">
              <span className="text-[8px] text-zinc-600">{filtered.strategy}</span>
            </div>
          )}

          {filtered && stopLoss > 0 && filteredStopHits === 0 && (
            <div className="mb-3 rounded border border-zinc-800 bg-zinc-950/40 px-3 py-2 text-[9px] text-amber-300">
              {`SL${stopLoss}`} is active, but no trade hit the stop on this sample. Try a tighter stop like {`100`} if you want to stress-test the filter.
            </div>
          )}

          {filtered?.dataAudit && (
            <div className="mb-3 rounded border border-zinc-800 bg-zinc-950/40 p-2">
              <div className="flex items-center justify-between mb-1">
                <span className="text-[9px] font-bold uppercase tracking-widest text-zinc-500">Data Audit</span>
                <span className={`text-[9px] font-semibold ${filtered.dataAudit.sufficientWarmup ? 'text-emerald-400' : 'text-amber-400'}`}>
                  Warmup {filtered.dataAudit.availableWarmupBars}/{filtered.dataAudit.requestedWarmupBars}
                </span>
              </div>
              <div className="mb-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[8px] text-zinc-500">
                <span>
                  Coverage {fmtAuditTime(filtered.dataAudit.firstCandleTime)} - {fmtAuditTime(filtered.dataAudit.lastCandleTime)}
                </span>
                <span>
                  Eval {fmtAuditTime(filtered.dataAudit.evaluationStartTime)}
                </span>
                {filtered.dataAudit.adjustedEvaluationStart && filtered.dataAudit.requestedEvaluationStartTime && (
                  <span className="text-amber-300">
                    requested {fmtAuditTime(filtered.dataAudit.requestedEvaluationStartTime)}
                  </span>
                )}
              </div>
              <div className="grid grid-cols-5 gap-2 text-[9px]">
                <Stat label="Loaded" value={String(filtered.dataAudit.loadedCandles)} />
                <Stat label="Evaluated" value={String(filtered.dataAudit.evaluatedCandles)} />
                <Stat label="Duplicates" value={String(filtered.dataAudit.duplicateCandles)} color={filtered.dataAudit.duplicateCandles === 0 ? 'text-emerald-400' : 'text-red-400'} />
                <Stat label="Misaligned" value={String(filtered.dataAudit.misalignedCandles)} color={filtered.dataAudit.misalignedCandles === 0 ? 'text-emerald-400' : 'text-red-400'} />
                <Stat label="Susp. Gaps" value={String(filtered.dataAudit.suspiciousGapCount)} color={filtered.dataAudit.suspiciousGapCount === 0 ? 'text-emerald-400' : 'text-amber-400'} />
              </div>
              {filtered.dataAudit.warnings.length > 0 && (
                <div className="mt-2 flex flex-col gap-1">
                  {filtered.dataAudit.warnings.map(w => (
                    <div key={w} className="text-[8px] text-amber-300">{w}</div>
                  ))}
                </div>
              )}
            </div>
          )}

          {/* Equity Curves overlaid */}
          <div className="mb-3">
            <EquityCurveComparison
              data1={original.equityCurve} data2={filtered?.equityCurve ?? []}
              initial={original.initialCapital}
              label1="Original" label2="Filtered"
              color1="#3b82f6" color2="#34d399"
            />
          </div>

          {/* Trades tables */}
          <div className="flex items-center justify-between mb-1">
            <span className="text-[9px] font-bold uppercase tracking-widest text-zinc-500">
              Trades — Original: {original.totalTrades} | Filtered: {filtered?.totalTrades ?? 0}
            </span>
            <div className="flex items-center gap-3">
              <button onClick={() => setShowDebug(!showDebug)} className="text-[9px] text-zinc-500 hover:text-zinc-300">
                {showDebug ? 'Hide Debug' : 'Show Debug'}
              </button>
              <button onClick={() => setShowTrades(!showTrades)} className="text-[9px] text-zinc-500 hover:text-zinc-300">
                {showTrades ? 'Hide Trades' : 'Show Trades'}
              </button>
            </div>
          </div>

          {showDebug && (
            <div className="grid grid-cols-2 gap-2 mb-3">
              <DebugTable events={original.debugEvents} accent="blue" />
              <DebugTable events={filtered?.debugEvents ?? []} accent="emerald" />
            </div>
          )}

          {showTrades && (
            <div className="grid grid-cols-2 gap-2">
              <TradeTable trades={original.trades} pnlColor={pnlColor} fmt={fmt} accent="blue" />
              <TradeTable trades={filtered?.trades ?? []} pnlColor={pnlColor} fmt={fmt} accent="emerald" />
            </div>
          )}
        </>
      )}
    </div>
  );
}

function countExitReason(result: BacktestResult, reason: string) {
  return result.trades.filter(trade => trade.exitReason === reason).length;
}

function StatsBlock({ result, pnlColor, fmt, accent }: {
  result: BacktestResult; pnlColor: (v: number) => string; fmt: (v: number) => string; accent: string;
}) {
  const borderMap: Record<string, string> = { blue: 'border-blue-900/50', amber: 'border-amber-900/50', emerald: 'border-emerald-900/50' };
  const border = borderMap[accent] ?? 'border-zinc-800';
  return (
    <div className={`border ${border} rounded p-2`}>
      <div className="grid grid-cols-4 gap-1.5">
        <Stat label="PnL" value={`$${fmt(result.totalPnl)}`} color={pnlColor(result.totalPnl)} />
        <Stat label="Return" value={`${fmt(result.totalReturnPct)}%`} color={pnlColor(result.totalReturnPct)} />
        <Stat label="Win Rate" value={`${fmt(result.winRate)}%`} color={result.winRate >= 50 ? 'text-emerald-400' : 'text-red-400'} />
        <Stat label="PF" value={fmt(result.profitFactor)} color={result.profitFactor >= 1 ? 'text-emerald-400' : 'text-red-400'} />
        <Stat label="Trades" value={String(result.totalTrades)} />
        <Stat label="W/L" value={`${result.wins}/${result.losses}`} />
        <Stat label="Max DD" value={`$${fmt(result.maxDrawdown)}`} color="text-red-400" />
        <Stat label="Sharpe" value={fmt(result.sharpeRatio)} />
      </div>
    </div>
  );
}

function Stat({ label, value, color = 'text-zinc-200' }: { label: string; value: string; color?: string }) {
  return (
    <div className="text-center">
      <div className="text-[8px] text-zinc-500 uppercase tracking-wider">{label}</div>
      <div className={`text-[11px] font-bold ${color}`}>{value}</div>
    </div>
  );
}

function DeltaStat({ label, value, fmt, prefix = '', suffix = '' }: {
  label: string; value: number; fmt: (v: number) => string; prefix?: string; suffix?: string;
}) {
  const color = value >= 0 ? 'text-emerald-400' : 'text-red-400';
  return (
    <div className="text-center">
      <div className="text-[8px] text-zinc-600 uppercase">{label}</div>
      <div className={`text-[11px] font-bold ${color}`}>
        {value >= 0 ? '+' : ''}{prefix}{fmt(value)}{suffix}
      </div>
    </div>
  );
}

function fmtAuditTime(iso: string | null) {
  if (!iso) return 'N/A';
  const d = new Date(iso);
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')} ${String(d.getUTCHours()).padStart(2, '0')}:${String(d.getUTCMinutes()).padStart(2, '0')}Z`;
}

function fmtDate(iso: string) {
  if (!iso) return '—';
  const d = new Date(iso);
  const mo = String(d.getUTCMonth() + 1).padStart(2, '0');
  const dy = String(d.getUTCDate()).padStart(2, '0');
  const hr = String(d.getUTCHours()).padStart(2, '0');
  const mn = String(d.getUTCMinutes()).padStart(2, '0');
  return `${mo}/${dy} ${hr}:${mn}`;
}

function TradeTable({ trades, pnlColor, fmt, accent }: {
  trades: { tradeNo: number; side: string; entryPrice: number; entryTime: string; exitPrice: number; exitTime: string; pnl: number; exitReason: string }[];
  pnlColor: (v: number) => string; fmt: (v: number) => string; accent: string;
}) {
  const headerColor = accent === 'emerald' ? 'text-emerald-500' : 'text-blue-500';
  return (
    <div className="overflow-x-auto max-h-[250px] overflow-y-auto">
      <table className="w-full text-[9px]">
        <thead className={`${headerColor} border-b border-zinc-800 sticky top-0 bg-zinc-900`}>
          <tr>
            <th className="text-left py-1 px-1">#</th>
            <th className="text-left py-1 px-1">Side</th>
            <th className="text-right py-1 px-1">Entry Date</th>
            <th className="text-right py-1 px-1">Entry</th>
            <th className="text-right py-1 px-1">Exit Date</th>
            <th className="text-right py-1 px-1">Exit</th>
            <th className="text-right py-1 px-1">PnL</th>
            <th className="text-left py-1 px-1">Reason</th>
          </tr>
        </thead>
        <tbody>
          {trades.map(t => (
            <tr key={t.tradeNo} className="border-b border-zinc-800/50 hover:bg-zinc-800/30">
              <td className="py-0.5 px-1 text-zinc-500">{t.tradeNo}</td>
              <td className={`py-0.5 px-1 font-semibold ${t.side === 'LONG' ? 'text-emerald-400' : 'text-red-400'}`}>
                {t.side === 'LONG' ? 'L' : 'S'}
              </td>
              <td className="py-0.5 px-1 text-right text-zinc-500 font-mono">{fmtDate(t.entryTime)}</td>
              <td className="py-0.5 px-1 text-right text-zinc-300">{fmt(t.entryPrice)}</td>
              <td className="py-0.5 px-1 text-right text-zinc-500 font-mono">{fmtDate(t.exitTime)}</td>
              <td className="py-0.5 px-1 text-right text-zinc-300">{fmt(t.exitPrice)}</td>
              <td className={`py-0.5 px-1 text-right font-semibold ${pnlColor(t.pnl)}`}>
                {t.pnl >= 0 ? '+' : ''}${fmt(t.pnl)}
              </td>
              <td className="py-0.5 px-1 text-zinc-600 text-[8px]">{
                t.exitReason === 'STOP_LOSS' ? 'SL' :
                t.exitReason === 'BB_TAKE_PROFIT' ? 'BB TP' :
                t.exitReason === 'SIGNAL_REVERSE' ? 'REV' :
                t.exitReason === 'END_OF_DAY' ? 'EOD' :
                t.exitReason === 'END_OF_WEEK' ? 'EOW' :
                t.exitReason === 'ATR_TRAILING' ? 'ATR' : t.exitReason
              }</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function DebugTable({ events, accent }: {
  events: { time: string; event: string; side: string; price: number; reason: string; wt1: number | null; wt2: number | null; stopPrice: number | null }[];
  accent: string;
}) {
  const headerColor = accent === 'emerald' ? 'text-emerald-500' : 'text-blue-500';
  const rows = events.slice(-80).reverse();
  return (
    <div className="overflow-x-auto max-h-[250px] overflow-y-auto">
      <table className="w-full text-[9px]">
        <thead className={`${headerColor} border-b border-zinc-800 sticky top-0 bg-zinc-900`}>
          <tr>
            <th className="text-left py-1 px-1">Time</th>
            <th className="text-left py-1 px-1">Evt</th>
            <th className="text-left py-1 px-1">Side</th>
            <th className="text-right py-1 px-1">Price</th>
            <th className="text-right py-1 px-1">WT1/WT2</th>
            <th className="text-left py-1 px-1">Reason</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((event, idx) => (
            <tr key={`${event.time}-${event.event}-${idx}`} className="border-b border-zinc-800/50 hover:bg-zinc-800/30">
              <td className="py-0.5 px-1 text-zinc-500 font-mono">{fmtDate(event.time)}</td>
              <td className="py-0.5 px-1 text-zinc-300">{event.event}</td>
              <td className="py-0.5 px-1 text-zinc-400">{event.side}</td>
              <td className="py-0.5 px-1 text-right text-zinc-300">{event.price.toFixed(2)}</td>
              <td className="py-0.5 px-1 text-right text-zinc-500 font-mono">
                {event.wt1 == null || event.wt2 == null ? '—' : `${event.wt1.toFixed(1)} / ${event.wt2.toFixed(1)}`}
              </td>
              <td className="py-0.5 px-1 text-zinc-600 text-[8px]">{event.reason}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function EquityCurveComparison({ data1, data2, initial, label1, label2, color1, color2 }: {
  data1: number[]; data2: number[]; initial: number;
  label1: string; label2: string; color1: string; color2: string;
}) {
  if (data1.length < 2) return null;

  const allData = [...data1, ...data2];
  const w = 600, h = 60;
  const min = Math.min(...allData);
  const max = Math.max(...allData);
  const range = max - min || 1;

  const toPoints = (data: number[]) => {
    const step = Math.max(1, Math.floor(data.length / 300));
    return data
      .filter((_, i) => i % step === 0 || i === data.length - 1)
      .map((v, i, arr) => {
        const x = (i / (arr.length - 1)) * w;
        const y = h - ((v - min) / range) * h;
        return `${x},${y}`;
      })
      .join(' ');
  };

  return (
    <div className="relative">
      <svg viewBox={`0 0 ${w} ${h}`} className="w-full h-[60px]" preserveAspectRatio="none">
        <line
          x1="0" x2={String(w)}
          y1={String(h - ((initial - min) / range) * h)}
          y2={String(h - ((initial - min) / range) * h)}
          stroke="#555" strokeWidth="0.5" strokeDasharray="4" vectorEffect="non-scaling-stroke"
        />
        <polyline points={toPoints(data1)} fill="none" stroke={color1} strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
        {data2.length > 1 && (
          <polyline points={toPoints(data2)} fill="none" stroke={color2} strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
        )}
      </svg>
      <div className="absolute top-0 right-0 flex gap-3 text-[8px]">
        <span style={{ color: color1 }}>${data1[data1.length - 1].toLocaleString()}</span>
        {data2.length > 1 && <span style={{ color: color2 }}>${data2[data2.length - 1].toLocaleString()}</span>}
      </div>
      <div className="absolute bottom-0 left-0 flex gap-3 text-[7px]">
        <span style={{ color: color1, opacity: 0.6 }}>― {label1}</span>
        <span style={{ color: color2, opacity: 0.6 }}>― {label2}</span>
      </div>
    </div>
  );
}
