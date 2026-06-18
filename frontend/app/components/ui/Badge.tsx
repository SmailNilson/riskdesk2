'use client';

import type { HTMLAttributes, ReactNode } from 'react';

export type BadgeVariant =
  | 'bull'
  | 'bear'
  | 'warn'
  | 'info'
  | 'urgent'
  | 'neutral'
  | 'live'
  | 'stale';

export type BadgeSize = 'xs' | 'sm';

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant;
  size?: BadgeSize;
  dot?: boolean;
  pulse?: boolean;
  children?: ReactNode;
  className?: string;
}

interface VariantStyle {
  chip: string;
  dot: string;
  defaultDot: boolean;
  defaultPulse: boolean;
}

const VARIANT_STYLES: Record<BadgeVariant, VariantStyle> = {
  bull: {
    chip: 'bg-emerald-950/70 text-emerald-300',
    dot: 'bg-emerald-400',
    defaultDot: false,
    defaultPulse: false,
  },
  bear: {
    chip: 'bg-red-950/70 text-red-300',
    dot: 'bg-red-400',
    defaultDot: false,
    defaultPulse: false,
  },
  warn: {
    chip: 'bg-amber-950/70 text-amber-300',
    dot: 'bg-amber-400',
    defaultDot: false,
    defaultPulse: false,
  },
  info: {
    chip: 'bg-blue-950/70 text-blue-300',
    dot: 'bg-blue-400',
    defaultDot: false,
    defaultPulse: false,
  },
  urgent: {
    chip: 'bg-fuchsia-950/70 text-fuchsia-300',
    dot: 'bg-fuchsia-400',
    defaultDot: false,
    defaultPulse: false,
  },
  neutral: {
    chip: 'bg-zinc-800 text-zinc-300',
    dot: 'bg-zinc-400',
    defaultDot: false,
    defaultPulse: false,
  },
  live: {
    chip: 'bg-emerald-950/70 text-emerald-300',
    dot: 'bg-emerald-400',
    defaultDot: true,
    defaultPulse: true,
  },
  stale: {
    chip: 'bg-amber-950/70 text-amber-400',
    dot: 'bg-amber-400',
    defaultDot: true,
    defaultPulse: false,
  },
};

const SIZE_STYLES: Record<BadgeSize, string> = {
  xs: 'text-[10px] px-1.5 py-0.5 rounded',
  sm: 'text-[11px] px-2 py-0.5 rounded-md',
};

export function Badge({
  variant = 'neutral',
  size = 'sm',
  dot,
  pulse,
  children,
  className,
  ...rest
}: BadgeProps) {
  const variantStyle = VARIANT_STYLES[variant];
  const sizeClass = SIZE_STYLES[size];

  const showDot = dot ?? variantStyle.defaultDot;
  const isPulsing = pulse ?? variantStyle.defaultPulse;

  return (
    <span
      className={`inline-flex items-center font-medium ${variantStyle.chip} ${sizeClass} ${className ?? ''}`}
      {...rest}
    >
      {showDot && (
        <span
          className={`inline-block w-1.5 h-1.5 rounded-full ${variantStyle.dot} ${isPulsing ? 'animate-pulse' : ''} mr-1.5`}
          aria-hidden="true"
        />
      )}
      {children}
    </span>
  );
}

export default Badge;
