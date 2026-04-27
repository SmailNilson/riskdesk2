'use client';

import { Fragment } from 'react';
import {
  BarGauge,
  Chip,
  Heatcell,
  Histogram,
  Panel,
  RRLadder,
  Sparkline,
  StatusDot,
} from '../lib/atoms';
import {
  Correlations,
  DxyData,
  Indicators,
  PlaybookEntry,
  Position,
  Strategy,
} from '../lib/data';
import { OrderFlowProdPanel } from './OrderFlowProd';
import { OrderFlowProd } from '../lib/data';

function StrategyPanel({ s }: { s: Strategy }) {
  return (
    <Panel title="Strategy · Regime" right={<Chip kind="accent">{s.regime}</Chip>}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 10, color: 'var(--ink-3)' }}>Confidence</span>
        <BarGauge value={s.confidence} />
        <span className="mono" style={{ fontSize: 11, color: 'var(--ink-1)' }}>
          {Math.round(s.confidence * 100)}%
        </span>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: 6 }}>
        {s.factors.map((f, i) => (
          <div
            key={i}
            style={{
              display: 'grid',
              gridTemplateColumns: '120px 1fr 100px',
              alignItems: 'center',
              gap: 8,
            }}
          >
            <span style={{ fontSize: 11, color: 'var(--ink-2)' }}>{f.name}</span>
            <BarGauge
              value={f.v}
              color={f.v >= 0.6 ? 'var(--up)' : f.v >= 0.4 ? 'var(--warn)' : 'var(--down)'}
            />
            <span
              className="mono"
              style={{ fontSize: 10, color: 'var(--ink-3)', textAlign: 'right' }}
            >
              {f.label}
            </span>
          </div>
        ))}
      </div>
      <div
        style={{
          padding: 8,
          background: 'var(--accent-glow)',
          border: '1px solid color-mix(in oklch, var(--accent) 30%, transparent)',
          borderRadius: 4,
          fontSize: 11,
          color: 'var(--ink-1)',
        }}
      >
        <strong style={{ color: 'var(--accent)' }}>→</strong> {s.recommendation}
      </div>
    </Panel>
  );
}

function IndicatorsPanel({ i, tf }: { i: Indicators; tf: string }) {
  const Row = ({
    k,
    v,
    sub,
    tone,
  }: {
    k: string;
    v: React.ReactNode;
    sub?: React.ReactNode;
    tone?: string;
  }) => (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'center',
        fontSize: 11,
        padding: '3px 0',
      }}
    >
      <span style={{ color: 'var(--ink-2)' }}>{k}</span>
      <span className="mono" style={{ color: tone || 'var(--ink-1)' }}>
        {v}{' '}
        {sub && (
          <span className="muted" style={{ marginLeft: 4 }}>
            {sub}
          </span>
        )}
      </span>
    </div>
  );
  return (
    <Panel title={`Indicators · MCL ${tf}`}>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '0 14px' }}>
        <div>
          <Row k="EMA 9" v={i.ema9.v} sub="↑ above" tone="var(--up)" />
          <Row k="EMA 50" v={i.ema50.v} sub="↑ above" tone="var(--up)" />
          <Row k="EMA 200" v={i.ema200.v} sub="↑ above" tone="var(--up)" />
          <Row k="VWAP" v={i.vwap.v} sub={`+${i.vwap.dev.toFixed(2)}`} tone="var(--up)" />
          <Row k="SuperT" v="↑" sub={`flip ${i.supertrend.flipped} bars`} tone="var(--up)" />
          <Row
            k="CMF"
            v={`${i.cmf.v >= 0 ? '+' : ''}${i.cmf.v.toFixed(2)}`}
            sub="buying"
            tone={i.cmf.v >= 0 ? 'var(--up)' : 'var(--down)'}
          />
        </div>
        <div>
          <Row k="ATR" v={i.atr.v} sub={`${Math.round(i.atr.pctRange * 100)}p`} />
          <Row k="BB w" v={i.bb.width.toFixed(2)} sub="normal" />
          <Row k="Pivot" v={i.pivots.p} />
          <Row k="R1/R2" v={`${i.pivots.r1} · ${i.pivots.r2}`} />
          <Row k="S1/S2" v={`${i.pivots.s1} · ${i.pivots.s2}`} />
          <Row k="Cloud" v="above" tone="var(--up)" />
        </div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, marginTop: 4 }}>
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ fontSize: 10, color: 'var(--ink-3)' }}>RSI 14</span>
            <span className="mono" style={{ fontSize: 10, color: 'var(--ink-1)' }}>
              {i.rsi.v.toFixed(1)}
            </span>
          </div>
          <BarGauge
            value={i.rsi.v}
            max={100}
            marker={0.5}
            zones={[
              { from: 0, to: 0.3, color: 'color-mix(in oklch, var(--down) 20%, transparent)' },
              { from: 0.7, to: 1, color: 'color-mix(in oklch, var(--up) 20%, transparent)' },
            ]}
          />
        </div>
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between' }}>
            <span style={{ fontSize: 10, color: 'var(--ink-3)' }}>MACD hist</span>
            <Chip kind="up">{i.macd.cross}</Chip>
          </div>
          <Histogram values={i.macd.hist} w={140} h={22} />
        </div>
      </div>
    </Panel>
  );
}

function DXYPanel({ d }: { d: DxyData }) {
  return (
    <Panel
      title="DXY · USD Index"
      right={
        <span
          className={d.chg >= 0 ? 'up mono' : 'down mono'}
          style={{ fontSize: 11 }}
        >
          {d.chg >= 0 ? '+' : ''}
          {d.chg.toFixed(2)} ({d.chgPct >= 0 ? '+' : ''}
          {d.chgPct.toFixed(2)}%)
        </span>
      }
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
        <span
          className="mono"
          style={{
            fontSize: 22,
            color: 'var(--ink-0)',
            fontWeight: 600,
            fontVariantNumeric: 'tabular-nums',
          }}
        >
          {d.px.toFixed(2)}
        </span>
        <Sparkline values={d.series} color={d.chg < 0 ? 'var(--down)' : 'var(--up)'} w={140} h={36} />
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <Chip kind={d.chg < 0 ? 'down' : 'up'}>{d.bias}</Chip>
        <span style={{ fontSize: 11, color: 'var(--ink-2)' }}>{d.effect}</span>
      </div>
    </Panel>
  );
}

function PlaybookPanel({ entries }: { entries: PlaybookEntry[] }) {
  return (
    <Panel title="Playbook" right={<button type="button" className="btn btn-sm btn-ghost">+ New</button>}>
      {entries.map((p) => (
        <div
          key={p.id}
          style={{
            display: 'grid',
            gridTemplateColumns: '1fr auto auto',
            gap: 10,
            alignItems: 'center',
            padding: '6px 0',
            borderBottom: '1px solid var(--line)',
          }}
        >
          <div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <StatusDot kind={p.active ? 'up' : 'down'} />
              <span
                style={{
                  fontSize: 12,
                  color: p.active ? 'var(--ink-0)' : 'var(--ink-3)',
                  fontWeight: 500,
                }}
              >
                {p.name}
              </span>
              <Chip kind="ghost">{p.tf}</Chip>
            </div>
            <div style={{ display: 'flex', gap: 4, marginTop: 4, flexWrap: 'wrap' }}>
              {p.tags.map((t) => (
                <span
                  key={t}
                  style={{
                    fontSize: 9,
                    color: 'var(--ink-3)',
                    letterSpacing: '0.04em',
                    textTransform: 'uppercase',
                  }}
                >
                  {t}
                </span>
              ))}
            </div>
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end' }}>
            <span
              className="mono"
              style={{
                fontSize: 12,
                color:
                  p.winRate >= 0.6
                    ? 'var(--up)'
                    : p.winRate >= 0.5
                    ? 'var(--ink-1)'
                    : 'var(--down)',
                fontWeight: 600,
              }}
            >
              {Math.round(p.winRate * 100)}%
            </span>
            <span className="mono muted" style={{ fontSize: 10 }}>
              {p.trades}t
            </span>
          </div>
          <span
            className="mono muted"
            style={{ fontSize: 10, minWidth: 80, textAlign: 'right' }}
          >
            {p.lastHit}
          </span>
        </div>
      ))}
    </Panel>
  );
}

function CorrelationsPanel({ c }: { c: Correlations }) {
  return (
    <Panel title="Correlations · 30d" subtitle="rolling">
      <div
        className="matrix"
        style={{ gridTemplateColumns: `60px repeat(${c.cols.length}, 1fr)` }}
      >
        <div className="h"></div>
        {c.cols.map((col) => (
          <div key={col} className="h">
            {col}
          </div>
        ))}
        {c.rows.map((row, ri) => (
          <Fragment key={row}>
            <div className="h" style={{ background: 'var(--s2)', textAlign: 'left', paddingLeft: 6 }}>
              {row}
            </div>
            {c.cols.map((col, ci) => (
              <Heatcell key={col} v={c.data[ri][ci]} w="auto" h={26} />
            ))}
          </Fragment>
        ))}
      </div>
    </Panel>
  );
}

function PositionsPanel({ positions }: { positions: Position[] }) {
  return (
    <Panel
      title="Open Positions"
      right={<Chip kind="accent">{positions.length} open</Chip>}
    >
      {positions.map((p) => {
        const pnlPts = p.side === 'long' ? p.last - p.entry : p.entry - p.last;
        return (
          <div
            key={p.id}
            style={{
              background: 'var(--s2)',
              border: '1px solid var(--line)',
              borderRadius: 4,
              padding: 10,
              display: 'flex',
              flexDirection: 'column',
              gap: 6,
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Chip kind={p.side === 'long' ? 'up' : 'down'}>{p.side.toUpperCase()}</Chip>
              <span style={{ fontWeight: 700, color: 'var(--ink-0)' }}>
                {p.sym} ×{p.qty}
              </span>
              <span className="mono muted" style={{ fontSize: 10 }}>
                @ {p.entry}
              </span>
              <span style={{ flex: 1 }} />
              <span
                className={pnlPts >= 0 ? 'up mono' : 'down mono'}
                style={{ fontWeight: 600, fontSize: 12 }}
              >
                {pnlPts >= 0 ? '+' : ''}
                {pnlPts.toFixed(p.sym === 'E6' ? 4 : 2)}
              </span>
            </div>
            <RRLadder side={p.side} entry={p.entry} sl={p.sl} tp1={p.tp1} tp2={p.tp2} last={p.last} />
            <div style={{ display: 'flex', gap: 8, fontSize: 10, color: 'var(--ink-3)' }}>
              <span>opened {p.opened}</span>
              <span>·</span>
              <span>
                trail: <span style={{ color: 'var(--ink-1)' }}>{p.trail}</span>
              </span>
              <span>·</span>
              <span style={{ color: p.state === 'tp1-hit' ? 'var(--up)' : 'var(--ink-2)' }}>{p.state}</span>
              <span style={{ flex: 1 }} />
              <button type="button" className="btn btn-sm btn-ghost" style={{ height: 18, fontSize: 10 }}>
                Manage
              </button>
              <button type="button" className="btn btn-sm" style={{ height: 18, fontSize: 10 }}>
                Close
              </button>
            </div>
          </div>
        );
      })}
    </Panel>
  );
}

function TradeDecisionPanel({ tf }: { tf: string }) {
  return (
    <Panel
      title={`Trade Decision · MCL ${tf}`}
      right={<Chip kind="up">eligible</Chip>}
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 10 }}>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)', marginBottom: 4 }}>Bias</div>
          <Chip kind="up">LONG</Chip>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)', marginBottom: 4 }}>Setup score</div>
          <BarGauge value={0.78} />
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)', marginBottom: 4 }}>RR target</div>
          <span className="mono" style={{ fontSize: 12, color: 'var(--up)', fontWeight: 600 }}>
            2.11R
          </span>
        </div>
      </div>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginTop: 4 }}>
        <div className="kv">
          <div className="k">Entry trigger</div>
          <div className="v">break 78.40</div>
          <div className="k">Stop</div>
          <div className="v down">78.18 (−22t)</div>
          <div className="k">Target 1</div>
          <div className="v up">78.58 (+22t)</div>
          <div className="k">Target 2</div>
          <div className="v up">78.74 (+38t)</div>
        </div>
        <div className="kv">
          <div className="k">Risk $</div>
          <div className="v">$66</div>
          <div className="k">Reward $ (T2)</div>
          <div className="v">$152</div>
          <div className="k">Position size</div>
          <div className="v">3 contracts</div>
          <div className="k">Cool-down</div>
          <div className="v">4m if invalid</div>
        </div>
      </div>
      <div style={{ display: 'flex', gap: 6 }}>
        <button type="button" className="btn btn-up" style={{ flex: 1 }}>
          Arm Long ×3
        </button>
        <button type="button" className="btn">
          Edit
        </button>
        <button type="button" className="btn btn-ghost">
          Pass
        </button>
      </div>
    </Panel>
  );
}

interface SetupViewProps {
  strategy: Strategy;
  indicators: Indicators;
  positions: Position[];
  playbook: PlaybookEntry[];
  dxy: DxyData;
  correlations: Correlations;
  orderflowProd: OrderFlowProd;
  tf: string;
}

export function SetupView({
  strategy,
  indicators,
  positions,
  playbook,
  dxy,
  correlations,
  orderflowProd,
  tf,
}: SetupViewProps) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, padding: 12 }}>
      <StrategyPanel s={strategy} />
      <IndicatorsPanel i={indicators} tf={tf} />
      <PositionsPanel positions={positions} />
      <TradeDecisionPanel tf={tf} />
      <div style={{ gridColumn: '1 / -1' }}>
        <OrderFlowProdPanel data={orderflowProd} />
      </div>
      <PlaybookPanel entries={playbook} />
      <DXYPanel d={dxy} />
      <div style={{ gridColumn: '1 / -1' }}>
        <CorrelationsPanel c={correlations} />
      </div>
    </div>
  );
}
