'use client';

import { useEffect, useMemo, useState } from 'react';
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
  /** Always non-null — derived locally from {@code rows} via {@code useMemo}. */
  stats: Quant7GatesSimulationStats;
  connected: boolean;
}

/**
 * Subscribes to /topic/quant/simulations and keeps a live list of the harness
 * trades. Bootstraps via REST so the panel renders something before the first
 * WebSocket frame.
 *
 * <p>Merge rule: incoming rows replace existing rows with the same {@code id};
 * unknown ids are prepended (newest first by openedAt).
 *
 * <p>Stats are derived locally from {@code rows} rather than refetched on
 * every WS event. The backend emits mark-to-market updates for every open
 * row on every scan tick, so one open trade would otherwise produce a
 * continuous stream of redundant `/api/quant/simulations/stats` calls and
 * could let slower, older responses overwrite fresher state on the wire.
 * Computing from {@code rows} (which the WS stream keeps current) gives
 * race-free, request-free stats that always match the rendered table.
 */
export function useQuant7GatesSimulations(): State {
  const [rows, setRows] = useState<Quant7GatesSimulationView[]>([]);
  const [connected, setConnected] = useState(false);

  useEffect(() => {
    let cancelled = false;
    api
      .listQuantSimulations()
      .then(initial => {
        if (cancelled) return;
        // Merge by id rather than overwriting — on a fast WS connect / slow
        // HTTP boot, live updates can land BEFORE the REST snapshot resolves.
        // Last-write-wins on duplicates would clobber freshly-opened or
        // freshly-closed rows the WS already delivered. Live state takes
        // precedence; REST only fills in rows the WS hasn't pushed yet.
        setRows(prev => {
          const seen = new Set(prev.map(r => r.id));
          const merged = prev.slice();
          for (const r of initial) {
            if (!seen.has(r.id)) merged.push(r);
          }
          merged.sort((a, b) => {
            const at = Date.parse(b.openedAt) - Date.parse(a.openedAt);
            return Number.isFinite(at) ? at : 0;
          });
          return merged;
        });
      })
      .catch(err => console.warn('quant-sim bootstrap failed', err));

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

  const stats = useMemo<Quant7GatesSimulationStats>(() => computeStats(rows), [rows]);

  return { rows, stats, connected };
}

/**
 * Pure stats reducer over the live rows. Mirrors the backend
 * {@code Quant7GatesSimulationService.Stats} aggregate so the panel and the
 * REST endpoint converge on the same numbers — without the per-tick fetch.
 *
 * <p>OPEN rows are excluded from win/loss tallies but counted in
 * {@code openCount}. A closed row counts as a win when {@code pnlPoints > 0},
 * a loss when {@code pnlPoints < 0}; exactly-zero outcomes (rare) are
 * resolved-but-flat and contribute to {@code closedCount} only.
 */
function computeStats(rows: Quant7GatesSimulationView[]): Quant7GatesSimulationStats {
  let closedCount = 0;
  let wins = 0;
  let losses = 0;
  let netPoints = 0;
  let netUsd = 0;
  let openCount = 0;
  for (const r of rows) {
    if (r.status === 'OPEN') {
      openCount++;
      continue;
    }
    closedCount++;
    const pts = r.pnlPoints ?? 0;
    const usd = r.pnlUsd ?? 0;
    netPoints += pts;
    netUsd += usd;
    if (pts > 0) wins++;
    else if (pts < 0) losses++;
  }
  const decided = wins + losses;
  return {
    closedCount,
    wins,
    losses,
    winRatePct: decided > 0 ? (wins * 100) / decided : null,
    netPoints,
    netUsd,
    openCount,
  };
}
