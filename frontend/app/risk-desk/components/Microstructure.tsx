'use client';

import { MicroEvent, MicroEvents } from '../lib/data';

type TagTone = 'mom' | 'ice' | 'abs' | 'spoof';

const TAG_COLOR: Record<TagTone, string> = {
  abs: '#8b5cf6',
  ice: '#06b6d4',
  spoof: '#f59e0b',
  mom: '#f43f5e',
};

function MicroEventRow({ tag, tagTone, e }: { tag: string; tagTone: TagTone; e: MicroEvent }) {
  const tagColor = TAG_COLOR[tagTone];
  return (
    <div className="micro-row">
      <span
        className="micro-tag"
        style={{
          background: tagColor + '22',
          color: tagColor,
          borderColor: tagColor + '44',
        }}
      >
        {tag}
      </span>
      <span className="micro-sym">{e.sym}</span>
      <span className={'micro-dir ' + (e.dir === 'BULL' ? 'up' : 'down')}>{e.dir}</span>
      <span className="micro-meta">
        score: <span className="num">{e.score?.toFixed(1)}</span>
        {e.delta !== undefined && (
          <>
            {' '}· delta:{' '}
            <span className="num">
              {e.delta > 0 ? '+' : ''}
              {e.delta}
            </span>
          </>
        )}
        {e.move !== undefined && (
          <>
            {' '}· move: <span className="num">{e.move?.toFixed(2)}</span>
          </>
        )}
        {e.note && <> · {e.note}</>}
      </span>
      <span className="micro-age">{e.age}</span>
    </div>
  );
}

function MicroSection({
  label,
  sym,
  items,
  tag,
  tagTone,
  empty = 'No events yet',
}: {
  label: string;
  sym: string;
  items: MicroEvent[];
  tag: string;
  tagTone: TagTone;
  empty?: string;
}) {
  return (
    <div className="micro-section">
      <div className="micro-head">
        <span className="micro-head-label">{label}</span>
        <span className="micro-head-sym">— {sym}</span>
      </div>
      {items.length === 0 ? (
        <div className="micro-empty">{empty}</div>
      ) : (
        <div className="micro-list">
          {items.map((e, i) => (
            <MicroEventRow key={i} tag={tag} tagTone={tagTone} e={e} />
          ))}
        </div>
      )}
    </div>
  );
}

export function MicrostructurePanel({
  instrument = 'MCL',
  events,
}: {
  instrument?: string;
  events: MicroEvents;
}) {
  return (
    <div className="panel micro-panel">
      <div className="panel-head">
        <span className="title">MICROSTRUCTURE</span>
        <span className="muted" style={{ textTransform: 'none', letterSpacing: 0, fontSize: 11, fontWeight: 400 }}>
          Events &amp; histories
        </span>
      </div>
      <div className="panel-body micro-body">
        <MicroSection
          label="MOMENTUM BURSTS"
          sym={instrument}
          items={events.momentum}
          tag="MOM"
          tagTone="mom"
          empty="No momentum bursts yet"
        />
        <MicroSection
          label="ICEBERG ACTIVITY"
          sym={instrument}
          items={events.iceberg}
          tag="ICE"
          tagTone="ice"
          empty="No iceberg events yet"
        />
        <MicroSection
          label="ABSORPTION HISTORY"
          sym={instrument}
          items={events.absorption}
          tag="ABS"
          tagTone="abs"
          empty="No absorption yet"
        />
        <MicroSection
          label="SPOOFING HISTORY"
          sym={instrument}
          items={events.spoofing}
          tag="SPF"
          tagTone="spoof"
          empty="No spoofing yet"
        />
        <MicroSection
          label="LIVE FEED"
          sym={instrument}
          items={events.liveFeed}
          tag="LIVE"
          tagTone="mom"
          empty="No live events yet"
        />
      </div>
    </div>
  );
}
