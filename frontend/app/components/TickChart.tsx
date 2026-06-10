'use client';

import { memo, useEffect, useMemo, useRef, useState } from 'react';
import {
  createChart,
  ColorType,
  CandlestickSeries,
  HistogramSeries,
  IChartApi,
  IPriceLine,
  ISeriesApi,
  LineStyle,
  UTCTimestamp,
} from 'lightweight-charts';
import { TickBar, useOrderFlow } from '@/app/hooks/useOrderFlow';
import { api, IndicatorSnapshot } from '@/app/lib/api';

interface TickChartProps {
  selectedInstrument?: string;
  /** Dashboard's indicator snapshot — supplies SMC levels for the overlay. */
  snapshot?: IndicatorSnapshot | null;
}

/** Base bars kept client-side for re-aggregation (matches backend ring buffer). */
const MAX_BASE_BARS = 3000;
/** Merged bars actually rendered. */
const MAX_RENDERED_BARS = 300;
/** User-selectable bar sizes offered on top of the per-instrument base size. */
const EXTRA_BAR_SIZES = [1000, 2000];

/**
 * Re-aggregates base tick bars into larger constant-tick-count bars by grouping
 * `factor` consecutive base bars (grouped on absolute seq, so groups are stable
 * across ring-buffer eviction). The head group is dropped when its earliest base
 * bars were already evicted — its OHLC would be wrong.
 */
function mergeTickBars(bars: TickBar[], factor: number): TickBar[] {
  if (factor <= 1 || bars.length === 0) return bars;
  const out: TickBar[] = [];
  let group: TickBar[] = [];
  let groupKey = Math.floor(bars[0].seq / factor);

  const flush = () => {
    if (group.length === 0) return;
    const first = group[0];
    const last = group[group.length - 1];
    let high = first.high;
    let low = first.low;
    let buyVolume = 0;
    let sellVolume = 0;
    let tickCount = 0;
    for (const b of group) {
      high = Math.max(high, b.high);
      low = Math.min(low, b.low);
      buyVolume += b.buyVolume;
      sellVolume += b.sellVolume;
      tickCount += b.tickCount;
    }
    out.push({
      instrument: first.instrument,
      ticksPerBar: first.ticksPerBar * factor,
      seq: groupKey,
      openTime: first.openTime,
      closeTime: last.closeTime,
      open: first.open,
      high,
      low,
      close: last.close,
      volume: buyVolume + sellVolume,
      buyVolume,
      sellVolume,
      delta: buyVolume - sellVolume,
      tickCount,
      complete: group.length === factor && group.every(b => b.complete),
    });
  };

  for (const b of bars) {
    const key = Math.floor(b.seq / factor);
    if (key !== groupKey) {
      flush();
      group = [];
      groupKey = key;
    }
    group.push(b);
  }
  flush();

  // Drop a truncated head group (its first base bar isn't the group's true open).
  if (out.length > 1 && bars[0].seq % factor !== 0) out.shift();
  return out;
}

/**
 * Tick chart: constant-tick-count candles (e.g. one candle per 200 trades on MNQ)
 * with a per-bar delta histogram below. Bars are activity-normalized — they
 * compress in fast markets and stretch in quiet ones.
 *
 * Bar size is user-selectable: the per-instrument base (200 MNQ / 100 MCL) plus
 * 1000 and 2000 ticks, built by merging base bars client-side (exact: groups of
 * N consecutive base bars share boundaries with a native N×base aggregator).
 *
 * SMC overlay (toggleable): horizontal price lines for the 3 nearest active order
 * blocks, strong/weak highs/lows and premium/discount/equilibrium, sourced from
 * the Dashboard's indicator snapshot (selected timeframe, 30s poll).
 *
 * Data: REST seed (/api/order-flow/tick-bars) + live merge from /topic/tick-bars
 * (handled in useOrderFlow, keyed by bar seq).
 *
 * lightweight-charts requires strictly increasing times; consecutive tick bars can
 * close within the same second on a busy tape, so times are de-duplicated by
 * bumping +1s when needed (label drift of a few seconds is acceptable here).
 */
function TickChart({ selectedInstrument, snapshot }: TickChartProps) {
  const { tickBars } = useOrderFlow();
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const deltaSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const smcLinesRef = useRef<IPriceLine[]>([]);
  const lastCloseRef = useRef<number | null>(null);
  const [seedBars, setSeedBars] = useState<TickBar[]>([]);
  const [barSize, setBarSize] = useState<number | null>(null); // null = base size
  const [showSmc, setShowSmc] = useState(true);

  // REST seed on instrument switch (the WS tail only carries the last bars).
  useEffect(() => {
    setBarSize(null); // base size differs per instrument — reset the selector
    if (!selectedInstrument) { setSeedBars([]); return; }
    let cancelled = false;
    api.getTickBars(selectedInstrument, MAX_BASE_BARS).then(bars => {
      if (!cancelled) setSeedBars(Array.isArray(bars) ? (bars as unknown as TickBar[]) : []);
    }).catch(() => { if (!cancelled) setSeedBars([]); });
    return () => { cancelled = true; };
  }, [selectedInstrument]);

  // Merge REST seed + live bars by seq. Guard on instrument so a stale seed from
  // the previously selected instrument never mixes in while the new fetch resolves.
  const baseBars = useMemo(() => {
    if (!selectedInstrument) return [] as TickBar[];
    const live = tickBars.get(selectedInstrument) ?? [];
    const bySeq = new Map<number, TickBar>(
      seedBars.filter(b => b.instrument === selectedInstrument).map(b => [b.seq, b]),
    );
    for (const bar of live) bySeq.set(bar.seq, bar);
    return Array.from(bySeq.values()).sort((a, b) => a.seq - b.seq).slice(-MAX_BASE_BARS);
  }, [seedBars, tickBars, selectedInstrument]);

  const baseSize = baseBars.length > 0 ? baseBars[baseBars.length - 1].ticksPerBar : null;
  const sizeOptions = useMemo(() => {
    if (baseSize == null) return [] as number[];
    const opts = [baseSize, ...EXTRA_BAR_SIZES.filter(s => s > baseSize && s % baseSize === 0)];
    return Array.from(new Set(opts));
  }, [baseSize]);
  const effectiveSize = barSize != null && sizeOptions.includes(barSize) ? barSize : baseSize;

  const bars = useMemo(() => {
    if (baseSize == null || effectiveSize == null) return baseBars;
    return mergeTickBars(baseBars, effectiveSize / baseSize).slice(-MAX_RENDERED_BARS);
  }, [baseBars, baseSize, effectiveSize]);

  const lastBar = bars.length > 0 ? bars[bars.length - 1] : null;

  // Chart lifecycle
  useEffect(() => {
    if (!containerRef.current) return;
    const chart = createChart(containerRef.current, {
      layout: {
        background: { type: ColorType.Solid, color: 'transparent' },
        textColor: '#a1a1aa',
        fontSize: 10,
      },
      grid: {
        vertLines: { color: 'rgba(63,63,70,0.3)' },
        horzLines: { color: 'rgba(63,63,70,0.3)' },
      },
      height: 260,
      timeScale: { timeVisible: true, secondsVisible: true, borderColor: '#3f3f46' },
      rightPriceScale: { borderColor: '#3f3f46' },
    });
    const candles = chart.addSeries(CandlestickSeries, {
      upColor: '#10b981',
      downColor: '#ef4444',
      borderUpColor: '#10b981',
      borderDownColor: '#ef4444',
      wickUpColor: '#10b98180',
      wickDownColor: '#ef444480',
    });
    const delta = chart.addSeries(HistogramSeries, {
      priceFormat: { type: 'volume' },
      priceScaleId: 'delta',
    });
    chart.priceScale('delta').applyOptions({
      scaleMargins: { top: 0.82, bottom: 0 },
    });

    chartRef.current = chart;
    candleSeriesRef.current = candles;
    deltaSeriesRef.current = delta;

    const onResize = () => {
      if (containerRef.current) chart.applyOptions({ width: containerRef.current.clientWidth });
    };
    onResize();
    window.addEventListener('resize', onResize);
    return () => {
      window.removeEventListener('resize', onResize);
      chart.remove();
      chartRef.current = null;
      candleSeriesRef.current = null;
      deltaSeriesRef.current = null;
      smcLinesRef.current = [];
    };
  }, []);

  // Data updates — setData with de-duplicated, strictly increasing times.
  useEffect(() => {
    const candles = candleSeriesRef.current;
    const delta = deltaSeriesRef.current;
    if (!candles || !delta) return;

    // The container starts display:none while empty — re-sync width once data shows it.
    if (chartRef.current && containerRef.current && containerRef.current.clientWidth > 0) {
      chartRef.current.applyOptions({ width: containerRef.current.clientWidth });
    }

    let prevTime = 0;
    const candleData = [] as { time: UTCTimestamp; open: number; high: number; low: number; close: number }[];
    const deltaData = [] as { time: UTCTimestamp; value: number; color: string }[];
    for (const bar of bars) {
      const time = Math.max(bar.closeTime, prevTime + 1);
      prevTime = time;
      candleData.push({
        time: time as UTCTimestamp,
        open: bar.open,
        high: bar.high,
        low: bar.low,
        close: bar.close,
      });
      deltaData.push({
        time: time as UTCTimestamp,
        value: bar.delta,
        color: bar.delta >= 0 ? 'rgba(16,185,129,0.55)' : 'rgba(239,68,68,0.55)',
      });
    }
    candles.setData(candleData);
    delta.setData(deltaData);
    lastCloseRef.current = bars.length > 0 ? bars[bars.length - 1].close : null;
  }, [bars]);

  // SMC overlay — horizontal price lines from the indicator snapshot. Re-renders
  // when the snapshot refreshes (30s poll) or the toggle flips, not on every bar.
  useEffect(() => {
    const candles = candleSeriesRef.current;
    if (!candles) return;
    for (const line of smcLinesRef.current) candles.removePriceLine(line);
    smcLinesRef.current = [];

    // Guard on instrument: the snapshot may briefly lag an instrument switch.
    if (!showSmc || !snapshot || snapshot.instrument !== selectedInstrument) return;

    const add = (price: number | null, color: string, style: LineStyle, title: string) => {
      if (price == null) return;
      smcLinesRef.current.push(candles.createPriceLine({
        price, color, lineWidth: 1, lineStyle: style, axisLabelVisible: title !== '', title,
      }));
    };

    // Nearest 3 active order blocks (top + bottom lines, colored by direction).
    const ref = lastCloseRef.current;
    const orderBlocks = [...(snapshot.activeOrderBlocks ?? [])]
      .sort((a, b) => {
        if (ref == null) return 0;
        return Math.abs((a.high + a.low) / 2 - ref) - Math.abs((b.high + b.low) / 2 - ref);
      })
      .slice(0, 3);
    for (const ob of orderBlocks) {
      const bull = ob.type === 'BULLISH';
      const color = bull ? 'rgba(100,160,255,0.85)' : 'rgba(255,100,100,0.85)';
      add(ob.high, color, LineStyle.Dashed, bull ? 'OB ▲' : 'OB ▼');
      add(ob.low, color, LineStyle.Dashed, '');
    }

    add(snapshot.strongHigh, '#ef4444cc', LineStyle.Dashed, 'Strong H');
    add(snapshot.strongLow, '#22c55ecc', LineStyle.Dashed, 'Strong L');
    add(snapshot.premiumZoneTop, '#ef444480', LineStyle.Dotted, 'Premium');
    add(snapshot.equilibriumLevel, '#a78bfa99', LineStyle.Solid, 'EQ 50%');
    add(snapshot.discountZoneBottom, '#22c55e80', LineStyle.Dotted, 'Discount');
  }, [showSmc, snapshot, selectedInstrument]);

  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900/50 overflow-hidden">
      <div className="flex items-center justify-between px-3 py-2 border-b border-zinc-800/60">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-zinc-300">Tick Chart</span>
          {sizeOptions.length > 0 && (
            <div className="flex items-center gap-0.5">
              {sizeOptions.map(size => (
                <button
                  key={size}
                  onClick={() => setBarSize(size)}
                  className={`px-1.5 py-0.5 rounded text-[10px] font-medium transition-colors ${
                    size === effectiveSize
                      ? 'bg-zinc-700 text-zinc-100'
                      : 'text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800'
                  }`}
                  title={`${size} ticks par barre`}
                >
                  {size >= 1000 ? `${size / 1000}k` : size}
                </button>
              ))}
            </div>
          )}
          <button
            onClick={() => setShowSmc(v => !v)}
            className={`px-1.5 py-0.5 rounded text-[10px] font-medium transition-colors ${
              showSmc
                ? 'bg-indigo-900/60 text-indigo-300'
                : 'text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800'
            }`}
            title="Afficher / masquer les niveaux SMC (order blocks, structure, premium/discount)"
          >
            SMC
          </button>
          {bars.length > 0 && (
            <span className="text-[10px] text-zinc-600">{bars.length} bars</span>
          )}
        </div>
        {lastBar && (
          <div className="flex items-center gap-3 text-[10px]">
            <span className="text-zinc-500">
              {lastBar.complete ? 'closed' : `${lastBar.tickCount}/${lastBar.ticksPerBar}`}
            </span>
            <span className={lastBar.delta >= 0 ? 'text-emerald-400' : 'text-red-400'}>
              Δ {lastBar.delta >= 0 ? '+' : ''}{lastBar.delta}
            </span>
            <span className="text-zinc-400">{lastBar.close.toFixed(2)}</span>
          </div>
        )}
      </div>
      <div className="p-1">
        {/* Keep the container mounted so the chart instance survives empty states. */}
        <div ref={containerRef} className={`w-full ${bars.length === 0 ? 'hidden' : ''}`} />
        {bars.length === 0 && (
          <div className="text-xs text-zinc-600 text-center py-8">
            {selectedInstrument
              ? `Waiting for ${selectedInstrument} tick data...`
              : 'Select an instrument'}
          </div>
        )}
      </div>
    </div>
  );
}

export default memo(TickChart);
