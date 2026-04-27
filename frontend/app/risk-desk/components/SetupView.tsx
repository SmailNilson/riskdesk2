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
  PlaybookLive,
  Position,
  Strategy,
  StrategyAgentVoteView,
  StrategyLayerScore,
} from '../lib/data';
import { OrderFlowProdPanel } from './OrderFlowProd';
import { OrderFlowProd } from '../lib/data';

function StrategyPanel({
  s,
  votes,
  layerScores,
  finalScore,
  vetoReasons,
}: {
  s: Strategy;
  votes: StrategyAgentVoteView[];
  layerScores: StrategyLayerScore[];
  finalScore: number;
  vetoReasons: string[];
}) {
  // No real votes → fall back to the simple regime gauge view (mock).
  const hasLive = votes.length > 0 || layerScores.some((l) => l.score !== 0);
  const finalScoreNorm = Math.max(0, Math.min(1, (finalScore + 100) / 200));
  return (
    <Panel
      title="Strategy · Regime"
      right={
        <span style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
          {hasLive && (
            <span
              className="mono"
              style={{
                fontSize: 10,
                color: finalScore >= 0 ? 'var(--up)' : 'var(--down)',
                fontWeight: 600,
              }}
            >
              score {finalScore >= 0 ? '+' : ''}
              {finalScore.toFixed(0)}
            </span>
          )}
          <Chip kind="accent">{s.regime}</Chip>
        </span>
      }
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <span style={{ fontSize: 10, color: 'var(--ink-3)' }}>Confidence</span>
        <BarGauge
          value={hasLive ? finalScoreNorm : s.confidence}
          color={
            hasLive
              ? finalScore >= 30
                ? 'var(--up)'
                : finalScore <= -30
                ? 'var(--down)'
                : 'var(--warn)'
              : undefined
          }
        />
        <span className="mono" style={{ fontSize: 11, color: 'var(--ink-1)' }}>
          {Math.round((hasLive ? finalScoreNorm : s.confidence) * 100)}%
        </span>
      </div>
      {hasLive && layerScores.length > 0 ? (
        <div style={{ display: 'grid', gridTemplateColumns: '1fr', gap: 6 }}>
          {layerScores.map((l) => {
            // -100..+100 → 0..1 for the gauge
            const norm = Math.max(0, Math.min(1, (l.score + 100) / 200));
            return (
              <div
                key={l.layer}
                style={{
                  display: 'grid',
                  gridTemplateColumns: '120px 1fr 60px',
                  alignItems: 'center',
                  gap: 8,
                }}
              >
                <span style={{ fontSize: 11, color: 'var(--ink-2)' }}>
                  {l.layer}{' '}
                  <span className="muted" style={{ fontSize: 9 }}>
                    {Math.round(l.weight * 100)}%
                  </span>
                </span>
                <BarGauge
                  value={norm}
                  color={
                    l.score >= 30 ? 'var(--up)' : l.score <= -30 ? 'var(--down)' : 'var(--warn)'
                  }
                />
                <span
                  className="mono"
                  style={{ fontSize: 10, color: 'var(--ink-2)', textAlign: 'right' }}
                >
                  {l.score >= 0 ? '+' : ''}
                  {l.score.toFixed(0)}
                </span>
              </div>
            );
          })}
        </div>
      ) : (
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
      )}
      {hasLive && votes.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginTop: 4 }}>
          <span
            style={{
              fontSize: 9,
              color: 'var(--ink-3)',
              letterSpacing: '0.06em',
              textTransform: 'uppercase',
              fontWeight: 600,
            }}
          >
            Agent votes
          </span>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            {votes.slice(0, 6).map((v) => (
              <div
                key={v.agentId + v.layer}
                style={{
                  display: 'grid',
                  gridTemplateColumns: '110px 50px 1fr 60px',
                  alignItems: 'center',
                  gap: 6,
                  fontSize: 10,
                }}
              >
                <span style={{ color: 'var(--ink-1)', fontWeight: 500 }}>{v.agentId}</span>
                <Chip kind="ghost">{v.layer}</Chip>
                <span
                  className="muted"
                  style={{
                    overflow: 'hidden',
                    textOverflow: 'ellipsis',
                    whiteSpace: 'nowrap',
                  }}
                >
                  {v.abstain ? 'abstain' : v.vetoReason ? `veto · ${v.vetoReason}` : v.evidence[0] ?? ''}
                </span>
                <span
                  className="mono"
                  style={{
                    textAlign: 'right',
                    color:
                      v.abstain
                        ? 'var(--ink-3)'
                        : v.vote >= 30
                        ? 'var(--up)'
                        : v.vote <= -30
                        ? 'var(--down)'
                        : 'var(--ink-2)',
                    fontWeight: 600,
                  }}
                >
                  {v.abstain ? '—' : `${v.vote >= 0 ? '+' : ''}${v.vote.toFixed(0)}`}
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
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
        <strong style={{ color: 'var(--accent)' }}>→</strong>{' '}
        {vetoReasons.length > 0 ? vetoReasons[0] : s.recommendation}
      </div>
    </Panel>
  );
}

function IndicatorsPanel({ i, tf, instrument }: { i: Indicators; tf: string; instrument: string }) {
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
    <Panel title={`Indicators · ${instrument} ${tf}`}>
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

function PlaybookLivePanel({
  live,
  instrument,
  tf,
}: {
  live: PlaybookLive;
  instrument: string;
  tf: string;
}) {
  const score = live.checklistScore;
  const max = live.checklistMax || 7;
  const ratio = max > 0 ? score / max : 0;
  return (
    <Panel
      title={`Playbook · ${instrument} ${tf}`}
      right={
        live.available ? (
          <Chip
            kind={
              ratio >= 0.7
                ? 'up'
                : ratio >= 0.5
                ? 'warn'
                : 'ghost'
            }
          >
            {score}/{max} · {live.verdict}
          </Chip>
        ) : (
          <Chip kind="ghost">no eval</Chip>
        )
      }
    >
      {!live.available ? (
        <div style={{ fontSize: 11, color: 'var(--ink-3)', fontStyle: 'italic' }}>
          No playbook evaluation yet for this contract / timeframe.
        </div>
      ) : (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <BarGauge
              value={ratio}
              color={ratio >= 0.7 ? 'var(--up)' : ratio >= 0.5 ? 'var(--warn)' : 'var(--down)'}
            />
            {live.tradeDirection && (
              <Chip kind={live.tradeDirection === 'LONG' ? 'up' : 'down'}>
                {live.tradeDirection}
              </Chip>
            )}
            {live.bestSetup && (
              <span className="mono muted" style={{ fontSize: 10 }}>
                {live.bestSetup.zoneName} · RR {live.bestSetup.rrRatio.toFixed(2)}
              </span>
            )}
          </div>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2, marginTop: 4 }}>
            {live.checklist.map((c) => {
              const color =
                c.status === 'PASS'
                  ? 'var(--up)'
                  : c.status === 'FAIL'
                  ? 'var(--down)'
                  : 'var(--warn)';
              const mark = c.status === 'PASS' ? '✓' : c.status === 'FAIL' ? '✕' : '·';
              return (
                <div
                  key={c.step}
                  style={{
                    display: 'grid',
                    gridTemplateColumns: '20px 1fr 1fr',
                    alignItems: 'center',
                    gap: 6,
                    fontSize: 11,
                    padding: '2px 0',
                    borderBottom: '1px solid var(--line)',
                  }}
                >
                  <span className="mono" style={{ color, fontWeight: 700, textAlign: 'center' }}>
                    {mark}
                  </span>
                  <span style={{ color: 'var(--ink-1)' }}>{c.label}</span>
                  <span
                    className="muted"
                    style={{
                      fontSize: 10,
                      overflow: 'hidden',
                      textOverflow: 'ellipsis',
                      whiteSpace: 'nowrap',
                    }}
                    title={c.detail}
                  >
                    {c.detail}
                  </span>
                </div>
              );
            })}
          </div>
        </>
      )}
    </Panel>
  );
}

function PlaybookHistoryPanel({ entries }: { entries: PlaybookEntry[] }) {
  return (
    <Panel title="Playbook · history" right={<button type="button" className="btn btn-sm btn-ghost">+ New</button>}>
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

function TradeDecisionPanel({
  tf,
  instrument,
  strategy,
}: {
  tf: string;
  instrument: string;
  strategy: Strategy;
}) {
  const plan = strategy.plan;
  const isLong = plan?.direction === 'LONG';
  const fmtPx = (n: number) => n.toFixed(Math.abs(n) >= 100 ? 2 : 4);
  return (
    <Panel
      title={`Trade Decision · ${instrument} ${tf}`}
      right={
        <Chip kind={strategy.eligible ? 'up' : 'ghost'}>
          {strategy.eligible ? 'eligible' : 'no trade'}
        </Chip>
      }
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 10 }}>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)', marginBottom: 4 }}>Bias</div>
          {plan ? (
            <Chip kind={isLong ? 'up' : 'down'}>{plan.direction}</Chip>
          ) : (
            <Chip kind="ghost">—</Chip>
          )}
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)', marginBottom: 4 }}>Setup score</div>
          <BarGauge value={strategy.confidence} />
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)', marginBottom: 4 }}>RR target</div>
          <span
            className="mono"
            style={{
              fontSize: 12,
              color: plan ? 'var(--up)' : 'var(--ink-3)',
              fontWeight: 600,
            }}
          >
            {plan ? `${plan.rrRatio.toFixed(2)}R` : '—'}
          </span>
        </div>
      </div>
      {plan ? (
        <>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10, marginTop: 4 }}>
            <div className="kv">
              <div className="k">Entry trigger</div>
              <div className="v">{fmtPx(plan.entry)}</div>
              <div className="k">Stop</div>
              <div className="v down">{fmtPx(plan.stop)}</div>
              <div className="k">Target 1</div>
              <div className="v up">{fmtPx(plan.tp1)}</div>
              <div className="k">Target 2</div>
              <div className="v up">{fmtPx(plan.tp2)}</div>
            </div>
            <div className="kv">
              <div className="k">Direction</div>
              <div className="v">{plan.direction}</div>
              <div className="k">RR (T2)</div>
              <div className="v">{plan.rrRatio.toFixed(2)}R</div>
              <div className="k">Recommendation</div>
              <div className="v" style={{ fontSize: 10 }}>
                {strategy.recommendation}
              </div>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 6 }}>
            <button
              type="button"
              className={isLong ? 'btn btn-up' : 'btn btn-down'}
              style={{ flex: 1 }}
              disabled={!strategy.eligible}
            >
              Arm {plan.direction.charAt(0) + plan.direction.slice(1).toLowerCase()}
            </button>
            <button type="button" className="btn">
              Edit
            </button>
            <button type="button" className="btn btn-ghost">
              Pass
            </button>
          </div>
        </>
      ) : (
        <div
          style={{
            fontSize: 11,
            color: 'var(--ink-3)',
            fontStyle: 'italic',
            padding: '12px 0',
          }}
        >
          No mechanical plan for {instrument} {tf} — {strategy.regime.toLowerCase()}.
        </div>
      )}
    </Panel>
  );
}

interface SetupViewProps {
  strategy: Strategy;
  strategyVotes: StrategyAgentVoteView[];
  strategyLayerScores: StrategyLayerScore[];
  strategyFinalScore: number;
  strategyVetoReasons: string[];
  indicators: Indicators;
  positions: Position[];
  playbook: PlaybookEntry[];
  playbookLive: PlaybookLive;
  dxy: DxyData;
  correlations: Correlations;
  orderflowProd: OrderFlowProd;
  tf: string;
  instrument: string;
}

export function SetupView({
  strategy,
  strategyVotes,
  strategyLayerScores,
  strategyFinalScore,
  strategyVetoReasons,
  indicators,
  positions,
  playbook,
  playbookLive,
  dxy,
  correlations,
  orderflowProd,
  tf,
  instrument,
}: SetupViewProps) {
  return (
    <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12, padding: 12 }}>
      <StrategyPanel
        s={strategy}
        votes={strategyVotes}
        layerScores={strategyLayerScores}
        finalScore={strategyFinalScore}
        vetoReasons={strategyVetoReasons}
      />
      <IndicatorsPanel i={indicators} tf={tf} instrument={instrument} />
      <PositionsPanel positions={positions} />
      <TradeDecisionPanel tf={tf} instrument={instrument} strategy={strategy} />
      <div style={{ gridColumn: '1 / -1' }}>
        <OrderFlowProdPanel data={orderflowProd} />
      </div>
      <PlaybookLivePanel live={playbookLive} instrument={instrument} tf={tf} />
      <PlaybookHistoryPanel entries={playbook} />
      <DXYPanel d={dxy} />
      <div style={{ gridColumn: '1 / -1' }}>
        <CorrelationsPanel c={correlations} />
      </div>
    </div>
  );
}
