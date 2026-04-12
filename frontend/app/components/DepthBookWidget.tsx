'use client';

import { DepthMetrics } from '@/app/hooks/useOrderFlow';

interface DepthBookWidgetProps {
  instrument: string;
  depthData?: DepthMetrics;
}

// Synthetic bid/ask levels for visualization when only aggregate data is available.
// In a real scenario these would come from a full L2 feed; here we derive a simple
// 5-level display from the aggregate metrics to give a visual sense of depth.
interface BookLevel {
  price: number;
  size: number;
  isWall: boolean;
}

function deriveBookLevels(
  metrics: DepthMetrics,
  side: 'bid' | 'ask',
  levels: number = 5,
): BookLevel[] {
  const wall = side === 'bid' ? metrics.bidWall : metrics.askWall;
  const totalSize = side === 'bid' ? metrics.totalBidSize : metrics.totalAskSize;

  // If we have no meaningful data, return empty
  if (totalSize === 0 && !wall) return [];

  const result: BookLevel[] = [];
  const baseSize = totalSize / levels;
  const wallLevel = wall ? Math.min(Math.floor(levels / 2), levels - 1) : -1;

  for (let i = 0; i < levels; i++) {
    const isWall = i === wallLevel && !!wall;
    const size = isWall ? (wall?.size ?? baseSize) : baseSize * (0.6 + Math.random() * 0.8);
    const priceOffset = (i + 1) * (metrics.spread / levels);
    const price = wall?.price
      ? (side === 'bid' ? wall.price - priceOffset : wall.price + priceOffset)
      : priceOffset;

    result.push({ price, size: Math.round(size), isWall });
  }

  return result;
}

function SizeBar({
  size,
  maxSize,
  side,
  isWall,
}: {
  size: number;
  maxSize: number;
  side: 'bid' | 'ask';
  isWall: boolean;
}) {
  const pct = maxSize > 0 ? (size / maxSize) * 100 : 0;
  const barColor = side === 'bid' ? 'bg-emerald-600/70' : 'bg-red-600/70';
  const wallBorder = isWall ? 'border-2 border-white/40' : '';

  return (
    <div className={`relative h-4 rounded ${side === 'bid' ? 'flex-row-reverse' : 'flex-row'} flex`}>
      <div
        className={`h-full rounded ${barColor} ${wallBorder} transition-all duration-300`}
        style={{ width: `${Math.max(pct, 2)}%` }}
      />
    </div>
  );
}

export default function DepthBookWidget({ instrument, depthData }: DepthBookWidgetProps) {
  if (!depthData) {
    return (
      <div className="flex flex-col items-center justify-center gap-1 p-3 rounded bg-zinc-900 border border-zinc-800 min-h-[120px]">
        <span className="text-xs text-zinc-500">{instrument}</span>
        <span className="text-[10px] text-zinc-600 italic">Waiting for depth data...</span>
      </div>
    );
  }

  const bidLevels = deriveBookLevels(depthData, 'bid');
  const askLevels = deriveBookLevels(depthData, 'ask');
  const allSizes = [...bidLevels, ...askLevels].map(l => l.size);
  const maxSize = Math.max(...allSizes, 1);

  return (
    <div className="flex flex-col gap-1 p-3 rounded bg-zinc-900 border border-zinc-800">
      {/* Header */}
      <div className="flex items-center justify-between mb-1">
        <span className="text-xs font-semibold text-zinc-200">{instrument} Depth</span>
        <span className="text-[10px] text-zinc-500">
          Spread: <span className="text-zinc-300">{depthData.spread.toFixed(2)}</span>
        </span>
      </div>

      {/* Book visualization */}
      <div className="flex gap-1">
        {/* Bids (left side) */}
        <div className="flex-1 flex flex-col gap-0.5">
          {bidLevels.map((level, i) => (
            <div key={i} className="flex items-center gap-1">
              <span className="text-[9px] text-emerald-400/70 w-14 text-right shrink-0">
                {level.price > 0 ? level.price.toFixed(2) : '--'}
              </span>
              <div className="flex-1 flex flex-row-reverse">
                <SizeBar size={level.size} maxSize={maxSize} side="bid" isWall={level.isWall} />
              </div>
              <span className="text-[9px] text-zinc-500 w-10 text-right shrink-0">
                {level.size.toLocaleString()}
              </span>
            </div>
          ))}
        </div>

        {/* Center spread */}
        <div className="flex flex-col items-center justify-center px-1">
          <div className="w-px h-full bg-zinc-700" />
        </div>

        {/* Asks (right side) */}
        <div className="flex-1 flex flex-col gap-0.5">
          {askLevels.map((level, i) => (
            <div key={i} className="flex items-center gap-1">
              <span className="text-[9px] text-zinc-500 w-10 shrink-0">
                {level.size.toLocaleString()}
              </span>
              <div className="flex-1">
                <SizeBar size={level.size} maxSize={maxSize} side="ask" isWall={level.isWall} />
              </div>
              <span className="text-[9px] text-red-400/70 w-14 shrink-0">
                {level.price > 0 ? level.price.toFixed(2) : '--'}
              </span>
            </div>
          ))}
        </div>
      </div>

      {/* Imbalance */}
      <div className="flex items-center justify-center gap-2 mt-1 pt-1 border-t border-zinc-800">
        <span className="text-[10px] text-zinc-500">Imbalance:</span>
        <span className={`text-[10px] font-semibold ${
          depthData.imbalance > 0.1
            ? 'text-emerald-400'
            : depthData.imbalance < -0.1
              ? 'text-red-400'
              : 'text-zinc-400'
        }`}>
          {(depthData.imbalance * 100).toFixed(1)}%
        </span>

        {/* Wall indicators */}
        {depthData.bidWall && (
          <span className="text-[9px] px-1 py-0.5 rounded bg-emerald-900/40 text-emerald-400">
            Bid wall @{depthData.bidWall.price.toFixed(2)}
          </span>
        )}
        {depthData.askWall && (
          <span className="text-[9px] px-1 py-0.5 rounded bg-red-900/40 text-red-400">
            Ask wall @{depthData.askWall.price.toFixed(2)}
          </span>
        )}
      </div>
    </div>
  );
}
