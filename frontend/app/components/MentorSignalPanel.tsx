'use client';

import { useEffect, useMemo, useState } from 'react';
import { api, MentorSignalReview } from '@/app/lib/api';
import { AlertMessage } from '@/app/hooks/useWebSocket';
import { buildMentorAlertKey, isMentorEligibleAlert, TzEntry } from '@/app/lib/mentor';

type AlertGroup = {
  groupKey: string;
  direction: string;
  instrument: string;
  timeframe: string;
  categories: string[];
  alerts: AlertMessage[];
  timestamp: string;
};

export default function MentorSignalPanel({
  timezone,
  alerts,
  reviews,
}: {
  timezone: TzEntry;
  alerts: AlertMessage[];
  reviews: MentorSignalReview[];
}) {
  const liveEligibleAlerts = useMemo(() => alerts.filter(isMentorEligibleAlert), [alerts]);

  // Reconstruct alerts from saved reviews that have no matching live alert
  const allAlerts = useMemo(() => {
    const liveKeys = new Set(liveEligibleAlerts.map(buildMentorAlertKey));
    const seenKeys = new Set<string>(liveKeys);
    const fromReviews: AlertMessage[] = [];

    for (const review of reviews) {
      if (!seenKeys.has(review.alertKey) && review.instrument) {
        seenKeys.add(review.alertKey);
        fromReviews.push({
          severity: review.severity,
          category: review.category,
          message: review.message,
          instrument: review.instrument,
          timestamp: review.timestamp,
        });
      }
    }

    const combined = [...liveEligibleAlerts, ...fromReviews];
    const reviewKeys = new Set(reviews.map(r => r.alertKey));
    combined.sort((a, b) => {
      const aHasReview = reviewKeys.has(buildMentorAlertKey(a)) ? 1 : 0;
      const bHasReview = reviewKeys.has(buildMentorAlertKey(b)) ? 1 : 0;
      if (aHasReview !== bHasReview) return bHasReview - aHasReview;
      return new Date(b.timestamp).getTime() - new Date(a.timestamp).getTime();
    });
    return combined;
  }, [liveEligibleAlerts, reviews]);

  // Group alerts by instrument + timeframe + direction, but only if timestamps are within 90s
  const GROUP_WINDOW_MS = 90_000;
  const groups = useMemo(() => {
    const result: AlertGroup[] = [];
    for (const alert of allAlerts) {
      const direction = inferDirection(alert) ?? 'REVIEW';
      const tf = parseTimeframe(alert.message);
      const instrument = alert.instrument ?? 'GLOBAL';
      const alertTime = new Date(alert.timestamp).getTime();

      // Find an existing group with same instrument+tf+direction AND within time window
      const match = result.find(g =>
        g.instrument === instrument &&
        g.timeframe === tf &&
        g.direction === direction &&
        Math.abs(alertTime - new Date(g.timestamp).getTime()) <= GROUP_WINDOW_MS
      );

      if (match) {
        match.alerts.push(alert);
        if (!match.categories.includes(alert.category)) {
          match.categories.push(alert.category);
        }
      } else {
        const groupKey = `${instrument}:${tf}:${direction}:${alert.timestamp}`;
        result.push({
          groupKey,
          direction,
          instrument,
          timeframe: tf,
          categories: [alert.category],
          alerts: [alert],
          timestamp: alert.timestamp,
        });
      }
    }
    return result;
  }, [allAlerts]);

  const [filterInstrument, setFilterInstrument] = useState<string>('ALL');
  const availableInstruments = useMemo(() => {
    const set = new Set(groups.map(g => g.instrument));
    return Array.from(set).sort();
  }, [groups]);

  const filteredGroups = useMemo(() => {
    if (filterInstrument === 'ALL') return groups;
    return groups.filter(g => g.instrument === filterInstrument);
  }, [groups, filterInstrument]);

  const [selectedGroupKey, setSelectedGroupKey] = useState<string | null>(null);
  const [loadingGroupKey, setLoadingGroupKey] = useState<string | null>(null);
  const [reanalyzingAlertKey, setReanalyzingAlertKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [threadsByAlertKey, setThreadsByAlertKey] = useState<Record<string, MentorSignalReview[]>>({});

  useEffect(() => {
    if (!selectedGroupKey && filteredGroups[0]) {
      setSelectedGroupKey(filteredGroups[0].groupKey);
    }
  }, [filteredGroups, selectedGroupKey]);

  useEffect(() => {
    if (selectedGroupKey && !filteredGroups.some(g => g.groupKey === selectedGroupKey)) {
      setSelectedGroupKey(filteredGroups[0]?.groupKey ?? null);
    }
  }, [filteredGroups, selectedGroupKey]);

  useEffect(() => {
    if (reviews.length === 0) return;
    setThreadsByAlertKey(prev => {
      const next = { ...prev };
      for (const review of reviews) {
        next[review.alertKey] = mergeReviews(next[review.alertKey] ?? [], [review]);
      }
      return next;
    });
  }, [reviews]);

  const selectedGroup = groups.find(g => g.groupKey === selectedGroupKey) ?? null;

  // Collect all reviews for all alerts in the selected group
  const selectedGroupThreads = useMemo(() => {
    if (!selectedGroup) return [];
    const allThreads: MentorSignalReview[] = [];
    for (const alert of selectedGroup.alerts) {
      const alertKey = buildMentorAlertKey(alert);
      const thread = threadsByAlertKey[alertKey] ?? reviewsForAlert(reviews, alert);
      allThreads.push(...thread);
    }
    return sortReviews(allThreads);
  }, [selectedGroup, threadsByAlertKey, reviews]);

  const latestGroupReview = selectedGroupThreads.length > 0 ? selectedGroupThreads[selectedGroupThreads.length - 1] : null;

  // Best verdict across all alerts in a group
  const groupVerdict = (group: AlertGroup) => {
    let bestReview: MentorSignalReview | null = null;
    for (const alert of group.alerts) {
      const alertKey = buildMentorAlertKey(alert);
      const thread = threadsByAlertKey[alertKey] ?? reviewsForAlert(reviews, alert);
      const latest = thread.length > 0 ? thread[thread.length - 1] : null;
      if (latest && (!bestReview || latest.status === 'DONE')) {
        bestReview = latest;
      }
    }
    return bestReview;
  };

  const groupReviewCount = (group: AlertGroup) => {
    let count = 0;
    for (const alert of group.alerts) {
      const alertKey = buildMentorAlertKey(alert);
      const thread = threadsByAlertKey[alertKey] ?? reviewsForAlert(reviews, alert);
      count += thread.length;
    }
    return count;
  };

  const openGroup = async (group: AlertGroup) => {
    setSelectedGroupKey(group.groupKey);
    setError(null);
    setLoadingGroupKey(group.groupKey);

    try {
      for (const alert of group.alerts) {
        const alertKey = buildMentorAlertKey(alert);
        const thread = await api.getMentorAlertThread(toRequest(alert));
        setThreadsByAlertKey(prev => ({
          ...prev,
          [alertKey]: mergeReviews(prev[alertKey] ?? [], thread),
        }));
      }
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Unable to load saved mentor reviews.');
    } finally {
      setLoadingGroupKey(current => (current === group.groupKey ? null : current));
    }
  };

  const [autoAnalysis, setAutoAnalysis] = useState<boolean>(false);
  const [togglingAuto, setTogglingAuto] = useState(false);

  useEffect(() => {
    api.getAutoAnalysisStatus().then(r => setAutoAnalysis(r.enabled)).catch(() => {});
  }, []);

  const toggleAutoAnalysis = async () => {
    setTogglingAuto(true);
    try {
      const r = await api.toggleAutoAnalysis();
      setAutoAnalysis(r.enabled);
    } finally {
      setTogglingAuto(false);
    }
  };

  const reanalyzeAlert = async (alert: AlertMessage) => {
    const alertKey = buildMentorAlertKey(alert);
    setError(null);
    setReanalyzingAlertKey(alertKey);

    try {
      const review = await api.reanalyzeMentorAlert(toRequest(alert));
      setThreadsByAlertKey(prev => ({
        ...prev,
        [alertKey]: mergeReviews(prev[alertKey] ?? [], [review]),
      }));
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Mentor reanalysis failed.');
    } finally {
      setReanalyzingAlertKey(current => (current === alertKey ? null : current));
    }
  };

  return (
    <div className="rounded-lg border border-cyan-900/40 bg-zinc-900/80 p-3">
      <div className="mb-3 flex items-center justify-between gap-3">
        <div>
          <div className="text-[11px] font-bold uppercase tracking-widest text-cyan-300">Mentor Alert Review</div>
          <div className="text-[10px] text-zinc-500">
            Alertes groupees quand elles arrivent en meme temps (~90s) sur le meme instrument/timeframe/direction.
          </div>
        </div>
        <div className="flex items-center gap-2 text-[10px]">
          <button
            onClick={() => void toggleAutoAnalysis()}
            disabled={togglingAuto}
            title={autoAnalysis ? 'Auto-analyse activée — cliquer pour désactiver' : 'Auto-analyse désactivée — cliquer pour activer'}
            className={`flex items-center gap-1.5 rounded border px-2 py-1 font-semibold transition-colors disabled:cursor-not-allowed ${
              autoAnalysis
                ? 'border-emerald-700 bg-emerald-950/60 text-emerald-300 hover:border-emerald-500'
                : 'border-zinc-700 bg-zinc-900/60 text-zinc-500 hover:border-zinc-500 hover:text-zinc-400'
            }`}
          >
            <span className={`inline-block h-1.5 w-1.5 rounded-full ${autoAnalysis ? 'bg-emerald-400' : 'bg-zinc-600'}`} />
            AUTO {autoAnalysis ? 'ON' : 'OFF'}
          </button>
          <select
            value={filterInstrument}
            onChange={e => setFilterInstrument(e.target.value)}
            className="rounded border border-zinc-800 bg-zinc-950/60 px-2 py-1 text-zinc-400 outline-none cursor-pointer hover:border-zinc-600 transition-colors"
          >
            <option value="ALL">Tous</option>
            {availableInstruments.map(inst => (
              <option key={inst} value={inst}>{inst}</option>
            ))}
          </select>
          <span className="rounded border border-zinc-800 bg-zinc-950/60 px-2 py-1 text-zinc-400">
            {filteredGroups.length} groupe{filteredGroups.length > 1 ? 's' : ''}
          </span>
          <span className="rounded border border-zinc-800 bg-zinc-950/60 px-2 py-1 text-zinc-400">
            {reviews.length} review{reviews.length > 1 ? 's' : ''}
          </span>
        </div>
      </div>

      {filteredGroups.length === 0 ? (
        <div className="rounded border border-dashed border-zinc-800 bg-zinc-950/40 px-3 py-4 text-[11px] text-zinc-500">
          Aucune alerte qualifiee disponible pour l'instant.
        </div>
      ) : (
        <div className="grid gap-3 xl:grid-cols-[0.95fr_1.45fr]">
          <div className="max-h-[500px] space-y-2 overflow-y-auto pr-1">
            {filteredGroups.slice(0, 30).map(group => {
              const bestReview = groupVerdict(group);
              const reviewCount = groupReviewCount(group);
              const selected = group.groupKey === selectedGroupKey;

              return (
                <button
                  key={group.groupKey}
                  onClick={() => void openGroup(group)}
                  className={`w-full rounded border px-3 py-2 text-left transition-colors ${
                    selected
                      ? 'border-cyan-600 bg-cyan-950/20'
                      : 'border-zinc-800 bg-zinc-950/40 hover:border-zinc-700 hover:bg-zinc-900'
                  }`}
                >
                  <div className="mb-2 flex items-center gap-2">
                    <span className={`rounded px-2 py-1 text-[10px] font-semibold ${directionClassStr(group.direction)}`}>
                      {group.direction}
                    </span>
                    <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-300">
                      {group.instrument}
                    </span>
                    <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-500">
                      {group.timeframe}
                    </span>
                    <span className="ml-auto">
                      <StatusChip
                        status={bestReview?.status ?? 'MISSING'}
                        verdict={bestReview?.analysis?.analysis.verdict}
                      />
                    </span>
                  </div>
                  <div className="mb-1.5 flex flex-wrap gap-1">
                    {group.categories.map(cat => (
                      <span key={cat} className="rounded bg-zinc-800/80 px-1.5 py-0.5 text-[9px] text-zinc-400">
                        {cat}
                      </span>
                    ))}
                  </div>
                  <div className="flex items-center justify-between text-[10px] text-zinc-600">
                    <span>{new Date(group.timestamp).toLocaleTimeString(undefined, { timeZone: timezone.tz })}</span>
                    <span>{reviewCount} review{reviewCount > 1 ? 's' : ''} · {group.alerts.length} signal{group.alerts.length > 1 ? 's' : ''}</span>
                  </div>
                </button>
              );
            })}
          </div>

          <div className="max-h-[500px] overflow-y-auto rounded border border-zinc-800 bg-zinc-950/40 p-3">
            {!selectedGroup ? (
              <div className="text-[11px] text-zinc-500">Selectionne un groupe pour ouvrir les reviews Mentor.</div>
            ) : (
              <>
                <div className="mb-3 flex flex-wrap items-center gap-2">
                  <span className={`rounded px-2 py-1 text-[10px] font-semibold ${directionClassStr(selectedGroup.direction)}`}>
                    {selectedGroup.direction}
                  </span>
                  <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-300">
                    {selectedGroup.instrument}
                  </span>
                  <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-500">
                    {selectedGroup.timeframe}
                  </span>
                  {selectedGroup.categories.map(cat => (
                    <span key={cat} className="rounded bg-zinc-800/80 px-1.5 py-0.5 text-[9px] text-zinc-400">
                      {cat}
                    </span>
                  ))}
                  <StatusChip
                    status={latestGroupReview?.status ?? 'MISSING'}
                    verdict={latestGroupReview?.analysis?.analysis.verdict}
                  />
                </div>

                {error ? (
                  <div className="mb-3 rounded border border-red-900/40 bg-red-950/30 px-3 py-2 text-[11px] text-red-300">
                    {error}
                  </div>
                ) : null}

                {loadingGroupKey === selectedGroup.groupKey ? (
                  <div className="mb-3 text-[11px] text-zinc-500">Chargement des reviews...</div>
                ) : null}

                {selectedGroupThreads.length === 0 ? (
                  <div className="rounded border border-dashed border-zinc-800 px-3 py-4 text-[11px] text-zinc-500">
                    Aucune review sauvegardee pour ce groupe.
                  </div>
                ) : (
                  <div className="space-y-3">
                    {selectedGroupThreads.map(review => (
                      <div key={review.id} className="rounded border border-zinc-800 bg-zinc-950/50 p-3">
                        <div className="mb-3 flex flex-wrap items-center gap-2">
                          <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-300">
                            {review.category}
                          </span>
                          <span className={`rounded px-2 py-1 text-[10px] ${review.triggerType === 'INITIAL' ? 'bg-cyan-950/60 text-cyan-300' : 'bg-amber-950/60 text-amber-300'}`}>
                            {review.triggerType === 'INITIAL' ? 'Initial' : 'Reanalysis'}
                          </span>
                          <StatusChip status={review.status} verdict={review.analysis?.analysis.verdict} />
                          <span className="ml-auto text-[10px] text-zinc-600">
                            {new Date(review.createdAt).toLocaleTimeString(undefined, { timeZone: timezone.tz })}
                          </span>
                        </div>

                        <div className="mb-2 text-[10px] text-zinc-500">{review.message}</div>

                        {review.status === 'ANALYZING' ? (
                          <div className="text-[11px] text-zinc-500">Analyse Mentor en cours...</div>
                        ) : null}

                        {review.status === 'ERROR' ? (
                          <div className="rounded border border-red-900/40 bg-red-950/30 px-3 py-2 text-[11px] text-red-300">
                            {review.errorMessage ?? 'Mentor analysis failed.'}
                          </div>
                        ) : null}

                        {review.status === 'DONE' && review.analysis ? (
                          <div className="grid gap-3 xl:grid-cols-[1.4fr_0.8fr]">
                            <div className="space-y-2">
                              <Section label="Verdict" value={review.analysis.analysis.verdict} />
                              <Section label="Analyse" value={review.analysis.analysis.technicalQuickAnalysis} />
                              <Section label="Conseil" value={review.analysis.analysis.improvementTip} />
                            </div>

                            <div className="space-y-2">
                              <PlanRow label="Optimal Entry" value={review.analysis.analysis.proposedTradePlan?.entryPrice} />
                              <PlanRow label="SL" value={review.analysis.analysis.proposedTradePlan?.stopLoss} />
                              <PlanRow label="TP" value={review.analysis.analysis.proposedTradePlan?.takeProfit} />
                              <PlanRow label="R:R" value={review.analysis.analysis.proposedTradePlan?.rewardToRiskRatio} />
                              {review.analysis.analysis.proposedTradePlan?.safeDeepEntry ? (
                                <div className="rounded border border-amber-900/40 bg-amber-950/20 px-3 py-2 text-[11px]">
                                  <div className="mb-1 text-[10px] font-bold uppercase tracking-widest text-amber-300">Safe Deep Entry</div>
                                  <div className="text-amber-200">
                                    {review.analysis.analysis.proposedTradePlan.safeDeepEntry.entryPrice ?? 'n/a'}
                                  </div>
                                  <div className="mt-1 text-amber-100/80">
                                    {review.analysis.analysis.proposedTradePlan.safeDeepEntry.rationale ?? 'Aucune justification fournie.'}
                                  </div>
                                </div>
                              ) : null}
                            </div>
                          </div>
                        ) : null}
                      </div>
                    ))}
                  </div>
                )}

                {selectedGroup.alerts.length > 0 ? (
                  <div className="mt-3 flex flex-wrap justify-end gap-2">
                    {selectedGroup.alerts
                      .filter((alert, idx, arr) => arr.findIndex(a => a.category === alert.category) === idx)
                      .map(alert => {
                        const alertKey = buildMentorAlertKey(alert);
                        const thread = threadsByAlertKey[alertKey] ?? reviewsForAlert(reviews, alert);
                        const canReanalyze = thread.length > 0 && thread[thread.length - 1]?.status !== 'ANALYZING';
                        return (
                          <button
                            key={alert.category}
                            onClick={() => void reanalyzeAlert(alert)}
                            disabled={!canReanalyze || reanalyzingAlertKey === alertKey}
                            className="rounded border border-cyan-800 bg-cyan-950/60 px-2 py-1.5 text-[10px] font-semibold text-cyan-300 transition-colors hover:border-cyan-600 hover:bg-cyan-900/70 disabled:cursor-not-allowed disabled:border-zinc-800 disabled:bg-zinc-900 disabled:text-zinc-500"
                          >
                            {reanalyzingAlertKey === alertKey ? 'Relance...' : `Reanalyse ${alert.category}`}
                          </button>
                        );
                      })}
                  </div>
                ) : null}
              </>
            )}
          </div>
        </div>
      )}
    </div>
  );
}

function Section({ label, value }: { label: string; value: string | null | undefined }) {
  return (
    <div>
      <div className="mb-1 text-[10px] font-bold uppercase tracking-widest text-zinc-500">{label}</div>
      <div className="text-[11px] text-zinc-300">{value ?? 'n/a'}</div>
    </div>
  );
}

function StatusChip({ status, verdict }: { status: MentorSignalReview['status'] | 'MISSING'; verdict?: string }) {
  if (status === 'DONE') {
    const valid = verdict?.includes('Valid');
    return (
      <span className={`rounded px-2 py-1 text-[10px] font-semibold ${valid ? 'bg-emerald-950/70 text-emerald-300' : 'bg-red-950/70 text-red-300'}`}>
        {valid ? 'Trade OK' : 'Trade Non-Conforme'}
      </span>
    );
  }

  if (status === 'ANALYZING') {
    return <span className="rounded bg-cyan-950/70 px-2 py-1 text-[10px] font-semibold text-cyan-300">Analyzing</span>;
  }

  if (status === 'MISSING') {
    return <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] font-semibold text-zinc-400">No Review</span>;
  }

  return <span className="rounded bg-red-950/70 px-2 py-1 text-[10px] font-semibold text-red-300">Error</span>;
}

function PlanRow({ label, value }: { label: string; value: number | null | undefined }) {
  return (
    <div className="rounded border border-zinc-800 bg-zinc-950/40 px-3 py-2">
      <div className="text-[9px] uppercase tracking-wider text-zinc-500">{label}</div>
      <div className="text-[11px] font-semibold text-zinc-200">{value ?? 'n/a'}</div>
    </div>
  );
}

function toRequest(alert: AlertMessage) {
  return {
    severity: alert.severity,
    category: alert.category,
    message: alert.message,
    instrument: alert.instrument,
    timestamp: alert.timestamp,
  } as const;
}

function reviewsForAlert(reviews: MentorSignalReview[], alert: AlertMessage) {
  return sortReviews(reviews.filter(review => review.alertKey === buildMentorAlertKey(alert)));
}

function mergeReviews(existing: MentorSignalReview[], incoming: MentorSignalReview[]) {
  const merged = new Map<number, MentorSignalReview>();
  for (const review of existing) {
    merged.set(review.id, review);
  }
  for (const review of incoming) {
    merged.set(review.id, review);
  }
  return sortReviews(Array.from(merged.values()));
}

function sortReviews(reviews: MentorSignalReview[]) {
  return [...reviews].sort((left, right) => {
    if (left.revision !== right.revision) {
      return left.revision - right.revision;
    }
    return new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime();
  });
}

function parseTimeframe(message: string): string {
  const match = message.match(/\[(5m|10m|1h|1d)\]/i);
  return match ? match[1].toLowerCase() : '10m';
}

function directionClassStr(direction: string) {
  if (direction === 'LONG') return 'bg-emerald-950/70 text-emerald-300';
  if (direction === 'SHORT') return 'bg-red-950/70 text-red-300';
  return 'bg-zinc-800 text-zinc-300';
}

function inferDirection(alert: AlertMessage) {
  const normalized = `${alert.category} ${alert.message}`.toUpperCase();
  if (normalized.includes('BULLISH') || normalized.includes('OVERSOLD')) return 'LONG';
  if (normalized.includes('BEARISH') || normalized.includes('OVERBOUGHT')) return 'SHORT';
  return null;
}
