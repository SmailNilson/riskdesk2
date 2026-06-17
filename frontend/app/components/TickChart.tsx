'use client';

import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  createChart,
  ColorType,
  CandlestickSeries,
  HistogramSeries,
  IChartApi,
  IPriceLine,
  ISeriesApi,
  LineStyle,
  MouseEventParams,
  UTCTimestamp,
} from 'lightweight-charts';
import { TickBar, useOrderFlow } from '@/app/hooks/useOrderFlow';
import { useActivePositions } from '@/app/hooks/useActivePositions';
import { api, ActivePositionView, IndicatorSnapshot } from '@/app/lib/api';
import { isStaleSeed } from '@/app/lib/tickBarSession';

interface TickChartProps {
  selectedInstrument?: string;
  /** Dashboard's indicator snapshot — supplies SMC levels for the overlay. */
  snapshot?: IndicatorSnapshot | null;
  /** IBKR account selected in the portfolio panel — target of chart-trading orders.
   *  Falls back server-side to riskdesk.quant.auto-arm.broker-account-id when null. */
  brokerAccountId?: string | null;
}

/** Base bars kept client-side for re-aggregation (matches backend ring buffer). */
const MAX_BASE_BARS = 3000;
/** Merged bars actually rendered. */
const MAX_RENDERED_BARS = 300;
/** User-selectable bar sizes offered on top of the per-instrument base size. */
const EXTRA_BAR_SIZES = [1000, 2000];

/** Tick size / display decimals / default SL-TP offsets (in points) per instrument.
 *  SL/TP pre-fill the ticket only — the operator edits them before confirming. */
const INSTRUMENT_META: Record<string, { tick: number; decimals: number; slOffset: number; tpOffset: number }> = {
  MNQ: { tick: 0.25, decimals: 2, slOffset: 10, tpOffset: 15 },
  MCL: { tick: 0.01, decimals: 2, slOffset: 0.25, tpOffset: 0.4 },
  MGC: { tick: 0.1, decimals: 1, slOffset: 3, tpOffset: 4.5 },
  E6: { tick: 0.00005, decimals: 5, slOffset: 0.002, tpOffset: 0.003 },
};

const TERMINAL_STATUSES = new Set(['CLOSED', 'CANCELLED', 'REJECTED', 'FAILED']);
/** Entry not (fully) at the broker yet — the action is CANCEL, not close. */
const RESTING_STATUSES = new Set(['PENDING_ENTRY_SUBMISSION', 'ENTRY_SUBMITTED']);

function roundToTick(price: number, tick: number, decimals: number): number {
  return Number((Math.round(price / tick) * tick).toFixed(decimals));
}

function num(v: number | string | null | undefined): number | null {
  if (v == null) return null;
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? n : null;
}

interface PendingTicket {
  direction: 'LONG' | 'SHORT';
  price: number;
}

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
 * Chart trading (TRADE toggle, off by default): when armed, a click on the chart
 * opens a context menu quoting the clicked price (tick-rounded); choosing
 * Acheter/Vendre opens a confirmation ticket (price / qty / SL / TP editable)
 * which submits a real IBKR order through POST /api/quant/manual-trade
 * (submitImmediately). Working orders and the live position are drawn as price
 * lines fed by /topic/positions; the rows under the chart cancel a resting entry
 * (broker cancel) or flatten a live position (unified-router marketable close).
 * SL/TP lines are VIRTUAL — informative levels, not broker bracket orders.
 *
 * Data: REST seed (/api/order-flow/tick-bars) + live merge from /topic/tick-bars
 * (handled in useOrderFlow, keyed by bar seq). seq is only unique within one
 * backend session — the ring restarts at 0 on redeploy — so useOrderFlow bumps
 * `tickBarResets` (ring restart detected or STOMP reconnect) to trigger a seed
 * refetch, and a seed recognized as previous-session is dropped from the merge
 * (mixing sessions left old high-seq bars as frozen ghosts at the timeline end).
 *
 * lightweight-charts requires strictly increasing times; consecutive tick bars can
 * close within the same second on a busy tape, so times are de-duplicated by
 * bumping +1s when needed (label drift of a few seconds is acceptable here).
 */
function TickChart({ selectedInstrument, snapshot, brokerAccountId }: TickChartProps) {
  const { tickBars, tickBarResets } = useOrderFlow();
  const { positions, close, cancelEntry, refresh } = useActivePositions();
  const containerRef = useRef<HTMLDivElement>(null);
  const chartRef = useRef<IChartApi | null>(null);
  const candleSeriesRef = useRef<ISeriesApi<'Candlestick'> | null>(null);
  const deltaSeriesRef = useRef<ISeriesApi<'Histogram'> | null>(null);
  const smcLinesRef = useRef<IPriceLine[]>([]);
  const tradeLinesRef = useRef<IPriceLine[]>([]);
  const lastCloseRef = useRef<number | null>(null);
  const [seedBars, setSeedBars] = useState<TickBar[]>([]);
  const [barSize, setBarSize] = useState<number | null>(null); // null = base size
  const [showSmc, setShowSmc] = useState(true);

  // ── Chart-trading state ──────────────────────────────────────────────
  const [tradeArmed, setTradeArmed] = useState(false);
  const [menu, setMenu] = useState<{ x: number; y: number; price: number } | null>(null);
  const [ticket, setTicket] = useState<PendingTicket | null>(null);
  const [submitting, setSubmitting] = useState(false);
  const [notice, setNotice] = useState<{ kind: 'ok' | 'err'; text: string } | null>(null);
  /** Two-step inline confirm for cancel/close row buttons: executionId pending confirmation. */
  const [confirmAction, setConfirmAction] = useState<number | null>(null);

  const meta = selectedInstrument ? INSTRUMENT_META[selectedInstrument] : undefined;

  // Trading/selector UI resets only on instrument switch — not on a re-seed.
  useEffect(() => {
    setBarSize(null); // base size differs per instrument — reset the selector
    setMenu(null);
    setTicket(null);
    setConfirmAction(null);
  }, [selectedInstrument]);

  // REST seed on instrument switch (the WS tail only carries the last bars) and
  // on tick-bar resets (STOMP reconnect / backend ring restart): the old seed's
  // seqs belong to the previous backend session and must not merge with the new.
  useEffect(() => {
    if (!selectedInstrument) { setSeedBars([]); return; }
    let cancelled = false;
    api.getTickBars(selectedInstrument, MAX_BASE_BARS).then(bars => {
      if (!cancelled) setSeedBars(Array.isArray(bars) ? (bars as unknown as TickBar[]) : []);
    }).catch(() => { if (!cancelled) setSeedBars([]); });
    return () => { cancelled = true; };
  }, [selectedInstrument, tickBarResets]);

  // Merge REST seed + live bars by seq. Guard on instrument so a stale seed from
  // the previously selected instrument never mixes in while the new fetch resolves,
  // and drop a seed from a previous backend session (higher seq but strictly older
  // closeTime — impossible within one session) so it can't ghost the timeline
  // while its refetch is still in flight.
  const baseBars = useMemo(() => {
    if (!selectedInstrument) return [] as TickBar[];
    const live = tickBars.get(selectedInstrument) ?? [];
    let seed = seedBars.filter(b => b.instrument === selectedInstrument);
    if (isStaleSeed(seed, live)) seed = [];
    const bySeq = new Map<number, TickBar>(seed.map(b => [b.seq, b]));
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

  /** Open (non-terminal) executions on the displayed instrument — drives lines + action rows. */
  const openPositions = useMemo(
    () => positions.filter(p => p.instrument === selectedInstrument && !TERMINAL_STATUSES.has(p.status)),
    [positions, selectedInstrument],
  );

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
      height: 286, // +10% vs 260 pour un Tick Chart plus lisible
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
      tradeLinesRef.current = [];
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

  // Trading overlay — entry / SL / TP price lines for working orders and the live
  // position on this instrument, fed by /topic/positions (5s heartbeat + on change).
  useEffect(() => {
    const candles = candleSeriesRef.current;
    if (!candles) return;
    for (const line of tradeLinesRef.current) candles.removePriceLine(line);
    tradeLinesRef.current = [];

    const add = (price: number | null, color: string, style: LineStyle, width: 1 | 2, title: string) => {
      if (price == null) return;
      tradeLinesRef.current.push(candles.createPriceLine({
        price, color, lineWidth: width, lineStyle: style, axisLabelVisible: true, title,
      }));
    };

    for (const p of openPositions) {
      const entry = num(p.entryPrice);
      const qty = p.quantity ?? 1;
      const long = p.direction === 'LONG';
      if (RESTING_STATUSES.has(p.status)) {
        add(entry, '#f59e0b', LineStyle.Dashed, 1,
          `${long ? 'BUY' : 'SELL'} LMT ${qty}`);
      } else {
        const pnl = num(p.pnlDollars);
        add(entry, long ? '#10b981' : '#ef4444', LineStyle.Solid, 2,
          `${p.direction} ${qty}${pnl != null ? ` ${pnl >= 0 ? '+' : ''}${pnl.toFixed(0)}$` : ''}`);
        add(num(p.stopLoss), '#ef4444', LineStyle.Dotted, 1, 'SL');
        add(num(p.takeProfit1), '#10b981', LineStyle.Dotted, 1, 'TP');
      }
    }
  }, [openPositions]);

  // Chart-trading click capture — armed only while the TRADE toggle is on.
  useEffect(() => {
    const chart = chartRef.current;
    const candles = candleSeriesRef.current;
    if (!chart || !candles || !tradeArmed || !selectedInstrument || !meta) return;
    const handler = (param: MouseEventParams) => {
      if (!param.point) return;
      const raw = candles.coordinateToPrice(param.point.y);
      if (raw == null) return;
      const price = roundToTick(Number(raw), meta.tick, meta.decimals);
      if (!Number.isFinite(price) || price <= 0) return;
      setTicket(null);
      setMenu({ x: param.point.x, y: param.point.y, price });
    };
    chart.subscribeClick(handler);
    return () => chart.unsubscribeClick(handler);
  }, [tradeArmed, selectedInstrument, meta]);

  // Auto-clear the toast.
  useEffect(() => {
    if (!notice) return;
    const t = setTimeout(() => setNotice(null), 6000);
    return () => clearTimeout(t);
  }, [notice]);

  // Reset a pending inline confirm after a few seconds without the second click.
  useEffect(() => {
    if (confirmAction == null) return;
    const t = setTimeout(() => setConfirmAction(null), 4000);
    return () => clearTimeout(t);
  }, [confirmAction]);

  const handleRowAction = useCallback(async (p: ActivePositionView) => {
    if (confirmAction !== p.executionId) {
      setConfirmAction(p.executionId);
      return;
    }
    setConfirmAction(null);
    const resting = RESTING_STATUSES.has(p.status);
    const result = resting ? await cancelEntry(p.executionId) : await close(p.executionId);
    if (result) {
      setNotice({ kind: 'ok', text: resting ? 'Annulation envoyée au broker' : 'Clôture envoyée au broker' });
    } else {
      setNotice({ kind: 'err', text: resting ? 'Annulation refusée — voir Active Positions' : 'Clôture refusée — voir Active Positions' });
    }
    void refresh();
  }, [confirmAction, cancelEntry, close, refresh]);

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
          {meta && (
            <button
              onClick={() => { setTradeArmed(v => !v); setMenu(null); setTicket(null); }}
              className={`px-1.5 py-0.5 rounded text-[10px] font-semibold transition-colors ${
                tradeArmed
                  ? 'bg-amber-900/70 text-amber-300 ring-1 ring-amber-600/60'
                  : 'text-zinc-500 hover:text-zinc-300 hover:bg-zinc-800'
              }`}
              title="Mode trading : un clic sur le chart propose un ordre Achat/Vente au prix cliqué (confirmation requise avant envoi IBKR)"
            >
              TRADE
            </button>
          )}
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
      <div className="p-1 relative">
        {/* Keep the container mounted so the chart instance survives empty states. */}
        <div ref={containerRef} className={`w-full ${bars.length === 0 ? 'hidden' : ''}`} />
        {bars.length === 0 && (
          <div className="text-xs text-zinc-600 text-center py-8">
            {selectedInstrument
              ? `Waiting for ${selectedInstrument} tick data...`
              : 'Select an instrument'}
          </div>
        )}

        {/* Click-to-trade context menu */}
        {menu && meta && selectedInstrument && (
          <>
            <div className="absolute inset-0 z-10" onClick={() => setMenu(null)} />
            <div
              className="absolute z-20 rounded border border-zinc-700 bg-zinc-900 shadow-lg text-[11px] overflow-hidden"
              style={{
                left: Math.min(menu.x + 8, (containerRef.current?.clientWidth ?? 300) - 170),
                top: Math.max(menu.y - 30, 4),
              }}
            >
              <button
                className="block w-full text-left px-3 py-1.5 text-emerald-300 hover:bg-emerald-900/40 font-medium"
                onClick={() => { setTicket({ direction: 'LONG', price: menu.price }); setMenu(null); }}
              >
                Acheter LMT @ {menu.price.toFixed(meta.decimals)}
              </button>
              <button
                className="block w-full text-left px-3 py-1.5 text-red-300 hover:bg-red-900/40 font-medium border-t border-zinc-800"
                onClick={() => { setTicket({ direction: 'SHORT', price: menu.price }); setMenu(null); }}
              >
                Vendre LMT @ {menu.price.toFixed(meta.decimals)}
              </button>
              <button
                className="block w-full text-left px-3 py-1 text-zinc-500 hover:text-zinc-300 border-t border-zinc-800"
                onClick={() => setMenu(null)}
              >
                Annuler
              </button>
            </div>
          </>
        )}

        {/* Order ticket — the confirmation step before anything reaches IBKR */}
        {ticket && meta && selectedInstrument && (
          <TradeTicket
            instrument={selectedInstrument}
            meta={meta}
            ticket={ticket}
            brokerAccountId={brokerAccountId ?? null}
            submitting={submitting}
            onCancel={() => setTicket(null)}
            onSubmit={async (payload) => {
              setSubmitting(true);
              try {
                const result = await api.submitManualTrade(selectedInstrument, payload);
                setNotice({
                  kind: 'ok',
                  text: `Ordre ${payload.direction === 'LONG' ? 'ACHAT' : 'VENTE'} ${payload.entryType} envoyé — statut ${result.status}`,
                });
                setTicket(null);
                void refresh();
              } catch (err) {
                setNotice({ kind: 'err', text: err instanceof Error ? err.message : 'Envoi refusé' });
              } finally {
                setSubmitting(false);
              }
            }}
          />
        )}

        {/* Toast */}
        {notice && (
          <div className={`absolute top-2 left-1/2 -translate-x-1/2 z-30 px-3 py-1 rounded text-[11px] font-medium shadow ${
            notice.kind === 'ok' ? 'bg-emerald-900/90 text-emerald-200' : 'bg-red-900/90 text-red-200'
          }`}>
            {notice.text}
          </div>
        )}
      </div>

      {/* Open orders / position rows for this instrument */}
      {openPositions.length > 0 && (
        <div className="border-t border-zinc-800/60 divide-y divide-zinc-800/40">
          {openPositions.map(p => {
            const resting = RESTING_STATUSES.has(p.status);
            const entry = num(p.entryPrice);
            const pnl = num(p.pnlDollars);
            const confirming = confirmAction === p.executionId;
            return (
              <div key={p.executionId} className="flex items-center gap-2 px-3 py-1.5 text-[11px]">
                <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${
                  p.direction === 'LONG' ? 'bg-emerald-900/50 text-emerald-300' : 'bg-red-900/50 text-red-300'
                }`}>
                  {p.direction} {p.quantity ?? 1}
                </span>
                <span className={`text-[10px] ${resting ? 'text-amber-400' : 'text-zinc-400'}`}>
                  {resting ? `ordre ${p.status === 'PENDING_ENTRY_SUBMISSION' ? 'en préparation' : 'au broker'}` : p.status}
                </span>
                <span className="text-zinc-300">
                  @ {entry != null && meta ? entry.toFixed(meta.decimals) : '—'}
                </span>
                {!resting && pnl != null && (
                  <span className={pnl >= 0 ? 'text-emerald-400' : 'text-red-400'}>
                    {pnl >= 0 ? '+' : ''}{pnl.toFixed(2)}$
                  </span>
                )}
                <span className="flex-1" />
                <button
                  onClick={() => void handleRowAction(p)}
                  className={`px-2 py-0.5 rounded text-[10px] font-semibold transition-colors ${
                    confirming
                      ? 'bg-red-700 text-white'
                      : resting
                        ? 'bg-zinc-800 text-amber-300 hover:bg-amber-900/50'
                        : 'bg-zinc-800 text-red-300 hover:bg-red-900/50'
                  }`}
                >
                  {confirming ? 'Confirmer ?' : resting ? 'Annuler' : 'Fermer'}
                </button>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

/**
 * Confirmation ticket. Pre-fills SL/TP at per-instrument default offsets from the
 * clicked price — the operator can edit every field before the order is sent. The
 * SL/TP are persisted as VIRTUAL levels (drawn on the chart, no broker brackets);
 * exits remain operator-driven (Fermer) in this slice.
 */
function TradeTicket({ instrument, meta, ticket, brokerAccountId, submitting, onCancel, onSubmit }: {
  instrument: string;
  meta: { tick: number; decimals: number; slOffset: number; tpOffset: number };
  ticket: PendingTicket;
  brokerAccountId: string | null;
  submitting: boolean;
  onCancel: () => void;
  onSubmit: (payload: import('@/app/components/quant/types').ManualTradeRequest) => Promise<void>;
}) {
  const long = ticket.direction === 'LONG';
  const [entryType, setEntryType] = useState<'LIMIT' | 'MARKET'>('LIMIT');
  const [price, setPrice] = useState(ticket.price.toFixed(meta.decimals));
  const [qty, setQty] = useState('1');
  const [sl, setSl] = useState(
    roundToTick(long ? ticket.price - meta.slOffset : ticket.price + meta.slOffset, meta.tick, meta.decimals).toFixed(meta.decimals),
  );
  const [tp, setTp] = useState(
    roundToTick(long ? ticket.price + meta.tpOffset : ticket.price - meta.tpOffset, meta.tick, meta.decimals).toFixed(meta.decimals),
  );
  const [localError, setLocalError] = useState<string | null>(null);

  const submit = async () => {
    const entryPrice = Number(price);
    const stopLoss = Number(sl);
    const takeProfit = Number(tp);
    const quantity = Math.max(1, Math.floor(Number(qty) || 1));
    if (!brokerAccountId) {
      // No resolved IBKR account → the backend would 409 ("a brokerAccountId is
      // required"). Tell the operator to pick one in the IBKR Portfolio panel.
      setLocalError('Aucun compte IBKR — ouvrez le panel IBKR Portfolio et sélectionnez un compte'); return;
    }
    if (entryType === 'LIMIT' && (!Number.isFinite(entryPrice) || entryPrice <= 0)) {
      setLocalError('Prix limite invalide'); return;
    }
    if (!Number.isFinite(stopLoss) || !Number.isFinite(takeProfit)) {
      setLocalError('SL / TP invalides'); return;
    }
    const ref = entryType === 'LIMIT' ? entryPrice : ticket.price;
    if (long && (stopLoss >= ref || takeProfit <= ref)) {
      setLocalError('LONG : SL doit être sous le prix, TP au-dessus'); return;
    }
    if (!long && (stopLoss <= ref || takeProfit >= ref)) {
      setLocalError('SHORT : SL doit être au-dessus du prix, TP en dessous'); return;
    }
    setLocalError(null);
    await onSubmit({
      direction: ticket.direction,
      entryType,
      entryPrice: entryType === 'LIMIT' ? entryPrice : null,
      stopLoss,
      takeProfit1: takeProfit,
      takeProfit2: null,
      quantity,
      brokerAccountId,
      submitImmediately: true,
    });
  };

  const inputCls = 'w-full bg-zinc-800 border border-zinc-700 rounded px-1.5 py-0.5 text-[11px] text-zinc-200 focus:outline-none focus:border-zinc-500';

  return (
    <div className="absolute top-2 right-2 z-20 w-52 rounded border border-zinc-700 bg-zinc-900 shadow-xl p-2.5 text-[11px]">
      <div className={`font-semibold mb-1.5 ${long ? 'text-emerald-300' : 'text-red-300'}`}>
        {long ? 'ACHAT' : 'VENTE'} {instrument}
        {brokerAccountId
          ? <span className="text-zinc-500 font-normal"> · {brokerAccountId}</span>
          : <span className="text-amber-400 font-normal"> · ⚠ aucun compte IBKR</span>}
      </div>
      <div className="flex gap-1 mb-1.5">
        {(['LIMIT', 'MARKET'] as const).map(t => (
          <button
            key={t}
            onClick={() => setEntryType(t)}
            className={`flex-1 py-0.5 rounded text-[10px] font-medium ${
              entryType === t ? 'bg-zinc-700 text-zinc-100' : 'bg-zinc-800 text-zinc-500 hover:text-zinc-300'
            }`}
          >
            {t === 'LIMIT' ? 'LMT' : 'MKT'}
          </button>
        ))}
      </div>
      <div className="grid grid-cols-2 gap-1.5 mb-1.5">
        <label className="text-zinc-500">
          Prix
          <input value={price} onChange={e => setPrice(e.target.value)} disabled={entryType === 'MARKET'}
            className={`${inputCls} ${entryType === 'MARKET' ? 'opacity-40' : ''}`} />
        </label>
        <label className="text-zinc-500">
          Qté
          <input value={qty} onChange={e => setQty(e.target.value)} className={inputCls} />
        </label>
        <label className="text-zinc-500">
          SL (virtuel)
          <input value={sl} onChange={e => setSl(e.target.value)} className={inputCls} />
        </label>
        <label className="text-zinc-500">
          TP (virtuel)
          <input value={tp} onChange={e => setTp(e.target.value)} className={inputCls} />
        </label>
      </div>
      <p className="text-[10px] text-zinc-600 mb-1.5">
        SL/TP virtuels : tracés sur le chart, pas d&apos;ordres bracket broker — sortie via « Fermer ».
      </p>
      {localError && <p className="text-[10px] text-red-400 mb-1.5">{localError}</p>}
      <div className="flex gap-1.5">
        <button
          onClick={() => void submit()}
          disabled={submitting || !brokerAccountId}
          className={`flex-1 py-1 rounded font-semibold text-[11px] disabled:opacity-50 disabled:cursor-not-allowed ${
            long ? 'bg-emerald-700 hover:bg-emerald-600 text-white' : 'bg-red-700 hover:bg-red-600 text-white'
          }`}
        >
          {submitting ? 'Envoi…' : `Confirmer ${long ? 'ACHAT' : 'VENTE'}`}
        </button>
        <button onClick={onCancel} className="px-2 py-1 rounded bg-zinc-800 text-zinc-400 hover:text-zinc-200 text-[11px]">
          Annuler
        </button>
      </div>
    </div>
  );
}

export default memo(TickChart);
