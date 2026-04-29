'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';
import type { QuantInstrument, QuantSnapshotView, QuantWsPayload } from '@/app/components/quant/types';

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
        const s = client.subscribe(`/topic/quant/snapshot/${instr}`, (msg: IMessage) => {
          try {
            const payload = JSON.parse(msg.body) as QuantWsPayload;
            const view = payloadToView(payload);
            setSnapshots(prev => ({ ...prev, [view.instrument]: view }));
          } catch (err) {
            console.warn('quant snapshot parse failed', err);
          }
        });
        subs.push(s);
      }
      const signalSub = client.subscribe('/topic/quant/signals', (msg: IMessage) => {
        try {
          const payload = JSON.parse(msg.body) as QuantWsPayload;
          setLatestSignal(payloadToView(payload));
        } catch (err) {
          console.warn('quant signal parse failed', err);
        }
      });
      subs.push(signalSub);
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

  return { snapshots, latestSignal, connected, ack };
}
