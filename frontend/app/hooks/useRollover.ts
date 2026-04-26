'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';

import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';

const BASE = API_BASE;

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined) {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

const WS_URL = buildWsUrl(WS_BASE, API_BASE);

export type RolloverStatusLevel = 'STABLE' | 'WARNING' | 'CRITICAL';

export interface RolloverInfo {
  instrument: string;
  contractMonth: string | null;
  expiryDate: string | null;
  daysToExpiry: number;
  status: RolloverStatusLevel;
}

export interface RolloverStatus {
  activeContracts: Record<string, string>;
  rolloverStatus: Record<string, RolloverInfo>;
}

export interface RolloverEvent {
  type?: 'OI_ROLLOVER';
  instrument: string;
  contractMonth?: string;
  currentMonth?: string;
  nextMonth?: string;
  expiryDate?: string;
  daysToExpiry?: number;
  status?: RolloverStatusLevel;
  action?: string;
  autoConfirmed?: boolean;
}

export function useRollover() {
  const [status, setStatus] = useState<RolloverStatus | null>(null);
  const [latestEvent, setLatestEvent] = useState<RolloverEvent | null>(null);
  const clientRef = useRef<Client | null>(null);

  const fetch_ = useCallback(async () => {
    try {
      const res = await fetch(`${BASE}/api/rollover/status`);
      if (!res.ok) return;
      setStatus(await res.json());
    } catch {}
  }, []);

  // Polling fallback (kept for disconnect resilience)
  useEffect(() => {
    fetch_();
    const id = setInterval(fetch_, 5 * 60 * 1000); // poll every 5 min
    return () => clearInterval(id);
  }, [fetch_]);

  // WebSocket: instant refresh on /topic/rollover
  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
      onConnect: () => {
        client.subscribe('/topic/rollover', (msg: IMessage) => {
          try {
            const event: RolloverEvent = JSON.parse(msg.body);
            setLatestEvent(event);
          } catch {}
          // Refresh authoritative status right after any rollover event
          fetch_();
        });
      },
    });
    client.activate();
    clientRef.current = client;
    return () => {
      clientRef.current?.deactivate();
    };
  }, [fetch_]);

  const confirmRollover = useCallback(async (instrument: string, contractMonth: string) => {
    const res = await fetch(
      `${BASE}/api/rollover/confirm?instrument=${instrument}&contractMonth=${contractMonth}`,
      { method: 'POST' }
    );
    if (res.ok) await fetch_(); // refresh status after confirmation
    return res.ok;
  }, [fetch_]);

  /** Returns the highest severity across all instruments (null when all STABLE). */
  const worstStatus: RolloverStatusLevel | null = status
    ? Object.values(status.rolloverStatus).reduce<RolloverStatusLevel | null>((worst, info) => {
        if (info.status === 'CRITICAL') return 'CRITICAL';
        if (info.status === 'WARNING' && worst !== 'CRITICAL') return 'WARNING';
        return worst;
      }, null)
    : null;

  return { status, worstStatus, latestEvent, confirmRollover, refresh: fetch_ };
}
