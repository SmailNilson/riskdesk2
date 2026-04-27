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
  IndicatorSeriesSnapshot,
  IndicatorSnapshot,
  LivePriceView,
  MentorSignalReview as ApiMentorSignalReview,
  TradeExecutionView,
  api,
  isFootprintBar,
} from '../../lib/api';
import { API_BASE, WS_BASE } from '../../lib/runtimeConfig';
import { RD_MOCK, RiskDeskMock, Review } from './data';
import {
  mapAlert,
  mapCandles,
  mapCorrelations,
  mapDom,
  mapDxy,
  mapEmaSeries,
  mapFlashCrash,
  mapFootprint,
  mapIbkr,
  mapIndicators,
  mapMicroEvents,
  mapOrderFlowProd,
  mapPortfolio,
  mapPositions,
  mapReview,
  mapRiskAlerts,
  mapSmc,
  mapStrategy,
} from './mappers';

export interface RiskDeskState extends RiskDeskMock {
  ready: boolean;
  backendReachable: boolean;
  wsConnected: boolean;
  latencyMs: number;
  instrumentSym: string;
  setInstrumentSym: (s: string) => void;
  tf: string;
  setTf: (tf: string) => void;
  armReview: (r: Review, qty: number) => Promise<TradeExecutionView | null>;
  submitExecution: (executionId: number) => Promise<TradeExecutionView | null>;
  liveExecutions: Record<string, TradeExecutionView>;
}

const RiskDeskContext = createContext<RiskDeskState | null>(null);

export function useRiskDesk(): RiskDeskState {
  const ctx = useContext(RiskDeskContext);
  if (!ctx) throw new Error('useRiskDesk must be used inside <RiskDeskProvider>');
  return ctx;
}

function buildWsUrl(): string {
  const base = (WS_BASE || API_BASE || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

const WATCHLIST_TIMEFRAME_FOR_INDICATOR = '10m';

export function RiskDeskProvider({ children }: { children: ReactNode }) {
  // Mock is the SSR initial state; live data overwrites slice-by-slice as fetches resolve.
  const [data, setData] = useState<RiskDeskMock>(RD_MOCK);
  const [ready, setReady] = useState(false);
  const [backendReachable, setBackendReachable] = useState(false);
  const [wsConnected, setWsConnected] = useState(false);
  const [instrumentSym, setInstrumentSym] = useState('MCL');
  const [tf, setTf] = useState('10m');
  const [liveExecutions, setLiveExecutions] = useState<Record<string, TradeExecutionView>>({});

  // Stable refs for handlers that close over mutable state
  const dataRef = useRef(data);
  dataRef.current = data;

  // ─── Global one-shot fetches ─────────────────────────────────
  useEffect(() => {
    let cancelled = false;
    let anyOk = false;
    const tasks: Array<Promise<unknown>> = [];

    tasks.push(
      api
        .getPortfolioSummary()
        .then((p) => {
          anyOk = true;
          if (cancelled) return;
          setData((prev) => ({
            ...prev,
            portfolio: mapPortfolio(p, prev.portfolio),
            positions: mapPositions(p.openPositions),
          }));
        })
        .catch(() => {})
    );

    tasks.push(
      api
        .getDxyLatest()
        .then(async (latest) => {
          anyOk = true;
          const to = new Date().toISOString();
          const from = new Date(Date.now() - 6 * 3600_000).toISOString();
          let history: DxySnapshotView[] = [];
          try {
            history = await api.getDxyHistory(from, to);
          } catch {
            history = [];
          }
          if (cancelled) return;
          setData((prev) => ({ ...prev, dxy: mapDxy(latest, history, prev.dxy) }));
        })
        .catch(() => {})
    );

    tasks.push(
      api
        .getRecentAlerts()
        .then((alerts) => {
          anyOk = true;
          if (cancelled) return;
          setData((prev) => ({
            ...prev,
            alerts: alerts.map(mapAlert),
            riskAlerts: mapRiskAlerts(alerts),
          }));
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
          if (ids.length) {
            try {
              execs = await api.getTradeExecutionsByReviewIds(ids);
            } catch {
              execs = [];
            }
          }
          if (cancelled) return;
          const execMap: Record<string, TradeExecutionView> = {};
          for (const e of execs) execMap[String(e.mentorSignalReviewId)] = e;
          setLiveExecutions(execMap);
          setData((prev) => ({ ...prev, reviews: reviews.map(mapReview) }));
        })
        .catch(() => {})
    );

    tasks.push(
      api
        .getIbkrPortfolio()
        .then((s) => {
          anyOk = true;
          if (cancelled) return;
          setData((prev) => ({ ...prev, ibkr: mapIbkr(s, prev.ibkr) }));
        })
        .catch(() => {})
    );

    tasks.push(
      api
        .getCorrelationHistory()
        .then((history) => {
          anyOk = true;
          if (cancelled) return;
          setData((prev) => ({ ...prev, correlations: mapCorrelations(history, prev.correlations) }));
        })
        .catch(() => {})
    );

    tasks.push(
      api
        .getTrailingStats(7)
        .then((stats) => {
          anyOk = true;
          if (cancelled) return;
          setData((prev) => ({
            ...prev,
            backtest: {
              ...prev.backtest,
              trades: stats.fixedSLTP.trades + stats.trailingStop.trades,
              winRate: (stats.fixedSLTP.winRate + stats.trailingStop.winRate) / 2,
              profitFactor: prev.backtest.profitFactor,
            },
            trailing: {
              ...prev.trailing,
              modes: prev.trailing.modes.map((m) =>
                m.name === 'Fixed'
                  ? { ...m, winRate: stats.fixedSLTP.winRate, expectancy: stats.fixedSLTP.netPnl }
                  : m.name === 'ATR×1.2'
                  ? { ...m, winRate: stats.trailingStop.winRate, expectancy: stats.trailingStop.netPnl }
                  : m
              ),
            },
          }));
        })
        .catch(() => {})
    );

    // Live prices for the watchlist instruments (DXY/ES/NQ are different — backend
    // only exposes get-live-price for our 4 main contracts plus DXY).
    const watchSymbols = ['MCL', 'MGC', 'E6', 'MNQ', 'DXY'];
    for (const sym of watchSymbols) {
      tasks.push(
        api
          .getLivePrice(sym)
          .then((p: LivePriceView) => {
            anyOk = true;
            if (cancelled) return;
            setData((prev) => ({
              ...prev,
              instruments: prev.instruments.map((i) => (i.sym === sym ? { ...i, last: p.price, px: p.price } : i)),
              watchlist: prev.watchlist.map((w) => (w.sym === sym ? { ...w, px: p.price } : w)),
            }));
          })
          .catch(() => {})
      );
    }

    Promise.allSettled(tasks).then(() => {
      if (cancelled) return;
      setBackendReachable(anyOk);
      setReady(true);
    });

    return () => {
      cancelled = true;
    };
  }, []);

  // ─── Per-instrument / per-tf fetches ──────────────────────────
  useEffect(() => {
    let cancelled = false;
    (async () => {
      const candlesP = api.getCandles(instrumentSym, tf, 240).catch(() => [] as CandleBar[]);
      const indP = api.getIndicators(instrumentSym, tf).catch(() => null as IndicatorSnapshot | null);
      const seriesP = api
        .getIndicatorSeries(instrumentSym, tf, 240)
        .catch(() => null as IndicatorSeriesSnapshot | null);
      const fpP = api
        .getFootprint(instrumentSym, '5m')
        .catch(() => null as Awaited<ReturnType<typeof api.getFootprint>> | null);
      const depthP = api
        .getOrderFlowDepth(instrumentSym)
        .catch(() => null as Awaited<ReturnType<typeof api.getOrderFlowDepth>> | null);
      const flashP = api
        .getFlashCrashStatusForInstrument(instrumentSym)
        .catch(() => null as Awaited<ReturnType<typeof api.getFlashCrashStatusForInstrument>> | null);
      const momP = api.getMomentumEvents(instrumentSym, 20).catch(() => []);
      const iceP = api.getIcebergEvents(instrumentSym, 20).catch(() => []);
      const absP = api.getAbsorptionEvents(instrumentSym, 20).catch(() => []);
      const spfP = api.getSpoofingEvents(instrumentSym, 20).catch(() => []);
      const distP = api.getDistributionEvents(instrumentSym, 20).catch(() => []);
      const cycP = api.getCycleEvents(instrumentSym, 20).catch(() => []);
      const stratP = api
        .getStrategyDecision(instrumentSym, tf)
        .catch(() => null as Awaited<ReturnType<typeof api.getStrategyDecision>> | null);

      const [bars, ind, series, fp, depth, flash, mom, ice, abs, spf, dist, cyc, strat] = await Promise.all([
        candlesP, indP, seriesP, fpP, depthP, flashP, momP, iceP, absP, spfP, distP, cycP, stratP,
      ]);
      if (cancelled) return;

      setData((prev) => {
        const candles = bars.length ? mapCandles(bars) : prev.candles;
        const indicators = ind ? mapIndicators(ind, prev.indicators) : prev.indicators;
        const ema9 = mapEmaSeries(series?.ema9, candles, prev.ema9);
        const ema20 = prev.ema20.slice(0, candles.length); // backend doesn't expose ema20 series
        const ema50 = mapEmaSeries(series?.ema50, candles, prev.ema50);
        const smc = ind ? mapSmc(ind, candles, prev.smc) : prev.smc;
        const mappedFp = fp && isFootprintBar(fp) ? mapFootprint(fp) : null;
        const footprint = mappedFp && mappedFp.length ? mappedFp : prev.footprint;
        const dom = depth ? mapDom(depth, prev.dom) : prev.dom;
        const flashCrash = flash ? mapFlashCrash(flash, prev.flashCrash) : prev.flashCrash;
        const microEvents = mapMicroEvents({
          momentum: mom,
          iceberg: ice,
          absorption: abs,
          spoofing: spf,
          distribution: dist,
          cycle: cyc,
        });
        const orderflowProd = depth
          ? mapOrderFlowProd(depth, cyc, dist, prev.orderflowProd)
          : prev.orderflowProd;
        const strategy = strat ? mapStrategy(strat, prev.strategy) : prev.strategy;
        return {
          ...prev,
          candles,
          indicators,
          ema9,
          ema20,
          ema50,
          smc,
          footprint,
          dom,
          flashCrash,
          microEvents,
          orderflowProd,
          strategy,
        };
      });
    })();

    return () => {
      cancelled = true;
    };
  }, [instrumentSym, tf]);

  // ─── WebSocket subscriptions ──────────────────────────────────
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
            setData((prev) => ({
              ...prev,
              instruments: prev.instruments.map((i) =>
                i.sym === update.instrument ? { ...i, last: update.price, px: update.price } : i
              ),
              watchlist: prev.watchlist.map((w) =>
                w.sym === update.instrument ? { ...w, px: update.price } : w
              ),
            }));
          } catch {
            /* ignore */
          }
        });

        client.subscribe('/topic/alerts', (msg: IMessage) => {
          try {
            const a = JSON.parse(msg.body) as AlertPayload;
            setData((prev) => ({
              ...prev,
              alerts: [mapAlert(a), ...prev.alerts].slice(0, 50),
              riskAlerts: mapRiskAlerts([a, ...(prev.alerts as unknown as AlertPayload[])]),
            }));
          } catch {
            /* ignore */
          }
        });

        client.subscribe('/topic/mentor-alerts', (msg: IMessage) => {
          try {
            const review = JSON.parse(msg.body) as ApiMentorSignalReview;
            setData((prev) => ({
              ...prev,
              reviews: [mapReview(review), ...prev.reviews.filter((r) => r.id !== String(review.id))].slice(0, 50),
            }));
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

  // ─── Execution actions ────────────────────────────────────────
  const armReview = useCallback(async (r: Review, qty: number): Promise<TradeExecutionView | null> => {
    const reviewId = parseInt(r.id, 10);
    if (Number.isNaN(reviewId)) return null;
    try {
      const exec = await api.createTradeExecution({
        mentorSignalReviewId: reviewId,
        brokerAccountId: 'DEFAULT',
        quantity: qty,
      });
      setLiveExecutions((prev) => ({ ...prev, [r.id]: exec }));
      return exec;
    } catch (err) {
      console.error('armReview failed', err);
      return null;
    }
  }, []);

  const submitExecution = useCallback(async (executionId: number): Promise<TradeExecutionView | null> => {
    try {
      const exec = await api.submitTradeExecutionEntry(executionId);
      setLiveExecutions((prev) => ({ ...prev, [String(exec.mentorSignalReviewId)]: exec }));
      return exec;
    } catch (err) {
      console.error('submitExecution failed', err);
      return null;
    }
  }, []);

  // The 10m indicator snapshot is already used as the global indicator panel
  // when on the watchlist's default timeframe. Mark it as referenced so eslint
  // doesn't flag the constant as unused — it's there to document intent.
  void WATCHLIST_TIMEFRAME_FOR_INDICATOR;

  const value = useMemo<RiskDeskState>(
    () => ({
      ...data,
      ready,
      backendReachable,
      wsConnected,
      latencyMs: 14,
      instrumentSym,
      setInstrumentSym,
      tf,
      setTf,
      armReview,
      submitExecution,
      liveExecutions,
    }),
    [data, ready, backendReachable, wsConnected, instrumentSym, tf, armReview, submitExecution, liveExecutions]
  );

  return <RiskDeskContext.Provider value={value}>{children}</RiskDeskContext.Provider>;
}
