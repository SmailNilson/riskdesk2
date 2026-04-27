'use client';

import { useEffect, useMemo, useState } from 'react';
import { BarGauge, Chip, RRLadder, SectionLabel } from '../lib/atoms';
import { Review } from '../lib/data';
import { api, MentorAnalyzeResponse } from '../../lib/api';

const PAGE_SIZE = 10;

function VerdictBadge({ v }: { v: Review['verdict'] }) {
  const cls =
    v === 'TAKE' ? 'verdict-take' : v === 'SKIP' ? 'verdict-skip' : v === 'WATCH' ? 'verdict-watch' : 'verdict-pending';
  return <span className={`verdict-badge ${cls}`}>{v}</span>;
}

function ConfidenceMeter({ v }: { v: number }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 6, flex: 1 }}>
      <BarGauge value={v} color={v >= 0.65 ? 'var(--up)' : v >= 0.5 ? 'var(--warn)' : 'var(--down)'} />
      <span className="mono" style={{ fontSize: 10, color: 'var(--ink-2)', minWidth: 28, textAlign: 'right' }}>
        {Math.round(v * 100)}%
      </span>
    </div>
  );
}

function ReviewCard({ r, active, onClick }: { r: Review; active: boolean; onClick: () => void }) {
  return (
    <div className="review-card" data-active={active} onClick={onClick}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
        <VerdictBadge v={r.verdict} />
        <span style={{ fontWeight: 700, fontSize: 12, color: 'var(--ink-0)' }}>{r.sym}</span>
        <span className="mono muted" style={{ fontSize: 10 }}>
          {r.tf}
        </span>
        <span style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 10 }}>
          {r.at}
        </span>
        {r.grouped > 1 && <Chip kind="ghost">×{r.grouped}</Chip>}
      </div>
      <ConfidenceMeter v={r.confidence} />
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
        {r.confluence.slice(0, 3).map((c, i) => (
          <Chip key={i} kind={r.verdict === 'TAKE' ? 'up' : r.verdict === 'SKIP' ? 'ghost' : 'warn'}>
            {c}
          </Chip>
        ))}
        {r.confluence.length > 3 && <Chip kind="ghost">+{r.confluence.length - 3}</Chip>}
      </div>
      {!r.eligible && r.reasonHold && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 6,
            fontSize: 10,
            color: 'var(--ink-3)',
          }}
        >
          <svg width="10" height="10" viewBox="0 0 10 10">
            <circle cx="5" cy="5" r="4" stroke="var(--ink-3)" fill="none" />
            <line x1="5" y1="3" x2="5" y2="6" stroke="var(--ink-3)" />
            <circle cx="5" cy="7.5" r="0.5" fill="var(--ink-3)" />
          </svg>
          {r.reasonHold}
        </div>
      )}
    </div>
  );
}

function ReviewDetail({
  r,
  onArm,
  onSkip,
}: {
  r: Review | undefined;
  onArm?: () => void;
  onSkip?: () => void;
}) {
  if (!r) return null;
  const p = r.plan;
  // Rationale is the most important field — fall back through any signal we
  // have so this section never shows blank space (which made the panel look
  // like it duplicated the list card).
  const rationale =
    (r.rationale && r.rationale.trim()) ||
    (r.confluence[0] && `Signal: ${r.confluence[0]}`) ||
    (r.verdict === 'WATCH'
      ? 'Monitoring — awaiting a higher-quality setup before this becomes actionable.'
      : r.verdict === 'SKIP'
      ? 'Setup did not qualify against the current playbook filters.'
      : 'No trade plan attached to this signal.');
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12, minWidth: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <VerdictBadge v={r.verdict} />
        <span style={{ fontSize: 14, fontWeight: 700, color: 'var(--ink-0)' }}>
          {r.sym}{' '}
          <span className="muted" style={{ fontWeight: 500, fontSize: 11 }}>
            · {r.tf}
          </span>
        </span>
        <span style={{ flex: 1 }} />
        <ConfidenceMeter v={r.confidence} />
      </div>
      <div style={{ fontSize: 12, lineHeight: 1.5, color: 'var(--ink-1)' }}>{rationale}</div>

      {p && (
        <>
          <SectionLabel>Plan</SectionLabel>
          <RRLadder side={p.side} entry={p.entry} sl={p.sl} tp1={p.tp1} tp2={p.tp2} last={p.entry + 0.04} />
          <div className="kv" style={{ gridTemplateColumns: '1fr 1fr 1fr 1fr' }}>
            <div className="k">Side</div>
            <div className="v" style={{ color: p.side === 'long' ? 'var(--up)' : 'var(--down)' }}>
              {p.side.toUpperCase()} ×{p.qty}
            </div>
            <div className="k">Entry</div>
            <div className="v">{p.entry.toFixed(2)}</div>
            <div className="k">SL</div>
            <div className="v down">{p.sl.toFixed(2)}</div>
            <div className="k">TP1 / TP2</div>
            <div className="v up">
              {p.tp1.toFixed(2)} / {p.tp2.toFixed(2)}
            </div>
            <div className="k">RR (T1/T2)</div>
            <div className="v">
              {p.rr1.toFixed(2)} / {p.rr2.toFixed(2)}R
            </div>
            <div className="k">Risk</div>
            <div className="v">${(p.qty * Math.abs(p.entry - p.sl) * 100).toFixed(0)}</div>
          </div>
        </>
      )}

      <SectionLabel>Confluence ({r.confluence.length})</SectionLabel>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 4 }}>
        {r.confluence.map((c, i) => (
          <Chip key={i} kind={r.verdict === 'TAKE' ? 'up' : 'warn'}>
            {c}
          </Chip>
        ))}
      </div>

      {r.risks.length > 0 && (
        <>
          <SectionLabel>Risks</SectionLabel>
          <ul
            style={{
              margin: 0,
              paddingLeft: 14,
              fontSize: 11,
              color: 'var(--ink-2)',
              lineHeight: 1.6,
            }}
          >
            {r.risks.map((rk, i) => (
              <li key={i}>{rk}</li>
            ))}
          </ul>
        </>
      )}

      {r.eligible && p && (
        <div style={{ display: 'flex', gap: 6, marginTop: 4 }}>
          <button type="button" className="btn btn-accent btn-lg" style={{ flex: 1 }} onClick={onArm}>
            Arm {p.side.toUpperCase()} ×{p.qty} @ {p.entry.toFixed(2)}
          </button>
          <button type="button" className="btn btn-lg" onClick={onSkip}>
            Skip
          </button>
        </div>
      )}
    </div>
  );
}

interface MentorDeskProps {
  reviews: Review[];
  instrument: string;
  tf: string;
  onArm?: (r: Review) => void;
  onSkip?: (r: Review) => void;
}

export function MentorDesk({ reviews, instrument, tf, onArm, onSkip }: MentorDeskProps) {
  const [activeId, setActiveId] = useState<string>(reviews[0]?.id ?? '');
  const [filter, setFilter] = useState<'all' | 'eligible' | 'take'>('all');
  const [page, setPage] = useState(0);

  // Always keep an active review when the list changes
  useEffect(() => {
    if (!reviews.find((r) => r.id === activeId) && reviews[0]) setActiveId(reviews[0].id);
  }, [reviews, activeId]);

  const active = reviews.find((r) => r.id === activeId);

  const filtered = useMemo(() => {
    if (filter === 'eligible') return reviews.filter((r) => r.eligible);
    if (filter === 'take') return reviews.filter((r) => r.verdict === 'TAKE');
    return reviews;
  }, [filter, reviews]);

  useEffect(() => {
    setPage(0);
  }, [filter]);

  const pageCount = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE));
  const safePage = Math.min(page, pageCount - 1);
  const pageStart = safePage * PAGE_SIZE;
  const pageItems = filtered.slice(pageStart, pageStart + PAGE_SIZE);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 8,
          padding: '10px 14px',
          borderBottom: '1px solid var(--line)',
        }}
      >
        <svg width="14" height="14" viewBox="0 0 14 14">
          <circle cx="7" cy="7" r="6" stroke="var(--accent)" strokeWidth="1.4" fill="none" />
          <circle cx="7" cy="7" r="2" fill="var(--accent)" />
        </svg>
        <span
          style={{ fontSize: 12, fontWeight: 700, color: 'var(--ink-0)', letterSpacing: '0.02em' }}
        >
          AI Mentor Desk
        </span>
        <Chip kind="accent">live</Chip>
        <span style={{ flex: 1 }} />
        <button type="button" className="btn btn-sm btn-ghost">
          Ask ⌘ /
        </button>
      </div>

      <div
        style={{
          display: 'flex',
          gap: 4,
          padding: '8px 12px',
          borderBottom: '1px solid var(--line)',
        }}
      >
        {[
          { v: 'all' as const, l: 'All' },
          { v: 'eligible' as const, l: 'Eligible' },
          { v: 'take' as const, l: 'TAKE' },
        ].map((f) => (
          <button
            key={f.v}
            type="button"
            className="btn btn-sm"
            style={{
              background: filter === f.v ? 'var(--s3)' : 'var(--s2)',
              color: filter === f.v ? 'var(--ink-0)' : 'var(--ink-2)',
            }}
            onClick={() => setFilter(f.v)}
          >
            {f.l}
          </button>
        ))}
        <span style={{ flex: 1 }} />
        <span className="mono muted" style={{ fontSize: 10, alignSelf: 'center' }}>
          {filtered.length} reviews · ttl 4m
        </span>
      </div>

      <div
        style={{
          // Proportional split with the detail section below: 1.4× their share
          // so 10 cards stay readable without crushing the detail pane.
          // overflow-x: hidden + min-width: 0 prevent the long confluence
          // chips from triggering a stray horizontal scrollbar.
          flex: '1.4 1 0',
          minHeight: 120,
          overflowY: 'auto',
          overflowX: 'hidden',
          padding: 10,
          display: 'flex',
          flexDirection: 'column',
          gap: 8,
          minWidth: 0,
        }}
      >
        {pageItems.map((r) => (
          <ReviewCard key={r.id} r={r} active={r.id === activeId} onClick={() => setActiveId(r.id)} />
        ))}
        {pageItems.length === 0 && (
          <div
            style={{
              padding: '16px 8px',
              fontSize: 11,
              color: 'var(--ink-3)',
              fontStyle: 'italic',
              textAlign: 'center',
            }}
          >
            No reviews match this filter.
          </div>
        )}
      </div>

      {pageCount > 1 && (
        <div className="mentor-pager">
          <button
            type="button"
            className="pager-btn"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={safePage === 0}
            aria-label="Previous"
          >
            ‹
          </button>
          {/* Up to 7 dots — beyond that the dot strip is unreadable, so we
              fall back to a numeric "n / m" indicator only. */}
          {pageCount <= 7 ? (
            <div className="pager-dots">
              {Array.from({ length: pageCount }).map((_, i) => (
                <button
                  key={i}
                  type="button"
                  className={'pager-dot' + (i === safePage ? ' is-active' : '')}
                  onClick={() => setPage(i)}
                  aria-label={'Page ' + (i + 1)}
                />
              ))}
            </div>
          ) : (
            <span className="mono" style={{ fontSize: 10, color: 'var(--ink-2)', fontWeight: 600 }}>
              {safePage + 1} / {pageCount}
            </span>
          )}
          <span className="pager-meta mono">
            {pageStart + 1}–{Math.min(pageStart + PAGE_SIZE, filtered.length)} / {filtered.length}
          </span>
          <button
            type="button"
            className="pager-btn"
            onClick={() => setPage((p) => Math.min(pageCount - 1, p + 1))}
            disabled={safePage === pageCount - 1}
            aria-label="Next"
          >
            ›
          </button>
        </div>
      )}

      <div
        style={{
          borderTop: '1px solid var(--line)',
          padding: 14,
          flex: '1 1 0',
          minHeight: 180,
          overflowY: 'auto',
          overflowX: 'hidden',
          background: 'var(--s1)',
          minWidth: 0,
        }}
      >
        <ReviewDetail
          r={active}
          onArm={() => active && onArm && onArm(active)}
          onSkip={() => active && onSkip && onSkip(active)}
        />
      </div>

      <ManualAsk instrument={instrument} tf={tf} />
    </div>
  );
}

function ManualAsk({ instrument, tf }: { instrument: string; tf: string }) {
  const [question, setQuestion] = useState('');
  const [loading, setLoading] = useState(false);
  const [response, setResponse] = useState<MentorAnalyzeResponse | null>(null);
  const [error, setError] = useState<string | null>(null);

  const ask = async (q: string) => {
    const text = q.trim();
    if (!text || loading) return;
    setLoading(true);
    setError(null);
    try {
      const res = await api.analyzeMentor({
        instrument,
        timeframe: tf,
        question: text,
        source: 'manual-mentor:dashboard',
      });
      setResponse(res);
    } catch (e) {
      setError(String((e as Error)?.message || e));
    } finally {
      setLoading(false);
    }
  };

  const quickPrompts = [
    "What's the bias?",
    'Should I trail tighter?',
    'Risk in next 30m?',
    `News check ${instrument}`,
  ];

  const a = response?.analysis ?? null;

  return (
    <div
      style={{
        borderTop: '1px solid var(--line)',
        padding: 12,
        background: 'var(--s2)',
        maxHeight: 360,
        overflowY: 'auto',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6 }}>
        <span className="section-label">Ask Mentor</span>
        <Chip kind="ghost">{response?.model || 'gemini'}</Chip>
        {loading && <Chip kind="warn">analyzing…</Chip>}
      </div>
      <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginBottom: 8 }}>
        {quickPrompts.map((p) => (
          <button
            key={p}
            type="button"
            onClick={() => {
              setQuestion(p);
              void ask(p);
            }}
            disabled={loading}
            style={{
              fontSize: 10,
              padding: '2px 8px',
              borderRadius: 3,
              background: 'var(--s1)',
              border: '1px solid var(--line)',
              color: 'var(--ink-2)',
              cursor: loading ? 'not-allowed' : 'pointer',
            }}
          >
            {p}
          </button>
        ))}
      </div>
      <div style={{ display: 'flex', gap: 6 }}>
        <input
          value={question}
          onChange={(e) => setQuestion(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') void ask(question);
          }}
          disabled={loading}
          placeholder={`Ask about ${instrument} ${tf} setup, position, or context…`}
          style={{
            flex: 1,
            height: 30,
            padding: '0 10px',
            background: 'var(--s1)',
            border: '1px solid var(--line)',
            borderRadius: 4,
            fontSize: 12,
            color: 'var(--ink-1)',
          }}
        />
        <button
          type="button"
          className="btn btn-accent btn-sm"
          onClick={() => void ask(question)}
          disabled={loading || !question.trim()}
        >
          {loading ? '…' : 'Ask'}
        </button>
      </div>
      {error && (
        <div style={{ marginTop: 8, fontSize: 11, color: 'var(--down)' }}>
          Error: {error}
        </div>
      )}
      {a && (
        <div style={{ marginTop: 10, display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span className="section-label">Verdict</span>
            <Chip
              kind={
                /TAKE|LONG|SHORT/i.test(a.verdict)
                  ? 'up'
                  : /SKIP|NO_TRADE|AVOID/i.test(a.verdict)
                  ? 'down'
                  : 'warn'
              }
            >
              {a.verdict}
            </Chip>
          </div>
          <div style={{ fontSize: 11, color: 'var(--ink-1)', lineHeight: 1.4 }}>
            {a.technicalQuickAnalysis}
          </div>
          {a.proposedTradePlan &&
            (a.proposedTradePlan.entryPrice != null || a.proposedTradePlan.rationale) && (
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 4, fontSize: 11 }}>
                {a.proposedTradePlan.entryPrice != null && (
                  <>
                    <span className="muted">Entry</span>
                    <span className="mono">{a.proposedTradePlan.entryPrice.toFixed(2)}</span>
                  </>
                )}
                {a.proposedTradePlan.stopLoss != null && (
                  <>
                    <span className="muted">Stop</span>
                    <span className="mono down">{a.proposedTradePlan.stopLoss.toFixed(2)}</span>
                  </>
                )}
                {a.proposedTradePlan.takeProfit != null && (
                  <>
                    <span className="muted">Target</span>
                    <span className="mono up">{a.proposedTradePlan.takeProfit.toFixed(2)}</span>
                  </>
                )}
                {a.proposedTradePlan.rewardToRiskRatio != null && (
                  <>
                    <span className="muted">RR</span>
                    <span className="mono">{a.proposedTradePlan.rewardToRiskRatio.toFixed(2)}R</span>
                  </>
                )}
              </div>
            )}
          {a.errors.length > 0 && (
            <div style={{ fontSize: 10, color: 'var(--down)' }}>
              ⚠ {a.errors.slice(0, 2).join(' · ')}
            </div>
          )}
          {a.improvementTip && (
            <div style={{ fontSize: 10, color: 'var(--ink-3)', fontStyle: 'italic' }}>
              💡 {a.improvementTip}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
