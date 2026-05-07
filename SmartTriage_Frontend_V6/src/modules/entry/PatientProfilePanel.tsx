import React, { useState, useEffect, useCallback } from 'react';
import {
  AlertTriangle,
  Heart,
  Droplet,
  Shield,
  Phone,
  Baby,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { patientApi } from '@/api/patients';
import type { PatientResponse, PregnancyStatus } from '@/api/types';
import type { UserRole } from '@/types/roles';

/* ─── Props ─── */
interface PatientProfilePanelProps {
  patient?: PatientResponse;
  patientId?: string;
}

/* ─── Constants ─── */
const PREGNANCY_STATUS_VALUES: PregnancyStatus[] = [
  'PREGNANT',
  'BREASTFEEDING',
  'POSSIBLY_PREGNANT',
  'NOT_PREGNANT',
  'NOT_APPLICABLE',
  'UNKNOWN',
];

const PREGNANCY_STATUS_LABELS: Record<PregnancyStatus, string> = {
  PREGNANT: 'Pregnant',
  BREASTFEEDING: 'Breastfeeding',
  POSSIBLY_PREGNANT: 'Possibly pregnant',
  NOT_PREGNANT: 'Not pregnant',
  NOT_APPLICABLE: 'Not applicable',
  UNKNOWN: 'Unknown',
};

const CAN_EDIT_PREGNANCY_STATUS: UserRole[] = [
  'NURSE',     // includes triage / charge / staff nurse designations
  'DOCTOR',
  'HOSPITAL_ADMIN',
  'SUPER_ADMIN',
];

/* ─── Helpers ─── */
function formatRelativeTime(iso: string | null): string {
  if (!iso) return '';
  const diffMs = Date.now() - new Date(iso).getTime();
  const minutes = Math.floor(diffMs / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.floor(hours / 24);
  return `${days}d ago`;
}

/**
 * Clinically-defensive default when pregnancyStatus arrives null from the
 * server (legacy data path or in-flight migration). Mirrors the backend's
 * PregnancyStatus.defaultFor — keep them in sync.
 *
 * MALE                                 → NOT_APPLICABLE
 * FEMALE / OTHER / UNKNOWN / undefined → UNKNOWN
 *
 * NOT_APPLICABLE for OTHER or UNKNOWN gender would silently skip the very
 * safety check this column exists to drive — UNKNOWN forces the clinician
 * to confirm before prescribing teratogens.
 */
function defaultPregnancyStatusFor(gender: string | null | undefined): PregnancyStatus {
  return gender === 'MALE' ? 'NOT_APPLICABLE' : 'UNKNOWN';
}

/* ─── Section row ─── */
const InfoRow = ({ label, value, accent }: { label: string; value?: string | null; accent?: string }) => (
  <div className="flex items-start justify-between py-2.5 border-b border-gray-100 last:border-0">
    <span className="text-sm text-gray-500 font-medium">{label}</span>
    <span className={`text-sm font-semibold text-right max-w-[60%] ${accent || 'text-gray-900'}`}>
      {value || <span className="text-gray-300 font-normal italic">None on record</span>}
    </span>
  </div>
);

/* ─── Main component ─── */
export function PatientProfilePanel({ patient: propPatient, patientId }: PatientProfilePanelProps) {
  const { glassCard } = useTheme();
  const currentUser = useAuthStore((s) => s.user);

  const [patient, setPatient] = useState<PatientResponse | undefined>(propPatient);
  const [loading, setLoading] = useState(!propPatient && !!patientId);
  const [pregnancyUpdating, setPregnancyUpdating] = useState(false);
  const [pregnancyError, setPregnancyError] = useState<string | null>(null);

  // Fetch patient if only patientId is provided
  useEffect(() => {
    if (propPatient) {
      setPatient(propPatient);
      return;
    }
    if (!patientId) return;
    let cancelled = false;
    setLoading(true);
    patientApi.getById(patientId).then((res) => {
      if (!cancelled) setPatient(res);
    }).catch(() => {
      // Silently fail — panel is supplementary
    }).finally(() => {
      if (!cancelled) setLoading(false);
    });
    return () => { cancelled = true; };
  }, [propPatient, patientId]);

  const canEditPregnancy = currentUser
    ? CAN_EDIT_PREGNANCY_STATUS.includes(currentUser.role)
    : false;

  const handlePregnancyChange = useCallback(async (e: React.ChangeEvent<HTMLSelectElement>) => {
    if (!patient) return;
    const newStatus = e.target.value as PregnancyStatus;
    const previousStatus = patient.pregnancyStatus;
    const previousRecordedAt = patient.pregnancyStatusRecordedAt;

    // Optimistic update
    setPatient((prev) => prev ? {
      ...prev,
      pregnancyStatus: newStatus,
      pregnancyStatusRecordedAt: new Date().toISOString(),
    } : prev);
    setPregnancyError(null);
    setPregnancyUpdating(true);

    try {
      const updated = await patientApi.updatePregnancyStatus(patient.id, newStatus);
      setPatient(updated);
    } catch (err: any) {
      // Rollback
      setPatient((prev) => prev ? {
        ...prev,
        pregnancyStatus: previousStatus,
        pregnancyStatusRecordedAt: previousRecordedAt,
      } : prev);
      setPregnancyError(err?.message ?? 'Failed to update pregnancy status');
    } finally {
      setPregnancyUpdating(false);
    }
  }, [patient]);

  if (loading) {
    return (
      <div style={glassCard} className="rounded-2xl p-6 animate-pulse">
        <div className="h-4 bg-gray-200 rounded w-1/3 mb-4" />
        <div className="space-y-3">
          <div className="h-3 bg-gray-200 rounded w-full" />
          <div className="h-3 bg-gray-200 rounded w-2/3" />
        </div>
      </div>
    );
  }

  if (!patient) return null;

  return (
    <div className="space-y-4">
      {/* ─── Allergies (highest priority — red) ─── */}
      <div className="bg-white rounded-2xl shadow-sm border-2 border-red-200 overflow-hidden">
        <div className="flex items-center gap-3 px-5 py-3.5 border-b border-red-200 bg-gradient-to-r from-red-50 to-white">
          <div className="w-8 h-8 rounded-lg bg-red-50 flex items-center justify-center">
            <AlertTriangle className="w-4 h-4 text-red-600" />
          </div>
          <h3 className="text-sm font-bold text-gray-900 tracking-tight">Allergies</h3>
        </div>
        <div className="px-5 py-3">
          <span className={`text-sm ${patient.knownAllergies ? 'font-semibold text-red-700' : 'text-gray-300 italic'}`}>
            {patient.knownAllergies || 'None on record'}
          </span>
        </div>
      </div>

      {/* ─── Chronic Conditions (amber) ─── */}
      <div className="bg-white rounded-2xl shadow-sm border border-amber-200 overflow-hidden">
        <div className="flex items-center gap-3 px-5 py-3.5 border-b border-amber-200 bg-gradient-to-r from-amber-50 to-white">
          <div className="w-8 h-8 rounded-lg bg-amber-50 flex items-center justify-center">
            <Heart className="w-4 h-4 text-amber-600" />
          </div>
          <h3 className="text-sm font-bold text-gray-900 tracking-tight">Chronic Conditions</h3>
        </div>
        <div className="px-5 py-3">
          <span className={`text-sm ${patient.chronicConditions ? 'font-semibold text-amber-700' : 'text-gray-300 italic'}`}>
            {patient.chronicConditions || 'None on record'}
          </span>
        </div>
      </div>

      {/* ─── Blood Type ─── */}
      <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
        <div className="flex items-center gap-3 px-5 py-3.5 border-b border-gray-200 bg-gradient-to-r from-gray-50 to-white">
          <div className="w-8 h-8 rounded-lg bg-rose-50 flex items-center justify-center">
            <Droplet className="w-4 h-4 text-rose-600" />
          </div>
          <h3 className="text-sm font-bold text-gray-900 tracking-tight">Blood Type</h3>
        </div>
        <div className="px-5 py-3">
          <InfoRow label="Type" value={patient.bloodType} />
        </div>
      </div>

      {/* ─── Pregnancy Status ─── */}
      {(() => {
        // Effective status: server value if present, else gender-aware default.
        // Mirrors the backend; never silently skip the safety check.
        const effectiveStatus: PregnancyStatus =
          patient.pregnancyStatus ?? defaultPregnancyStatusFor(patient.gender);
        // recordedAt = null while status is set means a synthetic default
        // (backfill or registration-time placeholder). Surface it so a
        // clinician knows confirmation is still owed.
        const needsConfirmation = patient.pregnancyStatusRecordedAt == null;

        return (
          <div className="bg-white rounded-2xl shadow-sm border border-violet-200 overflow-hidden">
            <div className="flex items-center gap-3 px-5 py-3.5 border-b border-violet-200 bg-gradient-to-r from-violet-50 to-white">
              <div className="w-8 h-8 rounded-lg bg-violet-50 flex items-center justify-center">
                <Baby className="w-4 h-4 text-violet-600" />
              </div>
              <h3 className="text-sm font-bold text-gray-900 tracking-tight">Pregnancy Status</h3>
              {patient.pregnancyStatusRecordedAt && (
                <span className="ml-auto text-xs text-gray-400">
                  {formatRelativeTime(patient.pregnancyStatusRecordedAt)}
                </span>
              )}
            </div>
            <div className="px-5 py-3">
              {canEditPregnancy ? (
                <div className="space-y-2">
                  <select
                    value={effectiveStatus}
                    onChange={handlePregnancyChange}
                    disabled={pregnancyUpdating}
                    className="w-full text-sm font-medium border border-gray-200 rounded-lg px-3 py-2 bg-white focus:outline-none focus:ring-2 focus:ring-violet-300 focus:border-violet-400 disabled:opacity-50 disabled:cursor-wait"
                  >
                    {PREGNANCY_STATUS_VALUES.map((status) => (
                      <option key={status} value={status}>
                        {PREGNANCY_STATUS_LABELS[status]}
                      </option>
                    ))}
                  </select>
                  {needsConfirmation && (
                    <p className="text-xs text-amber-700 font-medium">
                      Default value — please confirm with the patient.
                    </p>
                  )}
                  {pregnancyError && (
                    <p className="text-xs text-red-600 font-medium">{pregnancyError}</p>
                  )}
                </div>
              ) : (
                <div className="space-y-1">
                  <span className="text-sm font-semibold text-gray-900">
                    {PREGNANCY_STATUS_LABELS[effectiveStatus]}
                  </span>
                  {needsConfirmation && (
                    <p className="text-xs text-amber-700 font-medium">
                      Default value — not yet confirmed by a clinician.
                    </p>
                  )}
                </div>
              )}
            </div>
          </div>
        );
      })()}

      {/* ─── Guardian ─── */}
      <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
        <div className="flex items-center gap-3 px-5 py-3.5 border-b border-gray-200 bg-gradient-to-r from-gray-50 to-white">
          <div className="w-8 h-8 rounded-lg bg-blue-50 flex items-center justify-center">
            <Shield className="w-4 h-4 text-blue-600" />
          </div>
          <h3 className="text-sm font-bold text-gray-900 tracking-tight">Guardian</h3>
        </div>
        <div className="px-5 py-3">
          <InfoRow label="Name" value={patient.emergencyContactName} />
          <InfoRow label="Phone" value={patient.emergencyContactPhone} />
        </div>
      </div>

      {/* ─── Emergency Contact ─── */}
      <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
        <div className="flex items-center gap-3 px-5 py-3.5 border-b border-gray-200 bg-gradient-to-r from-gray-50 to-white">
          <div className="w-8 h-8 rounded-lg bg-emerald-50 flex items-center justify-center">
            <Phone className="w-4 h-4 text-emerald-600" />
          </div>
          <h3 className="text-sm font-bold text-gray-900 tracking-tight">Emergency Contact</h3>
        </div>
        <div className="px-5 py-3">
          <InfoRow label="Name" value={patient.emergencyContactName} />
          <InfoRow label="Phone" value={patient.emergencyContactPhone} />
        </div>
      </div>
    </div>
  );
}
