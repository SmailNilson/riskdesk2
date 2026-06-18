'use client';

import { Badge } from './Badge';
import type { BadgeSize } from './Badge';

export interface SideBadgeProps {
  direction: 'LONG' | 'SHORT';
  size?: BadgeSize;
  className?: string;
}

export function SideBadge({ direction, size = 'sm', className }: SideBadgeProps) {
  return (
    <Badge
      variant={direction === 'LONG' ? 'bull' : 'bear'}
      size={size}
      className={className}
    >
      {direction}
    </Badge>
  );
}

export default SideBadge;
