/* ── Cross-hospital safety banner (Phase 3) ──
 *
 * Shown at registration once an existing patient is picked. Fetches the always-available
 * cross-hospital SAFETY SUMMARY by national ID and surfaces life-critical flags recorded at OTHER
 * SmartTriage hospitals (allergies, chronic conditions, active meds), each tagged with its source.
 * Non-blocking — it never gates registration. Also the entry point for recording data-sharing
 * consent (role-gated), which unlocks the cross-hospital deep record for treating clinicians.
 */
import { useCallback, useEffect, useState } from 'react';
import { Globe, ShieldAlert, Pill, Activity, FileSignature } from 'lucide-react';
import { crossHospitalApi, type CrossHospitalSafetySummary, type CrossHospitalSafetyItem } from '@/api/crossHospital';
import { ApiError } from '@/api/client';
import { useAuthStore } from '@/store/authStore';
import type { UserRole } from '@/types/roles';
import { DataSharingConsentModal } from './DataSharingConsentModal';

const CONSENT_ROLES: UserRole[] = ['SUPER_ADMIN', 'DOCTOR', 'NURSE', 'REGISTRAR'];

interface Props {
  nationalId: string;
  patientName?: string;
}

export function CrossHospitalSafetyBanner({ nationalId, patientName }: Props) {
  const role = useAuthStore((s) => s.user?.role);
  const [summary, setSummary] = useState<CrossHospitalSafetySummary | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showConsent, setShowConsent] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setError(null);
    try {
      setSummary(await crossHospitalApi.getSafetySummary(nationalId));
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not load cross-hospital records');
    } finally {
      setLoading(false);
    }
  }, [nationalId]);

  useEffect(() => { load(); }, [load]);

  if (loading) {
    return (
      <div className="rounded-xl px-4 py-3 text-xs font-medium text-amber-700 animate-fade-in"
        style={{ background: 'rgba(217,119,6,0.06)', border: '1px solid rgba(217,119,6,0.2)' }}>
        Checking other SmartTriage hospitals…
      </div>
    );
  }
  if (error) {
    return (
      <div className="rounded-xl px-4 py-3 text-xs font-medium text-slate-500 animate-fade-in"
        style={{ background: 'rgba(100,116,139,0.06)', border: '1px solid rgba(100,116,139,0.2)' }}>
        Cross-hospital lookup unavailable — {error}
      </div>
    );
  }
  if (!summary || !summary.found) return null;

  const otherHospitals = Math.max(0, (summary.linkedHospitalCount ?? 1) - 1);
  const items: { icon: typeof ShieldAlert; label: string; list: CrossHospitalSafetyItem[] | null; tone: string }[] = [
    { icon: ShieldAlert, label: 'Allergies', list: summary.allergies, tone: 'text-red-700' },
    { icon: Activity, label: 'Chronic conditions', list: summary.chronicConditions, tone: 'text-amber-700' },
    { icon: Pill, label: 'Active medications', list: summary.activeMedications, tone: 'text-cyan-700' },
  ];
  const hasAnyItem = items.some((g) => g.list && g.list.length > 0);
  const canManageConsent = role != null && CONSENT_ROLES.includes(role);

  return (
    <div className="rounded-xl px-4 py-3 animate-fade-in"
      style={{ background: 'rgba(217,119,6,0.08)', border: '1px solid rgba(217,119,6,0.3)' }}>
      <div className="flex items-start gap-3">
        <Globe className="w-5 h-5 text-amber-600 flex-shrink-0 mt-0.5" />
        <div className="flex-1 min-w-0">
          <p className="text-sm font-bold text-amber-700">
            Cross-hospital record found
          </p>
          <p className="text-xs text-amber-600/80 font-medium mt-0.5">
            {otherHospitals > 0
              ? `This patient also has records at ${otherHospitals} other SmartTriage hospital${otherHospitals > 1 ? 's' : ''}.`
              : 'This patient is registered only at this hospital so far.'}
            {hasAnyItem ? ' Review the safety flags below before proceeding.' : ''}
          </p>

          {hasAnyItem && (
            <div className="mt-2 space-y-1.5">
              {items.filter((g) => g.list && g.list.length > 0).map((g) => (
                <div key={g.label} className="flex items-start gap-2">
                  <g.icon className={`w-3.5 h-3.5 flex-shrink-0 mt-0.5 ${g.tone}`} />
                  <div className="text-xs">
                    <span className={`font-bold uppercase tracking-wide ${g.tone}`}>{g.label}: </span>
                    <span className="text-slate-700">
                      {g.list!.map((it, i) => (
                        <span key={i}>
                          {i > 0 ? '; ' : ''}{it.detail}
                          <span className="text-slate-400"> ({it.sourceHospital})</span>
                        </span>
                      ))}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}

          {canManageConsent && (
            <button
              type="button"
              onClick={() => setShowConsent(true)}
              className="inline-flex items-center gap-1.5 mt-2.5 text-xs font-semibold text-amber-700 hover:text-amber-900 px-2.5 py-1 rounded-md hover:bg-amber-100 transition-colors"
            >
              <FileSignature className="w-3.5 h-3.5" />
              Record data-sharing consent
            </button>
          )}
        </div>
      </div>

      {showConsent && (
        <DataSharingConsentModal
          nationalId={nationalId}
          patientName={patientName}
          onClose={() => setShowConsent(false)}
        />
      )}
    </div>
  );
}
