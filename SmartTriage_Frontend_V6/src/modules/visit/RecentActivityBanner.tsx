/**
 * RecentActivityBanner — "what's new on this patient since X" summary.
 *
 * Renders above the visit-detail tabs so a doctor inheriting the patient
 * at shift change can see, at a glance, what events happened during the
 * window they care about — without scrolling through the full timeline
 * trying to spot which entries are recent and which are baseline.
 *
 * <p>Defaults the cutoff to the user's current shift start time
 * (resolved via useMyShift). When the user has no active shift the
 * default falls back to "last 8 hours" — long enough to cover a full
 * Rwandan ED shift but short enough not to dilute the signal.
 *
 * <p>All counts are derived from data already loaded by VisitDetailPage
 * (vitals, triage, notes, diagnoses, investigations, medications,
 * alerts). No additional API calls.
 */

import { useMemo, useState } from 'react';
import {
  Activity, Clock, AlertTriangle, ClipboardList,
  Stethoscope, Pill, FileText, FlaskConical,
} from 'lucide-react';
import type {
  ClinicalAlertResponse,
  ClinicalNoteResponse,
  DiagnosisResponse,
  InvestigationResponse,
  MedicationResponse,
  TriageRecordResponse,
  VitalSignsResponse,
} from '@/api/types';
import { useMyShift } from '@/hooks/useMyShift';
import { useTheme } from '@/hooks/useTheme';

type Window = 'shift' | '1h' | '4h' | '8h' | '24h';

interface Props {
  vitals: VitalSignsResponse[];
  triageHistory: TriageRecordResponse[];
  notes: ClinicalNoteResponse[];
  diagnoses: DiagnosisResponse[];
  investigations: InvestigationResponse[];
  medications: MedicationResponse[];
  alerts: ClinicalAlertResponse[];
}

export function RecentActivityBanner({
  vitals, triageHistory, notes, diagnoses, investigations, medications, alerts,
}: Props) {
  const { assignment } = useMyShift();
  const { glassCard, glassInner, text } = useTheme();
  const shiftStartIso = assignment?.startedAt ?? null;

  const [window, setWindow] = useState<Window>(shiftStartIso ? 'shift' : '8h');

  const cutoff = useMemo<Date>(() => {
    const now = Date.now();
    if (window === 'shift' && shiftStartIso) {
      return new Date(shiftStartIso);
    }
    const hours = window === '1h' ? 1 : window === '4h' ? 4 : window === '24h' ? 24 : 8;
    return new Date(now - hours * 60 * 60 * 1000);
  }, [window, shiftStartIso]);

  // Helpers — count items whose canonical timestamp is >= cutoff. Each
  // entity uses a different field name for "when did this happen", so
  // we mirror the existing data shapes rather than normalise.
  const isRecent = (iso?: string | null) =>
    iso != null && new Date(iso).getTime() >= cutoff.getTime();

  const counts = useMemo(() => ({
    vitals: vitals.filter((v) => isRecent(v.recordedAt)).length,
    triage: triageHistory.filter((t) => isRecent(t.createdAt)).length,
    notes: notes.filter((n) => isRecent(n.createdAt)).length,
    diagnoses: diagnoses.filter((d) => isRecent(d.createdAt)).length,
    // Investigations: count both newly-ordered AND newly-resulted in
    // the window; both are clinically actionable for an incoming
    // doctor (one needs follow-up, one needs interpretation).
    investigationsOrdered: investigations.filter((i) => isRecent(i.orderedAt)).length,
    investigationsResulted: investigations.filter((i) =>
      i.resultedAt != null && isRecent(i.resultedAt),
    ).length,
    medsPrescribed: medications.filter((m) => isRecent(m.prescribedAt)).length,
    medsAdministered: medications.filter((m) =>
      m.administeredAt != null && isRecent(m.administeredAt),
    ).length,
    alerts: alerts.filter((a) => isRecent(a.createdAt)).length,
  }), [vitals, triageHistory, notes, diagnoses, investigations, medications, alerts, cutoff]);

  const totalEvents =
    counts.vitals + counts.triage + counts.notes + counts.diagnoses +
    counts.investigationsOrdered + counts.investigationsResulted +
    counts.medsPrescribed + counts.medsAdministered + counts.alerts;

  const cutoffLabel = formatCutoffLabel(cutoff);

  return (
    <section style={glassCard} className="rounded-2xl p-4">
      <div className="flex items-start justify-between gap-3 mb-3">
        <div className="flex items-center gap-2.5">
          <div className="w-8 h-8 rounded-full bg-blue-500/20 border border-blue-500/30 inline-flex items-center justify-center">
            <Activity className="w-4 h-4 text-blue-300" />
          </div>
          <div>
            <div className={`text-[11px] uppercase font-bold ${text.muted} tracking-wide`}>
              Recent activity
            </div>
            <div className={`text-sm font-bold ${text.heading}`}>
              {totalEvents === 0
                ? 'No new events in this window'
                : `${totalEvents} event${totalEvents === 1 ? '' : 's'} since ${cutoffLabel}`}
            </div>
          </div>
        </div>
        <div className="flex items-center gap-1.5">
          <Clock className={`w-3.5 h-3.5 ${text.muted}`} />
          <select
            value={window}
            onChange={(e) => setWindow(e.target.value as Window)}
            style={glassInner}
            className={`text-xs rounded-lg px-2 py-1 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
            aria-label="Activity window"
          >
            {shiftStartIso && <option value="shift">Since my shift started</option>}
            <option value="1h">Last 1 hour</option>
            <option value="4h">Last 4 hours</option>
            <option value="8h">Last 8 hours</option>
            <option value="24h">Last 24 hours</option>
          </select>
        </div>
      </div>

      {totalEvents > 0 && (
        <div className="grid grid-cols-2 sm:grid-cols-4 lg:grid-cols-5 gap-2 text-[12px]">
          <Pill_ icon={<Activity className="w-3.5 h-3.5" />} label="Vitals"
                count={counts.vitals} accent="text-cyan-600"
                accentStyle={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }} />
          <Pill_ icon={<Stethoscope className="w-3.5 h-3.5" />} label="Triage"
                count={counts.triage} accent="text-rose-600"
                accentStyle={{ background: 'rgba(244,63,94,0.08)', border: '1px solid rgba(244,63,94,0.2)' }} />
          <Pill_ icon={<FileText className="w-3.5 h-3.5" />} label="Notes"
                count={counts.notes} accent="text-indigo-600"
                accentStyle={{ background: 'rgba(99,102,241,0.08)', border: '1px solid rgba(99,102,241,0.2)' }} />
          <Pill_ icon={<ClipboardList className="w-3.5 h-3.5" />} label="Diagnoses"
                count={counts.diagnoses} accent="text-amber-600"
                accentStyle={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }} />
          <Pill_ icon={<FlaskConical className="w-3.5 h-3.5" />}
                label="Lab orders / results"
                count={counts.investigationsOrdered + counts.investigationsResulted}
                accent="text-emerald-600"
                accentStyle={{ background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }}
                subtext={
                  counts.investigationsResulted > 0
                    ? `${counts.investigationsResulted} result${counts.investigationsResulted === 1 ? '' : 's'} back`
                    : undefined
                } />
          <Pill_ icon={<Pill className="w-3.5 h-3.5" />}
                label="Meds"
                count={counts.medsPrescribed + counts.medsAdministered}
                accent="text-violet-600"
                accentStyle={{ background: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.2)' }}
                subtext={
                  counts.medsAdministered > 0
                    ? `${counts.medsAdministered} given`
                    : undefined
                } />
          <Pill_ icon={<AlertTriangle className="w-3.5 h-3.5" />} label="Alerts"
                count={counts.alerts} accent="text-red-600"
                accentStyle={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }} />
        </div>
      )}
    </section>
  );
}

function Pill_({
  icon, label, count, accent, accentStyle, subtext,
}: {
  icon: React.ReactNode;
  label: string;
  count: number;
  accent: string;
  accentStyle: React.CSSProperties;
  subtext?: string;
}) {
  if (count === 0) return null;
  return (
    <div style={accentStyle} className={`px-2 py-1.5 rounded-lg inline-flex items-center gap-2 ${accent}`}>
      <span className="opacity-80">{icon}</span>
      <div className="leading-tight">
        <div className="font-bold">
          {count} <span className="font-medium">{label}</span>
        </div>
        {subtext && <div className="text-[10px] opacity-75">{subtext}</div>}
      </div>
    </div>
  );
}

function formatCutoffLabel(d: Date): string {
  const now = new Date();
  const sameDay = d.toDateString() === now.toDateString();
  const time = d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  return sameDay ? time : `${d.toLocaleDateString([], { month: 'short', day: 'numeric' })} ${time}`;
}
