'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { MentorSignalReview } from '@/app/lib/api';
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
const MENTOR_REVIEW_HISTORY_LIMIT = 1000;

export function useWebSocket() {
  const clientRef = useRef<Client | null>(null);
  const [prices, setPrices] = useState<Record<string, PriceUpdate>>({});
  const [alerts, setAlerts] = useState<AlertMessage[]>([]);
  const [mentorSignalReviews, setMentorSignalReviews] = useState<MentorSignalReview[]>([]);
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

  const loadRecentMentorReviews = useCallback(() => {
    fetch(`${API_URL}/api/mentor/auto-alerts/recent?limit=${MENTOR_REVIEW_HISTORY_LIMIT}`)
      .then(r => r.ok ? r.json() : [])
      .then((recent: MentorSignalReview[]) => {
        setMentorSignalReviews(prev => {
          const byId = new Map(prev.map(review => [review.id, review]));
          for (const review of recent.slice(0, MENTOR_REVIEW_HISTORY_LIMIT)) {
            byId.set(review.id, review);
          }
          return Array.from(byId.values())
            .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
            .slice(0, MENTOR_REVIEW_HISTORY_LIMIT);
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
        loadRecentAlerts();
        loadRecentMentorReviews();

        client.subscribe('/topic/prices', (msg: IMessage) => {
          const update: PriceUpdate = JSON.parse(msg.body);
          setPrices(prev => ({ ...prev, [update.instrument]: update }));
        });

        client.subscribe('/topic/alerts', (msg: IMessage) => {
          const alert: AlertMessage = JSON.parse(msg.body);
          setAlerts(prev => [alert, ...prev].slice(0, 50)); // keep last 50
        });

        client.subscribe('/topic/mentor-alerts', (msg: IMessage) => {
          const review: MentorSignalReview = JSON.parse(msg.body);
          setMentorSignalReviews(prev => {
            const filtered = prev.filter(item => item.id !== review.id);
            return [review, ...filtered].slice(0, MENTOR_REVIEW_HISTORY_LIMIT);
          });
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
  }, [loadRecentAlerts, loadRecentMentorReviews]);

  useEffect(() => {
    loadRecentAlerts();
    loadRecentMentorReviews();
    connect();

    // Periodic polls — recover from WebSocket disconnects
    const reviewPoll = setInterval(loadRecentMentorReviews, 30_000);
    const alertPoll = setInterval(loadRecentAlerts, 30_000);

    return () => {
      clearInterval(reviewPoll);
      clearInterval(alertPoll);
      clientRef.current?.deactivate();
    };
  }, [connect, loadRecentAlerts, loadRecentMentorReviews]);

  const refresh = useCallback(() => {
    fetch(`${API_URL}/api/alerts/recent`)
      .then(r => r.ok ? r.json() : [])
      .then((recent: AlertMessage[]) => setAlerts(recent.slice(0, 50)))
      .catch(() => {});
    fetch(`${API_URL}/api/mentor/auto-alerts/recent?limit=${MENTOR_REVIEW_HISTORY_LIMIT}`)
      .then(r => r.ok ? r.json() : [])
      .then((recent: MentorSignalReview[]) => setMentorSignalReviews(recent.slice(0, MENTOR_REVIEW_HISTORY_LIMIT)))
      .catch(() => {});
  }, []);

  return {
    prices, alerts, mentorSignalReviews,
    wtxSignals, wtxRsiSignals, wtxRsiStates,
    connected, refresh,
  };
}
