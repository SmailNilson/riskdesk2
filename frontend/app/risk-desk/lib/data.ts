// Mock data + types for the RiskDesk redesign. Lives as the SSR initial state
// inside RiskDeskContext until live fetches resolve.

export interface Instrument {
  sym: string;
  name: string;
  last: number;
  chg: number;
  pip: number;
  tickVal: number;
  px: number;
  vol: string;
  session: string;
}

export interface WatchItem {
  sym: string;
  px: number;
  chg: number;
}

export interface Candle {
  time: number;
  open: number;
  high: number;
  low: number;
  close: number;
  volume: number;
  delta: number;
}

export interface OrderBlockSmc {
  type: 'bull' | 'bear';
  t1: number;
  t2: number;
  hi: number;
  lo: number;
  mit: boolean;
  label: string;
}
export interface FvgSmc {
  t1: number;
  t2: number;
  hi: number;
  lo: number;
  filled: boolean;
  label: string;
}
export interface StructureSmc {
  t: number;
  px: number;
  kind: 'BOS' | 'CHoCH';
  dir: 'up' | 'down';
}
export interface LiquiditySmc {
  px: number;
  label: string;
  t: number;
}
export interface Smc {
  orderBlocks: OrderBlockSmc[];
  fvgs: FvgSmc[];
  structure: StructureSmc[];
  liquidity: LiquiditySmc[];
}

export interface Indicators {
  ema9: { v: number; vs: string; strength: number };
  ema20: { v: number; vs: string; strength: number };
  ema50: { v: number; vs: string; strength: number };
  vwap: { v: number; vs: string; strength: number; dev: number };
  supertrend: { dir: string; flipped: number; strength: number };
  cmf: { v: number; label: string };
  rsi: { v: number; zone: string };
  macd: { hist: number[]; cross: string };
  atr: { v: number; pctRange: number };
  bb: { upper: number; lower: number; basis: number; width: number; squeeze: boolean };
  ichimoku: { cloud: string; future: string; twist: boolean };
  pivots: { p: number; r1: number; r2: number; s1: number; s2: number };
}

export interface Position {
  id: string;
  sym: string;
  side: 'long' | 'short';
  qty: number;
  entry: number;
  sl: number;
  tp1: number;
  tp2: number;
  last: number;
  opened: string;
  trail: string;
  state: string;
}

export interface Portfolio {
  unreal: number;
  realToday: number;
  totalPnL: number;
  exposure: number;
  margin: number;
  marginPct: number;
  buyingPower: number;
  dayDD: number;
  dayDDLimit: number;
  weekPnL: number;
  monthPnL: number;
  exposureCap: number;
}

export interface ReviewPlan {
  side: 'long' | 'short';
  entry: number;
  sl: number;
  tp1: number;
  tp2: number;
  rr1: number;
  rr2: number;
  qty: number;
}
export interface Review {
  id: string;
  sym: string;
  tf: string;
  at: string;
  verdict: 'TAKE' | 'WATCH' | 'SKIP' | 'PENDING';
  confidence: number;
  eligible: boolean;
  confluence: string[];
  plan: ReviewPlan | null;
  rationale: string;
  risks: string[];
  grouped: number;
  reasonHold?: string;
  isManage?: boolean;
}

export interface OrderFlowEvent {
  t: string;
  side: 'buy' | 'sell';
  sz: number;
  px: number;
  kind: 'aggr' | 'lift' | 'hit' | 'sweep' | 'abs';
  agg: number;
  note?: string;
}

export interface DomLevel {
  px: number;
  sz: number;
}
export interface Dom {
  bids: DomLevel[];
  asks: DomLevel[];
  last: number;
  spread: number;
}

export interface FootprintRow {
  px: number;
  b: number;
  a: number;
  poc?: boolean;
}
export interface FootprintCol {
  rows: FootprintRow[];
  dir: 'up' | 'down';
  delta: number;
}

export interface Correlations {
  rows: string[];
  cols: string[];
  data: number[][];
}

export interface Backtest {
  range: string;
  trades: number;
  winRate: number;
  avgRR: number;
  expectancy: number;
  sharpe: number;
  maxDD: number;
  profitFactor: number;
  bestSetup: string;
  worstHour: string;
  bestHour: string;
  equity: number[];
}

export interface TrailingMode {
  name: string;
  winRate: number;
  avgRR: number;
  profitFactor: number;
  expectancy: number;
  active: boolean;
}
export interface Trailing {
  modes: TrailingMode[];
  recommendation: string;
}

export interface FlashCrash {
  level: 'calm' | 'watch' | 'alert' | 'halt';
  reasons: string[];
  thresholds: { sigma: number; dropPct: number; minVol: number };
  current: { sigma: number; dropPct: number; vol: number };
  history: Array<{ t: string; level: string; note: string }>;
  onAlert: { autoFlat: boolean; requireConfirm: boolean; cancelPending: boolean; lockNewEntries: number };
}

export interface PlaybookEntry {
  id: string;
  name: string;
  tf: string;
  winRate: number;
  trades: number;
  lastHit: string;
  tags: string[];
  active: boolean;
}

export interface Strategy {
  regime: string;
  confidence: number;
  factors: Array<{ name: string; v: number; label: string }>;
  recommendation: string;
}

export interface DxyData {
  px: number;
  chg: number;
  chgPct: number;
  series: number[];
  bias: string;
  effect: string;
}

export interface Ibkr {
  account: string;
  netLiq: number;
  cashAvail: number;
  initMargin: number;
  maintMargin: number;
  realized: number;
  unrealized: number;
  buyingPower: number;
  conn: string;
  latencyMs: number;
}

export interface OrderFlowProd {
  delta: { sym: string; buy: number; sell: number; cum: number; real: number; buyPct: number; sellPct: number };
  depth: { sym: string; bid: number; ask: number; spread: number; imbalance: number; mid: number };
  smcCycle: Array<{ sym: string; dir: 'BULL' | 'BEAR'; phase: number; done: boolean; conf: number; age: string; note?: string }>;
  distAccum: Array<{ kind: 'ACCUM' | 'DIST'; sym: string; dir: 'BULL' | 'BEAR'; mult: number; avg: number; conf: number; age: string }>;
}

export interface MicroEvent {
  sym: string;
  dir: 'BULL' | 'BEAR';
  score: number;
  delta?: number;
  move?: number;
  age: string;
  note?: string;
}
export interface MicroEvents {
  momentum: MicroEvent[];
  iceberg: MicroEvent[];
  absorption: MicroEvent[];
  spoofing: MicroEvent[];
  liveFeed: MicroEvent[];
}

export interface RiskAlert {
  sym: string | null;
  text: string;
  level: 'warn' | 'high';
}

export interface AlertItem {
  t: string;
  sev: 'info' | 'warn' | 'ok' | 'alert';
  src: string;
  msg: string;
}

export interface Rollover {
  symbol: string;
  front: string;
  next: string;
  daysToExpiry: number;
  openInterestShift: number;
  recommended: string;
  basis: number;
}

// ────────────────────────────────────────────────────────────────────────
// Seed PRNG
// ────────────────────────────────────────────────────────────────────────
function mulberry32(seed: number): () => number {
  let t = seed >>> 0;
  return function () {
    t = (t + 0x6d2b79f5) >>> 0;
    let r = Math.imul(t ^ (t >>> 15), 1 | t);
    r = (r + Math.imul(r ^ (r >>> 7), 61 | r)) ^ r;
    return ((r ^ (r >>> 14)) >>> 0) / 4294967296;
  };
}

// Anchor "now" so SSR ↔ CSR don't drift on initial render.
const NOW_ANCHOR = 1745625600; // epoch seconds

export const INSTRUMENTS: Instrument[] = [
  { sym: 'MCL', name: 'Micro WTI Crude', last: 78.42, chg: +0.83, pip: 0.01, tickVal: 1.0, px: 78.42, vol: '184.2K', session: 'RTH' },
  { sym: 'MGC', name: 'Micro Gold', last: 2418.6, chg: -4.2, pip: 0.1, tickVal: 1.0, px: 2418.6, vol: '92.4K', session: 'RTH' },
  { sym: 'E6', name: 'Euro FX', last: 1.0842, chg: +0.0014, pip: 0.0001, tickVal: 6.25, px: 1.0842, vol: '47.1K', session: 'RTH' },
  { sym: 'MNQ', name: 'Micro Nasdaq-100', last: 18642.25, chg: +124.75, pip: 0.25, tickVal: 0.5, px: 18642.25, vol: '421K', session: 'RTH' },
];

export const WATCHLIST: WatchItem[] = [
  { sym: 'DXY', px: 104.62, chg: -0.18 },
  { sym: 'ES', px: 5274.5, chg: +14.25 },
  { sym: 'NQ', px: 18638.0, chg: +120.5 },
  { sym: 'CL', px: 78.41, chg: +0.82 },
  { sym: 'GC', px: 2418.4, chg: -4.1 },
  { sym: 'ZB', px: 117.2, chg: +0.31 },
  { sym: 'BTC', px: 67482, chg: +1284 },
  { sym: 'VIX', px: 14.82, chg: -0.41 },
  { sym: '10Y', px: 4.231, chg: +0.018 },
];

function genCandles(seed = 42, count = 240, startPrice = 78.1): Candle[] {
  const rng = mulberry32(seed);
  const candles: Candle[] = [];
  let price = startPrice;
  let trend = 0.005;
  const now = Math.floor(NOW_ANCHOR / 60) * 60;
  const start = now - count * 60;
  for (let i = 0; i < count; i++) {
    if (i % 35 === 0) trend = (rng() - 0.45) * 0.025;
    const drift = trend * (0.5 + rng());
    const vola = 0.06 + rng() * 0.12;
    const open = price;
    const close = open + drift + (rng() - 0.5) * vola;
    const high = Math.max(open, close) + rng() * vola * 0.8;
    const low = Math.min(open, close) - rng() * vola * 0.8;
    const vol = Math.floor(800 + rng() * 4200 + (Math.abs(close - open) > 0.08 ? 2000 : 0));
    candles.push({
      time: start + i * 60,
      open: +open.toFixed(2),
      high: +high.toFixed(2),
      low: +low.toFixed(2),
      close: +close.toFixed(2),
      volume: vol,
      delta: Math.floor((rng() - 0.5) * vol * 0.6),
    });
    price = close;
  }
  return candles;
}

export function ema(values: number[], period: number): number[] {
  const k = 2 / (period + 1);
  const out: number[] = [];
  let prev = values[0];
  for (let i = 0; i < values.length; i++) {
    prev = i === 0 ? values[i] : values[i] * k + prev * (1 - k);
    out.push(prev);
  }
  return out;
}

function genSMC(candles: Candle[]): Smc {
  const n = candles.length;
  return {
    orderBlocks: [
      { type: 'bull', t1: candles[n - 78].time, t2: candles[n - 8].time, hi: 78.18, lo: 78.04, mit: false, label: 'OB·10m·bull' },
      { type: 'bear', t1: candles[n - 142].time, t2: candles[n - 60].time, hi: 78.66, lo: 78.54, mit: true, label: 'OB·1H·bear' },
      { type: 'bull', t1: candles[n - 200].time, t2: candles[n - 30].time, hi: 77.92, lo: 77.78, mit: false, label: 'OB·1H·bull' },
    ],
    fvgs: [
      { t1: candles[n - 64].time, t2: candles[n - 40].time, hi: 78.36, lo: 78.28, filled: false, label: 'FVG bull' },
      { t1: candles[n - 110].time, t2: candles[n - 95].time, hi: 78.52, lo: 78.46, filled: true, label: 'FVG bear' },
    ],
    structure: [
      { t: candles[n - 168].time, px: 78.62, kind: 'BOS', dir: 'down' },
      { t: candles[n - 96].time, px: 77.84, kind: 'CHoCH', dir: 'up' },
      { t: candles[n - 22].time, px: 78.48, kind: 'BOS', dir: 'up' },
    ],
    liquidity: [
      { px: 78.66, label: 'BSL', t: candles[n - 1].time },
      { px: 77.88, label: 'SSL', t: candles[n - 1].time },
    ],
  };
}

export const INDICATORS: Indicators = {
  ema9: { v: 78.39, vs: 'above', strength: 0.62 },
  ema20: { v: 78.31, vs: 'above', strength: 0.74 },
  ema50: { v: 78.18, vs: 'above', strength: 0.81 },
  vwap: { v: 78.34, vs: 'above', strength: 0.55, dev: +0.08 },
  supertrend: { dir: 'up', flipped: 12, strength: 0.78 },
  cmf: { v: 0.18, label: 'buying pressure' },
  rsi: { v: 58.4, zone: 'neutral-bull' },
  macd: { hist: [0.02, 0.04, 0.05, 0.03, 0.06, 0.08, 0.07, 0.09, 0.06, 0.04, -0.01, -0.03, 0.02, 0.05, 0.07], cross: 'bull' },
  atr: { v: 0.142, pctRange: 0.62 },
  bb: { upper: 78.58, lower: 78.1, basis: 78.34, width: 0.48, squeeze: false },
  ichimoku: { cloud: 'above', future: 'bullish', twist: false },
  pivots: { p: 78.3, r1: 78.48, r2: 78.66, s1: 78.12, s2: 77.94 },
};

export const POSITIONS: Position[] = [
  { id: 'p1', sym: 'MCL', side: 'long', qty: 4, entry: 78.21, sl: 78.04, tp1: 78.42, tp2: 78.62, last: 78.42, opened: '09:42:18', trail: 'ATR×1.2', state: 'tp1-hit' },
  { id: 'p2', sym: 'MGC', side: 'long', qty: 2, entry: 2412.4, sl: 2406.8, tp1: 2422.0, tp2: 2434.0, last: 2418.6, opened: '08:51:04', trail: 'structure', state: 'running' },
  { id: 'p3', sym: 'E6', side: 'short', qty: 3, entry: 1.0858, sl: 1.0871, tp1: 1.0838, tp2: 1.082, last: 1.0842, opened: '10:12:55', trail: 'swing-be', state: 'running' },
];

export const PORTFOLIO: Portfolio = {
  unreal: +428.5,
  realToday: +312.0,
  totalPnL: +740.5,
  exposure: 22418.0,
  margin: 4862.0,
  marginPct: 0.27,
  buyingPower: 18482.0,
  dayDD: -184.0,
  dayDDLimit: -600.0,
  weekPnL: +2148.0,
  monthPnL: +5621.0,
  exposureCap: 50000,
};

export const REVIEWS: Review[] = [
  { id: 'r1', sym: 'MCL', tf: '10m', at: '10:42', verdict: 'TAKE', confidence: 0.78, eligible: true,
    confluence: ['EMA9>20>50 stack', 'VWAP reclaim', '1H bull OB tap', 'FVG·10m bull', 'CMF +0.18', 'RSI 58 → 64 div'],
    plan: { side: 'long', entry: 78.36, sl: 78.18, tp1: 78.58, tp2: 78.74, rr1: 1.22, rr2: 2.11, qty: 3 },
    rationale: 'Reclaimed VWAP with bullish OB tap, structural BOS up at 78.48 confirmed. CMF turning positive on rising volume.',
    risks: ['DXY ticking up could cap upside above 78.62', 'BSL above last swing — sweep risk'],
    grouped: 4 },
  { id: 'r2', sym: 'MCL', tf: '1H', at: '10:30', verdict: 'WATCH', confidence: 0.52, eligible: false,
    confluence: ['1H bullish bias intact', 'Approaching session high'],
    plan: null,
    rationale: 'Higher-timeframe trend supportive but price extended into liquidity. Wait for pullback to 78.30 zone.',
    risks: [], grouped: 2, reasonHold: 'Cooldown 4m' },
  { id: 'r3', sym: 'MGC', tf: '10m', at: '10:28', verdict: 'TAKE', confidence: 0.71, eligible: true,
    confluence: ['Trail to BE', 'Structure shift up', 'TP1 hit, scaled 50%'],
    plan: { side: 'long', entry: 2418.4, sl: 2412.4, tp1: 2426.0, tp2: 2434.0, rr1: 1.27, rr2: 2.6, qty: 1 },
    rationale: 'Manage existing MGC long — trail under 10m structure. Target 2434 H4 resistance.',
    risks: ['Yields broke higher last 30m — gold headwind'],
    grouped: 3, isManage: true },
  { id: 'r4', sym: 'E6', tf: '5m', at: '10:18', verdict: 'SKIP', confidence: 0.34, eligible: false,
    confluence: ['RSI overbought 5m', 'DXY bouncing off support'],
    plan: null,
    rationale: 'Setup conflicts with USD strength resuming. Wait for fresh structure.',
    risks: [], grouped: 1, reasonHold: 'Conflict with portfolio E6 short' },
  { id: 'r5', sym: 'MNQ', tf: '10m', at: '09:55', verdict: 'WATCH', confidence: 0.58, eligible: false,
    confluence: ['Above 9/20/50', 'SuperTrend up', 'Approaching 18,650 BSL'],
    plan: null,
    rationale: 'Strong trend day on Nasdaq — wait for pullback into FVG at 18,610-18,620.',
    risks: [], grouped: 5 },
];

export const ORDER_FLOW: OrderFlowEvent[] = [
  { t: '10:42:18', side: 'buy', sz: 18, px: 78.42, kind: 'aggr', agg: 0.78, note: 'abs at offer' },
  { t: '10:42:09', side: 'buy', sz: 4, px: 78.41, kind: 'lift', agg: 0.21 },
  { t: '10:41:54', side: 'sell', sz: 7, px: 78.4, kind: 'hit', agg: 0.32 },
  { t: '10:41:40', side: 'buy', sz: 22, px: 78.41, kind: 'sweep', agg: 0.92, note: 'swept 3 lvls' },
  { t: '10:41:21', side: 'buy', sz: 6, px: 78.4, kind: 'lift', agg: 0.31 },
  { t: '10:41:02', side: 'sell', sz: 11, px: 78.39, kind: 'hit', agg: 0.48 },
  { t: '10:40:48', side: 'sell', sz: 3, px: 78.39, kind: 'hit', agg: 0.18 },
  { t: '10:40:30', side: 'buy', sz: 9, px: 78.4, kind: 'lift', agg: 0.42 },
  { t: '10:40:14', side: 'sell', sz: 14, px: 78.38, kind: 'abs', agg: 0.64, note: 'iceberg refill' },
  { t: '10:39:58', side: 'buy', sz: 5, px: 78.39, kind: 'lift', agg: 0.26 },
  { t: '10:39:41', side: 'buy', sz: 31, px: 78.39, kind: 'sweep', agg: 0.96, note: 'block' },
];

export const CVD = [-218, -202, -184, -152, -118, -94, -72, -48, -12, 24, 58, 96, 118, 142, 178, 212, 248, 284, 318];

export const DOM: Dom = (() => {
  const center = 78.42;
  const rng = mulberry32(7);
  const asks: DomLevel[] = [];
  const bids: DomLevel[] = [];
  for (let i = 1; i <= 12; i++) {
    asks.push({ px: +(center + i * 0.01).toFixed(2), sz: Math.floor(40 + rng() * 220 + (i === 4 ? 380 : 0)) });
  }
  for (let i = 1; i <= 12; i++) {
    bids.push({ px: +(center - i * 0.01).toFixed(2), sz: Math.floor(40 + rng() * 220 + (i === 3 ? 420 : 0)) });
  }
  return { bids, asks, last: center, spread: 0.01 };
})();

export const FOOTPRINT: FootprintCol[] = (() => {
  const rng = mulberry32(11);
  const cols: FootprintCol[] = [];
  for (let c = 0; c < 8; c++) {
    const rows: FootprintRow[] = [];
    for (let p = 0; p < 9; p++) {
      const px = 78.5 - p * 0.02;
      rows.push({ px: +px.toFixed(2), b: Math.floor(rng() * 80), a: Math.floor(rng() * 80) });
    }
    rows[Math.floor(rng() * rows.length)].poc = true;
    cols.push({ rows, dir: rng() > 0.5 ? 'up' : 'down', delta: Math.floor((rng() - 0.5) * 280) });
  }
  return cols;
})();

export const CORRELATIONS: Correlations = {
  rows: ['MCL', 'MGC', 'E6', 'MNQ'],
  cols: ['DXY', 'ES', 'NQ', 'GC', 'CL', 'ZB', 'VIX'],
  data: [
    [-0.42, +0.38, +0.32, +0.21, +0.94, +0.18, -0.31],
    [-0.71, +0.18, +0.22, +0.96, +0.22, +0.38, -0.18],
    [-0.92, +0.41, +0.38, +0.62, +0.18, +0.31, -0.22],
    [-0.38, +0.91, +0.98, +0.22, +0.18, -0.18, -0.62],
  ],
};

export const BACKTEST: Backtest = {
  range: 'Last 30 days',
  trades: 142,
  winRate: 0.624,
  avgRR: 1.84,
  expectancy: 0.62,
  sharpe: 1.91,
  maxDD: -842,
  profitFactor: 2.18,
  bestSetup: 'VWAP reclaim → OB tap (1H bull)',
  worstHour: '12:00–13:00 ET',
  bestHour: '09:30–10:30 ET',
  equity: [0, 40, 82, 124, 98, 132, 176, 210, 248, 294, 278, 318, 356, 398, 432, 402, 438, 488, 524, 564, 608, 648, 684, 720, 712, 754, 798, 842, 884, 920],
};

export const TRAILING: Trailing = {
  modes: [
    { name: 'Fixed', winRate: 0.58, avgRR: 1.62, profitFactor: 1.78, expectancy: 0.48, active: false },
    { name: 'ATR×1.2', winRate: 0.62, avgRR: 1.84, profitFactor: 2.18, expectancy: 0.62, active: true },
    { name: 'ATR×1.5', winRate: 0.59, avgRR: 2.04, profitFactor: 2.06, expectancy: 0.58, active: false },
    { name: 'Structure', winRate: 0.54, avgRR: 2.31, profitFactor: 2.04, expectancy: 0.54, active: false },
    { name: 'Chandelier', winRate: 0.61, avgRR: 1.92, profitFactor: 2.11, expectancy: 0.6, active: false },
    { name: 'BE-after-1R', winRate: 0.65, avgRR: 1.48, profitFactor: 1.92, expectancy: 0.51, active: false },
  ],
  recommendation: 'ATR×1.2 — best expectancy + profit factor. Switch to Structure on trend days when ADX > 25.',
};

export const FLASH_CRASH: FlashCrash = {
  level: 'calm',
  reasons: [],
  thresholds: { sigma: 4.2, dropPct: 0.6, minVol: 5000 },
  current: { sigma: 1.1, dropPct: 0.08, vol: 1840 },
  history: [
    { t: '09:38', level: 'watch', note: '1.8σ down move on ES, vol spike' },
    { t: '09:38', level: 'calm', note: 'resolved within 90s' },
  ],
  onAlert: { autoFlat: true, requireConfirm: true, cancelPending: true, lockNewEntries: 60 },
};

export const PLAYBOOK: PlaybookEntry[] = [
  { id: 'pb1', name: 'VWAP reclaim + OB tap', tf: '10m', winRate: 0.71, trades: 28, lastHit: 'Today 09:48', tags: ['trend', 'mean-rev', 'SMC'], active: true },
  { id: 'pb2', name: 'London open sweep → reversal', tf: '5m', winRate: 0.62, trades: 41, lastHit: 'Yesterday 03:14', tags: ['session', 'SMC', 'reversal'], active: true },
  { id: 'pb3', name: 'FVG fill + structure shift', tf: '1H', winRate: 0.58, trades: 19, lastHit: 'Mon 10:22', tags: ['SMC', 'trend'], active: true },
  { id: 'pb4', name: 'Asia range breakout', tf: '5m', winRate: 0.49, trades: 32, lastHit: 'Yesterday 02:08', tags: ['session', 'breakout'], active: false },
  { id: 'pb5', name: 'RTH gap fade', tf: '5m', winRate: 0.66, trades: 24, lastHit: 'Last Fri', tags: ['session', 'mean-rev'], active: true },
];

export const STRATEGY: Strategy = {
  regime: 'Trend (bullish)',
  confidence: 0.74,
  factors: [
    { name: 'Trend (ADX)', v: 0.78, label: 'ADX 28 / +DI 32' },
    { name: 'Momentum (RSI)', v: 0.62, label: '58.4' },
    { name: 'Vol (ATR)', v: 0.41, label: '0.142 / 60d 35th' },
    { name: 'Breadth (NYSE)', v: 0.66, label: '+0.62 advance' },
    { name: 'DXY tailwind', v: 0.58, label: 'DXY −0.18' },
    { name: 'Session bias', v: 0.71, label: 'RTH am — 71% bull on MCL' },
  ],
  recommendation: 'Lean long, take pullbacks to VWAP/OB. Avoid fading.',
};

export const DXY_DATA: DxyData = {
  px: 104.62,
  chg: -0.18,
  chgPct: -0.17,
  series: [104.92, 104.88, 104.84, 104.81, 104.78, 104.74, 104.71, 104.68, 104.66, 104.64, 104.62, 104.61, 104.62, 104.62],
  bias: 'weakening',
  effect: 'Tailwind for MCL/MGC/E6 longs',
};

export const IBKR: Ibkr = {
  account: 'U7842***',
  netLiq: 28482.14,
  cashAvail: 18482.0,
  initMargin: 4862.0,
  maintMargin: 3848.0,
  realized: +312.0,
  unrealized: +428.5,
  buyingPower: 73928.0,
  conn: 'online',
  latencyMs: 14,
};

export const ORDERFLOW_PROD: OrderFlowProd = {
  delta: { sym: 'MCL', buy: 73, sell: 3, cum: 73, real: +70, buyPct: 0.961, sellPct: 0.039 },
  depth: { sym: 'MCL', bid: 64, ask: 88, spread: 0.01, imbalance: -0.135, mid: 0.62 },
  smcCycle: [
    { sym: 'MCL', dir: 'BULL', phase: 1, done: false, conf: 58, age: '2d ago' },
    { sym: 'MCL', dir: 'BULL', phase: 2, done: false, conf: 52, age: '2d ago' },
    { sym: 'MCL', dir: 'BEAR', phase: 2, done: false, conf: 65, age: '2d ago' },
    { sym: 'MCL', dir: 'BEAR', phase: 1, done: false, conf: 48, age: '2d ago' },
    { sym: 'MCL', dir: 'BULL', phase: 3, done: false, conf: 48, age: '2d ago' },
    { sym: 'MCL', dir: 'BEAR', phase: 3, done: true, conf: 86, age: '2d ago', note: 'move ~4.9min' },
    { sym: 'MCL', dir: 'BEAR', phase: 2, done: false, conf: 70, age: '2d ago' },
  ],
  distAccum: [
    { kind: 'ACCUM', sym: 'MCL', dir: 'BULL', mult: 10, avg: 3.0, conf: 52, age: '2d ago' },
    { kind: 'DIST', sym: 'MCL', dir: 'BEAR', mult: 5, avg: 3.1, conf: 52, age: '2d ago' },
    { kind: 'ACCUM', sym: 'MCL', dir: 'BULL', mult: 5, avg: 3.7, conf: 55, age: '2d ago' },
    { kind: 'ACCUM', sym: 'MCL', dir: 'BULL', mult: 5, avg: 3.2, conf: 53, age: '2d ago' },
    { kind: 'DIST', sym: 'MCL', dir: 'BEAR', mult: 4, avg: 4.2, conf: 60, age: '2d ago' },
  ],
};

export const MICRO_EVENTS: MicroEvents = {
  momentum: [
    { sym: 'MCL', dir: 'BEAR', score: 7.0, delta: -162, move: 0.41, age: '57m ago' },
    { sym: 'MCL', dir: 'BEAR', score: 6.7, delta: -155, move: 0.41, age: '57m ago' },
    { sym: 'MCL', dir: 'BEAR', score: 6.1, delta: -135, move: 0.41, age: '57m ago' },
    { sym: 'MCL', dir: 'BEAR', score: 5.8, delta: -135, move: 0.41, age: '57m ago' },
    { sym: 'MCL', dir: 'BEAR', score: 2.6, delta: -91, move: 0.41, age: '57m ago' },
    { sym: 'MCL', dir: 'BEAR', score: 2.6, delta: -83, move: 0.41, age: '57m ago' },
  ],
  iceberg: [],
  absorption: [
    { sym: 'MCL', dir: 'BULL', score: 2.3, delta: -101, age: '50m ago' },
    { sym: 'MCL', dir: 'BULL', score: 2.3, delta: -101, age: '50m ago' },
    { sym: 'MCL', dir: 'BULL', score: 2.4, delta: 107, age: '50m ago' },
    { sym: 'MCL', dir: 'BULL', score: 2.6, delta: -112, age: '50m ago' },
    { sym: 'MCL', dir: 'BULL', score: 2.7, delta: -117, age: '50m ago' },
    { sym: 'MCL', dir: 'BULL', score: 2.6, delta: -116, age: '50m ago' },
  ],
  spoofing: [],
  liveFeed: [],
};

export const RISK_ALERTS: RiskAlert[] = [
  { sym: 'MGC', text: 'Micro Gold position is 53.8% of total exposure', level: 'warn' },
  { sym: 'MNQ', text: 'Micro E-mini Nasdaq-100 position is 28.4% of t…', level: 'warn' },
  { sym: null, text: 'Margin usage at 67.0% — exceeds threshold', level: 'high' },
];

export const ALERTS: AlertItem[] = [
  { t: '10:42', sev: 'info', src: 'MENTOR', msg: 'MCL 10m TAKE armed — entry 78.36, SL 78.18, TP 78.58 / 78.74' },
  { t: '10:41', sev: 'warn', src: 'RISK', msg: 'MCL approaching session BSL 78.66 — sweep risk elevated' },
  { t: '10:40', sev: 'info', src: 'EXEC', msg: 'MGC TP1 partial filled (1 of 2) @ 2418.6 — trail moved to BE' },
  { t: '10:36', sev: 'info', src: 'FLOW', msg: 'MCL CVD +318 last 10m (was +98) — buyers dominant' },
  { t: '10:32', sev: 'warn', src: 'MACRO', msg: 'EIA crude inventories in 18m — auto-flat MCL? [Y/N]' },
  { t: '10:28', sev: 'ok', src: 'STRAT', msg: 'Regime confirmed Trend (bullish) — confidence 74%' },
  { t: '10:18', sev: 'info', src: 'MENTOR', msg: 'E6 5m SKIP — conflict with portfolio E6 short' },
  { t: '10:12', sev: 'alert', src: 'FLASH', msg: 'ES 1.8σ down spike — WATCH (resolved 90s)' },
  { t: '09:55', sev: 'info', src: 'MENTOR', msg: 'MNQ 10m WATCH — wait for FVG retest 18,610' },
  { t: '09:42', sev: 'ok', src: 'EXEC', msg: 'MCL long 4 @ 78.21 filled — SL 78.04, TP 78.42 / 78.62' },
  { t: '09:30', sev: 'info', src: 'SESS', msg: 'RTH open — bias bull (71% conf)' },
];

export const ROLLOVER: Rollover = {
  symbol: 'MCL',
  front: 'MCLF26',
  next: 'MCLG26',
  daysToExpiry: 4,
  openInterestShift: 0.62,
  recommended: 'Roll within 48h — OI shift past 50%',
  basis: -0.04,
};

// ────────────────────────────────────────────────────────────────────────
// Bundle — used as initial state in RiskDeskContext
// ────────────────────────────────────────────────────────────────────────
export interface RiskDeskMock {
  candles: Candle[];
  ema9: number[];
  ema20: number[];
  ema50: number[];
  smc: Smc;
  instruments: Instrument[];
  watchlist: WatchItem[];
  indicators: Indicators;
  positions: Position[];
  portfolio: Portfolio;
  reviews: Review[];
  orderFlow: OrderFlowEvent[];
  cvd: number[];
  dom: Dom;
  footprint: FootprintCol[];
  correlations: Correlations;
  backtest: Backtest;
  trailing: Trailing;
  flashCrash: FlashCrash;
  playbook: PlaybookEntry[];
  strategy: Strategy;
  dxy: DxyData;
  ibkr: Ibkr;
  orderflowProd: OrderFlowProd;
  microEvents: MicroEvents;
  riskAlerts: RiskAlert[];
  alerts: AlertItem[];
  rollover: Rollover;
}

export function buildMock(seed = 42): RiskDeskMock {
  const candles = genCandles(seed);
  const closes = candles.map((c) => c.close);
  return {
    candles,
    ema9: ema(closes, 9),
    ema20: ema(closes, 20),
    ema50: ema(closes, 50),
    smc: genSMC(candles),
    instruments: INSTRUMENTS,
    watchlist: WATCHLIST,
    indicators: INDICATORS,
    positions: POSITIONS,
    portfolio: PORTFOLIO,
    reviews: REVIEWS,
    orderFlow: ORDER_FLOW,
    cvd: CVD,
    dom: DOM,
    footprint: FOOTPRINT,
    correlations: CORRELATIONS,
    backtest: BACKTEST,
    trailing: TRAILING,
    flashCrash: FLASH_CRASH,
    playbook: PLAYBOOK,
    strategy: STRATEGY,
    dxy: DXY_DATA,
    ibkr: IBKR,
    orderflowProd: ORDERFLOW_PROD,
    microEvents: MICRO_EVENTS,
    riskAlerts: RISK_ALERTS,
    alerts: ALERTS,
    rollover: ROLLOVER,
  };
}

export const RD_MOCK: RiskDeskMock = buildMock();
