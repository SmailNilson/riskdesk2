'use client';

import { memo, useEffect, useMemo, useRef, useState } from 'react';
import {
  createChart,
  ColorType,
  CandlestickSeries,
  HistogramSeries,
  IChartApi,
  ISeriesApi,
  UTCTimestamp,
} from 'lightweight-charts';
import { TickBar, useOrderFlow } from '@/app/hooks/useOrderFlow';
import { api } from '@/app/lib/api';

interface TickChartProps {
  selectedInstrument?: string;
}

/**
 * Tick chart: constant-tick-count candles (e.g. one candle per 200 trades on MNQ)
 * with a per-bar delta histogram below. Bars are activity-normalized — they
 * compress in fast markets and stretch in quiet ones.
 *
 * Data: REST seed (/api/order-flow/tick-bars) + live merge from /topic/tick-bars
 * (handled in useOrderFlow, keyed by bar seq).
 *
 * lightweight-charts requires strictly increasing times; consecutive tick bars can
 * close within the same second on a busy tape, so times are de-duplicated by
 * bumping +1s when needed (label drift of a few seconds is acceptable here).
 */
function TickChart({ selectedInstrument }: TickChartProps) {
  const { tickBars } = useOrderFlow();
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const deltaSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const [seedBars, setSeedBars] = useState<TickBar[]>([]);

  // REST seed on instrument switch (the WS tail only carries the last bars).
  useEffect(() => {
    if (!selectedInstrument) { setSeedBars([]); return; }
    let cancelled = false;
    api.getTickBars(selectedInstrument).then(bars => {
      if (!cancelled) setSeedBars(Array.isArray(bars) ? (bars as unknown as TickBar[]) : []);
    }).catch(() => { if (!cancelled) setSeedBars([]); });
    return () => { cancelled = true; };
  }, [selectedInstrument]);

  // Merge REST seed + live bars by seq. Guard on instrument so a stale seed from
  // the previously selected instrument never mixes in while the new fetch resolves.
  const bars = useMemo(() => {
    if (!selectedInstrument) return [] as TickBar[];
    const live = tickBars.get(selectedInstrument) ?? [];
    const bySeq = new Map<number, TickBar>(
      seedBars.filter(b => b.instrument === selectedInstrument).map(b => [b.seq, b]),
    );
    for (const bar of live) bySeq.set(bar.seq, bar);
    return Array.from(bySeq.values()).sort((a, b) => a.seq - b.seq).slice(-300);
  }, [seedBars, tickBars, selectedInstrument]);

  const ticksPerBar = bars.length > 0 ? bars[bars.length - 1].ticksPerBar : null;
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
  }, [bars]);

  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900/50 overflow-hidden">
      <div className="flex items-center justify-between px-3 py-2 border-b border-zinc-800/60">
        <div className="flex items-center gap-2">
          <span className="text-xs font-semibold text-zinc-300">Tick Chart</span>
          {ticksPerBar != null && (
            <span className="text-[10px] text-zinc-600">
              {ticksPerBar} ticks/bar &middot; {bars.length} bars
            </span>
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
