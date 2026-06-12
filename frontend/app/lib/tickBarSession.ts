/**
 * Tick-bar sequence/session helpers.
 *
 * The backend keeps tick bars in an in-memory ring buffer whose `seq` restarts
 * at 0 on every backend restart (deploy). Client-side merges are keyed by seq,
 * so bars from two different backend sessions must never be merged: the new
 * session's low seqs would overwrite old bars in place while the old session's
 * high-seq bars survive as frozen "ghosts" at the end of the timeline
 * (non-monotonic closeTime → broken time axis, stuck last bar).
 *
 * Within a single backend session, seq and closeTime are both monotonically
 * non-decreasing. Therefore "lower seq but newer closeTime" can only mean the
 * ring restarted, and "higher seq but strictly older closeTime" can only mean
 * the bars come from a previous session.
 */

export interface SeqStamped {
  seq: number;
  closeTime: number; // epoch seconds
}

/** Bar with the highest seq, or undefined when empty. */
export function newestBySeq<T extends SeqStamped>(bars: readonly T[]): T | undefined {
  let newest: T | undefined;
  for (const b of bars) {
    if (!newest || b.seq > newest.seq) newest = b;
  }
  return newest;
}

/**
 * True when `incoming` belongs to a newer backend session than `lastKnown`:
 * its max seq regressed below the last known seq while time moved forward.
 */
export function isRingRestart(
  lastKnown: SeqStamped | undefined,
  incoming: readonly SeqStamped[],
): boolean {
  if (!lastKnown) return false;
  const newest = newestBySeq(incoming);
  if (!newest) return false;
  return newest.seq < lastKnown.seq && newest.closeTime >= lastKnown.closeTime;
}

/**
 * True when `seed` (REST snapshot) is from an OLDER backend session than the
 * live bars: higher seq yet strictly older closeTime. A seed that is merely
 * ahead of stale live bars (e.g. after a WS gap) has both higher seq and newer
 * closeTime, and is kept.
 */
export function isStaleSeed(
  seed: readonly SeqStamped[],
  live: readonly SeqStamped[],
): boolean {
  const seedNewest = newestBySeq(seed);
  const liveNewest = newestBySeq(live);
  if (!seedNewest || !liveNewest) return false;
  return seedNewest.seq > liveNewest.seq && seedNewest.closeTime < liveNewest.closeTime;
}
