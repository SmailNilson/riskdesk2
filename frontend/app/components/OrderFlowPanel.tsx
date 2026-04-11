'use client';

import { useOrderFlow, OrderFlowMetrics, DepthMetrics, AbsorptionEvent, SpoofingEvent } from '@/app/hooks/useOrderFlow';

const DEPTH_INSTRUMENTS = ['MNQ', 'MCL', 'MGC'] as const;

interface OrderFlowPanelProps {
  selectedInstrument?: string;
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function DeltaBar({ metrics }: { metrics: OrderFlowMetrics }) {
  const totalVolume = metrics.buyVolume + metrics.sellVolume;
  const buyPct = totalVolume > 0 ? (metrics.buyVolume / totalVolume) * 100 : 50;
  const sellPct = 100 - buyPct;
  const isRealTicks = metrics.source === 'REAL_TICKS';

  return (
    <div className="flex flex-col gap-1 p-2 rounded bg-zinc-800/60">
      <div className="flex items-center justify-between text-xs">
        <span className="font-medium text-zinc-300">{metrics.instrument}</span>
        <div className="flex items-center gap-2">
          <span className={metrics.delta >= 0 ? 'text-emerald-400' : 'text-red-400'}>
            {metrics.delta >= 0 ? '+' : ''}{metrics.delta.toLocaleString()}
          </span>
          <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold ${
            isRealTicks ? 'bg-emerald-900/60 text-emerald-400' : 'bg-yellow-900/60 text-yellow-400'
          }`}>
            {isRealTicks ? 'REAL' : 'CLV'}
          </span>
        </div>
      </div>

      {/* Buy/Sell bar */}
      <div className="flex h-3 rounded overflow-hidden">
        <div
          className="bg-emerald-600 transition-all duration-300"
          style={{ width: `${buyPct}%` }}
        />
        <div
          className="bg-red-600 transition-all duration-300"
          style={{ width: `${sellPct}%` }}
        />
      </div>

      <div className="flex justify-between text-[10px] text-zinc-500">
        <span>Buy {metrics.buyVolume.toLocaleString()} ({buyPct.toFixed(1)}%)</span>
        <span className="text-zinc-600">Cum: {metrics.cumulativeDelta.toLocaleString()}</span>
        <span>Sell {metrics.sellVolume.toLocaleString()} ({sellPct.toFixed(1)}%)</span>
      </div>
    </div>
  );
}

function DepthGauge({ metrics }: { metrics: DepthMetrics }) {
  // imbalance is -1 to +1; map to 0-100 for gauge position
  const gaugePosition = ((metrics.imbalance + 1) / 2) * 100;

  return (
    <div className="flex flex-col gap-1.5 p-2 rounded bg-zinc-800/60">
      <div className="flex items-center justify-between text-xs">
        <span className="font-medium text-zinc-300">{metrics.instrument}</span>
        <span className="text-zinc-500">Spread: {metrics.spread.toFixed(2)}</span>
      </div>

      {/* Imbalance gauge */}
      <div className="relative h-4 rounded bg-zinc-700 overflow-hidden">
        <div className="absolute inset-0 flex">
          <div className="w-1/2 bg-emerald-900/30" />
          <div className="w-1/2 bg-red-900/30" />
        </div>
        {/* Center line */}
        <div className="absolute left-1/2 top-0 bottom-0 w-px bg-zinc-500" />
        {/* Indicator needle */}
        <div
          className="absolute top-0 bottom-0 w-1.5 rounded bg-white/80 transition-all duration-300"
          style={{ left: `calc(${gaugePosition}% - 3px)` }}
        />
      </div>

      <div className="flex justify-between text-[10px]">
        <span className="text-emerald-400">
          Bid: {metrics.totalBidSize.toLocaleString()}
          {metrics.bidWall && (
            <span className="ml-1 text-emerald-300">
              Wall @{metrics.bidWall.price.toFixed(2)} ({metrics.bidWall.size.toLocaleString()})
            </span>
          )}
        </span>
        <span className="text-red-400">
          Ask: {metrics.totalAskSize.toLocaleString()}
          {metrics.askWall && (
            <span className="ml-1 text-red-300">
              Wall @{metrics.askWall.price.toFixed(2)} ({metrics.askWall.size.toLocaleString()})
            </span>
          )}
        </span>
      </div>

      <div className="text-center text-[10px] text-zinc-400">
        Imbalance: {(metrics.imbalance * 100).toFixed(1)}%
      </div>
    </div>
  );
}

function EventLogEntry({ event, type }: { event: AbsorptionEvent | SpoofingEvent; type: 'absorption' | 'spoofing' }) {
  const isAbsorption = type === 'absorption';
  const absEvent = event as AbsorptionEvent;
  const spoofEvent = event as SpoofingEvent;

  const time = new Date(event.timestamp).toLocaleTimeString();
  const isBuy = event.side === 'BUY' || event.side === 'LONG';

  return (
    <div className="flex items-center gap-2 px-2 py-1 text-[11px] rounded bg-zinc-800/40 hover:bg-zinc-800/70 transition-colors">
      <span className={`shrink-0 px-1.5 py-0.5 rounded text-[9px] font-bold ${
        isAbsorption ? 'bg-purple-900/60 text-purple-300' : 'bg-orange-900/60 text-orange-300'
      }`}>
        {isAbsorption ? 'ABS' : 'SPOOF'}
      </span>
      <span className="text-zinc-400 shrink-0">{event.instrument}</span>
      <span className={`shrink-0 ${isBuy ? 'text-emerald-400' : 'text-red-400'}`}>
        {event.side}
      </span>
      <span className="text-zinc-500 truncate">
        {isAbsorption
          ? `Score: ${absEvent.score.toFixed(2)} | Delta: ${absEvent.delta.toLocaleString()}`
          : `@${spoofEvent.priceLevel.toFixed(2)} | Size: ${spoofEvent.wallSize.toLocaleString()} | Score: ${spoofEvent.spoofScore.toFixed(2)}`
        }
      </span>
      <span className="ml-auto text-zinc-600 shrink-0">{time}</span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Main Panel
// ---------------------------------------------------------------------------

export default function OrderFlowPanel({ selectedInstrument }: OrderFlowPanelProps) {
  const { orderFlowData, depthData, absorptionEvents, spoofingEvents, connected } = useOrderFlow();

  const flowEntries = Array.from(orderFlowData.values());
  const filteredFlow = selectedInstrument
    ? flowEntries.filter(m => m.instrument === selectedInstrument)
    : flowEntries;

  const depthEntries = Array.from(depthData.values())
    .filter(m => DEPTH_INSTRUMENTS.includes(m.instrument as typeof DEPTH_INSTRUMENTS[number]));
  const filteredDepth = selectedInstrument
    ? depthEntries.filter(m => m.instrument === selectedInstrument)
    : depthEntries;

  const allEvents = [
    ...absorptionEvents.map(e => ({ event: e, type: 'absorption' as const })),
    ...spoofingEvents.map(e => ({ event: e, type: 'spoofing' as const })),
  ].sort((a, b) => new Date(b.event.timestamp).getTime() - new Date(a.event.timestamp).getTime())
   .slice(0, 20);

  const filteredEvents = selectedInstrument
    ? allEvents.filter(e => e.event.instrument === selectedInstrument)
    : allEvents;

  return (
    <div className="flex flex-col gap-3 p-3 rounded-lg bg-zinc-900 border border-zinc-800">
      {/* Header */}
      <div className="flex items-center justify-between">
        <h3 className="text-sm font-semibold text-zinc-200">Order Flow</h3>
        <span className={`w-2 h-2 rounded-full ${connected ? 'bg-emerald-400' : 'bg-red-500'}`} />
      </div>

      {/* Section 1: Delta Bars */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">Delta</h4>
        <div className="flex flex-col gap-1.5">
          {filteredFlow.length > 0
            ? filteredFlow.map(m => <DeltaBar key={m.instrument} metrics={m} />)
            : <p className="text-xs text-zinc-600 italic">Waiting for order flow data...</p>
          }
        </div>
      </div>

      {/* Section 2: Depth Gauges */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">Depth</h4>
        <div className="flex flex-col gap-1.5">
          {filteredDepth.length > 0
            ? filteredDepth.map(m => <DepthGauge key={m.instrument} metrics={m} />)
            : <p className="text-xs text-zinc-600 italic">Waiting for depth data...</p>
          }
        </div>
      </div>

      {/* Section 3: Event Log */}
      <div>
        <h4 className="text-[10px] uppercase tracking-wider text-zinc-500 mb-1.5">Events</h4>
        <div className="flex flex-col gap-0.5 max-h-48 overflow-y-auto scrollbar-thin scrollbar-thumb-zinc-700">
          {filteredEvents.length > 0
            ? filteredEvents.map((e, i) => (
                <EventLogEntry key={`${e.type}-${e.event.timestamp}-${i}`} event={e.event} type={e.type} />
              ))
            : <p className="text-xs text-zinc-600 italic">No events yet</p>
          }
        </div>
      </div>
    </div>
  );
}
