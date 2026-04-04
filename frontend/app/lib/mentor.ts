'use client';

import { AlertMessage, PriceUpdate } from '@/app/hooks/useWebSocket';
import type { TzEntry } from '@/app/lib/timezones';
import {
  api,
  CandleBar,
  IndicatorSeriesSnapshot,
  IndicatorSnapshot,
  MentorIntermarketSnapshot,
  MentorSignalReview,
  PortfolioSummary,
} from '@/app/lib/api';

export type Instrument = 'MCL' | 'MGC' | 'E6' | 'MNQ';
export type Timeframe = '5m' | '10m' | '1h' | '1d';
export type TradeAction = 'LONG' | 'SHORT';
export type { TzEntry } from '@/app/lib/timezones';

export type MentorTradeIntention = {
  action: TradeAction;
  entryPrice: number | null;
  stopLoss: number | null;
  takeProfit: number | null;
  isMarketOrder: boolean;
};

export type MentorSignalCandidate = {
  action: TradeAction;
  instrument: Instrument;
  timeframe: Timeframe;
};

const ASSET_ALIAS: Record<Instrument, string> = {
  MCL: 'CL1!',
  MGC: 'MGC1!',
  E6: '6E1!',
  MNQ: 'MNQ1!',
};

function assetClassFor(instrument: Instrument): string | null {
  switch (instrument) {
    case 'MCL': return 'ENERGY';
    case 'MGC': return 'METALS';
    case 'E6': return 'FOREX';
    case 'MNQ': return 'EQUITY_INDEX';
    default: return null;
  }
}

export async function runMentorAnalysis(params: {
  instrument: Instrument;
  timeframe: Timeframe;
  timezone: TzEntry;
  connected: boolean;
  summary: PortfolioSummary | null;
  snapshot: IndicatorSnapshot | null;
  prices: Record<string, PriceUpdate>;
  alerts: AlertMessage[];
  includePortfolioContext: boolean;
  tradeIntention: MentorTradeIntention;
}) {
  const [, livePriceView, freshSummary, freshAlerts, freshSnapshot, indicatorSeries, h1Snapshot, candles, intermarket] = await Promise.all([
    api.refreshMentorContext(params.instrument, params.timeframe).catch(() => null),
    api.getLivePrice(params.instrument).catch(() => null),
    params.includePortfolioContext
      ? api.getPortfolioSummary().catch(() => params.summary)
      : Promise.resolve(params.summary),
    api.getRecentAlerts().catch(() => params.alerts),
    api.getIndicators(params.instrument, params.timeframe).catch(() => params.snapshot),
    api.getIndicatorSeries(params.instrument, params.timeframe, 500),
    api.getIndicators(params.instrument, '1h'),
    api.getCandles(params.instrument, params.timeframe, 120),
    api.getMentorIntermarket(params.instrument),
  ]);

  const effectiveSnapshot = freshSnapshot ?? params.snapshot;
  if (!effectiveSnapshot) {
    throw new Error('Fresh indicators are not available yet.');
  }

  const resolvedPrices =
    livePriceView && shouldUseLivePriceView(livePriceView, params.prices[params.instrument])
      ? {
          [params.instrument]: {
            instrument: params.instrument,
            displayName: params.instrument,
            price: livePriceView.price,
            timestamp: livePriceView.timestamp,
          },
        }
      : params.prices;

  const payload = buildMentorPayload({
    instrument: params.instrument,
    timeframe: params.timeframe,
    timezone: params.timezone,
    connected: params.connected,
    summary: freshSummary,
    snapshot: effectiveSnapshot,
    h1Snapshot,
    indicatorSeries,
    candles,
    intermarket,
    prices: resolvedPrices,
    alerts: freshAlerts as AlertMessage[],
    includePortfolioContext: params.includePortfolioContext,
    tradeIntention: params.tradeIntention,
  });

  const response = await api.analyzeMentor(payload);
  return { payload, response };
}

export function isBehaviourReview(review: MentorSignalReview): boolean {
  return (review.sourceType ?? 'SIGNAL') === 'BEHAVIOUR';
}

export function isSignalReview(review: MentorSignalReview): boolean {
  return (review.sourceType ?? 'SIGNAL') === 'SIGNAL';
}

export function isMentorEligibleAlert(alert: AlertMessage) {
  if (!alert.instrument || !isSupportedInstrument(alert.instrument)) {
    return false;
  }

  if (alert.category === 'SMC') {
    return alert.message.includes('BOS') || alert.message.includes('CHoCH');
  }
  if (alert.category === 'MACD') {
    return alert.message.includes('Bullish Cross') || alert.message.includes('Bearish Cross');
  }
  if (alert.category === 'WAVETREND') {
    return ['Bullish Cross', 'Bearish Cross', 'oversold', 'overbought'].some(fragment => alert.message.includes(fragment));
  }
  if (alert.category === 'RSI') {
    return alert.message.includes('oversold') || alert.message.includes('overbought');
  }
  if (alert.category === 'ORDER_BLOCK' || alert.category === 'ORDER_BLOCK_VWAP') {
    return ['mitigated', 'invalidated', 'VWAP inside'].some(fragment => alert.message.includes(fragment));
  }
  return false;
}

export function deriveMentorSignalCandidate(alert: AlertMessage): MentorSignalCandidate | null {
  if (!isMentorEligibleAlert(alert) || !alert.instrument || !isSupportedInstrument(alert.instrument)) {
    return null;
  }

  const action = inferTradeActionFromAlert(alert);
  if (!action) {
    return null;
  }

  return {
    action,
    instrument: alert.instrument,
    timeframe: parseAlertTimeframe(alert.message) ?? '10m',
  };
}

// Canonical key function — used both to index threadsByAlertKey and to look it up.
// MUST be the single source of truth: never build this key inline elsewhere.
// Accepts AlertMessage or any object with the same shape (e.g. MentorSignalReview).
export function buildMentorAlertKey(alert: { timestamp: string; instrument: string | null | undefined; category: string; message: string }) {
  return `${alert.timestamp}:${alert.instrument ?? 'GLOBAL'}:${alert.category}:${alert.message}`;
}

export function buildMentorPayload(params: {
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
  includePortfolioContext: boolean;
  tradeIntention: MentorTradeIntention;
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
  const stopLossSize =
    hasPlan && effectiveEntryPrice != null && params.tradeIntention.stopLoss != null
      ? Math.abs(effectiveEntryPrice - params.tradeIntention.stopLoss)
      : null;
  const reward =
    hasPlan && effectiveEntryPrice != null && params.tradeIntention.takeProfit != null
      ? Math.abs(params.tradeIntention.takeProfit - effectiveEntryPrice)
      : null;
  const risk = stopLossSize && stopLossSize > 0 ? stopLossSize : null;

  return {
    metadata: {
      timestamp,
      asset: ASSET_ALIAS[params.instrument],
      asset_class: assetClassFor(params.instrument),
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
    market_structure_smc: {
      trend_H1: params.h1Snapshot.marketStructureTrend,
      trend_focus: params.snapshot.marketStructureTrend,
      internal_bias: params.snapshot.internalBias,
      swing_bias: params.snapshot.swingBias,
      focus_timeframe: toMentorTimeframe(params.timeframe),
      last_internal_event: params.snapshot.lastInternalBreakType,
      last_swing_event: params.snapshot.lastSwingBreakType,
      last_event: params.snapshot.lastBreakType,
      last_event_price: params.snapshot.recentBreaks[0]?.level ?? null,
      nearest_support_ob: nearestSupport,
      nearest_resistance_ob: nearestResistance,
      key_psychological_level_proximity: nearestPsychologicalLevel(currentPrice, params.instrument),
      pd_array_zone_session: params.snapshot.sessionPdZone ?? null,
      pd_array_zone_structural: params.snapshot.currentZone ?? null,
      liquidity_pools: {
        eqh_present: (params.snapshot.equalHighs?.length ?? 0) > 0,
        eqh_level: params.snapshot.equalHighs?.[0]?.price ?? null,
        eql_present: (params.snapshot.equalLows?.length ?? 0) > 0,
        eql_level: params.snapshot.equalLows?.[0]?.price ?? null,
      },
    },
    dynamic_levels_and_mean_reversion: {
      vwap_value: params.snapshot.vwap,
      distance_to_vwap_points: currentPrice != null && params.snapshot.vwap != null ? round(Math.abs(currentPrice - params.snapshot.vwap), params.instrument) : null,
      ma_fast_red_value: params.snapshot.ema50,
      ma_slow_blue_value: params.snapshot.ema200,
      distance_to_ma_slow_points: currentPrice != null && params.snapshot.ema200 != null ? round(Math.abs(currentPrice - params.snapshot.ema200), params.instrument) : null,
      ema_9_value: params.snapshot.ema9 ?? null,
      ema_50_value: params.snapshot.ema50 ?? null,
      ema_200_value: params.snapshot.ema200 ?? null,
      distance_to_ema_50_points: currentPrice && params.snapshot.ema50
        ? Math.round((currentPrice - params.snapshot.ema50) * 100) / 100
        : null,
      bollinger_state: params.snapshot.bbTrendExpanding ? 'EXPANDING' : (params.snapshot.bbWidth && params.snapshot.bbWidth < 0.02 ? 'SQUEEZE' : 'CONTRACTING'),
    },
    momentum_oscillators: {
      wavetrend_signal: params.snapshot.wtCrossover ?? null,
      wavetrend_is_overbought: params.snapshot.wtWt1 != null ? params.snapshot.wtWt1 > 53 : false,
      wavetrend_is_oversold: params.snapshot.wtWt1 != null ? params.snapshot.wtWt1 < -53 : false,
      rsi_value: params.snapshot.rsi ?? null,
      rsi_signal: params.snapshot.rsiSignal ?? null,
      chaikin_money_flow_cmf: params.snapshot.cmf ?? null,
      money_flow_state: inferMoneyFlowState(params.snapshot),
      money_flow_trend: inferMoneyFlowTrend(params.snapshot),
      oscillator_value: params.snapshot.rsi,
      oscillator_signal: params.snapshot.rsiSignal,
      divergence_detected: false,
      divergence_type: null,
    },
    order_flow_and_volume: {
      delta_flow_current: params.snapshot.deltaFlow ?? null,
      cumulative_delta_trend: params.snapshot.deltaFlowBias ?? null,
      buy_ratio_pct: params.snapshot.buyRatio ?? null,
      delta_divergence_detected: false,
      source: 'CLV_ESTIMATED',
    },
    macro_correlations_dynamic: {
      dxy_pct_change: params.intermarket.dxyPctChange,
      dxy_trend: params.intermarket.dxyTrend,
      silver_si1_pct_change: params.intermarket.silverSi1PctChange,
      gold_mgc1_pct_change: params.intermarket.goldMgc1PctChange,
      plat_pl1_pct_change: params.intermarket.platPl1PctChange,
      metals_convergence_status: params.intermarket.metalsConvergenceStatus,
    },
    risk_management_gatekeeper: {
      current_atr_focus: atr,
      stop_loss_size_points: stopLossSize != null ? round(stopLossSize, params.instrument) : null,
      reward_to_risk_ratio: risk && reward != null ? round(reward / risk, params.instrument) : null,
      is_sl_structurally_protected: params.tradeIntention.stopLoss != null
        ? isStructurallyProtected(params.tradeIntention.action, params.tradeIntention.stopLoss, nearestSupport, nearestResistance, params.snapshot)
        : null,
      price_extension_warning: currentPrice != null ? isPriceExtended(currentPrice, params.snapshot, atr) : false,
    },
    riskdesk_context: {
      portfolio_state_shared: params.includePortfolioContext,
      total_unrealized_pnl: params.includePortfolioContext ? params.summary?.totalUnrealizedPnL ?? null : null,
      today_realized_pnl: params.includePortfolioContext ? params.summary?.todayRealizedPnL ?? null : null,
      margin_used_pct: params.includePortfolioContext ? params.summary?.marginUsedPct ?? null : null,
      active_signals: [
        params.snapshot.rsiSignal,
        params.snapshot.macdCrossover,
        params.snapshot.wtSignal,
        params.snapshot.wtCrossover,
        params.snapshot.bbTrendSignal,
      ].filter(Boolean),
      recent_alerts: selectMentorAlerts(params.alerts, params.instrument, params.includePortfolioContext),
      chart_series_summary: {
        candles_loaded: params.candles.length,
        wave_trend_points: params.indicatorSeries.waveTrend.length,
      },
    },
  };
}

function parseAlertTimeframe(message: string): Timeframe | null {
  const match = message.match(/\[(5m|10m|1h|1d)\]/i);
  if (!match) {
    return null;
  }
  const timeframe = match[1].toLowerCase();
  if (timeframe === '5m' || timeframe === '10m' || timeframe === '1h' || timeframe === '1d') {
    return timeframe;
  }
  return null;
}

function inferTradeActionFromAlert(alert: AlertMessage): TradeAction | null {
  const normalized = alert.message.toUpperCase();
  if (normalized.includes('BULLISH') || normalized.includes('OVERSOLD')) {
    return 'LONG';
  }
  if (normalized.includes('BEARISH') || normalized.includes('OVERBOUGHT')) {
    return 'SHORT';
  }
  return null;
}

function isSupportedInstrument(value: string): value is Instrument {
  return value === 'MCL' || value === 'MGC' || value === 'E6' || value === 'MNQ';
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

function inferMoneyFlowTrend(snapshot: IndicatorSnapshot) {
  if (snapshot.deltaFlowBias === 'BULLISH') return 'INCREASING';
  if (snapshot.deltaFlowBias === 'BEARISH') return 'DECREASING';
  return 'FLAT';
}

function selectMentorAlerts(alerts: AlertMessage[], instrument: Instrument, includePortfolioContext: boolean) {
  return alerts
    .filter(alert => {
      if (alert.instrument !== instrument) {
        return false;
      }
      if (!includePortfolioContext && alert.category === 'RISK') {
        return false;
      }
      return true;
    })
    .slice(0, 8);
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

function nearestPsychologicalLevel(price: number | null, instrument: Instrument) {
  if (price == null) {
    return null;
  }
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
