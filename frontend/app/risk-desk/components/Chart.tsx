'use client';

import { useState } from 'react';
import { Chip } from '../lib/atoms';
import { Candle, Position, Smc } from '../lib/data';

interface ChartProps {
  symbol: string;
  tf: string;
  candles: Candle[];
  ema9: number[];
  ema20: number[];
  ema50: number[];
  smc: Smc;
  activePosition: Position | null;
}

export function LiveChart({ symbol, tf, candles, ema9, ema20, ema50, smc, activePosition }: ChartProps) {
  const lastN = 120;
  const view = candles.slice(-lastN);
  const e9 = ema9.slice(-lastN);
  const e20 = ema20.slice(-lastN);
  const e50 = ema50.slice(-lastN);

  const W = 1100;
  const H = 460;
  const padL = 0;
  const padR = 70;
  const padT = 16;
  const padB = 28;
  const chartW = W - padL - padR;
  const chartH = H - padT - padB - 90;

  const prices = view.flatMap((c) => [c.high, c.low]);
  const min = (prices.length ? Math.min(...prices) : 0) - 0.05;
  const max = (prices.length ? Math.max(...prices) : 0) + 0.05;
  const range = max - min || 1;

  const xOf = (i: number) => padL + (i / Math.max(1, view.length - 1)) * chartW;
  const yOf = (p: number) => padT + (1 - (p - min) / range) * chartH;
  const cw = (chartW / Math.max(1, view.length)) * 0.7;

  const last = view[view.length - 1];

  const [hover, setHover] = useState<{ i: number; px: number } | null>(
    last ? { i: view.length - 1, px: last.close } : null
  );
  const onMove = (e: React.MouseEvent<SVGSVGElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const px = e.clientX - rect.left;
    const py = e.clientY - rect.top;
    const ratio = (px - padL) / chartW;
    let i = Math.round(ratio * (view.length - 1));
    i = Math.max(0, Math.min(view.length - 1, i));
    const priceAt = max - ((py - padT) / chartH) * range;
    setHover({ i, px: priceAt });
  };

  const volMax = view.length ? Math.max(...view.map((c) => c.volume)) : 1;
  const volBaseY = H - padB;
  const volH = 70;

  const tIdx = (t: number) => view.findIndex((c) => c.time === t);

  return (
    <div
      className="panel-flat"
      style={{ flex: 1, display: 'flex', flexDirection: 'column', minHeight: 0, overflow: 'hidden' }}
    >
      <ChartHeader symbol={symbol} tf={tf} last={last} />
      <div
        style={{
          flex: 1,
          position: 'relative',
          background: 'var(--s0)',
          minHeight: 0,
          overflow: 'hidden',
        }}
      >
        {!last ? (
          <div
            style={{
              position: 'absolute',
              inset: 0,
              display: 'grid',
              placeItems: 'center',
              fontSize: 11,
              color: 'var(--ink-3)',
            }}
          >
            Loading candles…
          </div>
        ) : (
          <svg
            width="100%"
            height="100%"
            viewBox={`0 0 ${W} ${H}`}
            preserveAspectRatio="none"
            style={{ display: 'block', position: 'absolute', inset: 0 }}
            onMouseMove={onMove}
          >
            {[0, 0.25, 0.5, 0.75, 1].map((p, i) => (
              <g key={i}>
                <line
                  x1={padL}
                  y1={padT + p * chartH}
                  x2={W - padR}
                  y2={padT + p * chartH}
                  stroke="var(--line)"
                  strokeDasharray="1 3"
                  opacity="0.6"
                />
                <text
                  x={W - padR + 6}
                  y={padT + p * chartH + 3}
                  fill="var(--ink-3)"
                  fontSize="9"
                  fontFamily="var(--font-mono)"
                >
                  {(min + (1 - p) * range).toFixed(2)}
                </text>
              </g>
            ))}

            {/* Order blocks */}
            {smc.orderBlocks.map((ob, idx) => {
              const i1 = tIdx(ob.t1);
              if (i1 === -1) return null;
              const x1 = xOf(i1);
              const x2 = W - padR;
              const yh = yOf(ob.hi);
              const yl = yOf(ob.lo);
              const c = ob.type === 'bull' ? 'var(--up)' : 'var(--down)';
              return (
                <g key={'ob' + idx}>
                  <rect
                    x={x1}
                    y={yh}
                    width={x2 - x1}
                    height={yl - yh}
                    fill={c}
                    fillOpacity={ob.mit ? '0.05' : '0.12'}
                    stroke={c}
                    strokeOpacity="0.4"
                    strokeDasharray={ob.mit ? '3 3' : '0'}
                  />
                  <text
                    x={x1 + 4}
                    y={yh - 3}
                    fontSize="9"
                    fontFamily="var(--font-mono)"
                    fill={c}
                    opacity="0.85"
                  >
                    {ob.label}
                    {ob.mit ? ' · mit' : ''}
                  </text>
                </g>
              );
            })}

            {/* FVGs */}
            {smc.fvgs.map((f, idx) => {
              const i1 = tIdx(f.t1);
              if (i1 === -1) return null;
              const x1 = xOf(i1);
              const x2 = W - padR;
              const yh = yOf(f.hi);
              const yl = yOf(f.lo);
              return (
                <g key={'fvg' + idx}>
                  <rect
                    x={x1}
                    y={yh}
                    width={x2 - x1}
                    height={yl - yh}
                    fill="var(--violet)"
                    fillOpacity={f.filled ? '0.04' : '0.10'}
                    stroke="var(--violet)"
                    strokeOpacity="0.45"
                    strokeDasharray="4 3"
                  />
                  <text
                    x={x1 + 4}
                    y={yl + 10}
                    fontSize="9"
                    fontFamily="var(--font-mono)"
                    fill="var(--violet)"
                  >
                    {f.label}
                    {f.filled ? ' · filled' : ''}
                  </text>
                </g>
              );
            })}

            {/* Liquidity */}
            {smc.liquidity.map((l, idx) => (
              <g key={'liq' + idx}>
                <line
                  x1={padL}
                  y1={yOf(l.px)}
                  x2={W - padR}
                  y2={yOf(l.px)}
                  stroke="var(--cyan)"
                  strokeWidth="1"
                  strokeDasharray="6 4"
                  opacity="0.55"
                />
                <text
                  x={padL + 6}
                  y={yOf(l.px) - 3}
                  fontSize="9"
                  fontFamily="var(--font-mono)"
                  fill="var(--cyan)"
                >
                  {l.label} {l.px.toFixed(2)}
                </text>
              </g>
            ))}

            {/* Volume bars */}
            {view.map((c, i) => {
              const h = (c.volume / volMax) * volH;
              return (
                <rect
                  key={'v' + i}
                  x={xOf(i) - cw / 2}
                  y={volBaseY - h}
                  width={cw}
                  height={h}
                  fill={c.close >= c.open ? 'var(--up)' : 'var(--down)'}
                  opacity="0.32"
                />
              );
            })}
            <line x1={padL} y1={volBaseY} x2={W - padR} y2={volBaseY} stroke="var(--line)" />

            {/* Candles */}
            {view.map((c, i) => {
              const isUp = c.close >= c.open;
              const col = isUp ? 'var(--up)' : 'var(--down)';
              const yo = yOf(c.open);
              const yc = yOf(c.close);
              const yhc = yOf(c.high);
              const ylc = yOf(c.low);
              return (
                <g key={i}>
                  <line x1={xOf(i)} y1={yhc} x2={xOf(i)} y2={ylc} stroke={col} strokeWidth="1" />
                  <rect
                    x={xOf(i) - cw / 2}
                    y={Math.min(yo, yc)}
                    width={cw}
                    height={Math.max(1, Math.abs(yc - yo))}
                    fill={col}
                    stroke={col}
                  />
                </g>
              );
            })}

            {/* EMA lines */}
            {[
              { vals: e50, col: '#a78bfa', lbl: 'EMA50' },
              { vals: e20, col: '#fbbf24', lbl: 'EMA20' },
              { vals: e9, col: '#22d3ee', lbl: 'EMA9' },
            ].map((line, idx) => {
              if (line.vals.length === 0) return null;
              const d = line.vals
                .map((v, i) => `${i ? 'L' : 'M'}${xOf(i).toFixed(1)},${yOf(v).toFixed(1)}`)
                .join(' ');
              const lastY = yOf(line.vals[line.vals.length - 1]);
              return (
                <g key={idx}>
                  <path d={d} fill="none" stroke={line.col} strokeWidth="1.25" />
                  <text
                    x={W - padR + 6}
                    y={lastY + 3}
                    fill={line.col}
                    fontSize="9"
                    fontFamily="var(--font-mono)"
                  >
                    {line.lbl}
                  </text>
                </g>
              );
            })}

            {/* Structure markers */}
            {smc.structure.map((s, idx) => {
              const i1 = tIdx(s.t);
              if (i1 === -1) return null;
              return (
                <g key={'st' + idx}>
                  <line
                    x1={xOf(i1)}
                    y1={yOf(s.px)}
                    x2={xOf(Math.min(view.length - 1, i1 + 30))}
                    y2={yOf(s.px)}
                    stroke={s.dir === 'up' ? 'var(--up)' : 'var(--down)'}
                    strokeWidth="1.2"
                    strokeDasharray="0"
                    opacity="0.7"
                  />
                  <text
                    x={xOf(i1) + 4}
                    y={yOf(s.px) - 4}
                    fill={s.dir === 'up' ? 'var(--up)' : 'var(--down)'}
                    fontSize="9"
                    fontWeight="700"
                    fontFamily="var(--font-mono)"
                  >
                    {s.kind}
                  </text>
                </g>
              );
            })}

            {/* Position markers */}
            {activePosition && <PositionMarkers pos={activePosition} W={W} padR={padR} yOf={yOf} />}

            {/* Crosshair */}
            {hover && (
              <g pointerEvents="none">
                <line
                  x1={xOf(hover.i)}
                  y1={padT}
                  x2={xOf(hover.i)}
                  y2={H - padB}
                  stroke="var(--ink-3)"
                  strokeDasharray="2 2"
                  opacity="0.5"
                />
                <line
                  x1={padL}
                  y1={yOf(hover.px)}
                  x2={W - padR}
                  y2={yOf(hover.px)}
                  stroke="var(--ink-3)"
                  strokeDasharray="2 2"
                  opacity="0.5"
                />
                <rect x={W - padR} y={yOf(hover.px) - 8} width={padR} height={16} fill="var(--accent)" />
                <text
                  x={W - padR + 6}
                  y={yOf(hover.px) + 3}
                  fill="var(--accent-ink)"
                  fontSize="10"
                  fontFamily="var(--font-mono)"
                  fontWeight="700"
                >
                  {hover.px.toFixed(2)}
                </text>
              </g>
            )}

            {/* Last price */}
            {last && (
              <line
                x1={padL}
                y1={yOf(last.close)}
                x2={W - padR}
                y2={yOf(last.close)}
                stroke="var(--accent)"
                strokeDasharray="2 4"
                opacity="0.7"
              />
            )}
          </svg>
        )}
        {hover && view[hover.i] && <ChartLegend hover={hover} c={view[hover.i]} />}
      </div>
    </div>
  );
}

function PositionMarkers({
  pos,
  W,
  padR,
  yOf,
}: {
  pos: Position;
  W: number;
  padR: number;
  yOf: (p: number) => number;
}) {
  const mk = (px: number, label: string, col: string, dash: string) => (
    <g>
      <line x1={0} y1={yOf(px)} x2={W - padR} y2={yOf(px)} stroke={col} strokeDasharray={dash} strokeWidth="1" opacity="0.6" />
      <rect x={6} y={yOf(px) - 8} width={62} height={14} fill={col} opacity="0.92" rx="2" />
      <text
        x={37}
        y={yOf(px) + 3}
        fontSize="9"
        fontFamily="var(--font-mono)"
        fontWeight="700"
        textAnchor="middle"
        fill={col === 'var(--up)' ? 'var(--up-deep)' : col === 'var(--down)' ? 'var(--down-deep)' : 'white'}
      >
        {label} {px.toFixed(2)}
      </text>
    </g>
  );
  return (
    <g>
      {mk(pos.entry, `ENTRY ×${pos.qty}`, 'var(--ink-1)', '0')}
      {mk(pos.sl, 'SL', 'var(--down)', '4 3')}
      {mk(pos.tp1, 'TP1', 'var(--up)', '4 3')}
      {mk(pos.tp2, 'TP2', 'var(--up)', '4 3')}
    </g>
  );
}

function ChartHeader({ symbol, tf, last }: { symbol: string; tf: string; last: Candle | undefined }) {
  return (
    <div
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 12,
        padding: '8px 16px',
        borderBottom: '1px solid var(--line)',
      }}
    >
      <span style={{ fontWeight: 700, fontSize: 14, color: 'var(--ink-0)' }}>{symbol}</span>
      <span
        style={{
          fontSize: 10,
          color: 'var(--ink-3)',
          letterSpacing: '0.06em',
          textTransform: 'uppercase',
        }}
      >
        · {tf} · CME
      </span>
      {last && (
        <div style={{ display: 'flex', gap: 14, marginLeft: 8, fontFamily: 'var(--font-mono)', fontSize: 11 }}>
          <span>
            <span className="muted" style={{ marginRight: 4 }}>O</span>
            <span>{last.open.toFixed(2)}</span>
          </span>
          <span>
            <span className="muted" style={{ marginRight: 4 }}>H</span>
            <span>{last.high.toFixed(2)}</span>
          </span>
          <span>
            <span className="muted" style={{ marginRight: 4 }}>L</span>
            <span>{last.low.toFixed(2)}</span>
          </span>
          <span>
            <span className="muted" style={{ marginRight: 4 }}>C</span>
            <span className={last.close >= last.open ? 'up' : 'down'}>{last.close.toFixed(2)}</span>
          </span>
          <span>
            <span className="muted" style={{ marginRight: 4 }}>V</span>
            <span>{(last.volume / 1000).toFixed(1)}K</span>
          </span>
        </div>
      )}
      <div style={{ flex: 1 }} />
      <div style={{ display: 'flex', gap: 6 }}>
        <Chip kind="accent">EMAs · ON</Chip>
        <Chip kind="violet">FVG · ON</Chip>
        <Chip kind="info">VWAP · ON</Chip>
        <Chip kind="ghost">Volume profile</Chip>
      </div>
      <button type="button" className="btn btn-sm">
        Indicators
      </button>
      <button type="button" className="btn btn-sm btn-ghost">
        ⛶
      </button>
    </div>
  );
}

function ChartLegend({ hover, c }: { hover: { i: number; px: number }; c: Candle }) {
  void hover;
  const t = new Date(c.time * 1000);
  return (
    <div
      style={{
        position: 'absolute',
        top: 12,
        left: 12,
        background: 'color-mix(in oklch, var(--s1) 92%, transparent)',
        border: '1px solid var(--line)',
        borderRadius: 4,
        padding: '6px 10px',
        fontFamily: 'var(--font-mono)',
        fontSize: 10,
        lineHeight: 1.5,
        backdropFilter: 'blur(6px)',
      }}
    >
      <div className="muted">
        {t.toLocaleString('en-US', { month: 'short', day: '2-digit', hour: '2-digit', minute: '2-digit' })}
      </div>
      <div>
        O <span style={{ color: 'var(--ink-1)' }}>{c.open.toFixed(2)}</span> · H{' '}
        <span style={{ color: 'var(--ink-1)' }}>{c.high.toFixed(2)}</span>
      </div>
      <div>
        L <span style={{ color: 'var(--ink-1)' }}>{c.low.toFixed(2)}</span> · C{' '}
        <span className={c.close >= c.open ? 'up' : 'down'}>{c.close.toFixed(2)}</span>
      </div>
      <div>
        Δ <span className={c.delta >= 0 ? 'up' : 'down'}>
          {c.delta >= 0 ? '+' : ''}
          {c.delta}
        </span>{' '}
        · V {c.volume}
      </div>
    </div>
  );
}
