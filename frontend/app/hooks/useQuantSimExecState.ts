'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, type QuantSimExecState } from '@/app/lib/api';

interface UseQuantSimExecState {
  state: QuantSimExecState | null;
  error: string | null;
  /** Instrument currently being toggled (request in flight), or null. */
  busy: string | null;
  setEnabled: (instrument: string, enabled: boolean) => Promise<void>;
  refresh: () => Promise<void>;
}

/**
 * Loads and mutates the Quant 7-Gates Auto-IBKR mirror state (master flag +
 * per-instrument toggles). The server is authoritative: every successful
 * mutation returns the fresh snapshot, and any failure re-reads it so the UI
 * never drifts from the backend (e.g. a 400 for a non-allowlisted instrument
 * leaves the real state unchanged).
 */
export function useQuantSimExecState(): UseQuantSimExecState {
  const [state, setState] = useState<QuantSimExecState | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  const refresh = useCallback(async () => {
    try {
      setState(await api.getQuantSimExecState());
      setError(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }, []);

  useEffect(() => {
    void refresh();
  }, [refresh]);

  const setEnabled = useCallback(
    async (instrument: string, enabled: boolean) => {
      setBusy(instrument);
      try {
        const next = await api.setQuantSimAutoExecution(instrument, enabled);
        if (next) setState(next);
        else await refresh();
        setError(null);
      } catch (e) {
        setError(e instanceof Error ? e.message : String(e));
        await refresh();
      } finally {
        setBusy(null);
      }
    },
    [refresh],
  );

  return { state, error, busy, setEnabled, refresh };
}
