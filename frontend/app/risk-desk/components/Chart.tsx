'use client';

import { CSSProperties, useEffect, useRef, useState } from 'react';
import { Chip, Panel } from '../lib/atoms';
import { Instrument } from '../lib/data';
import { useRiskDeskData } from '../lib/RiskDeskContext';

interface ChartProps {
  instrument: Instrument;
  timeframe: string;
  livePrice: number;
}

interface HoverState {
  i: number;
  x: number;
  y: number;
  mx: number;
}

export function Chart({ instrument, timeframe, livePrice }: ChartProps) {
  const D = useRiskDeskData();
  const candles = D.candles;
  const [hover, setHover] = useState<HoverState | null>(null);
  const [showOB, setShowOB] = useState(true);
  const [showStruct, setShowStruct] = useState(true);
  const [showFvg, setShowFvg] = useState(true);
  const [showEMA, setShowEMA] = useState(true);
  const [showVWAP, setShowVWAP] = useState(true);
  const wrapRef = useRef<HTMLDivElement | null>(null);
  const [size, setSize] = useState({ w: 900, h: 460 });

  useEffect(() => {
    const el = wrapRef.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => {
      setSize({ w: e.contentRect.width, h: e.contentRect.height });
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const W = size.w;
  const H = size.h;
  const padL = 8;
  const padR = 60;
  const padT = 16;
  const padB = 56;
  const volH = 56;
  const chartH = H - padT - padB - volH - 4;

  const minP = Math.min(...candles.map((c) => c.l));
  const maxP = Math.max(...candles.map((c) => c.h));
  const padP = (maxP - minP) * 0.06;
  const pMin = minP - padP;
  const pMax = maxP + padP;
  const span = pMax - pMin;

  const cw = (W - padL - padR) / candles.length;
  const candleW = Math.max(2, cw * 0.66);
  const xOf = (i: number) => padL + i * cw + cw / 2;
  const yOf = (p: number) => padT + (1 - (p - pMin) / span) * chartH;

  const maxV = Math.max(...candles.map((c) => c.v));
  const yV = (v: number) => padT + chartH + 4 + (1 - v / maxV) * volH;

  const onMove = (e: React.MouseEvent<HTMLDivElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const x = e.clientX - rect.left;
    const y = e.clientY - rect.top;
    const i = Math.max(0, Math.min(candles.length - 1, Math.floor((x - padL) / cw)));
    setHover({ i, x: xOf(i), y, mx: x });
  };
  const onLeave = () => setHover(null);

  const last = candles[candles.length - 1];
  const lastY = yOf(livePrice ?? last.c);

  const ticks = 6;
  const yTicks = Array.from({ length: ticks }).map((_, i) => {
    const p = pMin + (span * i) / (ticks - 1);
    return { p, y: yOf(p) };
  });

  const emaSeries = [
    { d: D.ema9, color: 'var(--accent)', w: 1.2, label: 'EMA 9' },
    { d: D.ema50, color: 'var(--info)', w: 1.0, label: 'EMA 50' },
    { d: D.ema200, color: 'var(--vio)', w: 1.0, label: 'EMA 200' },
  ];

  return (
    <Panel
      eyebrow="LIVE"
      title={`${instrument.sym} · ${timeframe}`}
      right={
        <div style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 10 }}>
          <Toggle on={showEMA} onClick={() => setShowEMA((v) => !v)} label="EMA" />
          <Toggle on={showVWAP} onClick={() => setShowVWAP((v) => !v)} label="VWAP" />
          <Toggle on={showOB} onClick={() => setShowOB((v) => !v)} label="OB" />
          <Toggle on={showStruct} onClick={() => setShowStruct((v) => !v)} label="STRUCT" />
          <Toggle on={showFvg} onClick={() => setShowFvg((v) => !v)} label="FVG" />
          <span style={{ width: 1, height: 14, background: 'var(--hair)', margin: '0 2px' }} />
          <Chip tone="info" mono>
            RTH
          </Chip>
        </div>
      }
      style={{ flex: '1 1 auto', display: 'flex', flexDirection: 'column', minHeight: 0 }}
    >
      <div
        ref={wrapRef}
        style={{ position: 'relative', width: '100%', height: 480, marginTop: -4 }}
        onMouseMove={onMove}
        onMouseLeave={onLeave}
      >
        <svg width={W} height={H} style={{ display: 'block' }}>
          {/* Grid */}
          {yTicks.map((t, i) => (
            <line
              key={i}
              x1={padL}
              x2={W - padR}
              y1={t.y}
              y2={t.y}
              stroke="var(--hair-soft)"
              strokeDasharray="2 4"
              strokeWidth="0.6"
            />
          ))}

          {/* FVG zones */}
          {showFvg &&
            D.fvgZones.map((z, i) => (
              <rect
                key={`fvg-${i}`}
                x={xOf(z.from) - cw / 2}
                width={(z.to - z.from + 1) * cw + cw * 4}
                y={yOf(z.top)}
                height={yOf(z.bot) - yOf(z.top)}
                fill="var(--info)"
                opacity="0.10"
                stroke="var(--info)"
                strokeOpacity="0.35"
                strokeDasharray="3 3"
                strokeWidth="0.7"
              />
            ))}

          {/* Order blocks */}
          {showOB &&
            D.orderBlocks.map((ob, i) => {
              const tone = ob.side === 'bull' ? 'var(--pos)' : 'var(--neg)';
              const opacity = ob.strength === 'strong' ? 0.18 : ob.strength === 'weak' ? 0.10 : 0.07;
              return (
                <g key={`ob-${i}`}>
                  <rect
                    x={xOf(ob.from) - cw / 2}
                    width={W - padR - (xOf(ob.from) - cw / 2)}
                    y={yOf(ob.top)}
                    height={yOf(ob.bot) - yOf(ob.top)}
                    fill={tone}
                    opacity={opacity}
                    stroke={tone}
                    strokeOpacity="0.5"
                    strokeWidth="0.7"
                  />
                  <text
                    x={W - padR - 4}
                    y={yOf(ob.top) + 11}
                    textAnchor="end"
                    fontSize="9"
                    fill={tone}
                    fontFamily="var(--font-mono)"
                    fontWeight="600"
                  >
                    {ob.side === 'bull' ? '▲' : '▼'} OB · {ob.strength}
                  </text>
                </g>
              );
            })}

          {/* EMA lines */}
          {showEMA &&
            emaSeries.map((s, i) => {
              const path = s.d
                .map((v, j) => `${j === 0 ? 'M' : 'L'}${xOf(j).toFixed(1)},${yOf(v).toFixed(1)}`)
                .join(' ');
              return <path key={i} d={path} fill="none" stroke={s.color} strokeWidth={s.w} opacity="0.85" />;
            })}

          {/* VWAP */}
          {showVWAP && (
            <line
              x1={padL}
              x2={W - padR}
              y1={yOf(D.indicators.vwap)}
              y2={yOf(D.indicators.vwap)}
              stroke="var(--warn)"
              strokeWidth="1"
              strokeDasharray="5 3"
              opacity="0.75"
            />
          )}

          {/* Candles */}
          {candles.map((c, i) => {
            const up = c.c >= c.o;
            const color = up ? 'var(--pos)' : 'var(--neg)';
            const x = xOf(i);
            const yO = yOf(c.o);
            const yC = yOf(c.c);
            const yH = yOf(c.h);
            const yL = yOf(c.l);
            const top = Math.min(yO, yC);
            const bh = Math.max(1, Math.abs(yC - yO));
            return (
              <g key={i}>
                <line x1={x} x2={x} y1={yH} y2={yL} stroke={color} strokeWidth="1" />
                <rect x={x - candleW / 2} y={top} width={candleW} height={bh} fill={color} stroke={color} />
                <rect
                  x={x - candleW / 2}
                  y={yV(c.v)}
                  width={candleW}
                  height={padT + chartH + 4 + volH - yV(c.v)}
                  fill={color}
                  opacity="0.45"
                />
              </g>
            );
          })}

          {/* Structure markers */}
          {showStruct &&
            D.structure.map((s, i) => {
              const x = xOf(s.idx);
              const y = yOf(s.price);
              const color = s.side === 'bull' ? 'var(--pos)' : 'var(--neg)';
              return (
                <g key={`st-${i}`}>
                  <line
                    x1={x}
                    x2={W - padR}
                    y1={y}
                    y2={y}
                    stroke={color}
                    strokeDasharray="3 4"
                    strokeWidth="0.9"
                    opacity="0.7"
                  />
                  <rect
                    x={x - 24}
                    y={y - 8}
                    width={42}
                    height={16}
                    rx={3}
                    fill="var(--bg-1)"
                    stroke={color}
                    strokeWidth="0.8"
                  />
                  <text
                    x={x - 3}
                    y={y + 3}
                    textAnchor="middle"
                    fontSize="9"
                    fontFamily="var(--font-mono)"
                    fontWeight="700"
                    fill={color}
                  >
                    {s.type}
                  </text>
                </g>
              );
            })}

          {/* Live price line + label */}
          <line
            x1={padL}
            x2={W - padR}
            y1={lastY}
            y2={lastY}
            stroke="var(--accent)"
            strokeWidth="0.9"
            strokeDasharray="2 3"
            opacity="0.9"
          />
          <rect x={W - padR + 2} y={lastY - 9} width={padR - 6} height={18} rx={3} fill="var(--accent)" />
          <text
            x={W - padR + 6}
            y={lastY + 4}
            fontSize="10.5"
            fontFamily="var(--font-mono)"
            fontWeight="700"
            fill="var(--bg-0)"
          >
            {(livePrice ?? last.c).toFixed(2)}
          </text>

          {/* Y-axis labels */}
          {yTicks.map((t, i) => (
            <text
              key={i}
              x={W - padR + 6}
              y={t.y + 3.5}
              fontSize="9.5"
              fill="var(--fg-3)"
              fontFamily="var(--font-mono)"
            >
              {t.p.toFixed(2)}
            </text>
          ))}

          {/* X-axis time labels (every 12 bars) */}
          {candles
            .filter((_, i) => i % 12 === 0)
            .map((c, idx) => {
              const i = candles.indexOf(c);
              const d = new Date(c.t);
              const lbl = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
              return (
                <text
                  key={idx}
                  x={xOf(i)}
                  y={H - 6}
                  textAnchor="middle"
                  fontSize="9"
                  fill="var(--fg-3)"
                  fontFamily="var(--font-mono)"
                >
                  {lbl}
                </text>
              );
            })}

          {/* Crosshair */}
          {hover && (
            <g pointerEvents="none">
              <line
                x1={hover.x}
                x2={hover.x}
                y1={padT}
                y2={padT + chartH + volH + 4}
                stroke="var(--fg-2)"
                strokeDasharray="2 3"
                strokeWidth="0.7"
                opacity="0.6"
              />
              <line
                x1={padL}
                x2={W - padR}
                y1={hover.y}
                y2={hover.y}
                stroke="var(--fg-2)"
                strokeDasharray="2 3"
                strokeWidth="0.7"
                opacity="0.6"
              />
              {hover.y > padT && hover.y < padT + chartH && (
                <>
                  <rect
                    x={W - padR + 2}
                    y={hover.y - 9}
                    width={padR - 6}
                    height={18}
                    rx={3}
                    fill="var(--bg-3)"
                    stroke="var(--hair)"
                  />
                  <text
                    x={W - padR + 6}
                    y={hover.y + 4}
                    fontSize="10"
                    fontFamily="var(--font-mono)"
                    fill="var(--fg-0)"
                    fontWeight="600"
                  >
                    {(pMin + (1 - (hover.y - padT) / chartH) * span).toFixed(2)}
                  </text>
                </>
              )}
            </g>
          )}
        </svg>

        {/* OHLC readout (top-left) */}
        <div
          style={{
            position: 'absolute',
            top: 6,
            left: 12,
            display: 'flex',
            gap: 14,
            fontSize: 10.5,
            fontFamily: 'var(--font-mono)',
            color: 'var(--fg-2)',
            pointerEvents: 'none',
          }}
        >
          {(() => {
            const c = hover ? candles[hover.i] : last;
            const up = c.c >= c.o;
            const tone = up ? 'var(--pos)' : 'var(--neg)';
            return (
              <>
                <span>
                  <span style={{ color: 'var(--fg-3)' }}>O</span> <span style={{ color: tone }}>{c.o.toFixed(2)}</span>
                </span>
                <span>
                  <span style={{ color: 'var(--fg-3)' }}>H</span> <span style={{ color: tone }}>{c.h.toFixed(2)}</span>
                </span>
                <span>
                  <span style={{ color: 'var(--fg-3)' }}>L</span> <span style={{ color: tone }}>{c.l.toFixed(2)}</span>
                </span>
                <span>
                  <span style={{ color: 'var(--fg-3)' }}>C</span> <span style={{ color: tone }}>{c.c.toFixed(2)}</span>
                </span>
                <span>
                  <span style={{ color: 'var(--fg-3)' }}>VOL</span>{' '}
                  <span style={{ color: 'var(--fg-1)' }}>{c.v.toLocaleString('en-US')}</span>
                </span>
                <span>
                  <span style={{ color: 'var(--fg-3)' }}>Δ</span>{' '}
                  <span style={{ color: tone }}>
                    {up ? '+' : '−'}
                    {Math.abs(c.c - c.o).toFixed(2)} ({((c.c - c.o) / c.o * 100).toFixed(2)}%)
                  </span>
                </span>
              </>
            );
          })()}
        </div>

        {/* Legend (bottom-left, above volume) */}
        <div
          style={{
            position: 'absolute',
            left: 12,
            bottom: volH + 12,
            display: 'flex',
            gap: 12,
            fontSize: 10,
            color: 'var(--fg-3)',
            fontFamily: 'var(--font-mono)',
            pointerEvents: 'none',
          }}
        >
          {showEMA && (
            <>
              <LegendDot color="var(--accent)" label="EMA 9" />
              <LegendDot color="var(--info)" label="EMA 50" />
              <LegendDot color="var(--vio)" label="EMA 200" />
            </>
          )}
          {showVWAP && <LegendDot color="var(--warn)" label="VWAP" dashed />}
        </div>

        {/* Volume label */}
        <div
          style={{
            position: 'absolute',
            left: 12,
            bottom: 6,
            fontSize: 9.5,
            color: 'var(--fg-3)',
            fontFamily: 'var(--font-mono)',
            letterSpacing: '0.06em',
          }}
        >
          VOLUME
        </div>
      </div>
    </Panel>
  );
}

function Toggle({ on, onClick, label }: { on: boolean; onClick: () => void; label: string }) {
  const style: CSSProperties = {
    padding: '3px 7px',
    fontSize: 9.5,
    fontWeight: 600,
    letterSpacing: '0.04em',
    border: '1px solid',
    borderColor: on ? 'color-mix(in oklab, var(--accent) 50%, transparent)' : 'var(--hair)',
    color: on ? 'var(--accent)' : 'var(--fg-3)',
    background: on ? 'var(--accent-bg-soft)' : 'transparent',
    borderRadius: 3,
    cursor: 'pointer',
    fontFamily: 'var(--font-mono)',
  };
  return (
    <button type="button" onClick={onClick} style={style}>
      {label}
    </button>
  );
}

function LegendDot({ color, label, dashed = false }: { color: string; label: string; dashed?: boolean }) {
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4 }}>
      <span
        style={{
          width: 14,
          height: 1.5,
          background: dashed ? 'transparent' : color,
          borderTop: dashed ? `1.5px dashed ${color}` : 'none',
        }}
      />
      <span>{label}</span>
    </span>
  );
}
