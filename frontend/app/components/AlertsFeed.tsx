'use client';

import { AlertMessage } from '@/app/hooks/useWebSocket';

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

export default function AlertsFeed({ alerts }: Props) {
  return (
    <div className="fixed bottom-0 left-0 right-0 z-50 bg-zinc-950/95 backdrop-blur border-t border-zinc-800 flex items-center gap-0">
      {/* Label */}
      <div className="flex-shrink-0 flex items-center gap-2 px-3 border-r border-zinc-800 h-full self-stretch">
        <span className="text-[9px] font-bold uppercase tracking-widest text-zinc-500">Alerts</span>
        {alerts.length > 0 && (
          <span className="text-[9px] bg-zinc-800 text-zinc-400 rounded px-1">{alerts.length}</span>
        )}
      </div>

      {/* Scrollable alert chips */}
      <div className="flex-1 overflow-x-auto overflow-y-hidden">
        {alerts.length === 0 ? (
          <p className="text-[10px] text-zinc-600 px-4 py-2.5">Aucune alerte</p>
        ) : (
          <div className="flex items-center gap-2 px-3 py-2 w-max">
            {alerts.map((a, i) => (
              <div
                key={i}
                className={`flex-shrink-0 flex items-center gap-1.5 border rounded px-2 py-1 text-[10px] ${SEV_CHIP[a.severity]}`}
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
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
