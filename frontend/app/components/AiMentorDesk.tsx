'use client';

import { useState, useMemo } from 'react';
import type { AlertMessage, PriceUpdate } from '@/app/hooks/useWebSocket';
import type { TzEntry } from '@/app/lib/timezones';
import type { PortfolioSummary, IndicatorSnapshot, MentorSignalReview } from '@/app/lib/api';
import { isSignalReview, isBehaviourReview, BEHAVIOUR_CATEGORIES } from '@/app/lib/mentor';
import type { Instrument, Timeframe } from '@/app/lib/mentor';
import MentorSignalPanel from './MentorSignalPanel';
import MentorPanel from './MentorPanel';

type TabKey = 'ALL' | 'SIGNALS' | 'BEHAVIOUR' | 'MANUAL';

interface TabDef {
  key: TabKey;
  label: string;
  icon: string;
}

const TABS: TabDef[] = [
  { key: 'ALL', label: 'TOUT', icon: '' },
  { key: 'SIGNALS', label: 'SIGNAUX', icon: '\uD83E\uDD16' },
  { key: 'BEHAVIOUR', label: 'BEHAVIOUR', icon: '\uD83D\uDCD0' },
  { key: 'MANUAL', label: 'MANUEL', icon: '\uD83D\uDC64' },
];

interface AiMentorDeskProps {
  instrument: Instrument;
  timeframe: Timeframe;
  timezone: TzEntry;
  connected: boolean;
  summary: PortfolioSummary | null;
  snapshot: IndicatorSnapshot | null;
  prices: Record<string, PriceUpdate>;
  alerts: AlertMessage[];
  reviews: MentorSignalReview[];
  selectedBrokerAccountId?: string;
  onRefresh?: () => void;
}

export default function AiMentorDesk({
  instrument,
  timeframe,
  timezone,
  connected,
  summary,
  snapshot,
  prices,
  alerts,
  reviews,
  selectedBrokerAccountId,
  onRefresh,
}: AiMentorDeskProps) {
  const [activeTab, setActiveTab] = useState<TabKey>('SIGNALS');

  const signalReviews = useMemo(
    () => reviews.filter(isSignalReview),
    [reviews]
  );

  const behaviourReviews = useMemo(
    () => reviews.filter(isBehaviourReview),
    [reviews]
  );

  const signalAlerts = useMemo(
    () => alerts.filter(a => !BEHAVIOUR_CATEGORIES.has(a.category)),
    [alerts]
  );

  const behaviourAlerts = useMemo(
    () => alerts.filter(a => BEHAVIOUR_CATEGORIES.has(a.category)),
    [alerts]
  );


  const tabCounts: Record<TabKey, number> = useMemo(() => ({
    ALL: reviews.length,
    SIGNALS: signalReviews.length,
    BEHAVIOUR: behaviourReviews.length,
    MANUAL: 0, // manual reviews are loaded internally by MentorPanel
  }), [reviews.length, signalReviews.length, behaviourReviews.length]);

  return (
    <div className="rounded-xl border border-zinc-800 bg-zinc-900/80 overflow-hidden">
      {/* Header + Tab bar */}
      <div className="flex items-center justify-between px-4 py-2.5 border-b border-zinc-800">
        <h2 className="text-sm font-bold tracking-tight text-white">
          AI Mentor<span className="text-emerald-400">Desk</span>
        </h2>

        {/* Segmented control */}
        <div className="flex rounded-lg overflow-hidden border border-zinc-700 bg-zinc-800/50">
          {TABS.map(tab => {
            const isActive = activeTab === tab.key;
            const count = tabCounts[tab.key];
            return (
              <button
                key={tab.key}
                onClick={() => setActiveTab(tab.key)}
                className={`px-3 py-1.5 text-xs font-medium transition-colors flex items-center gap-1.5 ${
                  isActive
                    ? 'bg-zinc-600 text-white'
                    : 'text-zinc-400 hover:text-zinc-200 hover:bg-zinc-700/50'
                }`}
              >
                {tab.icon && <span className="text-[11px]">{tab.icon}</span>}
                <span>{tab.label}</span>
                {count > 0 && tab.key !== 'MANUAL' && (
                  <span className={`text-[10px] px-1.5 py-0.5 rounded-full ${
                    isActive ? 'bg-zinc-500 text-white' : 'bg-zinc-700 text-zinc-400'
                  }`}>
                    {count}
                  </span>
                )}
              </button>
            );
          })}
        </div>
      </div>

      {/* Tab content */}
      <div>
        {(activeTab === 'ALL' || activeTab === 'SIGNALS') && (
          <div>
            {activeTab === 'ALL' && (
              <div className="px-4 pt-2 pb-1">
                <span className="text-[10px] font-medium text-zinc-500 uppercase tracking-wider">
                  Signaux ({signalReviews.length})
                </span>
              </div>
            )}
            <MentorSignalPanel
              timezone={timezone}
              alerts={activeTab === 'ALL' ? alerts : signalAlerts}
              reviews={activeTab === 'ALL' ? reviews : signalReviews}
              selectedBrokerAccountId={selectedBrokerAccountId}
              onRefresh={onRefresh}
            />
          </div>
        )}

        {activeTab === 'BEHAVIOUR' && (
          <div>
            {behaviourReviews.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-16 text-zinc-500">
                <span className="text-3xl mb-3">{'\uD83D\uDCD0'}</span>
                <p className="text-sm">Aucune alerte behaviour avec review Mentor</p>
                <p className="text-xs text-zinc-600 mt-1">
                  Les alertes EMA Proximity, S/R Touch et CMF apparaitront ici
                </p>
              </div>
            ) : (
              <MentorSignalPanel
                timezone={timezone}
                alerts={behaviourAlerts}
                reviews={behaviourReviews}
                selectedBrokerAccountId={selectedBrokerAccountId}
                onRefresh={onRefresh}
              />
            )}
          </div>
        )}

        {activeTab === 'MANUAL' && (
          <MentorPanel
            instrument={instrument}
            timeframe={timeframe}
            timezone={timezone}
            connected={connected}
            summary={summary}
            snapshot={snapshot}
            prices={prices}
            alerts={alerts}
          />
        )}
      </div>
    </div>
  );
}
