import { API_BASE } from '@/app/lib/runtimeConfig';
import type { AdviceView, QuantSnapshotView } from '@/app/components/quant/types';

const BASE = API_BASE;

export interface PositionView {
  id: number | null;
  instrument: string;
  instrumentName: string;
  side: 'LONG' | 'SHORT';
  quantity: number;
  entryPrice: number;
  currentPrice: number;
  stopLoss: number | null;
  takeProfit: number | null;
  unrealizedPnL: number;
  riskAmount: number | null;
  riskRewardRatio: number | null;
  open: boolean;
  openedAt: string | null;
  notes: string | null;
  source: 'LOCAL' | 'IBKR';
  accountId: string | null;
  assetClass: string | null;
  closable: boolean;
}

export interface PortfolioSummary {
  totalUnrealizedPnL: number;
  todayRealizedPnL: number;
  totalPnL: number;
  openPositionCount: number;
  totalExposure: number;
  marginUsedPct: number;
  openPositions: PositionView[];
}

export interface IbkrAccountView {
  id: string;
  displayName: string;
  currency: string;
  selected: boolean;
}

export interface IbkrPositionView {
  accountId: string;
  conid: number;
  contractDesc: string;
  assetClass: string;
  position: number;
  marketPrice: number;
  marketValue: number;
  averageCost: number;
  averagePrice: number;
  realizedPnl: number;
  unrealizedPnl: number;
  currency: string;
}

export interface IbkrPortfolioSnapshot {
  connected: boolean;
  selectedAccountId: string | null;
  accounts: IbkrAccountView[];
  netLiquidation: number;
  initMarginReq: number;
  availableFunds: number;
  buyingPower: number;
  grossPositionValue: number;
  totalUnrealizedPnl: number;
  totalRealizedPnl: number;
  currency: string;
  positions: IbkrPositionView[];
  message: string | null;
}

export interface IbkrAuthStatus {
  authenticated: boolean;
  connected: boolean;
  established: boolean;
  competing: boolean;
  endpoint: string;
  message: string;
}

export interface OrderBlockView {
  type: 'BULLISH' | 'BEARISH';
  status: 'ACTIVE' | 'MITIGATED' | 'BREAKER';
  high: number;
  low: number;
  mid: number;
  startTime: number;   // epoch seconds of formation candle
  originalType?: 'BULLISH' | 'BEARISH';
  breakerTime?: number | null; // epoch seconds of invalidation candle when status=BREAKER
  // Order Flow enrichment (nullable — null when ticks unavailable)
  formationDelta?: number | null;
  obFormationScore?: number | null;
  obLiveScore?: number | null;
  defended?: boolean | null;
  absorptionScore?: number | null;
}

/** UC-SMC-009: OB lifecycle event (MITIGATION or INVALIDATION). */
export interface OrderBlockEventView {
  eventType: 'MITIGATION' | 'INVALIDATION';
  obType: 'BULLISH' | 'BEARISH';
  high: number;
  low: number;
  eventTime: number;   // epoch seconds
}

export interface FairValueGapView {
  bias: 'BULLISH' | 'BEARISH';
  top: number;
  bottom: number;
  startTime: number;       // epoch seconds
  extensionEndTime: number; // UC-SMC-010: visual zone extension end time
  // Order Flow enrichment (nullable)
  gapDelta?: number | null;
  fvgQualityScore?: number | null;
}

export interface EqualLevelView {
  type: 'EQH' | 'EQL';
  price: number;
  firstBarTime: number;   // epoch seconds
  lastBarTime: number;    // epoch seconds
  touchCount: number;
  // Order Flow enrichment (nullable — requires Level 2 depth)
  ordersVisible?: boolean | null;
  depthSizeAtLevel?: number | null;
  liquidityConfirmScore?: number | null;
}

/** UC-SMC-005: OHLC for a single higher-timeframe candle. */
export interface MtfLevelView {
  open: number;
  high: number;
  low: number;
  close: number;
}

/** UC-SMC-005: Daily / weekly / monthly levels. Null = no data for that timeframe. */
export interface MtfLevelsView {
  daily:   MtfLevelView | null;
  weekly:  MtfLevelView | null;
  monthly: MtfLevelView | null;
}

export interface StructureBreakView {
  type: 'BOS' | 'CHOCH';
  trend: 'BULLISH' | 'BEARISH';
  level: number;
  barTime: number;     // epoch seconds
  structureLevel: 'INTERNAL' | 'SWING' | null;
  // Order Flow enrichment (nullable)
  breakDelta?: number | null;
  volumeSpike?: number | null;
  confirmed?: boolean | null;
  breakConfidenceScore?: number | null;
}

export interface IndicatorSnapshot {
  instrument: string;
  timeframe: string;
  ema9: number | null;
  ema50: number | null;
  ema200: number | null;
  emaCrossover: string | null;
  rsi: number | null;
  rsiSignal: string | null;
  macdLine: number | null;
  macdSignal: number | null;
  macdHistogram: number | null;
  macdCrossover: string | null;
  supertrendValue: number | null;
  supertrendBullish: boolean;
  vwap: number | null;
  vwapUpperBand: number | null;
  vwapLowerBand: number | null;
  chaikinOscillator: number | null;
  cmf: number | null;
  bbMiddle: number | null;
  bbUpper: number | null;
  bbLower: number | null;
  bbWidth: number | null;
  bbPct: number | null;
  bbTrendValue: number | null;
  bbTrendExpanding: boolean;
  bbTrendSignal: string | null;
  deltaFlow: number | null;
  cumulativeDelta: number | null;
  buyRatio: number | null;
  deltaFlowBias: string | null;
  wtWt1: number | null;
  wtWt2: number | null;
  wtDiff: number | null;
  wtCrossover: string | null;
  wtSignal: string | null;
  // Stochastic Oscillator
  stochK: number | null;
  stochD: number | null;
  stochSignal: string | null;
  stochCrossover: string | null;
  // SMC: Internal structure
  internalBias: string | null;
  internalHigh: number | null;
  internalLow: number | null;
  internalHighTime: number | null;
  internalLowTime: number | null;
  lastInternalBreakType: string | null;
  // SMC: Swing structure
  swingBias: string | null;
  swingHigh: number | null;
  swingLow: number | null;
  swingHighTime: number | null;
  swingLowTime: number | null;
  lastSwingBreakType: string | null;
  // SMC: UC-SMC-008 confluence filter
  internalConfluenceFilterEnabled: boolean;
  // SMC: Liquidity (EQH / EQL)
  equalHighs: EqualLevelView[];
  equalLows: EqualLevelView[];
  // SMC: Premium / Discount / Equilibrium (UC-SMC-004)
  premiumZoneTop: number | null;
  equilibriumLevel: number | null;
  discountZoneBottom: number | null;
  currentZone: 'PREMIUM' | 'DISCOUNT' | 'EQUILIBRIUM' | null;
  // SMC: Multi-resolution bias (5 lookback scales)
  multiResolutionBias: {
    swing50: string | null;
    swing25: string | null;
    swing9: string | null;
    internal5: string | null;
    micro1: string | null;
  } | null;
  // SMC: Legacy / derived (backward compat)
  marketStructureTrend: string;
  strongHigh: number | null;
  strongLow: number | null;
  weakHigh: number | null;
  weakLow: number | null;
  lastBreakType: string | null;
  // SMC chart timestamps (epoch seconds)
  strongHighTime: number | null;
  strongLowTime:  number | null;
  weakHighTime:   number | null;
  weakLowTime:    number | null;
  // SMC overlays
  activeOrderBlocks:         OrderBlockView[];
  breakerOrderBlocks:        OrderBlockView[];
  recentOrderBlockEvents:    OrderBlockEventView[];
  activeFairValueGaps:       FairValueGapView[];
  recentBreaks:              StructureBreakView[];
  // UC-SMC-005: Multi-timeframe levels
  mtfLevels: MtfLevelsView | null;
  // Session PD Array (intraday range-based)
  sessionHigh: number | null;
  sessionLow: number | null;
  sessionEquilibrium: number | null;
  sessionPdZone: 'PREMIUM' | 'DISCOUNT' | 'EQUILIBRIUM' | null;
  // UC-OF-012: Volume Profile
  pocPrice: number | null;
  valueAreaHigh: number | null;
  valueAreaLow: number | null;
  // UC-OF-013: Session CME Context
  sessionPhase: 'ASIAN' | 'LONDON' | 'NY_AM' | 'NY_PM' | 'CLOSE' | 'CLOSED' | null;
}


async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { cache: 'no-store' });
  if (!res.ok) throw new Error(`GET ${path} → ${res.status}${await readErrorSuffix(res)}`);
  return res.json();
}

async function post<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${path} → ${res.status}${await readErrorSuffix(res)}`);
  return res.json();
}

async function del<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`DELETE ${path} → ${res.status}${await readErrorSuffix(res)}`);
  return res.json();
}

async function put<T>(path: string, body?: unknown): Promise<T | void> {
  const init: RequestInit = { method: 'PUT' };
  if (body !== undefined) {
    init.headers = { 'Content-Type': 'application/json' };
    init.body = JSON.stringify(body);
  }
  const res = await fetch(`${BASE}${path}`, init);
  if (!res.ok) throw new Error(`PUT ${path} → ${res.status}${await readErrorSuffix(res)}`);
  if (res.status === 204) return;
  const text = await res.text();
  return text ? (JSON.parse(text) as T) : undefined;
}

async function postNoContent(path: string, body: unknown): Promise<void> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`POST ${path} → ${res.status}${await readErrorSuffix(res)}`);
}

async function readErrorSuffix(res: Response) {
  try {
    const data = await res.clone().json();
    if (typeof data?.message === 'string' && data.message.trim() !== '') {
      return ` — ${data.message}`;
    }
    return '';
  } catch {
    try {
      const text = await res.text();
      return text ? ` — ${text}` : '';
    } catch {
      return '';
    }
  }
}

export interface CandleBar {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
}

export interface IndicatorLinePoint {
  time: number;
  value: number;
}

export interface BollingerPoint {
  time: number;
  upper: number;
  lower: number;
}

export interface WaveTrendPoint {
  time: number;
  wt1: number;
  wt2: number;
  diff: number;
}

export interface IndicatorSeriesSnapshot {
  instrument: string;
  timeframe: string;
  ema9: IndicatorLinePoint[];
  ema50: IndicatorLinePoint[];
  ema200: IndicatorLinePoint[];
  bollingerBands: BollingerPoint[];
  waveTrend: WaveTrendPoint[];
}

export interface AlertPayload {
  key?: string;
  severity: 'INFO' | 'WARNING' | 'DANGER';
  category: string;
  message: string;
  instrument: string | null;
  timestamp: string;
}

/** Body for POST /api/alerts/snooze — see AlertController.snoozeAlert. */
export interface SnoozeAlertRequest {
  key: string;
  durationSeconds: number;
}

export interface LivePriceView {
  instrument: string;
  price: number;
  timestamp: string;
  source: string;
}

export interface DxySnapshotView {
  timestamp: string;
  eurusd: number;
  usdjpy: number;
  gbpusd: number;
  usdcad: number;
  usdsek: number;
  usdchf: number;
  dxyValue: number;
  source: string;
  isComplete: boolean;
  changePercent?: number;
  baselineValue?: number;
  baselineTimestamp?: string;
}

export interface DxyHealthComponentView {
  pair: string;
  bid: number | null;
  ask: number | null;
  last: number | null;
  effectivePrice: number | null;
  pricingMethod: string | null;
  timestamp: string | null;
  status: string;
  message: string | null;
}

export interface DxyHealthView {
  status: 'UP' | 'DEGRADED' | 'DOWN';
  latestTimestamp: string | null;
  source: string;
  maxSkewSeconds: number;
  components: DxyHealthComponentView[];
}

/**
 * One FX pair's contribution to the current DXY move, relative to today's
 * session baseline. Returned by GET /api/market/dxy/breakdown — mirrors
 * {@code com.riskdesk.domain.marketdata.model.FxComponentContribution} on
 * the backend. Sorted by |weightedImpact| desc so the dominant driver is
 * index 0.
 */
export interface FxComponentContributionView {
  pair: string;
  currentRate: number;
  baselineRate: number;
  pctChange: number;
  dxyWeight: number;
  weightedImpact: number;
  impactDirection: string;
}

/**
 * Shape of the flash-crash status snapshot per instrument — the REST seed
 * for the Live Monitor tab before /topic/flash-crash starts pushing updates
 * (see FlashCrashController#getStatus). Fields align with the WebSocket
 * payload on /topic/flash-crash, plus a {@code timestamp} of the last
 * persisted transition. {@code conditions} is intentionally empty on the
 * REST side since individual booleans aren't persisted.
 */
export interface FlashCrashStatusView {
  instrument: string;
  phase: string;
  previousPhase: string;
  conditionsMet: number;
  conditions: boolean[];
  reversalScore: number;
  timestamp: string;
}

/**
 * Open Interest comparison between current and next contract month for one
 * futures instrument. Returned per instrument by GET /api/rollover/oi-status.
 * <p>When the backend could not fetch OI for an instrument the status map
 * carries either {@code { status: 'UNAVAILABLE' }} or {@code { status: 'ERROR', message }};
 * callers must narrow before treating the value as a full recommendation.
 */
export interface RolloverOiEntry {
  currentMonth?: string;
  nextMonth?: string;
  currentOI?: number;
  nextOI?: number;
  action?: 'NO_ACTION' | 'RECOMMEND_ROLL' | string;
  status?: 'UNAVAILABLE' | 'ERROR' | string;
  message?: string;
}

export type RolloverOiStatus = Record<string, RolloverOiEntry>;

export interface BacktestTrade {
  tradeNo: number;
  side: 'LONG' | 'SHORT';
  entryPrice: number;
  entryTime: string;
  exitPrice: number;
  exitTime: string;
  qty: number;
  pnl: number;
  pnlPct: number;
  exitReason: string;
}

export interface BacktestSignalDebug {
  time: string;
  type: 'LONG' | 'SHORT';
  price: number;
  wt1: number;
  wt2: number;
}

export interface BacktestDataAudit {
  loadedCandles: number;
  evaluatedCandles: number;
  duplicateCandles: number;
  outOfOrderPairs: number;
  alignedCandles: number;
  misalignedCandles: number;
  gapCount: number;
  suspiciousGapCount: number;
  maxGap: string | null;
  firstCandleTime: string | null;
  requestedEvaluationStartTime: string | null;
  evaluationStartTime: string | null;
  lastCandleTime: string | null;
  timezone: string;
  requestedWarmupBars: number;
  availableWarmupBars: number;
  sufficientWarmup: boolean;
  adjustedEvaluationStart: boolean;
  warnings: string[];
}

export interface BacktestDebugEvent {
  time: string;
  event: 'SIGNAL' | 'ENTRY' | 'EXIT' | 'SKIP';
  side: string;
  price: number;
  reason: string;
  wt1: number | null;
  wt2: number | null;
  ema: number | null;
  atr: number | null;
  stopPrice: number | null;
}

export interface BacktestResult {
  strategy: string;
  instrument: string;
  timeframe: string;
  totalCandles: number;
  initialCapital: number;
  finalCapital: number;
  totalPnl: number;
  totalReturnPct: number;
  totalTrades: number;
  wins: number;
  losses: number;
  winRate: number;
  avgWin: number;
  avgLoss: number;
  profitFactor: number;
  maxDrawdown: number;
  maxDrawdownPct: number;
  sharpeRatio: number;
  trades: BacktestTrade[];
  equityCurve: number[];
  signals: BacktestSignalDebug[];
  dataAudit: BacktestDataAudit | null;
  debugEvents: BacktestDebugEvent[];
}

export interface MentorStructuredResponse {
  technicalQuickAnalysis: string;
  strengths: string[];
  errors: string[];
  verdict: string;
  improvementTip: string;
  proposedTradePlan: {
    entryPrice: number | null;
    stopLoss: number | null;
    takeProfit: number | null;
    rewardToRiskRatio: number | null;
    rationale: string | null;
    safeDeepEntry: {
      entryPrice: number | null;
      rationale: string | null;
    } | null;
  } | null;
}

export interface MentorAnalyzeResponse {
  auditId: number | null;
  model: string;
  payload: unknown;
  analysis: MentorStructuredResponse | null;
  rawResponse: string;
  similarAudits: {
    auditId: number;
    createdAt: string;
    instrument: string;
    timeframe: string;
    action: string;
    verdict: string;
    similarity: number;
    summary: string;
  }[];
}

export interface MentorManualReview {
  auditId: number;
  sourceType: 'MANUAL_MENTOR';
  createdAt: string;
  selectedTimezone: string | null;
  instrument: string | null;
  timeframe: string | null;
  action: string | null;
  model: string | null;
  verdict: string | null;
  success: boolean;
  errorMessage: string | null;
  response: MentorAnalyzeResponse;
}

export interface MentorSignalReview {
  id: number;
  alertKey: string;
  revision: number;
  triggerType: 'INITIAL' | 'MANUAL_REANALYSIS';
  status: 'ANALYZING' | 'DONE' | 'ERROR';
  severity: 'INFO' | 'WARNING' | 'DANGER';
  category: string;
  message: string;
  instrument: string;
  timeframe: string;
  action: 'LONG' | 'SHORT' | 'MONITOR';
  sourceType: 'SIGNAL' | 'BEHAVIOUR';
  timestamp: string;
  createdAt: string;
  selectedTimezone: string | null;
  // MENTOR_UNAVAILABLE: Gemini reply was truncated / parse-failed / timed out.
  // Distinct from INELIGIBLE — the trade was never actually evaluated and the
  // UI surfaces it as "retry needed", not "rejected". Introduced after a prod
  // audit where ~96% of "Trade Non-Conforme" badges were masked technical
  // failures, not real rejections.
  executionEligibilityStatus:
    | 'NOT_EVALUATED'
    | 'ELIGIBLE'
    | 'INELIGIBLE'
    | 'MENTOR_UNAVAILABLE'
    | null;
  executionEligibilityReason: string | null;
  simulationStatus: 'PENDING_ENTRY' | 'ACTIVE' | 'WIN' | 'LOSS' | 'MISSED' | 'CANCELLED' | 'REVERSED' | null;
  activationTime: string | null;
  resolutionTime: string | null;
  maxDrawdownPoints: number | null;
  // Trailing-stop dual-track (runs in parallel with fixed SL/TP).
  // See CLAUDE.md § "Trailing Stop (Dual-Track)" for the full spec.
  trailingStopResult: 'TRAILING_WIN' | 'TRAILING_BE' | 'TRAILING_LOSS' | null;
  trailingExitPrice: number | null;
  // Maximum Favorable Excursion — best price reached before the trailing exit.
  bestFavorablePrice: number | null;
  analysis: MentorAnalyzeResponse | null;
  errorMessage: string | null;
  triggerPrice: number | null;
}

export interface MentorAlertReviewRequest {
  severity: 'INFO' | 'WARNING' | 'DANGER';
  category: string;
  message: string;
  instrument: string | null;
  timestamp: string;
  selectedTimezone?: string;
  entryPrice?: number;
  stopLoss?: number;
  takeProfit?: number;
}

export interface TradeExecutionView {
  id: number;
  version: number | null;
  executionKey: string;
  mentorSignalReviewId: number;
  reviewAlertKey: string;
  reviewRevision: number;
  brokerAccountId: string;
  instrument: string;
  timeframe: string;
  action: 'LONG' | 'SHORT';
  quantity: number | null;
  triggerSource: 'MANUAL_ARMING';
  requestedBy: string | null;
  status:
    | 'PENDING_ENTRY_SUBMISSION'
    | 'ENTRY_SUBMITTED'
    | 'ENTRY_PARTIALLY_FILLED'
    | 'ACTIVE'
    | 'VIRTUAL_EXIT_TRIGGERED'
    | 'EXIT_SUBMITTED'
    | 'CLOSED'
    | 'CANCELLED'
    | 'REJECTED'
    | 'FAILED';
  statusReason: string | null;
  normalizedEntryPrice: number;
  virtualStopLoss: number;
  virtualTakeProfit: number;
  disasterStopPrice: number | null;
  entryOrderId: number | null;
  disasterStopOrderId: number | null;
  lastReliableLivePrice: number | null;
  lastReliableLivePriceAt: string | null;
  createdAt: string;
  updatedAt: string;
  entrySubmittedAt: string | null;
  entryFilledAt: string | null;
  virtualExitTriggeredAt: string | null;
  exitSubmittedAt: string | null;
  closedAt: string | null;
}

export interface MentorIntermarketSnapshot {
  dxyPctChange: number | null;
  dxyTrend: string | null;
  silverSi1PctChange: number | null;
  goldMgc1PctChange: number | null;
  platPl1PctChange: number | null;
  metalsConvergenceStatus: string;
}

export interface MacroCorrelationSnapshot {
  dxyPctChange: number | null;
  dxyTrend: string | null;
  dxyComponentBreakdown: unknown[] | null;
  sectorLeaderSymbol: string | null;
  sectorLeaderPctChange: number | null;
  sectorLeaderTrend: string | null;
  vixPctChange: number | null;
  us10yYieldPctChange: number | null;
  correlationAlignment: string;
  dataAvailability: string;
}

export interface FootprintLevel {
  price: number;
  buyVolume: number;
  sellVolume: number;
  delta: number;
  imbalance: boolean;
}

export interface FootprintBar {
  instrument: string;
  timeframe: string;
  barTimestamp: number;
  levels: Record<string, FootprintLevel>;
  pocPrice: number;
  totalBuyVolume: number;
  totalSellVolume: number;
  totalDelta: number;
}

/**
 * Narrow a /api/order-flow/footprint response to a real bar.
 * FootprintController can return {available:false} or {error:...} with HTTP 200.
 */
export function isFootprintBar(v: unknown): v is FootprintBar {
  if (!v || typeof v !== 'object') return false;
  const o = v as Record<string, unknown>;
  return (
    typeof o.levels === 'object' &&
    o.levels !== null &&
    typeof o.instrument === 'string' &&
    typeof o.timeframe === 'string'
  );
}

// ── Trailing Stop Stats (GET /api/mentor/simulation/trailing-stats) ────────────
export interface TrailingTrackStats {
  trades: number;
  wins: number;
  winRate: number;
  netPnl: number;
}

export interface TrailingImprovement {
  winRateDelta: number;
  pnlDelta: number;
}

export interface TrailingStopStats {
  period: string;
  fixedSLTP: TrailingTrackStats;
  trailingStop: TrailingTrackStats;
  improvement: TrailingImprovement;
}

// ── Order Flow History (persisted events, newest first) ───────────────────────
// Backs the historical lists in OrderFlowPanel. Field names mirror the backend
// DTOs in com.riskdesk.application.dto.*EventView — keep in sync.

export interface IcebergEventHistory {
  instrument: string;
  timestamp: string;     // ISO-8601 UTC
  side: 'BID_ICEBERG' | 'ASK_ICEBERG';
  priceLevel: number;
  rechargeCount: number;
  avgRechargeSize: number;
  durationSeconds: number;
  icebergScore: number;
}

export interface AbsorptionEventHistory {
  instrument: string;
  timestamp: string;
  side: 'BULLISH_ABSORPTION' | 'BEARISH_ABSORPTION';
  absorptionScore: number;
  aggressiveDelta: number;
  priceMoveTicks: number;
  totalVolume: number;
}

export interface SpoofingEventHistory {
  instrument: string;
  timestamp: string;
  side: 'BID_SPOOF' | 'ASK_SPOOF';
  priceLevel: number;
  wallSize: number;
  durationSeconds: number;
  priceCrossed: boolean;
  spoofScore: number;
}

export interface DistributionEventHistory {
  instrument: string;
  timestamp: string;
  type: 'DISTRIBUTION' | 'ACCUMULATION';
  consecutiveCount: number;
  avgScore: number;
  totalDurationSeconds: number;
  priceAtDetection: number;
  resistanceLevel: number | null;
  confidenceScore: number;
}

export interface MomentumEventHistory {
  instrument: string;
  timestamp: string;
  side: 'BULLISH_MOMENTUM' | 'BEARISH_MOMENTUM';
  momentumScore: number;
  aggressiveDelta: number;
  priceMoveTicks: number;
  priceMovePoints: number;
  totalVolume: number;
}

export interface CycleEventHistory {
  instrument: string;
  timestamp: string;
  cycleType: 'BEARISH_CYCLE' | 'BULLISH_CYCLE';
  currentPhase: 'PHASE_1' | 'PHASE_2' | 'PHASE_3' | 'COMPLETE';
  priceAtPhase1: number;
  priceAtPhase2: number | null;
  priceAtPhase3: number | null;
  totalPriceMove: number;
  totalDurationMinutes: number;
  confidence: number;
  startedAt: string;
  completedAt: string | null;
}

// ── Order Flow Depth (GET /api/order-flow/depth/{instrument}) ──────────────────
export interface OrderFlowDepthSnapshot {
  instrument: string;
  available: boolean;
  totalBidSize?: number;
  totalAskSize?: number;
  depthImbalance?: number;
  bestBid?: number;
  bestAsk?: number;
  spread?: number;
  spreadTicks?: number;
  bidWall?: { price: number; size: number } | null;
  askWall?: { price: number; size: number } | null;
  timestamp?: string | null;
  error?: string;
}

// ── Trade Simulation views (GET /api/simulations/*) ────────────────────────────
// Phase 1b: read-only wrappers. Source of truth is the new `trade_simulations`
// table; legacy simulation fields on MentorSignalReview/MentorAudit DTOs still
// exist but are slated for removal in Phase 3.
export interface TradeSimulationView {
  id: number;
  reviewId: number;
  reviewType: 'SIGNAL' | 'AUDIT';
  instrument: string;
  action: string;
  simulationStatus: string;
  activationTime: string | null;
  resolutionTime: string | null;
  maxDrawdownPoints: number | null;
  trailingStopResult: string | null;
  trailingExitPrice: number | null;
  bestFavorablePrice: number | null;
  createdAt: string;
}

// ── ONIMS Correlation (GET /api/correlation/oil-nasdaq/*) ──────────────────────
// Matches CrossInstrumentAlertService.toPayload() exactly.
export interface CorrelationSignal {
  type?: string;
  category?: string;
  severity?: string;
  leaderInstrument: string;
  followerInstrument: string;
  leaderBreakout: number;
  leaderResistance: number;
  followerVwap: number;
  followerClose: number;
  lagSeconds: number;
  confirmedAt: string;   // ISO-8601
  message?: string;
}

// Matches CorrelationController.status() exactly. The engine-state key is
// `engineState` (not `state`) — see CorrelationController.java.
export interface CorrelationStatus {
  strategy?: string;
  engineState: string;
  blackoutStart: string | null;
  vixThreshold: number;
  cachedVixPrice: number | null;
  blackoutActive: boolean;
  blackoutDurationMins: number;
}

// ── Live Tri-Layer Analysis ──────────────────────────────────────────
export interface LiveScoreComponent {
  name: string;
  contribution: number;
  rationale: string;
}

export interface LiveFactor {
  polarity: 'BULLISH' | 'BEARISH';
  layer: string;
  description: string;
  strength: number;
}

export interface LiveContradiction {
  layerA: string;
  layerB: string;
  description: string;
}

export interface LiveTradeScenario {
  name: string;
  probability: number;
  direction: 'LONG' | 'SHORT' | 'NEUTRAL';
  entry: number | null;
  stopLoss: number | null;
  takeProfit1: number | null;
  takeProfit2: number | null;
  rewardRiskRatio: number;
  triggerCondition: string;
  invalidation: string;
}

export interface LiveBiasView {
  primary: 'LONG' | 'SHORT' | 'NEUTRAL';
  confidence: number;
  structureScore: number;
  orderFlowScore: number;
  momentumScore: number;
  bullishFactors: LiveFactor[];
  bearishFactors: LiveFactor[];
  contradictions: LiveContradiction[];
  standAsideReason: string | null;
}

export interface LiveVerdictView {
  instrument: string;
  timeframe: string;
  decisionTimestamp: string;
  scoringEngineVersion: number;
  currentPrice: number;
  bias: LiveBiasView;
  scenarios: LiveTradeScenario[];
  validUntil: string;
  /** True when validUntil < server now() — surfaced as a banner in the panel. */
  expired: boolean;
  /** Seconds since validUntil; 0 when not expired. */
  expiredForSeconds: number;
}

export interface ReplayReport {
  instrument: string;
  timeframe: string;
  from: string;
  to: string;
  weights: { structure: number; orderFlow: number; momentum: number };
  totalSnapshots: number;
  agreementCount: number;
  agreementRatio: number;
  actionableCount: number;
  directionDistribution: Record<string, number>;
  samples: Array<{
    decisionTimestamp: string;
    originalDirection: string;
    originalConfidence: number;
    replayedDirection: string;
    replayedConfidence: number;
    structureScore: number;
    orderFlowScore: number;
    momentumScore: number;
  }>;
}

export const api = {
  getPortfolioSummary: (accountId?: string) =>
    get<PortfolioSummary>(`/api/positions/summary${accountId ? `?accountId=${encodeURIComponent(accountId)}` : ''}`),
  // Read-only — returns the latest persisted verdict (no compute, no DB write).
  // Use this for dashboard polling so verdict_records grows at scheduler cadence
  // only, regardless of how many viewers are open.
  getLatestAnalysis: (instrument: string, timeframe: string) =>
    get<LiveVerdictView>(`/api/analysis/latest/${instrument}/${timeframe}`),
  // What the scheduler actually scans — used to flag tabs that will never
  // produce a verdict instead of polling /latest forever (PR #270 review).
  getAnalysisScanConfig: () =>
    get<{ schedulerEnabled: boolean; instruments: string[]; timeframes: string[]; pollIntervalMs: number }>(
      '/api/analysis/scan-config'),
  // On-demand fresh compute — call sparingly, this writes a verdict row.
  getLiveAnalysis: (instrument: string, timeframe: string) =>
    get<LiveVerdictView>(`/api/analysis/live/${instrument}/${timeframe}`),
  getRecentVerdicts: (instrument: string, timeframe: string, limit = 20) =>
    get<LiveVerdictView[]>(`/api/analysis/recent/${instrument}/${timeframe}?limit=${limit}`),
  replayAnalysis: (req: {
    instrument: string;
    timeframe: string;
    from: string;
    to: string;
    structure: number;
    orderFlow: number;
    momentum: number;
  }) => post<ReplayReport>('/api/analysis/replay', req),
  getOpenPositions: (accountId?: string) =>
    get<PositionView[]>(`/api/positions${accountId ? `?accountId=${encodeURIComponent(accountId)}` : ''}`),
  getClosedPositions: () => get<PositionView[]>('/api/positions/closed'),
  getIbkrPortfolio: (accountId?: string) =>
    get<IbkrPortfolioSnapshot>(`/api/ibkr/portfolio${accountId ? `?accountId=${encodeURIComponent(accountId)}` : ''}`),
  getIbkrAuthStatus: () => get<IbkrAuthStatus>('/api/ibkr/connection/status'),
  refreshIbkrAuth: () => post<IbkrAuthStatus>('/api/ibkr/connection/refresh', {}),
  getIndicators: (instrument: string, timeframe: string) =>
    get<IndicatorSnapshot>(`/api/indicators/${instrument}/${timeframe}`),
  getIndicatorSeries: (instrument: string, timeframe: string, limit = 500) =>
    get<IndicatorSeriesSnapshot>(`/api/indicators/${instrument}/${timeframe}/series?limit=${limit}`),
  getCandles: (instrument: string, timeframe: string, limit = 300) =>
    get<CandleBar[]>(`/api/candles/${instrument}/${timeframe}?limit=${limit}`),
  getRecentAlerts: () => get<AlertPayload[]>('/api/alerts/recent'),
  clearRecentAlerts: () => del<{ cleared: number }>('/api/alerts/recent').then(() => undefined as void),
  snoozeAlert: (body: SnoozeAlertRequest) => postNoContent('/api/alerts/snooze', body),
  getMutedTimeframes: () => get<string[]>('/api/alerts/muted-timeframes'),
  setTimeframeMuted: (timeframe: string, muted: boolean) =>
    put<void>(`/api/alerts/muted-timeframes/${encodeURIComponent(timeframe)}?muted=${muted}`).then(() => undefined as void),
  getLivePrice: (instrument: string) => get<LivePriceView>(`/api/live-price/${instrument}`),
  getDxyLatest: () => get<DxySnapshotView>('/api/market/dxy/latest'),
  getDxyHistory: (from: string, to: string) =>
    get<DxySnapshotView[]>(`/api/market/dxy/history?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  getDxyHealth: () => get<DxyHealthView>('/api/market/dxy/health'),
  // Per-component contribution to the DXY move vs today's session baseline
  // (driver analysis — which FX pair actually moved the index).
  getDxyBreakdown: () =>
    get<FxComponentContributionView[]>('/api/market/dxy/breakdown'),
  // Flash-crash FSM status for the Live Monitor panel. The WebSocket at
  // /topic/flash-crash only emits forward-going transitions, so a fresh page
  // load needs this REST seed to know the current phase of each instrument.
  getFlashCrashStatus: () =>
    get<{ instruments: Record<string, FlashCrashStatusView> }>('/api/order-flow/flash-crash/status'),
  getFlashCrashStatusForInstrument: (instrument: string) =>
    get<FlashCrashStatusView>(`/api/order-flow/flash-crash/status/${encodeURIComponent(instrument)}`),
  // Rollover open-interest comparison per instrument — complements the
  // time-to-expiry data exposed by useRollover with a liquidity-based signal.
  getRolloverOiStatus: () =>
    get<RolloverOiStatus>('/api/rollover/oi-status'),
  analyzeMentor: (payload: unknown) =>
    post<MentorAnalyzeResponse>('/api/mentor/analyze', { payload }),
  refreshMentorContext: (instrument: string, timeframe: string) =>
    post<{ instrument: string; refreshed: Record<string, number> }>('/api/mentor/refresh-context', { instrument, timeframe }),
  getMentorIntermarket: (instrument?: string) =>
    get<MentorIntermarketSnapshot>(`/api/mentor/intermarket${instrument ? `?instrument=${encodeURIComponent(instrument)}` : ''}`),
  getRecentManualMentorReviews: () =>
    get<MentorManualReview[]>('/api/mentor/manual-reviews/recent'),
  getRecentMentorSignalReviews: () =>
    get<MentorSignalReview[]>('/api/mentor/auto-alerts/recent'),
  getMentorAlertThread: (request: MentorAlertReviewRequest) =>
    post<MentorSignalReview[]>('/api/mentor/auto-alerts/thread', request),
  reanalyzeMentorAlert: (request: MentorAlertReviewRequest) =>
    post<MentorSignalReview>('/api/mentor/auto-alerts/reanalyze', request),
  createTradeExecution: (request: { mentorSignalReviewId: number; brokerAccountId: string; quantity: number }) =>
    post<TradeExecutionView>('/api/mentor/executions', request),
  getTradeExecutionsByReviewIds: (mentorSignalReviewIds: number[]) =>
    post<TradeExecutionView[]>('/api/mentor/executions/by-review-ids', { mentorSignalReviewIds }),
  submitTradeExecutionEntry: (executionId: number) =>
    post<TradeExecutionView>(`/api/mentor/executions/${executionId}/submit-entry`, {}),
  getAutoAnalysisStatus: () =>
    get<{ enabled: boolean }>('/api/mentor/auto-analysis/status'),
  toggleAutoAnalysis: () =>
    post<{ enabled: boolean }>('/api/mentor/auto-analysis/toggle', {}),
  // Backend may return {available: false} or {error: "..."} with HTTP 200 —
  // callers must narrow with isFootprintBar() before use.
  getFootprint: (instrument: string, timeframe = '5m') =>
    get<FootprintBar | { available?: boolean; error?: string }>(
      `/api/order-flow/footprint/${instrument}?timeframe=${timeframe}`,
    ),
  getOrderFlowDepth: (instrument: string) =>
    get<OrderFlowDepthSnapshot>(`/api/order-flow/depth/${instrument}`),
  // ── Order Flow history (last N persisted events, newest first) ──────────
  getIcebergEvents: (instrument: string, limit = 20) =>
    get<IcebergEventHistory[]>(`/api/order-flow/iceberg/${instrument}?limit=${limit}`),
  getAbsorptionEvents: (instrument: string, limit = 20) =>
    get<AbsorptionEventHistory[]>(`/api/order-flow/absorption/${instrument}?limit=${limit}`),
  getSpoofingEvents: (instrument: string, limit = 20) =>
    get<SpoofingEventHistory[]>(`/api/order-flow/spoofing/${instrument}?limit=${limit}`),
  getDistributionEvents: (instrument: string, limit = 20) =>
    get<DistributionEventHistory[]>(`/api/order-flow/distribution/${instrument}?limit=${limit}`),
  getMomentumEvents: (instrument: string, limit = 20) =>
    get<MomentumEventHistory[]>(`/api/order-flow/momentum/${instrument}?limit=${limit}`),
  getCycleEvents: (instrument: string, limit = 20) =>
    get<CycleEventHistory[]>(`/api/order-flow/cycle/${instrument}?limit=${limit}`),
  getTrailingStats: (days = 7) =>
    get<TrailingStopStats>(`/api/mentor/simulation/trailing-stats?days=${days}`),
  getCorrelationStatus: () =>
    get<CorrelationStatus>('/api/correlation/oil-nasdaq/status'),
  getCorrelationHistory: () =>
    get<CorrelationSignal[]>('/api/correlation/oil-nasdaq/history'),
  refreshDb: () => post<{ status: string; message: string }>('/api/backtest/refresh-db', {}),
  purgeInstrument: (instrument: string) =>
    del<{ instrument?: string; purged?: number; error?: string }>(
      `/api/backtest/purge/${encodeURIComponent(instrument)}`
    ),
  runBacktest: (params: {
    instrument?: string; timeframe?: string; pyramiding?: number; continuous?: boolean;
    n1?: number; n2?: number; nsc?: number; nsv?: number; qty?: number; capital?: number; pointValue?: number;
    nextBarEntry?: boolean; emaFilterPeriod?: number; stopLossPoints?: number;
    atrTrailingStop?: boolean; atrMultiplier?: number; atrPeriod?: number;
    bollingerTakeProfit?: boolean; bollingerLength?: number;
    closeEndOfDay?: boolean; closeEndOfWeek?: boolean;
    enableSmcFilter?: boolean; minHtf?: string; useH1Levels?: boolean; useH4Levels?: boolean; useDailyLevels?: boolean;
    swingLengthHtf?: number; requireCloseNearLevel?: boolean; nearThresholdMode?: 'ATR' | 'POINTS' | 'TICKS';
    nearThresholdValue?: number; nearThresholdAtrPeriod?: number; tickSize?: number;
    requireBullishStructureForLong?: boolean; requireBearishStructureForShort?: boolean;
    useBos?: boolean; useChoch?: boolean; useOrderBlocks?: boolean; minConfirmationScore?: number;
    maxLevelAgeBars?: number; debugLogging?: boolean;
    entryOnSignal?: number; debug?: boolean; fromDate?: string;
  } = {}) => {
    const p = new URLSearchParams();
    Object.entries(params).forEach(([k, v]) => { if (v !== undefined) p.set(k, String(v)); });
    return get<BacktestResult>(`/api/backtest/wt?${p.toString()}`);
  },

  // ── Playbook ──────────────────────────────────────────────────────
  getPlaybook: (instrument: string, timeframe: string) =>
    get<PlaybookEvaluation>(`/api/playbook/${instrument}/${timeframe}`),

  getFullPlaybook: (instrument: string, timeframe: string) =>
    get<FinalVerdict>(`/api/playbook/${instrument}/${timeframe}/full`),

  // ── Strategy engine (new probabilistic engine — read-only) ────────
  getStrategyDecision: (instrument: string, timeframe: string) =>
    get<StrategyDecisionView>(`/api/strategy/${instrument}/${timeframe}`),

  getStrategyComparison: (instrument: string, timeframe: string) =>
    get<DecisionComparisonView>(`/api/strategy/${instrument}/${timeframe}/compare`),

  // ── Trade Simulations (Phase 1 read-only wrappers — Phase 2 will migrate UI) ────
  getRecentSimulations: (limit?: number) =>
    get<TradeSimulationView[]>(
      `/api/simulations/recent${limit !== undefined ? `?limit=${limit}` : ''}`,
    ),
  getSimulationsByInstrument: (instrument: string, limit?: number) =>
    get<TradeSimulationView[]>(
      `/api/simulations/by-instrument/${encodeURIComponent(instrument)}${
        limit !== undefined ? `?limit=${limit}` : ''
      }`,
    ),
  getSimulationByReview: async (
    reviewId: number,
    type: 'SIGNAL' | 'AUDIT' = 'SIGNAL',
  ): Promise<TradeSimulationView | null> => {
    // - No `credentials` option: the backend controller advertises
    //   `@CrossOrigin(origins = "*")`, and browsers reject credentialed CORS
    //   responses when the allowed origin is the wildcard.
    // - `cache: 'no-store'` to match the shared `get<T>()` helper — simulation
    //   status changes on every scheduler tick, and server-rendered Next.js
    //   contexts would otherwise cache and surface stale status after a
    //   terminal transition. Sibling wrappers (`getRecentSimulations`,
    //   `getSimulationsByInstrument`) already inherit this from `get<T>()`.
    const response = await fetch(
      `${BASE}/api/simulations/by-review/${reviewId}?type=${encodeURIComponent(type)}`,
      { cache: 'no-store' },
    );
    if (response.status === 404) return null;
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return (await response.json()) as TradeSimulationView;
  },

  // ── Trade Decisions (agent orchestrator + narrator, read-only) ────
  getRecentDecisions: (limit?: number) =>
    get<TradeDecision[]>(
      `/api/decisions/recent${limit !== undefined ? `?limit=${limit}` : ''}`
    ),
  getDecisionsByInstrument: (instrument: string, limit?: number) =>
    get<TradeDecision[]>(
      `/api/decisions/by-instrument/${encodeURIComponent(instrument)}${
        limit !== undefined ? `?limit=${limit}` : ''
      }`
    ),
  getDecisionById: (id: number) =>
    get<TradeDecision>(`/api/decisions/${id}`),
  getDecisionThread: (
    instrument: string,
    timeframe: string,
    direction: string,
    zoneName: string,
  ) =>
    get<TradeDecision[]>(
      `/api/decisions/thread?instrument=${encodeURIComponent(instrument)}` +
        `&timeframe=${encodeURIComponent(timeframe)}` +
        `&direction=${encodeURIComponent(direction)}` +
        `&zoneName=${encodeURIComponent(zoneName)}`
    ),

  // ── External Setups (Claude wakeup → user validation) ──────────────────
  getExternalSetupStatus: () =>
    get<{
      enabled: boolean;
      autoExecuteOnHighConfidence: boolean;
      defaultTtlSeconds: number;
      ttlPerInstrument: Record<string, number>;
      rateLimitPerMinute: number;
      hasDefaultBrokerAccount: boolean;
    }>('/api/external-setups/status'),
  listExternalSetups: (status?: string, limit = 50) =>
    get<ExternalSetupSummary[]>(
      `/api/external-setups?${status ? `status=${encodeURIComponent(status)}&` : ''}limit=${limit}`
    ),
  getExternalSetup: (id: number) =>
    get<{ summary: ExternalSetupSummary; payloadJson: string | null }>(
      `/api/external-setups/${id}`
    ),
  validateExternalSetup: (id: number, body: ExternalSetupValidateRequest) =>
    post<ExternalSetupSummary>(`/api/external-setups/${id}/validate`, body),
  rejectExternalSetup: (id: number, body: ExternalSetupRejectRequest) =>
    post<ExternalSetupSummary>(`/api/external-setups/${id}/reject`, body),

  // ── Quant 7-Gates evaluator ────────────────────────────────────────────
  // Backend: com.riskdesk.presentation.quant.QuantGateController
  // /snapshot/{instr} runs a fresh scan; /history/{instr}?hours=2 returns the
  // in-memory ring buffer.
  getQuantSnapshot: (instrument: string) =>
    get<QuantSnapshotView>(`/api/quant/snapshot/${instrument}`),
  getQuantHistory: (instrument: string, hours = 2) =>
    get<QuantSnapshotView[]>(`/api/quant/history/${instrument}?hours=${hours}`),
  // Triggers a tier-2 AI advisor call for the latest snapshot. Returns
  // immediately with the cached verdict if one is fresh (≤ 30 s window).
  askQuantAiAdvice: (instrument: string) =>
    post<AdviceView & { instrument: string }>(`/api/quant/ai-advice/${instrument}`, {}),

  // ── Quant auto-arm pipeline (PR #303) ──────────────────────────────────
  // Backend: com.riskdesk.presentation.quant.QuantAutoArmController
  fireAutoArm: (executionId: number) =>
    post<AutoArmStatusResponse>(`/api/quant/auto-arm/${executionId}/fire`, {}),
  cancelAutoArm: (executionId: number) =>
    post<AutoArmStatusResponse>(`/api/quant/auto-arm/${executionId}/cancel`, {}),
  listActiveAutoArms: () =>
    get<AutoArmStatusResponse[]>(`/api/quant/auto-arm/active`),
};

// ── Quant auto-arm types (PR #303) ──────────────────────────────────────
export interface AutoArmStatusResponse {
  executionId: number;
  instrument: string;
  /** Derived from action — "LONG" or "SHORT". */
  direction: string;
  /** Raw IBKR action string — "BUY" / "SELL". */
  action: string;
  status: string;
  statusReason: string | null;
  entry: string | number | null;
  stopLoss: string | number | null;
  takeProfit: string | number | null;
  quantity: number | null;
  armedAt: string | null;
  autoSubmitAt: string | null;
  /** Null when auto-submit is disabled. */
  secondsUntilAutoSubmit: number | null;
}

/** Live state of an arm pushed via STOMP. */
export type AutoArmStreamKind = 'ARMED' | 'CANCELLED' | 'FIRED' | 'EXPIRED' | 'AUTO_SUBMITTED';
export interface AutoArmStreamPayload {
  kind: AutoArmStreamKind;
  instrument: string;
  executionId: number;
  /** Only present on ARMED. */
  direction?: string;
  entry?: string | null;
  stopLoss?: string | null;
  takeProfit1?: string | null;
  takeProfit2?: string | null;
  sizePercent?: number;
  armedAt?: string;
  expiresAt?: string;
  autoSubmitAt?: string | null;
  reasoning?: string;
  /** Lifecycle events carry this. */
  reason?: string;
  changedAt?: string;
}

// ── External Setup types ────────────────────────────────────────────────
export type ExternalSetupStatus =
  | 'PENDING'
  | 'VALIDATED'
  | 'EXECUTED'
  | 'EXECUTION_FAILED'
  | 'REJECTED'
  | 'EXPIRED';

export type ExternalSetupConfidence = 'LOW' | 'MEDIUM' | 'HIGH';
export type ExternalSetupSourceTag = 'CLAUDE_WAKEUP';
export type ExternalSetupDirection = 'LONG' | 'SHORT';

export interface ExternalSetupSummary {
  id: number;
  setupKey: string;
  instrument: string;
  direction: ExternalSetupDirection;
  entry: number;
  stopLoss: number | null;
  takeProfit1: number | null;
  takeProfit2: number | null;
  confidence: ExternalSetupConfidence;
  triggerLabel: string | null;
  source: ExternalSetupSourceTag;
  sourceRef: string | null;
  status: ExternalSetupStatus;
  submittedAt: string;
  expiresAt: string;
  validatedAt: string | null;
  validatedBy: string | null;
  rejectionReason: string | null;
  tradeExecutionId: number | null;
  executedAtPrice: number | null;
  autoExecuted: boolean;
}

export interface ExternalSetupValidateRequest {
  quantity?: number;
  brokerAccountId?: string;
  overrideEntryPrice?: number;
  validatedBy?: string;
}

export interface ExternalSetupRejectRequest {
  reason?: string;
  rejectedBy?: string;
}

export interface ExternalSetupWebSocketMessage {
  event: 'SETUP_NEW' | 'SETUP_VALIDATED' | 'SETUP_REJECTED' | 'SETUP_EXPIRED';
  setup: ExternalSetupSummary;
}

// ── Playbook Types ────────────────────────────────────────────────────

export interface PlaybookEvaluation {
  filters: FilterResult;
  setups: SetupCandidate[];
  bestSetup: SetupCandidate | null;
  plan: PlaybookPlan | null;
  checklist: ChecklistItem[];
  checklistScore: number;
  verdict: string;
  evaluatedAt: string;
}

export interface FilterResult {
  biasAligned: boolean;
  swingBias: string;
  tradeDirection: 'LONG' | 'SHORT';
  structureClean: boolean;
  validBreaks: number;
  fakeBreaks: number;
  totalBreaks: number;
  sizeMultiplier: number;
  vaPositionOk: boolean;
  vaPosition: 'ABOVE_VA' | 'BELOW_VA' | 'INSIDE_VA';
  allFiltersPass: boolean;
}

export interface SetupCandidate {
  type: 'ZONE_RETEST' | 'LIQUIDITY_SWEEP' | 'BREAK_RETEST';
  zoneName: string;
  zoneHigh: number;
  zoneLow: number;
  zoneMid: number;
  distanceFromPrice: number;
  priceInZone: boolean;
  reactionVisible: boolean;
  orderFlowConfirms: boolean;
  rrRatio: number;
  checklistScore: number;
}

export interface PlaybookPlan {
  entryPrice: number;
  stopLoss: number;
  takeProfit1: number;
  takeProfit2: number;
  rrRatio: number;
  riskPercent: number;
  slRationale: string;
  tp1Rationale: string;
}

export interface ChecklistItem {
  step: number;
  label: string;
  status: 'PASS' | 'FAIL' | 'WAITING';
  detail: string;
}

export interface AgentVerdictView {
  agentName: string;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  bias: 'LONG' | 'SHORT' | null;
  reasoning: string;
}

export interface FinalVerdict {
  verdict: string;
  adjustedPlan: PlaybookPlan | null;
  sizePercent: number;
  agentVerdicts: AgentVerdictView[];
  warnings: string[];
  eligibility: string;
}

// ── Strategy Engine (new probabilistic engine) ────────────────────────────

export type StrategyLayer = 'CONTEXT' | 'ZONE' | 'TRIGGER';
export type DecisionType =
  | 'NO_TRADE'
  | 'MONITORING'
  | 'PAPER_TRADE'
  | 'HALF_SIZE'
  | 'FULL_SIZE';

export interface StrategyAgentVote {
  agentId: string;
  layer: StrategyLayer;
  directionalVote: number;     // -100..+100
  confidence: number;          // 0..1
  abstain: boolean;
  evidence: string[];
  vetoReason: string | null;
}

export interface StrategyMechanicalPlan {
  direction: 'LONG' | 'SHORT';
  entry: number;
  stopLoss: number;
  takeProfit1: number;
  takeProfit2: number;
  rrRatio: number;
}

export interface StrategyDecisionView {
  candidatePlaybookId: string | null;   // "LSAR" | "SBDR" | null
  votes: StrategyAgentVote[];
  layerScores: Partial<Record<StrategyLayer, number>>;
  finalScore: number;                    // -100..+100
  decision: DecisionType;
  direction: 'LONG' | 'SHORT' | null;
  plan: StrategyMechanicalPlan | null;
  vetoReasons: string[];
  evaluatedAt: string;
}

export type ComparisonAgreement =
  | 'BOTH_NO_TRADE'
  | 'BOTH_TRADEABLE_SAME_DIRECTION'
  | 'BOTH_TRADEABLE_OPPOSITE_DIRECTION'
  | 'LEGACY_ONLY_TRADEABLE'
  | 'NEW_ONLY_TRADEABLE'
  | 'INCONCLUSIVE';

export interface DecisionComparisonView {
  instrument: string;
  timeframe: string;
  evaluatedAt: string;
  legacyPlaybook: PlaybookEvaluation | null;
  strategyDecision: StrategyDecisionView | null;
  agreement: ComparisonAgreement;
}

// ── Trade Decisions (agent orchestrator + narrator) ───────────────────────
//
// Backend counterpart: com.riskdesk.domain.decision.model.TradeDecision
// Endpoints: /api/decisions/{recent,by-instrument/{instrument},{id},thread}
//
// Agent verdicts and warnings are persisted as JSON strings on the row so the
// decision history stays self-contained even if upstream models evolve.

export type TradeDecisionStatus = 'NARRATING' | 'DONE' | 'ERROR';

/** One entry from the serialized {@code agentVerdictsJson} column. */
export interface TradeDecisionAgentVerdict {
  agentName: string;
  confidence: 'HIGH' | 'MEDIUM' | 'LOW';
  bias: 'LONG' | 'SHORT' | null;
  reasoning: string;
  adjustments?: unknown;
}

export interface TradeDecision {
  id: number | null;
  revision: number;
  createdAt: string;

  instrument: string;
  timeframe: string;
  direction: string;        // "LONG" | "SHORT" | "FLAT"
  setupType: string | null; // e.g. "ZONE_RETEST"
  zoneName: string | null;

  eligibility: string;      // "ELIGIBLE" | "INELIGIBLE" | "BLOCKED"
  sizePercent: number;
  verdict: string;
  agentVerdictsJson: string | null;
  warningsJson: string | null;

  entryPrice: number | null;
  stopLoss: number | null;
  takeProfit1: number | null;
  takeProfit2: number | null;
  rrRatio: number | null;

  narrative: string | null;
  narrativeModel: string | null;
  narrativeLatencyMs: number | null;

  status: TradeDecisionStatus;
  errorMessage: string | null;
}
