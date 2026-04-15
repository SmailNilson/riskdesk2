import { API_BASE } from '@/app/lib/runtimeConfig';

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
  severity: 'INFO' | 'WARNING' | 'DANGER';
  category: string;
  message: string;
  instrument: string | null;
  timestamp: string;
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
  executionEligibilityStatus: 'NOT_EVALUATED' | 'ELIGIBLE' | 'INELIGIBLE' | null;
  executionEligibilityReason: string | null;
  simulationStatus: 'PENDING_ENTRY' | 'ACTIVE' | 'WIN' | 'LOSS' | 'MISSED' | 'CANCELLED' | 'REVERSED' | null;
  activationTime: string | null;
  resolutionTime: string | null;
  maxDrawdownPoints: number | null;
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

export const api = {
  getPortfolioSummary: (accountId?: string) =>
    get<PortfolioSummary>(`/api/positions/summary${accountId ? `?accountId=${encodeURIComponent(accountId)}` : ''}`),
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
  getLivePrice: (instrument: string) => get<LivePriceView>(`/api/live-price/${instrument}`),
  getDxyLatest: () => get<DxySnapshotView>('/api/market/dxy/latest'),
  getDxyHistory: (from: string, to: string) =>
    get<DxySnapshotView[]>(`/api/market/dxy/history?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`),
  getDxyHealth: () => get<DxyHealthView>('/api/market/dxy/health'),
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
  getFootprint: (instrument: string, timeframe = '5m') =>
    get<FootprintBar>(`/api/order-flow/footprint/${instrument}?timeframe=${timeframe}`),
  refreshDb: () => post<{ status: string; message: string }>('/api/backtest/refresh-db', {}),
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
};

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
