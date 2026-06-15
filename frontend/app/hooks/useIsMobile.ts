'use client';

import { useEffect, useState } from 'react';

/** Mirrors Tailwind's `lg` breakpoint — below it the dashboard uses the mobile layout. */
const MOBILE_QUERY = '(max-width: 1023px)';

/**
 * SSR-safe mobile detection. Returns `null` on the server and during the first
 * client render, so callers can defer mounting either panel tree until the
 * viewport is known — a phone never mounts the heavy desktop panels (and their
 * WebSocket/polling side effects), not even for one frame.
 */
export function useIsMobile(): boolean | null {
  const [isMobile, setIsMobile] = useState<boolean | null>(null);

  useEffect(() => {
    const mql = window.matchMedia(MOBILE_QUERY);
    const update = () => setIsMobile(mql.matches);
    update();
    // `resize` fallback: some embedded/emulated viewports resize without
    // dispatching matchMedia change events.
    mql.addEventListener('change', update);
    window.addEventListener('resize', update);
    return () => {
      mql.removeEventListener('change', update);
      window.removeEventListener('resize', update);
    };
  }, []);

  return isMobile;
}
