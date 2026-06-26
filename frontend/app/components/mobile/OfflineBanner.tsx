'use client';

export interface OfflineBannerProps {
  connected: boolean;
  /** Socket is up but the price feed has frozen (no `/topic/prices` frames). */
  stale?: boolean;
  /** Age of the last price frame in ms, shown in the stale message when available. */
  staleAgeMs?: number | null;
  /** Override default message (e.g. for MARKET_CLOSED state). */
  message?: string;
  className?: string;
}

const DEFAULT_MESSAGE = 'Connexion temps réel perdue. Reconnexion…';

/**
 * Status banner shown when the realtime feed is unavailable — either the WebSocket is disconnected
 * (`connected === false`) or the socket is up but frames have stopped flowing (`stale === true`,
 * the "données figées" case the backend reconnect watchdog is recovering from).
 *
 * Renders nothing when connected and fresh. Otherwise displays an amber status banner with an
 * animated spinner and a polite live-region label so screen readers announce it without interrupting.
 */
export default function OfflineBanner({
  connected,
  stale = false,
  staleAgeMs,
  message,
  className,
}: OfflineBannerProps) {
  if (connected && !stale) return null;

  let label: string;
  if (message) {
    label = message;
  } else if (!connected) {
    label = DEFAULT_MESSAGE;
  } else {
    const secs = staleAgeMs != null ? Math.round(staleAgeMs / 1000) : null;
    label = secs != null
      ? `Données figées depuis ${secs}s — reconnexion…`
      : 'Données figées — reconnexion…';
  }
  const baseClasses =
    'bg-amber-950/80 text-amber-300 border-b border-amber-900 px-4 py-1.5 flex items-center gap-2 text-[11px] font-medium';
  const classes = className ? `${baseClasses} ${className}` : baseClasses;

  return (
    <div role="status" aria-live="polite" className={classes}>
      <svg
        className="animate-spin w-3 h-3 text-amber-400"
        viewBox="0 0 24 24"
        aria-hidden="true"
      >
        <circle
          cx="12"
          cy="12"
          r="10"
          stroke="currentColor"
          strokeWidth="3"
          fill="none"
          strokeDasharray="40 60"
          strokeLinecap="round"
        />
      </svg>
      <span>{label}</span>
    </div>
  );
}
