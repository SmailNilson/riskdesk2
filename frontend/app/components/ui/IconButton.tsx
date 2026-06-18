'use client';

import type { ButtonHTMLAttributes, ReactNode } from 'react';

export type IconButtonSize = 'sm' | 'md' | 'lg';
export type IconButtonTone = 'default' | 'danger' | 'success';

export interface IconButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  /** Required for a11y — icon-only buttons need an accessible label. */
  ariaLabel: string;
  /** default 'md' (44 px) */
  size?: IconButtonSize;
  /** default 'default' */
  tone?: IconButtonTone;
  /** pressed-look state */
  active?: boolean;
  /** the icon */
  children: ReactNode;
  className?: string;
}

const SIZE_CLASSES: Record<IconButtonSize, string> = {
  sm: 'w-9 h-9',
  md: 'w-11 h-11',
  lg: 'w-12 h-12',
};

const TONE_IDLE: Record<IconButtonTone, string> = {
  default:
    'bg-transparent border-zinc-800 text-zinc-400 hover:bg-zinc-800 hover:text-zinc-200',
  danger: 'border-zinc-800 text-red-400 hover:bg-red-950/40',
  success: 'border-zinc-800 text-emerald-400 hover:bg-emerald-950/40',
};

const TONE_ACTIVE: Record<IconButtonTone, string> = {
  default: 'bg-zinc-800 text-white border-zinc-700',
  danger: 'bg-red-950/40 text-red-300',
  success: 'bg-emerald-950/40 text-emerald-300',
};

const BASE_CLASSES =
  'inline-flex items-center justify-center rounded-lg border transition-colors';

const DISABLED_CLASSES = 'opacity-40 cursor-not-allowed pointer-events-none';

export function IconButton({
  ariaLabel,
  size = 'md',
  tone = 'default',
  active = false,
  disabled = false,
  type = 'button',
  className = '',
  children,
  ...rest
}: IconButtonProps) {
  const stateClasses = active ? TONE_ACTIVE[tone] : TONE_IDLE[tone];
  const composed = [
    BASE_CLASSES,
    SIZE_CLASSES[size],
    stateClasses,
    disabled ? DISABLED_CLASSES : '',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  return (
    <button
      {...rest}
      type={type}
      aria-label={ariaLabel}
      aria-pressed={active || undefined}
      disabled={disabled}
      className={composed}
    >
      {children}
    </button>
  );
}

export default IconButton;
