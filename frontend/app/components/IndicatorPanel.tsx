'use client';

import { IndicatorSnapshot } from '@/app/lib/api';
import { breakerOriginalType, relevantBreakerBlocks } from '@/app/lib/orderBlocks';

interface Props {
  snapshot: IndicatorSnapshot | null;
  currentPrice: number | null;
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

export default function IndicatorPanel({ snapshot: s, currentPrice }: Props) {
  if (!s) return (
    <div className="bg-zinc-900 rounded-lg border border-zinc-800 p-4 text-zinc-500 text-sm">
      Loading indicators…
    </div>
  );

  // Price decimals per instrument — E6 needs 5 to match IBKR tick size
  const priceDecimals = s.instrument === 'E6' ? 5 : 2;
  const n = (v: number | null, d?: number) => v != null ? v.toFixed(d ?? priceDecimals) : '—';
  const equalHighs = s.equalHighs ?? [];
  const equalLows = s.equalLows ?? [];
  const activeOrderBlocks = s.activeOrderBlocks ?? [];
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

      {/* EQH / EQL */}
      {(equalHighs.length > 0 || equalLows.length > 0) && (
        <Section title={`EQH/EQL (${equalHighs.length + equalLows.length})`}>
          {equalHighs.map((eq, i) => (
            <div key={`eqh-${i}`} className="flex justify-between items-center text-xs font-mono py-0.5">
              <Badge label="EQH" color="red" />
              <span className="text-zinc-400">{eq.price.toFixed(priceDecimals)}</span>
              <span className="text-zinc-500">x{eq.touchCount}</span>
            </div>
          ))}
          {equalLows.map((eq, i) => (
            <div key={`eql-${i}`} className="flex justify-between items-center text-xs font-mono py-0.5">
              <Badge label="EQL" color="green" />
              <span className="text-zinc-400">{eq.price.toFixed(priceDecimals)}</span>
              <span className="text-zinc-500">x{eq.touchCount}</span>
            </div>
          ))}
        </Section>
      )}

      {/* Order Blocks */}
      <Section title={`Order Blocks V1/V2 (${activeOrderBlocks.length + visibleBreakers.length})`} fullWidth>
        <div className="space-y-1.5">
          <div className="text-[10px] uppercase tracking-widest text-zinc-500">V1 Active</div>
          {activeOrderBlocks.length === 0
            ? <span className="text-zinc-600 text-xs">No active OBs</span>
            : activeOrderBlocks.map((ob, i) => (
              <div key={`active-${i}`} className="flex justify-between items-center text-xs font-mono py-0.5">
                <Badge label={ob.type} color={ob.type === 'BULLISH' ? 'green' : 'red'} />
                <span className="text-zinc-400">
                  {ob.low.toFixed(priceDecimals)} – {ob.high.toFixed(priceDecimals)}
                </span>
                <span className="text-zinc-500">mid {ob.mid.toFixed(priceDecimals)}</span>
              </div>
            ))
          }
        </div>
        <div className="mt-3 space-y-1.5">
          <div className="flex items-center justify-between gap-2">
            <div className="text-[10px] uppercase tracking-widest text-zinc-500">V2 Breaker</div>
            {hiddenBreakerCount > 0 && (
              <span className="text-[10px] text-zinc-600">
                {hiddenBreakerCount} offside
              </span>
            )}
          </div>
          {visibleBreakers.length === 0
            ? <span className="text-zinc-600 text-xs">No breaker OBs near current price</span>
            : visibleBreakers.map((ob, i) => (
              <div key={`breaker-${i}`} className="flex justify-between items-center text-xs font-mono py-0.5">
                <Badge label={`${ob.type} V2`} color={ob.type === 'BULLISH' ? 'blue' : 'amber'} />
                <span className="text-zinc-400">
                  {ob.low.toFixed(priceDecimals)} – {ob.high.toFixed(priceDecimals)}
                </span>
                <span className="text-zinc-500">
                  from {breakerOriginalType(ob).toLowerCase()} · mid {ob.mid.toFixed(priceDecimals)}
                </span>
              </div>
            ))
          }
        </div>
      </Section>
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
