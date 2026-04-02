'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { MentorSignalReview } from '@/app/lib/api';
import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';

export interface PriceUpdate {
  instrument: string;
  displayName: string;
  price: number;
  timestamp: string;
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
    return () => {
      clientRef.current?.deactivate();
    };
  }, [connect, loadRecentAlerts, loadRecentMentorReviews]);

  return { prices, alerts, mentorSignalReviews, connected };
}
