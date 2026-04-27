'use client';

import { useState } from 'react';
import { RiskAlert } from '../lib/data';

export function RiskAlertsBar({ alerts }: { alerts: RiskAlert[] }) {
  const [dismissed, setDismissed] = useState<Record<number, boolean>>({});
  const visible = alerts.filter((_, i) => !dismissed[i]);
  return (
    <div className="risk-bar">
      <div className="risk-bar-head">
        <span className="risk-count">ALERTS · {visible.length}</span>
        <button
          type="button"
          className="risk-clear"
          onClick={() =>
            setDismissed(Object.fromEntries(alerts.map((_, i) => [i, true])))
          }
        >
          CLEAR ALL
        </button>
      </div>
      <div className="risk-bar-list">
        {visible.map((a) => {
          const realIdx = alerts.indexOf(a);
          const tone = a.level === 'high' ? 'high' : 'warn';
          return (
            <div key={realIdx} className={'risk-pill risk-' + tone}>
              <span className="risk-dot" />
              <span className="risk-tag">RISK</span>
              {a.sym && <span className="risk-sym">{a.sym}</span>}
              <span className="risk-text">{a.text}</span>
              <button type="button" className="risk-snooze">
                SNOOZE 30M
              </button>
              <button
                type="button"
                className="risk-x"
                onClick={() => setDismissed((d) => ({ ...d, [realIdx]: true }))}
              >
                ×
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
