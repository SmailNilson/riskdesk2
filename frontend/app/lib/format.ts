/**
 * Shared number/price formatters for the trading UI.
 *
 * Before this module each panel reimplemented its own fmtUsd / pnlColor /
 * decimalsFor — the duplication drifted (some used `−` U+2212, some `-`, some
 * showed `+` on positives, some didn't). This file is the single source of
 * truth. New code should import from here; existing local helpers will be
 * migrated panel-by-panel in Vague 3.
 */

export type PnlTone = 'bull' | 'bear' | 'neutral';

/**
 * Decimal precision per instrument. Matches IBKR contract tick sizes.
 * - E6 (Euro FX) ticks at 0.00005 → 5 decimals
 * - DXY synthetic → 3 decimals
 * - MGC (Micro Gold) ticks at 0.10 → 1 decimal
 * - Everything else (MNQ, MCL, default) → 2 decimals
 */
export function decimalsFor(instrument: string): number {
  if (instrument === 'E6') return 5;
  if (instrument === 'DXY') return 3;
  if (instrument === 'MGC') return 1;
  return 2;
}

/**
 * Price format spec for lightweight-charts. `minMove` MUST match the
 * contract's actual tick size or the chart will snap prices incorrectly.
 */
export function priceFormatFor(
  instrument: string,
): { type: 'price'; precision: number; minMove: number } {
  switch (instrument) {
    case 'E6':  return { type: 'price', precision: 5, minMove: 0.00005 };
    case 'DXY': return { type: 'price', precision: 3, minMove: 0.005 };
    case 'MGC': return { type: 'price', precision: 1, minMove: 0.1 };
    case 'MNQ': return { type: 'price', precision: 2, minMove: 0.25 };
    case 'MCL': return { type: 'price', precision: 2, minMove: 0.01 };
    default:    return { type: 'price', precision: 2, minMove: 0.01 };
  }
}

/**
 * Format a raw price with the correct precision + thousand separators.
 * Returns the em-dash placeholder when the value is null/undefined/NaN.
 */
export function fmtPrice(
  value: number | null | undefined,
  instrument: string,
): string {
  if (value == null || Number.isNaN(value)) return '—';
  const decimals = decimalsFor(instrument);
  return value.toLocaleString('en-US', {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
}

export interface FmtUsdOptions {
  /** `'auto'` (default) shows `-` on negatives, no sign on positives.
   *  `'always'` shows `+` on positives. */
  sign?: 'auto' | 'always';
  /** Decimals — default 2. */
  decimals?: number;
}

/**
 * Format a USD amount. Null → '—'. Sign behaviour controlled by `sign`:
 *   fmtUsd(45.5)                  → "$45.50"
 *   fmtUsd(45.5, {sign:'always'}) → "+$45.50"
 *   fmtUsd(-12)                   → "-$12.00"
 *   fmtUsd(-12, {sign:'always'})  → "-$12.00"
 */
export function fmtUsd(
  value: number | null | undefined,
  opts: FmtUsdOptions = {},
): string {
  if (value == null || Number.isNaN(value)) return '—';
  const decimals = opts.decimals ?? 2;
  const sign = opts.sign ?? 'auto';
  const abs = Math.abs(value).toFixed(decimals);
  if (value < 0) return `-$${abs}`;
  if (value > 0 && sign === 'always') return `+$${abs}`;
  return `$${abs}`;
}

/**
 * Format a percentage. Always shows sign by default (it's the common
 * usage in market data — `+0.42%`, `-1.12%`).
 */
export function fmtPct(
  value: number | null | undefined,
  decimals = 2,
): string {
  if (value == null || Number.isNaN(value)) return '—';
  const fixed = Math.abs(value).toFixed(decimals);
  if (value < 0) return `-${fixed}%`;
  if (value > 0) return `+${fixed}%`;
  return `${fixed}%`;
}

/**
 * Semantic tone for a P&L number. Use this when you want to drive
 * styling from a single token (bull/bear/neutral) instead of hard-coding
 * Tailwind classes. Pairs well with the new semantic colour tokens.
 */
export function pnlTone(value: number | null | undefined): PnlTone {
  if (value == null || Number.isNaN(value)) return 'neutral';
  if (value > 0) return 'bull';
  if (value < 0) return 'bear';
  return 'neutral';
}

/**
 * Legacy Tailwind class for P&L colour — kept for migration. New code
 * should prefer `pnlTone()` + semantic tokens (e.g. `text-bull`).
 */
export function pnlColorClass(value: number | null | undefined): string {
  switch (pnlTone(value)) {
    case 'bull': return 'text-emerald-400';
    case 'bear': return 'text-red-400';
    default:     return 'text-zinc-400';
  }
}

/**
 * Margin-utilisation colour bucket. >80% is critical (urgent fuchsia in
 * the new design system), >60% is warn amber, otherwise healthy bull green.
 */
export function marginTone(pct: number | null | undefined): 'urgent' | 'warn' | 'bull' {
  const v = pct ?? 0;
  if (v > 80) return 'urgent';
  if (v > 60) return 'warn';
  return 'bull';
}

export function marginColorClass(pct: number | null | undefined): string {
  switch (marginTone(pct)) {
    case 'urgent': return 'text-fuchsia-400';
    case 'warn':   return 'text-amber-400';
    default:       return 'text-emerald-400';
  }
}
