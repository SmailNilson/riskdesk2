'use client';

import {
  createContext,
  createElement,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from 'react';
import { Client, IMessage, StompSubscription } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { API_BASE, WS_BASE } from '@/app/lib/runtimeConfig';
import type { AutoArmStreamPayload } from '@/app/lib/api';
import type {
  AdviceView,
  PatternView,
  QuantInstrument,
  QuantNarrationView,
  QuantSnapshotView,
  QuantWsPayload,
} from '@/app/components/quant/types';

function buildWsUrl(wsBase: string | undefined, apiBase: string | undefined): string {
  const base = (wsBase || apiBase || '').replace(/\/$/, '');
  if (!base) return '/ws';
  return base.endsWith('/ws') ? base : `${base}/ws`;
}

function payloadToView(p: QuantWsPayload): QuantSnapshotView {
  const longScore = p.longScore ?? 0;
  return {
    instrument: p.instrument,
    score: p.score,
    longScore,
    price: p.price,
    priceSource: p.priceSource,
    dayMove: p.dayMove,
    scanTime: p.scanTime,
    entry: p.entry,
    sl: p.sl,
    tp1: p.tp1,
    tp2: p.tp2,
    longEntry: p.longEntry ?? p.entry,
    longSl: p.longSl ?? null,
    longTp1: p.longTp1 ?? null,
    longTp2: p.longTp2 ?? null,
    shortSetup7_7: p.score >= 7,
    shortAlert6_7: p.score === 6,
    longSetup7_7: longScore >= 7,
    longAlert6_7: longScore === 6,
    gates: Object.entries(p.gates).map(([gate, value]) => ({
      gate,
      ok: value.ok,
      reason: value.reason,
    })),
    // SHORT structural filters (PR #299)
    structuralBlocks: p.structuralBlocks ?? [],
    structuralWarnings: p.structuralWarnings ?? [],
    structuralScoreModifier: p.structuralScoreModifier ?? 0,
    finalScore: p.finalScore ?? p.score,
    shortBlocked: p.shortBlocked ?? false,
    shortAvailable: p.shortAvailable ?? p.score >= 6,
    // LONG structural filters (LONG-symmetry slice)
    longStructuralBlocks: p.longStructuralBlocks ?? [],
    longStructuralWarnings: p.longStructuralWarnings ?? [],
    longStructuralScoreModifier: p.longStructuralScoreModifier ?? 0,
    longFinalScore: p.longFinalScore ?? longScore,
    longBlocked: p.longBlocked ?? false,
    longAvailable: p.longAvailable ?? longScore >= 6,
  };
}

const WS_URL = buildWsUrl(WS_BASE, API_BASE);

/**
 * Per-instrument auto-arm state surfaced to the dashboard. Mirrors the
 * lifecycle states emitted by the backend ({@code AutoArmStateChangedEvent}).
 * {@code armed} carries the latest ARMED payload (with entry/SL/TP) so the
 * UI can render the badge and countdown without an extra REST hit.
 */
export interface AutoArmInstrumentState {
  state: 'ARMED' | 'CANCELLED' | 'FIRED' | 'EXPIRED' | 'AUTO_SUBMITTED' | 'IDLE';
  armed: AutoArmStreamPayload | null;
  lastChangeAt: string | null;
  lastReason: string | null;
}

interface QuantStreamValue {
  snapshots: Record<string, QuantSnapshotView>;
  narrations: Record<string, QuantNarrationView>;
  advice: Record<string, AdviceView>;
  latestSignal: QuantSnapshotView | null;
  /** Per-instrument auto-arm state, keyed by instrument symbol. */
  autoArm: Record<string, AutoArmInstrumentState>;
  connected: boolean;
  ack: () => void;
}

const QuantStreamContext = createContext<QuantStreamValue | null>(null);

interface ProviderProps {
  instruments: readonly QuantInstrument[];
  children: ReactNode;
}

/**
 * Mounts ONE STOMP client for the whole dashboard. Consumers
 * (`QuantGatePanel`, `QuantSetupNotification`, …) call {@link useQuantStream}
 * which reads the shared state from context. Without this provider, hosting
 * two panels would open two SockJS connections per session and double the
 * broker's quant traffic — see PR #297 review feedback (P2).
 */
export function QuantStreamProvider({ instruments, children }: ProviderProps): JSX.Element {
  const clientRef = useRef<Client | null>(null);
  const [snapshots, setSnapshots] = useState<Record<string, QuantSnapshotView>>({});
  const [narrations, setNarrations] = useState<Record<string, QuantNarrationView>>({});
  const [advice, setAdvice] = useState<Record<string, AdviceView>>({});
  const [latestSignal, setLatestSignal] = useState<QuantSnapshotView | null>(null);
  const [connected, setConnected] = useState(false);
  const [autoArm, setAutoArm] = useState<Record<string, AutoArmInstrumentState>>({});

  const ack = useCallback(() => setLatestSignal(null), []);

  useEffect(() => {
    const client = new Client({
      webSocketFactory: () => new SockJS(WS_URL),
      reconnectDelay: 5000,
    });

    const subs: StompSubscription[] = [];

    client.onConnect = () => {
      setConnected(true);
      for (const instr of instruments) {
        subs.push(client.subscribe(`/topic/quant/snapshot/${instr}`, (msg: IMessage) => {
          try {
            const payload = JSON.parse(msg.body) as QuantWsPayload;
            setSnapshots(prev => ({ ...prev, [payload.instrument]: payloadToView(payload) }));
          } catch (err) {
            console.warn('quant snapshot parse failed', err);
          }
        }));

        subs.push(client.subscribe(`/topic/quant/narration/${instr}`, (msg: IMessage) => {
          try {
            const payload = JSON.parse(msg.body) as QuantWsPayload;
            setNarrations(prev => ({
              ...prev,
              [payload.instrument]: {
                pattern: extractPattern(payload),
                markdown: payload.markdown ?? '',
              },
            }));
          } catch (err) {
            console.warn('quant narration parse failed', err);
          }
        }));

        subs.push(client.subscribe(`/topic/quant/advice/${instr}`, (msg: IMessage) => {
          try {
            const payload = JSON.parse(msg.body) as QuantWsPayload;
            const a = payload.advice ?? null;
            if (!a) return;
            setAdvice(prev => ({ ...prev, [payload.instrument]: a }));
          } catch (err) {
            console.warn('quant advice parse failed', err);
          }
        }));

        // PR #303 — auto-arm lifecycle. ARMED carries the full plan; the
        // other kinds (CANCELLED / FIRED / EXPIRED / AUTO_SUBMITTED) only
        // change the state badge.
        subs.push(client.subscribe(`/topic/quant/auto-arm/${instr}`, (msg: IMessage) => {
          try {
            const payload = JSON.parse(msg.body) as AutoArmStreamPayload;
            setAutoArm(prev => {
              const existing = prev[payload.instrument];
              if (payload.kind === 'ARMED') {
                return {
                  ...prev,
                  [payload.instrument]: {
                    state: 'ARMED',
                    armed: payload,
                    lastChangeAt: payload.armedAt ?? null,
                    lastReason: payload.reasoning ?? null,
                  },
                };
              }
              return {
                ...prev,
                [payload.instrument]: {
                  state: payload.kind,
                  armed: existing?.armed ?? null,
                  lastChangeAt: payload.changedAt ?? existing?.lastChangeAt ?? null,
                  lastReason: payload.reason ?? existing?.lastReason ?? null,
                },
              };
            });
          } catch (err) {
            console.warn('quant auto-arm parse failed', err);
          }
        }));
      }
      subs.push(client.subscribe('/topic/quant/signals', (msg: IMessage) => {
        try {
          const payload = JSON.parse(msg.body) as QuantWsPayload;
          setLatestSignal(payloadToView(payload));
        } catch (err) {
          console.warn('quant signal parse failed', err);
        }
      }));
    };

    client.onDisconnect = () => setConnected(false);
    client.onStompError = () => setConnected(false);
    // Abrupt network drops (Wi-Fi loss, VPN switch, server crash) surface
    // through the underlying WebSocket close/error callbacks, not through a
    // graceful STOMP DISCONNECT frame. Without these the badge stays "live"
    // until the next reconnect attempt — misleading the trader.
    client.onWebSocketClose = () => setConnected(false);
    client.onWebSocketError = () => setConnected(false);

    client.activate();
    clientRef.current = client;

    return () => {
      subs.forEach(s => s.unsubscribe());
      clientRef.current?.deactivate();
      clientRef.current = null;
    };
    // The instruments list is intentionally fixed for the dashboard lifetime.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const value = useMemo<QuantStreamValue>(
    () => ({ snapshots, narrations, advice, latestSignal, autoArm, connected, ack }),
    [snapshots, narrations, advice, latestSignal, autoArm, connected, ack],
  );

  return createElement(QuantStreamContext.Provider, { value }, children);
}

/**
 * Consumes the shared {@link QuantStreamProvider}. Throws a clear error if
 * a consumer is mounted outside the provider so the missing wiring is obvious.
 */
export function useQuantStream(): QuantStreamValue {
  const ctx = useContext(QuantStreamContext);
  if (ctx === null) {
    throw new Error(
      'useQuantStream must be used inside <QuantStreamProvider>. ' +
      'Wrap the dashboard subtree with QuantStreamProvider so all quant panels share one STOMP client.'
    );
  }
  return ctx;
}

function extractPattern(payload: QuantWsPayload): PatternView | null {
  const p = payload.pattern;
  if (!p || !p.type) return null;
  return {
    type: p.type,
    label: p.label,
    reason: p.reason,
    confidence: p.confidence,
    action: p.action,
    // PR #310 — present from backends that emit the LONG mirror. Consumers
    // can read it directly; if absent (older backend), patternActionFor() in
    // QuantGatePanel falls back to flipping `action` (TRADE↔AVOID).
    longAction: p.longAction,
  };
}
