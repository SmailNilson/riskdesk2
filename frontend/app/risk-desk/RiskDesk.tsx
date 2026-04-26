'use client';

import { useCallback, useEffect, useState } from 'react';
import { AlertsFeed } from './components/AlertsFeed';
import { Chart } from './components/Chart';
import { ContextRail } from './components/ContextRail';
import { Footprint } from './components/Footprint';
import { ConnectionState, Header, MetricsStrip, Ticker } from './components/Header';
import { MentorDesk } from './components/MentorDesk';
import {
  TweakRadio,
  TweakSection,
  TweakSlider,
  TweakToggle,
  TweaksPanel,
} from './components/TweaksPanel';
import { RD_DATA } from './lib/data';
import { RiskDeskProvider, useRiskDeskData } from './lib/RiskDeskContext';

interface Tweaks {
  tone: 'zinc' | 'slate' | 'true' | 'warm';
  theme: 'dark' | 'light';
  density: 'comfortable' | 'compact';
  accentHue: number;
  showContextRail: boolean;
  showMentorDesk: boolean;
  showAlertsFeed: boolean;
  showFootprint: boolean;
  scenario: 'live' | 'playback' | 'what-if';
}

const TWEAK_DEFAULTS: Tweaks = {
  tone: 'zinc',
  theme: 'dark',
  density: 'comfortable',
  accentHue: 155,
  showContextRail: true,
  showMentorDesk: true,
  showAlertsFeed: true,
  showFootprint: true,
  scenario: 'live',
};

export function RiskDesk() {
  return (
    <RiskDeskProvider>
      <RiskDeskShell />
    </RiskDeskProvider>
  );
}

function RiskDeskShell() {
  const D = useRiskDeskData();
  const [tweaks, setTweaks] = useState<Tweaks>(TWEAK_DEFAULTS);
  const setTweak = useCallback(<K extends keyof Tweaks>(key: K, value: Tweaks[K]) => {
    setTweaks((prev) => ({ ...prev, [key]: value }));
  }, []);

  // Header state
  const [connection, setConnection] = useState<ConnectionState>(D.wsConnected ? 'LIVE' : 'OFFLINE');
  useEffect(() => {
    setConnection(D.wsConnected ? 'LIVE' : D.backendReachable ? 'MARKET CLOSED' : 'OFFLINE');
  }, [D.wsConnected, D.backendReachable]);

  const [theme, setTheme] = useState<'dark' | 'light'>('dark');
  useEffect(() => {
    setTweak('theme', theme);
  }, [theme, setTweak]);

  // Instrument / timeframe come from the context (drives candles/indicators fetches).
  const instrument =
    RD_DATA.INSTRUMENTS.find((i) => i.sym === D.instrumentSym) ?? RD_DATA.INSTRUMENTS[0];

  // Live price for the chart's right-edge label — pull from the matching ticker.
  const livePrice =
    D.tickers.find((t) => t.sym === instrument.sym)?.price ?? D.lastClose;

  return (
    <div
      className="rd-root"
      data-tone={tweaks.tone}
      data-theme={tweaks.theme}
      data-density={tweaks.density}
      style={{ ['--accent-h' as string]: String(tweaks.accentHue) }}
    >
      {/* Header */}
      <Header
        instrumentSym={instrument.sym}
        setInstrument={D.setInstrumentSym}
        instruments={RD_DATA.INSTRUMENTS}
        timeframe={D.timeframe}
        setTimeframe={D.setTimeframe}
        timeframes={RD_DATA.TIMEFRAMES}
        theme={theme}
        setTheme={setTheme}
        connection={connection}
        setConnection={setConnection}
        scenario={tweaks.scenario}
        setScenario={(s) => setTweak('scenario', s as Tweaks['scenario'])}
      />

      {/* Metrics strip — live portfolio summary */}
      <MetricsStrip portfolio={D.portfolio} />

      {/* Ticker — live prices via context */}
      <Ticker tickers={D.tickers} instrumentSym={instrument.sym} onSelect={D.setInstrumentSym} />

      {/* Main 3-column layout */}
      <div style={{ flex: 1, display: 'flex', overflow: 'hidden', minHeight: 0 }}>
        <ContextRail
          instrument={instrument}
          collapsed={!tweaks.showContextRail}
          setCollapsed={(v) => setTweak('showContextRail', !v)}
        />

        {/* Center column */}
        <main
          style={{
            flex: 1,
            display: 'flex',
            flexDirection: 'column',
            minWidth: 0,
            minHeight: 0,
            overflow: 'hidden',
          }}
        >
          <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
            <div
              style={{
                flex: tweaks.showFootprint ? '1 1 60%' : '1 1 100%',
                minHeight: 0,
                display: 'flex',
                padding: 12,
                gap: 10,
              }}
            >
              <div style={{ flex: 1, minWidth: 0 }}>
                <Chart instrument={instrument} timeframe={D.timeframe} livePrice={livePrice} />
              </div>
            </div>
            {tweaks.showFootprint && (
              <div
                style={{
                  flex: '0 1 320px',
                  minHeight: 220,
                  overflow: 'auto',
                  padding: '0 12px 12px',
                }}
              >
                <Footprint instrument={instrument} />
              </div>
            )}
          </div>

          {tweaks.showAlertsFeed && <AlertsFeed instrument={instrument} />}
        </main>

        {/* Right rail — wires arm/submit through to the context (backend) */}
        <MentorDesk
          instrument={instrument}
          collapsed={!tweaks.showMentorDesk}
          setCollapsed={(v) => setTweak('showMentorDesk', !v)}
        />
      </div>

      {/* Tweaks panel */}
      <RiskDeskTweaks tweaks={tweaks} setTweak={setTweak} />
    </div>
  );
}

// ───────────────────────────────────────────────
// Tweaks panel — surface tone, accent hue, layout
// ───────────────────────────────────────────────
function RiskDeskTweaks({
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
            { value: 'comfortable', label: 'Comfortable' },
            { value: 'compact', label: 'Compact' },
          ]}
        />
        <TweakSlider
          label="Accent hue"
          value={tweaks.accentHue}
          min={0}
          max={360}
          step={5}
          unit="°"
          onChange={(v) => setTweak('accentHue', v)}
        />
      </TweakSection>

      <TweakSection label="Layout">
        <TweakToggle
          label="Context rail"
          value={tweaks.showContextRail}
          onChange={(v) => setTweak('showContextRail', v)}
        />
        <TweakToggle
          label="AI Mentor desk"
          value={tweaks.showMentorDesk}
          onChange={(v) => setTweak('showMentorDesk', v)}
        />
        <TweakToggle
          label="Footprint panel"
          value={tweaks.showFootprint}
          onChange={(v) => setTweak('showFootprint', v)}
        />
        <TweakToggle
          label="Alerts feed"
          value={tweaks.showAlertsFeed}
          onChange={(v) => setTweak('showAlertsFeed', v)}
        />
      </TweakSection>

      <TweakSection label="Scenario">
        <TweakRadio<Tweaks['scenario']>
          label="Mode"
          value={tweaks.scenario}
          onChange={(v) => setTweak('scenario', v)}
          options={[
            { value: 'live', label: 'Live' },
            { value: 'playback', label: 'Playback' },
            { value: 'what-if', label: 'What-if' },
          ]}
        />
      </TweakSection>
    </TweaksPanel>
  );
}
