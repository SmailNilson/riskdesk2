'use client';

import { useEffect, useState } from 'react';
import { useRollover, RolloverInfo } from '@/app/hooks/useRollover';
import { api, RolloverOiEntry, RolloverOiStatus } from '@/app/lib/api';

/**
 * Shown below MetricsBar when any futures contract is within the rollover window
 * OR when the open-interest (OI) crossover recommends a roll.
 * - Orange banner  → WARNING  (≤ 7 days to expiry)
 * - Red banner     → CRITICAL (≤ 3 days to expiry OR OI crossover reached)
 *
 * The trader explicitly confirms each rollover — nothing happens automatically.
 *
 * OI-based signal is a separate track from time-to-expiry: on some instruments
 * the next month takes liquidity long before expiry, and vice versa. We surface
 * both so the trader has the full picture before pressing ROLL.
 */
export default function RolloverBanner() {
  const { status, worstStatus, confirmRollover } = useRollover();
  const [confirmTarget, setConfirmTarget] = useState<RolloverInfo | null>(null);
  const [newMonth, setNewMonth]           = useState('');
  const [confirming, setConfirming]       = useState(false);
  // OI crossover map — refreshed every 5 min to match the useRollover cadence.
  // Backend entries are either a full recommendation (action, currentOI, nextOI)
  // or a status object ({ status: 'UNAVAILABLE' | 'ERROR' }); we filter to the
  // actionable ones.
  const [oiStatus, setOiStatus] = useState<RolloverOiStatus>({});

  useEffect(() => {
    let cancelled = false;
    const load = async () => {
      try {
        const res = await api.getRolloverOiStatus();
        if (!cancelled) setOiStatus(res ?? {});
      } catch {
        if (!cancelled) setOiStatus({});
      }
    };
    void load();
    const id = setInterval(load, 5 * 60 * 1000);
    return () => { cancelled = true; clearInterval(id); };
  }, []);

  const oiRolls = Object.entries(oiStatus).filter(
    ([, entry]) => entry && entry.action === 'RECOMMEND_ROLL'
  ) as [string, RolloverOiEntry][];

  // If neither the time-based nor the OI-based track wants attention, stay hidden.
  if ((!status || worstStatus === null) && oiRolls.length === 0) return null;

  const alerts = status
    ? Object.values(status.rolloverStatus).filter(
        r => r.status === 'WARNING' || r.status === 'CRITICAL'
      )
    : [];

  // OI crossover forces CRITICAL styling: liquidity has already migrated and
  // staying on the old contract means worse fills and stale orderbook depth.
  const hasOiCrossover = oiRolls.length > 0;
  const effectiveWorst: 'WARNING' | 'CRITICAL' =
    worstStatus === 'CRITICAL' || hasOiCrossover ? 'CRITICAL' : 'WARNING';
  const bannerBg    = effectiveWorst === 'CRITICAL' ? 'bg-red-950 border-red-700'   : 'bg-amber-950 border-amber-700';
  const badgeColor  = effectiveWorst === 'CRITICAL' ? 'bg-red-600 text-white'       : 'bg-amber-500 text-black';
  const buttonColor = effectiveWorst === 'CRITICAL' ? 'bg-red-600 hover:bg-red-500' : 'bg-amber-500 hover:bg-amber-400 text-black';

  async function handleConfirm() {
    if (!confirmTarget || !newMonth.match(/^\d{6}$/)) return;
    setConfirming(true);
    await confirmRollover(confirmTarget.instrument, newMonth);
    setConfirming(false);
    setConfirmTarget(null);
    setNewMonth('');
  }

  return (
    <>
      {/* Banner */}
      <div className={`flex flex-wrap items-center gap-3 px-4 py-2 border-b text-xs ${bannerBg}`}>
        <span className={`px-2 py-0.5 rounded font-bold uppercase tracking-wider text-[10px] ${badgeColor}`}>
          {effectiveWorst === 'CRITICAL' ? 'Rollover Urgent' : 'Rollover Imminent'}
        </span>

        <div className="flex flex-wrap gap-3">
          {alerts.map(info => (
            <span key={info.instrument} className="flex items-center gap-1.5 text-zinc-200">
              <span className="font-mono font-semibold">{info.instrument}</span>
              <span className="text-zinc-400">{info.contractMonth}</span>
              <span className="text-zinc-500">expires</span>
              <span className={info.status === 'CRITICAL' ? 'text-red-400 font-semibold' : 'text-amber-400'}>
                {info.expiryDate ?? '?'} ({info.daysToExpiry}d)
              </span>
              <button
                onClick={() => { setConfirmTarget(info); setNewMonth(''); }}
                className={`ml-1 px-2 py-0.5 rounded text-[10px] font-semibold ${buttonColor} transition-colors`}
              >
                ROLL
              </button>
            </span>
          ))}
        </div>

        {/* OI crossover track — separate from time-to-expiry. Shown in red
            because next-month liquidity has overtaken current-month. */}
        {oiRolls.length > 0 && (
          <div className="flex flex-wrap items-center gap-2 basis-full">
            <span className="text-[10px] uppercase tracking-wider font-semibold text-red-300">
              OI crossover
            </span>
            {oiRolls.map(([instrument, entry]) => {
              const currentOI = entry.currentOI ?? 0;
              const nextOI = entry.nextOI ?? 0;
              const ratio = currentOI > 0 ? (nextOI / currentOI) : null;
              const existingInfo = status?.rolloverStatus?.[instrument];
              return (
                <span
                  key={instrument}
                  className="flex items-center gap-1.5 text-zinc-200"
                  title={`${instrument}: next month OI (${nextOI.toLocaleString()}) > current month OI (${currentOI.toLocaleString()}) — switch recommended`}
                >
                  <span className="font-mono font-semibold">{instrument}</span>
                  <span className="text-zinc-500">{entry.currentMonth}</span>
                  <span className="text-red-400">→</span>
                  <span className="text-zinc-300 font-mono">{entry.nextMonth}</span>
                  <span className="text-[10px] text-zinc-500">
                    OI {currentOI.toLocaleString()} → {nextOI.toLocaleString()}
                    {ratio !== null && ratio > 0 ? ` (${ratio.toFixed(2)}×)` : ''}
                  </span>
                  {existingInfo && (
                    <button
                      onClick={() => {
                        setConfirmTarget(existingInfo);
                        setNewMonth(entry.nextMonth ?? '');
                      }}
                      className="ml-1 px-2 py-0.5 rounded text-[10px] font-semibold bg-red-600 hover:bg-red-500 transition-colors"
                    >
                      SWITCH NOW
                    </button>
                  )}
                </span>
              );
            })}
          </div>
        )}
      </div>

      {/* Confirmation modal */}
      {confirmTarget && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70">
          <div className="bg-zinc-900 border border-zinc-700 rounded-xl p-6 w-80 flex flex-col gap-4 shadow-2xl">
            <h2 className="text-sm font-bold text-white">Confirm Rollover — {confirmTarget.instrument}</h2>

            <p className="text-xs text-zinc-400">
              Current contract: <span className="text-white font-mono">{confirmTarget.contractMonth}</span>
              <br />
              Expires: <span className={confirmTarget.status === 'CRITICAL' ? 'text-red-400' : 'text-amber-400'}>
                {confirmTarget.expiryDate} ({confirmTarget.daysToExpiry}d)
              </span>
            </p>

            <div className="flex flex-col gap-1">
              <label className="text-[10px] text-zinc-500 uppercase tracking-wider">New contract month (YYYYMM)</label>
              <input
                type="text"
                placeholder="e.g. 202608"
                maxLength={6}
                value={newMonth}
                onChange={e => setNewMonth(e.target.value.replace(/\D/g, ''))}
                className="bg-zinc-800 border border-zinc-600 rounded px-3 py-1.5 text-sm font-mono text-white outline-none focus:border-emerald-500"
              />
              {newMonth.length > 0 && !newMonth.match(/^\d{6}$/) && (
                <span className="text-[10px] text-red-400">Format requis : 6 chiffres (ex. 202608)</span>
              )}
            </div>

            <p className="text-[10px] text-zinc-500">
              Tous les indicateurs, candles et le Mentor IA basculeront immédiatement sur ce nouveau contrat.
              Cette action est irréversible sans modification manuelle.
            </p>

            <div className="flex gap-2 justify-end">
              <button
                onClick={() => setConfirmTarget(null)}
                className="px-3 py-1.5 rounded text-xs text-zinc-400 hover:text-white border border-zinc-700 hover:border-zinc-500 transition-colors"
              >
                Annuler
              </button>
              <button
                onClick={handleConfirm}
                disabled={!newMonth.match(/^\d{6}$/) || confirming}
                className="px-3 py-1.5 rounded text-xs font-semibold bg-emerald-600 hover:bg-emerald-500 text-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
              >
                {confirming ? 'En cours...' : 'Confirmer le rollover'}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
