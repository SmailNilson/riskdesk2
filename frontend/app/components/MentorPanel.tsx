'use client';

import { ReactNode, useEffect, useRef, useState } from 'react';
import { AlertMessage, PriceUpdate } from '@/app/hooks/useWebSocket';
import {
  IndicatorSnapshot,
  MentorManualReview,
  MentorAnalyzeResponse,
  PortfolioSummary,
  api,
} from '@/app/lib/api';
import {
  Instrument,
  MentorTradeIntention,
  runMentorAnalysis,
  Timeframe,
  TradeAction,
  TzEntry,
} from '@/app/lib/mentor';

export default function MentorPanel({
  instrument,
  timeframe,
  timezone,
  connected,
  summary,
  snapshot,
  prices,
  alerts,
}: {
  instrument: Instrument;
  timeframe: Timeframe;
  timezone: TzEntry;
  connected: boolean;
  summary: PortfolioSummary | null;
  snapshot: IndicatorSnapshot | null;
  prices: Record<string, PriceUpdate>;
  alerts: AlertMessage[];
}) {
  const livePrice = prices[instrument]?.price ?? null;
  const matchingPosition = summary?.openPositions.find(position => position.instrument === instrument && position.open);

  const [action, setAction] = useState<TradeAction>('LONG');
  const [entryPrice, setEntryPrice] = useState('');
  const [stopLoss, setStopLoss] = useState('');
  const [takeProfit, setTakeProfit] = useState('');
  const [isMarketOrder, setIsMarketOrder] = useState(true);
  const [includePortfolioContext, setIncludePortfolioContext] = useState(false);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [result, setResult] = useState<MentorAnalyzeResponse | null>(null);
  const [manualReviews, setManualReviews] = useState<MentorManualReview[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [selectedManualAuditId, setSelectedManualAuditId] = useState<number | null>(null);
  const hydratedFormKeyRef = useRef('');

  useEffect(() => {
    let cancelled = false;
    setHistoryLoading(true);
    api.getRecentManualMentorReviews()
      .then(reviews => {
        if (cancelled) return;
        setManualReviews(reviews);
      })
      .catch(() => {
        if (cancelled) return;
        setManualReviews([]);
      })
      .finally(() => {
        if (!cancelled) {
          setHistoryLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    const formSeedKey = [
      instrument,
      matchingPosition?.id ?? 'none',
      matchingPosition?.side ?? 'none',
      matchingPosition?.entryPrice ?? 'none',
      matchingPosition?.stopLoss ?? 'none',
      matchingPosition?.takeProfit ?? 'none',
    ].join(':');

    if (hydratedFormKeyRef.current === formSeedKey) {
      return;
    }

    hydratedFormKeyRef.current = formSeedKey;
    setAction((matchingPosition?.side as TradeAction | undefined) ?? 'LONG');
    setEntryPrice(
      matchingPosition?.entryPrice != null
        ? String(round(matchingPosition.entryPrice, instrument))
        : livePrice != null
          ? String(round(livePrice, instrument))
          : ''
    );
    setStopLoss(matchingPosition?.stopLoss != null ? String(round(matchingPosition.stopLoss, instrument)) : '');
    setTakeProfit(matchingPosition?.takeProfit != null ? String(round(matchingPosition.takeProfit, instrument)) : '');
  }, [instrument, matchingPosition?.id, matchingPosition?.side, matchingPosition?.entryPrice, matchingPosition?.stopLoss, matchingPosition?.takeProfit, livePrice]);

  const analyze = async () => {
    if (!snapshot) {
      setError('Indicators not loaded yet.');
      return;
    }
    const parsedEntry = parseOptionalNumber(entryPrice);
    const parsedStop = parseOptionalNumber(stopLoss);
    const parsedTakeProfit = parseOptionalNumber(takeProfit);

    setLoading(true);
    setError(null);
    try {
      const tradeIntention: MentorTradeIntention = {
        action,
        entryPrice: parsedEntry,
        stopLoss: parsedStop,
        takeProfit: parsedTakeProfit,
        isMarketOrder,
      };

      const { response } = await runMentorAnalysis({
        instrument,
        timeframe,
        timezone,
        connected,
        summary,
        snapshot,
        prices,
        alerts,
        includePortfolioContext,
        tradeIntention,
      });
      setResult(response);
      setSelectedManualAuditId(response.auditId ?? null);
      const reviews = await api.getRecentManualMentorReviews().catch(() => null);
      if (reviews) {
        setManualReviews(reviews);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Mentor analysis failed.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="rounded-lg border border-zinc-800 bg-zinc-900/80 p-3">
      <div className="mb-3 flex items-center justify-between gap-3">
        <div>
          <div className="text-[11px] font-bold uppercase tracking-widest text-zinc-400">Mentor AI</div>
          <div className="text-[10px] text-zinc-600">Analyses manuelles lancees depuis Ask Mentor, separees des reviews d&apos;alertes.</div>
        </div>
        <button
          onClick={analyze}
          disabled={loading || !snapshot}
          className="rounded bg-cyan-700 px-3 py-1 text-[11px] font-semibold text-white transition-colors hover:bg-cyan-600 disabled:bg-zinc-700 disabled:text-zinc-500"
        >
          {loading ? 'Analyzing...' : 'Ask Mentor'}
        </button>
      </div>

      <div className="mb-3 grid grid-cols-6 gap-2 text-[11px]">
        <button
          onClick={() => setAction('LONG')}
          className={`rounded px-2 py-1 font-semibold ${action === 'LONG' ? 'bg-emerald-700 text-white' : 'bg-zinc-800 text-zinc-400'}`}
        >
          LONG
        </button>
        <button
          onClick={() => setAction('SHORT')}
          className={`rounded px-2 py-1 font-semibold ${action === 'SHORT' ? 'bg-red-700 text-white' : 'bg-zinc-800 text-zinc-400'}`}
        >
          SHORT
        </button>
        <Field label="Entry (opt)" value={entryPrice} onChange={setEntryPrice} />
        <Field label="SL (opt)" value={stopLoss} onChange={setStopLoss} />
        <Field label="TP (opt)" value={takeProfit} onChange={setTakeProfit} />
        <button
          onClick={() => setIsMarketOrder(v => !v)}
          className={`rounded px-2 py-1 font-semibold ${isMarketOrder ? 'bg-blue-700 text-white' : 'bg-zinc-800 text-zinc-400'}`}
        >
          {isMarketOrder ? 'Market' : 'Limit'}
        </button>
      </div>

      <div className="mb-3 flex items-center justify-between gap-3 rounded border border-zinc-800 bg-zinc-950/40 px-3 py-2 text-[10px]">
        <div className="text-zinc-400">
          {includePortfolioContext
            ? 'Le mentor voit le contexte portefeuille et les alertes de risque associees.'
            : 'Le mentor voit uniquement le contexte du trade et des indicateurs, sans etat portefeuille.'}
        </div>
        <button
          onClick={() => setIncludePortfolioContext(v => !v)}
          className={`rounded px-2 py-1 font-semibold ${
            includePortfolioContext ? 'bg-amber-700 text-white' : 'bg-emerald-700 text-white'
          }`}
        >
          {includePortfolioContext ? 'Portfolio ON' : 'Portfolio OFF'}
        </button>
      </div>

      <div className="mb-3 text-[10px] text-zinc-500">
        {hasManualPlan(entryPrice, stopLoss, takeProfit)
          ? 'Mode actuel: Trade Audit (plan renseigné)'
          : 'Mode actuel: Setup Review (le mentor doit proposer Entry / SL / TP si le setup est valide)'}
      </div>

      {error && (
        <div className="mb-3 rounded border border-red-900/50 bg-red-950/30 px-3 py-2 text-[10px] text-red-300">
          {error}
        </div>
      )}

      {result && (
        <div className="space-y-3 rounded border border-zinc-800 bg-zinc-950/50 p-3">
          <div className="flex items-center justify-between gap-2">
            <div className="text-[10px] text-zinc-500">Type: Manual Mentor Review · Model: {result.model}</div>
            <button
              onClick={() => { setResult(null); setSelectedManualAuditId(null); }}
              className="rounded border border-zinc-700 bg-zinc-900/60 px-2 py-1 text-[10px] text-zinc-400 hover:border-zinc-500 hover:text-zinc-200 transition-colors"
              title="Fermer cet audit"
            >
              Audit #{result.auditId ?? 'n/a'} ✕
            </button>
          </div>

          <Section title="Analyse Technique Rapide">
            <p className="text-[11px] text-zinc-200">{result.analysis.technicalQuickAnalysis}</p>
          </Section>

          <Section title="Points Forts">
            <BulletList items={result.analysis.strengths} emptyLabel="Aucun point fort explicite." color="text-emerald-300" />
          </Section>

          <Section title="Erreurs / Violations">
            <BulletList items={result.analysis.errors} emptyLabel="Aucune violation explicite." color="text-red-300" />
          </Section>

          <Section title="Verdict Final">
            <div className={`inline-flex rounded px-2 py-1 text-[11px] font-semibold ${
              result.analysis.verdict?.includes('Validé')
                ? 'bg-emerald-950/70 text-emerald-300'
                : 'bg-red-950/70 text-red-300'
            }`}>
              {result.analysis.verdict}
            </div>
          </Section>

          <Section title="Conseil d'Amélioration">
            <p className="text-[11px] text-zinc-200">{result.analysis.improvementTip}</p>
          </Section>

          <Section title="Plan Proposé">
            {result.analysis.proposedTradePlan ? (
              <div className="grid grid-cols-4 gap-2 text-[11px]">
                <PlanCell label="Optimal Entry" value={result.analysis.proposedTradePlan.entryPrice} />
                <PlanCell label="SL" value={result.analysis.proposedTradePlan.stopLoss} />
                <PlanCell label="TP" value={result.analysis.proposedTradePlan.takeProfit} />
                <PlanCell label="R:R" value={result.analysis.proposedTradePlan.rewardToRiskRatio} />
                <div className="col-span-4 rounded border border-zinc-800 bg-zinc-950/40 px-2 py-2 text-zinc-300">
                  {result.analysis.proposedTradePlan.rationale ?? 'Aucune justification fournie.'}
                </div>
                {result.analysis.proposedTradePlan.safeDeepEntry ? (
                  <div className="col-span-4 rounded border border-amber-900/50 bg-amber-950/20 px-2 py-2">
                    <div className="mb-2 text-[10px] font-bold uppercase tracking-widest text-amber-300">Safe Deep Entry</div>
                    <div className="grid grid-cols-2 gap-2">
                      <PlanCell label="Deep Entry" value={result.analysis.proposedTradePlan.safeDeepEntry.entryPrice} />
                      <div className="rounded border border-zinc-800 bg-zinc-950/40 px-2 py-2 text-zinc-300">
                        {result.analysis.proposedTradePlan.safeDeepEntry.rationale ?? 'Aucune justification fournie.'}
                      </div>
                    </div>
                  </div>
                ) : null}
              </div>
            ) : (
              <div className="text-[11px] text-zinc-500">Aucun plan proposé par le mentor pour ce setup.</div>
            )}
          </Section>

          <Section title="Mémoire Similaire">
            {result.similarAudits?.length ? (
              <div className="space-y-2">
                {result.similarAudits.map(match => (
                  <div key={match.auditId} className="rounded border border-zinc-800 bg-zinc-950/40 p-2 text-[10px] text-zinc-300">
                    <div className="mb-1 flex items-center justify-between gap-2">
                      <span className="font-semibold text-zinc-200">
                        Audit #{match.auditId} · {match.instrument} · {match.action} · {match.timeframe}
                      </span>
                      <span className="text-cyan-300">{Math.round(match.similarity * 100)}% similaire</span>
                    </div>
                    <div className="mb-1 text-zinc-500">{new Date(match.createdAt).toLocaleString()}</div>
                    <div className="mb-1 text-zinc-400">{match.summary}</div>
                    <div className={match.verdict?.includes('Validé') ? 'text-emerald-300' : 'text-red-300'}>
                      {match.verdict}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="text-[11px] text-zinc-500">Aucun audit similaire mémorisé pour l’instant.</div>
            )}
          </Section>

          <Section title="Payload Envoyé">
            <pre className="max-h-72 overflow-auto rounded border border-zinc-800 bg-zinc-950/40 p-2 text-[10px] text-zinc-400">
              {JSON.stringify(result.payload, null, 2)}
            </pre>
          </Section>
        </div>
      )}

      <div className="mt-3 rounded border border-zinc-800 bg-zinc-950/30 p-3">
        <div className="mb-3 flex items-center justify-between gap-2">
          <div>
            <div className="text-[10px] font-bold uppercase tracking-widest text-zinc-500">Manual Mentor Reviews</div>
            <div className="text-[10px] text-zinc-600">Historique des analyses lancees depuis ce panneau, sans les reviews issues des alertes.</div>
          </div>
          <div className="rounded border border-zinc-800 bg-zinc-950/50 px-2 py-1 text-[10px] text-zinc-500">
            {manualReviews.length} saved
          </div>
        </div>

        {historyLoading ? (
          <div className="text-[11px] text-zinc-500">Chargement des reviews manuelles...</div>
        ) : manualReviews.length === 0 ? (
          <div className="text-[11px] text-zinc-500">Aucune review manuelle sauvegardee pour l’instant.</div>
        ) : (
          <div className="grid gap-2 xl:grid-cols-[0.92fr_1.08fr]">
            <div className="space-y-2">
              {manualReviews.map(review => {
                const selected = review.auditId === selectedManualAuditId;
                return (
                  <button
                    key={review.auditId}
                    onClick={() => {
                      if (selected) {
                        setSelectedManualAuditId(null);
                        setResult(null);
                      } else {
                        setSelectedManualAuditId(review.auditId);
                        setResult(review.response);
                        setError(null);
                      }
                    }}
                    className={`w-full rounded border px-3 py-2 text-left transition-colors ${
                      selected
                        ? 'border-cyan-600 bg-cyan-950/20'
                        : 'border-zinc-800 bg-zinc-950/40 hover:border-zinc-700 hover:bg-zinc-900'
                    }`}
                  >
                    <div className="mb-2 flex items-center gap-2">
                      <span className="rounded bg-zinc-800 px-2 py-1 text-[10px] text-zinc-300">
                        #{review.auditId}
                      </span>
                      <span className="rounded bg-cyan-950/70 px-2 py-1 text-[10px] text-cyan-300">
                        MANUAL
                      </span>
                      <span className={`ml-auto rounded px-2 py-1 text-[10px] font-semibold ${
                        review.success && review.verdict?.includes('Validé')
                          ? 'bg-emerald-950/70 text-emerald-300'
                          : 'bg-red-950/70 text-red-300'
                      }`}>
                        {review.success && review.verdict?.includes('Validé') ? 'Trade OK' : 'Trade Non-Conforme'}
                      </span>
                    </div>
                    <div className="text-[11px] text-zinc-200">
                      {review.instrument ?? instrument} · {review.action ?? action} · {review.timeframe ?? timeframe}
                    </div>
                    <div className="mt-1 line-clamp-2 text-[10px] text-zinc-500">
                      {review.verdict ?? review.errorMessage ?? 'No verdict saved.'}
                    </div>
                    <div className="mt-2 text-[10px] text-zinc-600">
                      {new Date(review.createdAt).toLocaleString()}
                    </div>
                  </button>
                );
              })}
            </div>

            <div className="rounded border border-zinc-800 bg-zinc-950/40 px-3 py-3">
              {selectedManualAuditId == null ? (
                <div className="text-[11px] text-zinc-500">Selectionne une review manuelle pour la reouvrir ici.</div>
              ) : (
                <div className="text-[11px] text-zinc-400">
                  La review manuelle selectionnee est affichee dans le bloc principal au-dessus.
                </div>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

function Field({ label, value, onChange }: { label: string; value: string; onChange: (value: string) => void }) {
  return (
    <label className="flex flex-col gap-1">
      <span className="text-[9px] uppercase tracking-wider text-zinc-500">{label}</span>
      <input
        value={value}
        onChange={e => onChange(e.target.value)}
        className="rounded border border-zinc-800 bg-zinc-950 px-2 py-1 text-[11px] text-zinc-200 outline-none"
      />
    </label>
  );
}

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <div className="mb-1 text-[10px] font-bold uppercase tracking-widest text-zinc-500">{title}</div>
      {children}
    </div>
  );
}

function BulletList({ items, emptyLabel, color }: { items: string[]; emptyLabel: string; color: string }) {
  if (!items || items.length === 0) {
    return <div className="text-[11px] text-zinc-500">{emptyLabel}</div>;
  }
  return (
    <ul className={`space-y-1 text-[11px] ${color}`}>
      {items.map(item => (
        <li key={item}>• {item}</li>
      ))}
    </ul>
  );
}

function hasManualPlan(entryPrice: string, stopLoss: string, takeProfit: string) {
  return parseOptionalNumber(entryPrice) != null
    && parseOptionalNumber(stopLoss) != null
    && parseOptionalNumber(takeProfit) != null;
}

function parseOptionalNumber(value: string) {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed);
  return Number.isFinite(parsed) ? parsed : null;
}

function PlanCell({ label, value }: { label: string; value: number | null }) {
  return (
    <div className="rounded border border-zinc-800 bg-zinc-950/40 px-2 py-2">
      <div className="text-[9px] uppercase tracking-wider text-zinc-500">{label}</div>
      <div className="text-[11px] font-semibold text-zinc-200">{value ?? 'n/a'}</div>
    </div>
  );
}

function round(value: number, instrument: Instrument | 'MGC') {
  const digits = instrument === 'E6' ? 5 : 2;
  return Number(value.toFixed(digits));
}
