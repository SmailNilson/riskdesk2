'use client';

import { useEffect, useState, useCallback } from 'react';

import { API_BASE } from '@/app/lib/runtimeConfig';

const BASE = API_BASE;

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

export function useRollover() {
  const [status, setStatus] = useState<RolloverStatus | null>(null);

  const fetch_ = useCallback(async () => {
    try {
      const res = await fetch(`${BASE}/api/rollover/status`);
      if (!res.ok) return;
      setStatus(await res.json());
    } catch {}
  }, []);

  useEffect(() => {
    fetch_();
    const id = setInterval(fetch_, 5 * 60 * 1000); // poll every 5 min
    return () => clearInterval(id);
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

  return { status, worstStatus, confirmRollover, refresh: fetch_ };
}
