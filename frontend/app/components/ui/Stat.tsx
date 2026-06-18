// Design-system Stat primitive — replaces ad-hoc Metric/Row patterns across MetricsBar, MobileVitalStrip, IndicatorPanel, etc.

export type StatTone = 'bull' | 'bear' | 'warn' | 'urgent' | 'neutral';
export type StatSize = 'sm' | 'md' | 'lg' | 'xl';
export type StatTrend = 'up' | 'down' | 'flat';

export interface StatProps {
  label: string;
  value: string | number | null | undefined;
  unit?: string;
  tone?: StatTone;
  size?: StatSize;
  trend?: StatTrend;
  tabular?: boolean;
  align?: 'left' | 'right';
  loading?: boolean;
  className?: string;
}

const SIZE_CLASSES: Record<StatSize, string> = {
  sm: 'text-sm font-medium',
  md: 'text-base font-medium',
  lg: 'text-lg font-semibold',
  xl: 'text-2xl font-bold',
};

const SKELETON_CLASSES: Record<StatSize, string> = {
  sm: 'h-4 w-12',
  md: 'h-5 w-16',
  lg: 'h-6 w-20',
  xl: 'h-8 w-24',
};

const TONE_CLASSES: Record<StatTone, string> = {
  bull: 'text-emerald-400',
  bear: 'text-red-400',
  warn: 'text-amber-400',
  urgent: 'text-fuchsia-400',
  neutral: 'text-white',
};

function TrendArrow({ trend }: { trend: StatTrend }) {
  if (trend === 'up') {
    return (
      <svg
        aria-hidden="true"
        width="12"
        height="12"
        viewBox="0 0 12 12"
        className="inline-block ml-1 text-emerald-400"
      >
        <path
          d="M3 9 L9 3 M9 3 L9 7 M9 3 L5 3"
          stroke="currentColor"
          strokeWidth="1.5"
          fill="none"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    );
  }
  if (trend === 'down') {
    return (
      <svg
        aria-hidden="true"
        width="12"
        height="12"
        viewBox="0 0 12 12"
        className="inline-block ml-1 text-red-400"
      >
        <path
          d="M3 3 L9 9 M9 9 L9 5 M9 9 L5 9"
          stroke="currentColor"
          strokeWidth="1.5"
          fill="none"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    );
  }
  return (
    <svg
      aria-hidden="true"
      width="12"
      height="12"
      viewBox="0 0 12 12"
      className="inline-block ml-1 text-zinc-500"
    >
      <path
        d="M2 6 L10 6 M10 6 L7 3 M10 6 L7 9"
        stroke="currentColor"
        strokeWidth="1.5"
        fill="none"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
}

export function Stat({
  label,
  value,
  unit,
  tone = 'neutral',
  size = 'md',
  trend,
  tabular = true,
  align = 'left',
  loading = false,
  className,
}: StatProps) {
  const isEmpty = value === null || value === undefined || value === '';
  const alignClasses = align === 'right' ? 'text-right items-end' : 'items-start';
  const tabularClasses = tabular ? 'font-mono tabular-nums' : '';
  const sizeClasses = SIZE_CLASSES[size];
  const toneClasses = isEmpty ? 'text-zinc-500' : TONE_CLASSES[tone];

  const displayValue = isEmpty ? '—' : String(value);
  const ariaValue = isEmpty ? '—' : `${displayValue}${unit ?? ''}`;

  const containerClasses = ['flex flex-col', alignClasses, className].filter(Boolean).join(' ');
  const valueClasses = [sizeClasses, toneClasses, tabularClasses, 'leading-tight']
    .filter(Boolean)
    .join(' ');

  return (
    <div className={containerClasses} aria-label={`${label}: ${ariaValue}`}>
      <span className="text-[10px] uppercase tracking-widest text-zinc-500">{label}</span>
      {loading ? (
        <span
          className={`bg-zinc-800 animate-pulse rounded ${SKELETON_CLASSES[size]} mt-1`}
          aria-hidden="true"
        />
      ) : (
        <span className={valueClasses}>
          {displayValue}
          {!isEmpty && unit ? (
            <span className="text-[60%] opacity-70 ml-0.5">{unit}</span>
          ) : null}
          {!isEmpty && trend ? <TrendArrow trend={trend} /> : null}
        </span>
      )}
    </div>
  );
}

export default Stat;
