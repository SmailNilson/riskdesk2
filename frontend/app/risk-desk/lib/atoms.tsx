'use client';

import { CSSProperties, ReactNode, useId } from 'react';

export type Tone = 'pos' | 'neg' | 'warn' | 'info' | 'vio' | 'accent' | 'mute';

// ── Status dot
export function StatusDot({
  tone = 'pos',
  pulse = false,
  size = 7,
}: {
  tone?: Tone;
  pulse?: boolean;
  size?: number;
}) {
  const colorMap: Record<Tone, string> = {
    pos: 'var(--pos)',
    neg: 'var(--neg)',
    warn: 'var(--warn)',
    info: 'var(--info)',
    vio: 'var(--vio)',
    accent: 'var(--accent)',
    mute: 'var(--fg-3)',
  };
  const color = colorMap[tone];
  return (
    <span
      style={{
        display: 'inline-block',
        width: size,
        height: size,
        borderRadius: 999,
        background: color,
        boxShadow: pulse ? `0 0 0 0 ${color}` : 'none',
        animation: pulse ? 'rd-pulse 1.6s infinite' : 'none',
        flexShrink: 0,
      }}
    />
  );
}

// ── Tag/chip — outlined, never bg-soup
export function Chip({
  tone = 'mute',
  children,
  soft = false,
  mono = false,
  style,
}: {
  tone?: Tone;
  children: ReactNode;
  soft?: boolean;
  mono?: boolean;
  style?: CSSProperties;
}) {
  const map: Record<Tone, { bd: string; fg: string; bg: string }> = {
    pos: { bd: 'color-mix(in oklab, var(--pos) 40%, transparent)', fg: 'var(--pos)', bg: soft ? 'var(--pos-bg)' : 'transparent' },
    neg: { bd: 'color-mix(in oklab, var(--neg) 40%, transparent)', fg: 'var(--neg)', bg: soft ? 'var(--neg-bg)' : 'transparent' },
    warn: { bd: 'color-mix(in oklab, var(--warn) 40%, transparent)', fg: 'var(--warn)', bg: soft ? 'var(--warn-bg)' : 'transparent' },
    info: { bd: 'color-mix(in oklab, var(--info) 40%, transparent)', fg: 'var(--info)', bg: soft ? 'var(--info-bg)' : 'transparent' },
    vio: { bd: 'color-mix(in oklab, var(--vio) 40%, transparent)', fg: 'var(--vio)', bg: soft ? 'var(--vio-bg)' : 'transparent' },
    accent: { bd: 'color-mix(in oklab, var(--accent) 40%, transparent)', fg: 'var(--accent)', bg: soft ? 'var(--accent-bg-soft)' : 'transparent' },
    mute: { bd: 'var(--hair)', fg: 'var(--fg-1)', bg: soft ? 'var(--bg-2)' : 'transparent' },
  };
  const c = map[tone];
  return (
    <span
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: 5,
        padding: '2px 7px',
        border: `1px solid ${c.bd}`,
        borderRadius: 4,
        color: c.fg,
        background: c.bg,
        fontSize: 10,
        lineHeight: 1.4,
        fontWeight: 600,
        letterSpacing: '0.02em',
        fontFamily: mono ? 'var(--font-mono)' : 'inherit',
        ...style,
      }}
    >
      {children}
    </span>
  );
}

// ── Numeric delta — colored signed number
export function Delta({
  value,
  decimals = 2,
  prefix = '',
  suffix = '',
  showSign = true,
  size,
}: {
  value: number | null | undefined;
  decimals?: number;
  prefix?: string;
  suffix?: string;
  showSign?: boolean;
  size?: number;
}) {
  if (value == null || Number.isNaN(value)) {
    return <span className="num" style={{ color: 'var(--fg-3)', fontSize: size }}>—</span>;
  }
  const tone = value > 0 ? 'var(--pos)' : value < 0 ? 'var(--neg)' : 'var(--fg-2)';
  const sign = value > 0 ? '+' : value < 0 ? '−' : '';
  const abs = Math.abs(value).toFixed(decimals);
  return (
    <span className="num" style={{ color: tone, fontSize: size, fontWeight: 600 }}>
      {showSign ? sign : ''}
      {prefix}
      {abs}
      {suffix}
    </span>
  );
}

// ── Sparkline
export function Sparkline({
  data,
  width = 80,
  height = 22,
  tone = 'accent',
  stroke = 1.4,
  fill = true,
}: {
  data: number[];
  width?: number;
  height?: number;
  tone?: Tone;
  stroke?: number;
  fill?: boolean;
}) {
  const id = useId();
  if (!data || data.length === 0) return null;
  const min = Math.min(...data);
  const max = Math.max(...data);
  const span = max - min || 1;
  const stepX = width / (data.length - 1);
  const pts = data.map((v, i) => [i * stepX, height - ((v - min) / span) * height] as const);
  const d = pts.map((p, i) => `${i === 0 ? 'M' : 'L'}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ');
  const area = fill ? `${d} L${width},${height} L0,${height} Z` : null;
  const colorMap: Record<Tone, string> = {
    accent: 'var(--accent)',
    pos: 'var(--pos)',
    neg: 'var(--neg)',
    info: 'var(--info)',
    warn: 'var(--warn)',
    vio: 'var(--vio)',
    mute: 'var(--fg-2)',
  };
  const strokeColor = colorMap[tone];
  return (
    <svg width={width} height={height} style={{ display: 'block', overflow: 'visible' }}>
      {fill && (
        <>
          <defs>
            <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stopColor={strokeColor} stopOpacity="0.30" />
              <stop offset="100%" stopColor={strokeColor} stopOpacity="0" />
            </linearGradient>
          </defs>
          {area && <path d={area} fill={`url(#${id})`} />}
        </>
      )}
      <path d={d} fill="none" stroke={strokeColor} strokeWidth={stroke} strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

// ── Bar gauge — used for RSI, margin, drawdown
export function BarGauge({
  value,
  min = 0,
  max = 100,
  lo = 30,
  hi = 70,
  label,
  decimals = 1,
  suffix = '',
  w,
}: {
  value: number;
  min?: number;
  max?: number;
  lo?: number;
  hi?: number;
  label?: string;
  decimals?: number;
  suffix?: string;
  w?: number | string;
}) {
  const pct = Math.max(0, Math.min(1, (value - min) / (max - min)));
  let tone = 'var(--fg-2)';
  if (value <= lo) tone = 'var(--info)';
  else if (value >= hi) tone = 'var(--warn)';
  return (
    <div style={{ width: w || '100%' }}>
      {label && (
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 4 }}>
          <span className="label">{label}</span>
          <span className="num" style={{ fontSize: 11, color: tone, fontWeight: 600 }}>
            {value.toFixed(decimals)}
            {suffix}
          </span>
        </div>
      )}
      <div style={{ position: 'relative', height: 4, background: 'var(--bg-2)', borderRadius: 2, overflow: 'hidden' }}>
        <div
          style={{
            position: 'absolute',
            inset: 0,
            left: `${lo}%`,
            right: `${100 - hi}%`,
            background: 'var(--hair)',
          }}
        />
        <div
          style={{
            position: 'absolute',
            left: 0,
            top: 0,
            bottom: 0,
            width: `${pct * 100}%`,
            background: tone,
          }}
        />
      </div>
    </div>
  );
}

// ── Panel — universal card chrome
export function Panel({
  title,
  eyebrow,
  right,
  children,
  dense = false,
  style,
  className,
}: {
  title?: ReactNode;
  eyebrow?: ReactNode;
  right?: ReactNode;
  children: ReactNode;
  dense?: boolean;
  style?: CSSProperties;
  className?: string;
}) {
  return (
    <section
      className={className}
      style={{
        background: 'var(--bg-1)',
        border: '1px solid var(--hair)',
        borderRadius: 'var(--radius)',
        overflow: 'hidden',
        ...style,
      }}
    >
      {(title || eyebrow || right) && (
        <header
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: dense ? '8px 12px' : '10px 14px',
            borderBottom: '1px solid var(--hair-soft)',
            background: 'var(--bg-1)',
            gap: 8,
            minHeight: 36,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'baseline', gap: 8, minWidth: 0 }}>
            {eyebrow && <span className="label" style={{ color: 'var(--accent)' }}>{eyebrow}</span>}
            {title && <span style={{ fontSize: 12, fontWeight: 600, color: 'var(--fg-0)' }}>{title}</span>}
          </div>
          {right && <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>{right}</div>}
        </header>
      )}
      <div style={{ padding: dense ? '10px 12px' : '12px 14px' }}>{children}</div>
    </section>
  );
}

// ── Segmented control
export type SegmentedOption<T extends string> = T | { value: T; label: ReactNode };

export function Segmented<T extends string>({
  value,
  onChange,
  options,
  size = 'sm',
}: {
  value: T;
  onChange: (v: T) => void;
  options: SegmentedOption<T>[];
  size?: 'xs' | 'sm' | 'md';
}) {
  const pad = size === 'xs' ? '3px 8px' : size === 'sm' ? '5px 10px' : '7px 12px';
  const fs = size === 'xs' ? 10 : 11;
  return (
    <div
      style={{
        display: 'inline-flex',
        border: '1px solid var(--hair)',
        borderRadius: 6,
        overflow: 'hidden',
        background: 'var(--bg-1)',
      }}
    >
      {options.map((opt, i) => {
        const v = (typeof opt === 'string' ? opt : opt.value) as T;
        const label = typeof opt === 'string' ? opt : opt.label;
        const active = v === value;
        return (
          <button
            type="button"
            key={String(v)}
            onClick={() => onChange(v)}
            style={{
              padding: pad,
              fontSize: fs,
              fontWeight: 600,
              background: active ? 'var(--bg-3)' : 'transparent',
              color: active ? 'var(--fg-0)' : 'var(--fg-2)',
              border: 'none',
              borderLeft: i === 0 ? 'none' : '1px solid var(--hair)',
              cursor: 'pointer',
              fontFamily: 'inherit',
              letterSpacing: '0.02em',
              transition: 'color 120ms, background 120ms',
            }}
          >
            {label}
          </button>
        );
      })}
    </div>
  );
}

// ── Key/value row
export function KV({
  label,
  value,
  tone,
  mono = true,
}: {
  label: ReactNode;
  value: ReactNode;
  tone?: Tone;
  mono?: boolean;
}) {
  const colorMap: Record<Tone, string> = {
    pos: 'var(--pos)',
    neg: 'var(--neg)',
    warn: 'var(--warn)',
    info: 'var(--info)',
    vio: 'var(--vio)',
    accent: 'var(--accent)',
    mute: 'var(--fg-0)',
  };
  const color = tone ? colorMap[tone] : 'var(--fg-0)';
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'baseline',
        padding: '5px 0',
        borderBottom: '1px dashed var(--hair-soft)',
        gap: 8,
      }}
    >
      <span style={{ fontSize: 11, color: 'var(--fg-2)', letterSpacing: '0.02em' }}>{label}</span>
      <span className={mono ? 'num' : ''} style={{ fontSize: 11.5, color, fontWeight: 600 }}>
        {value}
      </span>
    </div>
  );
}
