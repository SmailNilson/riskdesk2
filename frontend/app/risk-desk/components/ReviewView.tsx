'use client';

import { Fragment, useState } from 'react';
import { api, BacktestResult } from '../../lib/api';
import { BarGauge, Chip, Panel, SectionLabel, Sparkline, StatusDot } from '../lib/atoms';
import { Backtest, SimulationStats, SimulationView, TradeDecisionView, Trailing } from '../lib/data';

function BacktestPanel({ b }: { b: Backtest }) {
  return (
    <Panel
      title="Backtest · 30d"
      right={<Chip kind="accent">PF {b.profitFactor.toFixed(2)}</Chip>}
    >
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr 1fr', gap: 10 }}>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Trades</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--ink-0)' }}>
            {b.trades}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Win rate</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--up)' }}>
            {Math.round(b.winRate * 100)}%
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Avg RR</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--ink-0)' }}>
            {b.avgRR.toFixed(2)}R
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Sharpe</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--ink-0)' }}>
            {b.sharpe.toFixed(2)}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Expectancy</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--up)' }}>
            {b.expectancy.toFixed(2)}R
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Max DD</div>
          <div className="mono" style={{ fontSize: 16, color: 'var(--down)' }}>
            ${b.maxDD}
          </div>
        </div>
        <div style={{ gridColumn: 'span 2' }}>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Best setup</div>
          <div style={{ fontSize: 12, color: 'var(--ink-0)', fontWeight: 500 }}>{b.bestSetup}</div>
        </div>
      </div>
      <SectionLabel>Equity curve · cumulative R</SectionLabel>
      <Sparkline values={b.equity} color="var(--accent)" w={420} h={64} fill />
      <div style={{ display: 'flex', justifyContent: 'space-between' }}>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Best hour</div>
          <div className="mono" style={{ color: 'var(--up)' }}>
            {b.bestHour}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 10, color: 'var(--ink-3)' }}>Worst hour</div>
          <div className="mono" style={{ color: 'var(--down)' }}>
            {b.worstHour}
          </div>
        </div>
      </div>
    </Panel>
  );
}

function TrailingPanel({ t }: { t: Trailing }) {
  return (
    <Panel
      title="Trailing-Stop Comparison"
      right={<Chip kind="accent">recommended ATR×1.2</Chip>}
    >
      <div
        style={{
          display: 'grid',
          gridTemplateColumns: '1fr 70px 70px 80px 80px',
          gap: 0,
          fontSize: 11,
        }}
      >
        {['Mode', 'Win%', 'Avg RR', 'PF', 'Expectancy'].map((h) => (
          <div
            key={h}
            style={{
              fontSize: 10,
              color: 'var(--ink-3)',
              letterSpacing: '0.06em',
              textTransform: 'uppercase',
              padding: '4px 6px',
              borderBottom: '1px solid var(--line)',
              textAlign: h === 'Mode' ? 'left' : 'right',
              fontWeight: 600,
            }}
          >
            {h}
          </div>
        ))}
        {t.modes.map((m) => (
          <Fragment key={m.name}>
            <div
              style={{
                padding: '8px 6px',
                display: 'flex',
                alignItems: 'center',
                gap: 6,
                borderBottom: '1px solid var(--line)',
                background: m.active ? 'var(--accent-glow)' : 'transparent',
              }}
            >
              {m.active && <StatusDot kind="accent" />}
              <span
                style={{
                  color: m.active ? 'var(--accent)' : 'var(--ink-1)',
                  fontWeight: m.active ? 600 : 400,
                }}
              >
                {m.name}
              </span>
            </div>
            <div
              style={{
                padding: '8px 6px',
                textAlign: 'right',
                fontFamily: 'var(--font-mono)',
                borderBottom: '1px solid var(--line)',
              }}
            >
              {Math.round(m.winRate * 100)}%
            </div>
            <div
              style={{
                padding: '8px 6px',
                textAlign: 'right',
                fontFamily: 'var(--font-mono)',
                borderBottom: '1px solid var(--line)',
              }}
            >
              {m.avgRR.toFixed(2)}
            </div>
            <div
              style={{
                padding: '8px 6px',
                textAlign: 'right',
                fontFamily: 'var(--font-mono)',
                borderBottom: '1px solid var(--line)',
              }}
            >
              {m.profitFactor.toFixed(2)}
            </div>
            <div
              style={{
                padding: '8px 6px',
                textAlign: 'right',
                fontFamily: 'var(--font-mono)',
                borderBottom: '1px solid var(--line)',
                color: 'var(--up)',
              }}
            >
              {m.expectancy.toFixed(2)}R
            </div>
          </Fragment>
        ))}
      </div>
      <div
        style={{
          padding: 8,
          background: 'var(--accent-glow)',
          borderRadius: 4,
          fontSize: 11,
          color: 'var(--ink-1)',
          border: '1px solid color-mix(in oklch, var(--accent) 30%, transparent)',
        }}
      >
        <strong style={{ color: 'var(--accent)' }}>→</strong> {t.recommendation}
      </div>
    </Panel>
  );
}

function fmtTime(ts: string): string {
  try {
    return new Date(ts).toLocaleTimeString('en-US', { hour12: false, hour: '2-digit', minute: '2-digit' });
  } catch {
    return ts;
  }
}

function fmtDate(ts: string): string {
  try {
    return new Date(ts).toLocaleDateString('en-US', { month: 'short', day: '2-digit' });
  } catch {
    return ts;
  }
}

function statusTone(s: string): 'up' | 'down' | 'warn' | 'ghost' {
  const u = s.toUpperCase();
  if (u === 'WIN' || u === 'TP_HIT') return 'up';
  if (u === 'LOSS' || u === 'SL_HIT' || u === 'ERROR') return 'down';
  if (u === 'PENDING_ENTRY' || u === 'ACTIVE') return 'warn';
  return 'ghost';
}

function SimulationsStatsPanel({
  stats,
  rows,
  instrument,
}: {
  stats: SimulationStats;
  rows: SimulationView[];
  instrument: string;
}) {
  const resolved = stats.win + stats.loss;
  return (
    <Panel
      title={`Simulations · ${instrument}`}
      right={
        <Chip kind={stats.winRatePct != null && stats.winRatePct >= 50 ? 'up' : 'ghost'}>
          {stats.winRatePct != null ? `${stats.winRatePct.toFixed(0)}% wr` : 'no data'}
        </Chip>
      }
    >
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(7, 1fr)', gap: 8 }}>
        {(
          [
            ['Total', stats.total, 'var(--ink-1)'],
            ['WIN', stats.win, 'var(--up)'],
            ['LOSS', stats.loss, 'var(--down)'],
            ['MISSED', stats.missed, 'var(--ink-2)'],
            ['CANCELLED', stats.cancelled, 'var(--ink-3)'],
            ['PENDING', stats.pending, 'var(--warn)'],
            ['ACTIVE', stats.active, 'var(--accent)'],
          ] as Array<[string, number, string]>
        ).map(([label, value, color]) => (
          <div key={label}>
            <div
              style={{
                fontSize: 9,
                color: 'var(--ink-3)',
                letterSpacing: '0.06em',
                textTransform: 'uppercase',
              }}
            >
              {label}
            </div>
            <div className="mono" style={{ fontSize: 16, color, fontWeight: 600 }}>
              {value}
            </div>
          </div>
        ))}
      </div>
      {resolved > 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 4 }}>
          <span style={{ fontSize: 10, color: 'var(--ink-3)' }}>Win rate</span>
          <BarGauge
            value={stats.winRatePct != null ? stats.winRatePct / 100 : 0}
            color={
              stats.winRatePct != null && stats.winRatePct >= 60
                ? 'var(--up)'
                : stats.winRatePct != null && stats.winRatePct >= 45
                ? 'var(--warn)'
                : 'var(--down)'
            }
          />
          {stats.avgMfe != null && (
            <span className="mono muted" style={{ fontSize: 10 }}>
              MFE avg {stats.avgMfe.toFixed(2)}
            </span>
          )}
        </div>
      )}
      <SectionLabel>Last simulations</SectionLabel>
      <div style={{ maxHeight: 220, overflowY: 'auto' }}>
        {rows.length === 0 ? (
          <div style={{ fontSize: 11, color: 'var(--ink-3)', fontStyle: 'italic', padding: '12px 4px' }}>
            No simulations yet for {instrument}.
          </div>
        ) : (
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: '60px 50px 80px 1fr 60px 60px',
              gap: 4,
              fontSize: 11,
              padding: '4px 0',
              fontFamily: 'var(--font-mono)',
            }}
          >
            <div className="muted" style={{ fontSize: 9, letterSpacing: '0.06em' }}>
              TIME
            </div>
            <div className="muted" style={{ fontSize: 9 }}>
              SIDE
            </div>
            <div className="muted" style={{ fontSize: 9 }}>
              STATUS
            </div>
            <div className="muted" style={{ fontSize: 9 }}>
              TRAIL
            </div>
            <div className="muted" style={{ fontSize: 9, textAlign: 'right' }}>
              MFE
            </div>
            <div className="muted" style={{ fontSize: 9, textAlign: 'right' }}>
              DD
            </div>
            {rows.slice(0, 12).map((r) => (
              <Fragment key={r.id}>
                <span className="muted">{fmtTime(r.createdAt)}</span>
                <span style={{ color: r.action === 'LONG' ? 'var(--up)' : 'var(--down)' }}>{r.action}</span>
                <span>
                  <Chip kind={statusTone(r.status)}>{r.status}</Chip>
                </span>
                <span style={{ color: 'var(--ink-2)' }}>
                  {r.trailingStopResult ?? '—'}
                </span>
                <span style={{ textAlign: 'right', color: 'var(--up)' }}>
                  {r.bestFavorablePrice != null ? r.bestFavorablePrice.toFixed(2) : '—'}
                </span>
                <span style={{ textAlign: 'right', color: 'var(--down)' }}>
                  {r.maxDrawdownPoints != null ? r.maxDrawdownPoints.toFixed(2) : '—'}
                </span>
              </Fragment>
            ))}
          </div>
        )}
      </div>
    </Panel>
  );
}

function TradeDecisionsPanel({
  decisions,
  instrument,
}: {
  decisions: TradeDecisionView[];
  instrument: string;
}) {
  return (
    <Panel
      title={`Trade Decisions · ${instrument}`}
      right={<Chip kind="ghost">{decisions.length} recent</Chip>}
    >
      {decisions.length === 0 ? (
        <div style={{ fontSize: 11, color: 'var(--ink-3)', fontStyle: 'italic' }}>
          No persisted decisions for {instrument} yet.
        </div>
      ) : (
        <div style={{ maxHeight: 380, overflowY: 'auto' }}>
          {decisions.slice(0, 20).map((d) => {
            const dirColor =
              d.direction === 'LONG'
                ? 'var(--up)'
                : d.direction === 'SHORT'
                ? 'var(--down)'
                : 'var(--ink-3)';
            const eligOk = d.eligibility === 'ELIGIBLE';
            return (
              <div
                key={`${d.id ?? d.createdAt}-${d.timeframe}`}
                style={{
                  borderBottom: '1px solid var(--line)',
                  padding: '6px 4px',
                  display: 'grid',
                  gridTemplateColumns: '1fr auto',
                  gap: 6,
                  alignItems: 'start',
                }}
              >
                <div style={{ display: 'flex', flexDirection: 'column', gap: 3, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <span
                      className="mono"
                      style={{ fontSize: 10, color: 'var(--ink-3)' }}
                    >
                      {fmtDate(d.createdAt)} {fmtTime(d.createdAt)}
                    </span>
                    <Chip kind="ghost">{d.timeframe}</Chip>
                    <span style={{ color: dirColor, fontWeight: 700, fontSize: 11 }}>{d.direction}</span>
                    {d.setupType && <Chip kind="ghost">{d.setupType}</Chip>}
                    {d.zoneName && (
                      <span className="muted" style={{ fontSize: 10 }}>
                        {d.zoneName}
                      </span>
                    )}
                  </div>
                  <div style={{ display: 'flex', gap: 10, fontSize: 10, color: 'var(--ink-2)' }}>
                    <span>
                      verdict:{' '}
                      <span style={{ color: 'var(--ink-1)', fontWeight: 500 }}>{d.verdict}</span>
                    </span>
                    {d.entry != null && (
                      <span className="mono">
                        E {d.entry.toFixed(2)}
                        {d.stop != null ? ` · SL ${d.stop.toFixed(2)}` : ''}
                        {d.tp1 != null ? ` · TP ${d.tp1.toFixed(2)}` : ''}
                        {d.rrRatio != null ? ` · ${d.rrRatio.toFixed(2)}R` : ''}
                      </span>
                    )}
                  </div>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 4 }}>
                  <Chip kind={eligOk ? 'up' : 'ghost'}>{d.eligibility}</Chip>
                  <Chip kind={d.status === 'DONE' ? 'up' : d.status === 'ERROR' ? 'down' : 'warn'}>
                    {d.status}
                  </Chip>
                </div>
              </div>
            );
          })}
        </div>
      )}
    </Panel>
  );
}

// Configurable backtest runner — restores the legacy parameter controls so the
// trader can sweep stop-loss / BB length / quantity etc. against live IBKR data.
// Collapsed by default (advanced toggle) so it doesn't bloat the Review view.
function BacktestRunnerPanel({ instrument, tf }: { instrument: string; tf: string }) {
  const [open, setOpen] = useState(false);
  const [running, setRunning] = useState(false);
  const [result, setResult] = useState<BacktestResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [qty, setQty] = useState('2');
  const [stopLoss, setStopLoss] = useState('300');
  const [useAtr, setUseAtr] = useState(false);
  const [emaPeriod, setEmaPeriod] = useState('0');
  const [bbLength, setBbLength] = useState('20');
  const [useBbTp, setUseBbTp] = useState(true);
  const [closeEod, setCloseEod] = useState(false);
  const [closeEow, setCloseEow] = useState(false);
  const [pyramiding, setPyramiding] = useState('0');

  const run = async () => {
    setRunning(true);
    setError(null);
    try {
      const res = await api.runBacktest({
        instrument,
        timeframe: tf,
        qty: Number(qty) || 1,
        stopLossPoints: Number(stopLoss) || 0,
        atrTrailingStop: useAtr,
        emaFilterPeriod: Number(emaPeriod) || 0,
        bollingerLength: Number(bbLength) || 20,
        bollingerTakeProfit: useBbTp,
        closeEndOfDay: closeEod,
        closeEndOfWeek: closeEow,
        pyramiding: Number(pyramiding) || 0,
      });
      setResult(res);
    } catch (e) {
      setError(String((e as Error)?.message || e));
    } finally {
      setRunning(false);
    }
  };

  return (
    <Panel
      title={`Backtest runner · ${instrument} ${tf}`}
      right={
        <button
          type="button"
          className="btn btn-sm btn-ghost"
          onClick={() => setOpen((o) => !o)}
        >
          {open ? 'Hide params' : 'Show params'}
        </button>
      }
    >
      {open && (
        <div
          style={{
            display: 'grid',
            gridTemplateColumns: 'repeat(4, 1fr)',
            gap: 6,
            background: 'var(--s2)',
            border: '1px solid var(--line)',
            borderRadius: 4,
            padding: 8,
          }}
        >
          <ParamField label="Qty" value={qty} onChange={setQty} />
          <ParamField label="SL pts" value={stopLoss} onChange={setStopLoss} />
          <ParamField label="EMA filter" value={emaPeriod} onChange={setEmaPeriod} />
          <ParamField label="BB length" value={bbLength} onChange={setBbLength} />
          <ParamField label="Pyramiding" value={pyramiding} onChange={setPyramiding} />
          <ParamCheckbox label="ATR trail" value={useAtr} onChange={setUseAtr} />
          <ParamCheckbox label="BB TP" value={useBbTp} onChange={setUseBbTp} />
          <ParamCheckbox label="Close EOD" value={closeEod} onChange={setCloseEod} />
          <ParamCheckbox label="Close EOW" value={closeEow} onChange={setCloseEow} />
          <button
            type="button"
            className="btn btn-accent btn-sm"
            onClick={() => void run()}
            disabled={running}
            style={{ gridColumn: 'span 3' }}
          >
            {running ? 'Running…' : 'Run backtest'}
          </button>
        </div>
      )}
      {error && (
        <div style={{ fontSize: 11, color: 'var(--down)', marginTop: 4 }}>
          Error: {error}
        </div>
      )}
      {result && (
        <div style={{ marginTop: 8 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(6, 1fr)', gap: 8 }}>
            {(
              [
                ['Trades', result.totalTrades.toString(), 'var(--ink-1)'],
                ['Win %', `${(result.winRate * 100).toFixed(1)}%`, 'var(--up)'],
                ['PF', result.profitFactor.toFixed(2), 'var(--ink-1)'],
                ['Sharpe', result.sharpeRatio.toFixed(2), 'var(--ink-1)'],
                ['PnL', `${result.totalPnl >= 0 ? '+' : ''}${result.totalPnl.toFixed(0)}`, result.totalPnl >= 0 ? 'var(--up)' : 'var(--down)'],
                ['MaxDD %', `${(result.maxDrawdownPct * 100).toFixed(1)}%`, 'var(--down)'],
              ] as Array<[string, string, string]>
            ).map(([label, value, color]) => (
              <div key={label}>
                <div
                  style={{
                    fontSize: 9,
                    color: 'var(--ink-3)',
                    letterSpacing: '0.06em',
                    textTransform: 'uppercase',
                  }}
                >
                  {label}
                </div>
                <div className="mono" style={{ fontSize: 14, color, fontWeight: 600 }}>
                  {value}
                </div>
              </div>
            ))}
          </div>
          {result.equityCurve.length > 0 && (
            <Sparkline values={result.equityCurve} color="var(--accent)" w={420} h={48} fill />
          )}
        </div>
      )}
    </Panel>
  );
}

function ParamField({
  label,
  value,
  onChange,
}: {
  label: string;
  value: string;
  onChange: (v: string) => void;
}) {
  return (
    <label style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
      <span
        style={{
          fontSize: 9,
          color: 'var(--ink-3)',
          letterSpacing: '0.06em',
          textTransform: 'uppercase',
        }}
      >
        {label}
      </span>
      <input
        value={value}
        onChange={(e) => onChange(e.target.value)}
        inputMode="decimal"
        style={{
          height: 22,
          padding: '0 6px',
          background: 'var(--s1)',
          border: '1px solid var(--line)',
          borderRadius: 3,
          color: 'var(--ink-1)',
          fontFamily: 'var(--font-mono)',
          fontSize: 11,
        }}
      />
    </label>
  );
}

function ParamCheckbox({
  label,
  value,
  onChange,
}: {
  label: string;
  value: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label
      style={{
        display: 'flex',
        alignItems: 'center',
        gap: 4,
        fontSize: 11,
        color: 'var(--ink-2)',
        height: '100%',
      }}
    >
      <input type="checkbox" checked={value} onChange={(e) => onChange(e.target.checked)} />
      {label}
    </label>
  );
}

export function ReviewView({
  backtest,
  trailing,
  decisions,
  simulations,
  simulationStats,
  instrument,
  tf,
}: {
  backtest: Backtest;
  trailing: Trailing;
  decisions: TradeDecisionView[];
  simulations: SimulationView[];
  simulationStats: SimulationStats;
  instrument: string;
  tf: string;
}) {
  return (
    <div
      style={{
        display: 'grid',
        gridTemplateColumns: '1.2fr 1fr',
        gap: 12,
        padding: 12,
      }}
    >
      <BacktestPanel b={backtest} />
      <SimulationsStatsPanel stats={simulationStats} rows={simulations} instrument={instrument} />
      <div style={{ gridColumn: '1 / -1' }}>
        <BacktestRunnerPanel instrument={instrument} tf={tf} />
      </div>
      <div style={{ gridColumn: '1 / -1' }}>
        <TradeDecisionsPanel decisions={decisions} instrument={instrument} />
      </div>
      <div style={{ gridColumn: '1 / -1' }}>
        <TrailingPanel t={trailing} />
      </div>
    </div>
  );
}
