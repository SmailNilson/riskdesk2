'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { MentorSignalReview } from '@/app/lib/api';

export interface PriceUpdate {
  instrument: string;
  displayName: string;
  price: number;
  timestamp: string;
}

export interface AlertMessage {
  severity: 'INFO' | 'WARNING' | 'DANGER';
  category: string;
  message: string;
  instrument: string | null;
  timestamp: string;
}

const WS_URL = process.env.NEXT_PUBLIC_WS_URL ?? 'http://localhost:8080/ws';
const API_URL = process.env.NEXT_PUBLIC_API_URL ?? 'http://localhost:8080';
const MENTOR_REVIEW_HISTORY_LIMIT = 1000;

export function useWebSocket() {
  const clientRef = useRef<Client | null>(null);
  const [prices, setPrices] = useState<Record<string, PriceUpdate>>({});
  const [alerts, setAlerts] = useState<AlertMessage[]>([]);
  const [mentorSignalReviews, setMentorSignalReviews] = useState<MentorSignalReview[]>([]);
  const [connected, setConnected] = useState(false);

  const connect = useCallback(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);

        // Seed with recent alerts from REST (fired before WS connected)
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

        fetch(`${API_URL}/api/mentor/auto-alerts/recent?limit=${MENTOR_REVIEW_HISTORY_LIMIT}`)
          .then(r => r.ok ? r.json() : [])
          .then((recent: MentorSignalReview[]) => {
            setMentorSignalReviews(recent.slice(0, MENTOR_REVIEW_HISTORY_LIMIT));
          })
          .catch(() => {});

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
  }, []);

  useEffect(() => {
    connect();
    return () => {
      clientRef.current?.deactivate();
    };
  }, [connect]);

  return { prices, alerts, mentorSignalReviews, connected };
}
