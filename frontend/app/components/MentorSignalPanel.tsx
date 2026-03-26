'use client';

import { useEffect, useMemo, useState } from 'react';
import { api, MentorSignalReview } from '@/app/lib/api';
import { AlertMessage } from '@/app/hooks/useWebSocket';
import { buildMentorAlertKey, isMentorEligibleAlert, TzEntry } from '@/app/lib/mentor';

export default function MentorSignalPanel({
  timezone,
  alerts,
  reviews,
}: {
  timezone: TzEntry;
  alerts: AlertMessage[];
  reviews: MentorSignalReview[];
}) {
  const eligibleAlerts = useMemo(() => alerts.filter(isMentorEligibleAlert), [alerts]);
  const [selectedAlertKey, setSelectedAlertKey] = useState<string | null>(null);
  const [loadingThreadKey, setLoadingThreadKey] = useState<string | null>(null);
  const [reanalyzingAlertKey, setReanalyzingAlertKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [threadsByAlertKey, setThreadsByAlertKey] = useState<Record<string, MentorSignalReview[]>>({});

  useEffect(() => {
    if (!selectedAlertKey && eligibleAlerts[0]) {
      setSelectedAlertKey(buildMentorAlertKey(eligibleAlerts[0]));
    }
  }, [eligibleAlerts, selectedAlertKey]);

  useEffect(() => {
    if (selectedAlertKey && !eligibleAlerts.some(alert => buildMentorAlertKey(alert) === selectedAlertKey)) {
      setSelectedAlertKey(eligibleAlerts[0] ? buildMentorAlertKey(eligibleAlerts[0]) : null);
    }
  }, [eligibleAlerts, selectedAlertKey]);

  useEffect(() => {
    if (reviews.length === 0) {
      return;
    }

    setThreadsByAlertKey(prev => {
      const next = { ...prev };
      for (const review of reviews) {
        next[review.alertKey] = mergeReviews(next[review.alertKey] ?? [], [review]);
      }
      return next;
    });
  }, [reviews]);

  const selectedAlert = eligibleAlerts.find(alert => buildMentorAlertKey(alert) === selectedAlertKey) ?? null;
  const selectedThread = selectedAlert
    ? threadsByAlertKey[buildMentorAlertKey(selectedAlert)] ?? reviewsForAlert(reviews, selectedAlert)
    : [];
  const latestSelectedReview = selectedThread.length > 0 ? selectedThread[selectedThread.length - 1] : null;

  const openAlert = async (alert: AlertMessage) => {
    const alertKey = buildMentorAlertKey(alert);
    setSelectedAlertKey(alertKey);
    setError(null);
    setLoadingThreadKey(alertKey);

    try {
      const thread = await api.getMentorAlertThread(toRequest(alert));
      setThreadsByAlertKey(prev => ({
        ...prev,
        [alertKey]: mergeReviews(prev[alertKey] ?? [], thread),
      }));
    } catch (nextError) {
      setError(nextError instanceof Error ? nextError.message : 'Unable to load saved mentor reviews.');
    } finally {
      setLoadingThreadKey(current => (current === alertKey ? null : current));
    }
  };

  const reanalyzeAlert = async () => {
    if (!selectedAlert) {
      return;
    }

    const alertKey = buildMentorAlertKey(selectedAlert);
    setError(null);
    setReanalyzingAlertKey(alertKey);

    try {
      const review = await api.reanalyzeMentorAlert(toRequest(selectedAlert));
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
            Chaque alerte qualifiee fige son snapshot, cree sa review initiale, puis conserve l’historique des relances Mentor.
          </div>
        </div>
        <div className="flex items-center gap-2 text-[10px]">
          <span className="rounded border border-zinc-800 bg-zinc-950/60 px-2 py-1 text-zinc-400">
            Eligible alerts: {eligibleAlerts.length}
          </span>
          <span className="rounded border border-zinc-800 bg-zinc-950/60 px-2 py-1 text-zinc-400">
            Saved reviews: {reviews.length}
          </span>
        </div>
      </div>

      {eligibleAlerts.length === 0 ? (
        <div className="rounded border border-dashed border-zinc-800 bg-zinc-950/40 px-3 py-4 text-[11px] text-zinc-500">
          Aucune alerte qualifiee disponible pour l’instant.
        </div>
      ) : (
        <div className="grid gap-3 xl:grid-cols-[0.95fr_1.45fr]">
          <div className="space-y-2">
            {eligibleAlerts.slice(0, 12).map(alert => {
              const alertKey = buildMentorAlertKey(alert);
              const thread = threadsByAlertKey[alertKey] ?? reviewsForAlert(reviews, alert);
              const latestReview = thread.length > 0 ? thread[thread.length - 1] : null;
              const selected = alertKey === selectedAlertKey;

              return (
                <button
                  key={alertKey}
                  onClick={() => void openAlert(alert)}
                  className={`w-full rounded border px-3 py-2 text-left transition-colors ${
                    selected
                      ? 'border-cyan-600 bg-cyan-950/20'
                      : 'border-zinc-800 bg-zinc-950/40 hover:border-zinc-700 hover:bg-zinc-900'
                  }`}
                >
                  <div className="mb-2 flex items-center gap-2">
                    <span className={`rounded px-2 py-1 text-[10px] font-semibold ${directionClass(alert)}`}>
                      {directionLabel(alert)}
                    </span>
                    <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-300">
                      {alert.instrument ?? 'GLOBAL'}
                    </span>
                    <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-500">
                      {alert.category}
                    </span>
                    <span className="ml-auto">
                      <StatusChip
                        status={latestReview?.status ?? 'MISSING'}
                        verdict={latestReview?.analysis?.analysis.verdict}
                      />
                    </span>
                  </div>
                  <div className="line-clamp-2 text-[11px] text-zinc-300">{alert.message}</div>
                  <div className="mt-2 flex items-center justify-between text-[10px] text-zinc-600">
                    <span>{new Date(alert.timestamp).toLocaleTimeString(undefined, { timeZone: timezone.tz })}</span>
                    <span>{thread.length} review{thread.length > 1 ? 's' : ''}</span>
                  </div>
                </button>
              );
            })}
          </div>

          <div className="rounded border border-zinc-800 bg-zinc-950/40 p-3">
            {!selectedAlert ? (
              <div className="text-[11px] text-zinc-500">Selectionne une alerte pour ouvrir sa review Mentor sauvegardee.</div>
            ) : (
              <>
                <div className="mb-3 flex flex-wrap items-center gap-2">
                  <span className={`rounded px-2 py-1 text-[10px] font-semibold ${directionClass(selectedAlert)}`}>
                    {directionLabel(selectedAlert)}
                  </span>
                  <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-300">
                    {selectedAlert.instrument ?? 'GLOBAL'}
                  </span>
                  <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-500">
                    {selectedAlert.category}
                  </span>
                  <StatusChip
                    status={latestSelectedReview?.status ?? 'MISSING'}
                    verdict={latestSelectedReview?.analysis?.analysis.verdict}
                  />
                </div>

                <div className="mb-3 text-[11px] text-zinc-300">{selectedAlert.message}</div>

                {error ? (
                  <div className="mb-3 rounded border border-red-900/40 bg-red-950/30 px-3 py-2 text-[11px] text-red-300">
                    {error}
                  </div>
                ) : null}

                {loadingThreadKey === buildMentorAlertKey(selectedAlert) ? (
                  <div className="mb-3 text-[11px] text-zinc-500">Chargement du fil Mentor sauvegarde...</div>
                ) : null}

                {selectedThread.length === 0 ? (
                  <div className="rounded border border-dashed border-zinc-800 px-3 py-4 text-[11px] text-zinc-500">
                    Aucune review sauvegardee pour cette alerte. Les nouvelles alertes generent automatiquement leur review initiale au moment du trigger.
                  </div>
                ) : (
                  <div className="space-y-3">
                    {selectedThread.map(review => (
                      <div key={review.id} className="rounded border border-zinc-800 bg-zinc-950/50 p-3">
                        <div className="mb-3 flex flex-wrap items-center gap-2">
                          <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-300">
                            Review #{review.revision}
                          </span>
                          <span className={`rounded px-2 py-1 text-[10px] ${review.triggerType === 'INITIAL' ? 'bg-cyan-950/60 text-cyan-300' : 'bg-amber-950/60 text-amber-300'}`}>
                            {review.triggerType === 'INITIAL' ? 'Initial Snapshot' : 'Manual Reanalysis'}
                          </span>
                          <StatusChip status={review.status} verdict={review.analysis?.analysis.verdict} />
                          <span className="ml-auto text-[10px] text-zinc-600">
                            {new Date(review.createdAt).toLocaleTimeString(undefined, { timeZone: timezone.tz })}
                          </span>
                        </div>

                        {review.status === 'ANALYZING' ? (
                          <div className="text-[11px] text-zinc-500">Analyse Mentor en cours sur le snapshot sauvegarde...</div>
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

                <div className="mt-3 flex justify-end">
                  <button
                    onClick={() => void reanalyzeAlert()}
                    disabled={!selectedAlert || selectedThread.length === 0 || reanalyzingAlertKey === buildMentorAlertKey(selectedAlert) || latestSelectedReview?.status === 'ANALYZING'}
                    className="rounded border border-cyan-800 bg-cyan-950/60 px-3 py-2 text-[11px] font-semibold text-cyan-300 transition-colors hover:border-cyan-600 hover:bg-cyan-900/70 disabled:cursor-not-allowed disabled:border-zinc-800 disabled:bg-zinc-900 disabled:text-zinc-500"
                  >
                    {reanalyzingAlertKey === buildMentorAlertKey(selectedAlert) ? "Relance Mentor..." : "Refaire l'analyse Mentor"}
                  </button>
                </div>
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

function directionLabel(alert: AlertMessage) {
  return inferDirection(alert) ?? 'REVIEW';
}

function directionClass(alert: AlertMessage) {
  const direction = inferDirection(alert);
  if (direction === 'LONG') {
    return 'bg-emerald-950/70 text-emerald-300';
  }
  if (direction === 'SHORT') {
    return 'bg-red-950/70 text-red-300';
  }
  return 'bg-zinc-800 text-zinc-300';
}

function inferDirection(alert: AlertMessage) {
  const normalized = `${alert.category} ${alert.message}`.toUpperCase();
  if (normalized.includes('BULLISH') || normalized.includes('OVERSOLD')) {
    return 'LONG';
  }
  if (normalized.includes('BEARISH') || normalized.includes('OVERBOUGHT')) {
    return 'SHORT';
  }
  return null;
}
