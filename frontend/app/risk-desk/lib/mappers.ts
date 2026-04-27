// Backend → design type mappers. All mappers must tolerate partial / missing
// fields (RiskDeskContext falls back to mock when a fetch returns null).

import {
  AbsorptionEventHistory,
  AlertPayload,
  CandleBar,
  CorrelationSignal,
  CycleEventHistory,
  DistributionEventHistory,
  DxySnapshotView,
  FairValueGapView,
  FlashCrashStatusView,
  FootprintBar as ApiFootprintBar,
  IbkrPortfolioSnapshot,
  IcebergEventHistory,
  IndicatorSeriesSnapshot,
  IndicatorSnapshot,
  LivePriceView,
  MentorSignalReview as ApiMentorSignalReview,
  MomentumEventHistory,
  OrderBlockView,
  OrderFlowDepthSnapshot,
  PortfolioSummary,
  SpoofingEventHistory,
  StructureBreakView,
} from '../../lib/api';
import {
  AlertItem,
  Candle,
  Correlations,
  Dom,
  DomLevel,
  DxyData,
  FlashCrash,
  FootprintCol,
  FootprintRow,
  FvgSmc,
  Ibkr,
  Indicators,
  LiquiditySmc,
  MicroEvent,
  MicroEvents,
  OrderBlockSmc,
  OrderFlowProd,
  Portfolio,
  Position,
  Review,
  RiskAlert,
  Smc,
  StructureSmc,
  Strategy,
  WatchItem,
} from './data';

// ─── Portfolio + positions ────────────────────────────────────────
export function mapPortfolio(s: PortfolioSummary, fallback: Portfolio): Portfolio {
  return {
    unreal: s.totalUnrealizedPnL ?? fallback.unreal,
    realToday: s.todayRealizedPnL ?? fallback.realToday,
    totalPnL: s.totalPnL ?? fallback.totalPnL,
    exposure: s.totalExposure ?? fallback.exposure,
    margin: s.marginUsedPct ? s.totalExposure * (s.marginUsedPct / 100) : fallback.margin,
    marginPct: (s.marginUsedPct ?? 0) / 100,
    buyingPower: fallback.buyingPower,
    dayDD: Math.min(0, s.todayRealizedPnL ?? 0),
    dayDDLimit: fallback.dayDDLimit,
    weekPnL: fallback.weekPnL,
    monthPnL: fallback.monthPnL,
    exposureCap: fallback.exposureCap,
  };
}

export function mapPositions(positions: PortfolioSummary['openPositions']): Position[] {
  return (positions ?? []).map((p, idx) => {
    const opened = p.openedAt ? new Date(p.openedAt).toLocaleTimeString('en-US', { hour12: false }) : '—';
    return {
      id: p.id != null ? String(p.id) : `p-${idx}`,
      sym: p.instrument,
      side: p.side === 'SHORT' ? 'short' : 'long',
      qty: p.quantity ?? 0,
      entry: p.entryPrice ?? 0,
      sl: p.stopLoss ?? p.entryPrice ?? 0,
      tp1: p.takeProfit ?? p.entryPrice ?? 0,
      tp2: p.takeProfit ?? p.entryPrice ?? 0,
      last: p.currentPrice ?? p.entryPrice ?? 0,
      opened,
      trail: 'live',
      state: 'running',
    };
  });
}

// ─── Watchlist ────────────────────────────────────────────────────
export function mapTickerToWatchItem(p: LivePriceView, prevChg = 0): WatchItem {
  return { sym: p.instrument, px: p.price, chg: prevChg };
}

// ─── DXY ──────────────────────────────────────────────────────────
export function mapDxy(latest: DxySnapshotView, history: DxySnapshotView[], fallback: DxyData): DxyData {
  const chgPct = latest.changePercent ?? fallback.chgPct;
  const chg = latest.baselineValue != null ? latest.dxyValue - latest.baselineValue : fallback.chg;
  const series = history.length ? history.map((h) => h.dxyValue) : fallback.series;
  return {
    px: latest.dxyValue,
    chg,
    chgPct,
    series,
    bias: chgPct < 0 ? 'weakening' : 'strengthening',
    effect: chgPct < 0 ? 'Tailwind for crude / metals / euro longs' : 'Headwind for crude / metals / euro longs',
  };
}

// ─── Indicators ───────────────────────────────────────────────────
export function mapIndicators(s: IndicatorSnapshot, fallback: Indicators): Indicators {
  return {
    ema9: { v: s.ema9 ?? fallback.ema9.v, vs: s.emaCrossover ?? fallback.ema9.vs, strength: 0.7 },
    ema50: { v: s.ema50 ?? fallback.ema50.v, vs: fallback.ema50.vs, strength: 0.7 },
    ema200: { v: s.ema200 ?? fallback.ema200.v, vs: fallback.ema200.vs, strength: 0.7 },
    vwap: {
      v: s.vwap ?? fallback.vwap.v,
      vs: fallback.vwap.vs,
      strength: 0.7,
      dev: fallback.vwap.dev,
    },
    supertrend: {
      dir: s.supertrendBullish ? 'up' : 'down',
      flipped: fallback.supertrend.flipped,
      strength: fallback.supertrend.strength,
    },
    cmf: { v: s.cmf ?? fallback.cmf.v, label: (s.cmf ?? 0) > 0 ? 'buying pressure' : 'selling pressure' },
    rsi: { v: s.rsi ?? fallback.rsi.v, zone: s.rsiSignal ?? fallback.rsi.zone },
    macd: {
      hist: fallback.macd.hist, // backend doesn't expose the histogram series (just current value)
      cross: s.macdCrossover === 'BULLISH' ? 'bull' : s.macdCrossover === 'BEARISH' ? 'bear' : fallback.macd.cross,
    },
    atr: fallback.atr,
    bb: {
      upper: s.bbUpper ?? fallback.bb.upper,
      lower: s.bbLower ?? fallback.bb.lower,
      basis: s.bbMiddle ?? fallback.bb.basis,
      width: s.bbWidth ?? fallback.bb.width,
      squeeze: !s.bbTrendExpanding,
    },
    ichimoku: fallback.ichimoku,
    pivots: fallback.pivots,
  };
}

// ─── Candles + EMA series ────────────────────────────────────────
export function mapCandles(bars: CandleBar[]): Candle[] {
  return bars.map((b) => ({
    time: b.time,
    open: b.open,
    high: b.high,
    low: b.low,
    close: b.close,
    volume: b.volume,
    delta: 0,
  }));
}

/** Project a server-side IndicatorLinePoint series onto the candle index space. */
export function mapEmaSeries(
  points: IndicatorSeriesSnapshot['ema9'] | undefined,
  candles: Candle[],
  fallback: number[]
): number[] {
  if (!points || !points.length || !candles.length) return fallback.slice(0, candles.length);
  // Map ema points to nearest candle by epoch-second time
  const out: number[] = [];
  let pi = 0;
  for (const c of candles) {
    while (pi < points.length - 1 && Math.abs(points[pi + 1].time - c.time) < Math.abs(points[pi].time - c.time)) {
      pi++;
    }
    out.push(points[pi].value);
  }
  return out;
}

// ─── SMC overlays ────────────────────────────────────────────────
// We don't drop overlays whose startTime falls outside the candle window —
// the Chart clamps them to the left edge so live OBs / FVGs / breaks always
// render even when their formation bar is off-screen.
export function mapSmc(snapshot: IndicatorSnapshot, candles: Candle[], fallback: Smc): Smc {
  if (!candles.length) return fallback;
  const firstTime = candles[0].time;
  const lastTime = candles[candles.length - 1].time;
  const clamp = (t: number) => Math.max(firstTime, Math.min(lastTime, t));

  const obs: OrderBlockSmc[] = [
    ...(snapshot.activeOrderBlocks ?? []),
    ...(snapshot.breakerOrderBlocks ?? []),
  ].map((ob: OrderBlockView): OrderBlockSmc => ({
    type: ob.type === 'BULLISH' ? 'bull' : 'bear',
    t1: clamp(ob.startTime),
    t2: clamp(ob.breakerTime ?? ob.startTime),
    hi: ob.high,
    lo: ob.low,
    mit: ob.status !== 'ACTIVE',
    label: `OB·${ob.type === 'BULLISH' ? 'bull' : 'bear'}`,
  }));

  const fvgs: FvgSmc[] = (snapshot.activeFairValueGaps ?? []).map((g: FairValueGapView): FvgSmc => ({
    t1: clamp(g.startTime),
    t2: clamp(g.extensionEndTime || g.startTime),
    hi: g.top,
    lo: g.bottom,
    filled: false,
    label: `FVG ${g.bias === 'BULLISH' ? 'bull' : 'bear'}`,
  }));

  const structure: StructureSmc[] = (snapshot.recentBreaks ?? []).map((b: StructureBreakView): StructureSmc => ({
    t: clamp(b.barTime),
    px: b.level,
    kind: b.type === 'CHOCH' ? 'CHoCH' : 'BOS',
    dir: b.trend === 'BULLISH' ? 'up' : 'down',
  }));

  const liquidity: LiquiditySmc[] = [];
  for (const eq of snapshot.equalHighs ?? []) liquidity.push({ px: eq.price, label: 'BSL', t: lastTime });
  for (const eq of snapshot.equalLows ?? []) liquidity.push({ px: eq.price, label: 'SSL', t: lastTime });

  return obs.length || fvgs.length || structure.length || liquidity.length
    ? { orderBlocks: obs, fvgs, structure, liquidity }
    : fallback;
}

// ─── Reviews ──────────────────────────────────────────────────────
function computeConfidence(r: ApiMentorSignalReview): number {
  // Derived from the same signals the backend uses for eligibility:
  //   - eligibility verdict (anchor +/-)
  //   - strengths vs errors count from the structured Gemini reply
  //   - RR ratio when a trade plan exists (better RR = higher confidence)
  //   - severity of the original alert
  // Result clamped to [0.10, 0.95] so the meter is always informative.
  const elig = r.executionEligibilityStatus;
  const strengths = r.analysis?.analysis?.strengths?.length ?? 0;
  const errors = r.analysis?.analysis?.errors?.length ?? 0;
  const rr = r.analysis?.analysis?.proposedTradePlan?.rewardToRiskRatio ?? null;

  let score = 0.5;
  if (elig === 'ELIGIBLE') score += 0.25;
  else if (elig === 'INELIGIBLE') score -= 0.2;
  else if (elig === 'MENTOR_UNAVAILABLE') score -= 0.3;
  // Strengths and errors push opposite directions, capped so a wall of
  // strengths doesn't max out instantly.
  score += Math.min(0.20, strengths * 0.04);
  score -= Math.min(0.20, errors * 0.05);
  if (rr != null) {
    if (rr >= 2.5) score += 0.10;
    else if (rr >= 1.8) score += 0.05;
    else if (rr < 1) score -= 0.10;
  }
  if (r.severity === 'DANGER') score += 0.05;
  return Math.max(0.10, Math.min(0.95, score));
}

export function mapReview(r: ApiMentorSignalReview): Review {
  const elig = r.executionEligibilityStatus;
  const verdict: Review['verdict'] =
    r.action === 'MONITOR'
      ? 'WATCH'
      : elig === 'ELIGIBLE'
      ? 'TAKE'
      : elig === 'INELIGIBLE'
      ? 'SKIP'
      : 'WATCH';
  const plan = r.analysis?.analysis?.proposedTradePlan;
  const at = new Date(r.createdAt).toLocaleTimeString('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  const confluence = r.analysis?.analysis?.strengths ?? [];
  const risks = r.analysis?.analysis?.errors ?? [];

  return {
    id: String(r.id),
    sym: r.instrument,
    tf: r.timeframe,
    at,
    verdict,
    confidence: computeConfidence(r),
    eligible: elig === 'ELIGIBLE',
    confluence: confluence.length ? confluence : [r.category],
    plan:
      plan && plan.entryPrice != null && plan.stopLoss != null && plan.takeProfit != null
        ? {
            side: r.action === 'SHORT' ? 'short' : 'long',
            entry: plan.entryPrice,
            sl: plan.stopLoss,
            tp1: plan.takeProfit,
            tp2: plan.takeProfit,
            rr1: plan.rewardToRiskRatio ?? 0,
            rr2: plan.rewardToRiskRatio ?? 0,
            qty: 1,
          }
        : null,
    rationale: r.analysis?.analysis?.technicalQuickAnalysis ?? r.message,
    risks,
    grouped: 1,
    reasonHold: r.executionEligibilityReason ?? undefined,
  };
}

// ─── Alerts feed ─────────────────────────────────────────────────
export function mapAlert(a: AlertPayload): AlertItem {
  const t = new Date(a.timestamp).toLocaleTimeString('en-US', {
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  });
  const sev: AlertItem['sev'] =
    a.severity === 'DANGER' ? 'alert' : a.severity === 'WARNING' ? 'warn' : 'info';
  return { t, sev, src: a.category, msg: a.message };
}

// ─── Risk alerts (filtered) ──────────────────────────────────────
export function mapRiskAlerts(alerts: AlertPayload[]): RiskAlert[] {
  return alerts
    .filter((a) => a.severity !== 'INFO' && a.category.toLowerCase().includes('risk'))
    .slice(0, 3)
    .map((a) => ({
      sym: a.instrument,
      text: a.message,
      level: a.severity === 'DANGER' ? 'high' : 'warn',
    }));
}

// ─── IBKR ────────────────────────────────────────────────────────
export function mapIbkr(s: IbkrPortfolioSnapshot, fallback: Ibkr): Ibkr {
  return {
    account: s.selectedAccountId ?? fallback.account,
    netLiq: s.netLiquidation ?? fallback.netLiq,
    cashAvail: s.availableFunds ?? fallback.cashAvail,
    initMargin: s.initMarginReq ?? fallback.initMargin,
    maintMargin: fallback.maintMargin,
    realized: s.totalRealizedPnl ?? fallback.realized,
    unrealized: s.totalUnrealizedPnl ?? fallback.unrealized,
    buyingPower: s.buyingPower ?? fallback.buyingPower,
    conn: s.connected ? 'online' : 'offline',
    latencyMs: 14,
  };
}

// ─── Footprint ───────────────────────────────────────────────────
export function mapFootprint(bar: ApiFootprintBar): FootprintCol[] {
  // The backend returns a single bar; the design expects multi-bar grid.
  // Show that single bar as one column. Defensive against null `levels`.
  const levelsObj = (bar.levels ?? {}) as Record<string, { price: number; buyVolume: number; sellVolume: number }>;
  const rows: FootprintRow[] = Object.values(levelsObj)
    .filter((l): l is { price: number; buyVolume: number; sellVolume: number } => l != null && typeof l.price === 'number')
    .map((l) => ({ px: l.price, b: l.sellVolume ?? 0, a: l.buyVolume ?? 0 }))
    .sort((a, b) => b.px - a.px);
  if (rows.length && bar.pocPrice != null) {
    const pocIdx = rows.findIndex((r) => Math.abs(r.px - bar.pocPrice) < 0.001);
    if (pocIdx >= 0) rows[pocIdx].poc = true;
  }
  if (!rows.length) return []; // signal "no data" so the panel shows fallback
  return [
    {
      rows,
      dir: (bar.totalDelta ?? 0) >= 0 ? 'up' : 'down',
      delta: bar.totalDelta ?? 0,
    },
  ];
}

// ─── DOM ─────────────────────────────────────────────────────────
export function mapDom(snap: OrderFlowDepthSnapshot, fallback: Dom): Dom {
  if (!snap.available || snap.bestBid == null || snap.bestAsk == null) return fallback;
  // Backend exposes top-of-book aggregates; we synthesize 12 levels around it
  // using the totalBid/totalAsk averages for display continuity.
  const bestBid = snap.bestBid;
  const bestAsk = snap.bestAsk;
  // Guard against zero/negative spreadTicks producing Infinity tick size.
  const safeSpreadTicks = snap.spreadTicks && snap.spreadTicks > 0 ? snap.spreadTicks : 1;
  const tick = snap.spread && snap.spread > 0 ? snap.spread / safeSpreadTicks : 0.01;
  const bidAvg = snap.totalBidSize && snap.totalBidSize > 0 ? Math.floor(snap.totalBidSize / 12) : 80;
  const askAvg = snap.totalAskSize && snap.totalAskSize > 0 ? Math.floor(snap.totalAskSize / 12) : 80;
  const asks: DomLevel[] = Array.from({ length: 12 }, (_, i) => ({
    px: +(bestAsk + i * tick).toFixed(2),
    sz: askAvg + ((i * 13) % 60),
  }));
  const bids: DomLevel[] = Array.from({ length: 12 }, (_, i) => ({
    px: +(bestBid - i * tick).toFixed(2),
    sz: bidAvg + ((i * 17) % 80),
  }));
  if (snap.bidWall && snap.bidWall.price != null) bids[2] = { px: snap.bidWall.price, sz: snap.bidWall.size ?? 0 };
  if (snap.askWall && snap.askWall.price != null) asks[2] = { px: snap.askWall.price, sz: snap.askWall.size ?? 0 };
  return { bids, asks, last: (bestBid + bestAsk) / 2, spread: snap.spread ?? 0.01 };
}

// ─── Flash crash ─────────────────────────────────────────────────
export function mapFlashCrash(view: FlashCrashStatusView, fallback: FlashCrash): FlashCrash {
  const phase = view.phase?.toLowerCase();
  const level: FlashCrash['level'] =
    phase === 'crash' || phase === 'halt'
      ? 'halt'
      : phase === 'alert'
      ? 'alert'
      : phase === 'watch' || phase === 'pre_alert'
      ? 'watch'
      : 'calm';
  return {
    ...fallback,
    level,
    current: {
      sigma: view.reversalScore ?? fallback.current.sigma,
      dropPct: fallback.current.dropPct,
      vol: fallback.current.vol,
    },
  };
}

// ─── Microstructure events ───────────────────────────────────────
function ageString(timestamp: string): string {
  const t = new Date(timestamp).getTime();
  if (Number.isNaN(t)) return '—';
  const seconds = Math.max(0, Math.floor((Date.now() - t) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m ago`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)}h ago`;
  return `${Math.floor(seconds / 86400)}d ago`;
}

export function mapMicroEvents(input: {
  momentum: MomentumEventHistory[];
  iceberg: IcebergEventHistory[];
  absorption: AbsorptionEventHistory[];
  spoofing: SpoofingEventHistory[];
  distribution: DistributionEventHistory[];
  cycle: CycleEventHistory[];
}): MicroEvents {
  const mom: MicroEvent[] = input.momentum.map((e) => ({
    sym: e.instrument,
    dir: e.side === 'BULLISH_MOMENTUM' ? 'BULL' : 'BEAR',
    score: e.momentumScore,
    delta: e.aggressiveDelta,
    move: e.priceMovePoints,
    age: ageString(e.timestamp),
  }));
  const ice: MicroEvent[] = input.iceberg.map((e) => ({
    sym: e.instrument,
    dir: e.side === 'BID_ICEBERG' ? 'BULL' : 'BEAR',
    score: e.icebergScore,
    age: ageString(e.timestamp),
    note: `recharges ×${e.rechargeCount}`,
  }));
  const abs: MicroEvent[] = input.absorption.map((e) => ({
    sym: e.instrument,
    dir: e.side === 'BULLISH_ABSORPTION' ? 'BULL' : 'BEAR',
    score: e.absorptionScore,
    delta: e.aggressiveDelta,
    age: ageString(e.timestamp),
  }));
  const spf: MicroEvent[] = input.spoofing.map((e) => ({
    sym: e.instrument,
    dir: e.side === 'BID_SPOOF' ? 'BULL' : 'BEAR',
    score: e.spoofScore,
    age: ageString(e.timestamp),
    note: e.priceCrossed ? 'crossed' : `wall ${e.wallSize}`,
  }));
  const live: MicroEvent[] = []; // backend doesn't expose a dedicated live feed
  return { momentum: mom, iceberg: ice, absorption: abs, spoofing: spf, liveFeed: live };
}

// ─── Order flow prod (Setup view) ────────────────────────────────
export function mapOrderFlowProd(
  depth: OrderFlowDepthSnapshot,
  cycle: CycleEventHistory[],
  distribution: DistributionEventHistory[],
  fallback: OrderFlowProd
): OrderFlowProd {
  const sym = depth.instrument || fallback.delta.sym;
  const totalBid = depth.totalBidSize ?? 0;
  const totalAsk = depth.totalAskSize ?? 0;
  const total = totalBid + totalAsk;
  const buyPct = total > 0 ? totalAsk / total : fallback.delta.buyPct;
  const sellPct = total > 0 ? totalBid / total : fallback.delta.sellPct;
  const phaseToInt = (p: CycleEventHistory['currentPhase']): number =>
    p === 'PHASE_1' ? 1 : p === 'PHASE_2' ? 2 : 3;
  const smcCycle: OrderFlowProd['smcCycle'] = cycle.slice(0, 7).map((c) => ({
    sym: c.instrument,
    dir: c.cycleType === 'BULLISH_CYCLE' ? 'BULL' : 'BEAR',
    phase: phaseToInt(c.currentPhase),
    done: c.currentPhase === 'COMPLETE',
    conf: Math.round(c.confidence * 100),
    age: ageString(c.startedAt),
  }));
  const distAccum: OrderFlowProd['distAccum'] = distribution.slice(0, 5).map((d) => ({
    kind: d.type === 'ACCUMULATION' ? 'ACCUM' : 'DIST',
    sym: d.instrument,
    dir: d.type === 'ACCUMULATION' ? 'BULL' : 'BEAR',
    mult: d.consecutiveCount,
    avg: d.avgScore,
    conf: Math.round(d.confidenceScore * 100),
    age: ageString(d.timestamp),
  }));

  return {
    delta: {
      sym,
      buy: Math.round(totalAsk),
      sell: Math.round(totalBid),
      cum: Math.round(totalAsk - totalBid),
      real: Math.round(totalAsk - totalBid),
      buyPct,
      sellPct,
    },
    depth: {
      sym,
      bid: Math.round(totalBid),
      ask: Math.round(totalAsk),
      spread: depth.spread ?? fallback.depth.spread,
      imbalance: depth.depthImbalance ?? fallback.depth.imbalance,
      mid: fallback.depth.mid,
    },
    smcCycle: smcCycle.length ? smcCycle : fallback.smcCycle,
    distAccum: distAccum.length ? distAccum : fallback.distAccum,
  };
}

// ─── Strategy / Regime (from new probabilistic engine) ───────────
import type { StrategyDecisionView } from '../../lib/api';
export function mapStrategy(d: StrategyDecisionView, fallback: Strategy): Strategy {
  const conf = Math.max(0, Math.min(1, (Math.abs(d.finalScore) + 50) / 150));
  const factors: Strategy['factors'] = (d.votes ?? []).slice(0, 6).map((v) => ({
    name: v.agentId,
    v: Math.max(0, Math.min(1, (v.directionalVote + 100) / 200)),
    label: v.evidence?.[0] ?? v.layer,
  }));
  const regimeLabel =
    d.decision === 'NO_TRADE'
      ? 'No trade'
      : d.decision === 'MONITORING'
      ? 'Monitoring'
      : d.direction === 'LONG'
      ? 'Trend (bullish)'
      : d.direction === 'SHORT'
      ? 'Trend (bearish)'
      : 'Mixed';
  return {
    regime: regimeLabel,
    confidence: conf,
    factors: factors.length ? factors : fallback.factors,
    recommendation: d.vetoReasons?.[0] ?? fallback.recommendation,
  };
}

// ─── Correlations (subset — backend exposes oil-nasdaq only) ─────
export function mapCorrelations(history: CorrelationSignal[], fallback: Correlations): Correlations {
  // Backend's CorrelationStatus is oil/nasdaq only. The redesign's matrix is broader,
  // so we keep the mock structure and only override the MCL/MNQ cell.
  if (!history.length) return fallback;
  const last = history[0];
  const out = { ...fallback, data: fallback.data.map((row) => row.slice()) };
  // Approximate: average the leader/follower signal as a correlation proxy
  const proxy = Math.max(-1, Math.min(1, last.lagSeconds > 0 ? 1 - last.lagSeconds / 600 : 0.5));
  // MCL ↔ NQ cell (row 0, col 2) and MNQ ↔ CL cell (row 3, col 4)
  out.data[0][2] = proxy;
  out.data[3][4] = proxy;
  return out;
}
