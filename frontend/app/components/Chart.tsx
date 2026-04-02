'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import {
  createChart,
  IChartApi,
  ISeriesApi,
  createSeriesMarkers,
  SeriesMarker,
  CandlestickSeries,
  LineSeries,
  HistogramSeries,
  ColorType,
  CrosshairMode,
  LineStyle,
  Time,
} from 'lightweight-charts';
import { IndicatorSnapshot, api } from '@/app/lib/api';
import { breakerReferenceTime, relevantBreakerBlocks } from '@/app/lib/orderBlocks';
import { PriceUpdate } from '@/app/hooks/useWebSocket';
import { makeChartTickFormatter, makeChartTimeFormatter } from '@/app/lib/datetime';

interface Props {
  instrument: string;
  timeframe: string;
  timezone: string;           // IANA timezone e.g. "UTC", "America/New_York"
  theme: 'dark' | 'light';
  snapshot: IndicatorSnapshot | null;
  livePrice: PriceUpdate | undefined;
}

type CandleSeries  = ISeriesApi<'Candlestick', Time>;
type Line          = ISeriesApi<'Line', Time>;
type Histogram     = ISeriesApi<'Histogram', Time>;
type CandlePoint   = { time: Time; open: number; high: number; low: number; close: number };

const WT_OVERBOUGHT = 53;
const WT_OVERSOLD = -53;
const PRESENT_OB_WINDOW_BARS = 48;

function timeframeToSeconds(timeframe: string) {
  return timeframe === '1d' ? 86400 : timeframe === '1h' ? 3600 : timeframe === '5m' ? 300 : 600;
}

// Normalise any time value coming from the backend to a Unix timestamp in seconds.
// The Java backend may serialise LocalDateTime / Instant as an ISO string, a plain
// number (epoch-seconds or epoch-millis), or even a structured object — handle all.
function toUnixSeconds(raw: unknown): number {
  if (typeof raw === 'number') {
    // Epoch-millis are > ~1e12; epoch-seconds are ~1.7e9 as of 2025
    return raw > 1e10 ? Math.floor(raw / 1000) : Math.floor(raw);
  }
  if (typeof raw === 'string') {
    const ms = new Date(raw).getTime();
    return isNaN(ms) ? Math.floor(Date.now() / 1000) : Math.floor(ms / 1000);
  }
  if (raw !== null && typeof raw === 'object') {
    // Fallback: stringify and try to parse (handles Java date structs like {year,month,…})
    const ms = new Date(String(raw)).getTime();
    return isNaN(ms) ? Math.floor(Date.now() / 1000) : Math.floor(ms / 1000);
  }
  return Math.floor(Date.now() / 1000);
}

function resolveBarTime(update: PriceUpdate, timeframe: string): Time {
  const priceTs = update.timestamp ? Math.floor(new Date(update.timestamp).getTime() / 1000) : Math.floor(Date.now() / 1000);
  const periodSec = timeframeToSeconds(timeframe);
  return (Math.floor(priceTs / periodSec) * periodSec) as Time;
}

function mergeLivePrice(previous: CandlePoint | null, update: PriceUpdate, timeframe: string): CandlePoint {
  const barTime = resolveBarTime(update, timeframe);
  const price = update.price;

  if (!previous || previous.time < barTime) {
    return { time: barTime, open: price, high: price, low: price, close: price };
  }

  return {
    time: barTime,
    open: previous.open,
    high: Math.max(previous.high, price),
    low: Math.min(previous.low, price),
    close: price,
  };
}

export default function Chart({ instrument, timeframe, timezone, theme, snapshot, livePrice }: Props) {
  const dark = theme === 'dark';
  const pricePaneRef = useRef<HTMLDivElement>(null);
  const priceContainerRef = useRef<HTMLDivElement>(null);
  const wtContainerRef    = useRef<HTMLDivElement>(null);

  const chartRef     = useRef<IChartApi | null>(null);
  const wtChartRef   = useRef<IChartApi | null>(null);
  const candleRef    = useRef<CandleSeries | null>(null);
  const ema9Ref      = useRef<Line | null>(null);
  const ema50Ref     = useRef<Line | null>(null);
  const ema200Ref    = useRef<Line | null>(null);
  const bbUpperRef   = useRef<Line | null>(null);
  const bbLowerRef   = useRef<Line | null>(null);
  const wt1Ref       = useRef<Line | null>(null);
  const wt2Ref       = useRef<Line | null>(null);
  const wtDiffRef    = useRef<Histogram | null>(null);
  const lastCandleRef = useRef<CandlePoint | null>(null);
  // SMC overlays — tracked for cleanup between snapshot updates
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const smcLinesRef    = useRef<any[]>([]);                        // Strong/Weak H/L price lines
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const mtfLinesRef    = useRef<any[]>([]);                        // UC-SMC-005: D/W/M price lines
  const smcCanvasRef   = useRef<HTMLCanvasElement | null>(null);   // OB + FVG overlay canvas
  const drawSMCRef     = useRef<(() => void) | null>(null);        // current draw function
  const smcRedrawRaf1Ref = useRef<number | null>(null);
  const smcRedrawRaf2Ref = useRef<number | null>(null);
  const smcRedrawTimeoutRef = useRef<number | null>(null);
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  const markersApiRef  = useRef<any>(null);                        // createSeriesMarkers handle
  const livePriceRef = useRef<PriceUpdate | undefined>(livePrice);

  // ── Indicator visibility toggles ────────────────────────────────────────────
  const [vis, setVis]         = useState({ ema9: true, ema50: true, ema200: true, bb: true, wt: true, smc: false });
  // UC-SMC-005: MTF levels toggle
  const [showMtf, setShowMtf] = useState(false);
  // UC-SMC-007: candle coloring by SMC trend
  const [smcCandleColor, setSmcCandleColor] = useState(false);
  // UC-SMC-006: SMC render mode controls
  const [smcRenderMode, setSmcRenderMode] = useState<'historical' | 'present'>('present');
  const [smcColorMode,  setSmcColorMode]  = useState<'colored' | 'monochrome'>('colored');
  // lastBarTime as state so the SMC effect re-fires after candles load
  const [lastBarTime, setLastBarTime] = useState<Time>(0 as Time);

  // Reset visibility + lastBarTime when chart is recreated (instrument / timeframe change)
  useEffect(() => {
    setVis(prev => ({ ema9: true, ema50: true, ema200: true, bb: true, wt: true, smc: prev.smc }));
    setShowMtf(false);
    setSmcCandleColor(false);
    setSmcRenderMode('present');
    setLastBarTime(0 as Time);
    lastCandleRef.current = null;
  }, [instrument, timeframe]);

  useEffect(() => {
    livePriceRef.current = livePrice;
  }, [livePrice]);

  const cancelScheduledSmcRedraw = useCallback(() => {
    if (smcRedrawRaf1Ref.current != null) {
      window.cancelAnimationFrame(smcRedrawRaf1Ref.current);
      smcRedrawRaf1Ref.current = null;
    }
    if (smcRedrawRaf2Ref.current != null) {
      window.cancelAnimationFrame(smcRedrawRaf2Ref.current);
      smcRedrawRaf2Ref.current = null;
    }
    if (smcRedrawTimeoutRef.current != null) {
      window.clearTimeout(smcRedrawTimeoutRef.current);
      smcRedrawTimeoutRef.current = null;
    }
  }, []);

  const scheduleSmcRedraw = useCallback(() => {
    cancelScheduledSmcRedraw();
    smcRedrawRaf1Ref.current = window.requestAnimationFrame(() => {
      smcRedrawRaf1Ref.current = null;
      smcRedrawRaf2Ref.current = window.requestAnimationFrame(() => {
        smcRedrawRaf2Ref.current = null;
        drawSMCRef.current?.();
      });
    });
    smcRedrawTimeoutRef.current = window.setTimeout(() => {
      smcRedrawTimeoutRef.current = null;
      drawSMCRef.current?.();
    }, 120);
  }, [cancelScheduledSmcRedraw]);

  const reloadSeries = useCallback(async (fitContent = false) => {
    const candleSeries = candleRef.current;
    const ema9Series = ema9Ref.current;
    const ema50Series = ema50Ref.current;
    const ema200Series = ema200Ref.current;
    const bbUpperSeries = bbUpperRef.current;
    const bbLowerSeries = bbLowerRef.current;
    const wt1Series = wt1Ref.current;
    const wt2Series = wt2Ref.current;
    const wtDiffSeries = wtDiffRef.current;
    const priceChart = chartRef.current;
    const wtChart = wtChartRef.current;

    if (
      !candleSeries || !ema9Series || !ema50Series || !ema200Series ||
      !bbUpperSeries || !bbLowerSeries || !wt1Series || !wt2Series ||
      !wtDiffSeries || !priceChart || !wtChart
    ) {
      return;
    }

    try {
      const [candles, indicatorSeries] = await Promise.all([
        api.getCandles(instrument, timeframe, 500),
        api.getIndicatorSeries(instrument, timeframe, 500),
      ]);

      if (candles.length === 0) return;

      const candleData = candles
        .map(c => ({
          time: toUnixSeconds(c.time) as Time,
          open: c.open,
          high: c.high,
          low: c.low,
          close: c.close,
        }))
        .sort((a, b) => (a.time as number) - (b.time as number))
        // Remove duplicate timestamps — keep last occurrence (most recent data wins)
        .filter((item, idx, arr) => idx === arr.length - 1 || item.time !== arr[idx + 1].time);

      candleSeries.setData(candleData);
      lastCandleRef.current = candleData[candleData.length - 1];
      setLastBarTime(candleData[candleData.length - 1].time);

      const latestLive = livePriceRef.current;
      if (latestLive) {
        const mergedLiveCandle = mergeLivePrice(lastCandleRef.current, latestLive, timeframe);
        candleSeries.update(mergedLiveCandle);
        lastCandleRef.current = mergedLiveCandle;
        setLastBarTime(mergedLiveCandle.time);
      }

      // Helper: normalise + sort + dedup a line-point array
      const normLine = <T extends { time: unknown }>(
        arr: T[],
      ): (Omit<T, 'time'> & { time: Time })[] =>
        arr
          .map(p => ({ ...p, time: toUnixSeconds(p.time) as Time }))
          .sort((a, b) => (a.time as number) - (b.time as number))
          .filter((p, i, a) => i === a.length - 1 || p.time !== a[i + 1].time);

      ema9Series.setData(normLine(indicatorSeries.ema9).map(p => ({ time: p.time, value: p.value })));
      ema50Series.setData(normLine(indicatorSeries.ema50).map(p => ({ time: p.time, value: p.value })));
      ema200Series.setData(normLine(indicatorSeries.ema200).map(p => ({ time: p.time, value: p.value })));
      bbUpperSeries.setData(normLine(indicatorSeries.bollingerBands).map(p => ({ time: p.time, value: p.upper })));
      bbLowerSeries.setData(normLine(indicatorSeries.bollingerBands).map(p => ({ time: p.time, value: p.lower })));

      const wtNorm = normLine(indicatorSeries.waveTrend);
      wt1Series.setData(wtNorm.map(p => ({ time: p.time, value: p.wt1 })));
      wt2Series.setData(wtNorm.map(p => ({ time: p.time, value: p.wt2 })));
      wtDiffSeries.setData(wtNorm.map(p => ({
        time: p.time,
        value: p.diff,
        color: p.diff >= 0 ? '#22c55e60' : '#ef444460',
      })));

      if (fitContent) {
        priceChart.timeScale().fitContent();
        wtChart.timeScale().fitContent();
      }
    } catch {
      // backend unavailable
    }
  }, [instrument, timeframe]);

  const toggle = (key: keyof typeof vis) => {
    setVis(prev => {
      const next = { ...prev, [key]: !prev[key] };
      switch (key) {
        case 'ema9':  ema9Ref.current?.applyOptions({ visible: next.ema9 }); break;
        case 'ema50': ema50Ref.current?.applyOptions({ visible: next.ema50 }); break;
        case 'ema200': ema200Ref.current?.applyOptions({ visible: next.ema200 }); break;
        case 'bb':
          bbUpperRef.current?.applyOptions({ visible: next.bb });
          bbLowerRef.current?.applyOptions({ visible: next.bb });
          break;
        // 'wt' is handled via CSS on the container
        // 'smc' triggers re-render of price lines via snapshot useEffect
      }
      return next;
    });
  };

  // ── Mount / destroy charts ──────────────────────────────────────────────────
  useEffect(() => {
    if (!priceContainerRef.current || !wtContainerRef.current) return;

    // Theme palette for canvas rendering
    const C = dark
      ? { bg: '#111111', bgWt: '#0d0d0d', grid: '#1f2937', border: '#374151', text: '#9ca3af', wtGrid: '#161616', wtText: '#6b7280' }
      : { bg: '#ffffff', bgWt: '#f8fafc', grid: '#e5e7eb', border: '#d1d5db', text: '#374151', wtGrid: '#e5e7eb', wtText: '#6b7280' };

    // ── Price chart ───────────────────────────────────────────────────────────
    const priceChart = createChart(priceContainerRef.current, {
      layout: { background: { type: ColorType.Solid, color: C.bg }, textColor: C.text },
      grid:   { vertLines: { color: C.grid }, horzLines: { color: C.grid } },
      crosshair:       { mode: CrosshairMode.Normal },
      rightPriceScale: { borderColor: C.border },
      timeScale:       {
        borderColor: C.border,
        timeVisible: true,
        tickMarkFormatter: makeChartTickFormatter(timezone),
      },
      localization:    { timeFormatter: makeChartTimeFormatter(timezone) },
      width:  priceContainerRef.current.clientWidth,
      height: 300,
    });

    const candleSeries  = priceChart.addSeries(CandlestickSeries, {
      upColor: '#22c55e', downColor: '#ef4444',
      borderUpColor: '#22c55e', borderDownColor: '#ef4444',
      wickUpColor: '#22c55e',   wickDownColor: '#ef4444',
      priceLineVisible: false,
    });
    const ema9Series    = priceChart.addSeries(LineSeries, { color: '#22c55e', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
    const ema50Series   = priceChart.addSeries(LineSeries, { color: '#ef4444', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
    const ema200Series  = priceChart.addSeries(LineSeries, { color: '#3b82f6', lineWidth: 1, priceLineVisible: false, lastValueVisible: false });
    const bbUpperSeries = priceChart.addSeries(LineSeries, { color: '#6b7280', lineWidth: 1, lineStyle: LineStyle.Dotted, priceLineVisible: false, lastValueVisible: false });
    const bbLowerSeries = priceChart.addSeries(LineSeries, { color: '#6b7280', lineWidth: 1, lineStyle: LineStyle.Dotted, priceLineVisible: false, lastValueVisible: false });

    // Force-disable price lines and last-value labels via applyOptions (more reliable than constructor options)
    [ema9Series, ema50Series, ema200Series, bbUpperSeries, bbLowerSeries].forEach(s =>
      s.applyOptions({ priceLineVisible: false, lastValueVisible: false })
    );

    chartRef.current    = priceChart;
    candleRef.current   = candleSeries;
    ema9Ref.current     = ema9Series;
    ema50Ref.current    = ema50Series;
    ema200Ref.current   = ema200Series;
    bbUpperRef.current  = bbUpperSeries;
    bbLowerRef.current  = bbLowerSeries;

    // ── WaveTrend oscillator chart ─────────────────────────────────────────────
    const wtChart = createChart(wtContainerRef.current, {
      layout: {
        background: { type: ColorType.Solid, color: C.bgWt },
        textColor: C.wtText,
        fontSize: 10,
      },
      grid:   { vertLines: { color: C.wtGrid }, horzLines: { color: C.wtGrid } },
      crosshair:       { mode: CrosshairMode.Normal },
      rightPriceScale: { borderColor: C.border, scaleMargins: { top: 0.1, bottom: 0.1 } },
      timeScale:       {
        borderColor: C.border,
        timeVisible: false,
        visible: false,
        tickMarkFormatter: makeChartTickFormatter(timezone),
      },
      localization:    { timeFormatter: makeChartTimeFormatter(timezone) },
      width:  wtContainerRef.current.clientWidth,
      height: 110,
    });

    // Histogram (WT1 − WT2 diff bars) — drawn first so lines render on top
    const wtDiffSeries = wtChart.addSeries(HistogramSeries, {
      color: '#22c55e',
      priceScaleId: 'right',
      priceFormat: { type: 'price', precision: 1, minMove: 0.1 },
    });
    // WT1 (teal) — main wave line
    const wt1Series = wtChart.addSeries(LineSeries, {
      color: '#2dd4bf',
      lineWidth: 2,
      priceFormat: { type: 'price', precision: 1, minMove: 0.1 },
    });
    // WT2 (red dotted) — signal line
    const wt2Series = wtChart.addSeries(LineSeries, {
      color: '#f87171',
      lineWidth: 1,
      lineStyle: LineStyle.Dotted,
      priceFormat: { type: 'price', precision: 1, minMove: 0.1 },
    });

    // Horizontal reference lines: WT_X OB +53, OS -53, zero
    wt1Series.createPriceLine({ price: WT_OVERBOUGHT, color: '#ef444450', lineWidth: 1, lineStyle: LineStyle.Dotted, axisLabelVisible: true,  title: 'OB' });
    wt1Series.createPriceLine({ price: WT_OVERSOLD, color: '#22c55e50', lineWidth: 1, lineStyle: LineStyle.Dotted, axisLabelVisible: true,  title: 'OS' });
    wt1Series.createPriceLine({ price:   0, color: '#6b728050', lineWidth: 1, lineStyle: LineStyle.Solid,  axisLabelVisible: false, title: '' });

    wtChartRef.current = wtChart;
    wt1Ref.current     = wt1Series;
    wt2Ref.current     = wt2Series;
    wtDiffRef.current  = wtDiffSeries;

    // ── Fetch candles + backend-computed indicator series ─────────────────────
    void reloadSeries(true);

    // ── Sync scroll / zoom between price chart and WT chart ───────────────────
    let syncing = false;
    priceChart.timeScale().subscribeVisibleLogicalRangeChange(range => {
      if (syncing || !range) return;
      syncing = true;
      wtChart.timeScale().setVisibleLogicalRange(range);
      syncing = false;
    });
    wtChart.timeScale().subscribeVisibleLogicalRangeChange(range => {
      if (syncing || !range) return;
      syncing = true;
      priceChart.timeScale().setVisibleLogicalRange(range);
      syncing = false;
    });

    // ── SMC canvas — subscribe to range change so boxes redraw on scroll/zoom ──
    priceChart.timeScale().subscribeVisibleLogicalRangeChange(() => {
      scheduleSmcRedraw();
    });

    // ── Resize observer ────────────────────────────────────────────────────────
    const observer = new ResizeObserver(() => {
      if (priceContainerRef.current)
        priceChart.applyOptions({ width: priceContainerRef.current.clientWidth });
      if (wtContainerRef.current)
        wtChart.applyOptions({ width: wtContainerRef.current.clientWidth });
      scheduleSmcRedraw();
    });
    observer.observe(priceContainerRef.current);

    return () => {
      observer.disconnect();
      priceChart.remove();
      wtChart.remove();
      chartRef.current      = null;
      wtChartRef.current    = null;
      smcLinesRef.current   = [];
      mtfLinesRef.current   = [];
      drawSMCRef.current    = null;
      markersApiRef.current = null;
      cancelScheduledSmcRedraw();
    };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [instrument, timeframe, theme, reloadSeries, scheduleSmcRedraw, cancelScheduledSmcRedraw]);

  useEffect(() => {
    const id = window.setInterval(() => {
      void reloadSeries(false);
    }, 60_000);
    return () => window.clearInterval(id);
  }, [reloadSeries]);

  // ── Re-apply timezone formatter when timezone prop changes ─────────────────
  useEffect(() => {
    const localization = { timeFormatter: makeChartTimeFormatter(timezone) };
    const timeScale = { tickMarkFormatter: makeChartTickFormatter(timezone) };
    chartRef.current?.applyOptions({ localization, timeScale });
    wtChartRef.current?.applyOptions({ localization, timeScale });
  }, [timezone]);

  // ── UC-SMC-007: SMC candle coloring ──────────────────────────────────────────
  useEffect(() => {
    const cs = candleRef.current;
    if (!cs) return;
    if (!smcCandleColor || !snapshot) {
      // Restore default candle colors
      cs.applyOptions({
        upColor: '#22c55e', downColor: '#ef4444',
        borderUpColor: '#22c55e', borderDownColor: '#ef4444',
        wickUpColor: '#22c55e', wickDownColor: '#ef4444',
      });
      return;
    }
    // Derive active bias: prefer swing (longer-term), fallback to internal
    const bias = snapshot.swingBias ?? snapshot.internalBias ?? null;
    if (bias === 'BULLISH') {
      cs.applyOptions({
        upColor: '#26a69a', downColor: '#4db6ac',
        borderUpColor: '#26a69a', borderDownColor: '#4db6ac',
        wickUpColor: '#26a69a', wickDownColor: '#4db6ac',
      });
    } else if (bias === 'BEARISH') {
      cs.applyOptions({
        upColor: '#ef5350', downColor: '#e57373',
        borderUpColor: '#ef5350', borderDownColor: '#e57373',
        wickUpColor: '#ef5350', wickDownColor: '#e57373',
      });
    }
  }, [smcCandleColor, snapshot]);

  // ── SMC overlays — re-render whenever snapshot, visibility or render mode changes ──
  useEffect(() => {
    const series = candleRef.current;
    const chart  = chartRef.current;
    if (!series) return;

    // ── Cleanup previous price lines + markers ────────────────────────────
    smcLinesRef.current.forEach(pl => { try { series.removePriceLine(pl); } catch {} });
    smcLinesRef.current = [];
    markersApiRef.current?.setMarkers([]);

    // ── Always register the canvas draw fn (even if SMC is off, to clear) ─
    const canvas = smcCanvasRef.current;
    drawSMCRef.current = () => {
      if (!canvas) return;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;

      const host = pricePaneRef.current;
      const dpr = window.devicePixelRatio || 1;
      const cssWidth = host?.clientWidth ?? 0;
      const cssHeight = host?.clientHeight ?? 0;
      const targetWidth = Math.round(cssWidth * dpr);
      const targetHeight = Math.round(cssHeight * dpr);
      if (targetWidth <= 0 || targetHeight <= 0) return;
      if (canvas.width !== targetWidth || canvas.height !== targetHeight) {
        canvas.width = targetWidth;
        canvas.height = targetHeight;
        canvas.style.width = `${cssWidth}px`;
        canvas.style.height = `${cssHeight}px`;
      }

      ctx.clearRect(0, 0, canvas.width, canvas.height);
      if (!snapshot || !vis.smc || !chart) return;

      const t2  = lastBarTime;
      const W   = canvas.width;
      const H   = canvas.height;   // clamp y to canvas bounds
      const periodSec = timeframeToSeconds(timeframe);

      // UC-SMC-006: monochrome palette flag
      const mono = smcColorMode === 'monochrome';

      const drawBox = (
        t1: Time, low: number, high: number,
        fill: string, stroke: string,
        fillMono: string, strokeMono: string,
        label?: string,
        tEnd?: Time,  // UC-SMC-010: optional visual extension end time
      ) => {
        if (!t2) return;
        const x1 = chart.timeScale().timeToCoordinate(t1);
        const x2 = chart.timeScale().timeToCoordinate(tEnd ?? t2);
        const y1 = series.priceToCoordinate(low);
        const y2 = series.priceToCoordinate(high);
        if (x1 == null || x2 == null || y1 == null || y2 == null) return;
        const rawLx = Math.min(x1, x2) * dpr;
        const rawRx = Math.max(x1, x2) * dpr;
        const lx = Math.max(0, Math.min(rawLx, W));
        const rx = Math.max(0, Math.min(rawRx, W));
        // clamp y to canvas — priceToCoordinate can return huge values for off-screen prices
        const ty = Math.max(0, Math.min(Math.min(y1, y2) * dpr, H));
        const by = Math.max(0, Math.min(Math.max(y1, y2) * dpr, H));
        if (rx - lx < 1 || by - ty < 1) return;     // skip degenerate boxes
        ctx.fillStyle   = mono ? fillMono   : fill;
        ctx.fillRect(lx, ty, rx - lx, by - ty);
        ctx.strokeStyle = mono ? strokeMono : stroke;
        ctx.lineWidth = dpr;
        ctx.strokeRect(lx, ty, rx - lx, by - ty);
        // Label in the box if there's room
        if (label && by - ty >= 10 * dpr) {
          ctx.fillStyle = mono ? strokeMono : stroke;
          ctx.font = `${9 * dpr}px monospace`;
          ctx.fillText(label, lx + 3 * dpr, ty + 9 * dpr);
        }
      };

      // UC-SMC-006: Present mode limits to most recent 3 elements.
      // Order blocks come back newest-first from the backend; FVGs are not guaranteed,
      // so normalize both before selecting the "present" subset.
      const PRESENT_LIMIT = 3;
      const orderBlocks = [...(snapshot.activeOrderBlocks ?? [])]
        .sort((a, b) => Number(b.startTime) - Number(a.startTime));
      const currentBreakerPrice = livePriceRef.current?.price ?? lastCandleRef.current?.close ?? null;
      const breakerBlocks = relevantBreakerBlocks(snapshot, currentBreakerPrice);
      const fairValueGaps = [...(snapshot.activeFairValueGaps ?? [])]
        .sort((a, b) => Number(b.startTime) - Number(a.startTime));
      const obsVisible = (smcRenderMode === 'present'
        ? orderBlocks.slice(0, PRESENT_LIMIT)
        : orderBlocks
      ).sort((a, b) => Number(a.startTime) - Number(b.startTime));
      const breakerVisible = (smcRenderMode === 'present'
        ? breakerBlocks.slice(0, PRESENT_LIMIT)
        : breakerBlocks
      ).sort((a, b) => breakerReferenceTime(a) - breakerReferenceTime(b));
      const fvgsVisible = (smcRenderMode === 'present'
        ? fairValueGaps.slice(0, PRESENT_LIMIT)
        : fairValueGaps
      ).sort((a, b) => Number(a.startTime) - Number(b.startTime));

      // Order Block boxes  (blue=bull, red=bear — colored; gray — monochrome)
      for (const ob of obsVisible) {
        if (!ob.startTime) continue;
        const bull = ob.type === 'BULLISH';
        const obStart = smcRenderMode === 'present'
          ? (Math.max(Number(ob.startTime), Number(t2) - periodSec * PRESENT_OB_WINDOW_BARS) as Time)
          : (ob.startTime as Time);
        drawBox(obStart, ob.low, ob.high,
          bull ? 'rgba(49,121,245,0.35)' : 'rgba(247,70,80,0.35)',
          bull ? 'rgba(100,160,255,1)'   : 'rgba(255,100,100,1)',
          'rgba(150,150,170,0.25)', 'rgba(180,180,200,0.9)',
          bull ? 'OB ▲' : 'OB ▼');
      }
      // Breaker Block boxes (V2)
      for (const ob of breakerVisible) {
        if (!ob.startTime) continue;
        const bull = ob.type === 'BULLISH';
        const breakerStart = ob.breakerTime ?? ob.startTime;
        const obStart = smcRenderMode === 'present'
          ? (Math.max(Number(breakerStart), Number(t2) - periodSec * PRESENT_OB_WINDOW_BARS) as Time)
          : (breakerStart as Time);
        drawBox(obStart, ob.low, ob.high,
          bull ? 'rgba(45,212,191,0.24)' : 'rgba(251,146,60,0.24)',
          bull ? 'rgba(94,234,212,0.95)' : 'rgba(253,186,116,0.95)',
          'rgba(150,150,170,0.20)', 'rgba(180,180,200,0.8)',
          bull ? 'BB ▲' : 'BB ▼');
      }
      // Fair Value Gap boxes  (teal=bull, orange=bear — colored; gray — monochrome)
      for (const fvg of fvgsVisible) {
        if (!fvg.startTime) continue;
        const bull = fvg.bias === 'BULLISH';
        // Only use extensionEndTime when strictly after startTime (extensionBars > 0)
        const tEnd = (fvg.extensionEndTime && fvg.extensionEndTime > fvg.startTime)
          ? (fvg.extensionEndTime as Time) : undefined;
        drawBox(fvg.startTime as Time, fvg.bottom, fvg.top,
          bull ? 'rgba(0,204,136,0.28)' : 'rgba(255,100,50,0.28)',
          bull ? 'rgba(50,230,160,1)'   : 'rgba(255,130,80,1)',
          'rgba(140,140,160,0.20)', 'rgba(160,160,180,0.85)',
          bull ? 'FVG ▲' : 'FVG ▼',
          tEnd);
      }
    };
    scheduleSmcRedraw();

    if (!snapshot || !vis.smc) return;

    // ── Strong / Weak High + Low — horizontal dashed price lines ─────────
    const addLine = (
      price: number | null,
      color: string,
      style: LineStyle,
      title: string,
      axisLabelVisible = true,
    ) => {
      if (price == null) return;
      smcLinesRef.current.push(series.createPriceLine({
        price, color, lineWidth: 1, lineStyle: style,
        axisLabelVisible, title,
      }));
    };
    addLine(snapshot.strongHigh, '#ef4444cc', LineStyle.Dashed, 'Strong H');
    addLine(snapshot.strongLow,  '#22c55ecc', LineStyle.Dashed, 'Strong L');
    addLine(snapshot.weakHigh,   '#f97316aa', LineStyle.Dotted, 'Weak H');
    addLine(snapshot.weakLow,    '#3b82f6aa', LineStyle.Dotted, 'Weak L');

    // EQH / EQL — show only the two nearest liquidity labels, keep the rest unlabeled
    const liquidityLevels = [
      ...(snapshot.equalHighs ?? []).map(eq => ({ key: `EQH:${eq.price}:${eq.lastBarTime}`, title: 'EQH', price: eq.price })),
      ...(snapshot.equalLows ?? []).map(eq => ({ key: `EQL:${eq.price}:${eq.lastBarTime}`, title: 'EQL', price: eq.price })),
    ];
    const referencePrice = lastCandleRef.current?.close ?? null;
    const labeledLiquidity = new Set(
      liquidityLevels
        .slice()
        .sort((a, b) => {
          if (referencePrice == null) return 0;
          return Math.abs(a.price - referencePrice) - Math.abs(b.price - referencePrice);
        })
        .slice(0, 2)
        .map(level => level.key)
    );
    for (const eq of snapshot.equalHighs ?? []) {
      const key = `EQH:${eq.price}:${eq.lastBarTime}`;
      addLine(eq.price, '#ef4444aa', LineStyle.SparseDotted, labeledLiquidity.has(key) ? 'EQH' : '', labeledLiquidity.has(key));
    }
    for (const eq of snapshot.equalLows ?? []) {
      const key = `EQL:${eq.price}:${eq.lastBarTime}`;
      addLine(eq.price, '#22c55eaa', LineStyle.SparseDotted, labeledLiquidity.has(key) ? 'EQL' : '', labeledLiquidity.has(key));
    }

    // Premium / Discount / Equilibrium zone lines
    addLine(snapshot.premiumZoneTop, '#ef444480', LineStyle.Dashed, 'Premium');
    addLine(snapshot.equilibriumLevel, '#a78bfa99', LineStyle.Solid, 'EQ 50%');
    addLine(snapshot.discountZoneBottom, '#22c55e80', LineStyle.Dashed, 'Discount');

    // ── BOS / CHoCH arrow markers ─────────────────────────────────────────
    const markers: SeriesMarker<Time>[] = [...(snapshot.recentBreaks ?? [])]
      .sort((a, b) => a.barTime - b.barTime)
      .map(b => ({
        time:     b.barTime as Time,
        position: (b.trend === 'BULLISH' ? 'belowBar' : 'aboveBar') as 'belowBar' | 'aboveBar',
        color:    b.trend === 'BULLISH' ? '#22c55e' : '#ef4444',
        shape:    (b.trend === 'BULLISH' ? 'arrowUp' : 'arrowDown') as 'arrowUp' | 'arrowDown',
        text:     b.type === 'CHOCH' ? 'CHoCH' : 'BOS',
        size:     1,
      }));

    if (markersApiRef.current) {
      markersApiRef.current.setMarkers(markers);
    } else {
      markersApiRef.current = createSeriesMarkers(series, markers);
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [snapshot, vis.smc, lastBarTime, smcRenderMode, smcColorMode, scheduleSmcRedraw]);

  // ── UC-SMC-005: MTF level price lines ────────────────────────────────────────
  useEffect(() => {
    const series = candleRef.current;
    if (!series) return;

    // Remove previous MTF lines
    mtfLinesRef.current.forEach(pl => { try { series.removePriceLine(pl); } catch {} });
    mtfLinesRef.current = [];

    if (!showMtf || !snapshot?.mtfLevels) return;

    const addMtf = (price: number | null | undefined, color: string, style: LineStyle, title: string) => {
      if (price == null) return;
      mtfLinesRef.current.push(series.createPriceLine({
        price, color, lineWidth: 1, lineStyle: style,
        axisLabelVisible: true, title,
      }));
    };

    const { daily, weekly, monthly } = snapshot.mtfLevels;
    // Daily — solid bright lines
    if (daily) {
      addMtf(daily.high,   '#f59e0b', LineStyle.Solid,  'D H');
      addMtf(daily.low,    '#f59e0b', LineStyle.Solid,  'D L');
      addMtf(daily.open,   '#fbbf24', LineStyle.Dotted, 'D O');
      addMtf(daily.close,  '#fbbf2480', LineStyle.Dotted, 'D C');
    }
    // Weekly — dashed purple lines
    if (weekly) {
      addMtf(weekly.high,  '#a78bfa', LineStyle.Dashed, 'W H');
      addMtf(weekly.low,   '#a78bfa', LineStyle.Dashed, 'W L');
      addMtf(weekly.open,  '#c4b5fd', LineStyle.Dotted, 'W O');
    }
    // Monthly — sparse-dotted teal lines
    if (monthly) {
      addMtf(monthly.high, '#2dd4bf', LineStyle.SparseDotted, 'M H');
      addMtf(monthly.low,  '#2dd4bf', LineStyle.SparseDotted, 'M L');
      addMtf(monthly.open, '#5eead4', LineStyle.SparseDotted, 'M O');
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [snapshot, showMtf]);

  // ── Append live tick to current candle bar ──────────────────────────────────
  useEffect(() => {
    if (!livePrice || !candleRef.current) return;
    const updated = mergeLivePrice(lastCandleRef.current, livePrice, timeframe);
    // lightweight-charts throws if we update() with a time older than the last bar
    if (lastCandleRef.current && (updated.time as number) < (lastCandleRef.current.time as number)) return;
    lastCandleRef.current = updated;
    setLastBarTime(updated.time);
    candleRef.current.update(updated);
    scheduleSmcRedraw();
  }, [livePrice, timeframe, reloadSeries, scheduleSmcRedraw]);

  return (
    <div className={`relative rounded-lg overflow-hidden border ${dark ? 'bg-[#111] border-zinc-800' : 'bg-white border-slate-200'}`}>
      {/* ── Price chart ── */}
      <div ref={pricePaneRef} className="relative">
        <div ref={priceContainerRef} className="w-full" />
        {/* SMC zone boxes (OB + FVG) — overlay canvas above the chart canvas */}
        <canvas ref={smcCanvasRef} className="absolute inset-0 pointer-events-none" style={{ zIndex: 20 }} />

        {/* Overlay legend tags — click to toggle indicator */}
        {snapshot && (
          <div className="absolute top-2 left-3 flex flex-wrap gap-2 text-[10px] font-mono z-10">
            {snapshot.ema9   && <Tag color="green"  label={`EMA9 ${snapshot.ema9.toFixed(2)}`}   active={vis.ema9}   onClick={() => toggle('ema9')} />}
            {snapshot.ema50  && <Tag color="red"    label={`EMA50 ${snapshot.ema50.toFixed(2)}`}  active={vis.ema50}  onClick={() => toggle('ema50')} />}
            {snapshot.ema200 && <Tag color="blue"   label={`EMA200 ${snapshot.ema200.toFixed(2)}`} active={vis.ema200} onClick={() => toggle('ema200')} />}
            {snapshot.vwap   && <Tag color="purple" label={`VWAP ${snapshot.vwap.toFixed(2)}`} />}
            {snapshot.supertrendValue && (
              <Tag
                color={snapshot.supertrendBullish ? 'green' : 'red'}
                label={`ST ${snapshot.supertrendValue.toFixed(2)} ${snapshot.supertrendBullish ? '▲' : '▼'}`}
              />
            )}
            {snapshot.bbUpper && (
              <Tag color="gray" label={`BB ${snapshot.bbLower?.toFixed(2)}–${snapshot.bbUpper?.toFixed(2)}`} active={vis.bb} onClick={() => toggle('bb')} />
            )}
            {/* SMC toggle */}
            <Tag
              color={snapshot.marketStructureTrend === 'BULLISH' ? 'green' : snapshot.marketStructureTrend === 'BEARISH' ? 'red' : 'gray'}
              label={`SMC ${snapshot.swingBias ?? snapshot.internalBias ?? snapshot.marketStructureTrend}`}
              active={vis.smc}
              onClick={() => toggle('smc')}
            />
            {/* UC-SMC-005: MTF levels toggle */}
            {snapshot.mtfLevels && (
              <Tag color="amber" label="MTF" active={showMtf} onClick={() => setShowMtf(v => !v)} />
            )}
            {/* UC-SMC-007: candle color by SMC trend */}
            <Tag
              color="gray"
              label="C●"
              active={smcCandleColor}
              onClick={() => setSmcCandleColor(v => !v)}
            />
            {/* UC-SMC-006: render mode toggles (only visible when SMC is on) */}
            {vis.smc && (
              <>
                <Tag
                  color="gray"
                  label={smcRenderMode === 'historical' ? 'Hist' : 'Now'}
                  active
                  onClick={() => setSmcRenderMode(m => m === 'historical' ? 'present' : 'historical')}
                />
                <Tag
                  color="gray"
                  label={smcColorMode === 'colored' ? 'Color' : 'Mono'}
                  active
                  onClick={() => setSmcColorMode(m => m === 'colored' ? 'monochrome' : 'colored')}
                />
              </>
            )}
          </div>
        )}
      </div>

      {/* ── WaveTrend oscillator ── */}
      <div className={`relative border-t border-zinc-800/60 ${vis.wt ? '' : 'hidden'}`}>
        {/* WT legend */}
        <div className="absolute top-1 left-2 flex items-center gap-2.5 text-[9px] font-mono pointer-events-none z-10">
          <span className="flex items-center gap-1">
            <span className="inline-block w-3 h-0.5 bg-teal-400 rounded" />
            <span className="text-teal-400">WT1 {snapshot?.wtWt1?.toFixed(1) ?? '—'}</span>
          </span>
          <span className="flex items-center gap-1">
            <span className="inline-block w-3 h-0.5 bg-red-400 rounded" style={{ borderBottom: '2px dotted' }} />
            <span className="text-red-400">WT2 {snapshot?.wtWt2?.toFixed(1) ?? '—'}</span>
          </span>
          {snapshot?.wtSignal && snapshot.wtSignal !== 'NEUTRAL' && (
            <span className={`font-semibold ${snapshot.wtSignal === 'OVERBOUGHT' ? 'text-red-400' : 'text-emerald-400'}`}>
              {snapshot.wtSignal}
            </span>
          )}
          {snapshot?.wtCrossover && (
            <span className={`font-semibold ${snapshot.wtCrossover.includes('BULL') ? 'text-emerald-400' : 'text-red-400'}`}>
              {snapshot.wtCrossover.includes('BULL') ? '▲' : '▼'} {snapshot.wtCrossover.replace('_CROSS', '')}
            </span>
          )}
        </div>
        <div ref={wtContainerRef} className="w-full" />
      </div>
      {/* WT toggle button (always visible) */}
      <button
        onClick={() => toggle('wt')}
        className={`absolute bottom-1 right-2 text-[9px] font-mono px-1.5 py-0.5 rounded transition-opacity z-10 ${
          vis.wt
            ? 'bg-teal-900/60 text-teal-400'
            : 'bg-zinc-800/60 text-zinc-500 opacity-60'
        }`}
      >
        WT {vis.wt ? '▼' : '▶'}
      </button>
    </div>
  );
}

function Tag({ color, label, active = true, onClick }: {
  color: string; label: string; active?: boolean; onClick?: () => void;
}) {
  const cls: Record<string, string> = {
    amber:  'bg-amber-900/70 text-amber-300',
    blue:   'bg-blue-900/70 text-blue-300',
    purple: 'bg-purple-900/70 text-purple-300',
    green:  'bg-emerald-900/70 text-emerald-300',
    red:    'bg-red-900/70 text-red-300',
    gray:   'bg-zinc-800/80 text-zinc-400',
  };
  return (
    <button
      onClick={onClick}
      className={`px-1.5 py-0.5 rounded transition-opacity ${cls[color] ?? cls.gray} ${
        active ? '' : 'opacity-30 line-through'
      } ${onClick ? 'cursor-pointer hover:opacity-80' : 'cursor-default pointer-events-none'}`}
    >
      {label}
    </button>
  );
}
