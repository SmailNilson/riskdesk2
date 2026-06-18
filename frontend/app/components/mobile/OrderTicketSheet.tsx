'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { api } from '@/app/lib/api';
import type { ManualTradeRequest } from '@/app/components/quant/types';

/** Contract specs mirroring domain/model/Instrument.java (tick size, $ per tick). */
interface ContractSpec {
  tick: number;
  tickValue: number;
  decimals: number;
  /** Prefill distances ≈ $50 risk / $100 reward per contract. */
  defaultSlTicks: number;
  defaultTpTicks: number;
  /** Tick multiple for the SL/TP steppers — 1 tick per tap is too fine. */
  coarseTicks: number;
}

const SPECS: Record<string, ContractSpec> = {
  MCL: { tick: 0.01, tickValue: 1, decimals: 2, defaultSlTicks: 50, defaultTpTicks: 100, coarseTicks: 5 },
  MGC: { tick: 0.1, tickValue: 1, decimals: 1, defaultSlTicks: 50, defaultTpTicks: 100, coarseTicks: 5 },
  MNQ: { tick: 0.25, tickValue: 0.5, decimals: 2, defaultSlTicks: 100, defaultTpTicks: 200, coarseTicks: 10 },
  E6: { tick: 0.00005, tickValue: 6.25, decimals: 5, defaultSlTicks: 8, defaultTpTicks: 16, coarseTicks: 2 },
};

const HOLD_MS = 1000;
/** 0.5% of equity per 1R — conservative default for the mobile ticket. */
const RISK_PCT_PER_R = 0.005;

/**
 * USD risk for a 1-point (price unit) move on one contract.
 * Derived from the authoritative {@link ContractSpec} so MCL/MGC/MNQ/E6
 * stay aligned with domain values (tickValue / tick).
 *
 * Unknown instrument → 1, which disables the R-multiples slider.
 */
function dollarPerPoint(instrument: string): number {
  const s = SPECS[instrument];
  if (!s || s.tick <= 0) return 1;
  return s.tickValue / s.tick;
}

interface Props {
  open: boolean;
  instrument: string;
  /** Latest live price — prefills the limit price and anchors SL/TP. */
  lastPrice: number | null;
  /** Target IBKR account. Required — there is no server-side default fallback,
   *  so the submit button stays disabled until the Dashboard resolves one. */
  brokerAccountId?: string | null;
  /** Account equity (USD) used to size qty from risk-R. When null/undefined,
   *  the R-multiples slider is disabled and we fall back to manual qty. */
  accountEquity?: number | null;
  onClose: () => void;
  /** Called after a successful placement (the sheet closes itself). */
  onPlaced: () => void;
}

function parseNum(s: string): number | null {
  const n = parseFloat(s.replace(',', '.'));
  return Number.isFinite(n) ? n : null;
}

/**
 * Mobile manual order ticket — bottom sheet over any tab.
 *
 * Posts to /api/quant/manual-trade/{instrument}. SL and TP1 are REQUIRED by
 * the backend (plan geometry is validated server-side too), so the ticket
 * prefills both at sensible distances instead of making them optional.
 * Submission is hold-to-confirm (1s) to prevent fat-finger orders.
 *
 * Sizing: an R-multiples slider (0.25R … 3R, 0.25 steps) computes qty from
 * desired risk (RISK_PCT_PER_R × equity × R) divided by per-contract dollar
 * risk (|entry − SL| × $/point). The user can still override qty manually
 * via a collapsible "Saisie manuelle" detail — once they do, the slider
 * stops writing to qty until they touch the slider again.
 */
export default function OrderTicketSheet({ open, instrument, lastPrice, brokerAccountId, accountEquity, onClose, onPlaced }: Props) {
  const spec = SPECS[instrument] ?? SPECS.MCL;
  const fmt = useCallback((v: number) => v.toFixed(spec.decimals), [spec.decimals]);

  const [side, setSide] = useState<'LONG' | 'SHORT'>('LONG');
  const [entryType, setEntryType] = useState<'LIMIT' | 'MARKET'>('LIMIT');
  const [qty, setQty] = useState(1);
  const [priceStr, setPriceStr] = useState('');
  const [slStr, setSlStr] = useState('');
  const [tpStr, setTpStr] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [success, setSuccess] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [holdPct, setHoldPct] = useState(0);
  const [riskR, setRiskR] = useState(1);
  /** When true, the user took control of qty via the manual stepper — the
   *  R-slider effect stops syncing qty until the user touches the slider. */
  const [manualQtyOverride, setManualQtyOverride] = useState(false);

  const rafRef = useRef<number | null>(null);
  const firedRef = useRef(false);

  const roundTick = useCallback((v: number) => {
    return parseFloat((Math.round(v / spec.tick) * spec.tick).toFixed(spec.decimals));
  }, [spec.tick, spec.decimals]);

  const applyDefaults = useCallback((nextSide: 'LONG' | 'SHORT', anchor: number | null) => {
    if (anchor == null || anchor <= 0) return;
    const dir = nextSide === 'LONG' ? 1 : -1;
    setSlStr(fmt(roundTick(anchor - dir * spec.defaultSlTicks * spec.tick)));
    setTpStr(fmt(roundTick(anchor + dir * spec.defaultTpTicks * spec.tick)));
  }, [fmt, roundTick, spec.defaultSlTicks, spec.defaultTpTicks, spec.tick]);

  // Re-seed the form every time the sheet opens.
  useEffect(() => {
    if (!open) return;
    const anchor = lastPrice != null ? roundTick(lastPrice) : null;
    setSide('LONG');
    setEntryType('LIMIT');
    setQty(1);
    setPriceStr(anchor != null ? fmt(anchor) : '');
    applyDefaults('LONG', anchor);
    setSubmitting(false);
    setSuccess(null);
    setError(null);
    setHoldPct(0);
    setRiskR(1);
    setManualQtyOverride(false);
    firedRef.current = false;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, instrument]);

  const price = parseNum(priceStr);
  const sl = parseNum(slStr);
  const tp = parseNum(tpStr);
  const refPrice = entryType === 'LIMIT' ? price : (lastPrice != null ? roundTick(lastPrice) : null);

  // Risk-based sizing inputs. We use refPrice (limit price or last) as the
  // entry anchor so MARKET orders still get a size estimate from lastPrice.
  const dpp = dollarPerPoint(instrument);
  const slDistance = refPrice != null && sl != null ? Math.abs(refPrice - sl) : 0;
  const targetDistance = refPrice != null && tp != null ? Math.abs(tp - refPrice) : 0;
  const perContractRisk = slDistance * dpp;
  const targetRiskUsd = (accountEquity ?? 0) * RISK_PCT_PER_R * riskR;
  const sizingAvailable = accountEquity != null && accountEquity > 0 && dpp > 1 && perContractRisk > 0;
  const computedQty = sizingAvailable ? Math.max(1, Math.round(targetRiskUsd / perContractRisk)) : qty;

  // Sync qty ← computedQty when the R-slider, equity, or geometry changes,
  // unless the user has taken manual control of the quantity stepper.
  useEffect(() => {
    if (manualQtyOverride) return;
    if (!sizingAvailable) return;
    if (computedQty === qty) return;
    setQty(computedQty);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [riskR, priceStr, slStr, accountEquity, instrument, entryType]);

  const geometryOk = refPrice != null && sl != null && tp != null && qty >= 1 && (
    side === 'LONG' ? (sl < refPrice && tp > refPrice) : (sl > refPrice && tp < refPrice)
  );
  // A resolved IBKR account is required — the server has no default fallback
  // (riskdesk.quant.auto-arm.broker-account-id is empty), so a null account 409s.
  const valid = geometryOk && !!brokerAccountId && (entryType !== 'LIMIT' || (price != null && price > 0));

  const riskUsd = refPrice != null && sl != null
    ? Math.abs(refPrice - sl) / spec.tick * spec.tickValue * qty : null;
  const rewardUsd = refPrice != null && tp != null
    ? Math.abs(tp - refPrice) / spec.tick * spec.tickValue * qty : null;
  const rr = riskUsd && rewardUsd && riskUsd > 0 ? rewardUsd / riskUsd : null;

  // Compact totals shown next to the slider — always reflect the *current*
  // qty (computed or manual override) so the user sees the real PnL exposure.
  const absRiskUsd = sizingAvailable ? qty * perContractRisk : 0;
  const absTargetUsd = sizingAvailable ? qty * targetDistance * dpp : 0;
  const targetRatio = slDistance > 0 ? targetDistance / slDistance : 0;

  const step = useCallback((setter: (f: (s: string) => string) => void, ticks: number) => {
    setter(prev => {
      const cur = parseNum(prev);
      if (cur == null) return prev;
      return fmt(roundTick(cur + ticks * spec.tick));
    });
  }, [fmt, roundTick, spec.tick]);

  const changeSide = (next: 'LONG' | 'SHORT') => {
    setSide(next);
    applyDefaults(next, refPrice ?? (lastPrice != null ? roundTick(lastPrice) : null));
  };

  const handleManualQty = (next: number) => {
    setManualQtyOverride(true);
    setQty(next);
  };

  const handleRiskR = (next: number) => {
    setManualQtyOverride(false);
    setRiskR(next);
  };

  const submit = useCallback(async () => {
    if (!valid || submitting || sl == null || tp == null) return;
    setSubmitting(true);
    setError(null);
    const body: ManualTradeRequest = {
      direction: side,
      entryType,
      entryPrice: entryType === 'LIMIT' ? price : null,
      stopLoss: sl,
      takeProfit1: tp,
      takeProfit2: null,
      quantity: qty,
      brokerAccountId: brokerAccountId ?? null,
      // Without this a LIMIT row would rest as PENDING_ENTRY_SUBMISSION and
      // never reach IBKR — the mobile UI has no separate submit-entry step.
      submitImmediately: true,
    };
    try {
      await api.submitManualTrade(instrument, body);
      const verb = side === 'LONG' ? 'Achat' : 'Vente';
      const at = entryType === 'LIMIT' && price != null ? `@ ${fmt(price)}` : 'au marché';
      setSuccess(`${verb} ${qty} ${instrument} ${at} — ordre transmis`);
      setTimeout(() => { onPlaced(); onClose(); }, 1600);
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Échec de l’envoi de l’ordre');
      setSubmitting(false);
    }
  }, [valid, submitting, side, entryType, price, sl, tp, qty, instrument, brokerAccountId, fmt, onPlaced, onClose]);

  const cancelHold = useCallback(() => {
    if (rafRef.current != null) cancelAnimationFrame(rafRef.current);
    rafRef.current = null;
    setHoldPct(0);
  }, []);

  const startHold = useCallback(() => {
    if (!valid || submitting || firedRef.current) return;
    const start = performance.now();
    const tickFrame = (now: number) => {
      const pct = Math.min(1, (now - start) / HOLD_MS);
      setHoldPct(pct);
      if (pct >= 1) {
        if (!firedRef.current) {
          firedRef.current = true;
          void submit();
        }
        rafRef.current = null;
        return;
      }
      rafRef.current = requestAnimationFrame(tickFrame);
    };
    rafRef.current = requestAnimationFrame(tickFrame);
  }, [valid, submitting, submit]);

  useEffect(() => () => { if (rafRef.current != null) cancelAnimationFrame(rafRef.current); }, []);

  if (!open) return null;

  const long = side === 'LONG';
  const holdLabel = `Maintenir pour ${long ? 'acheter' : 'vendre'} ${qty} ${instrument} ${
    entryType === 'LIMIT' ? `@ ${priceStr || '—'}` : 'au marché'
  }`;

  const stepBtn = 'w-11 h-11 flex-shrink-0 rounded-lg border border-zinc-700 text-zinc-200 text-lg leading-none active:scale-95 transition-transform';
  const fieldCls = 'w-full bg-zinc-950 border border-zinc-800 rounded-lg px-2 py-2 text-center font-mono tabular-nums text-sm text-white outline-none focus:border-zinc-600';

  const sliderDisabled = !sizingAvailable;
  const riskHint = accountEquity == null
    ? 'Slider risque indisponible (compte non chargé).'
    : dpp <= 1
      ? 'Instrument non reconnu — slider risque désactivé.'
      : slDistance <= 0
        ? 'Renseignez le SL pour calculer la taille.'
        : null;

  return (
    <div className="fixed inset-0 z-50">
      <div className="absolute inset-0 bg-black/60" onClick={submitting ? undefined : onClose} />
      <div className="absolute bottom-0 inset-x-0 bg-zinc-900 border-t border-zinc-700 rounded-t-2xl px-4 pt-2.5 pb-[calc(1.25rem+env(safe-area-inset-bottom))]">
        <div className="w-9 h-1 rounded-full bg-zinc-700 mx-auto mb-3" />

        {success ? (
          <div className="flex flex-col items-center gap-2 py-8">
            <span className="w-12 h-12 rounded-full bg-emerald-950 border border-emerald-700 flex items-center justify-center">
              <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" className="text-emerald-400" aria-hidden="true"><path d="M20 6 9 17l-5-5" /></svg>
            </span>
            <span className="text-sm font-medium text-emerald-300 text-center">{success}</span>
            <span className="text-[11px] text-zinc-500">Suivi dans l&apos;onglet Portf</span>
          </div>
        ) : (
          <>
            <div className="flex items-center gap-2">
              <span className="text-sm font-semibold text-white">Ordre manuel</span>
              <span className="text-[11px] text-zinc-400 border border-zinc-800 rounded-md px-2 py-0.5">{instrument}</span>
              <span className="ml-auto font-mono tabular-nums text-sm text-zinc-200">
                {lastPrice != null ? fmt(lastPrice) : '—'}
              </span>
              <span className="text-[11px] text-zinc-500">dernier</span>
            </div>

            <div className="flex rounded-xl overflow-hidden border border-zinc-800 mt-3">
              <button
                onClick={() => changeSide('LONG')}
                className={`flex-1 py-3 min-h-[48px] text-sm font-semibold transition-colors ${
                  long ? 'bg-emerald-900/70 text-emerald-200' : 'text-zinc-500'
                }`}
              >Acheter</button>
              <button
                onClick={() => changeSide('SHORT')}
                className={`flex-1 py-3 min-h-[48px] text-sm font-semibold transition-colors ${
                  !long ? 'bg-rose-950 text-rose-300' : 'text-zinc-500'
                }`}
              >Vendre</button>
            </div>

            <div className="flex items-center gap-2 mt-3">
              <div className="flex rounded-lg overflow-hidden border border-zinc-800">
                <button
                  onClick={() => setEntryType('LIMIT')}
                  className={`px-3 py-2 text-xs font-medium ${entryType === 'LIMIT' ? 'bg-zinc-700 text-white' : 'text-zinc-500'}`}
                >Limite</button>
                <button
                  onClick={() => setEntryType('MARKET')}
                  className={`px-3 py-2 text-xs font-medium ${entryType === 'MARKET' ? 'bg-zinc-700 text-white' : 'text-zinc-500'}`}
                >Marché</button>
              </div>
              <span className="text-[11px] text-zinc-500 ml-auto font-mono tabular-nums">
                Qté <span className="text-white text-sm font-semibold">{qty}</span>
              </span>
            </div>

            {entryType === 'LIMIT' ? (
              <div className="flex items-center gap-2 mt-3">
                <span className="text-[11px] text-zinc-500 flex-shrink-0 w-16">Prix limite</span>
                <button className={stepBtn} onClick={() => step(setPriceStr, -1)} aria-label="Prix −1 tick">−</button>
                <input className={fieldCls} inputMode="decimal" value={priceStr} onChange={e => setPriceStr(e.target.value)} />
                <button className={stepBtn} onClick={() => step(setPriceStr, 1)} aria-label="Prix +1 tick">+</button>
              </div>
            ) : (
              <p className="text-[11px] text-zinc-500 mt-3">
                Exécution au prix live résolu côté serveur — rejet si aucun prix récent.
              </p>
            )}

            <div className="flex gap-2 mt-3">
              <div className="flex-1">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-[11px] text-zinc-500">Stop loss</span>
                  <span className="text-[11px] text-red-400 font-mono">requis</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <button className={stepBtn} onClick={() => step(setSlStr, -spec.coarseTicks)} aria-label="SL plus bas">−</button>
                  <input className={`${fieldCls} text-red-300`} inputMode="decimal" value={slStr} onChange={e => setSlStr(e.target.value)} />
                  <button className={stepBtn} onClick={() => step(setSlStr, spec.coarseTicks)} aria-label="SL plus haut">+</button>
                </div>
              </div>
              <div className="flex-1">
                <div className="flex items-center justify-between mb-1">
                  <span className="text-[11px] text-zinc-500">Take profit</span>
                  <span className="text-[11px] text-emerald-400 font-mono">requis</span>
                </div>
                <div className="flex items-center gap-1.5">
                  <button className={stepBtn} onClick={() => step(setTpStr, -spec.coarseTicks)} aria-label="TP plus bas">−</button>
                  <input className={`${fieldCls} text-emerald-300`} inputMode="decimal" value={tpStr} onChange={e => setTpStr(e.target.value)} />
                  <button className={stepBtn} onClick={() => step(setTpStr, spec.coarseTicks)} aria-label="TP plus haut">+</button>
                </div>
              </div>
            </div>

            {/* R-multiples sizing — between entry geometry and the manual qty fallback. */}
            <div className="mt-3">
              <div className="flex items-center justify-between mb-2">
                <span className="text-[10px] uppercase tracking-widest text-zinc-500">Taille en risque</span>
                <span className="font-mono tabular-nums text-sm text-white">{riskR.toFixed(2)}R</span>
              </div>
              <input
                type="range"
                min={0.25}
                max={3}
                step={0.25}
                value={riskR}
                onChange={e => handleRiskR(parseFloat(e.target.value))}
                disabled={sliderDisabled}
                className={`w-full accent-emerald-500 ${sliderDisabled ? 'opacity-40 cursor-not-allowed' : ''}`}
                aria-label="Taille en R-multiples"
              />
              <div className="flex items-center justify-between mt-2 text-[11px] font-mono tabular-nums">
                <span className="text-red-400">
                  Risque {sizingAvailable ? `−$${absRiskUsd.toFixed(2)}` : '—'}
                  {sizingAvailable && (
                    <span className="text-zinc-500"> ({(RISK_PCT_PER_R * riskR * 100).toFixed(2)}%)</span>
                  )}
                </span>
                {sizingAvailable && targetRatio > 0 ? (
                  <span className="text-emerald-400">
                    Cible +${absTargetUsd.toFixed(2)} <span className="text-zinc-500">({targetRatio.toFixed(1)}R)</span>
                  </span>
                ) : (
                  <span className="text-zinc-600">Cible —</span>
                )}
              </div>
              {riskHint && (
                <p className="text-[11px] text-zinc-500 italic mt-1">{riskHint}</p>
              )}
              {manualQtyOverride && sizingAvailable && (
                <p className="text-[11px] text-amber-400 mt-1">
                  Quantité manuelle active — le slider ne pilote plus la taille.
                </p>
              )}
            </div>

            {/* Manual qty fallback — collapsed by default, sets manualQtyOverride. */}
            <details className="mt-2 text-[11px]">
              <summary className="text-zinc-500 cursor-pointer min-h-[36px] flex items-center select-none">
                Saisie manuelle
              </summary>
              <div className="flex items-center gap-2 mt-2">
                <span className="text-[11px] text-zinc-500">Quantité</span>
                <button
                  className={stepBtn}
                  onClick={() => handleManualQty(Math.max(1, qty - 1))}
                  aria-label="Réduire la quantité"
                >−</button>
                <span className="font-mono tabular-nums text-base font-semibold text-white min-w-[24px] text-center">{qty}</span>
                <button
                  className={stepBtn}
                  onClick={() => handleManualQty(Math.min(10, qty + 1))}
                  aria-label="Augmenter la quantité"
                >+</button>
                {manualQtyOverride && (
                  <button
                    className="ml-auto text-[11px] text-emerald-400 underline-offset-2 hover:underline"
                    onClick={() => setManualQtyOverride(false)}
                  >Repasser au slider</button>
                )}
              </div>
            </details>

            <div className="flex flex-wrap gap-x-4 gap-y-1 bg-zinc-950 rounded-lg px-3 py-2 mt-3">
              <span className="text-[11px] text-zinc-500">
                Risque au stop <span className="font-mono tabular-nums text-red-400">{riskUsd != null ? `−$${riskUsd.toFixed(0)}` : '—'}</span>
              </span>
              <span className="text-[11px] text-zinc-500">
                Objectif <span className="font-mono tabular-nums text-emerald-400">{rewardUsd != null ? `+$${rewardUsd.toFixed(0)}` : '—'}</span>
              </span>
              <span className="text-[11px] text-zinc-500">
                R:R <span className="font-mono tabular-nums text-zinc-300">{rr != null ? rr.toFixed(2) : '—'}</span>
              </span>
              <span className="text-[11px] text-zinc-500">
                <span className="font-mono tabular-nums text-zinc-300">${spec.tickValue.toFixed(2)}</span> / tick
              </span>
            </div>

            {!geometryOk && refPrice != null && sl != null && tp != null && (
              <p className="text-[11px] text-amber-400 mt-2">
                Géométrie invalide : pour {long ? 'un achat' : 'une vente'}, le SL doit être {long ? 'sous' : 'au-dessus de'} l&apos;entrée et le TP {long ? 'au-dessus' : 'en dessous'}.
              </p>
            )}
            {error && (
              <p className="text-[11px] text-red-400 bg-red-950/50 border border-red-900 rounded-lg px-3 py-2 mt-2 break-words">{error}</p>
            )}
            {!brokerAccountId && (
              <p className="text-[11px] text-amber-400 bg-amber-950/40 border border-amber-900 rounded-lg px-3 py-2 mt-2">
                Aucun compte IBKR résolu — vérifiez la connexion IBKR avant de trader.
              </p>
            )}

            <button
              onPointerDown={startHold}
              onPointerUp={cancelHold}
              onPointerLeave={cancelHold}
              onPointerCancel={cancelHold}
              onContextMenu={e => e.preventDefault()}
              disabled={!valid || submitting}
              className={`relative w-full overflow-hidden rounded-xl mt-4 min-h-[52px] text-sm font-semibold transition-opacity select-none touch-none ${
                long ? 'bg-emerald-950 border border-emerald-800 text-emerald-100' : 'bg-rose-950 border border-rose-800 text-rose-100'
              } ${!valid || submitting ? 'opacity-40' : ''}`}
            >
              <span
                className={`absolute inset-y-0 left-0 ${long ? 'bg-emerald-600/50' : 'bg-rose-600/50'}`}
                style={{ width: `${holdPct * 100}%` }}
              />
              <span className="relative">{submitting ? 'Envoi…' : holdLabel}</span>
            </button>
            <p className="text-[11px] text-zinc-600 text-center mt-2">
              Maintenir 1 s — l&apos;ordre part immédiatement sur IBKR (SL/TP gérés côté app)
            </p>
          </>
        )}
      </div>
    </div>
  );
}
