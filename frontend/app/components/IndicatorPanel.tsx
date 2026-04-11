'use client';

import { IndicatorSnapshot, OrderBlockView } from '@/app/lib/api';
import { breakerOriginalType, relevantBreakerBlocks } from '@/app/lib/orderBlocks';

interface Props {
  snapshot: IndicatorSnapshot | null;
  currentPrice: number | null;
  children?: React.ReactNode;
}

function Row({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="flex justify-between items-baseline py-0.5">
      <span className="text-zinc-500 text-xs">{label}</span>
      <span className="text-zinc-200 text-xs font-mono">
        {value}
        {sub && <span className="ml-1 text-[10px] text-zinc-500">{sub}</span>}
      </span>
    </div>
  );
}

function Badge({ label, color }: { label: string; color: 'green' | 'red' | 'amber' | 'blue' | 'gray' }) {
  const cls = {
    green: 'bg-emerald-900/50 text-emerald-300',
    red: 'bg-red-900/50 text-red-300',
    amber: 'bg-amber-900/50 text-amber-300',
    blue: 'bg-blue-900/50 text-blue-300',
    gray: 'bg-zinc-700 text-zinc-400',
  }[color];
  return <span className={`text-[10px] px-1.5 py-0.5 rounded font-medium ${cls}`}>{label}</span>;
}

function signalColor(signal: string | null): 'green' | 'red' | 'amber' | 'gray' {
  if (!signal) return 'gray';
  if (signal.includes('BULL') || signal.includes('GOLDEN') || signal.includes('OVERSOLD')) return 'green';
  if (signal.includes('BEAR') || signal.includes('DEATH') || signal.includes('OVERBOUGHT')) return 'red';
  if (signal.includes('WARN') || signal.includes('WEAK')) return 'amber';
  return 'gray';
}

export default function IndicatorPanel({ snapshot: s, currentPrice, children }: Props) {
  if (!s) return (
    <div className="bg-zinc-900 rounded-lg border border-zinc-800 p-4 text-zinc-500 text-sm">
      Loading indicators…
    </div>
  );

  // Price decimals per instrument — E6 needs 5 to match IBKR tick size
  const priceDecimals = s.instrument === 'E6' ? 5 : 2;
  const n = (v: number | null, d?: number) => v != null ? v.toFixed(d ?? priceDecimals) : '—';
  const price = currentPrice ?? 0;
  const proximityFilter = (eq: { price: number }) => price > 0 ? Math.abs(eq.price - price) / price < 0.05 : true;
  const equalHighs = (s.equalHighs ?? []).filter(proximityFilter);
  const equalLows = (s.equalLows ?? []).filter(proximityFilter);
  const activeOrderBlocks = [...(s.activeOrderBlocks ?? [])].sort((a, b) => b.mid - a.mid);
  const chaikinFlow = s.cmf == null ? { label: '—', color: 'gray' as const }
    : s.cmf > 0 ? { label: 'BUYING', color: 'green' as const }
    : s.cmf < 0 ? { label: 'SELLING', color: 'red' as const }
    : { label: 'NEUTRAL', color: 'gray' as const };
  const visibleBreakers = relevantBreakerBlocks(s, currentPrice);
  const hiddenBreakerCount = Math.max((s.breakerOrderBlocks ?? []).length - visibleBreakers.length, 0);

  return (
    <div className="grid grid-cols-2 gap-3">
      {/* RSI */}
      <Section title="RSI (14)">
        <Row label="Value" value={n(s.rsi)} />
        <div className="flex justify-between items-center py-0.5">
          <span className="text-zinc-500 text-xs">Signal</span>
          <Badge label={s.rsiSignal ?? '—'} color={signalColor(s.rsiSignal)} />
        </div>
        {s.rsi != null && (
          <div className="mt-1.5 w-full bg-zinc-800 rounded-full h-1.5">
            <div
              className="h-1.5 rounded-full bg-gradient-to-r from-red-500 via-amber-400 to-emerald-400"
              style={{ width: `${Math.min(s.rsi, 100)}%` }}
            />
          </div>
        )}
      </Section>

      {/* MACD */}
      <Section title="MACD (12/26/9)">
        <Row label="MACD" value={n(s.macdLine, 4)} />
        <Row label="Signal" value={n(s.macdSignal, 4)} />
        <Row label="Hist" value={n(s.macdHistogram, 4)} />
        {s.macdCrossover && (
          <div className="flex justify-end mt-0.5">
            <Badge label={s.macdCrossover} color={signalColor(s.macdCrossover)} />
          </div>
        )}
      </Section>

      {/* Bollinger Bands */}
      <Section title="Bollinger Bands (20)">
        <Row label="Upper" value={n(s.bbUpper)} />
        <Row label="Middle" value={n(s.bbMiddle)} />
        <Row label="Lower" value={n(s.bbLower)} />
        <Row label="%B" value={n(s.bbPct, 3)} />
        <Row label="Width" value={n(s.bbWidth)} />
        {s.bbTrendSignal && (
          <div className="flex justify-between items-center py-0.5">
            <span className="text-zinc-500 text-xs">BBTrend</span>
            <Badge label={s.bbTrendSignal}
              color={s.bbTrendExpanding ? 'amber' : 'blue'} />
          </div>
        )}
      </Section>

      {/* Chaikin / CMF */}
      <Section title="Chaikin / CMF">
        <Row label="Osc" value={n(s.chaikinOscillator, 1)} />
        <Row label="CMF (20)" value={n(s.cmf, 4)} />
        <div className="flex justify-between items-center py-0.5">
          <span className="text-zinc-500 text-xs">Flow</span>
          <Badge label={chaikinFlow.label} color={chaikinFlow.color} />
        </div>
      </Section>

      {/* Delta Flow */}
      <Section title="Delta Flow (20)">
        <Row label="Delta" value={n(s.deltaFlow, 1)} />
        <Row label="Cumul." value={n(s.cumulativeDelta, 1)} />
        <Row label="Buy Ratio" value={s.buyRatio != null ? `${(s.buyRatio * 100).toFixed(1)}%` : '—'} />
        {s.deltaFlowBias && (
          <div className="flex justify-end mt-0.5">
            <Badge label={s.deltaFlowBias}
              color={s.deltaFlowBias === 'BUYING' ? 'green' : s.deltaFlowBias === 'SELLING' ? 'red' : 'gray'} />
          </div>
        )}
      </Section>

      {/* WaveTrend */}
      <Section title="WT_X WaveTrend (10/21/4)">
        <Row label="WT1" value={n(s.wtWt1, 2)} />
        <Row label="WT2" value={n(s.wtWt2, 2)} />
        <Row label="Diff" value={n(s.wtDiff, 2)} />
        <div className="flex justify-between items-center py-0.5">
          <span className="text-zinc-500 text-xs">Signal</span>
          <Badge
            label={s.wtSignal ?? '—'}
            color={s.wtSignal === 'OVERBOUGHT' ? 'red' : s.wtSignal === 'OVERSOLD' ? 'green' : 'gray'}
          />
        </div>
        {s.wtCrossover && (
          <div className="flex justify-end mt-0.5">
            <Badge label={s.wtCrossover} color={signalColor(s.wtCrossover)} />
          </div>
        )}
        {s.wtWt1 != null && (
          <div className="mt-1.5 w-full bg-zinc-800 rounded-full h-1.5">
            <div
              className="h-1.5 rounded-full bg-gradient-to-r from-emerald-400 via-zinc-400 to-red-500"
              style={{ width: `${Math.min(Math.max((s.wtWt1 + 100) / 2, 0), 100)}%` }}
            />
          </div>
        )}
      </Section>

      {/* SMC Internal */}
      <Section title="SMC Internal">
        <div className="flex justify-between items-center py-0.5">
          <span className="text-zinc-500 text-xs">Bias</span>
          <Badge label={s.internalBias ?? '—'}
            color={s.internalBias === 'BULLISH' ? 'green' : s.internalBias === 'BEARISH' ? 'red' : 'gray'} />
        </div>
        <Row label="High" value={n(s.internalHigh)} sub="weak" />
        <Row label="Low" value={n(s.internalLow)} sub="weak" />
        {s.lastInternalBreakType && (
          <div className="flex justify-between items-center py-0.5">
            <span className="text-zinc-500 text-xs">Last</span>
            <Badge label={s.lastInternalBreakType} color={signalColor(s.lastInternalBreakType)} />
          </div>
        )}
      </Section>

      {/* SMC Swing */}
      <Section title="SMC Swing">
        <div className="flex justify-between items-center py-0.5">
          <span className="text-zinc-500 text-xs">Bias</span>
          <Badge label={s.swingBias ?? '—'}
            color={s.swingBias === 'BULLISH' ? 'green' : s.swingBias === 'BEARISH' ? 'red' : 'gray'} />
        </div>
        <Row label="High" value={n(s.swingHigh)} sub="strong" />
        <Row label="Low" value={n(s.swingLow)} sub="strong" />
        {s.lastSwingBreakType && (
          <div className="flex justify-between items-center py-0.5">
            <span className="text-zinc-500 text-xs">Last</span>
            <Badge label={s.lastSwingBreakType} color={signalColor(s.lastSwingBreakType)} />
          </div>
        )}
      </Section>

      {/* Premium / Discount / Equilibrium */}
      {s.equilibriumLevel != null && (
        <Section title="Premium / Discount">
          <Row label="Premium Top" value={n(s.premiumZoneTop)} />
          <Row label="Equilibrium" value={n(s.equilibriumLevel)} />
          <Row label="Discount Bot" value={n(s.discountZoneBottom)} />
          <div className="flex justify-between items-center py-0.5">
            <span className="text-zinc-500 text-xs">Zone</span>
            <Badge
              label={s.currentZone ?? '—'}
              color={s.currentZone === 'PREMIUM' ? 'red' : s.currentZone === 'DISCOUNT' ? 'green' : 'blue'}
            />
          </div>
          <div className="mt-1.5 w-full bg-zinc-800 rounded-full h-1.5 relative overflow-hidden">
            <div className="absolute inset-y-0 left-0 w-1/2 bg-emerald-900/60 rounded-l-full" />
            <div className="absolute inset-y-0 right-0 w-1/2 bg-red-900/60 rounded-r-full" />
          </div>
        </Section>
      )}

      {/* FVG — Fair Value Gaps with quality score */}
      {(s.activeFairValueGaps ?? []).length > 0 && (
        <Section title={`FVG (${(s.activeFairValueGaps ?? []).length})`}>
          {[...(s.activeFairValueGaps ?? [])].sort((a, b) => b.top - a.top).map((fvg, i) => (
            <div key={`fvg-${i}`} className="flex items-center gap-2 text-xs font-mono py-0.5">
              <Badge label={fvg.bias} color={fvg.bias === 'BULLISH' ? 'green' : 'red'} />
              <span className="text-zinc-400 tabular-nums">{fvg.bottom.toFixed(priceDecimals)}–{fvg.top.toFixed(priceDecimals)}</span>
              {fvg.fvgQualityScore != null && (
                <div className="flex items-center gap-1 min-w-[50px]">
                  <div className="flex-1 h-1.5 bg-zinc-800 rounded-full overflow-hidden">
                    <div className={`h-full rounded-full ${barColor(fvg.fvgQualityScore)}`} style={{ width: `${Math.min(fvg.fvgQualityScore, 100)}%` }} />
                  </div>
                  <span className={`text-[9px] font-bold tabular-nums ${textColor(fvg.fvgQualityScore)}`}>{Math.round(fvg.fvgQualityScore)}</span>
                </div>
              )}
            </div>
          ))}
        </Section>
      )}

      {/* BOS/CHoCH — Structure Breaks with confidence score */}
      {(s.recentBreaks ?? []).length > 0 && (
        <Section title={`Breaks (${(s.recentBreaks ?? []).length})`}>
          {(s.recentBreaks ?? []).map((brk, i) => (
            <div key={`brk-${i}`} className="flex items-center gap-2 text-xs font-mono py-0.5">
              <Badge label={`${brk.type}`} color={brk.trend === 'BULLISH' ? 'green' : 'red'} />
              <span className="text-zinc-400 tabular-nums">{brk.level.toFixed(priceDecimals)}</span>
              <span className="text-[10px] text-zinc-600">{brk.structureLevel?.toLowerCase()}</span>
              {brk.breakConfidenceScore != null ? (
                <div className="flex items-center gap-1 min-w-[50px]">
                  <div className="flex-1 h-1.5 bg-zinc-800 rounded-full overflow-hidden">
                    <div className={`h-full rounded-full ${barColor(brk.breakConfidenceScore)}`} style={{ width: `${Math.min(brk.breakConfidenceScore, 100)}%` }} />
                  </div>
                  <span className={`text-[9px] font-bold tabular-nums ${textColor(brk.breakConfidenceScore)}`}>{Math.round(brk.breakConfidenceScore)}</span>
                  {brk.confirmed && <span className="text-[9px] text-emerald-400 font-bold">OK</span>}
                  {brk.confirmed === false && <span className="text-[9px] text-red-400 font-bold">FAKE?</span>}
                </div>
              ) : (
                <span className="text-zinc-600 text-[10px]">—</span>
              )}
            </div>
          ))}
        </Section>
      )}

      {/* EQH / EQL — Liquidity with depth confirmation */}
      {(equalHighs.length > 0 || equalLows.length > 0) && (
        <Section title={`EQH/EQL (${equalHighs.length + equalLows.length})`}>
          {equalHighs.map((eq, i) => (
            <div key={`eqh-${i}`} className="flex items-center gap-2 text-xs font-mono py-0.5">
              <Badge label="EQH" color="red" />
              <span className="text-zinc-400 tabular-nums">{eq.price.toFixed(priceDecimals)}</span>
              <span className="text-zinc-500">x{eq.touchCount}</span>
              {eq.liquidityConfirmScore != null && (
                <div className="flex items-center gap-1 min-w-[40px]">
                  <div className="flex-1 h-1.5 bg-zinc-800 rounded-full overflow-hidden">
                    <div className={`h-full rounded-full ${barColor(eq.liquidityConfirmScore)}`} style={{ width: `${Math.min(eq.liquidityConfirmScore, 100)}%` }} />
                  </div>
                  <span className={`text-[9px] font-bold tabular-nums ${textColor(eq.liquidityConfirmScore)}`}>{Math.round(eq.liquidityConfirmScore)}</span>
                </div>
              )}
              {eq.ordersVisible && <span className="text-[9px] text-emerald-400 font-bold">L2</span>}
            </div>
          ))}
          {equalLows.map((eq, i) => (
            <div key={`eql-${i}`} className="flex items-center gap-2 text-xs font-mono py-0.5">
              <Badge label="EQL" color="green" />
              <span className="text-zinc-400 tabular-nums">{eq.price.toFixed(priceDecimals)}</span>
              <span className="text-zinc-500">x{eq.touchCount}</span>
              {eq.liquidityConfirmScore != null && (
                <div className="flex items-center gap-1 min-w-[40px]">
                  <div className="flex-1 h-1.5 bg-zinc-800 rounded-full overflow-hidden">
                    <div className={`h-full rounded-full ${barColor(eq.liquidityConfirmScore)}`} style={{ width: `${Math.min(eq.liquidityConfirmScore, 100)}%` }} />
                  </div>
                  <span className={`text-[9px] font-bold tabular-nums ${textColor(eq.liquidityConfirmScore)}`}>{Math.round(eq.liquidityConfirmScore)}</span>
                </div>
              )}
              {eq.ordersVisible && <span className="text-[9px] text-emerald-400 font-bold">L2</span>}
            </div>
          ))}
        </Section>
      )}

      {/* Order Blocks */}
      <Section title={`Order Blocks (${activeOrderBlocks.length + visibleBreakers.length})`}>
        <table className="w-full text-xs font-mono">
          <thead>
            <tr className="text-[9px] uppercase tracking-widest text-zinc-600 text-center">
              <th className="pb-1 font-normal">Zone</th>
              <th className="pb-1 font-normal">Range</th>
              <th className="pb-1 font-normal">F<span className="mx-2 text-zinc-700">|</span>L</th>
              <th className="pb-1 font-normal">Mid</th>
            </tr>
          </thead>
          <tbody>
            {activeOrderBlocks.map((ob, i) => (
              <tr key={`a-${i}`} className="border-t border-zinc-800/50">
                <td className="py-1 pr-2">
                  <Badge label={ob.type} color={ob.type === 'BULLISH' ? 'green' : 'red'} />
                </td>
                <td className="py-1 pr-2 text-zinc-400 tabular-nums">
                  {ob.low.toFixed(priceDecimals)}–{ob.high.toFixed(priceDecimals)}
                </td>
                <td className="py-1 pr-2">
                  <OBScoreBar ob={ob} showLive />
                </td>
                <td className="py-1 text-right text-zinc-500 tabular-nums">{ob.mid.toFixed(priceDecimals)}</td>
              </tr>
            ))}
            {visibleBreakers.length > 0 && (
              <tr><td colSpan={4} className="pt-2 pb-1 text-[9px] uppercase tracking-widest text-zinc-600">Breaker {hiddenBreakerCount > 0 && <span className="normal-case tracking-normal">({hiddenBreakerCount} offside)</span>}</td></tr>
            )}
            {visibleBreakers.map((ob, i) => (
              <tr key={`b-${i}`} className="border-t border-zinc-800/50">
                <td className="py-1 pr-2">
                  <Badge label={`${ob.type[0]}v2`} color={ob.type === 'BULLISH' ? 'blue' : 'amber'} />
                </td>
                <td className="py-1 pr-2 text-zinc-400 tabular-nums">
                  {ob.low.toFixed(priceDecimals)}–{ob.high.toFixed(priceDecimals)}
                </td>
                <td className="py-1 pr-2">
                  <OBScoreBar ob={ob} />
                </td>
                <td className="py-1 text-right text-zinc-500 tabular-nums text-[10px]">
                  {breakerOriginalType(ob).toLowerCase()} · {ob.mid.toFixed(priceDecimals)}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {activeOrderBlocks.length === 0 && visibleBreakers.length === 0 && (
          <span className="text-zinc-600 text-xs">No active OBs</span>
        )}
      </Section>

      {/* Order Flow panel injected here — fills the column next to OBs */}
      {children}
    </div>
  );
}

function barColor(s: number) {
  return s >= 70 ? 'bg-emerald-500' : s >= 40 ? 'bg-yellow-500' : 'bg-red-500';
}
function textColor(s: number) {
  return s >= 70 ? 'text-emerald-400' : s >= 40 ? 'text-yellow-400' : 'text-red-400';
}

/** Dual bar: Formation (left) | Live (right) — OB quality indicator */
function OBScoreBar({ ob, showLive }: { ob: OrderBlockView; showLive?: boolean }) {
  const f = ob.obFormationScore;
  const l = showLive ? ob.obLiveScore : null;
  if (f == null) return <span className="text-zinc-600 text-[10px]">—</span>;

  return (
    <div className="flex items-center gap-1 min-w-[120px]">
      {/* Formation half */}
      <span className={`text-[9px] font-bold tabular-nums ${textColor(f)}`}>{Math.round(f)}</span>
      <div className="flex-1 flex gap-px">
        <div className="flex-1 h-1.5 bg-zinc-800 rounded-l-full overflow-hidden" title={`Formation: ${Math.round(f)}`}>
          <div className={`h-full rounded-l-full ${barColor(f)}`} style={{ width: `${Math.min(f, 100)}%` }} />
        </div>
        {/* Live half */}
        <div className="flex-1 h-1.5 bg-zinc-800 rounded-r-full overflow-hidden" title={`Live: ${l != null ? Math.round(l) : '—'}`}>
          {l != null && (
            <div className={`h-full rounded-r-full ${barColor(l)}`} style={{ width: `${Math.min(l, 100)}%` }} />
          )}
        </div>
      </div>
      <span className={`text-[9px] font-bold tabular-nums ${l != null ? textColor(l) : 'text-zinc-600'}`}>
        {l != null ? Math.round(l) : '—'}
      </span>
      {showLive && ob.defended && (
        <span className="text-[9px] px-1 rounded bg-emerald-500/20 text-emerald-400 font-bold">DEF</span>
      )}
    </div>
  );
}

function Section({ title, children, fullWidth }: {
  title: string; children: React.ReactNode; fullWidth?: boolean;
}) {
  return (
    <div className={`bg-zinc-900 rounded-lg border border-zinc-800 p-3 ${fullWidth ? 'col-span-2' : ''}`}>
      <h3 className="text-[10px] font-semibold uppercase tracking-widest text-zinc-500 mb-2">{title}</h3>
      {children}
    </div>
  );
}
