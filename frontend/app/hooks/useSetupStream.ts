'use client';

import { useEffect, useRef, useState } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';

export interface GateResultView {
  gateName: string;
  passed: boolean;
  reason: string;
}

export interface SetupView {
  id: string;
  instrument: string;
  template: string;
  style: string;
  phase: string;
  regime: string;
  direction: string;
  finalScore: number;
  entryPrice: number | null;
  slPrice: number | null;
  tp1Price: number | null;
  tp2Price: number | null;
  rrRatio: number;
  playbookId: string | null;
  gateResults: GateResultView[];
  detectedAt: string;
  updatedAt: string;
}

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined): string {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

const WS_URL = buildWsUrl(WS_BASE, API_BASE);
const API_URL = API_BASE ?? '';

/**
 * Subscribes to /topic/quant/setup-recommendation/{instrument} and
 * seeds from the REST endpoint on mount.
 */
export function useSetupStream(instrument: string): SetupView[] {
  const [setups, setSetups] = useState<SetupView[]>([]);
  const clientRef = useRef<Client | null>(null);

  // Seed from REST on instrument change
  useEffect(() => {
    const lower = instrument.toLowerCase();
    fetch(`${API_URL}/api/quant/setups/${lower}`)
      .then(r => (r.ok ? r.json() : []))
      .then((data: SetupView[]) => setSetups(data))
      .catch(() => setSetups([]));
  }, [instrument]);

  // Subscribe to live updates
  useEffect(() => {
    const lower = instrument.toLowerCase();
    const topic = `/topic/quant/setup-recommendation/${lower}`;

    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
    });

    client.onConnect = () => {
      client.subscribe(topic, (msg: IMessage) => {
        try {
          const view: SetupView = JSON.parse(msg.body);
          setSetups(prev => {
            const idx = prev.findIndex(s => s.id === view.id);
            if (idx >= 0) {
              const next = [...prev];
              next[idx] = view;
              return next;
            }
            // Keep at most 10 active setups per instrument
            return [view, ...prev].slice(0, 10);
          });
        } catch {
          // ignore malformed message
        }
      });
    };

    client.activate();
    clientRef.current = client;

    return () => {
      client.deactivate();
    };
  }, [instrument]);

  return setups;
}
