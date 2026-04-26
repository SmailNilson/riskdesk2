'use client';

import { useCallback, useEffect, useState } from 'react';
import { api, IbkrAuthStatus, IbkrPortfolioSnapshot } from '@/app/lib/api';

function moneyClass(v: number) {
  return v > 0 ? 'text-emerald-400' : v < 0 ? 'text-red-400' : 'text-zinc-300';
}

function fmt(v: number | null | undefined, digits = 2) {
  if (v == null) return '—';
  return v.toLocaleString('en-US', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

function sideLabel(position: number) {
  return position >= 0 ? 'LONG' : 'SHORT';
}

export default function IbkrPortfolioPanel({
  selectedAccountId,
  onAccountChange,
  onRefreshRequested,
}: {
  selectedAccountId?: string;
  onAccountChange?: (accountId: string) => void;
  onRefreshRequested?: () => void | Promise<void>;
}) {
  const [data, setData] = useState<IbkrPortfolioSnapshot | null>(null);
  const [auth, setAuth] = useState<IbkrAuthStatus | null>(null);
  const [accountId, setAccountId] = useState<string>('');
  const [loading, setLoading] = useState(false);
  const [authLoading, setAuthLoading] = useState(false);

  const loadAuth = useCallback(async (refresh = false) => {
    setAuthLoading(true);
    try {
      const nextAuth = refresh ? await api.refreshIbkrAuth() : await api.getIbkrAuthStatus();
      setAuth(nextAuth);
    } catch (e) {
      console.error('IBKR auth fetch failed', e);
    } finally {
      setAuthLoading(false);
    }
  }, []);

  const load = useCallback(async (nextAccountId?: string) => {
    setLoading(true);
    try {
      const snapshot = await api.getIbkrPortfolio(nextAccountId || undefined);
      setData(snapshot);
      setAccountId(snapshot.selectedAccountId ?? '');
      if (snapshot.selectedAccountId && snapshot.selectedAccountId !== selectedAccountId) {
        onAccountChange?.(snapshot.selectedAccountId);
      }
    } catch (e) {
      console.error('IBKR portfolio fetch failed', e);
    } finally {
      setLoading(false);
    }
  }, [onAccountChange, selectedAccountId]);

  useEffect(() => { void load(selectedAccountId); }, [selectedAccountId, load]);
  useEffect(() => { void loadAuth(false); }, [loadAuth]);

  const handleAuthRefresh = useCallback(async () => {
    setAuthLoading(true);
    try {
      const nextAuth = await api.refreshIbkrAuth();
      setAuth(nextAuth);
      await load(accountId || selectedAccountId);
      await onRefreshRequested?.();
    } catch (e) {
      console.error('IBKR auth refresh failed', e);
    } finally {
      setAuthLoading(false);
    }
  }, [accountId, load, onRefreshRequested, selectedAccountId]);

  return (
    <div className="bg-zinc-900 rounded-lg border border-zinc-800 overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-800 bg-zinc-950/60 px-3 py-2">
        <div className="flex items-center gap-2">
          <span className={`h-2 w-2 rounded-full ${auth?.authenticated && auth?.connected ? 'bg-emerald-400' : 'bg-amber-400'}`} />
          <div>
            <div className="text-[10px] font-semibold uppercase tracking-wider text-zinc-400">IBKR Connection</div>
            <div className="text-[10px] text-zinc-600">
              {auth?.message ?? 'Checking IBKR connection…'}
            </div>
            <div className="text-[10px] text-zinc-700">
              {auth?.endpoint ?? 'socket://127.0.0.1:4001'}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => void handleAuthRefresh()}
            disabled={authLoading}
            className="rounded border border-zinc-700 px-2.5 py-1 text-[11px] text-zinc-300 hover:border-zinc-500 hover:text-white disabled:text-zinc-600"
          >
            {authLoading ? 'Refreshing…' : 'Refresh Connection'}
          </button>
        </div>
      </div>

      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-zinc-800 px-3 py-2">
        <div>
          <h2 className="text-xs font-semibold text-zinc-400 uppercase tracking-wider">IBKR Portfolio</h2>
          <p className="text-[10px] text-zinc-600">
            {data?.connected ? 'Live positions from IB Gateway native API' : data?.message ?? 'IBKR unavailable'}
          </p>
        </div>
        <div className="flex items-center gap-2">
          <select
            value={accountId}
            onChange={e => {
              const next = e.target.value;
              setAccountId(next);
              onAccountChange?.(next);
              void load(next);
            }}
            className="rounded border border-zinc-700 bg-zinc-950 px-2 py-1 text-[11px] text-zinc-300 outline-none"
            disabled={loading || !data?.accounts.length}
          >
            {(data?.accounts ?? []).map(account => (
              <option key={account.id} value={account.id}>
                {account.id} · {account.currency}
              </option>
            ))}
          </select>
          <button
            onClick={() => void load(accountId)}
            disabled={loading}
            className="rounded border border-zinc-700 px-2.5 py-1 text-[11px] text-zinc-300 hover:border-zinc-500 hover:text-white disabled:text-zinc-600"
          >
            {loading ? 'Loading…' : 'Refresh'}
          </button>
        </div>
      </div>

      {data?.connected ? (
        <>
          <div className="grid grid-cols-2 gap-px bg-zinc-800 md:grid-cols-6">
            <Metric label="Net Liq." value={`$${fmt(data.netLiquidation)}`} />
            <Metric label="Available" value={`$${fmt(data.availableFunds)}`} />
            <Metric label="Buying Power" value={`$${fmt(data.buyingPower)}`} />
            <Metric label="Gross Value" value={`$${fmt(data.grossPositionValue)}`} />
            <Metric label="Unreal. P&L" value={`$${fmt(data.totalUnrealizedPnl)}`} valueClass={moneyClass(data.totalUnrealizedPnl)} />
            <Metric label="Realized P&L" value={`$${fmt(data.totalRealizedPnl)}`} valueClass={moneyClass(data.totalRealizedPnl)} />
          </div>

          {data.positions.length === 0 ? (
            <div className="p-6 text-center text-sm text-zinc-500">No open IBKR positions</div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full text-xs">
                <thead>
                  <tr className="border-b border-zinc-800 text-[10px] uppercase tracking-wider text-zinc-500">
                    <th className="px-3 py-2 text-left">Contract</th>
                    <th className="px-3 py-2 text-left">Asset</th>
                    <th className="px-3 py-2 text-left">Side</th>
                    <th className="px-3 py-2 text-right">Pos</th>
                    <th className="px-3 py-2 text-right">Avg Px</th>
                    <th className="px-3 py-2 text-right">Mkt Px</th>
                    <th className="px-3 py-2 text-right">Mkt Value</th>
                    <th className="px-3 py-2 text-right">Unreal. P&L</th>
                    <th className="px-3 py-2 text-right">Realized P&L</th>
                  </tr>
                </thead>
                <tbody>
                  {data.positions.map(position => (
                    <tr key={`${position.accountId}-${position.conid}`} className="border-b border-zinc-800/50 hover:bg-zinc-800/30">
                      <td className="px-3 py-2">
                        <div className="font-medium text-white">{position.contractDesc}</div>
                        <div className="text-[10px] text-zinc-600">{position.accountId}</div>
                      </td>
                      <td className="px-3 py-2 text-zinc-400">{position.assetClass}</td>
                      <td className={`px-3 py-2 font-semibold ${position.position >= 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                        {sideLabel(position.position)}
                      </td>
                      <td className={`px-3 py-2 text-right font-mono ${position.position > 0 ? 'text-emerald-400' : 'text-red-400'}`}>
                        {fmt(Math.abs(position.position), 2)}
                      </td>
                      <td className="px-3 py-2 text-right font-mono text-zinc-300">{fmt(position.averagePrice)}</td>
                      <td className="px-3 py-2 text-right font-mono text-amber-300">{fmt(position.marketPrice)}</td>
                      <td className="px-3 py-2 text-right font-mono text-zinc-300">${fmt(position.marketValue)}</td>
                      <td className={`px-3 py-2 text-right font-mono ${moneyClass(position.unrealizedPnl)}`}>${fmt(position.unrealizedPnl)}</td>
                      <td className={`px-3 py-2 text-right font-mono ${moneyClass(position.realizedPnl)}`}>${fmt(position.realizedPnl)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </>
      ) : (
        <div className="p-6 text-center text-sm text-zinc-500">
          {data?.message ?? 'IBKR gateway not connected'}
        </div>
      )}
    </div>
  );
}

function Metric({ label, value, valueClass = 'text-zinc-200' }: { label: string; value: string; valueClass?: string }) {
  return (
    <div className="bg-zinc-900 px-3 py-2">
      <div className="text-[9px] uppercase tracking-wider text-zinc-500">{label}</div>
      <div className={`text-sm font-mono font-semibold ${valueClass}`}>{value}</div>
    </div>
  );
}
