/* ═══════════════════════════════════════════════════════════════
   SignSparkline — compact per-sign timeline.

   Renders one row of colored dots, one per event, oldest on the left
   and latest on the right. The dot tone matches the status:
     • PRESENT   → red
     • WORSENING → orange
     • IMPROVING → cyan
     • ABSENT    → emerald
     • UNKNOWN   → slate

   Baseline events get a thin ring so the doctor can see at a glance
   "this came from triage" vs. "a colleague recorded this later". The
   latest event gets a thicker ring so the rightmost dot reads as
   "current".

   Spatial encoding choice: dots are evenly spaced rather than time-
   proportional. Time-proportional spacing ages out the sparkline
   visually — a sign recorded once at 06:00 and again at 22:00 would
   be two dots on opposite ends with nothing between them, hiding the
   pattern. Even spacing optimises for "what's the sequence of
   states?" which is what the doctor actually scans for. The exact
   timestamps are still in the tooltip on hover.

   Width is fixed-min, expand-to-fill so the sparkline takes the
   available track in the sign card without being a layout dependency.
   Up to 12 dots are shown; older events are summarised as a "+N
   earlier" pill on the left.
   ═══════════════════════════════════════════════════════════════ */

import { format } from 'date-fns';
import type { ClinicalSignEventResponse, ClinicalSignStatus } from '@/api/clinicalSigns';

const DOT_TONE: Record<ClinicalSignStatus, string> = {
  PRESENT:   'bg-red-500',
  WORSENING: 'bg-orange-500',
  IMPROVING: 'bg-cyan-500',
  ABSENT:    'bg-emerald-500',
  UNKNOWN:   'bg-slate-400',
};

const MAX_VISIBLE_DOTS = 12;

interface Props {
  events: ClinicalSignEventResponse[];
  isDark: boolean;
}

export function SignSparkline({ events, isDark }: Props) {
  if (!events || events.length === 0) {
    return (
      <div className={`text-[10px] italic ${isDark ? 'text-slate-500' : 'text-slate-400'}`}>
        No events
      </div>
    );
  }

  const visible = events.length > MAX_VISIBLE_DOTS
    ? events.slice(events.length - MAX_VISIBLE_DOTS)
    : events;
  const truncated = events.length - visible.length;
  const lastIndex = visible.length - 1;

  return (
    <div className="flex items-center gap-1.5" role="img" aria-label={`${events.length} clinical sign event${events.length === 1 ? '' : 's'}`}>
      {truncated > 0 && (
        <span
          className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg text-slate-600"
          style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}
          title={`${truncated} earlier event${truncated === 1 ? '' : 's'} not shown`}
        >
          +{truncated}
        </span>
      )}
      <div
        className="relative flex items-center gap-1 h-3 flex-1 min-w-[60px]"
        // The connector line beneath the dots gives the visual that
        // these are part of one trajectory rather than independent pips.
      >
        <span
          aria-hidden
          className={`absolute left-0 right-0 top-1/2 -translate-y-1/2 h-px ${
            isDark ? 'bg-white/10' : 'bg-slate-300/60'
          }`}
        />
        {visible.map((e, i) => {
          const isLatest = i === lastIndex;
          const dotCls = DOT_TONE[e.status] ?? DOT_TONE.UNKNOWN;
          const ringCls = isLatest
            ? isDark ? 'ring-2 ring-white/40' : 'ring-2 ring-slate-700/40'
            : e.isBaseline
              ? 'ring-1 ring-slate-400/70'
              : '';
          const sizeCls = isLatest ? 'w-2.5 h-2.5' : 'w-2 h-2';
          const tooltip = [
            format(new Date(e.recordedAt), 'dd MMM HH:mm'),
            e.status,
            e.isBaseline ? 'baseline' : null,
            e.numericValue != null ? `value ${e.numericValue}` : null,
            e.recordedByName ? `by ${e.recordedByName}` : null,
          ].filter(Boolean).join(' · ');
          return (
            <span
              key={e.id}
              className={`relative inline-block rounded-full ${dotCls} ${ringCls} ${sizeCls}`}
              title={tooltip}
              aria-label={tooltip}
            />
          );
        })}
      </div>
    </div>
  );
}
