'use client';

import { useEffect, useRef } from 'react';
import { useQuantStream } from '@/app/hooks/useQuantStream';

/**
 * Fires a one-shot audio + visual ping when the backend confirms a
 * SHORT 7/7 setup. The audio is generated client-side via WebAudio so the
 * browser does not need to fetch an external sound file.
 *
 * Reads from the shared {@code QuantStreamProvider} — one STOMP client is
 * shared across this notification and {@code QuantGatePanel} (PR #297 P2 fix).
 */
export default function QuantSetupNotification() {
  const { latestSignal, ack } = useQuantStream();
  const audioCtxRef = useRef<AudioContext | null>(null);

  const isLong = latestSignal ? Boolean(latestSignal.longSetup7_7) : false;

  useEffect(() => {
    if (!latestSignal) return;

    if (typeof window !== 'undefined' && 'AudioContext' in window) {
      try {
        audioCtxRef.current = audioCtxRef.current ?? new AudioContext();
        const ctx = audioCtxRef.current;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'square';

        if (isLong) {
          // Rising audio pitch cue for LONG setup
          osc.frequency.setValueAtTime(660, ctx.currentTime);
          osc.frequency.setValueAtTime(880, ctx.currentTime + 0.18);
        } else {
          // Falling audio pitch cue for SHORT setup
          osc.frequency.setValueAtTime(880, ctx.currentTime);
          osc.frequency.setValueAtTime(660, ctx.currentTime + 0.18);
        }

        gain.gain.setValueAtTime(0.18, ctx.currentTime);
        gain.gain.exponentialRampToValueAtTime(0.001, ctx.currentTime + 0.5);
        osc.connect(gain);
        gain.connect(ctx.destination);
        osc.start();
        osc.stop(ctx.currentTime + 0.55);
      } catch (err) {
        console.warn('quant audio cue failed', err);
      }
    }

    const handle = window.setTimeout(ack, 8000);
    return () => window.clearTimeout(handle);
  }, [latestSignal, ack, isLong]);

  if (!latestSignal) return null;

  const precision = latestSignal.instrument === 'E6' ? 5 : 2;
  const activeEntry = isLong ? latestSignal.longEntry : latestSignal.entry;
  const activeSl = isLong ? latestSignal.longSl : latestSignal.sl;
  const activeTp = isLong ? latestSignal.longTp1 : latestSignal.tp1;

  const containerClass = isLong
    ? "fixed bottom-6 right-6 z-50 max-w-sm rounded-lg border border-emerald-500 bg-emerald-950/95 p-4 text-sm text-emerald-50 shadow-2xl transition-all duration-300"
    : "fixed bottom-6 right-6 z-50 max-w-sm rounded-lg border border-rose-500 bg-rose-950/95 p-4 text-sm text-rose-50 shadow-2xl transition-all duration-300";

  const textClass = isLong ? "text-emerald-200" : "text-rose-200";
  const dismissClass = isLong
    ? "mt-2 text-xs underline text-emerald-200 hover:text-emerald-100 font-medium transition-colors"
    : "mt-2 text-xs underline text-rose-200 hover:text-rose-100 font-medium transition-colors";

  return (
    <div role="alert" className={containerClass}>
      <div className="flex items-start gap-3">
        <span className="text-2xl">{isLong ? '🟢' : '🔴'}</span>
        <div className="flex-1">
          <div className="font-semibold uppercase tracking-wider">
            {isLong ? 'LONG 7/7' : 'SHORT 7/7'} — {latestSignal.instrument}
          </div>
          <div className={`text-xs ${textClass} mt-1 leading-relaxed`}>
            score <span className="font-mono font-bold">{isLong ? latestSignal.longScore : latestSignal.score}/7</span> · entry{' '}
            <span className="font-mono font-bold">{activeEntry?.toFixed(precision) ?? '—'}</span> · SL{' '}
            <span className="font-mono font-bold">{activeSl?.toFixed(precision) ?? '—'}</span> · TP1{' '}
            <span className="font-mono font-bold">{activeTp?.toFixed(precision) ?? '—'}</span>
          </div>
          <button
            type="button"
            onClick={ack}
            className={dismissClass}
          >
            Dismiss Alert
          </button>
        </div>
      </div>
    </div>
  );
}
