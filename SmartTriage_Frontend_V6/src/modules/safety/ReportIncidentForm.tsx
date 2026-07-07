/* ═══════════════════════════════════════════════════════════════
   ReportIncidentForm — the blameless-reporting entry point.

   Shared by the Safety Incidents page (standalone report) and the patient
   chart (visit-linked report, visitId prefilled). Reporting is deliberately
   open to EVERY staff role; the register/workflow behind it stays a
   governance surface. Speaks the backend's REAL vocabulary — the previous
   form's severity/type lists didn't exist server-side, so most submissions
   400'd and the register stayed empty.
   ═══════════════════════════════════════════════════════════════ */

import { useState } from 'react';
import { ShieldAlert, Loader2, CheckCircle2, AlertTriangle, EyeOff } from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { safetyApi, INCIDENT_TYPES, INCIDENT_SEVERITIES } from '@/api/safety';
import type { SafetyIncident } from '@/api/safety';
import { ApiError } from '@/api/client';

const SEVERITY_HELP: Record<string, string> = {
  NEAR_MISS: 'Caught before reaching the patient',
  NO_HARM: 'Reached the patient, no harm',
  MILD_HARM: 'Required additional monitoring',
  MODERATE_HARM: 'Required intervention',
  SEVERE_HARM: 'Permanent harm / prolonged stay',
  DEATH: 'Contributed to death',
};

function label(s: string) { return s.replace(/_/g, ' '); }

interface ReportIncidentFormProps {
  hospitalId: string;
  /** When reporting from a patient chart — links the incident to the visit. */
  visitId?: string;
  visitNumber?: string | null;
  onReported?: (incident: SafetyIncident) => void;
  onCancel?: () => void;
}

export function ReportIncidentForm({ hospitalId, visitId, visitNumber, onReported, onCancel }: ReportIncidentFormProps) {
  const { glassInner, text } = useTheme();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<SafetyIncident | null>(null);
  const [form, setForm] = useState({
    incidentType: 'MEDICATION_ERROR',
    severity: 'NO_HARM',
    description: '',
    locationInHospital: '',
    contributingFactors: '',
    immediateActions: '',
    patientHarmed: false,
    isAnonymous: false,
  });

  const set = <K extends keyof typeof form>(k: K, v: (typeof form)[K]) =>
    setForm((f) => ({ ...f, [k]: v }));

  const submit = async () => {
    if (!form.description.trim()) return;
    setSubmitting(true);
    setError(null);
    try {
      const incident = await safetyApi.report({
        hospitalId,
        visitId,
        incidentType: form.incidentType,
        severity: form.severity,
        incidentDateTime: new Date().toISOString(),
        description: form.description.trim(),
        locationInHospital: form.locationInHospital.trim() || undefined,
        contributingFactors: form.contributingFactors.trim() || undefined,
        immediateActions: form.immediateActions.trim() || undefined,
        patientHarmed: form.patientHarmed,
        isAnonymous: form.isAnonymous,
      });
      setDone(incident);
      onReported?.(incident);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to report the incident');
    } finally {
      setSubmitting(false);
    }
  };

  if (done) {
    return (
      <div className="text-center py-6">
        <CheckCircle2 className="w-10 h-10 mx-auto mb-3 text-emerald-500" />
        <p className={`text-sm font-bold ${text.heading}`}>Incident reported</p>
        <p className={`text-xs mt-1 ${text.muted}`}>
          Registry number <span className="font-bold">{done.incidentNumber}</span>
          {done.isAnonymous ? ' — filed anonymously.' : ''} Governance has been notified
          {(done.severity === 'SEVERE_HARM' || done.severity === 'DEATH') ? ' and paged (critical severity).' : '.'}
        </p>
        {onCancel && (
          <button onClick={onCancel}
            className={`mt-4 px-4 py-2 text-[11px] font-bold rounded-xl transition-colors hover:bg-white/5 ${text.body}`}>
            Done
          </button>
        )}
      </div>
    );
  }

  return (
    <div className="space-y-3">
      {visitId && (
        <p className={`text-[11px] ${text.muted}`}>
          Linked to visit <span className="font-bold">{visitNumber || visitId}</span> — the incident will
          appear in this patient's governance timeline.
        </p>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        <div>
          <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1 ${text.muted}`}>Incident type</label>
          <select value={form.incidentType} onChange={(e) => set('incidentType', e.target.value)}
            style={glassInner} className={`w-full px-3 py-2 text-xs rounded-xl focus:outline-none ${text.body}`}>
            {INCIDENT_TYPES.map((t) => <option key={t} value={t}>{label(t)}</option>)}
          </select>
        </div>
        <div>
          <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1 ${text.muted}`}>Severity (harm scale)</label>
          <select value={form.severity} onChange={(e) => set('severity', e.target.value)}
            style={glassInner} className={`w-full px-3 py-2 text-xs rounded-xl focus:outline-none ${text.body}`}>
            {INCIDENT_SEVERITIES.map((s) => <option key={s} value={s}>{label(s)} — {SEVERITY_HELP[s]}</option>)}
          </select>
        </div>
      </div>

      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1 ${text.muted}`}>What happened? *</label>
        <textarea value={form.description} onChange={(e) => set('description', e.target.value)} rows={3}
          placeholder="Describe the incident factually — what, when, who was involved, what was affected"
          style={glassInner} className={`w-full px-3 py-2 text-xs rounded-xl focus:outline-none resize-none ${text.body}`} />
      </div>

      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        <input type="text" value={form.locationInHospital} onChange={(e) => set('locationInHospital', e.target.value)}
          placeholder="Location (e.g. Resus bay 2)" style={glassInner}
          className={`px-3 py-2 text-xs rounded-xl focus:outline-none ${text.body}`} />
        <input type="text" value={form.immediateActions} onChange={(e) => set('immediateActions', e.target.value)}
          placeholder="Immediate actions taken" style={glassInner}
          className={`px-3 py-2 text-xs rounded-xl focus:outline-none ${text.body}`} />
      </div>

      <textarea value={form.contributingFactors} onChange={(e) => set('contributingFactors', e.target.value)} rows={2}
        placeholder="Contributing factors (optional) — staffing, equipment, communication, environment…"
        style={glassInner} className={`w-full px-3 py-2 text-xs rounded-xl focus:outline-none resize-none ${text.body}`} />

      <div className="flex items-center gap-4 flex-wrap">
        <label className={`flex items-center gap-2 text-[11px] font-medium cursor-pointer ${text.body}`}>
          <input type="checkbox" checked={form.patientHarmed} onChange={(e) => set('patientHarmed', e.target.checked)}
            className="accent-red-500" />
          Patient was harmed
        </label>
        <label className={`flex items-center gap-2 text-[11px] font-medium cursor-pointer ${text.body}`}>
          <input type="checkbox" checked={form.isAnonymous} onChange={(e) => set('isAnonymous', e.target.checked)}
            className="accent-slate-500" />
          <EyeOff className="w-3.5 h-3.5" /> Report anonymously
        </label>
      </div>
      {form.isAnonymous && (
        <p className={`text-[10px] ${text.muted}`}>
          Your identity will not be recorded on the incident. Anonymous reporting is protected to
          encourage a blameless safety culture.
        </p>
      )}

      {error && (
        <div className="flex items-start gap-2 rounded-xl px-3 py-2.5 bg-red-500/10 border border-red-500/20">
          <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
          <p className="text-[11px] font-semibold text-red-500">{error}</p>
        </div>
      )}

      <div className="flex items-center gap-2">
        <button onClick={submit} disabled={!form.description.trim() || submitting}
          className="inline-flex items-center gap-2 px-5 py-2.5 text-[11px] font-bold rounded-xl bg-gradient-to-r from-red-500 to-rose-500 text-white shadow-md hover:-translate-y-0.5 transition-all disabled:opacity-50">
          {submitting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldAlert className="w-3.5 h-3.5" />}
          Report incident
        </button>
        {onCancel && (
          <button onClick={onCancel}
            className={`px-4 py-2 text-[11px] font-bold rounded-xl transition-colors hover:bg-white/5 ${text.muted}`}>
            Cancel
          </button>
        )}
      </div>
    </div>
  );
}
