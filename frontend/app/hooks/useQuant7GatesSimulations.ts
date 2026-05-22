'use client';

import { useEffect, useState } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { api, type Quant7GatesSimulationView, type Quant7GatesSimulationStats } from '@/app/lib/api';
import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined): string {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

const WS_URL = buildWsUrl(WS_BASE, API_BASE);

interface State {
  rows: Quant7GatesSimulationView[];
  stats: Quant7GatesSimulationStats | null;
  connected: boolean;
}

/**
 * Subscribes to /topic/quant/simulations and keeps a live list of the harness
 * trades. Bootstraps via REST so the panel renders something before the first
 * WebSocket frame.
 *
 * <p>Merge rule: incoming rows replace existing rows with the same {@code id};
 * unknown ids are prepended (newest first by openedAt).
 */
export function useQuant7GatesSimulations(): State {
  const [rows, setRows] = useState<Quant7GatesSimulationView[]>([]);
  const [stats, setStats] = useState<Quant7GatesSimulationStats | null>(null);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .listQuantSimulations()
      .then(initial => {
        if (cancelled) return;
        setRows(initial);
      })
      .catch(err => console.warn('quant-sim bootstrap failed', err));
    api
      .getQuantSimulationStats()
      .then(s => { if (!cancelled) setStats(s); })
      .catch(err => console.warn('quant-sim stats bootstrap failed', err));

    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
    });

    client.onConnect = () => {
      setConnected(true);
      client.subscribe('/topic/quant/simulations', (msg: IMessage) => {
        try {
          const incoming = JSON.parse(msg.body) as Quant7GatesSimulationView;
          setRows(prev => {
            const next = prev.slice();
            const idx = next.findIndex(r => r.id === incoming.id);
            if (idx >= 0) {
              next[idx] = incoming;
            } else {
              next.unshift(incoming);
            }
            // Sort newest open first, then by openedAt desc.
            next.sort((a, b) => {
              const at = Date.parse(b.openedAt) - Date.parse(a.openedAt);
              return Number.isFinite(at) ? at : 0;
            });
            return next;
          });
          // Refresh aggregate stats on each event so win-rate is reactive.
          api
            .getQuantSimulationStats()
            .then(s => setStats(s))
            .catch(() => undefined);
        } catch (err) {
          console.warn('quant-sim WS parse failed', err);
        }
      });
    };
    client.onDisconnect = () => setConnected(false);
    client.onStompError = () => setConnected(false);
    client.onWebSocketClose = () => setConnected(false);
    client.onWebSocketError = () => setConnected(false);
    client.activate();

    return () => {
      cancelled = true;
      void client.deactivate();
    };
  }, []);

  return { rows, stats, connected };
}
