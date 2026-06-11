'use client';

import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
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

/**
 * Period at which the client re-syncs from REST to drop rows the server
 * has evicted (closed-history cap, process restart, or operator reset).
 * Backend storage is intentionally ephemeral / capped, so the client must
 * not retain rows indefinitely or derived stats drift forever upward.
 */
const RESYNC_INTERVAL_MS = 60_000;

interface State {
  rows: Quant7GatesSimulationView[];
  /** Always non-null — derived locally from {@code rows} via {@code useMemo}. */
  stats: Quant7GatesSimulationStats;
  connected: boolean;
}

/**
 * Subscribes to {@code /topic/quant/simulations} and keeps a live list of the
 * harness trades. Boot + reconnect + a 60 s heartbeat re-sync from REST keep
 * the client in step with the server's view; in between, the WebSocket stream
 * applies per-row updates.
 *
 * <p>Sync model:
 * <ul>
 *   <li><b>Server authoritative on row set</b> — periodic / on-reconnect
 *       resync REPLACES the client list with what REST returns (less the few
 *       fresher rows the WS may have just pushed). Rows the backend has
 *       dropped (eviction past the closed-history cap, in-memory reset,
 *       reconnect after restart) disappear from the UI on the next resync.</li>
 *   <li><b>WS authoritative on row content</b> — incoming WS frames replace
 *       any row with the same id (mark-to-market keeps P&amp;L live) and
 *       prepend unknown ids.</li>
 * </ul>
 *
 * <p>Stats are derived locally from {@code rows} via {@code useMemo} so the
 * strip is race-free, request-free and always consistent with the table.
 */
export function useQuant7GatesSimulations(): State {
  const [rows, setRows] = useState<Quant7GatesSimulationView[]>([]);
  const [connected, setConnected] = useState(false);
  /** Monotonic id assigned to every WS update so resync can skip overwriting fresher rows. */
  const lastWsUpdateIdRef = useRef<Map<number, number>>(new Map());
  const wsTickRef = useRef<number>(0);

  /**
   * Replace the row list with the server's current view, preserving any rows
   * the WS has updated AFTER the REST request was issued (the wsTickRef
   * snapshot). Without that guard, an in-flight WS frame could be overwritten
   * by a slightly older REST response on the wire.
   */
  const resyncFromServer = useCallback(async () => {
    const issuedAtTick = wsTickRef.current;
    try {
      const fresh = await api.listQuantSimulations();
      setRows(prev => {
        const freshById = new Map(fresh.map(r => [r.id, r]));
        // Keep rows the WS updated after we issued the REST call — those
        // are newer than the REST snapshot by construction.
        const merged: Quant7GatesSimulationView[] = [];
        const fresherWsIds = new Set<number>();
        for (const r of prev) {
          const wsTick = lastWsUpdateIdRef.current.get(r.id) ?? 0;
          if (wsTick > issuedAtTick) {
            merged.push(r);
            fresherWsIds.add(r.id);
          }
        }
        for (const r of fresh) {
          if (!fresherWsIds.has(r.id)) merged.push(r);
        }
        merged.sort((a, b) => {
          const at = Date.parse(b.openedAt) - Date.parse(a.openedAt);
          return Number.isFinite(at) ? at : 0;
        });
        // Drop tick entries for rows no longer in the merged view so the
        // dedup map doesn't leak.
        const keep = new Set(merged.map(r => r.id));
        for (const id of Array.from(lastWsUpdateIdRef.current.keys())) {
          if (!keep.has(id)) lastWsUpdateIdRef.current.delete(id);
        }
        // Garbage-collect old fresh entries that the WS already had.
        for (const id of Array.from(freshById.keys())) {
          if (!keep.has(id)) lastWsUpdateIdRef.current.delete(id);
        }
        return merged;
      });
    } catch (err) {
      console.warn('quant-sim resync failed', err);
    }
  }, []);

  useEffect(() => {
    let cancelled = false;
    // Initial load — same merge guard as periodic resync so an early WS
    // frame isn't clobbered by the slower HTTP response.
    void resyncFromServer();

    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
    });

    client.onConnect = () => {
      if (cancelled) return;
      setConnected(true);
      // Reconnect (or first connect): the server may have evicted rows
      // while we were offline. Replace from REST to converge.
      void resyncFromServer();
      client.subscribe('/topic/quant/simulations', (msg: IMessage) => {
        try {
          const incoming = JSON.parse(msg.body) as Quant7GatesSimulationView;
          const tick = ++wsTickRef.current;
          lastWsUpdateIdRef.current.set(incoming.id, tick);
          setRows(prev => {
            const next = prev.slice();
            const idx = next.findIndex(r => r.id === incoming.id);
            if (idx >= 0) {
              next[idx] = incoming;
            } else {
              next.unshift(incoming);
            }
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

    // Heartbeat resync — catches backend evictions during steady-state
    // operation (closed-history cap, operator reset). Skip while offline
    // since `connected===false` means the next reconnect will resync.
    const heartbeat = window.setInterval(() => {
      if (cancelled) return;
      void resyncFromServer();
    }, RESYNC_INTERVAL_MS);

    return () => {
      cancelled = true;
      window.clearInterval(heartbeat);
      void client.deactivate();
    };
  }, [resyncFromServer]);

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
export function computeStats(rows: Quant7GatesSimulationView[]): Quant7GatesSimulationStats {
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
