'use client';

import { useEffect, useRef, useState, useCallback } from 'react';
import { Client, IMessage } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface OrderFlowMetrics {
  instrument: string;
  delta: number;
  cumulativeDelta: number;
  buyVolume: number;
  sellVolume: number;
  buyRatioPct: number;
  deltaTrend: string;
  source: string;
}

export interface DepthMetrics {
  instrument: string;
  totalBidSize: number;
  totalAskSize: number;
  imbalance: number;
  spread: number;
  bidWall?: { price: number; size: number };
  askWall?: { price: number; size: number };
}

export interface AbsorptionEvent {
  instrument: string;
  side: string;
  score: number;
  delta: number;
  timestamp: string;
}

export interface SpoofingEvent {
  instrument: string;
  side: string;
  priceLevel: number;
  wallSize: number;
  spoofScore: number;
  timestamp: string;
}

export interface IcebergEvent {
  instrument: string;
  side: string;             // BID_ICEBERG | ASK_ICEBERG
  priceLevel: number;
  rechargeCount: number;
  avgRechargeSize: number;
  durationSeconds: number;
  icebergScore: number;
  timestamp: string;
}

export interface FlashCrashState {
  instrument: string;
  phase: string;
  conditionsMet: number;
  conditions: boolean[];
  reversalScore: number;
}

export interface FootprintLevel {
  price: number;
  buyVolume: number;
  sellVolume: number;
  delta: number;
  imbalance: boolean;
}

export interface FootprintBar {
  instrument: string;
  timeframe: string;
  barTimestamp: number;
  levels: Record<string, FootprintLevel>;
  pocPrice: number;
  totalBuyVolume: number;
  totalSellVolume: number;
  totalDelta: number;
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const MAX_EVENTS = 20;

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined) {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

const WS_URL = buildWsUrl(WS_BASE, API_BASE);

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

export function useOrderFlow() {
  const clientRef = useRef<Client | null>(null);

  const [orderFlowData, setOrderFlowData] = useState<Map<string, OrderFlowMetrics>>(new Map());
  const [depthData, setDepthData] = useState<Map<string, DepthMetrics>>(new Map());
  const [absorptionEvents, setAbsorptionEvents] = useState<AbsorptionEvent[]>([]);
  const [spoofingEvents, setSpoofingEvents] = useState<SpoofingEvent[]>([]);
  const [icebergEvents, setIcebergEvents] = useState<IcebergEvent[]>([]);
  const [flashCrashState, setFlashCrashState] = useState<Map<string, FlashCrashState>>(new Map());
  const [footprintData, setFootprintData] = useState<Map<string, FootprintBar>>(new Map());
  const [connected, setConnected] = useState(false);

  const connect = useCallback(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
      onConnect: () => {
        setConnected(true);

        client.subscribe('/topic/order-flow', (msg: IMessage) => {
          const metrics: OrderFlowMetrics = JSON.parse(msg.body);
          setOrderFlowData(prev => {
            const next = new Map(prev);
            next.set(metrics.instrument, metrics);
            return next;
          });
        });

        client.subscribe('/topic/depth', (msg: IMessage) => {
          const metrics: DepthMetrics = JSON.parse(msg.body);
          setDepthData(prev => {
            const next = new Map(prev);
            next.set(metrics.instrument, metrics);
            return next;
          });
        });

        client.subscribe('/topic/absorption', (msg: IMessage) => {
          const event: AbsorptionEvent = JSON.parse(msg.body);
          setAbsorptionEvents(prev => [event, ...prev].slice(0, MAX_EVENTS));
        });

        client.subscribe('/topic/spoofing', (msg: IMessage) => {
          const event: SpoofingEvent = JSON.parse(msg.body);
          setSpoofingEvents(prev => [event, ...prev].slice(0, MAX_EVENTS));
        });

        client.subscribe('/topic/iceberg', (msg: IMessage) => {
          const event: IcebergEvent = JSON.parse(msg.body);
          setIcebergEvents(prev => [event, ...prev].slice(0, MAX_EVENTS));
        });

        client.subscribe('/topic/flash-crash', (msg: IMessage) => {
          const state: FlashCrashState = JSON.parse(msg.body);
          setFlashCrashState(prev => {
            const next = new Map(prev);
            next.set(state.instrument, state);
            return next;
          });
        });

        client.subscribe('/topic/footprint', (msg: IMessage) => {
          const bar: FootprintBar = JSON.parse(msg.body);
          setFootprintData(prev => {
            const next = new Map(prev);
            next.set(bar.instrument, bar);
            return next;
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

  return {
    orderFlowData,
    depthData,
    absorptionEvents,
    spoofingEvents,
    icebergEvents,
    flashCrashState,
    footprintData,
    connected,
  };
}
