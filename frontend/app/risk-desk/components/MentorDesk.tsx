'use client';

import { CSSProperties, ReactNode, useEffect, useMemo, useState } from 'react';
import { Chip, Panel, Segmented, StatusDot, Tone } from '../lib/atoms';
import {
  ExecStatus,
  Instrument,
  Review,
  ReviewExecution,
  ReviewSim,
} from '../lib/data';
import { fmtAgo } from '../lib/format';
import { useRiskDeskData } from '../lib/RiskDeskContext';

interface MentorDeskProps {
  instrument: Instrument;
  collapsed: boolean;
  setCollapsed: (v: boolean) => void;
}

type Filter = 'ALL' | 'OK' | 'NC' | 'PEND';

export function MentorDesk({ collapsed, setCollapsed }: MentorDeskProps) {
  const D = useRiskDeskData();
  const reviews = D.reviews;
  const liveExecutions = D.executions;
  const armExecution = D.armExecution;
  const submitExecutionEntry = D.submitExecutionEntry;
  const [filter, setFilter] = useState<Filter>('ALL');
  const [selId, setSelId] = useState<number>(reviews[0].id);
  const [armQty, setArmQty] = useState(2);
  const [now, setNow] = useState<number | null>(null);

  // Tick a now ref every 30s for fmtAgo display, deferred to client
  useEffect(() => {
    setNow(Date.now());
    const id = setInterval(() => setNow(Date.now()), 30000);
    return () => clearInterval(id);
  }, []);

  const sel = reviews.find((r) => r.id === selId);
  const exec = sel?.execution;

  const filtered = useMemo(() => {
    if (filter === 'ALL') return reviews;
    if (filter === 'OK') return reviews.filter((r) => r.eligibility === 'ELIGIBLE');
    if (filter === 'NC') return reviews.filter((r) => r.eligibility === 'INELIGIBLE');
    if (filter === 'PEND') return reviews.filter((r) => r.status === 'ANALYZING');
    return reviews;
  }, [filter, reviews]);

  if (collapsed) {
    return (
      <aside
        style={{
          width: 38,
          flexShrink: 0,
          borderLeft: '1px solid var(--hair)',
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
          title="Expand AI desk"
          style={{
            background: 'transparent',
            border: '1px solid var(--hair)',
            borderRadius: 4,
            color: 'var(--fg-2)',
            padding: '4px 6px',
            cursor: 'pointer',
          }}
        >
          ‹
        </button>
        <span
          style={{
            writingMode: 'vertical-rl',
            fontSize: 9.5,
            letterSpacing: '0.18em',
            color: 'var(--fg-3)',
            textTransform: 'uppercase',
            fontWeight: 600,
          }}
        >
          AI Mentor
        </span>
      </aside>
    );
  }

  return (
    <aside
      style={{
        width: 460,
        flexShrink: 0,
        borderLeft: '1px solid var(--hair)',
        background: 'var(--bg-0)',
        overflowY: 'auto',
        padding: 12,
        display: 'flex',
        flexDirection: 'column',
        gap: 12,
      }}
    >
      {/* Header row */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <span className="label" style={{ color: 'var(--accent)' }}>
            AI MENTOR
          </span>
          <Chip tone="accent" mono soft>
            <StatusDot tone="accent" pulse size={5} />
            AUTO ON
          </Chip>
        </div>
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
          ›
        </button>
      </div>

      {/* Filter row */}
      <div style={{ display: 'flex', gap: 6, alignItems: 'center', flexWrap: 'wrap' }}>
        <Segmented
          value={filter}
          onChange={setFilter}
          size="xs"
          options={[
            { value: 'ALL', label: `All · ${reviews.length}` },
            {
              value: 'OK',
              label: `Trade OK · ${reviews.filter((r) => r.eligibility === 'ELIGIBLE').length}`,
            },
            {
              value: 'NC',
              label: `Non-Conforme · ${reviews.filter((r) => r.eligibility === 'INELIGIBLE').length}`,
            },
            {
              value: 'PEND',
              label: `Pending · ${reviews.filter((r) => r.status === 'ANALYZING').length}`,
            },
          ]}
        />
      </div>

      {/* Group cards */}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
        {filtered.map((r) => {
          const active = r.id === selId;
          return (
            <button
              key={r.id}
              type="button"
              onClick={() => setSelId(r.id)}
              style={{
                textAlign: 'left',
                padding: 10,
                border: '1px solid',
                borderColor: active ? 'var(--accent)' : 'var(--hair)',
                borderRadius: 7,
                background: active ? 'var(--accent-bg-soft)' : 'var(--bg-1)',
                cursor: 'pointer',
                display: 'flex',
                flexDirection: 'column',
                gap: 6,
                color: 'inherit',
                fontFamily: 'inherit',
                position: 'relative',
              }}
            >
              <div style={{ display: 'flex', alignItems: 'center', gap: 6, flexWrap: 'wrap' }}>
                <Chip tone={r.direction === 'LONG' ? 'pos' : 'neg'} soft mono>
                  {r.direction}
                </Chip>
                <span className="num" style={{ fontSize: 12, fontWeight: 700 }}>
                  {r.instrument}
                </span>
                <Chip mono>{r.tf}</Chip>
                <span style={{ flex: 1 }} />
                <ReviewStatusChip r={r} exec={r.execution} />
              </div>
              <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
                {r.categories.map((c) => (
                  <span
                    key={c}
                    style={{
                      fontSize: 9,
                      padding: '1px 6px',
                      background: 'var(--bg-2)',
                      border: '1px solid var(--hair-soft)',
                      borderRadius: 3,
                      color: 'var(--fg-2)',
                      fontFamily: 'var(--font-mono)',
                      letterSpacing: '0.02em',
                    }}
                  >
                    {c}
                  </span>
                ))}
              </div>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: 9.5, color: 'var(--fg-3)', fontFamily: 'var(--font-mono)' }}>
                  {now != null ? fmtAgo(r.createdAt, now) : '—'} · trigger ${r.triggerPrice.toFixed(2)} · w {r.confluence.toFixed(1)}
                </span>
                {r.sim && <SimChip sim={r.sim} />}
              </div>
              {r.status === 'ANALYZING' && (
                <div
                  style={{
                    position: 'absolute',
                    bottom: 0,
                    left: 0,
                    right: 0,
                    height: 2,
                    background: 'linear-gradient(90deg, transparent, var(--accent), transparent)',
                    backgroundSize: '200% 100%',
                    animation: 'rd-shimmer 1.4s linear infinite',
                  }}
                />
              )}
            </button>
          );
        })}
      </div>

      {/* Detail */}
      {sel && (
        <Panel
          eyebrow="REVIEW"
          title={`#${sel.id} · ${sel.instrument} · ${sel.tf}`}
          dense
          right={<ReviewStatusChip r={sel} exec={exec} />}
        >
          {sel.status === 'ANALYZING' ? (
            <AnalyzingState />
          ) : (
            <ReviewBody
              r={sel}
              exec={exec}
              liveExecId={liveExecutions[sel.id]?.id}
              liveExecCreatedAt={liveExecutions[sel.id]?.createdAt}
              armQty={armQty}
              setArmQty={setArmQty}
              onArm={(rev, qty) => {
                void armExecution(rev, qty);
              }}
              onSubmit={(execId) => {
                void submitExecutionEntry(execId);
              }}
              now={now}
            />
          )}
        </Panel>
      )}

      <div style={{ height: 60 }} />
    </aside>
  );
}

// ── Status chip dispatcher
function ReviewStatusChip({ r, exec }: { r: Review; exec: ReviewExecution | undefined }) {
  if (r.status === 'ANALYZING')
    return (
      <Chip tone="info" mono soft>
        ANALYZING
      </Chip>
    );
  if (r.status === 'ERROR')
    return (
      <Chip tone="neg" mono soft>
        ERROR
      </Chip>
    );
  if (exec) {
    const map: Record<ExecStatus, { tone: Tone; label: string }> = {
      PENDING_ENTRY_SUBMISSION: { tone: 'warn', label: 'EXEC ARMED' },
      ENTRY_SUBMITTED: { tone: 'info', label: 'EXEC SUBMITTED' },
      ACTIVE: { tone: 'info', label: 'EXEC ACTIVE' },
      CLOSED: { tone: 'pos', label: 'EXEC CLOSED' },
      FAILED: { tone: 'neg', label: 'EXEC FAILED' },
    };
    const m = map[exec.status] ?? { tone: 'mute' as Tone, label: 'EXEC ?' };
    return (
      <Chip tone={m.tone} mono soft>
        {m.label}
      </Chip>
    );
  }
  if (r.eligibility === 'ELIGIBLE')
    return (
      <Chip tone="pos" mono soft>
        TRADE OK
      </Chip>
    );
  if (r.eligibility === 'INELIGIBLE')
    return (
      <Chip tone="neg" mono soft>
        NON-CONFORME
      </Chip>
    );
  return (
    <Chip tone="mute" mono>
      —
    </Chip>
  );
}

function SimChip({ sim }: { sim: ReviewSim }) {
  const map: Record<ReviewSim['status'], { tone: Tone; label: string }> = {
    PENDING_ENTRY: { tone: 'warn', label: 'Pending entry' },
    ACTIVE: { tone: 'info', label: `Active · −${(sim.drawdown ?? 0).toFixed(2)}` },
    WIN: { tone: 'pos', label: 'WIN ✓' },
    LOSS: { tone: 'neg', label: 'LOSS ✗' },
    MISSED: { tone: 'vio', label: 'Missed' },
    REVERSED: { tone: 'warn', label: 'Reversed' },
  };
  const m = map[sim.status];
  if (!m) return null;
  return (
    <Chip tone={m.tone} mono>
      {m.label}
    </Chip>
  );
}

// ── Analyzing skeleton
function AnalyzingState() {
  const [phase, setPhase] = useState(0);
  useEffect(() => {
    const id = setInterval(() => setPhase((p) => (p + 1) % 4), 900);
    return () => clearInterval(id);
  }, []);
  const phases = [
    'Freezing payload snapshot…',
    'Calling Gemini · per-asset prompt (ENERGY)…',
    'Parsing structured verdict…',
    'Computing R:R + execution eligibility…',
  ];
  return (
    <div style={{ padding: '8px 0' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 12 }}>
        <span className="label" style={{ color: 'var(--accent)' }}>
          MENTOR PIPELINE
        </span>
        <span style={{ flex: 1, height: 1, background: 'var(--hair)' }} />
        <span style={{ fontSize: 10, color: 'var(--fg-3)', fontFamily: 'var(--font-mono)' }}>{phase + 1}/4</span>
      </div>
      {phases.map((p, i) => (
        <div
          key={i}
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            padding: '5px 0',
            fontSize: 11,
            color: i < phase ? 'var(--fg-1)' : i === phase ? 'var(--accent)' : 'var(--fg-3)',
            fontFamily: 'var(--font-mono)',
          }}
        >
          <span style={{ width: 14, display: 'inline-block' }}>
            {i < phase ? '✓' : i === phase ? '▸' : '○'}
          </span>
          {p}
        </div>
      ))}
      <div
        style={{
          marginTop: 14,
          padding: 10,
          border: '1px dashed var(--hair)',
          borderRadius: 6,
          background: 'var(--bg-1)',
        }}
      >
        <div className="label" style={{ marginBottom: 6, color: 'var(--accent)' }}>
          Frozen payload
        </div>
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: '1fr 1fr',
            gap: 4,
            fontSize: 10.5,
            fontFamily: 'var(--font-mono)',
            color: 'var(--fg-2)',
          }}
        >
          <div>
            structure: <span style={{ color: 'var(--pos)' }}>BULL CHoCH @ 62.86</span>
          </div>
          <div>
            flow_src: <span style={{ color: 'var(--info)' }}>REAL_TICKS</span>
          </div>
          <div>
            order_blocks: <span style={{ color: 'var(--fg-1)' }}>3 active</span>
          </div>
          <div>
            regime: <span style={{ color: 'var(--accent)' }}>TRENDING</span>
          </div>
          <div>
            rsi: <span style={{ color: 'var(--fg-1)' }}>54.2</span>
          </div>
          <div>
            macd_hist: <span style={{ color: 'var(--pos)' }}>+0.024</span>
          </div>
          <div>
            vwap: <span style={{ color: 'var(--warn)' }}>62.71</span>
          </div>
          <div>
            dxy_24h: <span style={{ color: 'var(--neg)' }}>−0.12%</span>
          </div>
        </div>
      </div>
    </div>
  );
}

// ── Review body
function ReviewBody({
  r,
  exec,
  liveExecId,
  liveExecCreatedAt,
  armQty,
  setArmQty,
  onArm,
  onSubmit,
  now,
}: {
  r: Review;
  exec: ReviewExecution | undefined;
  liveExecId: number | undefined;
  liveExecCreatedAt: string | undefined;
  armQty: number;
  setArmQty: (n: number) => void;
  onArm: (r: Review, qty: number) => void;
  onSubmit: (execId: number) => void;
  now: number | null;
}) {
  const elig = r.eligibility === 'ELIGIBLE';
  const canArm = elig && !exec && r.plan != null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
      {r.verdict && (
        <div
          style={{
            padding: 10,
            border: '1px solid',
            borderColor: elig
              ? 'color-mix(in oklab, var(--pos) 35%, transparent)'
              : 'color-mix(in oklab, var(--neg) 35%, transparent)',
            background: elig ? 'var(--pos-bg)' : 'var(--neg-bg)',
            borderRadius: 6,
          }}
        >
          <div className="label" style={{ marginBottom: 4, color: elig ? 'var(--pos)' : 'var(--neg)' }}>
            VERDICT
          </div>
          <div style={{ fontSize: 11.5, color: 'var(--fg-0)', lineHeight: 1.5 }}>{r.verdict}</div>
        </div>
      )}

      {r.plan && (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(4, 1fr)',
            gap: 6,
            border: '1px solid var(--hair)',
            borderRadius: 6,
            padding: 10,
            background: 'var(--bg-1)',
          }}
        >
          <PlanCell label="Entry" value={r.plan.entry.toFixed(2)} tone="accent" />
          <PlanCell label="SL" value={r.plan.sl.toFixed(2)} tone="neg" />
          <PlanCell label="TP" value={r.plan.tp.toFixed(2)} tone="pos" />
          <PlanCell label="R:R" value={`${r.plan.rr.toFixed(2)}×`} tone="info" />
        </div>
      )}

      {r.analysis && <Section label="Analysis" body={r.analysis} />}
      {r.advice && <Section label="Mentor Advice" body={r.advice} accent />}

      {/* Confluence breakdown */}
      <ConfluenceBar weight={r.confluence} />

      {/* Action row */}
      {canArm && r.plan && (
        <div
          style={{
            padding: 10,
            border: '1px solid color-mix(in oklab, var(--accent) 30%, transparent)',
            background: 'var(--accent-bg-soft)',
            borderRadius: 6,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 8 }}>
            <span className="label" style={{ color: 'var(--accent)' }}>
              EXECUTION
            </span>
            <span style={{ fontSize: 9.5, color: 'var(--fg-3)', fontFamily: 'var(--font-mono)' }}>
              IBKR · acct U7842318 · slice 1
            </span>
          </div>
          <div style={{ display: 'flex', gap: 8, alignItems: 'stretch' }}>
            <div style={{ flex: '0 0 90px' }}>
              <div className="label" style={{ marginBottom: 3 }}>
                Quantity
              </div>
              <input
                type="number"
                min={1}
                step={1}
                value={armQty}
                onChange={(e) => setArmQty(Math.max(1, Math.floor(+e.target.value || 1)))}
                style={{
                  width: '100%',
                  padding: '6px 8px',
                  background: 'var(--bg-0)',
                  border: '1px solid var(--hair)',
                  borderRadius: 4,
                  color: 'var(--fg-0)',
                  fontSize: 13,
                  fontFamily: 'var(--font-mono)',
                  fontWeight: 600,
                }}
              />
            </div>
            <div style={{ flex: 1, display: 'flex', flexDirection: 'column', justifyContent: 'flex-end' }}>
              <button
                type="button"
                onClick={() => onArm(r, armQty)}
                style={{
                  padding: '8px 12px',
                  border: '1px solid var(--accent)',
                  background: 'var(--accent)',
                  color: 'var(--bg-0)',
                  borderRadius: 5,
                  fontSize: 12,
                  fontWeight: 700,
                  cursor: 'pointer',
                  letterSpacing: '0.02em',
                }}
              >
                Arm Execution · ×{armQty}
              </button>
              <div
                style={{
                  marginTop: 6,
                  fontSize: 9.5,
                  color: 'var(--fg-3)',
                  fontFamily: 'var(--font-mono)',
                  textAlign: 'center',
                }}
              >
                Risk ≈ ${(Math.abs(r.plan.entry - r.plan.sl) * armQty * 100).toFixed(0)} · R:R{' '}
                {r.plan.rr.toFixed(2)}×
              </div>
            </div>
          </div>
        </div>
      )}

      {exec && r.plan && (
        <ExecutionStatus
          exec={exec}
          review={r}
          liveExecId={liveExecId}
          liveExecCreatedAt={liveExecCreatedAt}
          onSubmit={onSubmit}
          now={now}
        />
      )}

      {/* Footer actions */}
      <div style={{ display: 'flex', gap: 6, justifyContent: 'flex-end', marginTop: 4 }}>
        <button type="button" style={btnSecondary}>
          Reanalyze
        </button>
        <button type="button" style={btnSecondary}>
          Open Chart
        </button>
        <button type="button" style={btnSecondary}>
          Mute 30m
        </button>
      </div>
    </div>
  );
}

function PlanCell({ label, value, tone }: { label: string; value: string; tone: Tone }) {
  const colorMap: Record<Tone, string> = {
    accent: 'var(--accent)',
    pos: 'var(--pos)',
    neg: 'var(--neg)',
    info: 'var(--info)',
    warn: 'var(--warn)',
    vio: 'var(--vio)',
    mute: 'var(--fg-0)',
  };
  return (
    <div>
      <div className="label" style={{ marginBottom: 3, fontSize: 8.5 }}>
        {label}
      </div>
      <div className="num" style={{ fontSize: 14, fontWeight: 700, color: colorMap[tone] }}>
        {value}
      </div>
    </div>
  );
}

function Section({ label, body, accent = false }: { label: string; body: ReactNode; accent?: boolean }) {
  return (
    <div>
      <div className="label" style={{ marginBottom: 4, color: accent ? 'var(--accent)' : 'var(--fg-2)' }}>
        {label}
      </div>
      <div style={{ fontSize: 11.5, lineHeight: 1.55, color: 'var(--fg-1)' }}>{body}</div>
    </div>
  );
}

function ConfluenceBar({ weight }: { weight: number }) {
  const pct = Math.min(1, weight / 5);
  const flushed = weight >= 3;
  return (
    <div
      style={{
        padding: 8,
        border: '1px solid var(--hair)',
        borderRadius: 6,
        background: 'var(--bg-1)',
      }}
    >
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 4 }}>
        <span className="label">Confluence Buffer</span>
        <span
          className="num"
          style={{
            fontSize: 11,
            color: flushed ? 'var(--pos)' : 'var(--warn)',
            fontWeight: 600,
          }}
        >
          w {weight.toFixed(1)} / 3.0 {flushed && '· FLUSHED'}
        </span>
      </div>
      <div style={{ position: 'relative', height: 4, background: 'var(--bg-2)', borderRadius: 2, overflow: 'hidden' }}>
        <div
          style={{
            position: 'absolute',
            left: 0,
            top: 0,
            bottom: 0,
            width: `${pct * 100}%`,
            background: flushed ? 'var(--pos)' : 'var(--warn)',
          }}
        />
        <div
          style={{
            position: 'absolute',
            left: '60%',
            top: -2,
            bottom: -2,
            width: 1,
            background: 'var(--fg-1)',
            opacity: 0.6,
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
        <span>0</span>
        <span>3.0 threshold</span>
        <span>5</span>
      </div>
    </div>
  );
}

function ExecutionStatus({
  exec,
  review,
  liveExecId,
  liveExecCreatedAt,
  onSubmit,
  now,
}: {
  exec: ReviewExecution;
  review: Review;
  liveExecId: number | undefined;
  liveExecCreatedAt: string | undefined;
  onSubmit: (execId: number) => void;
  now: number | null;
}) {
  const tone: Tone =
    exec.status === 'PENDING_ENTRY_SUBMISSION'
      ? 'warn'
      : exec.status === 'ENTRY_SUBMITTED' || exec.status === 'ACTIVE'
      ? 'info'
      : exec.status === 'CLOSED'
      ? 'pos'
      : 'neg';
  const armedAt = liveExecCreatedAt ? Date.parse(liveExecCreatedAt) : null;
  return (
    <div
      style={{
        padding: 10,
        border: `1px solid color-mix(in oklab, var(--${tone}) 35%, transparent)`,
        background: `var(--${tone}-bg)`,
        borderRadius: 6,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <StatusDot tone={tone} pulse={exec.status === 'ENTRY_SUBMITTED'} />
        <span className="label" style={{ color: `var(--${tone})` }}>
          {exec.status.replace(/_/g, ' ')}
        </span>
        <span style={{ flex: 1 }} />
        <span style={{ fontSize: 10, color: 'var(--fg-2)', fontFamily: 'var(--font-mono)' }}>
          {now != null && armedAt != null ? fmtAgo(armedAt, now) : '—'}
        </span>
      </div>
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr 1fr 1fr',
          gap: 6,
          fontSize: 10.5,
          fontFamily: 'var(--font-mono)',
        }}
      >
        <KvMini label="Qty" value={`×${exec.qty}`} />
        <KvMini label="Limit" value={review.plan!.entry.toFixed(2)} />
        <KvMini label="Fill" value={exec.fillPx ? exec.fillPx.toFixed(2) : '—'} />
      </div>
      {exec.status === 'PENDING_ENTRY_SUBMISSION' && liveExecId != null && (
        <button
          type="button"
          onClick={() => onSubmit(liveExecId)}
          style={{
            marginTop: 8,
            width: '100%',
            padding: '7px 10px',
            border: '1px solid var(--info)',
            background: 'var(--info)',
            color: 'var(--bg-0)',
            borderRadius: 4,
            fontSize: 11.5,
            fontWeight: 700,
            cursor: 'pointer',
          }}
        >
          ▸ Submit Entry Order to IBKR
        </button>
      )}
    </div>
  );
}

function KvMini({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div
        style={{
          fontSize: 8.5,
          color: 'var(--fg-3)',
          letterSpacing: '0.06em',
          textTransform: 'uppercase',
        }}
      >
        {label}
      </div>
      <div className="num" style={{ fontSize: 12, color: 'var(--fg-0)', fontWeight: 600 }}>
        {value}
      </div>
    </div>
  );
}

const btnSecondary: CSSProperties = {
  padding: '5px 10px',
  border: '1px solid var(--hair)',
  background: 'var(--bg-1)',
  color: 'var(--fg-1)',
  borderRadius: 4,
  fontSize: 10.5,
  fontWeight: 600,
  cursor: 'pointer',
  fontFamily: 'inherit',
};
