import { Activity, ArrowUpRight, Check, Info, TriangleAlert } from 'lucide-react';
import type { ActivityEntry } from '../../api/types';
import { time } from '../../lib/format';

export function ActivityFeed({ entries }: { entries: ActivityEntry[] }) {
  return <section className="panel activity" aria-labelledby="activity-title">
    <div className="panel-heading"><div><span className="eyebrow">THE JOURNEY</span><h2 id="activity-title">Activity stream</h2></div><Activity size={18} className="muted-icon" /></div>
    <p className="section-description">Actions and upload responses from this session.</p>
    <div className="activity-list" role="log" aria-live="polite" aria-relevant="additions" aria-label="Demo activity">
      {entries.length ? entries.map(entry => <article className={`activity-entry activity-${entry.tone}`} key={entry.id}>
        <span className="activity-icon">{entry.tone === 'success' ? <Check size={13} /> : entry.tone === 'error' || entry.tone === 'warning' ? <TriangleAlert size={13} /> : <ArrowUpRight size={13} />}</span>
        <div><strong>{entry.title}</strong><p>{entry.detail}</p><time dateTime={entry.at.toISOString()}>{time(entry.at)}</time></div>
      </article>) : <div className="activity-waiting"><span className="waiting-lines"><i /><i /><i /></span><strong>Ready when you are.</strong><p>Create your first packet to follow its journey through the mesh.</p></div>}
    </div>
    <div className="activity-note"><Info size={14} /><p>Upload responses and ledger statuses are displayed independently.</p></div>
  </section>;
}
