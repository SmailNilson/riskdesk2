'use client';

export interface OfflineBannerProps {
  connected: boolean;
  /** Override default message (e.g. for MARKET_CLOSED state). */
  message?: string;
  className?: string;
}

const DEFAULT_MESSAGE = 'Connexion temps réel perdue. Reconnexion…';

/**
 * Mobile-only banner shown when the WebSocket feed is disconnected.
 *
 * Renders nothing when `connected === true`. Otherwise displays an amber
 * status banner with an animated spinner and a polite live-region label so
 * screen readers announce the disconnection without interrupting the user.
 */
export default function OfflineBanner({
  connected,
  message,
  className,
}: OfflineBannerProps) {
  if (connected) return null;

  const label = message ?? DEFAULT_MESSAGE;
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
