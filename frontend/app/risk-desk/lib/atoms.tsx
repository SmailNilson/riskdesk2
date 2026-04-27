'use client';

import { CSSProperties, ReactNode, useId } from 'react';

export type ChipKind = 'up' | 'down' | 'warn' | 'info' | 'accent' | 'violet' | 'ghost' | 'solid-up' | 'solid-down';
export type DotKind = 'up' | 'down' | 'warn' | 'accent';

// ─── Sparkline ───────────────────────────────────────────────────
export function Sparkline({
  values,
  w = 80,
  h = 24,
  color,
  fill = true,
  baseline = false,
  strokeWidth = 1.25,
}: {
  values: number[];
  w?: number;
  h?: number;
  color?: string;
  fill?: boolean;
  baseline?: boolean;
  strokeWidth?: number;
}) {
  if (!values || values.length === 0) return null;
  const min = Math.min(...values);
  const max = Math.max(...values);
  const range = max - min || 1;
  const step = w / (values.length - 1);
  const pts = values.map((v, i) => [i * step, h - ((v - min) / range) * h] as const);
  const line = pts.map((p, i) => `${i ? 'L' : 'M'}${p[0].toFixed(1)},${p[1].toFixed(1)}`).join(' ');
  const area = `${line} L${w},${h} L0,${h} Z`;
  const c = color || 'var(--accent)';
  const last = pts[pts.length - 1];
  return (
    <svg width={w} height={h} style={{ display: 'block', overflow: 'visible' }}>
      {baseline && <line x1="0" y1={h / 2} x2={w} y2={h / 2} stroke="var(--line)" strokeDasharray="2 2" />}
      {fill && <path d={area} fill={c} opacity="0.14" />}
      <path d={line} fill="none" stroke={c} strokeWidth={strokeWidth} strokeLinejoin="round" strokeLinecap="round" />
      <circle cx={last[0]} cy={last[1]} r="2" fill={c} />
    </svg>
  );
}

// ─── BarGauge ───────────────────────────────────────────────────
export function BarGauge({
  value,
  min = 0,
  max = 1,
  color = 'var(--accent)',
  height = 4,
  marker,
  zones,
}: {
  value: number;
  min?: number;
  max?: number;
  color?: string;
  height?: number;
  marker?: number;
  zones?: Array<{ from: number; to: number; color: string }>;
}) {
  const pct = Math.max(0, Math.min(1, (value - min) / (max - min)));
  return (
    <div
      style={{
        position: 'relative',
        width: '100%',
        height,
        background: 'var(--s3)',
        borderRadius: 2,
        overflow: 'hidden',
      }}
    >
      {zones &&
        zones.map((z, i) => (
          <div
            key={i}
            style={{
              position: 'absolute',
              left: `${z.from * 100}%`,
              width: `${(z.to - z.from) * 100}%`,
              top: 0,
              bottom: 0,
              background: z.color,
            }}
          />
        ))}
      <div style={{ width: `${pct * 100}%`, height: '100%', background: color, borderRadius: 2 }} />
      {typeof marker === 'number' && (
        <div
          style={{ position: 'absolute', left: `${marker * 100}%`, top: -2, bottom: -2, width: 1, background: 'var(--ink-1)' }}
        />
      )}
    </div>
  );
}

// ─── RRLadder ───────────────────────────────────────────────────
export function RRLadder({
  side,
  entry,
  sl,
  tp1,
  tp2,
  last,
}: {
  side: 'long' | 'short';
  entry: number;
  sl: number;
  tp1: number;
  tp2: number;
  last: number;
}) {
  const lo = Math.min(sl, entry, tp1, tp2, last);
  const hi = Math.max(sl, entry, tp1, tp2, last);
  const range = hi - lo || 1;
  const pos = (v: number) => ((v - lo) / range) * 100;
  const isLong = side === 'long';
  const markers = [
    { v: sl, c: 'var(--down)', label: 'SL' },
    { v: entry, c: 'var(--ink-1)', label: 'E' },
    { v: tp1, c: 'var(--up)', label: 'T1' },
    { v: tp2, c: 'var(--up)', label: 'T2' },
  ];
  return (
    <div style={{ position: 'relative', height: 28, padding: '10px 4px' }}>
      <div style={{ position: 'absolute', left: 4, right: 4, top: 13, height: 2, background: 'var(--line)' }} />
      <div
        style={{
          position: 'absolute',
          left: `${pos(Math.min(entry, last))}%`,
          width: `${Math.abs(pos(last) - pos(entry))}%`,
          top: 13,
          height: 2,
          background: isLong ? 'var(--up)' : 'var(--down)',
        }}
      />
      {markers.map((m, i) => (
        <div key={i} style={{ position: 'absolute', left: `${pos(m.v)}%`, top: 8, transform: 'translateX(-50%)' }}>
          <div style={{ width: 2, height: 12, background: m.c }} />
          <div
            style={{
              position: 'absolute',
              top: -10,
              left: '50%',
              transform: 'translateX(-50%)',
              fontSize: 9,
              fontFamily: 'var(--font-mono)',
              color: m.c,
              fontWeight: 600,
            }}
          >
            {m.label}
          </div>
        </div>
      ))}
      <div style={{ position: 'absolute', left: `${pos(last)}%`, top: 4, transform: 'translateX(-50%)' }}>
        <div
          style={{
            width: 0,
            height: 0,
            borderLeft: '4px solid transparent',
            borderRight: '4px solid transparent',
            borderTop: '5px solid var(--accent)',
          }}
        />
      </div>
    </div>
  );
}

// ─── StatusDot ───────────────────────────────────────────────────
export function StatusDot({ kind = 'up', pulse = false, size = 6 }: { kind?: DotKind; pulse?: boolean; size?: number }) {
  return <span className={`dot dot-${kind} ${pulse ? 'pulse' : ''}`} style={{ width: size, height: size }} />;
}

// ─── Chip ───────────────────────────────────────────────────
export function Chip({ children, kind, ...rest }: { children: ReactNode; kind?: ChipKind; [k: string]: unknown }) {
  return (
    <span className={`chip ${kind ? 'chip-' + kind : ''}`} {...rest}>
      {children}
    </span>
  );
}

// ─── Segmented ───────────────────────────────────────────────────
export type SegOption<T extends string> = T | { value: T; label: ReactNode };
export function Segmented<T extends string>({
  value,
  options,
  onChange,
}: {
  value: T;
  options: SegOption<T>[];
  onChange?: (v: T) => void;
}) {
  return (
    <div className="segmented">
      {options.map((opt) => {
        const v = (typeof opt === 'string' ? opt : opt.value) as T;
        const l = typeof opt === 'string' ? opt : opt.label;
        return (
          <button type="button" key={String(v)} aria-pressed={value === v} onClick={() => onChange && onChange(v)}>
            {l}
          </button>
        );
      })}
    </div>
  );
}

// ─── Panel ───────────────────────────────────────────────────
export function Panel({
  title,
  subtitle,
  right,
  children,
  dense,
  flat,
  style,
}: {
  title?: ReactNode;
  subtitle?: ReactNode;
  right?: ReactNode;
  children: ReactNode;
  dense?: boolean;
  flat?: boolean;
  style?: CSSProperties;
}) {
  return (
    <div className={flat ? 'panel-flat' : 'panel'} style={style}>
      {(title || right) && (
        <div className="panel-head">
          {title && <span className="title">{title}</span>}
          {subtitle && (
            <span
              className="muted"
              style={{ textTransform: 'none', letterSpacing: 0, fontSize: 11, fontWeight: 400 }}
            >
              {subtitle}
            </span>
          )}
          <span className="spacer" />
          {right}
        </div>
      )}
      <div className={`panel-body ${dense ? 'dense' : ''}`}>{children}</div>
    </div>
  );
}

// ─── Heatcell ───────────────────────────────────────────────────
export function Heatcell({
  v,
  formatter,
  w = 56,
  h = 26,
}: {
  v: number;
  formatter?: (v: number) => string;
  w?: number | string;
  h?: number;
}) {
  const a = Math.abs(v);
  let bg: string;
  let fg: string;
  if (v >= 0) {
    bg = `color-mix(in oklch, var(--up) ${Math.round(a * 80)}%, var(--s2))`;
    fg = a > 0.5 ? 'var(--up-deep)' : 'var(--ink-1)';
  } else {
    bg = `color-mix(in oklch, var(--down) ${Math.round(a * 80)}%, var(--s2))`;
    fg = a > 0.5 ? 'var(--down-deep)' : 'var(--ink-1)';
  }
  return (
    <div
      style={{
        width: w,
        height: h,
        background: bg,
        color: fg,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        fontFamily: 'var(--font-mono)',
        fontSize: 10,
        fontWeight: 600,
        borderRight: '1px solid var(--line)',
        borderBottom: '1px solid var(--line)',
      }}
    >
      {formatter ? formatter(v) : v.toFixed(2)}
    </div>
  );
}

// ─── Histogram ───────────────────────────────────────────────────
export function Histogram({ values, w = 80, h = 24 }: { values: number[]; w?: number; h?: number }) {
  const max = Math.max(...values.map(Math.abs)) || 1;
  const bw = w / values.length;
  const id = useId();
  return (
    <svg width={w} height={h} style={{ display: 'block' }} aria-labelledby={id}>
      <line x1="0" y1={h / 2} x2={w} y2={h / 2} stroke="var(--line)" />
      {values.map((v, i) => {
        const bh = (Math.abs(v) / max) * (h / 2 - 1);
        const y = v >= 0 ? h / 2 - bh : h / 2;
        return (
          <rect
            key={i}
            x={i * bw + 0.5}
            y={y}
            width={bw - 1}
            height={bh}
            fill={v >= 0 ? 'var(--up)' : 'var(--down)'}
            opacity="0.85"
          />
        );
      })}
    </svg>
  );
}

// ─── SectionLabel ───────────────────────────────────────────────────
export function SectionLabel({ children, right }: { children: ReactNode; right?: ReactNode }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
      <span className="section-label">{children}</span>
      <span style={{ flex: 1, height: 1, background: 'var(--line)' }} />
      {right}
    </div>
  );
}

// ─── KV ───────────────────────────────────────────────────
export function KV({ k, v, mono = true, align = 'right' }: { k: ReactNode; v: ReactNode; mono?: boolean; align?: string }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', fontSize: 12 }}>
      <span style={{ color: 'var(--ink-3)', fontSize: 11 }}>{k}</span>
      <span
        style={{
          color: 'var(--ink-1)',
          textAlign: align as React.CSSProperties['textAlign'],
          fontFamily: mono ? 'var(--font-mono)' : 'var(--font-sans)',
          fontVariantNumeric: 'tabular-nums',
        }}
      >
        {v}
      </span>
    </div>
  );
}
