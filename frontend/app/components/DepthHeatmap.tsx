'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { DepthMetrics } from '@/app/hooks/useOrderFlow';

interface DepthHeatmapProps {
  instrument: string;
  depthData?: DepthMetrics;
}

// ~20 min of columns at the /topic/depth 500ms cadence.
const MAX_COLUMNS = 2400;
// Visible price window, in ticks (auto-follows the mid).
const WINDOW_TICKS = 40;
const HEIGHT_PX = 260;
// Re-anchor when the mid leaves the central 60% of the window.
const RECENTER_FRACTION = 0.3;
// Client-side staleness fallback when the server flag is absent (matches DepthBookWidget).
const STALE_AFTER_SEC = 30;

const TICK_SIZES: Record<string, number> = {
  MNQ: 0.25,
  MCL: 0.01,
  MGC: 0.1,
  E6: 0.00005,
};

interface HeatColumn {
  t: number;
  mid: number;
  bids: Array<[number, number]>; // [price, size]
  asks: Array<[number, number]>;
}

/** Smallest gap between adjacent ladder prices — fallback tick size inference. */
function inferTickSize(depthData?: DepthMetrics): number | null {
  const prices = [
    ...(depthData?.bids ?? []).map(l => l.price),
    ...(depthData?.asks ?? []).map(l => l.price),
  ].sort((a, b) => a - b);
  let min = Infinity;
  for (let i = 1; i < prices.length; i++) {
    const d = prices[i] - prices[i - 1];
    if (d > 1e-9 && d < min) min = d;
  }
  return Number.isFinite(min) ? min : null;
}

/** p95 of level sizes over a sample of recent columns — absolute scaling fails on thin books. */
function rollingP95(columns: HeatColumn[]): number {
  const sizes: number[] = [];
  // sample every 4th column (≤ ~600 columns × 20 levels) to keep redraws cheap
  for (let i = columns.length - 1; i >= 0 && sizes.length < 12_000; i -= 4) {
    const col = columns[i];
    for (const [, s] of col.bids) sizes.push(s);
    for (const [, s] of col.asks) sizes.push(s);
  }
  if (sizes.length === 0) return 1;
  sizes.sort((a, b) => a - b);
  return Math.max(1, sizes[Math.floor(sizes.length * 0.95)] ?? 1);
}

/**
 * Bookmap-style "movie" of the order book: x = time (last ~20 min), y = price
 * (window auto-follows the mid), cell brightness = resting size (log-scaled,
 * normalized to the rolling p95 of level sizes). Bids greenish, asks reddish,
 * mid-price drawn as a line. Raw canvas — no chart library — devicePixelRatio-aware.
 * Drawing pauses (FLUX FIGÉ overlay) while the latest snapshot is server-stale.
 */
export default function DepthHeatmap({ instrument, depthData }: DepthHeatmapProps) {
  const canvasRef = useRef<HTMLCanvasElement | null>(null);
  const containerRef = useRef<HTMLDivElement | null>(null);
  const columnsRef = useRef<HeatColumn[]>([]);
  const anchorRef = useRef<number | null>(null); // window center price
  const tickRef = useRef<number>(TICK_SIZES[instrument] ?? 0.25);
  const [widthPx, setWidthPx] = useState(0);

  // 1s heartbeat so the staleness overlay appears even when payloads stop entirely.
  const [nowMs, setNowMs] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNowMs(Date.now()), 1000);
    return () => clearInterval(timer);
  }, []);

  const lastUpdateMs = depthData?.dataTimestamp ? Date.parse(depthData.dataTimestamp) : NaN;
  const ageSec = Number.isNaN(lastUpdateMs) ? null : Math.max(0, (nowMs - lastUpdateMs) / 1000);
  const stale = depthData?.serverStale === true || (ageSec != null && ageSec > STALE_AFTER_SEC);

  // Reset history when switching instrument.
  useEffect(() => {
    columnsRef.current = [];
    anchorRef.current = null;
    tickRef.current = TICK_SIZES[instrument] ?? 0.25;
  }, [instrument]);

  // Track container width (devicePixelRatio handled at draw time).
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;
    const observer = new ResizeObserver(entries => {
      const w = Math.floor(entries[0]?.contentRect.width ?? 0);
      if (w > 0) setWidthPx(w);
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  const draw = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || widthPx === 0) return;
    const dpr = typeof window !== 'undefined' ? window.devicePixelRatio || 1 : 1;
    if (canvas.width !== widthPx * dpr || canvas.height !== HEIGHT_PX * dpr) {
      canvas.width = widthPx * dpr;
      canvas.height = HEIGHT_PX * dpr;
    }
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, widthPx, HEIGHT_PX);

    const columns = columnsRef.current;
    const anchor = anchorRef.current;
    if (columns.length === 0 || anchor == null) return;

    const tick = tickRef.current;
    const p95 = rollingP95(columns);
    const logP95 = Math.log1p(p95);
    const priceTop = anchor + (WINDOW_TICKS / 2) * tick;
    const rowH = HEIGHT_PX / WINDOW_TICKS;

    // x mapping: latest column at the right edge. Stretch the columns we already
    // have across the full width so the movie is legible from the first snapshots
    // instead of staying jammed in a thin right-hand sliver until ~20 min of
    // history accumulates. Once the buffer is full this settles into a rolling
    // 20-min window at widthPx / MAX_COLUMNS.
    const slotCount = Math.min(Math.max(columns.length, 1), MAX_COLUMNS);
    const slotW = widthPx / slotCount;
    const drawW = Math.max(slotW, 1 / dpr);

    const yFor = (price: number) => ((priceTop - price) / tick) * rowH;

    for (let i = 0; i < columns.length; i++) {
      const col = columns[i];
      const x = widthPx - (columns.length - i) * slotW;
      if (x + drawW < 0) continue;

      for (const [price, size] of col.bids) {
        const y = yFor(price);
        if (y < -rowH || y > HEIGHT_PX) continue;
        const a = Math.min(1, Math.max(0.06, Math.log1p(size) / logP95));
        ctx.fillStyle = `rgba(16, 185, 129, ${a.toFixed(3)})`;
        ctx.fillRect(x, y, drawW, rowH);
      }
      for (const [price, size] of col.asks) {
        const y = yFor(price);
        if (y < -rowH || y > HEIGHT_PX) continue;
        const a = Math.min(1, Math.max(0.06, Math.log1p(size) / logP95));
        ctx.fillStyle = `rgba(239, 68, 68, ${a.toFixed(3)})`;
        ctx.fillRect(x, y, drawW, rowH);
      }
    }

    // Mid-price line on top of the heat cells.
    ctx.strokeStyle = 'rgba(244, 244, 245, 0.85)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    let started = false;
    for (let i = 0; i < columns.length; i++) {
      const col = columns[i];
      if (col.mid <= 0) continue;
      const x = widthPx - (columns.length - i) * slotW + slotW / 2;
      const y = yFor(col.mid);
      if (y < 0 || y > HEIGHT_PX) { started = false; continue; }
      if (!started) { ctx.moveTo(x, y); started = true; }
      else ctx.lineTo(x, y);
    }
    ctx.stroke();

    // Price scale: top / mid / bottom of the window, right-aligned.
    const decimals = tick < 0.01 ? 5 : tick < 1 ? 2 : 0;
    ctx.fillStyle = 'rgba(161, 161, 170, 0.9)';
    ctx.font = '9px ui-monospace, monospace';
    ctx.textAlign = 'right';
    ctx.fillText(priceTop.toFixed(decimals), widthPx - 2, 9);
    ctx.fillText(anchor.toFixed(decimals), widthPx - 2, HEIGHT_PX / 2 + 3);
    ctx.fillText((anchor - (WINDOW_TICKS / 2) * tick).toFixed(decimals), widthPx - 2, HEIGHT_PX - 3);
  }, [widthPx]);

  // Append a column for every fresh payload (~500ms); pause while stale.
  useEffect(() => {
    if (!depthData || stale) return;
    const bids = depthData.bids ?? [];
    const asks = depthData.asks ?? [];
    if (bids.length === 0 && asks.length === 0) return;

    if (!TICK_SIZES[instrument]) {
      const inferred = inferTickSize(depthData);
      if (inferred) tickRef.current = inferred;
    }

    const bestBid = bids[0]?.price ?? 0;
    const bestAsk = asks[0]?.price ?? 0;
    const mid = bestBid > 0 && bestAsk > 0 ? (bestBid + bestAsk) / 2 : Math.max(bestBid, bestAsk);
    if (mid <= 0) return;

    const columns = columnsRef.current;
    columns.push({
      t: Date.now(),
      mid,
      bids: bids.map(l => [l.price, l.size]),
      asks: asks.map(l => [l.price, l.size]),
    });
    if (columns.length > MAX_COLUMNS) columns.splice(0, columns.length - MAX_COLUMNS);

    // Auto-follow: re-anchor when the mid leaves the central 60% of the window.
    const tickSize = tickRef.current;
    if (
      anchorRef.current == null ||
      Math.abs(mid - anchorRef.current) > RECENTER_FRACTION * WINDOW_TICKS * tickSize
    ) {
      anchorRef.current = mid;
    }

    draw();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [depthData, stale, instrument, draw]);

  // Redraw on resize.
  useEffect(() => {
    draw();
  }, [draw]);

  return (
    <div
      ref={containerRef}
      className={`relative rounded bg-zinc-900 border overflow-hidden ${
        stale ? 'border-amber-700/60' : 'border-zinc-800'
      }`}
    >
      <canvas
        ref={canvasRef}
        style={{ width: widthPx > 0 ? `${widthPx}px` : '100%', height: `${HEIGHT_PX}px`, display: 'block' }}
      />

      {/* Legend */}
      <div className="absolute left-1.5 top-1 flex items-center gap-2 text-[9px] font-mono text-zinc-500 bg-zinc-900/70 px-1 rounded pointer-events-none">
        <span><span className="text-emerald-400">■</span> bids</span>
        <span><span className="text-red-400">■</span> asks</span>
        <span>— mid</span>
        <span className="text-zinc-600">{WINDOW_TICKS}t · p95-norm</span>
      </div>

      {/* Staleness overlay — a frozen movie must never read as a live one */}
      {stale && (
        <div className="absolute inset-0 flex items-center justify-center bg-zinc-950/60 pointer-events-none">
          <div className="flex flex-col items-center gap-0.5 px-3 py-1.5 rounded bg-amber-950/80 border border-amber-800/60">
            <span className="text-[11px] font-semibold text-amber-300">FLUX FIGÉ</span>
            <span className="text-[9px] text-amber-400/80">
              {ageSec != null
                ? `dernier update il y a ${ageSec < 90 ? `${Math.round(ageSec)}s` : `${Math.round(ageSec / 60)}min`} — tracé en pause`
                : 'âge du carnet inconnu — tracé en pause'}
            </span>
          </div>
        </div>
      )}

      {columnsRef.current.length === 0 && !stale && (
        <div className="absolute inset-0 flex items-center justify-center pointer-events-none">
          <span className="text-[10px] text-zinc-600 italic">
            Accumulating {instrument} book snapshots…
          </span>
        </div>
      )}
    </div>
  );
}
