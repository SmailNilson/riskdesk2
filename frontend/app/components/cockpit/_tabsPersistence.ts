'use client';

import { useCallback, useEffect, useState } from 'react';

/**
 * Internal helper for the cockpit tabs router family.
 *
 * Persists the active tab key in localStorage under
 *   riskdesk.layout.<prefix>.<id>
 * Falls back to `fallback` if no value is stored or the stored value
 * is no longer part of the current valid tab keys.
 *
 * NOTE: This module is intentionally prefixed with an underscore — it is
 * a private implementation detail of the cockpit tab routers and must not
 * be imported from outside `components/cockpit/`.
 */
export function usePersistedTab(
  prefix: string,
  id: string,
  fallback: string,
  validKeys: readonly string[],
): [string, (next: string) => void] {
  const storageKey = `riskdesk.layout.${prefix}.${id}`;

  const readInitial = useCallback((): string => {
    if (typeof window === 'undefined') {
      return fallback;
    }
    try {
      const stored = window.localStorage.getItem(storageKey);
      if (stored && validKeys.includes(stored)) {
        return stored;
      }
    } catch {
      // localStorage may throw in private mode / sandboxed contexts.
    }
    return fallback;
  }, [storageKey, fallback, validKeys]);

  const [active, setActiveState] = useState<string>(readInitial);

  // If the set of valid keys changes (e.g. tabs reconfigured at runtime),
  // make sure we don't keep pointing at a stale key.
  useEffect(() => {
    if (!validKeys.includes(active)) {
      setActiveState(fallback);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [validKeys.join('|'), fallback]);

  const setActive = useCallback(
    (next: string) => {
      setActiveState(next);
      if (typeof window === 'undefined') {
        return;
      }
      try {
        window.localStorage.setItem(storageKey, next);
      } catch {
        // Swallow storage errors — UI state still works in-memory.
      }
    },
    [storageKey],
  );

  return [active, setActive];
}
