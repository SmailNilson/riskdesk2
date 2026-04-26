// Mock market data + scenario seed for the RiskDesk redesign.
// Mirrors the design bundle's data.jsx but typed and import-friendly.

export interface Instrument {
  sym: string;
  name: string;
  asset: 'ENERGY' | 'METALS' | 'FOREX' | 'EQUITY_INDEX';
  digits: number;
  tickSize: number;
  contractSize: number;
}

export interface Candle {
  t: number;
  i: number;
  o: number;
  h: number;
  l: number;
  c: number;
  v: number;
  bid: number;
  ask: number;
}

export interface OrderBlock {
  from: number;
  to: number;
  top: number;
  bot: number;
  side: 'bull' | 'bear';
  strength: 'strong' | 'weak' | 'mit';
}

export interface StructureEvent {
  idx: number;
  type: 'BOS' | 'CHoCH' | 'SWING_LOW' | 'SWING_HIGH';
  side: 'bull' | 'bear';
  price: number;
}

export interface FvgZone {
  from: number;
  to: number;
  top: number;
  bot: number;
  side: 'bull' | 'bear';
}

export interface FootprintLevel {
  price: number;
  bid: number;
  ask: number;
}
export interface FootprintBar {
  t: number;
  o: number;
  c: number;
  levels: FootprintLevel[];
}

export interface Ticker {
  sym: string;
  price: number;
  chg: number;
  chgPct: number;
  source: 'LIVE' | 'STALE';
}

export interface Position {
  id: number;
  instrument: string;
  side: 'LONG' | 'SHORT';
  qty: number;
  entry: number;
  current: number;
  sl: number;
  tp: number;
  opened: number;
  pnl: number;
  notes: string;
}

export interface Portfolio {
  unrealized: number;
  todayRealized: number;
  total: number;
  openCount: number;
  exposure: number;
  marginPct: number;
  dayDD: number;
  dailyTarget: number;
  dailyMax: number;
}

export type ReviewStatus = 'ANALYZING' | 'DONE' | 'ERROR';
export type Eligibility = 'ELIGIBLE' | 'INELIGIBLE' | null;
export type SimStatus = 'PENDING_ENTRY' | 'ACTIVE' | 'WIN' | 'LOSS' | 'MISSED' | 'REVERSED';
export type ExecStatus =
  | 'PENDING_ENTRY_SUBMISSION'
  | 'ENTRY_SUBMITTED'
  | 'ACTIVE'
  | 'CLOSED'
  | 'FAILED';

export interface ReviewSim {
  status: SimStatus;
  drawdown: number | null;
  mfe: number | null;
}

export interface ReviewPlan {
  entry: number;
  sl: number;
  tp: number;
  rr: number;
}

export interface ReviewExecution {
  status: ExecStatus;
  qty: number;
  fillPx: number | null;
}

export interface Review {
  id: number;
  instrument: string;
  tf: string;
  direction: 'LONG' | 'SHORT';
  categories: string[];
  status: ReviewStatus;
  eligibility: Eligibility;
  triggerPrice: number;
  createdAt: number;
  confluence: number;
  plan: ReviewPlan | null;
  verdict: string | null;
  analysis: string | null;
  advice: string | null;
  sim: ReviewSim | null;
  execution?: ReviewExecution;
}

export type AlertSev = 'DANGER' | 'WARNING' | 'INFO';
export interface AlertItem {
  id: string;
  sev: AlertSev;
  cat: string;
  instrument: string | null;
  tf: string | null;
  msg: string;
  t: number;
  fresh?: boolean;
}

export interface Indicators {
  ema9: number;
  ema50: number;
  ema200: number;
  rsi: number;
  macd: number;
  macdSig: number;
  macdHist: number;
  vwap: number;
  superTrend: { value: number; side: 'bull' | 'bear' };
  cmf: number;
  regime: 'TRENDING' | 'RANGING' | 'CHOPPY';
}

export interface MacroSeries {
  price: number;
  chgPct: number;
}
export interface DxyMacro extends MacroSeries {
  chg24h: number;
  trend: 'UP' | 'DOWN';
  sparkline: number[];
}

export interface Macro {
  dxy: DxyMacro;
  vix: MacroSeries;
  us10y: MacroSeries;
  silver: MacroSeries;
}

export interface Backtest {
  winRate: number;
  avgRR: number;
  trades30d: number;
  pf: number;
  best: { day: string; pnl: number };
  worst: { day: string; pnl: number };
  curve: number[];
}

export interface TrailingStats {
  win: number;
  be: number;
  loss: number;
  trailWin: number;
  trailBe: number;
  trailLoss: number;
  netPts: number;
}

export interface RiskDeskData {
  INSTRUMENTS: Instrument[];
  TIMEFRAMES: string[];
  candles: Candle[];
  ema9: number[];
  ema50: number[];
  ema200: number[];
  rsi14: number[];
  macd: { line: number[]; sig: number[]; hist: number[] };
  orderBlocks: OrderBlock[];
  structure: StructureEvent[];
  fvgZones: FvgZone[];
  footprint: FootprintBar[];
  tickers: Ticker[];
  positions: Position[];
  portfolio: Portfolio;
  reviews: Review[];
  alerts: AlertItem[];
  indicators: Indicators;
  macro: Macro;
  backtest: Backtest;
  trailingStats: TrailingStats;
  lastClose: number;
}

const INSTRUMENTS: Instrument[] = [
  { sym: 'MCL', name: 'Micro WTI Crude', asset: 'ENERGY', digits: 2, tickSize: 0.01, contractSize: 100 },
  { sym: 'MGC', name: 'Micro Gold', asset: 'METALS', digits: 1, tickSize: 0.10, contractSize: 10 },
  { sym: 'E6', name: 'Euro FX', asset: 'FOREX', digits: 5, tickSize: 0.00005, contractSize: 125000 },
  { sym: 'MNQ', name: 'Micro Nasdaq', asset: 'EQUITY_INDEX', digits: 2, tickSize: 0.25, contractSize: 2 },
  { sym: 'DXY', name: 'Synthetic DXY', asset: 'FOREX', digits: 3, tickSize: 0.001, contractSize: 1 },
];

const TIMEFRAMES = ['5m', '10m', '1h', '1d'];

// Deterministic PRNG so seeded scenarios are stable across reloads
function mulberry32(seed: number): () => number {
  let a = seed >>> 0;
  return function () {
    a |= 0;
    a = (a + 0x6d2b79f5) | 0;
    let t = Math.imul(a ^ (a >>> 15), 1 | a);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}

interface GenCandlesOpts {
  count: number;
  basePrice: number;
  drift: number;
  vol: number;
  seed: number;
  tickSize: number;
  startMs: number;
}

function genCandles(opts: GenCandlesOpts): Candle[] {
  const rand = mulberry32(opts.seed);
  const candles: Candle[] = [];
  let close = opts.basePrice;

  for (let i = 0; i < opts.count; i++) {
    const t = opts.startMs + i * 10 * 60_000;
    const open = close;
    const phase = Math.sin(i / 22) * 0.4 + Math.cos(i / 7) * 0.15;
    const driftStep = opts.drift * (1 + phase);
    const noise = (rand() - 0.5) * opts.vol;
    const body = driftStep + noise;
    close = open + body;
    const wickU = rand() * opts.vol * 0.6;
    const wickD = rand() * opts.vol * 0.6;
    const high = Math.max(open, close) + wickU;
    const low = Math.min(open, close) - wickD;
    const volume = Math.round(800 + rand() * 6500 + Math.abs(body) * 800);
    const snap = (v: number) => Math.round(v / opts.tickSize) * opts.tickSize;
    const bid = Math.round(volume * (0.5 - body * 0.04 - (rand() - 0.5) * 0.15));
    candles.push({
      t,
      i,
      o: snap(open),
      h: snap(high),
      l: snap(low),
      c: snap(close),
      v: volume,
      bid,
      ask: volume - bid,
    });
  }
  return candles;
}

function ema(values: number[], period: number): number[] {
  const k = 2 / (period + 1);
  const out: number[] = [];
  let prev = values[0];
  for (let i = 0; i < values.length; i++) {
    prev = i === 0 ? values[0] : values[i] * k + prev * (1 - k);
    out.push(prev);
  }
  return out;
}

function rsi(values: number[], period = 14): number[] {
  const out: number[] = [50];
  let gains = 0;
  let losses = 0;
  for (let i = 1; i < values.length; i++) {
    const ch = values[i] - values[i - 1];
    const g = Math.max(ch, 0);
    const l = Math.max(-ch, 0);
    if (i <= period) {
      gains += g;
      losses += l;
      out.push(50);
      continue;
    }
    gains = (gains * (period - 1) + g) / period;
    losses = (losses * (period - 1) + l) / period;
    const rs = losses === 0 ? 100 : gains / losses;
    out.push(100 - 100 / (1 + rs));
  }
  return out;
}

function macd(values: number[]): { line: number[]; sig: number[]; hist: number[] } {
  const e12 = ema(values, 12);
  const e26 = ema(values, 26);
  const line = values.map((_, i) => e12[i] - e26[i]);
  const sig = ema(line, 9);
  const hist = line.map((v, i) => v - sig[i]);
  return { line, sig, hist };
}

function genSparkline(start: number, drift: number, n: number, seed: number): number[] {
  const r = mulberry32(seed);
  const out: number[] = [];
  let v = start;
  for (let i = 0; i < n; i++) {
    v += drift / n + (r() - 0.5) * 0.04;
    out.push(v);
  }
  return out;
}

// All "now"-derived timestamps must be computed at build time only — using a
// fixed anchor stops Next.js hydration mismatches between SSR and client.
const NOW_ANCHOR = 1745625600000; // 2025-04-26 00:00 UTC, stable seed

export function buildData(): RiskDeskData {
  const MCL_INST = INSTRUMENTS[0];
  const candles = genCandles({
    count: 96,
    basePrice: 62.40,
    drift: 0.008,
    vol: 0.22,
    seed: 87341,
    tickSize: MCL_INST.tickSize,
    startMs: NOW_ANCHOR - 96 * 10 * 60_000,
  });
  const lastIdx = candles.length - 1;
  candles[lastIdx - 12].l -= 0.18;
  candles[lastIdx - 12].c = candles[lastIdx - 12].l + 0.04;
  candles[lastIdx - 11].o = candles[lastIdx - 12].c;
  candles[lastIdx - 8].c += 0.22;
  candles[lastIdx - 8].h += 0.30;
  candles[lastIdx - 4].c += 0.18;
  candles[lastIdx - 1].c += 0.14;
  candles[lastIdx].c += 0.12;
  candles[lastIdx].h = Math.max(candles[lastIdx].h, candles[lastIdx].c + 0.05);

  const closes = candles.map((c) => c.c);
  const ema9 = ema(closes, 9);
  const ema50 = ema(closes, 50);
  const ema200 = ema(closes, 200);
  const rsi14 = rsi(closes, 14);
  const macdR = macd(closes);
  const lastClose = closes[closes.length - 1];

  const orderBlocks: OrderBlock[] = [
    { from: lastIdx - 14, to: lastIdx - 10, top: candles[lastIdx - 12].l + 0.18, bot: candles[lastIdx - 12].l, side: 'bull', strength: 'strong' },
    { from: lastIdx - 38, to: lastIdx - 34, top: 62.18, bot: 61.95, side: 'bull', strength: 'weak' },
    { from: lastIdx - 60, to: lastIdx - 56, top: 63.40, bot: 63.18, side: 'bear', strength: 'mit' },
  ];

  const structure: StructureEvent[] = [
    { idx: lastIdx - 28, type: 'BOS', side: 'bear', price: 61.92 },
    { idx: lastIdx - 12, type: 'SWING_LOW', side: 'bull', price: candles[lastIdx - 12].l },
    { idx: lastIdx - 5, type: 'CHoCH', side: 'bull', price: 62.86 },
  ];

  const fvgZones: FvgZone[] = [
    { from: lastIdx - 7, to: lastIdx - 3, top: 62.74, bot: 62.58, side: 'bull' },
  ];

  const footprint: FootprintBar[] = candles.slice(-14).map((c) => {
    const levels: FootprintLevel[] = [];
    const range = c.h - c.l;
    const step = Math.max(MCL_INST.tickSize, range / 7);
    // Use deterministic per-candle seed to avoid SSR mismatches with Math.random
    const rand = mulberry32(c.t & 0xffffffff);
    for (let p = c.l; p <= c.h + 1e-6; p += step) {
      const dist = Math.abs(p - (c.o + c.c) / 2) / Math.max(range, 0.01);
      const w = Math.exp(-dist * 2.2);
      const total = Math.round((c.v / 7) * (0.6 + w));
      const bidShare = c.c >= c.o ? 0.42 : 0.58;
      const bid = Math.round(total * (bidShare + (rand() - 0.5) * 0.12));
      levels.push({ price: +p.toFixed(2), bid, ask: total - bid });
    }
    return { t: c.t, o: c.o, c: c.c, levels };
  });

  const tickers: Ticker[] = [
    { sym: 'MCL', price: lastClose, chg: +0.42, chgPct: +0.68, source: 'LIVE' },
    { sym: 'MGC', price: 2384.6, chg: +5.20, chgPct: +0.22, source: 'LIVE' },
    { sym: 'E6', price: 1.07842, chg: -0.00094, chgPct: -0.09, source: 'LIVE' },
    { sym: 'MNQ', price: 19842.50, chg: +84.25, chgPct: +0.43, source: 'LIVE' },
    { sym: 'DXY', price: 104.218, chg: +0.082, chgPct: +0.08, source: 'LIVE' },
  ];

  const positions: Position[] = [
    {
      id: 1,
      instrument: 'MCL',
      side: 'LONG',
      qty: 3,
      entry: 62.62,
      current: lastClose,
      sl: 62.30,
      tp: 63.40,
      opened: NOW_ANCHOR - 18 * 60_000,
      pnl: (lastClose - 62.62) * 3 * 100,
      notes: 'Long off OB rejection 62.50, BOS bull on 10m',
    },
    {
      id: 2,
      instrument: 'MGC',
      side: 'SHORT',
      qty: 2,
      entry: 2389.4,
      current: 2384.6,
      sl: 2394.0,
      tp: 2378.0,
      opened: NOW_ANCHOR - 73 * 60_000,
      pnl: (2389.4 - 2384.6) * 2 * 10,
      notes: 'Bearish CHoCH 1h, OB top 2390',
    },
  ];

  const portfolio: Portfolio = {
    unrealized: positions.reduce((s, p) => s + p.pnl, 0),
    todayRealized: 184.40,
    total: 0,
    openCount: positions.length,
    exposure: 38_420,
    marginPct: 42.6,
    dayDD: -126.20,
    dailyTarget: 600,
    dailyMax: -800,
  };
  portfolio.total = portfolio.unrealized + portfolio.todayRealized;

  const reviews: Review[] = [
    {
      id: 901,
      instrument: 'MCL',
      tf: '10m',
      direction: 'LONG',
      categories: ['MARKET_STRUCTURE', 'ORDER_BLOCK', 'VWAP'],
      status: 'ANALYZING',
      eligibility: null,
      triggerPrice: 62.86,
      createdAt: NOW_ANCHOR - 18_000,
      confluence: 3.4,
      plan: null,
      verdict: null,
      analysis: null,
      advice: null,
      sim: null,
    },
    {
      id: 894,
      instrument: 'MCL',
      tf: '1h',
      direction: 'LONG',
      categories: ['MACD_CROSS', 'RSI_OVERSOLD'],
      status: 'DONE',
      eligibility: 'ELIGIBLE',
      triggerPrice: 62.40,
      createdAt: NOW_ANCHOR - 47 * 60_000,
      confluence: 4.1,
      plan: { entry: 62.55, sl: 62.10, tp: 63.45, rr: 1.8 },
      verdict: 'Trade OK — Bullish 1H confluence with macro DXY weakness backing the long.',
      analysis:
        'Higher-timeframe structure flipped bullish on the 1H BOS at 62.18. RSI(14) lifted from 27 → 41, MACD histogram crossed positive on the prior bar. Price is now retesting the breakout zone with active demand visible in tape. DXY is rolling lower from 104.40 which structurally supports crude.',
      advice:
        'Scale 1/3 at +1R, trail behind 9-EMA on 10m once +1.2R is reached. Invalidation is a 10m close back below 62.18.',
      sim: { status: 'ACTIVE', drawdown: 0.18, mfe: 0.42 },
      execution: { status: 'ENTRY_SUBMITTED', qty: 2, fillPx: null },
    },
    {
      id: 879,
      instrument: 'MCL',
      tf: '10m',
      direction: 'SHORT',
      categories: ['BEARISH_OB'],
      status: 'DONE',
      eligibility: 'INELIGIBLE',
      triggerPrice: 63.04,
      createdAt: NOW_ANCHOR - 2 * 3600_000,
      confluence: 1.8,
      plan: { entry: 63.05, sl: 63.40, tp: 62.40, rr: 1.85 },
      verdict:
        'Trade Non-Conforme — counter-trend against 1H bullish flip; insufficient confluence weight (1.8 < 3.0).',
      analysis:
        'Setup leans on a single bearish OB without structural confirmation on higher timeframe. EMA9>EMA50 alignment argues against shorts.',
      advice:
        'Wait for either a 10m BOS bear OR a clean rejection wick at 63.20 with delta divergence before re-engaging short side.',
      sim: { status: 'MISSED', drawdown: null, mfe: null },
    },
    {
      id: 871,
      instrument: 'MGC',
      tf: '1h',
      direction: 'SHORT',
      categories: ['MARKET_STRUCTURE', 'ORDER_BLOCK'],
      status: 'DONE',
      eligibility: 'ELIGIBLE',
      triggerPrice: 2390.2,
      createdAt: NOW_ANCHOR - 75 * 60_000,
      confluence: 3.6,
      plan: { entry: 2390.0, sl: 2394.5, tp: 2378.0, rr: 2.7 },
      verdict: 'Trade OK — bearish CHoCH on 1H with OB rejection at 2390.',
      analysis:
        'Gold lost 1H trendline support after rejection at 2390 OB top. DXY firming +0.08% caps further upside near term.',
      advice: 'Move stop to break-even at 2386. First target 2382.',
      sim: { status: 'WIN', drawdown: 0.6, mfe: 5.6 },
      execution: { status: 'CLOSED', qty: 2, fillPx: 2389.4 },
    },
  ];

  const alerts: AlertItem[] = [
    { id: 'a-9001', sev: 'DANGER', cat: 'MARKET_STRUCTURE', instrument: 'MCL', tf: '10m', msg: '[10m] Bullish CHoCH confirmed @ 62.86 — first BOS reversal since 09:40 ET', t: NOW_ANCHOR - 14_000 },
    { id: 'a-9000', sev: 'INFO', cat: 'ORDER_BLOCK', instrument: 'MCL', tf: '10m', msg: '[10m] Bullish OB tap @ 62.58 — 3 prior touches honored', t: NOW_ANCHOR - 32_000 },
    { id: 'a-8999', sev: 'WARNING', cat: 'RISK', instrument: null, tf: null, msg: 'Daily drawdown at -16% of max ($-126/$-800) — exposure warning', t: NOW_ANCHOR - 4 * 60_000 },
    { id: 'a-8998', sev: 'INFO', cat: 'VWAP', instrument: 'MCL', tf: '10m', msg: '[10m] Price reclaimed session VWAP @ 62.71', t: NOW_ANCHOR - 6 * 60_000 },
    { id: 'a-8997', sev: 'INFO', cat: 'MACD_CROSS', instrument: 'MCL', tf: '1h', msg: '[1h] MACD bullish cross — histogram > 0 confirmed', t: NOW_ANCHOR - 14 * 60_000 },
    { id: 'a-8996', sev: 'INFO', cat: 'CHAIKIN', instrument: 'MCL', tf: '10m', msg: '[10m] CMF turning positive: -0.04 → +0.11', t: NOW_ANCHOR - 22 * 60_000 },
    { id: 'a-8995', sev: 'WARNING', cat: 'ROLLOVER', instrument: 'MCL', tf: null, msg: 'MCL active contract rolls in 4 days — open positions will need repricing', t: NOW_ANCHOR - 38 * 60_000 },
  ];

  const indicators: Indicators = {
    ema9: ema9[ema9.length - 1],
    ema50: ema50[ema50.length - 1],
    ema200: ema200[ema200.length - 1],
    rsi: rsi14[rsi14.length - 1],
    macd: macdR.line[macdR.line.length - 1],
    macdSig: macdR.sig[macdR.sig.length - 1],
    macdHist: macdR.hist[macdR.hist.length - 1],
    vwap: 62.71,
    superTrend: { value: 62.30, side: 'bull' },
    cmf: 0.11,
    regime: 'TRENDING',
  };

  const macro: Macro = {
    dxy: { price: 104.218, chg24h: +0.082, chgPct: +0.08, trend: 'DOWN', sparkline: genSparkline(104.30, -0.12, 32, 9001) },
    vix: { price: 14.82, chgPct: -1.4 },
    us10y: { price: 4.214, chgPct: +0.6 },
    silver: { price: 28.42, chgPct: +0.31 },
  };

  const backtest: Backtest = {
    winRate: 0.612,
    avgRR: 1.84,
    trades30d: 47,
    pf: 2.31,
    best: { day: 'Apr 18', pnl: 412 },
    worst: { day: 'Apr 09', pnl: -284 },
    curve: genSparkline(0, 800, 30, 4221),
  };

  const trailingStats: TrailingStats = {
    win: 14,
    be: 6,
    loss: 3,
    trailWin: 11,
    trailBe: 8,
    trailLoss: 4,
    netPts: +14.6,
  };

  return {
    INSTRUMENTS,
    TIMEFRAMES,
    candles,
    ema9,
    ema50,
    ema200,
    rsi14,
    macd: macdR,
    orderBlocks,
    structure,
    fvgZones,
    footprint,
    tickers,
    positions,
    portfolio,
    reviews,
    alerts,
    indicators,
    macro,
    backtest,
    trailingStats,
    lastClose,
  };
}

export const RD_DATA: RiskDeskData = buildData();
