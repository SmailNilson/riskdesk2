'use client';

import {
  createContext,
  ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import {
  AlertPayload,
  CandleBar,
  DxySnapshotView,
  FairValueGapView,
  FootprintBar as ApiFootprintBar,
  IndicatorSnapshot,
  isFootprintBar,
  LivePriceView,
  MentorSignalReview as ApiMentorSignalReview,
  OrderBlockView,
  PortfolioSummary,
  PositionView,
  StructureBreakView,
  TradeExecutionView,
  api,
} from '../../lib/api';
import { API_BASE, WS_BASE } from '../../lib/runtimeConfig';
import {
  AlertItem,
  Candle,
  ExecStatus,
  FootprintBar as DesignFootprintBar,
  FvgZone,
  Indicators,
  Macro,
  OrderBlock,
  Portfolio,
  Position,
  RD_DATA,
  RiskDeskData,
  Review,
  ReviewSim,
  StructureEvent,
} from './data';

// ── Live data shape ───────────────────────────────────────────────
export interface LiveDataState extends RiskDeskData {
  /** True once the first batch of fetches has resolved (mock until then). */
  ready: boolean;
  /** Live websocket connection state. */
  wsConnected: boolean;
  /** Whether the backend is reachable at all (last fetch succeeded). */
  backendReachable: boolean;
  /** Currently selected instrument symbol. */
  instrumentSym: string;
  setInstrumentSym: (sym: string) => void;
  /** Currently selected timeframe. */
  timeframe: string;
  setTimeframe: (tf: string) => void;
  /** Wired execution actions — call backend then update local state. */
  armExecution: (review: Review, qty: number) => Promise<TradeExecutionView | null>;
  submitExecutionEntry: (executionId: number) => Promise<TradeExecutionView | null>;
  /** Map of reviewId → live broker execution. */
  executions: Record<number, TradeExecutionView>;
}

const RiskDeskContext = createContext<LiveDataState | null>(null);

export function useRiskDeskData(): LiveDataState {
  const ctx = useContext(RiskDeskContext);
  if (!ctx) {
    throw new Error('useRiskDeskData must be used inside <RiskDeskProvider>');
  }
  return ctx;
}

// ── Backend → design type mappers ─────────────────────────────────

function mapPortfolio(s: PortfolioSummary): Portfolio {
  return {
    unrealized: s.totalUnrealizedPnL ?? 0,
    todayRealized: s.todayRealizedPnL ?? 0,
    total: s.totalPnL ?? 0,
    openCount: s.openPositionCount ?? 0,
    exposure: s.totalExposure ?? 0,
    marginPct: s.marginUsedPct ?? 0,
    // Backend doesn't expose dayDD directly — surface today's realized when negative.
    dayDD: Math.min(0, s.todayRealizedPnL ?? 0),
    dailyTarget: 600,
    dailyMax: -800,
  };
}

function mapPosition(p: PositionView): Position {
  const opened = p.openedAt ? Date.parse(p.openedAt) : Date.now();
  return {
    id: p.id ?? 0,
    instrument: p.instrument,
    side: p.side,
    qty: p.quantity,
    entry: p.entryPrice,
    current: p.currentPrice,
    sl: p.stopLoss ?? p.entryPrice,
    tp: p.takeProfit ?? p.entryPrice,
    opened: Number.isFinite(opened) ? opened : Date.now(),
    pnl: p.unrealizedPnL ?? 0,
    notes: p.notes ?? '',
  };
}

function mapIndicators(s: IndicatorSnapshot, fallback: Indicators): Indicators {
  const regime = inferRegime(s) ?? fallback.regime;
  return {
    ema9: s.ema9 ?? fallback.ema9,
    ema50: s.ema50 ?? fallback.ema50,
    ema200: s.ema200 ?? fallback.ema200,
    rsi: s.rsi ?? fallback.rsi,
    macd: s.macdLine ?? fallback.macd,
    macdSig: s.macdSignal ?? fallback.macdSig,
    macdHist: s.macdHistogram ?? fallback.macdHist,
    vwap: s.vwap ?? fallback.vwap,
    superTrend: {
      value: s.supertrendValue ?? fallback.superTrend.value,
      side: s.supertrendBullish ? 'bull' : 'bear',
    },
    cmf: s.cmf ?? fallback.cmf,
    regime,
  };
}

function inferRegime(s: IndicatorSnapshot): Indicators['regime'] | null {
  // Prefer the BB-trend-based marketStructureTrend signal when present.
  if (s.bbTrendSignal === 'EXPANDING') return 'TRENDING';
  if (s.bbTrendSignal === 'CONTRACTING') return 'RANGING';
  if (s.marketStructureTrend === 'BULLISH' || s.marketStructureTrend === 'BEARISH') return 'TRENDING';
  if (s.marketStructureTrend === 'RANGING') return 'RANGING';
  return null;
}

function mapDxy(latest: DxySnapshotView, history: DxySnapshotView[]): Macro['dxy'] {
  const chgPct = latest.changePercent ?? 0;
  const chg24h =
    latest.baselineValue != null ? latest.dxyValue - latest.baselineValue : 0;
  const sparkline = history.length > 0
    ? history.map((h) => h.dxyValue)
    : [latest.dxyValue];
  return {
    price: latest.dxyValue,
    chg24h,
    chgPct,
    trend: chgPct < 0 ? 'DOWN' : 'UP',
    sparkline,
  };
}

function mapCandles(bars: CandleBar[]): Candle[] {
  return bars.map((b, i) => ({
    t: b.time * 1000,
    i,
    o: b.open,
    h: b.high,
    l: b.low,
    c: b.close,
    v: b.volume,
    bid: 0,
    ask: 0,
  }));
}

function findCandleIndex(candles: Candle[], epochSeconds: number): number {
  if (!candles.length) return -1;
  const targetMs = epochSeconds * 1000;
  let best = 0;
  let bestDelta = Math.abs(candles[0].t - targetMs);
  for (let i = 1; i < candles.length; i++) {
    const delta = Math.abs(candles[i].t - targetMs);
    if (delta < bestDelta) {
      bestDelta = delta;
      best = i;
    }
  }
  return best;
}

function mapOrderBlocks(snapshot: IndicatorSnapshot, candles: Candle[]): OrderBlock[] {
  const all: OrderBlockView[] = [
    ...(snapshot.activeOrderBlocks ?? []),
    ...(snapshot.breakerOrderBlocks ?? []),
  ];
  return all
    .map((ob): OrderBlock | null => {
      const idx = findCandleIndex(candles, ob.startTime);
      if (idx < 0) return null;
      const strength = ob.status === 'ACTIVE' ? 'strong' : ob.status === 'BREAKER' ? 'mit' : 'weak';
      return {
        from: idx,
        to: Math.min(candles.length - 1, idx + 4),
        top: ob.high,
        bot: ob.low,
        side: ob.type === 'BULLISH' ? 'bull' : 'bear',
        strength,
      };
    })
    .filter((ob): ob is OrderBlock => ob !== null);
}

function mapStructure(snapshot: IndicatorSnapshot, candles: Candle[]): StructureEvent[] {
  const breaks = snapshot.recentBreaks ?? [];
  return breaks
    .map((b: StructureBreakView): StructureEvent | null => {
      const idx = findCandleIndex(candles, b.barTime);
      if (idx < 0) return null;
      return {
        idx,
        type: b.type === 'CHOCH' ? 'CHoCH' : 'BOS',
        side: b.trend === 'BULLISH' ? 'bull' : 'bear',
        price: b.level,
      };
    })
    .filter((s): s is StructureEvent => s !== null);
}

function mapFvgs(snapshot: IndicatorSnapshot, candles: Candle[]): FvgZone[] {
  const fvgs = snapshot.activeFairValueGaps ?? [];
  return fvgs
    .map((g: FairValueGapView): FvgZone | null => {
      const from = findCandleIndex(candles, g.startTime);
      const to = findCandleIndex(candles, g.extensionEndTime || g.startTime);
      if (from < 0) return null;
      return {
        from,
        to: Math.max(from + 1, to),
        top: g.top,
        bot: g.bottom,
        side: g.bias === 'BULLISH' ? 'bull' : 'bear',
      };
    })
    .filter((g): g is FvgZone => g !== null);
}

function mapFootprint(bar: ApiFootprintBar): DesignFootprintBar[] {
  // The redesign expects a list of bars but the REST endpoint returns one bar.
  // Emit a single-bar list so the rest of the UI keeps working.
  const levels = Object.values(bar.levels)
    .map((l) => ({ price: l.price, bid: l.sellVolume, ask: l.buyVolume }))
    .sort((a, b) => b.price - a.price);
  // Synthesize an o/c from the totals (close > open if buy dominant)
  const buyDominant = bar.totalDelta >= 0;
  const ref = bar.pocPrice;
  const o = buyDominant ? ref - 0.05 : ref + 0.05;
  const c = buyDominant ? ref + 0.05 : ref - 0.05;
  return [{ t: bar.barTimestamp, o, c, levels }];
}

function mapAlert(a: AlertPayload, idx: number): AlertItem {
  // Backend alert.key is the dedup key (e.g., "risk:concentration:2") and is
  // intentionally NOT unique across firings — combine with the timestamp +
  // index so React's reconciliation has a stable, unique key per row.
  const keyPart = a.key ? `${a.key}:${a.timestamp}` : `live-${idx}-${a.timestamp}`;
  return {
    id: keyPart,
    sev: a.severity,
    cat: a.category,
    instrument: a.instrument,
    tf: null,
    msg: a.message,
    t: Date.parse(a.timestamp) || Date.now(),
  };
}

function mapReview(r: ApiMentorSignalReview, exec: TradeExecutionView | undefined): Review {
  const direction = r.action === 'SHORT' ? 'SHORT' : 'LONG';
  const plan = r.analysis?.analysis?.proposedTradePlan;
  const elig = r.executionEligibilityStatus;
  return {
    id: r.id,
    instrument: r.instrument,
    tf: r.timeframe,
    direction,
    categories: r.category ? [r.category] : [],
    status: r.status,
    eligibility: elig === 'ELIGIBLE' ? 'ELIGIBLE' : elig === 'INELIGIBLE' ? 'INELIGIBLE' : null,
    triggerPrice: r.triggerPrice ?? 0,
    createdAt: Date.parse(r.createdAt) || Date.now(),
    confluence: 3.0,
    plan:
      plan && plan.entryPrice != null && plan.stopLoss != null && plan.takeProfit != null
        ? {
            entry: plan.entryPrice,
            sl: plan.stopLoss,
            tp: plan.takeProfit,
            rr: plan.rewardToRiskRatio ?? 0,
          }
        : null,
    verdict: r.analysis?.analysis?.verdict ?? null,
    analysis: r.analysis?.analysis?.technicalQuickAnalysis ?? null,
    advice: r.analysis?.analysis?.improvementTip ?? null,
    sim: r.simulationStatus
      ? ({
          status: r.simulationStatus === 'CANCELLED' ? 'MISSED' : r.simulationStatus,
          drawdown: r.maxDrawdownPoints,
          mfe: r.bestFavorablePrice,
        } as ReviewSim)
      : null,
    execution: exec
      ? {
          status: mapExecStatus(exec.status),
          qty: exec.quantity ?? 0,
          fillPx: null,
        }
      : undefined,
  };
}

function mapExecStatus(s: TradeExecutionView['status']): ExecStatus {
  switch (s) {
    case 'PENDING_ENTRY_SUBMISSION':
      return 'PENDING_ENTRY_SUBMISSION';
    case 'ENTRY_SUBMITTED':
    case 'ENTRY_PARTIALLY_FILLED':
      return 'ENTRY_SUBMITTED';
    case 'ACTIVE':
    case 'VIRTUAL_EXIT_TRIGGERED':
    case 'EXIT_SUBMITTED':
      return 'ACTIVE';
    case 'CLOSED':
      return 'CLOSED';
    case 'CANCELLED':
    case 'REJECTED':
    case 'FAILED':
      return 'FAILED';
    default:
      return 'PENDING_ENTRY_SUBMISSION';
  }
}

// ── WS URL helper (mirrors useWebSocket) ──────────────────────────
function buildWsUrl(): string {
  const base = (WS_BASE || API_BASE || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

// ── Provider ──────────────────────────────────────────────────────
export function RiskDeskProvider({ children }: { children: ReactNode }) {
  // Static mock as initial state — keeps SSR/initial render rich, then live data
  // overwrites once fetches resolve.
  const [state, setState] = useState<RiskDeskData>(RD_DATA);
  const [ready, setReady] = useState(false);
  const [backendReachable, setBackendReachable] = useState(false);
  const [wsConnected, setWsConnected] = useState(false);
  const [executions, setExecutions] = useState<Record<number, TradeExecutionView>>({});
  const [instrumentSym, setInstrumentSym] = useState('MCL');
  const [timeframe, setTimeframe] = useState('10m');

  // Stable refs so WebSocket handlers don't capture stale state
  const stateRef = useRef(state);
  stateRef.current = state;

  // ── Initial fetches: portfolio, positions, dxy, alerts, reviews, executions
  useEffect(() => {
    let cancelled = false;
    let anyOk = false;

    (async () => {
      const tasks: Array<Promise<void>> = [];

      tasks.push(
        api
          .getPortfolioSummary()
          .then((p) => {
            anyOk = true;
            if (cancelled) return;
            setState((prev) => ({
              ...prev,
              portfolio: mapPortfolio(p),
              positions: (p.openPositions ?? []).map(mapPosition),
            }));
          })
          .catch(() => {})
      );

      tasks.push(
        api
          .getDxyLatest()
          .then(async (latest) => {
            anyOk = true;
            // Pull a 6h history window for the sparkline
            const to = new Date().toISOString();
            const from = new Date(Date.now() - 6 * 3600_000).toISOString();
            let history: DxySnapshotView[] = [];
            try {
              history = await api.getDxyHistory(from, to);
            } catch {
              history = [];
            }
            if (cancelled) return;
            const dxy = mapDxy(latest, history);
            setState((prev) => ({
              ...prev,
              macro: { ...prev.macro, dxy },
              tickers: prev.tickers.map((t) =>
                t.sym === 'DXY' ? { ...t, price: latest.dxyValue, chgPct: latest.changePercent ?? t.chgPct } : t
              ),
            }));
          })
          .catch(() => {})
      );

      tasks.push(
        api
          .getRecentAlerts()
          .then((alerts) => {
            anyOk = true;
            if (cancelled) return;
            setState((prev) => ({ ...prev, alerts: alerts.map(mapAlert) }));
          })
          .catch(() => {})
      );

      tasks.push(
        api
          .getRecentMentorSignalReviews()
          .then(async (reviews) => {
            anyOk = true;
            const ids = reviews.map((r) => r.id);
            let execs: TradeExecutionView[] = [];
            if (ids.length > 0) {
              try {
                execs = await api.getTradeExecutionsByReviewIds(ids);
              } catch {
                execs = [];
              }
            }
            if (cancelled) return;
            const execMap: Record<number, TradeExecutionView> = {};
            for (const e of execs) execMap[e.mentorSignalReviewId] = e;
            setExecutions(execMap);
            setState((prev) => ({
              ...prev,
              reviews: reviews.map((r) => mapReview(r, execMap[r.id])),
            }));
          })
          .catch(() => {})
      );

      // Initial live prices for the watchlist (excluding DXY which uses its own endpoint)
      const wantedSyms = ['MCL', 'MGC', 'E6', 'MNQ'];
      tasks.push(
        ...wantedSyms.map((sym) =>
          api
            .getLivePrice(sym)
            .then((p: LivePriceView) => {
              anyOk = true;
              if (cancelled) return;
              setState((prev) => ({
                ...prev,
                tickers: prev.tickers.map((t) =>
                  t.sym === sym ? { ...t, price: p.price } : t
                ),
              }));
            })
            .catch(() => {})
        )
      );

      await Promise.allSettled(tasks);
      if (cancelled) return;
      setBackendReachable(anyOk);
      setReady(true);
    })();

    return () => {
      cancelled = true;
    };
  }, []);

  // ── Per-instrument/per-timeframe fetches: candles + indicators + footprint
  useEffect(() => {
    let cancelled = false;

    (async () => {
      const candlesP = api.getCandles(instrumentSym, timeframe, 96).catch(() => [] as CandleBar[]);
      const indP = api.getIndicators(instrumentSym, timeframe).catch(() => null);
      const fpP = api
        .getFootprint(instrumentSym, '5m')
        .catch(() => null as Awaited<ReturnType<typeof api.getFootprint>> | null);
      const [bars, ind, fp] = await Promise.all([candlesP, indP, fpP]);
      if (cancelled) return;

      setState((prev) => {
        const candles = bars.length ? mapCandles(bars) : prev.candles;
        const indicators = ind ? mapIndicators(ind, prev.indicators) : prev.indicators;
        const orderBlocks = ind ? mapOrderBlocks(ind, candles) : prev.orderBlocks;
        const structure = ind ? mapStructure(ind, candles) : prev.structure;
        const fvgZones = ind ? mapFvgs(ind, candles) : prev.fvgZones;
        const footprint = fp && isFootprintBar(fp) ? mapFootprint(fp) : prev.footprint;
        const lastClose = candles.length ? candles[candles.length - 1].c : prev.lastClose;
        return {
          ...prev,
          candles,
          indicators,
          orderBlocks,
          structure,
          fvgZones,
          footprint,
          lastClose,
        };
      });
    })();

    return () => {
      cancelled = true;
    };
  }, [instrumentSym, timeframe]);

  // ── WebSocket: prices, alerts, mentor reviews
  useEffect(() => {
    const wsUrl = buildWsUrl();
    const client = new Client({
      webSocketFactory: () => new SockJS(wsUrl) as WebSocket,
      reconnectDelay: 5000,
      onConnect: () => {
        setWsConnected(true);

        client.subscribe('/topic/prices', (msg: IMessage) => {
          try {
            const update = JSON.parse(msg.body) as { instrument: string; price: number };
            setState((prev) => ({
              ...prev,
              tickers: prev.tickers.map((t) =>
                t.sym === update.instrument ? { ...t, price: update.price } : t
              ),
              lastClose: update.instrument === stateRef.current.candles?.[0]?.i.toString()
                ? update.price
                : prev.lastClose,
            }));
          } catch {
            /* ignore malformed messages */
          }
        });

        client.subscribe('/topic/alerts', (msg: IMessage) => {
          try {
            const alert = JSON.parse(msg.body) as AlertPayload;
            setState((prev) => ({
              ...prev,
              alerts: [mapAlert(alert, 0), ...prev.alerts].slice(0, 50),
            }));
          } catch {
            /* ignore */
          }
        });

        client.subscribe('/topic/mentor-alerts', (msg: IMessage) => {
          try {
            const review = JSON.parse(msg.body) as ApiMentorSignalReview;
            setState((prev) => {
              const existing = prev.reviews.filter((r) => r.id !== review.id);
              const exec = executionsRef.current[review.id];
              return {
                ...prev,
                reviews: [mapReview(review, exec), ...existing].slice(0, 50),
              };
            });
          } catch {
            /* ignore */
          }
        });
      },
      onDisconnect: () => setWsConnected(false),
      onStompError: () => setWsConnected(false),
      onWebSocketClose: () => setWsConnected(false),
    });

    client.activate();
    return () => {
      void client.deactivate();
    };
  }, []);

  const executionsRef = useRef(executions);
  executionsRef.current = executions;

  const armExecution = useCallback(
    async (review: Review, qty: number): Promise<TradeExecutionView | null> => {
      try {
        const exec = await api.createTradeExecution({
          mentorSignalReviewId: review.id,
          brokerAccountId: 'DEFAULT',
          quantity: qty,
        });
        setExecutions((prev) => ({ ...prev, [review.id]: exec }));
        setState((prev) => ({
          ...prev,
          reviews: prev.reviews.map((r) =>
            r.id === review.id
              ? {
                  ...r,
                  execution: { status: mapExecStatus(exec.status), qty, fillPx: null },
                }
              : r
          ),
        }));
        return exec;
      } catch (err) {
        // Surface the failure on the review row so the UI doesn't go stale.
        console.error('armExecution failed', err);
        return null;
      }
    },
    []
  );

  const submitExecutionEntry = useCallback(
    async (executionId: number): Promise<TradeExecutionView | null> => {
      try {
        const exec = await api.submitTradeExecutionEntry(executionId);
        setExecutions((prev) => ({ ...prev, [exec.mentorSignalReviewId]: exec }));
        setState((prev) => ({
          ...prev,
          reviews: prev.reviews.map((r) =>
            r.id === exec.mentorSignalReviewId
              ? {
                  ...r,
                  execution: {
                    status: mapExecStatus(exec.status),
                    qty: exec.quantity ?? 0,
                    fillPx: null,
                  },
                }
              : r
          ),
        }));
        return exec;
      } catch (err) {
        console.error('submitExecutionEntry failed', err);
        return null;
      }
    },
    []
  );

  const value = useMemo<LiveDataState>(
    () => ({
      ...state,
      ready,
      wsConnected,
      backendReachable,
      instrumentSym,
      setInstrumentSym,
      timeframe,
      setTimeframe,
      armExecution,
      submitExecutionEntry,
      executions,
    }),
    [
      state,
      ready,
      wsConnected,
      backendReachable,
      instrumentSym,
      timeframe,
      armExecution,
      submitExecutionEntry,
      executions,
    ]
  );

  return <RiskDeskContext.Provider value={value}>{children}</RiskDeskContext.Provider>;
}
