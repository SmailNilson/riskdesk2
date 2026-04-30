'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { api, type ActivePositionView } from '@/app/lib/api';
import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined): string {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

const WS_URL = buildWsUrl(WS_BASE, API_BASE);

/**
 * Result returned by {@link useActivePositions}.
 *
 * Live updates flow through the {@code /topic/positions} STOMP destination —
 * the backend pushes a fresh snapshot on every status change AND on a 5s
 * heartbeat tick. The hook also performs a one-shot REST fetch on mount so
 * the panel renders immediately even before the first WS frame arrives.
 */
export interface UseActivePositionsResult {
  positions: ActivePositionView[];
  loading: boolean;
  error: string | null;
  /** Triggers a market close for the given execution id. */
  close: (executionId: number) => Promise<ActivePositionView | null>;
  /** Forces a REST refresh — useful after a manual action that the WS may not echo immediately. */
  refresh: () => Promise<void>;
  /** True when the STOMP connection is up. */
  connected: boolean;
}

/**
 * Subscribes to {@code /topic/positions} and exposes a small API for the
 * Active Positions panel.
 *
 * <p>The hook intentionally does NOT recompute PnL client-side — the backend
 * already does it on every 5s heartbeat. If a future iteration wants
 * sub-second PnL granularity, a separate {@code /topic/prices}
 * subscription can be wired into a {@code useMemo} that derives the new
 * dollar / point figures from the latest price snapshot. We keep the
 * current behaviour deliberately simple to ship the panel quickly.</p>
 */
export function useActivePositions(): UseActivePositionsResult {
  const [positions, setPositions] = useState<ActivePositionView[]>([]);
  const [loading, setLoading] = useState<boolean>(true);
  const [error, setError] = useState<string | null>(null);
  const [connected, setConnected] = useState<boolean>(false);
  const clientRef = useRef<Client | null>(null);

  const refresh = useCallback(async () => {
    try {
      const list = await api.listActivePositions();
      setPositions(Array.isArray(list) ? list : []);
      setError(null);
    } catch (err) {
      console.warn('listActivePositions failed', err);
      setError(err instanceof Error ? err.message : 'fetch failed');
    } finally {
      setLoading(false);
    }
  }, []);

  // Initial REST snapshot — never blocks the WS subscription.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      await refresh();
      if (cancelled) return;
    })();
    return () => {
      cancelled = true;
    };
  }, [refresh]);

  // STOMP subscription — separate from the QuantStreamProvider client so the
  // hook stays self-contained and can be dropped into any panel without
  // requiring a provider higher up the tree.
  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
    });

    client.onConnect = () => {
      setConnected(true);
      client.subscribe('/topic/positions', (msg: IMessage) => {
        try {
          const payload = JSON.parse(msg.body) as ActivePositionView[];
          if (Array.isArray(payload)) {
            setPositions(payload);
            setLoading(false);
          }
        } catch (err) {
          console.warn('positions stream parse failed', err);
        }
      });
    };
    client.onDisconnect = () => setConnected(false);
    client.onStompError = () => setConnected(false);
    client.onWebSocketClose = () => setConnected(false);
    client.onWebSocketError = () => setConnected(false);

    client.activate();
    clientRef.current = client;
    return () => {
      void clientRef.current?.deactivate();
      clientRef.current = null;
    };
  }, []);

  const close = useCallback(
    async (executionId: number): Promise<ActivePositionView | null> => {
      try {
        const result = await api.closeActivePosition(executionId);
        // Optimistic local update — the WS frame will overwrite us soon.
        setPositions((prev) =>
          prev.map((p) => (p.executionId === executionId ? result : p))
        );
        return result;
      } catch (err) {
        console.warn('closeActivePosition failed', err);
        setError(err instanceof Error ? err.message : 'close failed');
        return null;
      }
    },
    []
  );

  return { positions, loading, error, close, refresh, connected };
}
