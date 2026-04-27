'use client';

import { Fragment } from 'react';
import { BarGauge, Chip, Panel, SectionLabel, Sparkline, StatusDot } from '../lib/atoms';
import {
  Dom,
  FlashCrash,
  FootprintCol,
  Ibkr,
  MicroEvents,
  OrderFlowEvent,
} from '../lib/data';
import { fmt } from '../lib/format';
import { MicrostructurePanel } from './Microstructure';

function DepthBookPanel({ dom, instrument }: { dom: Dom; instrument: string }) {
  const maxSz = Math.max(...dom.bids.map((b) => b.sz), ...dom.asks.map((a) => a.sz), 1);
  return (
    <Panel
      title={`Depth · ${instrument}`}
      right={<Chip kind="ghost">spread {dom.spread.toFixed(2)}</Chip>}
      dense
    >
      <div style={{ background: 'var(--s1)' }}>
        {dom.asks
          .slice()
          .reverse()
          .map((a) => (
            <div key={a.px} className="dom-row">
              <div className="ask-bar" style={{ width: `${(a.sz / maxSz) * 50}%` }} />
              <div className="bid-size"></div>
              <div className="price down">{a.px.toFixed(2)}</div>
              <div className="ask-size">{a.sz}</div>
              <div></div>
            </div>
          ))}
        <div className="dom-row spread">
          <div></div>
          <div></div>
          <div className="price" style={{ color: 'var(--accent)', fontWeight: 700 }}>
            {dom.last.toFixed(2)}
          </div>
          <div></div>
          <div
            style={{
              textAlign: 'left',
              paddingLeft: 6,
              fontSize: 9,
              letterSpacing: '0.06em',
              color: 'var(--ink-3)',
            }}
          >
            SPREAD
          </div>
        </div>
        {dom.bids.map((b) => (
          <div key={b.px} className="dom-row">
            <div className="bid-bar" style={{ width: `${(b.sz / maxSz) * 50}%` }} />
            <div className="bid-size">{b.sz}</div>
            <div className="price up">{b.px.toFixed(2)}</div>
            <div className="ask-size"></div>
            <div></div>
          </div>
        ))}
      </div>
    </Panel>
  );
}

function OrderFlowTapePanel({
  events,
  cvd,
  instrument,
}: {
  events: OrderFlowEvent[];
  cvd: number[];
  instrument: string;
}) {
  const hasData = events.length > 0;
  const lastCvd = cvd[cvd.length - 1] ?? 0;
  return (
    <Panel
      title={`Order Flow · ${instrument} Tape`}
      right={
        hasData ? (
          <span className="mono" style={{ fontSize: 10, color: 'var(--up)' }}>
            CVD {lastCvd >= 0 ? '+' : ''}
            {lastCvd} ↑
          </span>
        ) : (
          <Chip kind="ghost">no feed</Chip>
        )
      }
    >
      {!hasData && (
        <div
          style={{
            fontSize: 11,
            color: 'var(--ink-3)',
            fontStyle: 'italic',
            padding: '24px 0',
            textAlign: 'center',
          }}
        >
          No tick-by-tick tape for {instrument} yet.
        </div>
      )}
      <Sparkline values={cvd} color="var(--up)" w={340} h={36} />
      <div style={{ maxHeight: 260, overflowY: 'auto' }}>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: '60px 36px 60px 50px 1fr 60px',
            fontSize: 10,
            color: 'var(--ink-3)',
            padding: '4px 8px',
            borderBottom: '1px solid var(--line)',
            letterSpacing: '0.06em',
            textTransform: 'uppercase',
            fontWeight: 600,
          }}
        >
          <span>Time</span>
          <span>Side</span>
          <span>Price</span>
          <span>Size</span>
          <span>Note</span>
          <span style={{ textAlign: 'right' }}>Aggr</span>
        </div>
        {events.map((o, i) => (
          <div
            key={i}
            style={{
              display: 'grid',
              gridTemplateColumns: '60px 36px 60px 50px 1fr 60px',
              fontSize: 11,
              padding: '3px 8px',
              fontFamily: 'var(--font-mono)',
              background: i === 0 ? 'var(--accent-glow)' : 'transparent',
              borderLeft: i === 0 ? '2px solid var(--accent)' : '2px solid transparent',
            }}
          >
            <span className="muted">{o.t.split(':').slice(1).join(':')}</span>
            <span className={o.side === 'buy' ? 'up' : 'down'} style={{ fontWeight: 700 }}>
              {o.side === 'buy' ? '↑' : '↓'} {o.side.toUpperCase()[0]}
            </span>
            <span style={{ color: o.side === 'buy' ? 'var(--up)' : 'var(--down)' }}>
              {o.px.toFixed(2)}
            </span>
            <span style={{ color: 'var(--ink-1)', fontWeight: o.sz >= 20 ? 700 : 400 }}>{o.sz}</span>
            <span style={{ fontSize: 10, color: 'var(--ink-2)' }}>
              {o.note ? <Chip kind={o.kind === 'sweep' ? 'up' : 'ghost'}>{o.note}</Chip> : <span className="muted">{o.kind}</span>}
            </span>
            <span style={{ textAlign: 'right', color: o.agg > 0.7 ? 'var(--warn)' : 'var(--ink-2)' }}>
              {Math.round(o.agg * 100)}%
            </span>
          </div>
        ))}
      </div>
    </Panel>
  );
}

function FootprintPanel({ cols, tf, instrument }: { cols: FootprintCol[]; tf: string; instrument: string }) {
  const maxV = Math.max(
    ...cols.flatMap((c) => c.rows.flatMap((r) => [r.b, r.a])),
    1
  );
  const cell = (v: number, side: 'b' | 'a'): React.CSSProperties => {
    const intensity = v / maxV;
    const col = side === 'b' ? 'var(--up)' : 'var(--down)';
    return {
      background: `color-mix(in oklch, ${col} ${Math.round(intensity * 50)}%, transparent)`,
      color:
        intensity > 0.5
          ? side === 'b'
            ? 'var(--up-deep)'
            : 'var(--down-deep)'
          : 'var(--ink-1)',
      fontWeight: intensity > 0.6 ? 700 : 500,
    };
  };
  if (!cols.length) {
    return (
      <Panel title={`Footprint · ${instrument} ${tf}`}>
        <div style={{ fontSize: 11, color: 'var(--ink-3)', fontStyle: 'italic' }}>
          No footprint data available.
        </div>
      </Panel>
    );
  }
  return (
    <Panel
      title={`Footprint · ${instrument} ${tf}`}
      right={<Chip kind="ghost">{cols.length} bars · POC ●</Chip>}
    >
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: `60px repeat(${cols.length}, 1fr)`,
          gap: 1,
          background: 'var(--line)',
          borderRadius: 3,
          overflow: 'hidden',
          fontFamily: 'var(--font-mono)',
          fontSize: 9,
        }}
      >
        <div style={{ background: 'var(--s2)' }}></div>
        {cols.map((c, i) => (
          <div
            key={i}
            style={{
              background: 'var(--s2)',
              textAlign: 'center',
              padding: 2,
              color: c.dir === 'up' ? 'var(--up)' : 'var(--down)',
              fontWeight: 600,
            }}
          >
            {c.dir === 'up' ? '▲' : '▼'} {c.delta >= 0 ? '+' : ''}
            {c.delta}
          </div>
        ))}
        {cols[0].rows.map((row, ri) => (
          <Fragment key={ri}>
            <div
              style={{
                background: 'var(--s2)',
                textAlign: 'right',
                paddingRight: 4,
                color: 'var(--ink-2)',
                fontWeight: 600,
              }}
            >
              {row.px.toFixed(2)}
            </div>
            {cols.map((col, ci) => {
              const r = col.rows[ri];
              if (!r) return <div key={ci} />;
              return (
                <div
                  key={ci}
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '1fr 1fr',
                    background: 'var(--s1)',
                    position: 'relative',
                  }}
                >
                  <div
                    style={{
                      ...cell(r.b, 'b'),
                      padding: '1px 3px',
                      textAlign: 'right',
                      borderRight: '1px solid var(--s2)',
                    }}
                  >
                    {r.b}
                  </div>
                  <div
                    style={{
                      ...cell(r.a, 'a'),
                      padding: '1px 3px',
                      textAlign: 'left',
                    }}
                  >
                    {r.a}
                  </div>
                  {r.poc && (
                    <div
                      style={{
                        position: 'absolute',
                        left: -1,
                        top: 0,
                        bottom: 0,
                        width: 2,
                        background: 'var(--accent)',
                      }}
                    />
                  )}
                </div>
              );
            })}
          </Fragment>
        ))}
      </div>
    </Panel>
  );
}

function FlashCrashPanel({ f }: { f: FlashCrash }) {
  const sigPct = f.thresholds.sigma > 0 ? f.current.sigma / f.thresholds.sigma : 0;
  return (
    <Panel
      title="Flash-Crash Detector"
      right={
        <Chip
          kind={
            f.level === 'calm'
              ? 'up'
              : f.level === 'watch'
              ? 'warn'
              : 'down'
          }
        >
          {f.level.toUpperCase()}
        </Chip>
      }
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 10 }}>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>σ-move</div>
          <div
            className="mono"
            style={{
              fontSize: 16,
              color: sigPct > 0.7 ? 'var(--down)' : 'var(--ink-1)',
              fontWeight: 600,
            }}
          >
            {f.current.sigma.toFixed(1)}σ
          </div>
          <BarGauge
            value={f.current.sigma}
            max={f.thresholds.sigma * 1.2}
            color={sigPct > 0.7 ? 'var(--down)' : sigPct > 0.4 ? 'var(--warn)' : 'var(--up)'}
          />
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-3)' }}>
            thr {f.thresholds.sigma}σ
          </span>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Drop %</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--ink-1)', fontWeight: 600 }}>
            {f.current.dropPct.toFixed(2)}%
          </div>
          <BarGauge value={f.current.dropPct} max={f.thresholds.dropPct} />
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-3)' }}>
            thr {f.thresholds.dropPct}%
          </span>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Volume burst</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--ink-1)', fontWeight: 600 }}>
            {f.current.vol.toLocaleString('en-US')}
          </div>
          <BarGauge value={f.current.vol} max={f.thresholds.minVol * 1.5} />
          <span className="mono" style={{ fontSize: 9, color: 'var(--ink-3)' }}>
            thr {f.thresholds.minVol.toLocaleString('en-US')}
          </span>
        </div>
      </div>
      <SectionLabel>On-alert protocol</SectionLabel>
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 4, fontSize: 11 }}>
        <span>● Auto-flat positions</span>
        <span style={{ color: 'var(--up)' }}>{f.onAlert.autoFlat ? 'ON' : 'off'}</span>
        <span>● Require confirm</span>
        <span style={{ color: 'var(--up)' }}>{f.onAlert.requireConfirm ? 'ON' : 'off'}</span>
        <span>● Cancel pending</span>
        <span style={{ color: 'var(--up)' }}>{f.onAlert.cancelPending ? 'ON' : 'off'}</span>
        <span>● Lock entries (s)</span>
        <span className="mono">{f.onAlert.lockNewEntries}</span>
      </div>
      <SectionLabel>Recent</SectionLabel>
      {f.history.map((h, i) => (
        <div
          key={i}
          style={{ fontSize: 11, color: 'var(--ink-2)', display: 'flex', gap: 8 }}
        >
          <span className="mono muted">{h.t}</span>
          <Chip kind={h.level === 'watch' ? 'warn' : 'up'}>{h.level}</Chip>
          <span>{h.note}</span>
        </div>
      ))}
    </Panel>
  );
}

function IBKRPanel({ ib }: { ib: Ibkr }) {
  const connected = ib.conn === 'online';
  return (
    <Panel
      title="IBKR · Account"
      right={
        <>
          <StatusDot kind={connected ? 'up' : 'down'} pulse={connected} />
          <span
            className="mono"
            style={{ fontSize: 10, color: 'var(--ink-2)', marginLeft: 4 }}
          >
            {ib.account} · {ib.latencyMs}ms
          </span>
        </>
      }
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Net liq</div>
          <div className="mono" style={{ fontSize: 18, color: 'var(--ink-0)', fontWeight: 600 }}>
            ${ib.netLiq.toLocaleString('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 })}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Buying power</div>
          <div className="mono" style={{ fontSize: 18, color: 'var(--ink-0)', fontWeight: 600 }}>
            ${ib.buyingPower.toLocaleString('en-US')}
          </div>
        </div>
      </div>
      <div className="kv">
        <div className="k">Cash available</div>
        <div className="v">${ib.cashAvail.toLocaleString('en-US', { minimumFractionDigits: 2 })}</div>
        <div className="k">Initial margin</div>
        <div className="v">${ib.initMargin.toLocaleString('en-US', { minimumFractionDigits: 2 })}</div>
        <div className="k">Maintenance margin</div>
        <div className="v">${ib.maintMargin.toLocaleString('en-US', { minimumFractionDigits: 2 })}</div>
        <div className="k">Realized today</div>
        <div className="v up">{fmt.money(ib.realized)}</div>
        <div className="k">Unrealized</div>
        <div className="v up">{fmt.money(ib.unrealized)}</div>
      </div>
    </Panel>
  );
}

interface ExecuteViewProps {
  dom: Dom;
  cvd: number[];
  orderFlow: OrderFlowEvent[];
  flashCrash: FlashCrash;
  ibkr: Ibkr;
  microEvents: MicroEvents;
  footprint: FootprintCol[];
  tf: string;
  instrument: string;
}

export function ExecuteView({
  dom,
  cvd,
  orderFlow,
  flashCrash,
  ibkr,
  microEvents,
  footprint,
  tf,
  instrument,
}: ExecuteViewProps) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: '320px 1fr 1fr',
        gap: 12,
        padding: 12,
      }}
    >
      <DepthBookPanel dom={dom} instrument={instrument} />
      <OrderFlowTapePanel events={orderFlow} cvd={cvd} instrument={instrument} />
      <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
        <FlashCrashPanel f={flashCrash} />
        <IBKRPanel ib={ibkr} />
      </div>
      <div style={{ gridColumn: '1 / -1' }}>
        <MicrostructurePanel instrument={instrument} events={microEvents} />
      </div>
      <div style={{ gridColumn: '1 / -1' }}>
        <FootprintPanel cols={footprint} tf={tf} instrument={instrument} />
      </div>
    </div>
  );
}
