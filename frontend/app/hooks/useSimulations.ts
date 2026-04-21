'use client';

/**
 * useSimulations — STOMP subscription to `/topic/simulations` plus REST seed.
 *
 * <p>Phase 2 UI hook: consumes the `trade_simulations` aggregate exposed by
 * {@code SimulationController} (see /api/simulations/*). Matches the STOMP/SockJS
 * idiom used by {@link useOrderFlow} and {@link useWebSocket}.
 *
 * <p>Update semantics:
 * <ul>
 *   <li>On mount, seeds state via {@code api.getRecentSimulations(MAX_SIMULATIONS)}.</li>
 *   <li>WebSocket pushes upsert by composite key {@code (reviewId, reviewType)}:
 *       existing entries are replaced in place (preserving overall order by
 *       {@code createdAt} desc after a re-sort); new entries are prepended.</li>
 *   <li>List is capped at {@code MAX_SIMULATIONS} to bound memory.</li>
 *   <li>A 30s polling fallback re-seeds from REST in case the STOMP session drops
 *       silently — mirroring the pattern in {@link useWebSocket}.</li>
 * </ul>
 */

import { useCallback, useEffect, useRef, useState } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { api, TradeSimulationView } from '@/app/lib/api';
import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';

const MAX_SIMULATIONS = 100;
const SEED_LIMIT = 50;
const POLL_INTERVAL_MS = 30_000;

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined): string {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

const WS_URL = buildWsUrl(WS_BASE, API_BASE);

function simulationKey(sim: TradeSimulationView): string {
  return `${sim.reviewType}:${sim.reviewId}`;
}

function sortByCreatedAtDesc(a: TradeSimulationView, b: TradeSimulationView): number {
  const aTime = Date.parse(a.createdAt);
  const bTime = Date.parse(b.createdAt);
  const aValid = Number.isFinite(aTime);
  const bValid = Number.isFinite(bTime);
  if (aValid && bValid) return bTime - aTime;
  if (aValid) return -1;
  if (bValid) return 1;
  return 0;
}

function upsertSimulation(
  current: TradeSimulationView[],
  incoming: TradeSimulationView,
): TradeSimulationView[] {
  const key = simulationKey(incoming);
  const filtered = current.filter(s => simulationKey(s) !== key);
  const merged = [incoming, ...filtered];
  merged.sort(sortByCreatedAtDesc);
  return merged.slice(0, MAX_SIMULATIONS);
}

export interface UseSimulationsResult {
  simulations: TradeSimulationView[];
  lastEvent: TradeSimulationView | null;
  connected: boolean;
  refresh: () => void;
}

export function useSimulations(): UseSimulationsResult {
  const clientRef = useRef<Client | null>(null);
  const [simulations, setSimulations] = useState<TradeSimulationView[]>([]);
  const [lastEvent, setLastEvent] = useState<TradeSimulationView | null>(null);
  const [connected, setConnected] = useState(false);

  const loadRecent = useCallback(() => {
    api.getRecentSimulations(SEED_LIMIT)
      .then((recent: TradeSimulationView[]) => {
        setSimulations(prev => {
          // Upsert each recent entry into current state — preserves WS-only
          // additions that may already be present beyond the REST window.
          const byKey = new Map(prev.map(s => [simulationKey(s), s]));
          for (const sim of recent) {
            byKey.set(simulationKey(sim), sim);
          }
          const merged = Array.from(byKey.values());
          merged.sort(sortByCreatedAtDesc);
          return merged.slice(0, MAX_SIMULATIONS);
        });
      })
      .catch(() => {
        // Swallow — polling fallback will retry. Consistent with useWebSocket.
      });
  }, []);

  const connect = useCallback(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        loadRecent();

        client.subscribe('/topic/simulations', (msg: IMessage) => {
          try {
            const sim = JSON.parse(msg.body) as TradeSimulationView;
            setSimulations(prev => upsertSimulation(prev, sim));
            setLastEvent(sim);
          } catch {
            // Ignore malformed payloads.
          }
        });
      },
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
      onWebSocketClose: () => setConnected(false),
    });

    client.activate();
    clientRef.current = client;
  }, [loadRecent]);

  useEffect(() => {
    loadRecent();
    connect();

    const poll = setInterval(loadRecent, POLL_INTERVAL_MS);

    return () => {
      clearInterval(poll);
      clientRef.current?.deactivate();
      clientRef.current = null;
    };
  }, [connect, loadRecent]);

  return {
    simulations,
    lastEvent,
    connected,
    refresh: loadRecent,
  };
}
