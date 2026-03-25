'use client';

import { useState } from 'react';
import { PositionView, api } from '@/app/lib/api';
import { PriceUpdate } from '@/app/hooks/useWebSocket';

interface Props {
  positions: PositionView[];
  prices: Record<string, PriceUpdate>;
  onRefresh: () => void;
}

function pnlClass(v: number | null) {
  if (v == null) return 'text-zinc-400';
  return v > 0 ? 'text-emerald-400' : v < 0 ? 'text-red-400' : 'text-zinc-400';
}

function fmt(v: number | null, d = 2) {
  if (v == null) return '—';
  return v >= 0 ? `+${v.toFixed(d)}` : v.toFixed(d);
}

export default function PositionsTable({ positions, prices, onRefresh }: Props) {
  const [closingId, setClosingId] = useState<number | null>(null);
  const [exitPrices, setExitPrices] = useState<Record<number, string>>({});

  async function handleClose(id: number) {
    const raw = exitPrices[id];
    const price = parseFloat(raw);
    if (isNaN(price) || price <= 0) {
      alert('Enter a valid exit price');
      return;
    }
    try {
      await api.closePosition(id, price);
      setClosingId(null);
      onRefresh();
    } catch (e) {
      alert(`Close failed: ${e}`);
    }
  }

  if (positions.length === 0) {
    return (
      <div className="bg-zinc-900 rounded-lg border border-zinc-800 p-6 text-center text-zinc-500 text-sm">
        No open positions
      </div>
    );
  }

  return (
    <div className="bg-zinc-900 rounded-lg border border-zinc-800 overflow-hidden">
      <div className="overflow-x-auto">
        <table className="w-full text-xs">
          <thead>
            <tr className="border-b border-zinc-800 text-zinc-500 text-[10px] uppercase tracking-wider">
              <th className="px-3 py-2 text-left">Instrument</th>
              <th className="px-3 py-2 text-left">Side</th>
              <th className="px-3 py-2 text-right">Qty</th>
              <th className="px-3 py-2 text-right">Entry</th>
              <th className="px-3 py-2 text-right">Live</th>
              <th className="px-3 py-2 text-right">Stop</th>
              <th className="px-3 py-2 text-right">TP</th>
              <th className="px-3 py-2 text-right">Unreal. P&L</th>
              <th className="px-3 py-2 text-right">R:R</th>
              <th className="px-3 py-2 text-right">Risk $</th>
              <th className="px-3 py-2 text-left">Notes</th>
              <th className="px-3 py-2 text-right">Action</th>
            </tr>
          </thead>
          <tbody>
            {positions.map(p => {
              const live = prices[p.instrument]?.price ?? p.currentPrice;
              const positionId = p.id;
              return (
                <tr key={`${p.source}-${p.id ?? p.instrument}-${p.accountId ?? 'na'}`} className="border-b border-zinc-800/50 hover:bg-zinc-800/30 transition-colors">
                  <td className="px-3 py-2">
                    <div className="font-medium text-white">{p.instrument}</div>
                    <div className="text-[10px] text-zinc-600">{p.instrumentName}</div>
                  </td>
                  <td className="px-3 py-2">
                    <span className={`font-semibold ${p.side === 'LONG' ? 'text-emerald-400' : 'text-red-400'}`}>
                      {p.side}
                    </span>
                  </td>
                  <td className="px-3 py-2 text-right font-mono">{p.quantity}</td>
                  <td className="px-3 py-2 text-right font-mono">{p.entryPrice}</td>
                  <td className="px-3 py-2 text-right font-mono text-amber-300">{live?.toFixed?.(5) ?? '—'}</td>
                  <td className="px-3 py-2 text-right font-mono text-zinc-400">{p.stopLoss ?? '—'}</td>
                  <td className="px-3 py-2 text-right font-mono text-zinc-400">{p.takeProfit ?? '—'}</td>
                  <td className={`px-3 py-2 text-right font-mono ${pnlClass(p.unrealizedPnL)}`}>
                    {fmt(p.unrealizedPnL)}
                  </td>
                  <td className="px-3 py-2 text-right font-mono text-zinc-300">
                    {p.riskRewardRatio != null ? `1:${p.riskRewardRatio}` : '—'}
                  </td>
                  <td className="px-3 py-2 text-right font-mono text-red-400">
                    {p.riskAmount != null ? `-${p.riskAmount.toFixed(0)}` : '—'}
                  </td>
                  <td className="px-3 py-2 text-zinc-500 max-w-[160px] truncate">
                    {p.notes ?? [p.source, p.assetClass, p.accountId].filter(Boolean).join(' · ')}
                  </td>
                  <td className="px-3 py-2 text-right">
                    {p.closable && positionId != null && closingId === positionId ? (
                      <div className="flex items-center gap-1 justify-end">
                        <input
                          type="number"
                          step="0.0001"
                          className="w-20 bg-zinc-800 border border-zinc-600 rounded px-1.5 py-0.5 text-xs text-white"
                          placeholder="Exit px"
                          value={exitPrices[positionId] ?? ''}
                          onChange={e => setExitPrices(prev => ({ ...prev, [positionId]: e.target.value }))}
                        />
                        <button
                          onClick={() => handleClose(positionId)}
                          className="text-[10px] bg-red-600 hover:bg-red-500 text-white px-2 py-0.5 rounded transition-colors"
                        >OK</button>
                        <button
                          onClick={() => setClosingId(null)}
                          className="text-[10px] text-zinc-500 hover:text-zinc-300"
                        >✕</button>
                      </div>
                    ) : p.closable && positionId != null ? (
                      <button
                        onClick={() => setClosingId(positionId)}
                        className="text-[10px] border border-zinc-700 hover:border-red-500 hover:text-red-400 text-zinc-400 px-2 py-0.5 rounded transition-colors"
                      >Close</button>
                    ) : (
                      <span className="text-[10px] text-zinc-600">IBKR</span>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
