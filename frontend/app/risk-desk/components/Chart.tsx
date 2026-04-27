'use client';

import {
  CandlestickData,
  ColorType,
  CrosshairMode,
  IChartApi,
  ISeriesApi,
  IPriceLine,
  LineStyle,
  PriceLineOptions,
  UTCTimestamp,
  createChart,
} from 'lightweight-charts';
import { CSSProperties, useEffect, useRef, useState } from 'react';
import { Candle, Position, Smc } from '../lib/data';

interface ChartProps {
  symbol: string;
  tf: string;
  candles: Candle[];
  ema9: number[];
  ema50: number[];
  ema200: number[];
  bbUpper: number[];
  bbLower: number[];
  bbBasis: number[];
  smc: Smc;
  activePosition: Position | null;
}

interface ChartToggles {
  ema: boolean;
  vwap: boolean;
  bb: boolean;
  fvg: boolean;
  volume: boolean;
  ob: boolean;
  structure: boolean;
  liquidity: boolean;
  position: boolean;
}

const DEFAULT_TOGGLES: ChartToggles = {
  ema: true,
  vwap: true,
  bb: false,
  fvg: true,
  volume: true,
  ob: true,
  structure: true,
  liquidity: true,
  position: true,
};

// Resolve a CSS custom property at runtime so we can pass real colors to
// lightweight-charts (which doesn't accept var(--…) strings).
function readVar(name: string, fallback: string): string {
  if (typeof window === 'undefined') return fallback;
  const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  return v || fallback;
}

// Compute cumulative session VWAP from candles, resetting at ET midnight.
// ET ≈ UTC−5 (we use a fixed offset — good enough for chart display).
function computeVwap(candles: Candle[]): { time: UTCTimestamp; value: number }[] {
  const ET_OFFSET_S = 5 * 3600;
  const out: { time: UTCTimestamp; value: number }[] = [];
  let cumTpV = 0;
  let cumV = 0;
  let curDay = -1;
  for (const c of candles) {
    const etDay = Math.floor((c.time - ET_OFFSET_S) / 86400);
    if (etDay !== curDay) {
      cumTpV = 0;
      cumV = 0;
      curDay = etDay;
    }
    const tp = (c.high + c.low + c.close) / 3;
    cumTpV += tp * c.volume;
    cumV += c.volume;
    out.push({ time: c.time as UTCTimestamp, value: cumV > 0 ? cumTpV / cumV : c.close });
  }
  return out;
}

export function LiveChart({
  symbol,
  tf,
  candles,
  ema9,
  ema50,
  ema200,
  bbUpper,
  bbLower,
  bbBasis,
  smc,
  activePosition,
}: ChartProps) {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const overlayRef = useRef<HTMLCanvasElement | null>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const ema9SeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const ema50SeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const ema200SeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const vwapSeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const bbUpperSeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const bbLowerSeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const bbBasisSeriesRef = useRef<ISeriesApi<'Line'> | null>(null);
  const volSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const priceLinesRef = useRef<IPriceLine[]>([]);

  const [toggles, setToggles] = useState<ChartToggles>(DEFAULT_TOGGLES);
  const togglesRef = useRef(toggles);
  togglesRef.current = toggles;
  const toggle = (k: keyof ChartToggles) => setToggles((t) => ({ ...t, [k]: !t[k] }));

  const drawOverlayRef = useRef<() => void>(() => undefined);

  const last = candles[candles.length - 1];

  const [initError, setInitError] = useState<string | null>(null);

  // ── Mount / unmount the chart ─────────────────────────────────
  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    if (container.clientWidth === 0 || container.clientHeight === 0) return;

    const upColor = readVar('--up', '#34d399');
    const downColor = readVar('--down', '#f87171');
    const ink2 = readVar('--ink-2', '#a1a1aa');
    const lineColor = readVar('--line', '#26262c');
    const s0 = readVar('--s0', '#09090b');

    let chart: IChartApi;
    try {
      chart = createChart(container, {
        width: container.clientWidth,
        height: container.clientHeight,
        layout: {
          background: { type: ColorType.Solid, color: s0 },
          textColor: ink2,
          fontFamily: 'var(--font-mono), ui-monospace, monospace',
          fontSize: 11,
        },
        grid: {
          vertLines: { color: lineColor, style: LineStyle.Dashed },
          horzLines: { color: lineColor, style: LineStyle.Dashed },
        },
        rightPriceScale: { borderColor: lineColor, scaleMargins: { top: 0.06, bottom: 0.28 } },
        timeScale: { borderColor: lineColor, timeVisible: true, secondsVisible: false },
        crosshair: { mode: CrosshairMode.Normal },
        handleScroll: true,
        handleScale: true,
      });
    } catch (err) {
      console.error('[RiskDesk] lightweight-charts init failed:', err);
      setInitError(String((err as Error)?.message || err));
      return;
    }
    chartRef.current = chart;

    const candleSeries = chart.addCandlestickSeries({
      upColor,
      downColor,
      borderUpColor: upColor,
      borderDownColor: downColor,
      wickUpColor: upColor,
      wickDownColor: downColor,
    });
    candleSeriesRef.current = candleSeries;

    // EMA color scheme: EMA9=green, EMA50=red, EMA200=blue
    ema9SeriesRef.current = chart.addLineSeries({
      color: '#34d399',
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'EMA 9',
    });
    ema50SeriesRef.current = chart.addLineSeries({
      color: '#f87171',
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'EMA 50',
    });
    ema200SeriesRef.current = chart.addLineSeries({
      color: '#60a5fa',
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'EMA 200',
    });

    // VWAP — yellow dotted line, same style as TradingView default
    vwapSeriesRef.current = chart.addLineSeries({
      color: '#fbbf24',
      lineWidth: 2,
      lineStyle: LineStyle.Dotted,
      priceLineVisible: false,
      lastValueVisible: true,
      title: 'VWAP',
    });

    // Bollinger Bands — upper/lower dashed, basis solid (all purple-ish)
    bbUpperSeriesRef.current = chart.addLineSeries({
      color: '#a78bfa',
      lineWidth: 1,
      lineStyle: LineStyle.Dashed,
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'BB Upper',
    });
    bbLowerSeriesRef.current = chart.addLineSeries({
      color: '#a78bfa',
      lineWidth: 1,
      lineStyle: LineStyle.Dashed,
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'BB Lower',
    });
    bbBasisSeriesRef.current = chart.addLineSeries({
      color: '#a78bfa55',
      lineWidth: 1,
      priceLineVisible: false,
      lastValueVisible: false,
      title: 'BB Basis',
    });

    volSeriesRef.current = chart.addHistogramSeries({
      priceFormat: { type: 'volume' },
      priceScaleId: '',
      priceLineVisible: false,
      lastValueVisible: false,
    });
    chart
      .priceScale('')
      .applyOptions({ scaleMargins: { top: 0.78, bottom: 0 }, borderVisible: false });

    const ro = new ResizeObserver(() => {
      const w = container.clientWidth;
      const h = container.clientHeight;
      chart.applyOptions({ width: w, height: h });
      const cv = overlayRef.current;
      if (cv) {
        cv.width = w * window.devicePixelRatio;
        cv.height = h * window.devicePixelRatio;
        cv.style.width = `${w}px`;
        cv.style.height = `${h}px`;
        drawOverlayRef.current();
      }
    });
    ro.observe(container);

    chart.timeScale().subscribeVisibleLogicalRangeChange(() => drawOverlayRef.current());

    return () => {
      ro.disconnect();
      chart.remove();
      chartRef.current = null;
      candleSeriesRef.current = null;
      ema9SeriesRef.current = null;
      ema50SeriesRef.current = null;
      ema200SeriesRef.current = null;
      vwapSeriesRef.current = null;
      bbUpperSeriesRef.current = null;
      bbLowerSeriesRef.current = null;
      bbBasisSeriesRef.current = null;
      volSeriesRef.current = null;
      priceLinesRef.current = [];
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // ── Push candle / EMA / VWAP / BB / volume data ───────────────
  useEffect(() => {
    const cs = candleSeriesRef.current;
    const e9 = ema9SeriesRef.current;
    const e50 = ema50SeriesRef.current;
    const e200 = ema200SeriesRef.current;
    const vwap = vwapSeriesRef.current;
    const bbU = bbUpperSeriesRef.current;
    const bbL = bbLowerSeriesRef.current;
    const bbM = bbBasisSeriesRef.current;
    const vol = volSeriesRef.current;
    if (!cs || !e9 || !e50 || !e200 || !vwap || !bbU || !bbL || !bbM || !vol) return;
    if (!candles.length) return;

    const candleData: CandlestickData<UTCTimestamp>[] = candles.map((c) => ({
      time: c.time as UTCTimestamp,
      open: c.open,
      high: c.high,
      low: c.low,
      close: c.close,
    }));
    cs.setData(candleData);

    const lineData = (vals: number[]) =>
      vals
        .slice(0, candles.length)
        .map((v, i) => ({ time: candles[i].time as UTCTimestamp, value: v }))
        .filter((d) => Number.isFinite(d.value));

    e9.setData(toggles.ema ? lineData(ema9) : []);
    e50.setData(toggles.ema ? lineData(ema50) : []);
    e200.setData(toggles.ema ? lineData(ema200) : []);

    // VWAP computed client-side from candles; resets at ET midnight
    vwap.setData(toggles.vwap ? computeVwap(candles) : []);

    // Bollinger Bands from backend series
    bbU.setData(toggles.bb ? lineData(bbUpper) : []);
    bbL.setData(toggles.bb ? lineData(bbLower) : []);
    bbM.setData(toggles.bb ? lineData(bbBasis) : []);

    const upCol = readVar('--up', '#34d399');
    const dnCol = readVar('--down', '#f87171');
    vol.setData(
      toggles.volume
        ? candles.map((c) => ({
            time: c.time as UTCTimestamp,
            value: c.volume,
            color: c.close >= c.open ? upCol + '55' : dnCol + '55',
          }))
        : []
    );

    chartRef.current?.timeScale().fitContent();
    let raf2 = 0;
    const raf1 = requestAnimationFrame(() => {
      raf2 = requestAnimationFrame(() => drawOverlay());
    });
    return () => {
      cancelAnimationFrame(raf1);
      if (raf2) cancelAnimationFrame(raf2);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [candles, ema9, ema50, ema200, bbUpper, bbLower, bbBasis, toggles.ema, toggles.vwap, toggles.bb, toggles.volume]);

  // ── Price lines (entry / SL / TP / liquidity) ─────────────────
  useEffect(() => {
    const cs = candleSeriesRef.current;
    if (!cs) return;
    for (const pl of priceLinesRef.current) {
      try {
        cs.removePriceLine(pl);
      } catch {
        /* ignore */
      }
    }
    priceLinesRef.current = [];

    const add = (opts: Partial<PriceLineOptions> & { price: number; title: string; color: string }) => {
      const line = cs.createPriceLine({
        lineWidth: 1,
        lineStyle: LineStyle.Dashed,
        axisLabelVisible: true,
        ...opts,
      });
      priceLinesRef.current.push(line);
    };

    if (toggles.position && activePosition) {
      const p = activePosition;
      const ink1 = readVar('--ink-1', '#d4d4d8');
      const upCol = readVar('--up', '#34d399');
      const dnCol = readVar('--down', '#f87171');
      add({ price: p.entry, title: `ENTRY ×${p.qty}`, color: ink1, lineStyle: LineStyle.Solid });
      add({ price: p.sl, title: 'SL', color: dnCol });
      add({ price: p.tp1, title: 'TP1', color: upCol });
      add({ price: p.tp2, title: 'TP2', color: upCol });
    }

    if (toggles.liquidity) {
      const cyan = readVar('--cyan', '#22d3ee');
      for (const l of smc.liquidity) {
        add({ price: l.px, title: l.label, color: cyan, lineStyle: LineStyle.LargeDashed });
      }
    }
  }, [activePosition, smc.liquidity, toggles.position, toggles.liquidity]);

  // ── Canvas overlay: order blocks, FVGs, structure markers ─────
  const drawOverlay = () => {
    const chart = chartRef.current;
    const cs = candleSeriesRef.current;
    const cv = overlayRef.current;
    const container = containerRef.current;
    if (!chart || !cs || !cv || !container) return;
    const ctx = cv.getContext('2d');
    if (!ctx) return;
    const dpr = window.devicePixelRatio || 1;
    const w = container.clientWidth;
    const h = container.clientHeight;
    if (cv.width !== w * dpr || cv.height !== h * dpr) {
      cv.width = w * dpr;
      cv.height = h * dpr;
      cv.style.width = `${w}px`;
      cv.style.height = `${h}px`;
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);

    const t = togglesRef.current;
    const ts = chart.timeScale();
    const lastTime = (candles[candles.length - 1]?.time ?? 0) as UTCTimestamp;
    const xRight = ts.timeToCoordinate(lastTime);
    const upCol = readVar('--up', '#34d399');
    const dnCol = readVar('--down', '#f87171');
    const violet = readVar('--violet', '#a78bfa');

    // Order blocks
    if (t.ob) {
      for (const ob of smc.orderBlocks) {
        const x1 = ts.timeToCoordinate(ob.t1 as UTCTimestamp);
        const x2 = ob.t2 ? ts.timeToCoordinate(ob.t2 as UTCTimestamp) : xRight;
        const yh = cs.priceToCoordinate(ob.hi);
        const yl = cs.priceToCoordinate(ob.lo);
        if (x1 == null || yh == null || yl == null) continue;
        const right = Math.max(x1, x2 ?? xRight ?? w);
        const fill = ob.type === 'bull' ? upCol : dnCol;
        ctx.fillStyle = fill + (ob.mit ? '0d' : '20');
        ctx.fillRect(x1, yh, right - x1, yl - yh);
        ctx.strokeStyle = fill + '66';
        ctx.lineWidth = 0.7;
        ctx.setLineDash(ob.mit ? [3, 3] : []);
        ctx.strokeRect(x1, yh, right - x1, yl - yh);
        ctx.setLineDash([]);
        ctx.fillStyle = fill;
        ctx.font = '600 9px var(--font-mono)';
        ctx.fillText(ob.label + (ob.mit ? ' · mit' : ''), x1 + 4, yh - 3);
      }
    }

    // FVGs
    if (t.fvg) {
      for (const f of smc.fvgs) {
        const x1 = ts.timeToCoordinate(f.t1 as UTCTimestamp);
        const x2 = ts.timeToCoordinate(f.t2 as UTCTimestamp) ?? xRight;
        const yh = cs.priceToCoordinate(f.hi);
        const yl = cs.priceToCoordinate(f.lo);
        if (x1 == null || x2 == null || yh == null || yl == null) continue;
        ctx.fillStyle = violet + (f.filled ? '0a' : '1a');
        ctx.fillRect(x1, yh, x2 - x1, yl - yh);
        ctx.strokeStyle = violet + '73';
        ctx.lineWidth = 0.8;
        ctx.setLineDash([4, 3]);
        ctx.strokeRect(x1, yh, x2 - x1, yl - yh);
        ctx.setLineDash([]);
        ctx.fillStyle = violet;
        ctx.font = '600 9px var(--font-mono)';
        ctx.fillText(f.label + (f.filled ? ' · filled' : ''), x1 + 4, yl + 10);
      }
    }

    // Structure markers (BOS / CHoCH)
    if (t.structure) {
      for (const s of smc.structure) {
        const x1 = ts.timeToCoordinate(s.t as UTCTimestamp);
        const y = cs.priceToCoordinate(s.px);
        if (x1 == null || y == null) continue;
        const col = s.dir === 'up' ? upCol : dnCol;
        ctx.strokeStyle = col;
        ctx.lineWidth = 1.2;
        ctx.setLineDash([]);
        ctx.beginPath();
        ctx.moveTo(x1, y);
        ctx.lineTo(Math.min(w, x1 + 240), y);
        ctx.stroke();
        ctx.fillStyle = col;
        ctx.font = '700 9px var(--font-mono)';
        ctx.fillText(s.kind, x1 + 6, y - 4);
      }
    }
  };

  drawOverlayRef.current = drawOverlay;

  useEffect(() => {
    const id = requestAnimationFrame(() => drawOverlayRef.current());
    return () => cancelAnimationFrame(id);
  }, [toggles, smc, activePosition]);

  return (
    <div
      className="panel-flat"
      style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'hidden' }}
    >
      <ChartHeader symbol={symbol} tf={tf} last={last} toggles={toggles} onToggle={toggle} />
      <div
        ref={containerRef}
        style={{
          flex: 1,
          position: 'relative',
          background: 'var(--s0)',
          minHeight: 0,
          overflow: 'hidden',
        }}
      >
        {initError && (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              display: 'grid',
              placeItems: 'center',
              fontSize: 12,
              color: 'var(--down)',
              fontFamily: 'var(--font-mono)',
              padding: 16,
              textAlign: 'center',
            }}
          >
            Chart unavailable — {initError}
          </div>
        )}
        <canvas
          ref={overlayRef}
          style={{
            position: 'absolute',
            inset: 0,
            pointerEvents: 'none',
            zIndex: 2,
          }}
        />
      </div>
    </div>
  );
}

function ChartToggleBtn({
  on,
  label,
  kind,
  onClick,
}: {
  on: boolean;
  label: string;
  kind: 'accent' | 'violet' | 'info' | 'warn' | 'ghost' | 'yellow';
  onClick: () => void;
}) {
  const colorMap: Record<typeof kind, string> = {
    accent: 'var(--accent)',
    violet: 'var(--violet)',
    info: 'var(--info)',
    warn: 'var(--warn)',
    ghost: 'var(--ink-3)',
    yellow: '#fbbf24',
  };
  const bgMap: Record<typeof kind, string> = {
    accent: 'var(--accent-glow)',
    violet: 'color-mix(in oklch, var(--violet) 18%, transparent)',
    info: 'color-mix(in oklch, var(--info) 16%, transparent)',
    warn: 'color-mix(in oklch, var(--warn) 16%, transparent)',
    ghost: 'var(--s2)',
    yellow: 'color-mix(in oklch, #fbbf24 16%, transparent)',
  };
  const offBg = 'transparent';
  const offFg = 'var(--ink-3)';
  const offBorder = 'var(--line)';
  const style: CSSProperties = {
    display: 'inline-flex',
    alignItems: 'center',
    gap: 4,
    height: 18,
    padding: '0 6px',
    borderRadius: 3,
    fontSize: 10,
    fontWeight: 600,
    letterSpacing: '0.04em',
    textTransform: 'uppercase',
    background: on ? bgMap[kind] : offBg,
    color: on ? colorMap[kind] : offFg,
    border: `1px solid ${on ? `color-mix(in oklch, ${colorMap[kind]} 30%, transparent)` : offBorder}`,
    fontFamily: 'var(--font-mono)',
    cursor: 'pointer',
    whiteSpace: 'nowrap',
  };
  return (
    <button type="button" onClick={onClick} aria-pressed={on} style={style}>
      {label} · {on ? 'ON' : 'OFF'}
    </button>
  );
}

function ChartHeader({
  symbol,
  tf,
  last,
  toggles,
  onToggle,
}: {
  symbol: string;
  tf: string;
  last: Candle | undefined;
  toggles: ChartToggles;
  onToggle: (k: keyof ChartToggles) => void;
}) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        padding: '8px 16px',
        borderBottom: '1px solid var(--line)',
      }}
    >
      <span style={{ fontWeight: 700, fontSize: 14, color: 'var(--ink-0)' }}>{symbol}</span>
      <span
        style={{
          fontSize: 10,
          color: 'var(--ink-3)',
          letterSpacing: '0.06em',
          textTransform: 'uppercase',
        }}
      >
        · {tf} · CME
      </span>
      {last && (
        <div style={{ display: 'flex', gap: 14, marginLeft: 8, fontFamily: 'var(--font-mono)', fontSize: 11 }}>
          <span>
            <span className="muted" style={{ marginRight: 4 }}>O</span>
            <span>{last.open.toFixed(2)}</span>
          </span>
          <span>
            <span className="muted" style={{ marginRight: 4 }}>H</span>
            <span>{last.high.toFixed(2)}</span>
          </span>
          <span>
            <span className="muted" style={{ marginRight: 4 }}>L</span>
            <span>{last.low.toFixed(2)}</span>
          </span>
          <span>
            <span className="muted" style={{ marginRight: 4 }}>C</span>
            <span className={last.close >= last.open ? 'up' : 'down'}>{last.close.toFixed(2)}</span>
          </span>
          <span>
            <span className="muted" style={{ marginRight: 4 }}>V</span>
            <span>{(last.volume / 1000).toFixed(1)}K</span>
          </span>
        </div>
      )}
      <div style={{ flex: 1 }} />
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
        <ChartToggleBtn on={toggles.ema} label="EMAs" kind="accent" onClick={() => onToggle('ema')} />
        <ChartToggleBtn on={toggles.vwap} label="VWAP" kind="yellow" onClick={() => onToggle('vwap')} />
        <ChartToggleBtn on={toggles.bb} label="BB" kind="violet" onClick={() => onToggle('bb')} />
        <ChartToggleBtn on={toggles.fvg} label="FVG" kind="violet" onClick={() => onToggle('fvg')} />
        <ChartToggleBtn on={toggles.ob} label="OB" kind="warn" onClick={() => onToggle('ob')} />
        <ChartToggleBtn
          on={toggles.structure}
          label="STRUCT"
          kind="info"
          onClick={() => onToggle('structure')}
        />
        <ChartToggleBtn
          on={toggles.liquidity}
          label="LIQ"
          kind="info"
          onClick={() => onToggle('liquidity')}
        />
        <ChartToggleBtn on={toggles.volume} label="VOL" kind="ghost" onClick={() => onToggle('volume')} />
        <ChartToggleBtn
          on={toggles.position}
          label="POS"
          kind="accent"
          onClick={() => onToggle('position')}
        />
      </div>
    </div>
  );
}
