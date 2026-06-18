// Visual placeholder block while data loads; aria-hidden so SR users get loading state from a parent region.
import type { CSSProperties, HTMLAttributes } from 'react';

export type SkeletonRounded = 'sm' | 'md' | 'lg' | 'full' | 'none';

export interface SkeletonProps extends Omit<HTMLAttributes<HTMLDivElement>, 'children'> {
  w?: string | number;
  h?: string | number;
  rounded?: SkeletonRounded;
  className?: string;
  /** Set false to disable the pulse animation (rare, for nested skeletons). */
  animate?: boolean;
}

const ROUNDED_CLASS: Record<SkeletonRounded, string> = {
  sm: 'rounded-sm',
  md: 'rounded',
  lg: 'rounded-lg',
  full: 'rounded-full',
  none: 'rounded-none',
};

function toDim(v: string | number | undefined): string | undefined {
  if (v === undefined) return undefined;
  return typeof v === 'number' ? `${v}px` : v;
}

export default function Skeleton({
  w,
  h,
  rounded = 'md',
  className,
  animate = true,
  style,
  ...rest
}: SkeletonProps) {
  const baseClasses = `bg-zinc-800 ${animate !== false ? 'animate-pulse' : ''}`;
  const roundedClass = ROUNDED_CLASS[rounded];

  const hasDim = w !== undefined || h !== undefined;
  const styleObj: CSSProperties = hasDim
    ? { width: toDim(w), height: toDim(h), ...style }
    : { width: '100%', height: '1em', ...style };

  return (
    <div
      className={`${baseClasses} ${roundedClass} ${className ?? ''}`}
      style={styleObj}
      aria-hidden="true"
      {...rest}
    />
  );
}
