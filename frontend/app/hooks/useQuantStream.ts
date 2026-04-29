'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';
import type {
  AdviceView,
  PatternView,
  QuantInstrument,
  QuantNarrationView,
  QuantSnapshotView,
  QuantWsPayload,
} from '@/app/components/quant/types';

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined): string {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

function payloadToView(p: QuantWsPayload): QuantSnapshotView {
  return {
    instrument: p.instrument,
    score: p.score,
    price: p.price,
    priceSource: p.priceSource,
    dayMove: p.dayMove,
    scanTime: p.scanTime,
    entry: p.entry,
    sl: p.sl,
    tp1: p.tp1,
    tp2: p.tp2,
    shortSetup7_7: p.score >= 7,
    shortAlert6_7: p.score === 6,
    gates: Object.entries(p.gates).map(([gate, value]) => ({
      gate,
      ok: value.ok,
      reason: value.reason,
    })),
  };
}

const WS_URL = buildWsUrl(WS_BASE, API_BASE);

/**
 * Self-contained STOMP client for the Quant 7-Gates feed. Subscribes per
 * instrument to {@code /topic/quant/snapshot/{instr}} and to the global
 * {@code /topic/quant/signals} channel for setup-confirmation pings.
 */
export function useQuantStream(instruments: readonly QuantInstrument[]) {
  const clientRef = useRef<Client | null>(null);
  const [snapshots, setSnapshots] = useState<Record<string, QuantSnapshotView>>({});
  const [narrations, setNarrations] = useState<Record<string, QuantNarrationView>>({});
  const [advice, setAdvice] = useState<Record<string, AdviceView>>({});
  const [latestSignal, setLatestSignal] = useState<QuantSnapshotView | null>(null);
  const [connected, setConnected] = useState(false);

  const ack = useCallback(() => setLatestSignal(null), []);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
    });

    const subs: StompSubscription[] = [];

    client.onConnect = () => {
      setConnected(true);
      for (const instr of instruments) {
        subs.push(client.subscribe(`/topic/quant/snapshot/${instr}`, (msg: IMessage) => {
          try {
            const payload = JSON.parse(msg.body) as QuantWsPayload;
            setSnapshots(prev => ({ ...prev, [payload.instrument]: payloadToView(payload) }));
          } catch (err) {
            console.warn('quant snapshot parse failed', err);
          }
        }));

        subs.push(client.subscribe(`/topic/quant/narration/${instr}`, (msg: IMessage) => {
          try {
            const payload = JSON.parse(msg.body) as QuantWsPayload;
            setNarrations(prev => ({
              ...prev,
              [payload.instrument]: {
                pattern: extractPattern(payload),
                markdown: payload.markdown ?? '',
              },
            }));
          } catch (err) {
            console.warn('quant narration parse failed', err);
          }
        }));

        subs.push(client.subscribe(`/topic/quant/advice/${instr}`, (msg: IMessage) => {
          try {
            const payload = JSON.parse(msg.body) as QuantWsPayload;
            const a = payload.advice ?? null;
            if (!a) return;
            setAdvice(prev => ({ ...prev, [payload.instrument]: a }));
          } catch (err) {
            console.warn('quant advice parse failed', err);
          }
        }));
      }
      subs.push(client.subscribe('/topic/quant/signals', (msg: IMessage) => {
        try {
          const payload = JSON.parse(msg.body) as QuantWsPayload;
          setLatestSignal(payloadToView(payload));
        } catch (err) {
          console.warn('quant signal parse failed', err);
        }
      }));
    };

    client.onDisconnect = () => setConnected(false);
    client.onStompError = () => setConnected(false);

    client.activate();
    clientRef.current = client;

    return () => {
      subs.forEach(s => s.unsubscribe());
      clientRef.current?.deactivate();
      clientRef.current = null;
    };
    // The instruments array is intentionally treated as fixed for the panel lifetime;
    // callers should pass a stable reference (e.g. a module-level constant).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return { snapshots, narrations, advice, latestSignal, connected, ack };
}

function extractPattern(payload: QuantWsPayload): PatternView | null {
  const p = payload.pattern;
  if (!p || !p.type) return null;
  return {
    type: p.type,
    label: p.label,
    reason: p.reason,
    confidence: p.confidence,
    action: p.action,
  };
}
