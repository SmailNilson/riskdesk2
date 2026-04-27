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
import type { RolloverStatusResponse, RolloverOiStatus, TradeDecision, TradeSimulationView, PlaybookEvaluation } from '../../lib/api';
import { API_BASE, WS_BASE } from '../../lib/runtimeConfig';
import { RD_MOCK, RiskDeskMock, Review } from './data';
import {
  buildSimStats,
  mapAlert,
  mapBbSeries,
  mapCandles,
  mapCorrelations,
  mapDecisions,
  mapDom,
  mapDxy,
  mapEmaSeries,
  mapFlashCrash,
  mapFootprint,
  mapIbkr,
  mapIndicators,
  mapMicroEvents,
  mapOrderFlowProd,
  mapPlaybookLive,
  mapPortfolio,
  mapPositions,
  mapReview,
  mapRiskAlerts,
  mapRollover,
  mapSimulations,
  mapSmc,
  mapStrategy,
  mapStrategyLayerScores,
  mapStrategyVotes,
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
  // Operator actions
  purgeInstrument: (sym: string) => Promise<{ purged?: number; error?: string }>;
  confirmRollover: (instrument: string, contractMonth: string) => Promise<boolean>;
  refreshSnapshots: () => void;
  // IBKR multi-account
  ibkrAccounts: Array<{ accountId: string; selected: boolean }>;
  selectedAccountId: string | null;
  setSelectedAccountId: (id: string | null) => void;
  // Mentor desk actions
  reanalyzeReview: (
    review: Review,
    overrides: { entry?: number; sl?: number; tp?: number }
  ) => Promise<boolean>;
  snoozeAlert: (key: string, durationSec: number) => Promise<boolean>;
  mutedTimeframes: string[];
  setTimeframeMuted: (tf: string, muted: boolean) => Promise<boolean>;
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
  // Bumping this nonce re-triggers the per-instrument fetch effect on demand
  // (used after a Purge so the dashboard reloads from the freshly backfilled DB).
  const [refreshNonce, setRefreshNonce] = useState(0);
  const [selectedAccountId, setSelectedAccountId] = useState<string | null>(null);
  const [ibkrAccounts, setIbkrAccounts] = useState<Array<{ accountId: string; selected: boolean }>>([]);
  const [mutedTimeframes, setMutedTimeframes] = useState<string[]>([]);

  // Stable refs for handlers that close over mutable state
  const dataRef = useRef(data);
  dataRef.current = data;
  // WS subscriptions are registered once at mount; this ref lets the price
  // handler know which instrument is currently active without re-subscribing.
  const activeInstrumentRef = useRef(instrumentSym);
  activeInstrumentRef.current = instrumentSym;

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
          setIbkrAccounts(
            (s.accounts ?? []).map((a) => ({ accountId: a.id, selected: a.selected }))
          );
          if (s.selectedAccountId) setSelectedAccountId(s.selectedAccountId);
        })
        .catch(() => {})
    );

    // Muted timeframes — let MentorDesk reflect server state on mount.
    tasks.push(
      api
        .getMutedTimeframes()
        .then((tfs) => {
          anyOk = true;
          if (cancelled) return;
          setMutedTimeframes(tfs);
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

    // Rollover status + OI (per-instrument). Combined into rolloverDetails so
    // the UI can show both time-to-expiry and OI crossover signals side by side.
    const rolloverP = Promise.all([
      api.getRolloverStatus().catch(() => null as RolloverStatusResponse | null),
      api.getRolloverOiStatus().catch(() => null as RolloverOiStatus | null),
    ]).then(([status, oi]) => {
      anyOk = true;
      if (cancelled) return;
      setData((prev) => ({ ...prev, rolloverDetails: mapRollover(status, oi) }));
    });
    tasks.push(rolloverP);

    // Recent decisions (last 30) — used by the Review view.
    tasks.push(
      api
        .getRecentDecisions(30)
        .then((rows) => {
          anyOk = true;
          if (cancelled) return;
          setData((prev) => ({ ...prev, decisions: mapDecisions(rows) }));
        })
        .catch(() => {})
    );

    // Recent simulations (last 50) — Phase 2 read model.
    tasks.push(
      api
        .getRecentSimulations(50)
        .then((rows) => {
          anyOk = true;
          if (cancelled) return;
          const sims = mapSimulations(rows);
          setData((prev) => ({ ...prev, simulations: sims, simulationStats: buildSimStats(sims) }));
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

  // ─── Refetch portfolio when the trader switches IBKR account ──
  useEffect(() => {
    if (!selectedAccountId) return;
    let cancelled = false;
    Promise.all([
      api.getPortfolioSummary(selectedAccountId).catch(() => null),
      api.getIbkrPortfolio(selectedAccountId).catch(() => null),
    ]).then(([summary, snap]) => {
      if (cancelled) return;
      setData((prev) => ({
        ...prev,
        portfolio: summary ? mapPortfolio(summary, prev.portfolio) : prev.portfolio,
        positions: summary ? mapPositions(summary.openPositions) : prev.positions,
        ibkr: snap ? mapIbkr(snap, prev.ibkr) : prev.ibkr,
      }));
    });
    return () => {
      cancelled = true;
    };
  }, [selectedAccountId]);

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
      const playbookP = api
        .getPlaybook(instrumentSym, tf)
        .catch(() => null as PlaybookEvaluation | null);
      const decisionsP = api
        .getDecisionsByInstrument(instrumentSym, 30)
        .catch(() => [] as TradeDecision[]);
      const simsByInstrP = api
        .getSimulationsByInstrument(instrumentSym, 30)
        .catch(() => [] as TradeSimulationView[]);

      const [bars, ind, series, fp, depth, flash, mom, ice, abs, spf, dist, cyc, strat, playbook, decisions, simsByInstr] = await Promise.all([
        candlesP, indP, seriesP, fpP, depthP, flashP, momP, iceP, absP, spfP, distP, cycP, stratP, playbookP, decisionsP, simsByInstrP,
      ]);
      if (cancelled) return;

      setData((prev) => {
        const candles = bars.length ? mapCandles(bars) : prev.candles;
        const indicators = ind ? mapIndicators(ind, prev.indicators) : prev.indicators;
        const ema9 = mapEmaSeries(series?.ema9, candles, prev.ema9);
        const ema50 = mapEmaSeries(series?.ema50, candles, prev.ema50);
        const ema200 = mapEmaSeries(series?.ema200, candles, prev.ema200);
        const bb = mapBbSeries(series?.bollingerBands, candles, {
          upper: prev.bbUpper,
          lower: prev.bbLower,
          basis: prev.bbBasis,
        });
        const smc = ind ? mapSmc(ind, candles, prev.smc) : prev.smc;
        const mappedFp = fp && isFootprintBar(fp) ? mapFootprint(fp) : null;
        // Don't fall back to prev.footprint — that's MCL crude mock data and
        // would make MNQ/MGC/E6 tabs show MCL prices in the footprint matrix.
        const footprint = mappedFp ?? [];
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
        const strategyVotes = strat ? mapStrategyVotes(strat.votes) : prev.strategyVotes;
        const strategyLayerScores = strat
          ? mapStrategyLayerScores(strat.layerScores)
          : prev.strategyLayerScores;
        const strategyFinalScore = strat?.finalScore ?? prev.strategyFinalScore;
        const strategyVetoReasons = strat?.vetoReasons ?? prev.strategyVetoReasons;
        const playbookLive = mapPlaybookLive(playbook);
        const decisionsMapped = decisions.length ? mapDecisions(decisions) : prev.decisions;
        const sims = simsByInstr.length ? mapSimulations(simsByInstr) : prev.simulations;
        const simStats = simsByInstr.length ? buildSimStats(sims) : prev.simulationStats;
        return {
          ...prev,
          candles,
          indicators,
          ema9,
          ema50,
          ema200,
          bbUpper: bb.upper,
          bbLower: bb.lower,
          bbBasis: bb.basis,
          // No per-instrument backend tape feed yet — clear the (mock-only)
          // tape + cvd so we don't show MCL crude prices on MNQ/MGC/E6 tabs.
          orderFlow: [],
          cvd: [],
          smc,
          footprint,
          dom,
          flashCrash,
          microEvents,
          orderflowProd,
          strategy,
          strategyVotes,
          strategyLayerScores,
          strategyFinalScore,
          strategyVetoReasons,
          playbookLive,
          decisions: decisionsMapped,
          simulations: sims,
          simulationStats: simStats,
        };
      });
    })();

    return () => {
      cancelled = true;
    };
  }, [instrumentSym, tf, refreshNonce]);

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
            setData((prev) => {
              // Patch the last candle for the active instrument so the chart's
              // price reflects live ticks (close + bounded high/low). Without
              // this the chart freezes on whatever the last candle backfill
              // returned and the OHLC label never moves.
              let candles = prev.candles;
              if (update.instrument === activeInstrumentRef.current && candles.length) {
                const last = candles[candles.length - 1];
                candles = [
                  ...candles.slice(0, -1),
                  {
                    ...last,
                    close: update.price,
                    high: Math.max(last.high, update.price),
                    low: Math.min(last.low, update.price),
                  },
                ];
              }
              return {
                ...prev,
                candles,
                instruments: prev.instruments.map((i) =>
                  i.sym === update.instrument ? { ...i, last: update.price, px: update.price } : i
                ),
                watchlist: prev.watchlist.map((w) =>
                  w.sym === update.instrument ? { ...w, px: update.price } : w
                ),
              };
            });
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

  // Purge all candles for an instrument across timeframes and trigger backfill.
  // The backend will re-pull from IBKR; the dashboard refreshes the per-instrument
  // slice once we bump the nonce.
  const purgeInstrument = useCallback(
    async (sym: string): Promise<{ purged?: number; error?: string }> => {
      try {
        const purgeRes = await api.purgeInstrument(sym);
        if (purgeRes.error) return { error: purgeRes.error };
        await api.refreshDb().catch(() => {});
        // Bump after a short delay so the backend has time to start backfill.
        setTimeout(() => setRefreshNonce((n) => n + 1), 1500);
        return { purged: purgeRes.purged ?? 0 };
      } catch (e) {
        return { error: String((e as Error)?.message || e) };
      }
    },
    []
  );

  const confirmRolloverAction = useCallback(
    async (instrument: string, contractMonth: string): Promise<boolean> => {
      try {
        await api.confirmRollover(instrument, contractMonth);
        // Refresh rollover details after confirm.
        const [status, oi] = await Promise.all([
          api.getRolloverStatus().catch(() => null),
          api.getRolloverOiStatus().catch(() => null),
        ]);
        setData((prev) => ({ ...prev, rolloverDetails: mapRollover(status, oi) }));
        return true;
      } catch (e) {
        console.error('confirmRollover failed', e);
        return false;
      }
    },
    []
  );

  const refreshSnapshots = useCallback(() => {
    setRefreshNonce((n) => n + 1);
  }, []);

  // Reanalyze a Mentor review with optional entry/SL/TP overrides. The backend
  // creates a new revision keyed by alertKey + revision; we let the WS push the
  // updated review row instead of mutating local state here.
  const reanalyzeReview = useCallback(
    async (
      review: Review,
      overrides: { entry?: number; sl?: number; tp?: number }
    ): Promise<boolean> => {
      try {
        await api.reanalyzeMentorAlert({
          severity: review.severity ?? 'INFO',
          category: review.category ?? review.confluence?.[0] ?? 'MANUAL_REANALYSIS',
          message: review.message ?? review.rationale ?? '',
          instrument: review.sym,
          timestamp: review.triggeredAt ?? new Date().toISOString(),
          entryPrice: overrides.entry,
          stopLoss: overrides.sl,
          takeProfit: overrides.tp,
        });
        return true;
      } catch (err) {
        console.error('reanalyzeReview failed', err);
        return false;
      }
    },
    []
  );

  const snoozeAlertAction = useCallback(
    async (key: string, durationSec: number): Promise<boolean> => {
      try {
        await api.snoozeAlert({ key, durationSeconds: durationSec });
        return true;
      } catch (err) {
        console.error('snoozeAlert failed', err);
        return false;
      }
    },
    []
  );

  const setTimeframeMutedAction = useCallback(
    async (tfKey: string, muted: boolean): Promise<boolean> => {
      try {
        await api.setTimeframeMuted(tfKey, muted);
        setMutedTimeframes((prev) => {
          const has = prev.includes(tfKey);
          if (muted && !has) return [...prev, tfKey];
          if (!muted && has) return prev.filter((t) => t !== tfKey);
          return prev;
        });
        return true;
      } catch (err) {
        console.error('setTimeframeMuted failed', err);
        return false;
      }
    },
    []
  );

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
      purgeInstrument,
      confirmRollover: confirmRolloverAction,
      refreshSnapshots,
      ibkrAccounts,
      selectedAccountId,
      setSelectedAccountId,
      reanalyzeReview,
      snoozeAlert: snoozeAlertAction,
      mutedTimeframes,
      setTimeframeMuted: setTimeframeMutedAction,
    }),
    [
      data,
      ready,
      backendReachable,
      wsConnected,
      instrumentSym,
      tf,
      armReview,
      submitExecution,
      liveExecutions,
      purgeInstrument,
      confirmRolloverAction,
      refreshSnapshots,
      ibkrAccounts,
      selectedAccountId,
      reanalyzeReview,
      snoozeAlertAction,
      mutedTimeframes,
      setTimeframeMutedAction,
    ]
  );

  return <RiskDeskContext.Provider value={value}>{children}</RiskDeskContext.Provider>;
}
