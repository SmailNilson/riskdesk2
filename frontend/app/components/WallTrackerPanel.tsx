'use client';

import { memo, useEffect, useState } from 'react';
import { api, ActiveWall, WallEpisodeHistory } from '@/app/lib/api';

interface WallTrackerPanelProps {
  instrument?: string;
}

const POLL_MS = 5_000;

/** Outcome badge: what happened to the wall when it left the book. */
function OutcomeBadge({ outcome }: { outcome: WallEpisodeHistory['outcome'] }) {
  switch (outcome) {
    case 'CONSUMED':
      return (
        <span className="text-[9px] px-1 py-0.5 rounded bg-emerald-900/50 text-emerald-300 font-semibold"
              title="Le prix a atteint le mur avant sa disparition — ordre réel, absorbé/exécuté">
          CONSUMED
        </span>
      );
    case 'PULLED':
      return (
        <span className="text-[9px] px-1 py-0.5 rounded bg-amber-900/50 text-amber-300 font-semibold"
              title="Annulé alors que le prix était encore loin — suspect de spoofing">
          PULLED ⚠
        </span>
      );
    case 'FADED':
      return (
        <span className="text-[9px] px-1 py-0.5 rounded bg-zinc-800 text-zinc-400"
              title="Taille encore présente, simplement passée sous le seuil relatif (5× moyenne)">
          FADED
        </span>
      );
    default:
      return (
        <span className="text-[9px] px-1 py-0.5 rounded bg-zinc-800 text-zinc-500 italic"
              title="Le niveau est sorti des 10 niveaux visibles — issue inconnue">
          OUT OF RANGE
        </span>
      );
  }
}

function formatAge(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)}s`;
  if (seconds < 3600) return `${Math.floor(seconds / 60)}m${Math.round(seconds % 60).toString().padStart(2, '0')}`;
  return `${Math.floor(seconds / 3600)}h${Math.floor((seconds % 3600) / 60)}m`;
}

function formatTime(iso: string): string {
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? '--:--:--'
    : d.toLocaleTimeString('fr-FR', { hour12: false });
}

/**
 * Wall traceability (UC-OF-012): live walls currently resting in the book and
 * the recent closed episodes with their outcome — so large orders can be traced
 * (where they appeared, how big, how long) and spoof-like pulls stand out.
 * Data: REST poll of /api/order-flow/walls/{instrument} every 5s.
 */
function WallTrackerPanel({ instrument }: WallTrackerPanelProps) {
  const [active, setActive] = useState<ActiveWall[]>([]);
  const [recent, setRecent] = useState<WallEpisodeHistory[]>([]);

  useEffect(() => {
    setActive([]);
    setRecent([]);
    if (!instrument) return;
    let cancelled = false;
    const fetchWalls = () => {
      api.getWallTracker(instrument).then(snapshot => {
        if (cancelled || snapshot.instrument !== instrument) return;
        setActive(snapshot.active ?? []);
        setRecent(snapshot.recent ?? []);
      }).catch(() => { /* keep last known state on transient errors */ });
    };
    fetchWalls();
    const timer = setInterval(fetchWalls, POLL_MS);
    return () => { cancelled = true; clearInterval(timer); };
  }, [instrument]);

  if (!instrument) {
    return <p className="text-xs text-zinc-600 italic">Select an instrument to trace walls</p>;
  }

  return (
    <div className="flex flex-col gap-2">
      {/* Live walls */}
      <div>
        <div className="text-[9px] uppercase tracking-wider text-zinc-600 mb-1">Live</div>
        {active.length > 0 ? (
          <div className="flex flex-col gap-0.5">
            {active.map((w, i) => (
              <div key={`${w.side}-${w.price}-${i}`}
                   className="flex items-center gap-2 text-[10px] px-1.5 py-1 rounded bg-zinc-900 ring-1 ring-inset ring-yellow-400/40">
                <span className={`font-semibold w-7 ${w.side === 'BID' ? 'text-emerald-400' : 'text-red-400'}`}>
                  {w.side}
                </span>
                <span className="text-zinc-200 font-semibold">{w.price.toFixed(2)}</span>
                <span className="text-zinc-400">
                  {w.size.toLocaleString()}
                  {w.maxSize > w.size && <span className="text-zinc-600"> (max {w.maxSize.toLocaleString()})</span>}
                </span>
                <span className="text-zinc-500 ml-auto">{formatAge(w.ageSeconds)}</span>
                {w.distanceTicks != null && (
                  <span className="text-zinc-500" title="Distance au meilleur prix du même côté">
                    {w.distanceTicks.toFixed(0)} ticks
                  </span>
                )}
              </div>
            ))}
          </div>
        ) : (
          <p className="text-[10px] text-zinc-600 italic">No wall in the book right now</p>
        )}
      </div>

      {/* Closed episodes */}
      <div>
        <div className="text-[9px] uppercase tracking-wider text-zinc-600 mb-1">History</div>
        {recent.length > 0 ? (
          <div className="flex flex-col gap-0.5 max-h-44 overflow-y-auto scrollbar-thin scrollbar-thumb-zinc-700">
            {recent.map((ep, i) => (
              <div key={`${ep.timestamp}-${ep.side}-${ep.price}-${i}`}
                   className="flex items-center gap-2 text-[10px] px-1.5 py-1 rounded bg-zinc-900/60">
                <span className="text-zinc-600 tabular-nums">{formatTime(ep.timestamp)}</span>
                <span className={`font-semibold w-7 ${ep.side === 'BID' ? 'text-emerald-400' : 'text-red-400'}`}>
                  {ep.side}
                </span>
                <span className="text-zinc-300">{ep.price.toFixed(2)}</span>
                <span className="text-zinc-500" title="Taille max atteinte">
                  max {ep.maxSize.toLocaleString()}
                </span>
                <span className="text-zinc-600" title="Durée de vie en tant que mur">
                  {formatAge(ep.durationSeconds)}
                </span>
                <span className="ml-auto"><OutcomeBadge outcome={ep.outcome} /></span>
              </div>
            ))}
          </div>
        ) : (
          <p className="text-[10px] text-zinc-600 italic">No closed wall episode yet</p>
        )}
      </div>
    </div>
  );
}

export default memo(WallTrackerPanel);
