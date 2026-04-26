'use client';

import { ReactNode } from 'react';
import { BarGauge, Chip, Delta, KV, Panel, Sparkline } from '../lib/atoms';
import { Instrument, Position } from '../lib/data';
import { useRiskDeskData } from '../lib/RiskDeskContext';

interface ContextRailProps {
  instrument: Instrument;
  collapsed: boolean;
  setCollapsed: (v: boolean) => void;
}

export function ContextRail({ instrument, collapsed, setCollapsed }: ContextRailProps) {
  const D = useRiskDeskData();
  const m = D.macro;
  const ind = D.indicators;
  const pos = D.positions;
  const bt = D.backtest;

  if (collapsed) {
    return (
      <aside
        style={{
          width: 38,
          flexShrink: 0,
          borderRight: '1px solid var(--hair)',
          background: 'var(--bg-1)',
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          padding: '10px 0',
          gap: 14,
        }}
      >
        <button
          type="button"
          onClick={() => setCollapsed(false)}
          title="Expand context"
          style={{
            background: 'transparent',
            border: '1px solid var(--hair)',
            borderRadius: 4,
            color: 'var(--fg-2)',
            padding: '4px 6px',
            cursor: 'pointer',
          }}
        >
          ›
        </button>
        <span
          style={{
            writingMode: 'vertical-rl',
            transform: 'rotate(180deg)',
            fontSize: 9.5,
            letterSpacing: '0.18em',
            color: 'var(--fg-3)',
            textTransform: 'uppercase',
            fontWeight: 600,
          }}
        >
          Context
        </span>
      </aside>
    );
  }

  return (
    <aside
      style={{
        width: 320,
        flexShrink: 0,
        borderRight: '1px solid var(--hair)',
        background: 'var(--bg-0)',
        overflowY: 'auto',
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
      }}
    >
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <span className="label">Context</span>
        <button
          type="button"
          onClick={() => setCollapsed(true)}
          style={{
            background: 'transparent',
            border: '1px solid var(--hair)',
            borderRadius: 4,
            color: 'var(--fg-2)',
            padding: '2px 6px',
            cursor: 'pointer',
            fontSize: 11,
          }}
        >
          ‹
        </button>
      </div>

      {/* DXY card */}
      <Panel eyebrow="MACRO" title="Synthetic DXY" dense>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div>
            <div className="num" style={{ fontSize: 22, fontWeight: 600, letterSpacing: '-0.02em' }}>
              {m.dxy.price.toFixed(3)}
            </div>
            <Delta value={m.dxy.chgPct} suffix="%" decimals={2} size={11} />
            <span style={{ fontSize: 10, color: 'var(--fg-3)', marginLeft: 6 }}>24h</span>
          </div>
          <Sparkline data={m.dxy.sparkline} width={110} height={36} tone={m.dxy.trend === 'DOWN' ? 'neg' : 'pos'} />
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
          <Chip tone="info" mono>
            VIX 14.82 −1.4%
          </Chip>
          <Chip tone="warn" mono>
            10Y 4.214 +0.6%
          </Chip>
          <Chip tone="mute" mono>
            Silver 28.42 +0.31%
          </Chip>
        </div>
        <div style={{ marginTop: 8, fontSize: 10.5, color: 'var(--fg-2)' }}>
          DXY rolling lower → constructive for {instrument.sym} long bias.
        </div>
      </Panel>

      {/* Indicators */}
      <Panel
        eyebrow="SIGNALS"
        title={`Indicators · ${instrument.sym} · 10m`}
        dense
        right={
          <Chip tone="accent" mono soft>
            {ind.regime}
          </Chip>
        }
      >
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 14px' }}>
          <KV label="EMA 9" value={ind.ema9.toFixed(2)} tone={ind.ema9 > ind.ema50 ? 'pos' : 'neg'} />
          <KV label="EMA 50" value={ind.ema50.toFixed(2)} tone={ind.ema50 > ind.ema200 ? 'pos' : 'neg'} />
          <KV label="EMA 200" value={ind.ema200.toFixed(2)} />
          <KV label="VWAP" value={ind.vwap.toFixed(2)} tone="warn" />
          <KV label="Super" value={ind.superTrend.value.toFixed(2)} tone={ind.superTrend.side === 'bull' ? 'pos' : 'neg'} />
          <KV label="CMF" value={(ind.cmf >= 0 ? '+' : '') + ind.cmf.toFixed(2)} tone={ind.cmf > 0 ? 'pos' : 'neg'} />
        </div>
        <div style={{ marginTop: 10 }}>
          <BarGauge value={ind.rsi} lo={33} hi={60} label="RSI 14" decimals={1} />
          <div style={{ height: 8 }} />
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
            <span className="label">MACD</span>
            <span
              className="num"
              style={{
                fontSize: 11,
                color: ind.macdHist >= 0 ? 'var(--pos)' : 'var(--neg)',
                fontWeight: 600,
              }}
            >
              {ind.macdHist >= 0 ? '+' : '−'}
              {Math.abs(ind.macdHist).toFixed(3)}
            </span>
          </div>
          <MacdHist hist={D.macd.hist.slice(-32)} />
        </div>
      </Panel>

      {/* Positions */}
      <Panel
        eyebrow="BOOK"
        title="Open Positions"
        dense
        right={
          <Chip tone="mute" mono>
            {pos.length} open
          </Chip>
        }
      >
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {pos.map((p) => (
            <div
              key={p.id}
              style={{
                border: '1px solid var(--hair)',
                borderRadius: 6,
                padding: 9,
                background: 'var(--bg-1)',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6 }}>
                <Chip tone={p.side === 'LONG' ? 'pos' : 'neg'} soft mono>
                  {p.side}
                </Chip>
                <span className="num" style={{ fontSize: 12, fontWeight: 700 }}>
                  {p.instrument}
                </span>
                <span style={{ fontSize: 10.5, color: 'var(--fg-3)' }}>×{p.qty}</span>
                <span style={{ flex: 1 }} />
                <Delta value={p.pnl} prefix="$" size={12} />
              </div>
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 6, fontSize: 10 }}>
                <CellMini label="Entry" value={p.entry.toFixed(2)} />
                <CellMini label="SL" value={p.sl.toFixed(2)} color="var(--neg)" />
                <CellMini label="TP" value={p.tp.toFixed(2)} color="var(--pos)" />
              </div>
              <PosProgress p={p} />
            </div>
          ))}
        </div>
      </Panel>

      {/* Backtest mini */}
      <Panel eyebrow="STATS" title="30D Backtest" dense>
        <div
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'flex-end',
            marginBottom: 6,
          }}
        >
          <div>
            <div className="num" style={{ fontSize: 18, fontWeight: 600 }}>
              {(bt.winRate * 100).toFixed(1)}%
            </div>
            <span className="label">Win Rate</span>
          </div>
          <Sparkline data={bt.curve} width={130} height={32} tone="pos" />
        </div>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 8, marginTop: 6 }}>
          <Stat label="Avg R:R" value={bt.avgRR.toFixed(2)} />
          <Stat label="Profit F." value={bt.pf.toFixed(2)} />
          <Stat label="Trades" value={String(bt.trades30d)} />
        </div>
      </Panel>

      <div style={{ height: 60 }} />
    </aside>
  );
}

function CellMini({ label, value, color }: { label: string; value: ReactNode; color?: string }) {
  return (
    <div>
      <div className="label" style={{ fontSize: 8.5 }}>
        {label}
      </div>
      <div className="num" style={{ color }}>
        {value}
      </div>
    </div>
  );
}

function MacdHist({ hist }: { hist: number[] }) {
  const max = Math.max(...hist.map(Math.abs));
  const w = 280;
  const h = 28;
  const bw = w / hist.length;
  return (
    <svg width="100%" height={h} viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none">
      <line x1="0" x2={w} y1={h / 2} y2={h / 2} stroke="var(--hair-soft)" strokeWidth="0.5" />
      {hist.map((v, i) => {
        const bh = (Math.abs(v) / max) * (h / 2 - 1);
        const y = v >= 0 ? h / 2 - bh : h / 2;
        return (
          <rect
            key={i}
            x={i * bw + 0.4}
            y={y}
            width={Math.max(0.8, bw - 0.8)}
            height={bh}
            fill={v >= 0 ? 'var(--pos)' : 'var(--neg)'}
            opacity={0.85}
          />
        );
      })}
    </svg>
  );
}

function PosProgress({ p }: { p: Position }) {
  const range = p.side === 'LONG' ? p.tp - p.sl : p.sl - p.tp;
  const cur = p.side === 'LONG' ? p.current - p.sl : p.sl - p.current;
  const ent = p.side === 'LONG' ? p.entry - p.sl : p.sl - p.entry;
  const pctC = Math.max(0, Math.min(1, cur / range));
  const pctE = Math.max(0, Math.min(1, ent / range));
  return (
    <div style={{ marginTop: 8 }}>
      <div style={{ position: 'relative', height: 4, background: 'var(--bg-2)', borderRadius: 2 }}>
        <div
          style={{
            position: 'absolute',
            left: 0,
            top: 0,
            bottom: 0,
            width: `${pctC * 100}%`,
            background: pctC > pctE ? 'var(--pos)' : 'var(--neg)',
            opacity: 0.6,
          }}
        />
        <div
          style={{
            position: 'absolute',
            left: `${pctE * 100}%`,
            top: -2,
            bottom: -2,
            width: 1,
            background: 'var(--fg-1)',
          }}
        />
        <div
          style={{
            position: 'absolute',
            left: `calc(${pctC * 100}% - 3px)`,
            top: -3,
            width: 6,
            height: 10,
            borderRadius: 1,
            background: 'var(--accent)',
          }}
        />
      </div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          marginTop: 3,
          fontSize: 9,
          color: 'var(--fg-3)',
          fontFamily: 'var(--font-mono)',
        }}
      >
        <span>SL</span>
        <span>Entry</span>
        <span>TP</span>
      </div>
    </div>
  );
}

function Stat({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="label" style={{ marginBottom: 2 }}>
        {label}
      </div>
      <div className="num" style={{ fontSize: 12, fontWeight: 600 }}>
        {value}
      </div>
    </div>
  );
}
