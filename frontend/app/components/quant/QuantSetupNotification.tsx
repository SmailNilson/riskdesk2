'use client';

import { useEffect, useRef } from 'react';
import { useQuantStream } from '@/app/hooks/useQuantStream';
import { QUANT_INSTRUMENTS } from './types';

/**
 * Fires a one-shot audio + visual ping when the backend confirms a
 * SHORT 7/7 setup. The audio is generated client-side via WebAudio so the
 * browser does not need to fetch an external sound file.
 */
export default function QuantSetupNotification() {
  const { latestSignal, ack } = useQuantStream(QUANT_INSTRUMENTS);
  const audioCtxRef = useRef<AudioContext | null>(null);

  useEffect(() => {
    if (!latestSignal) return;

    if (typeof window !== 'undefined' && 'AudioContext' in window) {
      try {
        audioCtxRef.current = audioCtxRef.current ?? new AudioContext();
        const ctx = audioCtxRef.current;
        const osc = ctx.createOscillator();
        const gain = ctx.createGain();
        osc.type = 'square';
        osc.frequency.setValueAtTime(880, ctx.currentTime);
        osc.frequency.setValueAtTime(660, ctx.currentTime + 0.18);
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
  }, [latestSignal, ack]);

  if (!latestSignal) return null;

  return (
    <div
      role="alert"
      className="fixed bottom-6 right-6 z-50 max-w-sm rounded-lg border border-emerald-500 bg-emerald-950/95 p-4 text-sm text-emerald-50 shadow-2xl"
    >
      <div className="flex items-start gap-3">
        <span className="text-2xl">🔔</span>
        <div className="flex-1">
          <div className="font-semibold">SHORT 7/7 — {latestSignal.instrument}</div>
          <div className="text-xs text-emerald-200 mt-1">
            score <span className="font-mono">{latestSignal.score}/7</span> · entry{' '}
            <span className="font-mono">{latestSignal.entry?.toFixed(2) ?? '—'}</span> · SL{' '}
            <span className="font-mono">{latestSignal.sl?.toFixed(2) ?? '—'}</span> · TP1{' '}
            <span className="font-mono">{latestSignal.tp1?.toFixed(2) ?? '—'}</span>
          </div>
          <button
            type="button"
            onClick={ack}
            className="mt-2 text-xs underline text-emerald-200 hover:text-emerald-100"
          >
            Dismiss
          </button>
        </div>
      </div>
    </div>
  );
}
