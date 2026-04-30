// Wire types for the Quant 7-Gates evaluator.
// Mirror src/main/java/com/riskdesk/presentation/quant/dto/QuantSnapshotResponse.java.

export interface QuantGateView {
  gate: string;
  ok: boolean;
  reason: string;
}

export interface StructuralBlockView {
  code: string;
  evidence: string;
}

export interface StructuralWarningView {
  code: string;
  evidence: string;
  scoreModifier: number;
}

export interface QuantSnapshotView {
  instrument: string;
  score: number;
  longScore: number;
  price: number | null;
  priceSource: string;
  dayMove: number;
  scanTime: string | null;
  entry: number | null;
  sl: number | null;
  tp1: number | null;
  tp2: number | null;
  longEntry: number | null;
  longSl: number | null;
  longTp1: number | null;
  longTp2: number | null;
  shortSetup7_7: boolean;
  shortAlert6_7: boolean;
  longSetup7_7: boolean;
  longAlert6_7: boolean;
  gates: QuantGateView[];
  // SHORT structural filters (PR #299) — optional for backward compat.
  structuralBlocks?: StructuralBlockView[];
  structuralWarnings?: StructuralWarningView[];
  structuralScoreModifier?: number;
  finalScore?: number;
  shortBlocked?: boolean;
  shortAvailable?: boolean;
  // LONG structural filters (LONG-symmetry slice).
  longStructuralBlocks?: StructuralBlockView[];
  longStructuralWarnings?: StructuralWarningView[];
  longStructuralScoreModifier?: number;
  longFinalScore?: number;
  longBlocked?: boolean;
  longAvailable?: boolean;
}

/** WebSocket payload — same shape as REST plus a `kind` discriminator. */
export interface QuantWsPayload {
  kind: 'SNAPSHOT' | 'SHORT_7_7' | 'SETUP_6_7' | 'NARRATION' | 'ADVICE';
  instrument: string;
  score: number;
  longScore?: number;
  price: number | null;
  priceSource: string;
  dayMove: number;
  scanTime: string | null;
  entry: number | null;
  sl: number | null;
  tp1: number | null;
  tp2: number | null;
  longEntry?: number | null;
  longSl?: number | null;
  longTp1?: number | null;
  longTp2?: number | null;
  gates: Record<string, { ok: boolean; reason: string }>;
  pattern?: PatternView | null;
  markdown?: string | null;
  advice?: AdviceView | null;
  // SHORT structural filters (PR #299) — optional for backward compat.
  structuralBlocks?: StructuralBlockView[];
  structuralWarnings?: StructuralWarningView[];
  structuralScoreModifier?: number;
  finalScore?: number;
  shortBlocked?: boolean;
  shortAvailable?: boolean;
  // LONG structural filters (LONG-symmetry slice).
  longStructuralBlocks?: StructuralBlockView[];
  longStructuralWarnings?: StructuralWarningView[];
  longStructuralScoreModifier?: number;
  longFinalScore?: number;
  longBlocked?: boolean;
  longAvailable?: boolean;
}

export interface PatternView {
  type: string;
  label: string;
  reason: string;
  confidence: 'LOW' | 'MEDIUM' | 'HIGH';
  action: 'TRADE' | 'WAIT' | 'AVOID';
}

export interface AdviceView {
  verdict: 'TRADE' | 'ATTENDRE' | 'EVITER' | 'UNAVAILABLE';
  reasoning: string;
  risk: string;
  confidence: number;
  model: string;
  generatedAt: string | null;
}

export interface QuantNarrationView {
  pattern: PatternView | null;
  markdown: string;
}

export const QUANT_INSTRUMENTS = ['MNQ', 'MGC', 'MCL'] as const;
export type QuantInstrument = typeof QUANT_INSTRUMENTS[number];

export const GATE_LABELS: Record<string, string> = {
  G0_REGIME: 'G0 Régime',
  G1_ABS_BEAR: 'G1 ABS BEAR',
  G2_DIST_PUR: 'G2 DIST_pur 2/3',
  G3_DELTA_NEG: 'G3 Δ < -100',
  G4_BUY_PCT_LOW: 'G4 buy% < 48',
  G5_ACCU_THRESHOLD: 'G5 ACCU seuil',
  G6_LIVE_PUSH: 'G6 LIVE_PUSH',
  L0_REGIME: 'L0 Régime',
  L1_ABS_BULL: 'L1 ABS BULL',
  L2_ACCU_PUR: 'L2 ACCU_pur 2/3',
  L3_DELTA_POS: 'L3 Δ > +100',
  L4_BUY_PCT_HIGH: 'L4 buy% > 52',
  L5_DIST_THRESHOLD: 'L5 DIST seuil',
  L6_LIVE_PUSH: 'L6 LIVE_PUSH',
};

/** Names of the SHORT-track gates (used to filter the gate list per direction). */
export const SHORT_GATES: string[] = [
  'G0_REGIME', 'G1_ABS_BEAR', 'G2_DIST_PUR', 'G3_DELTA_NEG',
  'G4_BUY_PCT_LOW', 'G5_ACCU_THRESHOLD', 'G6_LIVE_PUSH',
];

/** Names of the LONG-track gates (used to filter the gate list per direction). */
export const LONG_GATES: string[] = [
  'L0_REGIME', 'L1_ABS_BULL', 'L2_ACCU_PUR', 'L3_DELTA_POS',
  'L4_BUY_PCT_HIGH', 'L5_DIST_THRESHOLD', 'L6_LIVE_PUSH',
];

// Manual trade ticket payload (PR #305) — POST /api/quant/manual-trade/{instrument}.
export interface ManualTradeRequest {
  direction: 'LONG' | 'SHORT';
  entryType: 'MARKET' | 'LIMIT';
  /** Required when entryType=LIMIT. Server uses live price for MARKET. */
  entryPrice: number | null;
  stopLoss: number;
  takeProfit1: number;
  takeProfit2: number | null;
  quantity: number;
}
