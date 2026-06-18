'use client';

// Surface primitives matching the trading dashboard's dark palette.
// Used by Vague 3 panel migrations to replace ~30 ad-hoc card div patterns.

import type { HTMLAttributes, ReactNode } from 'react';

export type CardVariant = 'default' | 'raised' | 'sunken';

export interface CardProps extends HTMLAttributes<HTMLDivElement> {
  variant?: CardVariant;
  padded?: boolean;
  className?: string;
  children?: ReactNode;
}

const VARIANT_CLASSES: Record<CardVariant, string> = {
  default: 'bg-zinc-900/50 border border-zinc-800 rounded-md',
  raised: 'bg-zinc-900 border border-zinc-700 rounded-md shadow-lg',
  sunken: 'bg-zinc-950 border border-zinc-800 rounded-md',
};

export default function Card({
  variant = 'default',
  padded = true,
  className,
  children,
  ...rest
}: CardProps) {
  const baseClasses = VARIANT_CLASSES[variant];
  const paddingClass = padded ? 'p-3' : '';
  const merged = [baseClasses, paddingClass, className].filter(Boolean).join(' ');
  return (
    <div className={merged} {...rest}>
      {children}
    </div>
  );
}

export function CardHeader({ children, className }: { children: ReactNode; className?: string }) {
  return <div className={`flex items-center justify-between mb-2 ${className ?? ''}`}>{children}</div>;
}

export function CardTitle({ children, className }: { children: ReactNode; className?: string }) {
  return <h3 className={`text-sm font-medium text-white ${className ?? ''}`}>{children}</h3>;
}

export function CardEyebrow({ children, className }: { children: ReactNode; className?: string }) {
  return (
    <span className={`text-[10px] uppercase tracking-widest text-zinc-500 ${className ?? ''}`}>
      {children}
    </span>
  );
}
