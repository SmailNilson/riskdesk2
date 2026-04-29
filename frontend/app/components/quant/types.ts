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
  price: number | null;
  priceSource: string;
  dayMove: number;
  scanTime: string | null;
  entry: number | null;
  sl: number | null;
  tp1: number | null;
  tp2: number | null;
  shortSetup7_7: boolean;
  shortAlert6_7: boolean;
  gates: QuantGateView[];
  // Structural filters (PR #299) — optional for backward compat.
  structuralBlocks?: StructuralBlockView[];
  structuralWarnings?: StructuralWarningView[];
  structuralScoreModifier?: number;
  finalScore?: number;
  shortBlocked?: boolean;
  shortAvailable?: boolean;
}

/** WebSocket payload — same shape as REST plus a `kind` discriminator. */
export interface QuantWsPayload {
  kind: 'SNAPSHOT' | 'SHORT_7_7' | 'SETUP_6_7' | 'NARRATION' | 'ADVICE';
  instrument: string;
  score: number;
  price: number | null;
  priceSource: string;
  dayMove: number;
  scanTime: string | null;
  entry: number | null;
  sl: number | null;
  tp1: number | null;
  tp2: number | null;
  gates: Record<string, { ok: boolean; reason: string }>;
  pattern?: PatternView | null;
  markdown?: string | null;
  advice?: AdviceView | null;
  // Structural filters (PR #299) — optional for backward compat.
  structuralBlocks?: StructuralBlockView[];
  structuralWarnings?: StructuralWarningView[];
  structuralScoreModifier?: number;
  finalScore?: number;
  shortBlocked?: boolean;
  shortAvailable?: boolean;
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
};
