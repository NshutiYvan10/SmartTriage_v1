/**
 * ShiftStartBanner — first-page-of-shift briefing strip.
 *
 * Renders at the top of the Dashboard when a clinician is on shift,
 * summarising the patient population they just inherited and the
 * outstanding work that crossed the shift boundary. Closes the
 * "no system-generated shift-start signal" gap from the audit:
 * nothing previously told the night doctor "your shift starts now;
 * here are 7 ACUTE patients, 2 with critical labs pending".
 *
 * <p>Dismissable. Dismissal is keyed on the active shift-assignment id
 * (sessionStorage), so:
 *   - Dismissing this shift's banner does not suppress the next shift's
 *     banner.
 *   - Reloading the page within the same shift remembers the dismissal.
 *   - Logging in to a fresh shift re-shows the banner — exactly the
 *     "first-page-of-shift" semantic.
 *
 * <p>Aggregates are derived entirely from the visit list already in the
 * patient store (which is itself zone-scoped server-side). No new API
 * calls.
 */

import { useMemo, useState, useEffect } from 'react';
import { Sun, Moon, AlertTriangle, FlaskConical, Pill, Activity, X, Clock } from 'lucide-react';
import { format } from 'date-fns';
import type { Patient } from '@/types';
import type { ShiftAssignmentResponse } from '@/api/types';

interface Props {
  assignment: ShiftAssignmentResponse | null;
  patients: Patient[];
}

export function ShiftStartBanner({ assignment, patients }: Props) {
  const [dismissed, setDismissed] = useState(false);

  // Re-evaluate dismissal state whenever the assignment changes — a
  // new shift gets a fresh banner even if the previous one was dismissed.
  useEffect(() => {
    if (!assignment) {
      setDismissed(false);
      return;
    }
    const key = `shift-banner-dismissed:${assignment.id}`;
    setDismissed(sessionStorage.getItem(key) === '1');
  }, [assignment?.id]);

  const dismiss = () => {
    if (assignment) {
      sessionStorage.setItem(`shift-banner-dismissed:${assignment.id}`, '1');
    }
    setDismissed(true);
  };

  // Compute zone-aggregate counts. The patientStore feeds zone-scoped
  // visits (server-side filtering), so summing across `patients` already
  // gives "patients in my zone".
  const stats = useMemo(() => {
    const totals = {
      patientCount: patients.length,
      red: 0,
      pendingLabs: 0,
      criticalResults: 0,
      pendingMeds: 0,
      icuPending: 0,
    };
    for (const p of patients) {
      if (p.category === 'RED') totals.red++;
      // Patient is built from VisitResponse — these aggregate fields
      // pass through transparently. Cast through unknown because the
      // Patient type doesn't enumerate every backend field.
      const v = p as unknown as {
        pendingInvestigationsCount?: number;
        unacknowledgedCriticalResultsCount?: number;
        pendingMedicationsCount?: number;
        hasOpenIcuEscalation?: boolean;
      };
      totals.pendingLabs += v.pendingInvestigationsCount ?? 0;
      totals.criticalResults += v.unacknowledgedCriticalResultsCount ?? 0;
      totals.pendingMeds += v.pendingMedicationsCount ?? 0;
      if (v.hasOpenIcuEscalation) totals.icuPending++;
    }
    return totals;
  }, [patients]);

  if (!assignment || dismissed) return null;

  const periodIcon = assignment.shiftPeriod === 'NIGHT'
    ? <Moon className="w-5 h-5 text-indigo-100" />
    : <Sun className="w-5 h-5 text-amber-100" />;
  const periodLabel = assignment.shiftPeriod === 'NIGHT' ? 'Night' : 'Day';
  const startedAt = assignment.startedAt
    ? format(new Date(assignment.startedAt), 'HH:mm')
    : '—';

  // The "any priorities at all?" predicate. When the floor is calm
  // we soften the banner copy.
  const hasPriorities =
    stats.red > 0 || stats.pendingLabs > 0 || stats.criticalResults > 0 ||
    stats.pendingMeds > 0 || stats.icuPending > 0;

  return (
    <section
      className="rounded-2xl overflow-hidden shadow-md animate-fade-down"
      style={{
        background: assignment.shiftPeriod === 'NIGHT'
          ? 'linear-gradient(135deg, #312e81 0%, #1e1b4b 100%)'
          : 'linear-gradient(135deg, #b45309 0%, #92400e 100%)',
      }}
    >
      <div className="p-5 text-white">
        <div className="flex items-start gap-4">
          <div className="flex-shrink-0 w-10 h-10 rounded-full bg-white/15 inline-flex items-center justify-center">
            {periodIcon}
          </div>

          <div className="flex-1 min-w-0">
            <div className="flex items-center gap-3 flex-wrap">
              <div className="text-[11px] font-bold uppercase tracking-widest text-white/70">
                {periodLabel} shift • {assignment.zone}
              </div>
              <div className="text-[11px] inline-flex items-center gap-1 text-white/60">
                <Clock className="w-3 h-3" />
                Started at {startedAt}
              </div>
              {assignment.isShiftLead && (
                <span className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full bg-violet-500/30 text-violet-100 border border-violet-300/30">
                  Shift Lead
                </span>
              )}
              {/* Workflow 4 — covered-zone chips. Multi-zone
                  coverage is the small-hospital reality (one doctor
                  for RESUS + ACUTE + PEDIATRIC). Surfacing it on the
                  banner makes the responsibility unambiguous so the
                  clinician knows what they're seeing in the patient
                  list and alert stream. */}
              {assignment.additionalZones && assignment.additionalZones.length > 0 && (
                <span
                  className="text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-full bg-cyan-500/30 text-cyan-50 border border-cyan-300/30 inline-flex items-center gap-1"
                  title="Additional zones covered on this shift"
                >
                  + {assignment.additionalZones.join(' · ')}
                </span>
              )}
            </div>

            <h2 className="text-lg font-bold mt-1">
              {stats.patientCount === 0
                ? `No active patients in ${assignment.zone} right now.`
                : `${stats.patientCount} ${stats.patientCount === 1 ? 'patient' : 'patients'} in ${assignment.zone}.`}
            </h2>

            {hasPriorities ? (
              <div className="mt-3 flex flex-wrap items-center gap-2">
                {stats.red > 0 && (
                  <Stat icon={<AlertTriangle className="w-3.5 h-3.5" />}
                        label={`${stats.red} RED`} accent="bg-rose-500/30 border-rose-300/40" />
                )}
                {stats.icuPending > 0 && (
                  <Stat icon={<AlertTriangle className="w-3.5 h-3.5" />}
                        label={`${stats.icuPending} ICU pending`} accent="bg-rose-500/30 border-rose-300/40" />
                )}
                {stats.criticalResults > 0 && (
                  <Stat icon={<Activity className="w-3.5 h-3.5" />}
                        label={`${stats.criticalResults} critical result${stats.criticalResults === 1 ? '' : 's'}`}
                        accent="bg-red-500/30 border-red-300/40" />
                )}
                {stats.pendingLabs > 0 && (
                  <Stat icon={<FlaskConical className="w-3.5 h-3.5" />}
                        label={`${stats.pendingLabs} pending lab${stats.pendingLabs === 1 ? '' : 's'}`}
                        accent="bg-emerald-500/30 border-emerald-300/40" />
                )}
                {stats.pendingMeds > 0 && (
                  <Stat icon={<Pill className="w-3.5 h-3.5" />}
                        label={`${stats.pendingMeds} pending med${stats.pendingMeds === 1 ? '' : 's'}`}
                        accent="bg-violet-500/30 border-violet-300/40" />
                )}
              </div>
            ) : stats.patientCount > 0 ? (
              <p className="mt-2 text-sm text-white/80">
                No outstanding labs, medications, or escalations on this list. Quiet shift so far.
              </p>
            ) : null}
          </div>

          <button
            onClick={dismiss}
            className="flex-shrink-0 p-1.5 rounded-lg hover:bg-white/10 transition-colors"
            title="Dismiss for this shift"
            aria-label="Dismiss shift-start banner"
          >
            <X className="w-4 h-4 text-white/60" />
          </button>
        </div>
      </div>
    </section>
  );
}

function Stat({
  icon, label, accent,
}: { icon: React.ReactNode; label: string; accent: string }) {
  return (
    <span className={`inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg border text-[11px] font-bold uppercase tracking-wide text-white ${accent}`}>
      {icon}
      {label}
    </span>
  );
}

