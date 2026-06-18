'use client';

/**
 * Trade Desk V2 — sticky cockpit header (Vagues 2/3/4).
 *
 * Two-level, props-driven, purely presentational. Layout matches the validated
 * desktop mockup:
 *   Level 1 (40 px): logo + instrument pills + timeframe segmented + LIVE feed
 *                    indicator + P&L total + margin badge + ⚙ + ⌘K hint.
 *   Level 2 (32 px): horizontal ticker (price + %), DXY cell with sparkline.
 *
 * Notes:
 * - No semantic tokens, no lib/format imports — inline Tailwind only
 *   (this PR ships standalone; Vague 1 may not yet be merged).
 * - Financial numbers use `font-mono tabular-nums`.
 * - SVG icons inlined; no new npm deps.
 */

interface PriceCell {
  price: number | null;
  changePct?: number | null;
  source?: string;
}

export interface VitalHeaderProps {
  instrument: string;
  instruments: readonly string[];
  onInstrumentChange: (i: string) => void;
  timeframe: string;
  timeframes: readonly string[];
  onTimeframeChange: (tf: string) => void;
  connected: boolean;
  totalPnl: number | null;
  marginUsedPct: number | null;
  prices: Record<string, PriceCell>;
  dxySeries?: number[];
  onOpenCommandPalette?: () => void;
}

// -- formatting helpers (inline; do NOT import lib/format.ts) -----------------

function decimalsFor(i: string): number {
  if (i === 'E6' || i === '6E') return 5;
  if (i === 'DXY') return 3;
  if (i === 'MGC') return 1;
  return 2;
}

function fmtPrice(v: number | null | undefined, i: string): string {
  if (v == null) return '—';
  const d = decimalsFor(i);
  return v.toLocaleString('en-US', { minimumFractionDigits: d, maximumFractionDigits: d });
}

function fmtPct(v: number | null | undefined, decimals = 2): string {
  if (v == null) return '—';
  return `${v >= 0 ? '+' : ''}${v.toFixed(decimals)}%`;
}

function fmtUsd(v: number | null | undefined): string {
  if (v == null) return '—';
  const sign = v >= 0 ? '+' : '-';
  return `${sign}$${Math.abs(v).toFixed(2)}`;
}

function pnlColor(v: number | null | undefined): string {
  if (v == null) return 'text-zinc-400';
  if (v > 0) return 'text-emerald-400';
  if (v < 0) return 'text-red-400';
  return 'text-zinc-400';
}

function changeColor(v: number | null | undefined): string {
  if (v == null) return 'text-zinc-500';
  return v >= 0 ? 'text-emerald-400' : 'text-red-400';
}

function marginBadgeClass(pct: number | null | undefined): string {
  if (pct == null) return 'bg-zinc-800 text-zinc-400';
  if (pct > 80) return 'bg-fuchsia-900/40 text-fuchsia-400';
  if (pct > 60) return 'bg-amber-900/40 text-amber-400';
  return 'bg-zinc-800 text-zinc-400';
}

// -- inline SVG icons ---------------------------------------------------------

function SettingsIcon({ className }: { className?: string }) {
  return (
    <svg
      width={16}
      height={16}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden
    >
      <path d="M12.22 2h-.44a2 2 0 0 0-2 2v.18a2 2 0 0 1-1 1.73l-.43.25a2 2 0 0 1-2 0l-.15-.08a2 2 0 0 0-2.73.73l-.22.38a2 2 0 0 0 .73 2.73l.15.1a2 2 0 0 1 1 1.72v.51a2 2 0 0 1-1 1.74l-.15.09a2 2 0 0 0-.73 2.73l.22.38a2 2 0 0 0 2.73.73l.15-.08a2 2 0 0 1 2 0l.43.25a2 2 0 0 1 1 1.73V20a2 2 0 0 0 2 2h.44a2 2 0 0 0 2-2v-.18a2 2 0 0 1 1-1.73l.43-.25a2 2 0 0 1 2 0l.15.08a2 2 0 0 0 2.73-.73l.22-.39a2 2 0 0 0-.73-2.73l-.15-.08a2 2 0 0 1-1-1.74v-.5a2 2 0 0 1 1-1.74l.15-.09a2 2 0 0 0 .73-2.73l-.22-.38a2 2 0 0 0-2.73-.73l-.15.08a2 2 0 0 1-2 0l-.43-.25a2 2 0 0 1-1-1.73V4a2 2 0 0 0-2-2z" />
      <circle cx={12} cy={12} r={3} />
    </svg>
  );
}

// -- sparkline ----------------------------------------------------------------

const SPARK_W = 80;
const SPARK_H = 16;

function buildSparklinePoints(series: number[] | undefined): string {
  if (!series || series.length < 2) return '';
  const min = Math.min(...series);
  const max = Math.max(...series);
  const range = max - min;
  const stepX = SPARK_W / (series.length - 1);
  // Inset 1px so a 1px stroke doesn't get clipped at the edges.
  const innerH = SPARK_H - 2;
  return series
    .map((v, i) => {
      const x = i * stepX;
      const norm = range === 0 ? 0.5 : (v - min) / range;
      // Invert Y so larger values render higher on screen.
      const y = 1 + (1 - norm) * innerH;
      return `${x.toFixed(2)},${y.toFixed(2)}`;
    })
    .join(' ');
}

function Sparkline({ series }: { series?: number[] }) {
  const points = buildSparklinePoints(series);
  return (
    <svg
      width={SPARK_W}
      height={SPARK_H}
      viewBox={`0 0 ${SPARK_W} ${SPARK_H}`}
      className="flex-shrink-0"
      aria-hidden
    >
      {points && (
        <polyline
          points={points}
          fill="none"
          stroke="currentColor"
          strokeWidth={1}
          strokeLinecap="round"
          strokeLinejoin="round"
          className="text-emerald-400"
        />
      )}
    </svg>
  );
}

// -- component ----------------------------------------------------------------

export default function VitalHeader({
  instrument,
  instruments,
  onInstrumentChange,
  timeframe,
  timeframes,
  onTimeframeChange,
  connected,
  totalPnl,
  marginUsedPct,
  prices,
  dxySeries,
  onOpenCommandPalette,
}: VitalHeaderProps) {
  const feedDotClass = connected ? 'bg-emerald-400 animate-pulse' : 'bg-red-500';
  const feedLabel = connected ? 'LIVE' : 'OFF';

  return (
    <header className="sticky top-0 z-40 w-full">
      {/* ----- Level 1 (40 px) ------------------------------------------- */}
      <div className="flex items-center gap-3 h-10 px-3 bg-zinc-900 border-b border-zinc-800">
        {/* Logo */}
        <div className="flex items-center gap-3">
          <span className="font-mono text-[14px] font-bold text-white tracking-tight">RD</span>
          <span className="bg-zinc-800 w-px h-5" aria-hidden />
        </div>

        {/* Instrument pills */}
        <nav className="flex items-center gap-1" aria-label="Instrument">
          {instruments.map((i) => {
            const active = i === instrument;
            return (
              <button
                key={i}
                type="button"
                onClick={() => onInstrumentChange(i)}
                aria-pressed={active}
                className={
                  'inline-flex items-center gap-1.5 h-7 px-2.5 rounded-md text-[11px] font-medium transition-colors ' +
                  (active
                    ? 'bg-emerald-500/15 border border-emerald-500 text-emerald-400'
                    : 'bg-zinc-800 border border-zinc-800 text-zinc-400 hover:bg-zinc-700')
                }
              >
                {active && (
                  <span
                    className="w-1.5 h-1.5 rounded-full bg-emerald-400 animate-pulse"
                    aria-hidden
                  />
                )}
                {i}
              </button>
            );
          })}
        </nav>

        {/* Timeframe segmented */}
        <div
          className="flex items-center gap-0.5 bg-zinc-800 rounded-md p-0.5"
          role="group"
          aria-label="Timeframe"
        >
          {timeframes.map((tf) => {
            const active = tf === timeframe;
            return (
              <button
                key={tf}
                type="button"
                onClick={() => onTimeframeChange(tf)}
                aria-pressed={active}
                className={
                  'h-6 px-2 rounded text-[11px] font-medium transition-colors ' +
                  (active ? 'bg-zinc-700 text-white' : 'text-zinc-400 hover:text-zinc-200')
                }
              >
                {tf}
              </button>
            );
          })}
        </div>

        {/* Right side: feed + P&L + margin + settings + ⌘K */}
        <div className="ml-auto flex items-center gap-4">
          {/* Feed indicator */}
          <div className="flex items-center gap-1.5">
            <span className={`w-2 h-2 rounded-full ${feedDotClass}`} aria-hidden />
            <span className="text-[10px] uppercase tracking-widest text-zinc-500">{feedLabel}</span>
          </div>

          {/* Total P&L */}
          <div className="flex flex-col items-end leading-none">
            <span
              className={`font-mono tabular-nums text-[28px] font-bold leading-none ${pnlColor(totalPnl)}`}
            >
              {fmtUsd(totalPnl)}
            </span>
            <span className="text-[10px] uppercase tracking-widest text-zinc-500 mt-0.5">
              Total P&amp;L · Today
            </span>
          </div>

          {/* Margin badge */}
          <span
            className={`text-[11px] px-1.5 py-0.5 rounded font-mono tabular-nums ${marginBadgeClass(marginUsedPct)}`}
            title="Margin used"
          >
            Margin {marginUsedPct != null ? `${marginUsedPct.toFixed(0)}%` : '—'}
          </span>

          {/* Settings */}
          <button
            type="button"
            className="inline-flex items-center justify-center w-7 h-7 rounded-md text-zinc-400 hover:text-zinc-200 hover:bg-zinc-800 transition-colors"
            aria-label="Settings"
          >
            <SettingsIcon />
          </button>

          {/* ⌘K hint */}
          <button
            type="button"
            onClick={onOpenCommandPalette}
            className="bg-zinc-800 px-1.5 py-0.5 rounded text-[10px] text-zinc-400 font-mono hover:bg-zinc-700 transition-colors"
            aria-label="Open command palette"
          >
            ⌘K
          </button>
        </div>
      </div>

      {/* ----- Level 2 (32 px): ticker ----------------------------------- */}
      <div className="flex items-center h-8 px-3 gap-3 overflow-x-auto bg-zinc-900/60 border-b border-zinc-800">
        {instruments.map((sym, idx) => {
          const cell = prices[sym];
          const isDxy = sym === 'DXY';
          return (
            <div key={sym} className="flex items-center gap-3 flex-shrink-0">
              {idx > 0 && <span className="bg-zinc-800 w-px h-4" aria-hidden />}
              <div className="flex items-center gap-2 font-mono tabular-nums text-[12px]">
                <span className="text-zinc-500">{sym}</span>
                <span className="text-white">{fmtPrice(cell?.price ?? null, sym)}</span>
                <span className={changeColor(cell?.changePct ?? null)}>
                  {fmtPct(cell?.changePct ?? null)}
                </span>
                {isDxy && <Sparkline series={dxySeries} />}
              </div>
            </div>
          );
        })}
      </div>
    </header>
  );
}
