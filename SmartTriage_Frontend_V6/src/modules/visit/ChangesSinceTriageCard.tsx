/* ═══════════════════════════════════════════════════════════════
   ChangesSinceTriageCard — at-a-glance diff between triage baseline
   and the current state.

   What goes on the doctor's eyes first when they open the chart in
   the middle of a busy shift is:
     "what's actually different now compared to when this patient
      was first seen?"

   This card collapses the full event log into four buckets:
     • NEW       — sign appeared after triage (worsening or de-novo)
     • WORSENING — explicitly worse, or crossed from good to bad
     • IMPROVING — explicitly improving, or crossed from bad to good
     • RESOLVED  — was present at triage, now ABSENT

   The order is deliberate: clinically dangerous categories (NEW +
   WORSENING) sit at the top; reassuring categories (IMPROVING +
   RESOLVED) sit beneath. A doctor scanning top-to-bottom hits the
   things that need action first.

   When there are zero post-baseline updates, the card renders a quiet
   "no changes since triage" line — explicit absence, not nothing.
   "No card visible" reads as "there's no diff feature" instead of
   "we checked and nothing has changed".
   ═══════════════════════════════════════════════════════════════ */

import { useMemo, useState } from 'react';
import { format } from 'date-fns';
import { ArrowDownRight, ArrowUpRight, Plus, Check, ChevronDown, ChevronUp, GitBranch } from 'lucide-react';
import type { ClinicalSignEventResponse, ClinicalSignStatus } from '@/api/clinicalSigns';
import {
  buildSignTimelines,
  classifyChanges,
  groupChangesByBucket,
  type ChangeBucket,
  type SignChange,
} from './clinicalSignDiff';
import { SIGN_BY_CODE } from './clinicalSignDefinitions';

const STATUS_TEXT: Record<ClinicalSignStatus, string> = {
  PRESENT: 'present',
  ABSENT: 'absent',
  IMPROVING: 'improving',
  WORSENING: 'worsening',
  UNKNOWN: 'unknown',
};

const BUCKET_TONE: Record<Exclude<ChangeBucket, 'UNCHANGED'>, {
  label: string;
  className: string;
  Icon: typeof Plus;
  description: string;
}> = {
  NEW: {
    label: 'New since triage',
    className: 'bg-red-500/20 text-red-300 border-red-500/30',
    Icon: Plus,
    description: 'Signs that appeared after the baseline triage assessment.',
  },
  WORSENING: {
    label: 'Worsening',
    className: 'bg-orange-500/20 text-orange-300 border-orange-500/30',
    Icon: ArrowUpRight,
    description: 'Signs that have explicitly worsened, or crossed from a good state to a bad one.',
  },
  IMPROVING: {
    label: 'Improving',
    className: 'bg-cyan-500/20 text-cyan-300 border-cyan-500/30',
    Icon: ArrowDownRight,
    description: 'Signs that are improving, or have crossed back from a bad state to a good one.',
  },
  RESOLVED: {
    label: 'Resolved',
    className: 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30',
    Icon: Check,
    description: 'Signs that were present at triage and are now absent.',
  },
};

const BUCKET_ORDER: Array<Exclude<ChangeBucket, 'UNCHANGED'>> =
  ['NEW', 'WORSENING', 'IMPROVING', 'RESOLVED'];

interface Props {
  history: ClinicalSignEventResponse[];
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
}

export function ChangesSinceTriageCard({ history, glassCard, glassInner, isDark, text }: Props) {
  const grouped = useMemo(() => {
    const tl = buildSignTimelines(history);
    return groupChangesByBucket(classifyChanges(tl));
  }, [history]);

  const totalChanges =
    grouped.NEW.length + grouped.WORSENING.length +
    grouped.IMPROVING.length + grouped.RESOLVED.length;

  // Bucket open/close state. NEW + WORSENING default to open because
  // those are the categories the doctor has to act on; IMPROVING and
  // RESOLVED default to closed (reassuring info, scannable count).
  const [open, setOpen] = useState<Record<string, boolean>>({
    NEW: true,
    WORSENING: true,
    IMPROVING: false,
    RESOLVED: false,
  });

  if (totalChanges === 0) {
    return (
      <div className="rounded-2xl p-3 flex items-center gap-2" style={glassCard}>
        <GitBranch className={`w-4 h-4 ${isDark ? 'text-slate-500' : 'text-slate-400'}`} />
        <span className={`text-xs ${text.muted}`}>
          No changes since triage. The current state matches the baseline assessment.
        </span>
      </div>
    );
  }

  return (
    <div className="rounded-2xl p-4" style={glassCard}>
      <div className="flex items-center gap-2 mb-3">
        <GitBranch className="w-4 h-4 text-cyan-500" />
        <h4 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>
          Changes since triage
        </h4>
        <span className={`ml-auto text-[10px] font-bold ${text.muted}`}>
          {totalChanges} change{totalChanges === 1 ? '' : 's'}
        </span>
      </div>

      {/* Bucket-count chips — always visible, even for empty buckets,
          so the doctor sees "0 worsening" as positive evidence rather
          than evidence of absence of the feature. */}
      <div className="flex flex-wrap gap-1.5 mb-3">
        {BUCKET_ORDER.map((b) => {
          const tone = BUCKET_TONE[b];
          const count = grouped[b].length;
          const Icon = tone.Icon;
          return (
            <button
              key={b}
              type="button"
              onClick={() => count > 0 && setOpen((prev) => ({ ...prev, [b]: !prev[b] }))}
              disabled={count === 0}
              className={`inline-flex items-center gap-1 px-2 py-1 text-[11px] font-bold rounded-md border transition-colors ${tone.className} ${
                count === 0 ? 'opacity-40 cursor-not-allowed' : 'hover:brightness-110 cursor-pointer'
              }`}
              title={tone.description}
            >
              <Icon className="w-3 h-3" />
              {count} {tone.label.toLowerCase()}
              {count > 0 && (open[b]
                ? <ChevronUp className="w-3 h-3" />
                : <ChevronDown className="w-3 h-3" />)}
            </button>
          );
        })}
      </div>

      {/* Per-bucket detail blocks */}
      <div className="space-y-2">
        {BUCKET_ORDER.map((b) => {
          const items = grouped[b];
          if (items.length === 0 || !open[b]) return null;
          return (
            <BucketBlock
              key={b}
              bucket={b}
              items={items}
              glassInner={glassInner}
              isDark={isDark}
              text={text}
            />
          );
        })}
      </div>
    </div>
  );
}

function BucketBlock({
  bucket, items, glassInner, isDark, text,
}: {
  bucket: Exclude<ChangeBucket, 'UNCHANGED'>;
  items: SignChange[];
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
}) {
  const tone = BUCKET_TONE[bucket];
  return (
    <div className={`rounded-xl border p-3 ${tone.className}`}>
      <div className="text-[10px] font-extrabold uppercase tracking-wider mb-2">
        {tone.label}
      </div>
      <div className="space-y-1.5">
        {items.map((c) => {
          const def = SIGN_BY_CODE[c.signCode];
          const label = def?.label ?? c.signCode;
          const baselineText = c.baseline
            ? STATUS_TEXT[c.baseline.status]
            : 'no baseline';
          const latestText = STATUS_TEXT[c.latest.status];
          return (
            <div
              key={c.signCode}
              className="rounded-lg p-2 flex items-start gap-2"
              style={glassInner}
            >
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <span className={`text-xs font-bold ${text.heading}`}>{label}</span>
                  {c.latest.numericValue != null && (
                    <span className="text-[10px] font-mono px-1 py-0.5 rounded bg-amber-500/20 text-amber-300">
                      {c.latest.numericValue}
                    </span>
                  )}
                </div>
                <div className={`text-[11px] mt-0.5 ${text.body}`}>
                  <span className="font-semibold">{baselineText}</span>
                  <span className={`mx-1 ${isDark ? 'text-slate-400' : 'text-slate-500'}`}>→</span>
                  <span className="font-semibold">{latestText}</span>
                  {' '}
                  <span className={text.muted}>
                    · {format(new Date(c.latest.recordedAt), 'dd MMM HH:mm')}
                    {c.latest.recordedByName && <> by {c.latest.recordedByName}</>}
                  </span>
                </div>
                {c.latest.notes && (
                  <p className={`text-[11px] mt-0.5 italic ${text.muted}`}>"{c.latest.notes}"</p>
                )}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
