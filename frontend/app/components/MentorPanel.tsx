'use client';

import { ReactNode, useEffect, useRef, useState } from 'react';
import { AlertMessage, PriceUpdate } from '@/app/hooks/useWebSocket';
import {
  api,
  CandleBar,
  IndicatorSeriesSnapshot,
  IndicatorSnapshot,
  MentorAnalyzeResponse,
  MentorIntermarketSnapshot,
  PortfolioSummary,
} from '@/app/lib/api';

type Instrument = 'MCL' | 'MGC' | 'E6' | 'MNQ';
type Timeframe = '5m' | '10m' | '1h' | '1d';

type TzEntry = {
  label: string;
  tz: string;
};

type TradeAction = 'LONG' | 'SHORT';

const ASSET_ALIAS: Record<Instrument, string> = {
  MCL: 'CL1!',
  MGC: 'MGC1!',
  E6: '6E1!',
  MNQ: 'MNQ1!',
};

export default function MentorPanel({
  instrument,
  timeframe,
  timezone,
  connected,
  summary,
  snapshot,
  prices,
  alerts,
}: {
  instrument: Instrument;
  timeframe: Timeframe;
  timezone: TzEntry;
  connected: boolean;
  summary: PortfolioSummary | null;
  snapshot: IndicatorSnapshot | null;
  prices: Record<string, PriceUpdate>;
  alerts: AlertMessage[];
}) {
  const livePrice = prices[instrument]?.price ?? null;
  const matchingPosition = summary?.openPositions.find(position => position.instrument === instrument && position.open);

  const [action, setAction] = useState<TradeAction>('LONG');
  const [entryPrice, setEntryPrice] = useState('');
  const [stopLoss, setStopLoss] = useState('');
  const [takeProfit, setTakeProfit] = useState('');
  const [isMarketOrder, setIsMarketOrder] = useState(true);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<MentorAnalyzeResponse | null>(null);
  const hydratedFormKeyRef = useRef('');

  useEffect(() => {
    const formSeedKey = [
      instrument,
      matchingPosition?.id ?? 'none',
      matchingPosition?.side ?? 'none',
      matchingPosition?.entryPrice ?? 'none',
      matchingPosition?.stopLoss ?? 'none',
      matchingPosition?.takeProfit ?? 'none',
    ].join(':');

    if (hydratedFormKeyRef.current === formSeedKey) {
      return;
    }

    hydratedFormKeyRef.current = formSeedKey;
    setAction((matchingPosition?.side as TradeAction | undefined) ?? 'LONG');
    setEntryPrice(
      matchingPosition?.entryPrice != null
        ? String(round(matchingPosition.entryPrice, instrument))
        : livePrice != null
          ? String(round(livePrice, instrument))
          : ''
    );
    setStopLoss(matchingPosition?.stopLoss != null ? String(round(matchingPosition.stopLoss, instrument)) : '');
    setTakeProfit(matchingPosition?.takeProfit != null ? String(round(matchingPosition.takeProfit, instrument)) : '');
  }, [instrument, matchingPosition?.id, matchingPosition?.side, matchingPosition?.entryPrice, matchingPosition?.stopLoss, matchingPosition?.takeProfit, livePrice]);

  const analyze = async () => {
    if (!snapshot) {
      setError('Indicators not loaded yet.');
      return;
    }
    const parsedEntry = parseOptionalNumber(entryPrice);
    const parsedStop = parseOptionalNumber(stopLoss);
    const parsedTakeProfit = parseOptionalNumber(takeProfit);

    setLoading(true);
    setError(null);
    try {
      await api.refreshMentorContext(instrument, timeframe).catch(() => null);

      const [livePriceView, freshSummary, freshAlerts, freshSnapshot, indicatorSeries, h1Snapshot, candles, intermarket] = await Promise.all([
        api.getLivePrice(instrument).catch(() => null),
        api.getPortfolioSummary().catch(() => summary),
        api.getRecentAlerts().catch(() => alerts),
        api.getIndicators(instrument, timeframe),
        api.getIndicatorSeries(instrument, timeframe, 500),
        api.getIndicators(instrument, '1h'),
        api.getCandles(instrument, timeframe, 120),
        api.getMentorIntermarket(),
      ]);

      const effectiveSnapshot = freshSnapshot ?? snapshot;
      if (!effectiveSnapshot) {
        setError('Fresh indicators are not available yet.');
        setLoading(false);
        return;
      }

      const resolvedPrices =
        livePriceView && shouldUseLivePriceView(livePriceView, prices[instrument])
          ? {
              [instrument]: {
                instrument,
                displayName: instrument,
                price: livePriceView.price,
                timestamp: livePriceView.timestamp,
              },
            }
          : prices;

      const payload = buildMentorPayload({
        instrument,
        timeframe,
        timezone,
        connected,
        summary: freshSummary,
        snapshot: effectiveSnapshot,
        h1Snapshot,
        indicatorSeries,
        candles,
        intermarket,
        prices: resolvedPrices,
        alerts: freshAlerts as AlertMessage[],
        tradeIntention: {
          action,
          entryPrice: parsedEntry,
          stopLoss: parsedStop,
          takeProfit: parsedTakeProfit,
          isMarketOrder,
        },
      });

      setResult(await api.analyzeMentor(payload));
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Mentor analysis failed.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900/80 p-3">
      <div className="mb-3 flex items-center justify-between gap-3">
        <div>
          <div className="text-[11px] font-bold uppercase tracking-widest text-zinc-400">Mentor AI</div>
          <div className="text-[10px] text-zinc-600">Audit de setup ou de trade avec payload réel RiskDesk</div>
        </div>
        <button
          onClick={analyze}
          disabled={loading || !snapshot}
          className="rounded bg-cyan-700 px-3 py-1 text-[11px] font-semibold text-white transition-colors hover:bg-cyan-600 disabled:bg-zinc-700 disabled:text-zinc-500"
        >
          {loading ? 'Analyzing...' : 'Ask Mentor'}
        </button>
      </div>

      <div className="mb-3 grid grid-cols-6 gap-2 text-[11px]">
        <button
          onClick={() => setAction('LONG')}
          className={`rounded px-2 py-1 font-semibold ${action === 'LONG' ? 'bg-emerald-700 text-white' : 'bg-zinc-800 text-zinc-400'}`}
        >
          LONG
        </button>
        <button
          onClick={() => setAction('SHORT')}
          className={`rounded px-2 py-1 font-semibold ${action === 'SHORT' ? 'bg-red-700 text-white' : 'bg-zinc-800 text-zinc-400'}`}
        >
          SHORT
        </button>
        <Field label="Entry (opt)" value={entryPrice} onChange={setEntryPrice} />
        <Field label="SL (opt)" value={stopLoss} onChange={setStopLoss} />
        <Field label="TP (opt)" value={takeProfit} onChange={setTakeProfit} />
        <button
          onClick={() => setIsMarketOrder(v => !v)}
          className={`rounded px-2 py-1 font-semibold ${isMarketOrder ? 'bg-blue-700 text-white' : 'bg-zinc-800 text-zinc-400'}`}
        >
          {isMarketOrder ? 'Market' : 'Limit'}
        </button>
      </div>

      <div className="mb-3 text-[10px] text-zinc-500">
        {hasManualPlan(entryPrice, stopLoss, takeProfit)
          ? 'Mode actuel: Trade Audit (plan renseigné)'
          : 'Mode actuel: Setup Review (le mentor doit proposer Entry / SL / TP si le setup est valide)'}
      </div>

      {error && (
        <div className="mb-3 rounded border border-red-900/50 bg-red-950/30 px-3 py-2 text-[10px] text-red-300">
          {error}
        </div>
      )}

      {result && (
        <div className="space-y-3 rounded border border-zinc-800 bg-zinc-950/50 p-3">
          <div className="flex items-center justify-between gap-2">
            <div className="text-[10px] text-zinc-500">Model: {result.model}</div>
            <div className="text-[10px] text-zinc-500">Audit #{result.auditId ?? 'n/a'}</div>
          </div>

          <Section title="Analyse Technique Rapide">
            <p className="text-[11px] text-zinc-200">{result.analysis.technicalQuickAnalysis}</p>
          </Section>

          <Section title="Points Forts">
            <BulletList items={result.analysis.strengths} emptyLabel="Aucun point fort explicite." color="text-emerald-300" />
          </Section>

          <Section title="Erreurs / Violations">
            <BulletList items={result.analysis.errors} emptyLabel="Aucune violation explicite." color="text-red-300" />
          </Section>

          <Section title="Verdict Final">
            <div className={`inline-flex rounded px-2 py-1 text-[11px] font-semibold ${
              result.analysis.verdict?.includes('Validé')
                ? 'bg-emerald-950/70 text-emerald-300'
                : 'bg-red-950/70 text-red-300'
            }`}>
              {result.analysis.verdict}
            </div>
          </Section>

          <Section title="Conseil d'Amélioration">
            <p className="text-[11px] text-zinc-200">{result.analysis.improvementTip}</p>
          </Section>

          <Section title="Plan Proposé">
            {result.analysis.proposedTradePlan ? (
              <div className="grid grid-cols-4 gap-2 text-[11px]">
                <PlanCell label="Entry" value={result.analysis.proposedTradePlan.entryPrice} />
                <PlanCell label="SL" value={result.analysis.proposedTradePlan.stopLoss} />
                <PlanCell label="TP" value={result.analysis.proposedTradePlan.takeProfit} />
                <PlanCell label="R:R" value={result.analysis.proposedTradePlan.rewardToRiskRatio} />
                <div className="col-span-4 rounded border border-zinc-800 bg-zinc-950/40 px-2 py-2 text-zinc-300">
                  {result.analysis.proposedTradePlan.rationale ?? 'Aucune justification fournie.'}
                </div>
              </div>
            ) : (
              <div className="text-[11px] text-zinc-500">Aucun plan proposé par le mentor pour ce setup.</div>
            )}
          </Section>

          <Section title="Mémoire Similaire">
            {result.similarAudits?.length ? (
              <div className="space-y-2">
                {result.similarAudits.map(match => (
                  <div key={match.auditId} className="rounded border border-zinc-800 bg-zinc-950/40 p-2 text-[10px] text-zinc-300">
                    <div className="mb-1 flex items-center justify-between gap-2">
                      <span className="font-semibold text-zinc-200">
                        Audit #{match.auditId} · {match.instrument} · {match.action} · {match.timeframe}
                      </span>
                      <span className="text-cyan-300">{Math.round(match.similarity * 100)}% similaire</span>
                    </div>
                    <div className="mb-1 text-zinc-500">{new Date(match.createdAt).toLocaleString()}</div>
                    <div className="mb-1 text-zinc-400">{match.summary}</div>
                    <div className={match.verdict?.includes('Validé') ? 'text-emerald-300' : 'text-red-300'}>
                      {match.verdict}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="text-[11px] text-zinc-500">Aucun audit similaire mémorisé pour l’instant.</div>
            )}
          </Section>

          <Section title="Payload Envoyé">
            <pre className="max-h-72 overflow-auto rounded border border-zinc-800 bg-zinc-950/40 p-2 text-[10px] text-zinc-400">
              {JSON.stringify(result.payload, null, 2)}
            </pre>
          </Section>
        </div>
      )}
    </div>
  );
}

function Field({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-[9px] uppercase tracking-wider text-zinc-500">{label}</span>
      <input
        value={value}
        onChange={e => onChange(e.target.value)}
        className="rounded border border-zinc-800 bg-zinc-950 px-2 py-1 text-[11px] text-zinc-200 outline-none"
      />
    </label>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <div className="mb-1 text-[10px] font-bold uppercase tracking-widest text-zinc-500">{title}</div>
      {children}
    </div>
  );
}

function BulletList({ items, emptyLabel, color }: { items: string[]; emptyLabel: string; color: string }) {
  if (!items || items.length === 0) {
    return <div className="text-[11px] text-zinc-500">{emptyLabel}</div>;
  }
  return (
    <ul className={`space-y-1 text-[11px] ${color}`}>
      {items.map(item => (
        <li key={item}>• {item}</li>
      ))}
    </ul>
  );
}

function buildMentorPayload(params: {
  instrument: Instrument;
  timeframe: Timeframe;
  timezone: TzEntry;
  connected: boolean;
  summary: PortfolioSummary | null;
  snapshot: IndicatorSnapshot;
  h1Snapshot: IndicatorSnapshot;
  indicatorSeries: IndicatorSeriesSnapshot;
  candles: CandleBar[];
  intermarket: MentorIntermarketSnapshot;
  prices: Record<string, PriceUpdate>;
  alerts: AlertMessage[];
  tradeIntention: {
    action: TradeAction;
    entryPrice: number | null;
    stopLoss: number | null;
    takeProfit: number | null;
    isMarketOrder: boolean;
  };
}) {
  const lastClose = params.candles.length > 0 ? params.candles[params.candles.length - 1]?.close ?? null : null;
  const currentPrice = params.prices[params.instrument]?.price ?? lastClose ?? params.tradeIntention.entryPrice ?? null;
  const effectiveEntryPrice = params.tradeIntention.entryPrice ?? currentPrice;
  const timestamp = params.prices[params.instrument]?.timestamp ?? new Date().toISOString();
  const atr = computeAtr(params.candles, 14, params.instrument);
  const referencePrice = currentPrice ?? params.snapshot.vwap ?? params.snapshot.ema50 ?? 0;
  const nearestSupport = findNearestOrderBlock(params.snapshot, referencePrice, 'BULLISH');
  const nearestResistance = findNearestOrderBlock(params.snapshot, referencePrice, 'BEARISH');
  const hasPlan = effectiveEntryPrice != null && params.tradeIntention.stopLoss != null && params.tradeIntention.takeProfit != null;
  const stopLossSize = hasPlan ? Math.abs(effectiveEntryPrice! - params.tradeIntention.stopLoss!) : null;
  const reward = hasPlan ? Math.abs(params.tradeIntention.takeProfit! - effectiveEntryPrice!) : null;
  const risk = stopLossSize && stopLossSize > 0 ? stopLossSize : null;

  return {
    metadata: {
      timestamp,
      asset: ASSET_ALIAS[params.instrument],
      current_price: currentPrice != null ? round(currentPrice, params.instrument) : null,
      timeframe_focus: toMentorTimeframe(params.timeframe),
      market_session: inferMarketSession(timestamp),
      dashboard_connection_status: params.connected ? 'LIVE' : 'DISCONNECTED',
      selected_timezone: params.timezone.tz,
    },
    trade_intention: {
      action: params.tradeIntention.action,
      analysis_mode: hasPlan ? 'TRADE_AUDIT' : 'SETUP_REVIEW',
      entry_price: effectiveEntryPrice != null ? round(effectiveEntryPrice, params.instrument) : null,
      stop_loss: params.tradeIntention.stopLoss != null ? round(params.tradeIntention.stopLoss, params.instrument) : null,
      take_profit: params.tradeIntention.takeProfit != null ? round(params.tradeIntention.takeProfit, params.instrument) : null,
      time_to_candle_close_seconds: timeToCandleCloseSeconds(params.timeframe, timestamp),
      is_market_order: params.tradeIntention.isMarketOrder,
      mentor_should_propose_plan: !hasPlan,
    },
    market_structure_the_king: {
      trend_H1: params.h1Snapshot.marketStructureTrend,
      trend_focus: params.snapshot.marketStructureTrend,
      focus_timeframe: toMentorTimeframe(params.timeframe),
      last_event: params.snapshot.lastBreakType,
      last_event_price: params.snapshot.recentBreaks[0]?.level ?? null,
      nearest_support_ob: nearestSupport,
      nearest_resistance_ob: nearestResistance,
      key_psychological_level_proximity: nearestPsychologicalLevel(currentPrice, params.instrument),
    },
    dynamic_levels_and_vwap: {
      vwap_value: params.snapshot.vwap,
      distance_to_vwap_points: currentPrice != null && params.snapshot.vwap != null ? round(Math.abs(currentPrice - params.snapshot.vwap), params.instrument) : null,
      ma_fast_red_value: params.snapshot.ema50,
      ma_slow_blue_value: params.snapshot.ema200,
      distance_to_ma_slow_points: currentPrice != null && params.snapshot.ema200 != null ? round(Math.abs(currentPrice - params.snapshot.ema200), params.instrument) : null,
    },
    momentum_and_flow_the_trigger: {
      money_flow_state: inferMoneyFlowState(params.snapshot),
      money_flow_trend: inferMoneyFlowTrend(params.snapshot),
      oscillator_value: params.snapshot.rsi,
      oscillator_signal: params.snapshot.rsiSignal,
      divergence_detected: false,
      divergence_type: null,
    },
    intermarket_correlations_the_edge: {
      dxy_pct_change: params.intermarket.dxyPctChange,
      silver_si1_pct_change: params.intermarket.silverSi1PctChange,
      gold_mgc1_pct_change: params.intermarket.goldMgc1PctChange,
      plat_pl1_pct_change: params.intermarket.platPl1PctChange,
      metals_convergence_status: params.intermarket.metalsConvergenceStatus,
    },
    risk_and_emotional_check: {
      current_atr_focus: atr,
      stop_loss_size_points: stopLossSize != null ? round(stopLossSize, params.instrument) : null,
      reward_to_risk_ratio: risk && reward != null ? round(reward / risk, params.instrument) : null,
      is_sl_structurally_protected: params.tradeIntention.stopLoss != null
        ? isStructurallyProtected(params.tradeIntention.action, params.tradeIntention.stopLoss, nearestSupport, nearestResistance, params.snapshot)
        : null,
      price_extension_warning: currentPrice != null ? isPriceExtended(currentPrice, params.snapshot, atr) : false,
    },
    riskdesk_context: {
      total_unrealized_pnl: params.summary?.totalUnrealizedPnL ?? null,
      today_realized_pnl: params.summary?.todayRealizedPnL ?? null,
      margin_used_pct: params.summary?.marginUsedPct ?? null,
      active_signals: [
        params.snapshot.rsiSignal,
        params.snapshot.macdCrossover,
        params.snapshot.wtSignal,
        params.snapshot.wtCrossover,
        params.snapshot.bbTrendSignal,
      ].filter(Boolean),
      recent_alerts: params.alerts
        .filter(alert => alert.instrument == null || alert.instrument === params.instrument)
        .slice(0, 8),
      chart_series_summary: {
        candles_loaded: params.candles.length,
        wave_trend_points: params.indicatorSeries.waveTrend.length,
      },
    },
  };
}

function hasManualPlan(entryPrice: string, stopLoss: string, takeProfit: string) {
  return parseOptionalNumber(entryPrice) != null
    && parseOptionalNumber(stopLoss) != null
    && parseOptionalNumber(takeProfit) != null;
}

function parseOptionalNumber(value: string) {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

function findNearestOrderBlock(snapshot: IndicatorSnapshot, currentPrice: number, bias: 'BULLISH' | 'BEARISH') {
  const filtered = snapshot.activeOrderBlocks.filter(block =>
    bias === 'BULLISH' ? block.mid <= currentPrice : block.mid >= currentPrice
  );
  if (filtered.length > 0) {
    const nearest = filtered.sort((a, b) => Math.abs(a.mid - currentPrice) - Math.abs(b.mid - currentPrice))[0];
    return {
      type: bias === 'BULLISH' ? 'DEMAND' : 'SUPPLY',
      price_top: nearest.high,
      price_bottom: nearest.low,
      is_tested: false,
    };
  }

  const structuralLevel = bias === 'BULLISH'
    ? pickNearestLevel(currentPrice, [snapshot.weakLow, snapshot.strongLow], 'below')
    : pickNearestLevel(currentPrice, [snapshot.weakHigh, snapshot.strongHigh], 'above');
  if (structuralLevel == null) {
    return null;
  }
  const zonePadding = Math.max(Math.abs(currentPrice) * 0.0005, 0.0005);
  return {
    type: bias === 'BULLISH' ? 'DEMAND_SWING' : 'SUPPLY_SWING',
    price_top: bias === 'BULLISH' ? structuralLevel + zonePadding : structuralLevel,
    price_bottom: bias === 'BULLISH' ? structuralLevel : structuralLevel - zonePadding,
    is_tested: false,
  };
}

function pickNearestLevel(currentPrice: number, levels: Array<number | null>, direction: 'below' | 'above') {
  const filtered = levels.filter((level): level is number =>
    level != null && (direction === 'below' ? level <= currentPrice : level >= currentPrice)
  );
  if (filtered.length === 0) {
    return null;
  }
  return filtered.sort((a, b) => Math.abs(a - currentPrice) - Math.abs(b - currentPrice))[0];
}

function computeAtr(candles: CandleBar[], period: number, instrument: Instrument) {
  if (candles.length < period + 1) {
    return null;
  }
  const trs: number[] = [];
  for (let i = 1; i < candles.length; i += 1) {
    const current = candles[i];
    const previous = candles[i - 1];
    const tr = Math.max(
      current.high - current.low,
      Math.abs(current.high - previous.close),
      Math.abs(current.low - previous.close)
    );
    trs.push(tr);
  }
  const last = trs.slice(-period);
  const avg = last.reduce((sum, value) => sum + value, 0) / last.length;
  return avg > 0 ? round(avg, instrument) : null;
}

function inferMoneyFlowState(snapshot: IndicatorSnapshot) {
  if (snapshot.cmf == null && snapshot.buyRatio == null) {
    return 'UNAVAILABLE';
  }
  if ((snapshot.cmf ?? 0) > 0 || (snapshot.buyRatio ?? 0) >= 0.5) {
    return 'GREEN';
  }
  if ((snapshot.cmf ?? 0) < 0 || (snapshot.buyRatio ?? 0) < 0.5) {
    return 'RED';
  }
  return 'NEUTRAL';
}

function PlanCell({ label, value }: { label: string; value: number | null }) {
  return (
    <div className="rounded border border-zinc-800 bg-zinc-950/40 px-2 py-2">
      <div className="text-[9px] uppercase tracking-wider text-zinc-500">{label}</div>
      <div className="text-[11px] font-semibold text-zinc-200">{value ?? 'n/a'}</div>
    </div>
  );
}

function inferMoneyFlowTrend(snapshot: IndicatorSnapshot) {
  if (snapshot.deltaFlowBias === 'BULLISH') return 'INCREASING';
  if (snapshot.deltaFlowBias === 'BEARISH') return 'DECREASING';
  return 'FLAT';
}

function isStructurallyProtected(
  action: TradeAction,
  stopLoss: number,
  nearestSupport: { price_bottom: number } | null,
  nearestResistance: { price_top: number } | null,
  snapshot: IndicatorSnapshot
) {
  if (action === 'LONG') {
    return (
      (nearestSupport != null && stopLoss <= nearestSupport.price_bottom) ||
      (snapshot.weakLow != null && stopLoss <= snapshot.weakLow)
    );
  }
  return (
    (nearestResistance != null && stopLoss >= nearestResistance.price_top) ||
    (snapshot.weakHigh != null && stopLoss >= snapshot.weakHigh)
  );
}

function isPriceExtended(currentPrice: number, snapshot: IndicatorSnapshot, atr: number | null) {
  if (snapshot.vwap == null || atr == null) {
    return false;
  }
  return Math.abs(currentPrice - snapshot.vwap) > atr * 1.5;
}

function shouldUseLivePriceView(
  livePriceView: { source: string; timestamp: string },
  websocketPrice?: PriceUpdate
) {
  if (livePriceView.source !== 'FALLBACK_DB') {
    return true;
  }

  if (!websocketPrice?.price) {
    return true;
  }

  const liveTs = Date.parse(livePriceView.timestamp);
  const wsTs = Date.parse(websocketPrice.timestamp);
  if (Number.isNaN(liveTs) || Number.isNaN(wsTs)) {
    return false;
  }

  return liveTs >= wsTs;
}

function nearestPsychologicalLevel(price: number, instrument: Instrument) {
  const step = instrument === 'E6' ? 0.005 : instrument === 'MNQ' ? 100 : instrument === 'MCL' ? 5 : 10;
  return round(Math.round(price / step) * step, instrument);
}

function timeToCandleCloseSeconds(timeframe: Timeframe, timestamp: string) {
  const date = new Date(timestamp);
  const minutes = timeframe === '5m' ? 5 : timeframe === '10m' ? 10 : timeframe === '1h' ? 60 : 1440;
  const ms = date.getTime();
  const bucket = minutes * 60 * 1000;
  const nextClose = Math.ceil(ms / bucket) * bucket;
  return Math.max(0, Math.round((nextClose - ms) / 1000));
}

function inferMarketSession(timestamp: string) {
  const hour = new Date(timestamp).getUTCHours();
  if (hour >= 22 || hour < 6) return 'ASIAN_OPEN';
  if (hour >= 6 && hour < 12) return 'LONDON';
  if (hour >= 12 && hour < 20) return 'NEW_YORK';
  return 'OFF_HOURS';
}

function toMentorTimeframe(timeframe: Timeframe) {
  if (timeframe === '5m') return 'M5';
  if (timeframe === '10m') return 'M10';
  if (timeframe === '1h') return 'H1';
  return 'D1';
}

function round(value: number, instrument: Instrument | 'MGC') {
  const digits = instrument === 'E6' ? 5 : 2;
  return Number(value.toFixed(digits));
}
