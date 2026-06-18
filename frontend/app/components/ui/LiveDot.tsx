// Tiny status dot (live/stale/closed) used in headers to signal stream freshness.
import type { HTMLAttributes } from 'react';

export type LiveStatus = 'live' | 'stale' | 'closed';
export type LiveDotSize = 'xs' | 'sm' | 'md';

export interface LiveDotProps extends Omit<HTMLAttributes<HTMLSpanElement>, 'children'> {
  status: LiveStatus;
  size?: LiveDotSize;
  pulse?: boolean;
  className?: string;
}

const SIZE_CLASS: Record<LiveDotSize, string> = {
  xs: 'w-1.5 h-1.5',
  sm: 'w-2 h-2',
  md: 'w-2.5 h-2.5',
};

const COLOR_CLASS: Record<LiveStatus, string> = {
  live: 'bg-emerald-400',
  stale: 'bg-amber-400',
  closed: 'bg-zinc-500',
};

const ARIA_LABEL: Record<LiveStatus, string> = {
  live: 'Stream live',
  stale: 'Stream stale',
  closed: 'Marché fermé',
};

export default function LiveDot({
  status,
  size = 'sm',
  pulse,
  className,
  ...rest
}: LiveDotProps) {
  const sizeClass = SIZE_CLASS[size];
  const colorClass = COLOR_CLASS[status];
  const shouldPulse = pulse ?? status === 'live';

  return (
    <span
      className={`rounded-full inline-block ${sizeClass} ${colorClass} ${shouldPulse ? 'animate-pulse' : ''} ${className ?? ''}`}
      aria-label={ARIA_LABEL[status]}
      role="status"
      {...rest}
    />
  );
}
