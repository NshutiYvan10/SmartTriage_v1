import { FlaskConical, Pill, AlertTriangle, Activity } from 'lucide-react';

/**
 * Structural type — anything that exposes the four shift-handoff
 * aggregate fields satisfies it. Lets the badges work directly off a
 * VisitResponse (Doctor Workspace), a Patient (PatientsList — the
 * store mapper now copies the fields through), or a synthetic object
 * (Monitoring's MonitoredPatient).
 */
export interface HandoffSignals {
  pendingInvestigationsCount?: number | null;
  unacknowledgedCriticalResultsCount?: number | null;
  pendingMedicationsCount?: number | null;
  hasOpenIcuEscalation?: boolean | null;
}

/**
 * Priority badges rendered on patient cards (Doctor Workspace,
 * PatientsList, Monitoring, any other active-visit list) so an
 * inheriting clinician sees, at a glance, exactly which patients have
 * outstanding work that crossed the shift boundary.
 *
 * <p>Drives off the aggregate counts populated by the backend's
 * {@code enrichWithHandoverSignals} on every active-visits list
 * response — no per-card fetches, no N+1.
 *
 * <p>A badge is hidden when its count is 0 / its boolean is false, so
 * the card stays clean for patients with nothing pending and the
 * present badges genuinely demand attention.
 *
 * <p>Two display modes:
 * <ul>
 *   <li>{@code full} (default) — all four signals: ICU pending,
 *       critical results, pending labs, pending meds. Right for
 *       browse-style surfaces (Doctor Workspace, PatientsList).</li>
 *   <li>{@code urgent-only} — only ICU pending + critical results.
 *       Right for the real-time Monitoring page where the dominant
 *       signal is physiological deterioration, and the dense card
 *       already has vitals + ECG + alerts competing for the eye.
 *       "3 pending labs" on a patient who's coding is noise; an
 *       open ICU escalation or a crit result back is not.</li>
 * </ul>
 */
type BadgeMode = 'full' | 'urgent-only';

export function HandoffPriorityBadges({
  signals, mode = 'full',
}: { signals: HandoffSignals; mode?: BadgeMode }) {
  const labs = signals.pendingInvestigationsCount ?? 0;
  const critResults = signals.unacknowledgedCriticalResultsCount ?? 0;
  const meds = signals.pendingMedicationsCount ?? 0;
  const icu = !!signals.hasOpenIcuEscalation;
  const urgentOnly = mode === 'urgent-only';

  // Render-relevant signals after mode filtering.
  const showLabs = labs > 0 && !urgentOnly;
  const showMeds = meds > 0 && !urgentOnly;
  const showCrit = critResults > 0;
  const showIcu = icu;

  if (!showLabs && !showMeds && !showCrit && !showIcu) {
    return null;
  }

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {showIcu && (
        <Badge
          icon={<AlertTriangle className="w-3 h-3" />}
          label="ICU pending"
          accent="bg-rose-50 text-rose-700 border-rose-200"
          title="Open ICU escalation — not yet resolved"
        />
      )}
      {showCrit && (
        <Badge
          icon={<Activity className="w-3 h-3" />}
          label={`${critResults} crit result${critResults === 1 ? '' : 's'}`}
          accent="bg-red-50 text-red-700 border-red-200"
          title="Critical / abnormal lab result(s) back — needs review"
        />
      )}
      {showLabs && (
        <Badge
          icon={<FlaskConical className="w-3 h-3" />}
          label={`${labs} pending lab${labs === 1 ? '' : 's'}`}
          accent="bg-emerald-50 text-emerald-700 border-emerald-200"
          title="Lab order(s) ordered or specimen-collected — awaiting result"
        />
      )}
      {showMeds && (
        <Badge
          icon={<Pill className="w-3 h-3" />}
          label={`${meds} pending med${meds === 1 ? '' : 's'}`}
          accent="bg-violet-50 text-violet-700 border-violet-200"
          title="Medication(s) prescribed but not yet administered"
        />
      )}
    </div>
  );
}

function Badge({
  icon, label, accent, title,
}: { icon: React.ReactNode; label: string; accent: string; title: string }) {
  return (
    <span
      title={title}
      className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md border text-[10px] font-bold uppercase tracking-wide ${accent}`}
    >
      {icon}
      {label}
    </span>
  );
}
