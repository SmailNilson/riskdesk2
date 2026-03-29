import { IndicatorSnapshot, OrderBlockView } from '@/app/lib/api';

const BREAKER_MAX_DISTANCE_RATIO = 0.02;

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

  const absCurrentPrice = Math.max(Math.abs(currentPrice), 1);
  const maxDistance = absCurrentPrice * BREAKER_MAX_DISTANCE_RATIO;

  return sorted.filter(block => {
    const isOnActionableSide = block.type === 'BULLISH' ? block.mid <= currentPrice : block.mid >= currentPrice;
    const distanceFromPrice = Math.abs(block.mid - currentPrice);
    return isOnActionableSide && distanceFromPrice <= maxDistance;
  });
}
