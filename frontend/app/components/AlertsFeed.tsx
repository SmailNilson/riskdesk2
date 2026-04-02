'use client';

import { useState, useRef, useEffect } from 'react';
import { AlertMessage } from '@/app/hooks/useWebSocket';
import { API_BASE } from '@/app/lib/runtimeConfig';

interface Props { alerts: AlertMessage[] }

const SEV_CHIP = {
  INFO:    'border-blue-700/60 bg-blue-950/60 text-blue-300',
  WARNING: 'border-amber-600/60 bg-amber-950/60 text-amber-300',
  DANGER:  'border-red-600/70 bg-red-950/70 text-red-300',
};

const SEV_DOT = {
  INFO:    'bg-blue-400',
  WARNING: 'bg-amber-400',
  DANGER:  'bg-red-400 animate-pulse',
};

const SEV_LABEL = {
  INFO: 'text-blue-500',
  WARNING: 'text-amber-500',
  DANGER: 'text-red-500',
};

const SNOOZE_OPTIONS = [
  { label: '5m',  seconds: 300 },
  { label: '10m', seconds: 600 },
  { label: '1H',  seconds: 3600 },
];

const API_URL = API_BASE ?? '';

async function callSnooze(key: string, durationSeconds: number) {
  await fetch(`${API_URL}/api/alerts/snooze`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ key, durationSeconds }),
  });
}

export default function AlertsFeed({ alerts }: Props) {
  const [snoozedKeys, setSnoozedKeys] = useState<Set<string>>(new Set());
  const [openKey, setOpenKey] = useState<string | null>(null);
  const menuRef = useRef<HTMLDivElement>(null);

  // Close dropdown when clicking outside
  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setOpenKey(null);
      }
    }
    document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, []);

  const handleSnooze = async (key: string, seconds: number) => {
    await callSnooze(key, seconds);
    setSnoozedKeys(prev => new Set(prev).add(key));
    setOpenKey(null);
  };

  const visibleAlerts = alerts.filter(a => !a.key || !snoozedKeys.has(a.key));

  return (
    /* Fixed bottom bar */
    <div className="fixed bottom-0 left-0 right-0 z-50 bg-zinc-950/95 backdrop-blur border-t border-zinc-800 flex items-center gap-0">
      {/* Label */}
      <div className="flex-shrink-0 flex items-center gap-2 px-3 border-r border-zinc-800 h-full self-stretch">
        <span className="text-[9px] font-bold uppercase tracking-widest text-zinc-500">Alerts</span>
        {visibleAlerts.length > 0 && (
          <span className="text-[9px] bg-zinc-800 text-zinc-400 rounded px-1">{visibleAlerts.length}</span>
        )}
      </div>

      {/* Scrollable alert chips */}
      <div className="flex-1 overflow-x-auto overflow-y-hidden">
        {visibleAlerts.length === 0 ? (
          <p className="text-[10px] text-zinc-600 px-4 py-2.5">Aucune alerte</p>
        ) : (
          <div className="flex items-center gap-2 px-3 py-2 w-max">
            {visibleAlerts.map((a, i) => {
              const chipKey = a.key ?? `${a.timestamp}:${a.message}`;
              const isOpen = openKey === chipKey;
              return (
                <div key={i} className="relative flex-shrink-0" ref={isOpen ? menuRef : undefined}>
                  <div
                    className={`flex items-center gap-1.5 border rounded px-2 py-1 text-[10px] ${SEV_CHIP[a.severity]}`}
                  >
                    <span className={`w-1.5 h-1.5 rounded-full flex-shrink-0 ${SEV_DOT[a.severity]}`} />
                    <span className={`font-semibold ${SEV_LABEL[a.severity]}`}>{a.category}</span>
                    {a.instrument && (
                      <span className="bg-zinc-700/60 text-zinc-400 text-[9px] px-1 rounded">{a.instrument}</span>
                    )}
                    <span className="text-zinc-300 max-w-[220px] truncate">{a.message}</span>
                    <span className="text-[9px] text-zinc-600 ml-1 flex-shrink-0">
                      {new Date(a.timestamp).toLocaleTimeString()}
                    </span>
                    {/* Snooze toggle — only when key is available */}
                    {a.key && (
                      <button
                        onClick={() => setOpenKey(isOpen ? null : chipKey)}
                        title="Snooze cet alert"
                        className="ml-1 flex-shrink-0 text-zinc-500 hover:text-zinc-300 transition-colors"
                      >
                        <svg width="10" height="10" viewBox="0 0 16 16" fill="currentColor">
                          <path d="M8 1a7 7 0 1 0 0 14A7 7 0 0 0 8 1zm0 1.5a5.5 5.5 0 1 1 0 11 5.5 5.5 0 0 1 0-11zM7.25 4v4.31l2.72 2.72.75-.75-2.47-2.47V4h-1z"/>
                        </svg>
                      </button>
                    )}
                  </div>

                  {/* Snooze dropdown */}
                  {isOpen && (
                    <div className="absolute bottom-full mb-1 right-0 bg-zinc-900 border border-zinc-700 rounded shadow-lg z-50 min-w-[90px]">
                      <div className="px-2 pt-1.5 pb-0.5 text-[9px] font-bold uppercase tracking-widest text-zinc-500">
                        Snooze
                      </div>
                      {SNOOZE_OPTIONS.map(opt => (
                        <button
                          key={opt.label}
                          onClick={() => handleSnooze(a.key!, opt.seconds)}
                          className="w-full text-left px-3 py-1.5 text-[11px] text-zinc-300 hover:bg-zinc-700 transition-colors"
                        >
                          {opt.label}
                        </button>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
