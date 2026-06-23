/* ── CrossHospitalPanel (Phase 3) ──
 *
 * Per-visit chart tab showing the patient's bounded clinical history from OTHER SmartTriage
 * hospitals. Disclosure is gated server-side: served when the patient's data-sharing CONSENT is
 * on file, otherwise locked with a break-the-glass emergency override (mandatory reason, recorded
 * forensically + governance-alerted). Read-only, provenance-tagged.
 */
import { useCallback, useEffect, useState } from 'react';
import {
  Globe, Loader2, Lock, ShieldAlert, ShieldCheck, Building2, Stethoscope,
  FlaskConical, FileText, Pill, ChevronRight,
} from 'lucide-react';
import { crossHospitalApi, type CrossHospitalDeepRecord } from '@/api/crossHospital';
import { ApiError } from '@/api/client';
import { useTheme } from '@/hooks/useTheme';
import { BreakTheGlassModal } from './BreakTheGlassModal';

interface Props {
  nationalId: string | null;
}

export function CrossHospitalPanel({ nationalId }: Props) {
  const { glassCard, isDark, text } = useTheme();
  const [record, setRecord] = useState<CrossHospitalDeepRecord | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showBreakGlass, setShowBreakGlass] = useState(false);

  const load = useCallback(async (breakTheGlassReason?: string) => {
    if (!nationalId) { setLoading(false); return; }
    setLoading(true);
    setError(null);
    try {
      setRecord(await crossHospitalApi.getDeepRecord(nationalId, breakTheGlassReason));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load cross-hospital record');
    } finally {
      setLoading(false);
    }
  }, [nationalId]);

  useEffect(() => { load(); }, [load]);

  if (!nationalId) {
    return (
      <div className="rounded-xl p-6 text-center text-sm text-slate-500" style={glassCard}>
        This patient has no national ID on file, so no cross-hospital identity exists.
      </div>
    );
  }
  if (loading) {
    return (
      <div className="flex items-center justify-center gap-2 py-10 text-sm text-slate-500">
        <Loader2 className="w-5 h-5 animate-spin" /> Loading cross-hospital record…
      </div>
    );
  }
  if (error) {
    return (
      <div className="rounded-xl p-4 text-sm font-semibold text-red-700 bg-red-50 border border-red-200">
        {error}
      </div>
    );
  }
  if (!record || !record.found) {
    return (
      <div className="rounded-xl p-6 text-center text-sm text-slate-500" style={glassCard}>
        No cross-hospital record found for this patient.
      </div>
    );
  }

  // Access denied → locked state with break-the-glass.
  if (!record.accessGranted) {
    const label = `${record.firstName ?? ''} ${record.lastName ?? ''}`.trim() || undefined;
    return (
      <div className="space-y-4">
        <div className="rounded-xl p-6 text-center" style={glassCard}>
          <Lock className="w-10 h-10 mx-auto text-amber-500 mb-3" />
          <h3 className={`text-sm font-bold ${text.heading}`}>Cross-hospital record is locked</h3>
          <p className="text-xs text-slate-500 mt-1 max-w-md mx-auto">
            This patient is registered at {record.linkedHospitalCount} SmartTriage hospital
            {record.linkedHospitalCount === 1 ? '' : 's'}, but has not consented to sharing their
            deep clinical record. Consent can be recorded at registration. In an emergency, you may
            break the glass — an audited, governance-notified override.
          </p>
          <button
            onClick={() => setShowBreakGlass(true)}
            className="inline-flex items-center gap-1.5 mt-4 px-4 py-2 rounded-lg text-xs font-bold text-white bg-red-600 hover:bg-red-500"
          >
            <ShieldAlert className="w-4 h-4" /> Break the glass
          </button>
        </div>
        {showBreakGlass && (
          <BreakTheGlassModal
            patientLabel={label}
            onConfirm={async (reason) => { await load(reason); setShowBreakGlass(false); }}
            onClose={() => setShowBreakGlass(false)}
          />
        )}
      </div>
    );
  }

  // Access granted → render bounded provenance-tagged history.
  const basisGranted = record.accessBasis === 'BREAK_THE_GLASS';
  return (
    <div className="space-y-4">
      {/* Access basis chip */}
      <div className={`rounded-xl px-4 py-3 flex items-center gap-2 ${
        basisGranted ? 'bg-red-50 border border-red-200' : 'bg-emerald-50 border border-emerald-200'}`}>
        {basisGranted ? <ShieldAlert className="w-4 h-4 text-red-600" /> : <ShieldCheck className="w-4 h-4 text-emerald-600" />}
        <div className="text-xs">
          <span className={`font-bold ${basisGranted ? 'text-red-700' : 'text-emerald-700'}`}>
            {basisGranted ? 'BREAK-THE-GLASS access' : 'Access by patient consent'}
          </span>
          <span className="text-slate-500">
            {' · '}{record.linkedHospitalCount} hospital{record.linkedHospitalCount === 1 ? '' : 's'}
            {basisGranted ? ' · this override has been logged and is auditable' : ''}
          </span>
        </div>
      </div>

      {/* Cross-visit medication history */}
      {record.medicationHistory && record.medicationHistory.length > 0 && (
        <div className="rounded-xl p-4" style={glassCard}>
          <div className="flex items-center gap-2 mb-2">
            <Pill className="w-4 h-4 text-cyan-600" />
            <h4 className={`text-xs font-bold uppercase tracking-wide ${text.heading}`}>Medication history</h4>
          </div>
          <ul className="space-y-1">
            {record.medicationHistory.map((m, i) => (
              <li key={i} className="text-xs text-slate-600">{m}</li>
            ))}
          </ul>
        </div>
      )}

      {/* Per-hospital sections */}
      {(record.hospitals ?? []).map((h, hi) => (
        <div key={hi} className="rounded-xl p-4" style={glassCard}>
          <div className="flex items-center gap-2 mb-3">
            <Building2 className="w-4 h-4 text-slate-500" />
            <h4 className={`text-sm font-bold ${text.heading}`}>{h.sourceHospital}</h4>
            {h.truncated && (
              <span className="text-[10px] font-semibold text-amber-600 bg-amber-50 px-1.5 py-0.5 rounded">
                showing most recent visits
              </span>
            )}
          </div>

          {(h.visits ?? []).length === 0 ? (
            <p className="text-xs text-slate-400">No visit summaries.</p>
          ) : (
            <div className="space-y-3">
              {(h.visits ?? []).map((v, vi) => (
                <div key={vi} className={`rounded-lg p-3 ${isDark ? 'bg-white/[0.03] border border-white/10' : 'bg-slate-50 border border-slate-200'}`}>
                  <div className="flex items-center gap-2 mb-1.5 text-xs">
                    <ChevronRight className="w-3.5 h-3.5 text-slate-400" />
                    <span className={`font-bold ${text.heading}`}>Visit {v.visitNumber ?? '—'}</span>
                    {v.status && <span className="text-slate-400">· {v.status}</span>}
                    {v.arrivalTime && <span className="text-slate-400">· {new Date(v.arrivalTime).toLocaleDateString()}</span>}
                  </div>
                  <Section icon={Stethoscope} label="Diagnoses" items={v.diagnoses} tone="text-indigo-600" />
                  <Section icon={FlaskConical} label="Critical labs" items={v.criticalLabs} tone="text-red-600" />
                  <Section icon={FileText} label="Discharge summaries" items={v.dischargeSummaries} tone="text-emerald-600" />
                  <Section icon={FileText} label="Key notes" items={v.keyNotes} tone="text-slate-600" />
                </div>
              ))}
            </div>
          )}
        </div>
      ))}

      {(record.hospitals ?? []).length === 0 && (
        <div className="rounded-xl p-6 text-center text-sm text-slate-500 flex items-center justify-center gap-2" style={glassCard}>
          <Globe className="w-4 h-4" /> No detailed history available across hospitals.
        </div>
      )}
    </div>
  );
}

function Section({ icon: Icon, label, items, tone }: {
  icon: typeof Stethoscope; label: string; items: string[] | null; tone: string;
}) {
  if (!items || items.length === 0) return null;
  return (
    <div className="mt-1.5">
      <div className={`flex items-center gap-1.5 text-[11px] font-bold uppercase tracking-wide ${tone}`}>
        <Icon className="w-3 h-3" /> {label}
      </div>
      <ul className="mt-0.5 ml-4 space-y-0.5">
        {items.map((it, i) => <li key={i} className="text-xs text-slate-600 list-disc">{it}</li>)}
      </ul>
    </div>
  );
}
