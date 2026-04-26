'use client';

import { Chip, Panel } from '../lib/atoms';
import { Instrument } from '../lib/data';
import { useRiskDeskData } from '../lib/RiskDeskContext';

interface FootprintProps {
  instrument: Instrument;
}

export function Footprint({ instrument }: FootprintProps) {
  const D = useRiskDeskData();
  const bars = D.footprint;
  const allLevels = bars.flatMap((b) => b.levels);
  const maxVol = Math.max(...allLevels.map((l) => Math.max(l.bid, l.ask)));

  const priceSet = new Set<number>();
  bars.forEach((b) => b.levels.forEach((l) => priceSet.add(l.price)));
  const prices = Array.from(priceSet).sort((a, b) => b - a);

  const last = bars[bars.length - 1];
  const lastPct = ((last.c - last.o) / last.o) * 100;

  return (
    <Panel
      eyebrow="ORDER FLOW"
      title={`Footprint · Bid × Ask · ${instrument.sym}`}
      right={
        <div style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          <Chip tone={lastPct >= 0 ? 'pos' : 'neg'} mono>{`${lastPct >= 0 ? '+' : ''}Δ ${lastPct.toFixed(2)}%`}</Chip>
          <Chip tone="info" mono>{`${bars.length} bars`}</Chip>
        </div>
      }
    >
      <div style={{ overflowX: 'auto', maxHeight: 260 }}>
        <table
          style={{
            borderCollapse: 'collapse',
            fontFamily: 'var(--font-mono)',
            fontSize: 9.5,
            width: '100%',
          }}
        >
          <thead>
            <tr>
              <th
                style={{
                  position: 'sticky',
                  left: 0,
                  background: 'var(--bg-1)',
                  padding: '4px 8px',
                  color: 'var(--fg-3)',
                  textAlign: 'right',
                  fontWeight: 600,
                  borderBottom: '1px solid var(--hair-soft)',
                }}
              >
                PRICE
              </th>
              {bars.map((b, i) => {
                const up = b.c >= b.o;
                const d = new Date(b.t);
                const lbl = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
                return (
                  <th
                    key={i}
                    style={{
                      padding: '4px 6px',
                      color: up ? 'var(--pos)' : 'var(--neg)',
                      fontWeight: 600,
                      borderBottom: '1px solid var(--hair-soft)',
                      minWidth: 64,
                    }}
                  >
                    {lbl}
                  </th>
                );
              })}
            </tr>
          </thead>
          <tbody>
            {prices.map((p, ri) => (
              <tr key={p} style={{ background: ri % 2 === 0 ? 'var(--bg-1)' : 'var(--bg-0)' }}>
                <td
                  style={{
                    position: 'sticky',
                    left: 0,
                    background: 'inherit',
                    padding: '2px 8px',
                    color: 'var(--fg-2)',
                    textAlign: 'right',
                    borderRight: '1px solid var(--hair-soft)',
                  }}
                >
                  {p.toFixed(2)}
                </td>
                {bars.map((b, ci) => {
                  const lvl = b.levels.find((l) => Math.abs(l.price - p) < 0.001);
                  if (!lvl) return <td key={ci} style={{ padding: '2px 4px' }} />;
                  const bidPct = lvl.bid / maxVol;
                  const askPct = lvl.ask / maxVol;
                  const dom: 'bid' | 'ask' = lvl.bid > lvl.ask ? 'bid' : 'ask';
                  const isPoc = lvl.bid + lvl.ask === Math.max(...b.levels.map((l) => l.bid + l.ask));
                  return (
                    <td key={ci} style={{ padding: 0, position: 'relative' }}>
                      <div style={{ display: 'flex', alignItems: 'stretch', height: 16, position: 'relative' }}>
                        <div style={{ flex: 1, position: 'relative', borderRight: '1px solid var(--hair-soft)' }}>
                          <div
                            style={{
                              position: 'absolute',
                              right: 0,
                              top: 0,
                              bottom: 0,
                              width: `${bidPct * 100}%`,
                              background:
                                dom === 'bid'
                                  ? 'color-mix(in oklab, var(--neg) 32%, transparent)'
                                  : 'color-mix(in oklab, var(--neg) 14%, transparent)',
                            }}
                          />
                          <span
                            style={{
                              position: 'relative',
                              display: 'block',
                              textAlign: 'right',
                              padding: '1px 5px',
                              color: dom === 'bid' ? 'var(--neg)' : 'var(--fg-3)',
                              fontWeight: dom === 'bid' ? 700 : 400,
                            }}
                          >
                            {lvl.bid}
                          </span>
                        </div>
                        <div style={{ flex: 1, position: 'relative' }}>
                          <div
                            style={{
                              position: 'absolute',
                              left: 0,
                              top: 0,
                              bottom: 0,
                              width: `${askPct * 100}%`,
                              background:
                                dom === 'ask'
                                  ? 'color-mix(in oklab, var(--pos) 32%, transparent)'
                                  : 'color-mix(in oklab, var(--pos) 14%, transparent)',
                            }}
                          />
                          <span
                            style={{
                              position: 'relative',
                              display: 'block',
                              textAlign: 'left',
                              padding: '1px 5px',
                              color: dom === 'ask' ? 'var(--pos)' : 'var(--fg-3)',
                              fontWeight: dom === 'ask' ? 700 : 400,
                            }}
                          >
                            {lvl.ask}
                          </span>
                        </div>
                        {isPoc && (
                          <span
                            style={{
                              position: 'absolute',
                              right: -2,
                              top: '50%',
                              transform: 'translateY(-50%)',
                              width: 4,
                              height: 4,
                              borderRadius: '50%',
                              background: 'var(--accent)',
                            }}
                          />
                        )}
                      </div>
                    </td>
                  );
                })}
              </tr>
            ))}
            <tr>
              <td
                style={{
                  position: 'sticky',
                  left: 0,
                  background: 'var(--bg-1)',
                  padding: '4px 8px',
                  color: 'var(--fg-3)',
                  textAlign: 'right',
                  borderTop: '1px solid var(--hair-soft)',
                  fontWeight: 600,
                }}
              >
                Δ
              </td>
              {bars.map((b, ci) => {
                const totBid = b.levels.reduce((s, l) => s + l.bid, 0);
                const totAsk = b.levels.reduce((s, l) => s + l.ask, 0);
                const delta = totAsk - totBid;
                return (
                  <td
                    key={ci}
                    style={{
                      padding: '4px 6px',
                      color: delta >= 0 ? 'var(--pos)' : 'var(--neg)',
                      fontWeight: 700,
                      textAlign: 'center',
                      borderTop: '1px solid var(--hair-soft)',
                    }}
                  >
                    {delta >= 0 ? '+' : '−'}
                    {Math.abs(delta)}
                  </td>
                );
              })}
            </tr>
          </tbody>
        </table>
      </div>
      <div
        style={{
          marginTop: 8,
          display: 'flex',
          gap: 14,
          fontSize: 9.5,
          color: 'var(--fg-3)',
          fontFamily: 'var(--font-mono)',
        }}
      >
        <span>
          <span style={{ color: 'var(--neg)' }}>■</span> BID dominant
        </span>
        <span>
          <span style={{ color: 'var(--pos)' }}>■</span> ASK dominant
        </span>
        <span>
          <span style={{ color: 'var(--accent)' }}>●</span> POC per bar
        </span>
        <span style={{ marginLeft: 'auto' }}>Source: Lee–Ready classified ticks</span>
      </div>
    </Panel>
  );
}
