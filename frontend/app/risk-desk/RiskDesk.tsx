'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { AlertsRail } from './components/Alerts';
import { LiveChart } from './components/Chart';
import { RiskDeskErrorBoundary } from './components/ErrorBoundary';
import { ExecuteView } from './components/ExecuteView';
import { ManualAsk, MentorDesk } from './components/Mentor';
import { MetricsBar, RolloverBanner } from './components/Metrics';
import { ReviewView } from './components/ReviewView';
import { RiskAlertsBar } from './components/RiskAlertsBar';
import { SetupView } from './components/SetupView';
import { TopBar, ViewMode } from './components/TopBar';
import { TweakRadio, TweakSection, TweakSlider, TweakToggle, TweaksPanel } from './components/TweaksPanel';
import { RiskDeskProvider, useRiskDesk } from './lib/RiskDeskContext';

interface Tweaks {
  tone: 'zinc' | 'slate' | 'true' | 'warm';
  theme: 'dark' | 'light';
  density: 'comfortable' | 'compact';
  accentHue: number;
  view: ViewMode;
  scenario: 'live' | 'halt' | 'flat';
  showRolloverBanner: boolean;
  showTicker: boolean;
  showAlertsRail: boolean;
  showMentorDesk: boolean;
}

const TWEAK_DEFAULTS: Tweaks = {
  tone: 'zinc',
  theme: 'dark',
  density: 'comfortable',
  accentHue: 158,
  view: 'setup',
  scenario: 'live',
  showRolloverBanner: true,
  showTicker: true,
  showAlertsRail: true,
  showMentorDesk: true,
};

export function RiskDesk() {
  return (
    <RiskDeskErrorBoundary label="root">
      <RiskDeskProvider>
        <RiskDeskShell />
      </RiskDeskProvider>
    </RiskDeskErrorBoundary>
  );
}

function RiskDeskShell() {
  const D = useRiskDesk();
  const [tweaks, setTweaks] = useState<Tweaks>(TWEAK_DEFAULTS);
  const setTweak = useCallback(<K extends keyof Tweaks>(key: K, value: Tweaks[K]) => {
    setTweaks((prev) => ({ ...prev, [key]: value }));
  }, []);

  // Instrument + tf live in the context so per-instrument REST fetches trigger
  // automatically when the user clicks an instrument tab or timeframe.
  const instrument = D.instrumentSym;
  const setInstrument = D.setInstrumentSym;
  const tf = D.tf;
  const setTf = D.setTf;
  const [showRollover, setShowRollover] = useState(true);

  // Resizable chart height as a *percentage* of the workspace column so the
  // chart adapts to viewport changes (Tailscale, projector, fullscreen demo).
  // 50% default, clamp [25%..80%] so the panels below always have room.
  const [chartPct, setChartPct] = useState(50);
  const workspaceColRef = useRef<HTMLDivElement | null>(null);
  const startResize = useCallback(
    (e: React.MouseEvent) => {
      e.preventDefault();
      const col = workspaceColRef.current;
      if (!col) return;
      const startY = e.clientY;
      const startPct = chartPct;
      const colHeight = col.clientHeight;
      const onMove = (ev: MouseEvent) => {
        const dy = ev.clientY - startY;
        const dPct = (dy / colHeight) * 100;
        setChartPct(Math.max(25, Math.min(80, startPct + dPct)));
      };
      const onUp = () => {
        window.removeEventListener('mousemove', onMove);
        window.removeEventListener('mouseup', onUp);
        document.body.style.cursor = '';
        document.body.style.userSelect = '';
      };
      document.body.style.cursor = 'row-resize';
      document.body.style.userSelect = 'none';
      window.addEventListener('mousemove', onMove);
      window.addEventListener('mouseup', onUp);
    },
    [chartPct]
  );

  // ⌘1/2/3 view switching — only registered on the client to avoid SSR drift.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (!e.metaKey && !e.ctrlKey) return;
      if (e.key === '1') {
        setTweak('view', 'setup');
        e.preventDefault();
      } else if (e.key === '2') {
        setTweak('view', 'execute');
        e.preventDefault();
      } else if (e.key === '3') {
        setTweak('view', 'review');
        e.preventDefault();
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [setTweak]);

  // The chart highlights the position currently in the visible instrument
  const activePos = D.positions.find((p) => p.sym === instrument) ?? null;

  return (
    <div
      className="app"
      data-tone={tweaks.tone}
      data-theme={tweaks.theme}
      data-density={tweaks.density}
      style={{ ['--accent-h' as string]: String(tweaks.accentHue) }}
    >
      <TopBar
        instruments={D.instruments}
        instrument={instrument}
        onInstrument={setInstrument}
        view={tweaks.view}
        onView={(v) => setTweak('view', v)}
        tf={tf}
        onTf={setTf}
        connected={D.wsConnected || D.backendReachable}
        latencyMs={D.latencyMs}
      />

      {tweaks.showTicker ? <MetricsBar portfolio={D.portfolio} watchlist={D.watchlist} /> : <div />}

      {tweaks.showRolloverBanner && showRollover ? (
        <RolloverBanner rollover={D.rollover} onDismiss={() => setShowRollover(false)} />
      ) : (
        <div />
      )}

      <div
        className="workspace"
        style={{
          gridTemplateColumns: tweaks.showMentorDesk ? '1fr 380px' : '1fr',
        }}
      >
        <div
          ref={workspaceColRef}
          style={{
            display: 'flex',
            flexDirection: 'column',
            minHeight: 0,
            minWidth: 0,
            overflow: 'hidden',
          }}
        >
          <div
            style={{
              flex: `0 0 ${chartPct}%`,
              minHeight: 0,
              display: 'flex',
              flexDirection: 'column',
            }}
          >
            <LiveChart
              symbol={instrument}
              tf={tf}
              candles={D.candles}
              ema9={D.ema9}
              ema50={D.ema50}
              ema200={D.ema200}
              bbUpper={D.bbUpper}
              bbLower={D.bbLower}
              bbBasis={D.bbBasis}
              smc={D.smc}
              activePosition={activePos}
            />
          </div>
          {/* Drag handle — TradingView-style horizontal splitter. Drag to
              resize the chart between 25–80% of the column height. */}
          <div
            role="separator"
            aria-orientation="horizontal"
            aria-label="Resize chart"
            aria-valuenow={chartPct}
            onMouseDown={startResize}
            style={{
              height: 6,
              flexShrink: 0,
              cursor: 'row-resize',
              background: 'var(--s0)',
              borderTop: '1px solid var(--line)',
              borderBottom: '1px solid var(--line)',
              position: 'relative',
            }}
          >
            <div
              style={{
                position: 'absolute',
                left: '50%',
                top: '50%',
                transform: 'translate(-50%, -50%)',
                width: 32,
                height: 2,
                background: 'var(--line-strong)',
                borderRadius: 1,
              }}
            />
          </div>
          <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
            {tweaks.view === 'setup' && (
              <SetupView
                strategy={D.strategy}
                indicators={D.indicators}
                positions={D.positions}
                playbook={D.playbook}
                dxy={D.dxy}
                correlations={D.correlations}
                orderflowProd={D.orderflowProd}
                tf={tf}
                instrument={instrument}
              />
            )}
            {tweaks.view === 'execute' && (
              <ExecuteView
                dom={D.dom}
                cvd={D.cvd}
                orderFlow={D.orderFlow}
                flashCrash={D.flashCrash}
                ibkr={D.ibkr}
                microEvents={D.microEvents}
                footprint={D.footprint}
                tf={tf}
                instrument={instrument}
              />
            )}
            {tweaks.view === 'review' && <ReviewView backtest={D.backtest} trailing={D.trailing} />}
          </div>
        </div>

        {tweaks.showMentorDesk && (
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              minHeight: 0,
              borderLeft: '1px solid var(--line)',
              overflow: 'hidden',
            }}
          >
            {/* MentorDesk takes flexible space, ManualAsk + AlertsRail are
                fixed-height slots so they're never clipped on short viewports. */}
            <div style={{ flex: '1 1 0', minHeight: 0, overflow: 'hidden' }}>
              <MentorDesk
                reviews={D.reviews}
                onArm={(r) => {
                  void D.armReview(r, r.plan?.qty ?? 1);
                }}
                onSkip={() => {
                  /* skip is local-only — no backend action needed */
                }}
              />
            </div>
            <div
              style={{
                borderTop: '1px solid var(--line)',
                flex: '0 0 180px',
                minHeight: 0,
                overflow: 'hidden',
                background: 'var(--s2)',
              }}
            >
              <ManualAsk instrument={instrument} tf={tf} />
            </div>
            {tweaks.showAlertsRail && (
              <div
                style={{
                  borderTop: '1px solid var(--line)',
                  flex: '0 0 220px',
                  minHeight: 0,
                  overflow: 'hidden',
                }}
              >
                <AlertsRail alerts={D.alerts} />
              </div>
            )}
          </div>
        )}
      </div>

      <RiskAlertsBar alerts={D.riskAlerts} />

      <RDTweaks tweaks={tweaks} setTweak={setTweak} />
    </div>
  );
}

function RDTweaks({
  tweaks,
  setTweak,
}: {
  tweaks: Tweaks;
  setTweak: <K extends keyof Tweaks>(key: K, value: Tweaks[K]) => void;
}) {
  return (
    <TweaksPanel title="Tweaks">
      <TweakSection label="Surface">
        <TweakRadio<Tweaks['tone']>
          label="Tone"
          value={tweaks.tone}
          onChange={(v) => setTweak('tone', v)}
          options={[
            { value: 'zinc', label: 'Zinc' },
            { value: 'slate', label: 'Slate' },
            { value: 'true', label: 'True' },
            { value: 'warm', label: 'Warm' },
          ]}
        />
        <TweakRadio<Tweaks['theme']>
          label="Theme"
          value={tweaks.theme}
          onChange={(v) => setTweak('theme', v)}
          options={[
            { value: 'dark', label: 'Dark' },
            { value: 'light', label: 'Light' },
          ]}
        />
        <TweakRadio<Tweaks['density']>
          label="Density"
          value={tweaks.density}
          onChange={(v) => setTweak('density', v)}
          options={[
            { value: 'comfortable', label: 'Comfort' },
            { value: 'compact', label: 'Compact' },
          ]}
        />
        <TweakSlider
          label="Accent hue"
          value={tweaks.accentHue}
          min={0}
          max={360}
          step={1}
          onChange={(v) => setTweak('accentHue', v)}
        />
      </TweakSection>
      <TweakSection label="Workspace">
        <TweakRadio<ViewMode>
          label="View"
          value={tweaks.view}
          onChange={(v) => setTweak('view', v)}
          options={[
            { value: 'setup', label: 'Setup' },
            { value: 'execute', label: 'Execute' },
            { value: 'review', label: 'Review' },
          ]}
        />
        <TweakRadio<Tweaks['scenario']>
          label="Scenario"
          value={tweaks.scenario}
          onChange={(v) => setTweak('scenario', v)}
          options={[
            { value: 'live', label: 'Live' },
            { value: 'halt', label: 'Halt' },
            { value: 'flat', label: 'Flat' },
          ]}
        />
        <TweakToggle
          label="Mentor desk"
          value={tweaks.showMentorDesk}
          onChange={(v) => setTweak('showMentorDesk', v)}
        />
        <TweakToggle
          label="Alerts rail"
          value={tweaks.showAlertsRail}
          onChange={(v) => setTweak('showAlertsRail', v)}
        />
        <TweakToggle label="Ticker" value={tweaks.showTicker} onChange={(v) => setTweak('showTicker', v)} />
        <TweakToggle
          label="Rollover banner"
          value={tweaks.showRolloverBanner}
          onChange={(v) => setTweak('showRolloverBanner', v)}
        />
      </TweakSection>
    </TweaksPanel>
  );
}
