'use client';

import {
  useCallback,
  useEffect,
  useId,
  useRef,
  useState,
  type KeyboardEvent,
  type PointerEvent,
  type ReactNode,
} from 'react';

export type HoldButtonTone = 'bull' | 'bear' | 'urgent' | 'default';

export interface HoldButtonProps {
  onConfirm: () => void | Promise<void>;
  /** default 1000 */
  holdMs?: number;
  label: ReactNode;
  /** default 'default' */
  tone?: HoldButtonTone;
  disabled?: boolean;
  className?: string;
  /** Optional secondary label shown below the main label during hold (e.g. "Maintenir 1.0s"). */
  hint?: string;
}

const TONE_CLASSES: Record<HoldButtonTone, string> = {
  bull: 'bg-emerald-600 text-zinc-950 hover:bg-emerald-500',
  bear: 'bg-red-600 text-white hover:bg-red-500',
  urgent: 'bg-fuchsia-600 text-white hover:bg-fuchsia-500',
  default: 'bg-zinc-800 text-white border border-zinc-700 hover:bg-zinc-700',
};

const BASE_CLASSES =
  'relative overflow-hidden inline-flex items-center justify-center w-full rounded-lg px-4 py-3 font-medium transition-colors select-none touch-none';

const DISABLED_CLASSES = 'opacity-50 cursor-not-allowed pointer-events-none';

export function HoldButton({
  onConfirm,
  holdMs = 1000,
  label,
  tone = 'default',
  disabled = false,
  className = '',
  hint,
}: HoldButtonProps) {
  const [progress, setProgress] = useState(0);
  const [submitting, setSubmitting] = useState(false);
  const [holding, setHolding] = useState(false);

  const rafIdRef = useRef<number | null>(null);
  const timeoutIdRef = useRef<number | null>(null);
  const startTsRef = useRef<number | null>(null);
  const completedRef = useRef(false);
  const mountedRef = useRef(true);
  const hintId = useId();

  const clearTimers = useCallback(() => {
    if (rafIdRef.current !== null) {
      cancelAnimationFrame(rafIdRef.current);
      rafIdRef.current = null;
    }
    if (timeoutIdRef.current !== null) {
      window.clearTimeout(timeoutIdRef.current);
      timeoutIdRef.current = null;
    }
    startTsRef.current = null;
  }, []);

  useEffect(() => {
    return () => {
      mountedRef.current = false;
      clearTimers();
    };
  }, [clearTimers]);

  const runConfirm = useCallback(async () => {
    completedRef.current = true;
    clearTimers();
    setProgress(1);
    setHolding(false);
    setSubmitting(true);
    try {
      await onConfirm();
    } finally {
      if (mountedRef.current) {
        setSubmitting(false);
        setProgress(0);
      }
    }
  }, [clearTimers, onConfirm]);

  const startHold = useCallback(() => {
    if (disabled || submitting || holding) return;
    completedRef.current = false;
    setHolding(true);
    setProgress(0);
    startTsRef.current = performance.now();

    const tick = () => {
      if (startTsRef.current === null) return;
      const elapsed = performance.now() - startTsRef.current;
      const pct = Math.min(1, elapsed / holdMs);
      setProgress(pct);
      if (pct < 1 && !completedRef.current) {
        rafIdRef.current = requestAnimationFrame(tick);
      }
    };
    rafIdRef.current = requestAnimationFrame(tick);

    timeoutIdRef.current = window.setTimeout(() => {
      if (!completedRef.current) {
        void runConfirm();
      }
    }, holdMs);
  }, [disabled, submitting, holding, holdMs, runConfirm]);

  const cancelHold = useCallback(() => {
    if (completedRef.current) return;
    clearTimers();
    setHolding(false);
    setProgress(0);
  }, [clearTimers]);

  const handlePointerDown = (event: PointerEvent<HTMLButtonElement>) => {
    // Only respond to primary pointer interactions.
    if (event.button !== undefined && event.button !== 0) return;
    event.currentTarget.setPointerCapture?.(event.pointerId);
    startHold();
  };

  const handlePointerEnd = (event: PointerEvent<HTMLButtonElement>) => {
    try {
      event.currentTarget.releasePointerCapture?.(event.pointerId);
    } catch {
      // ignore — capture may already be released
    }
    cancelHold();
  };

  // Keyboard hold support: start on keydown of Space/Enter, cancel on keyup.
  // Suppress the browser's default click activation so we don't fire onConfirm twice.
  const isActivationKey = (event: KeyboardEvent<HTMLButtonElement>) =>
    event.key === ' ' || event.key === 'Spacebar' || event.key === 'Enter';

  const handleKeyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (!isActivationKey(event)) return;
    event.preventDefault();
    if (event.repeat) return;
    startHold();
  };

  const handleKeyUp = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (!isActivationKey(event)) return;
    event.preventDefault();
    cancelHold();
  };

  const composed = [
    BASE_CLASSES,
    TONE_CLASSES[tone],
    disabled || submitting ? DISABLED_CLASSES : '',
    className,
  ]
    .filter(Boolean)
    .join(' ');

  const showHint = Boolean(hint) && (holding || submitting);

  return (
    <button
      type="button"
      className={composed}
      disabled={disabled || submitting}
      onPointerDown={handlePointerDown}
      onPointerUp={handlePointerEnd}
      onPointerLeave={handlePointerEnd}
      onPointerCancel={handlePointerEnd}
      onKeyDown={handleKeyDown}
      onKeyUp={handleKeyUp}
      aria-describedby={hint ? hintId : undefined}
      aria-busy={submitting || undefined}
    >
      <span
        aria-hidden="true"
        className="absolute inset-y-0 left-0 bg-black/20 transition-none"
        style={{ width: `${progress * 100}%` }}
      />
      <span className="relative z-10 flex flex-col items-center justify-center gap-0.5">
        <span>{submitting ? '...' : label}</span>
        {showHint && hint ? (
          <span className="text-xs opacity-90">{hint}</span>
        ) : null}
      </span>
      {hint ? (
        <span id={hintId} className="sr-only">
          {hint}
        </span>
      ) : null}
    </button>
  );
}

export default HoldButton;
