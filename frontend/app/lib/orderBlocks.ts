import { IndicatorSnapshot, OrderBlockView } from '@/app/lib/api';

export function breakerReferenceTime(block: OrderBlockView): number {
  return block.breakerTime ?? block.startTime;
}

export function breakerOriginalType(block: OrderBlockView): 'BULLISH' | 'BEARISH' {
  if (block.originalType) {
    return block.originalType;
  }
  return block.type === 'BULLISH' ? 'BEARISH' : 'BULLISH';
}

export function relevantBreakerBlocks(snapshot: IndicatorSnapshot | null, currentPrice: number | null): OrderBlockView[] {
  if (!snapshot?.breakerOrderBlocks?.length) {
    return [];
  }

  const sorted = [...snapshot.breakerOrderBlocks]
    .sort((a, b) => breakerReferenceTime(b) - breakerReferenceTime(a));

  if (currentPrice == null) {
    return sorted;
  }

  return sorted.filter(block =>
    block.type === 'BULLISH' ? block.mid <= currentPrice : block.mid >= currentPrice
  );
}
