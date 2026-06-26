'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import type { WtxSignalView, WtxRsiSignalView, WtxRsiStrategyStateView } from '@/app/lib/api';
import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';

export interface PriceUpdate {
  instrument: string;
  displayName: string;
  price: number;
  timestamp: string;
  source?: string;
}

export interface AlertMessage {
  key?: string;
  severity: 'INFO' | 'WARNING' | 'DANGER';
  category: string;
  message: string;
  instrument: string | null;
  timestamp: string;
}

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined) {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

const WS_URL = buildWsUrl(WS_BASE, API_BASE);
const API_URL = API_BASE ?? '';

/**
 * A live `/topic/prices` frame older than this (while the STOMP socket still reports connected)
 * means the backend feed has frozen — the "real data not available" symptom. 45s clears the
 * backend's own 30s poll cadence with margin so a single skipped poll never trips the banner.
 */
const STALE_THRESHOLD_MS = 45_000;

export function useWebSocket() {
  const clientRef = useRef<Client | null>(null);
  // Coalesce high-frequency price ticks into one render per animation frame.
  const pendingPricesRef = useRef<Record<string, PriceUpdate>>({});
  const priceFlushRef = useRef<number | null>(null);
  // Wall-clock of the last `/topic/prices` frame, used to detect a frozen feed behind a
  // still-"connected" STOMP socket. 0 = no frame received yet.
  const lastPriceAtRef = useRef<number>(0);
  const [prices, setPrices] = useState<Record<string, PriceUpdate>>({});
  const [priceAgeMs, setPriceAgeMs] = useState<number | null>(null);
  const [alerts, setAlerts] = useState<AlertMessage[]>([]);
  const [wtxSignals, setWtxSignals] = useState<WtxSignalView[]>([]);
  const [wtxRsiSignals, setWtxRsiSignals] = useState<WtxRsiSignalView[]>([]);
  // Keyed by `${instrument}:${timeframe}` — the only WS topic shape the
  // server publishes for WTX+RSI state. Consumers index by panel identity.
  const [wtxRsiStates, setWtxRsiStates] = useState<Record<string, WtxRsiStrategyStateView>>({});
  const [connected, setConnected] = useState(false);

  const loadRecentAlerts = useCallback(() => {
    fetch(`${API_URL}/api/alerts/recent`)
      .then(r => r.ok ? r.json() : [])
      .then((recent: AlertMessage[]) => {
        setAlerts(prev => {
          const existing = new Set(prev.map(a => a.timestamp + a.message));
          const fresh = recent.filter(a => !existing.has(a.timestamp + a.message));
          return [...prev, ...fresh].slice(0, 50);
        });
      })
      .catch(() => {});
  }, []);

  const connect = useCallback(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);
        // Give the freshly (re)connected feed a full STALE_THRESHOLD_MS grace to deliver its first
        // frame before it can be flagged stale — otherwise an outage > 45s would flash a false
        // "Données figées depuis Ns" between reconnect and the first new frame.
        lastPriceAtRef.current = Date.now();
        loadRecentAlerts();

        client.subscribe('/topic/prices', (msg: IMessage) => {
          lastPriceAtRef.current = Date.now();   // feed liveness — stamp before coalescing
          const update: PriceUpdate = JSON.parse(msg.body);
          pendingPricesRef.current[update.instrument] = update;
          if (priceFlushRef.current === null) {
            priceFlushRef.current = requestAnimationFrame(() => {
              priceFlushRef.current = null;
              const batch = pendingPricesRef.current;
              pendingPricesRef.current = {};
              setPrices(prev => ({ ...prev, ...batch }));
            });
          }
        });

        client.subscribe('/topic/alerts', (msg: IMessage) => {
          const alert: AlertMessage = JSON.parse(msg.body);
          setAlerts(prev => [alert, ...prev].slice(0, 50)); // keep last 50
        });

        client.subscribe('/topic/wtx-signals', (msg: IMessage) => {
          const signal: WtxSignalView = JSON.parse(msg.body);
          setWtxSignals(prev => [signal, ...prev].slice(0, 50));
        });

        client.subscribe('/topic/wtxrsi-signals', (msg: IMessage) => {
          const signal: WtxRsiSignalView = JSON.parse(msg.body);
          setWtxRsiSignals(prev => [signal, ...prev].slice(0, 50));
        });

        // Per-(instrument, timeframe) state stream. Topic shape mirrors the WTx
        // pattern: /topic/wtxrsi-state/{instrument}/{timeframe}. We can't enumerate
        // active panels here, so subscribe to a wildcard pattern via a STOMP
        // destination filter and key by the topic suffix.
        client.subscribe('/topic/wtxrsi-state', (msg: IMessage) => {
          // Some brokers deliver via individual topics, others via a fan-out parent.
          // Either way, the payload carries instrument+timeframe so we can key off it.
          try {
            const state: WtxRsiStrategyStateView = JSON.parse(msg.body);
            if (state.instrument && state.timeframe) {
              setWtxRsiStates(prev => ({ ...prev, [`${state.instrument}:${state.timeframe}`]: state }));
            }
          } catch { /* swallow malformed payloads */ }
        });
      },
      onDisconnect: () => setConnected(false),
      onStompError: () => setConnected(false),
    });

    client.activate();
    clientRef.current = client;
  }, [loadRecentAlerts]);

  useEffect(() => {
    loadRecentAlerts();
    connect();

    // Periodic poll — recover from WebSocket disconnects
    const alertPoll = setInterval(loadRecentAlerts, 30_000);
    // Tick the feed-age clock once a second so the staleness banner reflects a frozen feed
    // even though no new frames are arriving to drive a render.
    const ageTick = setInterval(() => {
      setPriceAgeMs(lastPriceAtRef.current === 0 ? null : Date.now() - lastPriceAtRef.current);
    }, 1000);

    return () => {
      clearInterval(alertPoll);
      clearInterval(ageTick);
      if (priceFlushRef.current !== null) cancelAnimationFrame(priceFlushRef.current);
      clientRef.current?.deactivate();
    };
  }, [connect, loadRecentAlerts]);

  const refresh = useCallback(() => {
    fetch(`${API_URL}/api/alerts/recent`)
      .then(r => r.ok ? r.json() : [])
      .then((recent: AlertMessage[]) => setAlerts(recent.slice(0, 50)))
      .catch(() => {});
  }, []);

  // Feed is "stale" only when the socket is up but frames have stopped — distinct from a dropped
  // socket (connected === false). null age (no frame yet) is treated as not-stale during warmup.
  const dataStale = connected && priceAgeMs !== null && priceAgeMs > STALE_THRESHOLD_MS;

  return {
    prices, alerts,
    wtxSignals, wtxRsiSignals, wtxRsiStates,
    connected, refresh,
    priceAgeMs, dataStale,
  };
}
