'use client';

import { Panel, SectionLabel, StatusDot } from '../lib/atoms';
import { OrderFlowProd } from '../lib/data';

function DeltaBar({ d }: { d: OrderFlowProd['delta'] }) {
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
        <span style={{ fontSize: 11, color: 'var(--ink-1)', fontWeight: 600 }}>{d.sym}</span>
        <span className="mono up" style={{ fontSize: 11, fontWeight: 600 }}>
          {d.real >= 0 ? '+' : ''}
          {d.real} REAL
        </span>
      </div>
      <div
        style={{
          display: 'flex',
          height: 18,
          borderRadius: 3,
          overflow: 'hidden',
          border: '1px solid var(--line)',
        }}
      >
        <div
          style={{
            width: `${d.buyPct * 100}%`,
            background: 'var(--up)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-start',
            paddingLeft: 8,
          }}
        >
          <span
            style={{
              fontSize: 10,
              fontFamily: 'var(--font-mono)',
              color: 'var(--up-deep)',
              fontWeight: 700,
            }}
          >
            Buy {d.buy} ({(d.buyPct * 100).toFixed(1)}%)
          </span>
        </div>
        <div
          style={{
            width: `${d.sellPct * 100}%`,
            background: 'var(--down)',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'flex-end',
            paddingRight: 8,
          }}
        >
          <span
            style={{
              fontSize: 10,
              fontFamily: 'var(--font-mono)',
              color: 'var(--down-deep)',
              fontWeight: 700,
            }}
          >
            Sell {d.sell} ({(d.sellPct * 100).toFixed(1)}%)
          </span>
        </div>
      </div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'center',
          marginTop: 3,
          fontSize: 10,
          color: 'var(--ink-3)',
          fontFamily: 'var(--font-mono)',
        }}
      >
        Cum: {d.cum}
      </div>
    </div>
  );
}

function DepthBar({ d }: { d: OrderFlowProd['depth'] }) {
  const total = d.bid + d.ask;
  const bidPct = total > 0 ? d.bid / total : 0.5;
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
        <span style={{ fontSize: 11, color: 'var(--ink-1)', fontWeight: 600 }}>{d.sym}</span>
        <span className="mono muted" style={{ fontSize: 10 }}>
          Spread: {d.spread.toFixed(2)}
        </span>
      </div>
      <div
        style={{
          position: 'relative',
          height: 14,
          borderRadius: 3,
          overflow: 'hidden',
          display: 'flex',
          border: '1px solid var(--line)',
        }}
      >
        <div
          style={{
            width: `${bidPct * 100}%`,
            background: 'linear-gradient(90deg, var(--up), color-mix(in oklch, var(--up) 30%, transparent))',
          }}
        />
        <div
          style={{
            flex: 1,
            background: 'linear-gradient(90deg, color-mix(in oklch, var(--down) 30%, transparent), var(--down))',
          }}
        />
        <div
          style={{
            position: 'absolute',
            left: `${bidPct * 100}%`,
            top: -2,
            bottom: -2,
            width: 2,
            background: 'var(--ink-0)',
            transform: 'translateX(-1px)',
          }}
        />
      </div>
      <div
        style={{
          display: 'flex',
          justifyContent: 'space-between',
          marginTop: 3,
          fontSize: 10,
          fontFamily: 'var(--font-mono)',
        }}
      >
        <span className="up">Bid: {d.bid}</span>
        <span style={{ color: 'var(--down)' }}>Imbalance: {(d.imbalance * 100).toFixed(1)}%</span>
        <span className="down">Ask: {d.ask}</span>
      </div>
    </div>
  );
}

function PhasePips({ phase, done, dir }: { phase: number; done: boolean; dir: 'BULL' | 'BEAR' }) {
  const col = dir === 'BULL' ? 'var(--up)' : 'var(--down)';
  const inkDeep = dir === 'BULL' ? 'var(--up-deep)' : 'var(--down-deep)';
  return (
    <div style={{ display: 'inline-flex', gap: 3 }}>
      {[1, 2, 3].map((p) => (
        <span
          key={p}
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            justifyContent: 'center',
            width: 18,
            height: 14,
            borderRadius: 2,
            fontSize: 9,
            fontFamily: 'var(--font-mono)',
            fontWeight: 700,
            background: p <= phase ? col : 'var(--s3)',
            color: p <= phase ? inkDeep : 'var(--ink-3)',
            opacity: p <= phase ? 1 : 0.5,
          }}
        >
          P{p}
        </span>
      ))}
      <span
        style={{
          display: 'inline-flex',
          alignItems: 'center',
          justifyContent: 'center',
          width: 30,
          height: 14,
          borderRadius: 2,
          fontSize: 9,
          fontFamily: 'var(--font-mono)',
          fontWeight: 700,
          background: done ? col : 'var(--s3)',
          color: done ? inkDeep : 'var(--ink-3)',
          opacity: done ? 1 : 0.5,
        }}
      >
        DONE
      </span>
    </div>
  );
}

function SmcCycleRow({ r }: { r: OrderFlowProd['smcCycle'][number] }) {
  const dirCol = r.dir === 'BULL' ? 'var(--up)' : 'var(--down)';
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'auto auto auto 1fr auto auto',
        alignItems: 'center',
        gap: 8,
        padding: '5px 6px',
        borderBottom: '1px solid var(--line)',
        fontSize: 11,
      }}
    >
      <span
        style={{
          background: 'var(--s3)',
          padding: '2px 6px',
          borderRadius: 2,
          fontSize: 9,
          fontFamily: 'var(--font-mono)',
          fontWeight: 700,
          color: 'var(--ink-2)',
          letterSpacing: '0.04em',
        }}
      >
        CYCLE
      </span>
      <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 600, color: 'var(--ink-1)' }}>{r.sym}</span>
      <span
        style={{
          color: dirCol,
          fontWeight: 700,
          fontFamily: 'var(--font-mono)',
          fontSize: 10,
          letterSpacing: '0.04em',
        }}
      >
        {r.dir === 'BULL' ? '▲' : '▼'} {r.dir}
      </span>
      <PhasePips phase={r.phase} done={r.done} dir={r.dir} />
      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--ink-2)' }}>
        conf:{' '}
        <span style={{ color: r.conf >= 60 ? 'var(--up)' : 'var(--ink-1)', fontWeight: 600 }}>{r.conf}</span>
        {r.note && (
          <span className="muted" style={{ marginLeft: 6 }}>
            {r.note}
          </span>
        )}
      </span>
      <span className="mono muted" style={{ fontSize: 10, minWidth: 50, textAlign: 'right' }}>
        {r.age}
      </span>
    </div>
  );
}

function DistAccumRow({ r }: { r: OrderFlowProd['distAccum'][number] }) {
  const dirCol = r.dir === 'BULL' ? 'var(--up)' : 'var(--down)';
  const tagBg =
    r.kind === 'ACCUM'
      ? 'color-mix(in oklch, var(--up) 22%, var(--s2))'
      : 'color-mix(in oklch, var(--down) 22%, var(--s2))';
  const tagFg = r.kind === 'ACCUM' ? 'var(--up)' : 'var(--down)';
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: 'auto auto auto auto auto 1fr auto',
        alignItems: 'center',
        gap: 10,
        padding: '5px 6px',
        borderBottom: '1px solid var(--line)',
        fontSize: 11,
      }}
    >
      <span
        style={{
          background: tagBg,
          color: tagFg,
          padding: '2px 6px',
          borderRadius: 2,
          fontSize: 9,
          fontFamily: 'var(--font-mono)',
          fontWeight: 700,
          letterSpacing: '0.04em',
        }}
      >
        {r.kind}
      </span>
      <span style={{ fontFamily: 'var(--font-mono)', fontWeight: 600, color: 'var(--ink-1)' }}>{r.sym}</span>
      <span style={{ color: dirCol, fontWeight: 700, fontFamily: 'var(--font-mono)', fontSize: 10 }}>{r.dir}</span>
      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--ink-2)' }}>×{r.mult}</span>
      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--ink-2)' }}>
        avg: <span style={{ color: 'var(--ink-1)' }}>{r.avg.toFixed(1)}</span>
      </span>
      <span style={{ fontFamily: 'var(--font-mono)', fontSize: 10, color: 'var(--ink-2)' }}>
        conf:{' '}
        <span style={{ color: r.conf >= 55 ? 'var(--up)' : 'var(--ink-1)', fontWeight: 600 }}>{r.conf}</span>
      </span>
      <span className="mono muted" style={{ fontSize: 10, minWidth: 50, textAlign: 'right' }}>
        {r.age}
      </span>
    </div>
  );
}

export function OrderFlowProdPanel({ data }: { data: OrderFlowProd }) {
  return (
    <Panel title="Order Flow" right={<StatusDot kind="up" pulse />}>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span className="section-label">Delta</span>
        <DeltaBar d={data.delta} />
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
        <span className="section-label">Depth</span>
        <DepthBar d={data.depth} />
      </div>
      <div>
        <SectionLabel>Smart Money Cycle — {data.delta.sym}</SectionLabel>
        <div style={{ marginTop: 4 }}>
          {data.smcCycle.map((r, i) => (
            <SmcCycleRow key={i} r={r} />
          ))}
        </div>
      </div>
      <div>
        <SectionLabel>Distribution / Accumulation — {data.delta.sym}</SectionLabel>
        <div style={{ marginTop: 4 }}>
          {data.distAccum.map((r, i) => (
            <DistAccumRow key={i} r={r} />
          ))}
        </div>
      </div>
    </Panel>
  );
}
