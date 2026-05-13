/**
 * PatientProfilePanel — patient-level persistent clinical facts.
 *
 * Why this exists:
 *   A Visit holds what's happening NOW. The Patient holds what's TRUE
 *   ABOUT THE PERSON regardless of which visit they're on — blood type,
 *   known allergies, chronic conditions, guardian. Those facts are
 *   safety-critical (a penicillin allergy should be impossible to miss
 *   when ordering antibiotics) and they're invisible on most surfaces
 *   today.
 *
 * This panel surfaces them as a single compact card with loud visual
 * treatment for the dangerous bits (allergies → red alert, conditions →
 * amber tag, blood type → drop icon).
 *
 * Two consumption modes:
 *   - Pass `patient` directly when the caller already holds a fresh
 *     PatientResponse (avoids re-fetch).
 *   - Pass `patientId` and the panel fetches `patientApi.getById`
 *     itself.
 *
 * Empty/unknown handling: if a field is null, blank, or "None", we
 * render a muted "Unknown" / "None on record" rather than hiding the
 * row. Hiding lies — "no allergies displayed" reads like "no allergies"
 * and that misreading kills people.
 */
import { useEffect, useState } from 'react';
import {
  ShieldAlert, Heart, Baby, Phone, AlertCircle, Loader2, Droplet, Activity,
  Pencil, Check, X,
} from 'lucide-react';
import { patientApi } from '@/api/patients';
import type { PatientResponse } from '@/api/types';
import { useTheme } from '@/hooks/useTheme';
import { PatientAllergiesPanel } from '@/modules/patient/PatientAllergiesPanel';

interface Props {
  /** Provide either patientId (panel fetches) … */
  patientId?: string | null;
  /** … or a fresh PatientResponse (panel uses it directly). */
  patient?: PatientResponse | null;
  /**
   * When true, allergies and chronic conditions show pencil icons that
   * open inline editors (textarea + save/cancel). Saving calls
   * patientApi.updateAllergies / updateChronicConditions and refreshes
   * local state. Default false — read-only is the safer default for
   * any surface that hasn't explicitly opted in (e.g. registration
   * preview cards).
   */
  editable?: boolean;
}

/**
 * Inline edit row used by `editable` mode. Shows the value in display
 * mode with a pencil. Click pencil → textarea + save/cancel buttons.
 * Save calls the API and (on success) updates the parent's patient
 * state so the panel re-renders with the new value.
 */
function EditableMedicalRow({
  label, value, onSave, isDark, subtleTextCls, headerTextCls, emptyText, accentColorClass,
}: {
  label: string;
  value: string | null | undefined;
  onSave: (next: string | null) => Promise<void>;
  isDark: boolean;
  subtleTextCls: string;
  headerTextCls: string;
  emptyText: string;
  accentColorClass: string;
}) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState<string>(value ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Keep draft in sync when parent value changes between renders
  // (e.g. panel re-fetches after a save).
  useEffect(() => {
    if (!editing) setDraft(value ?? '');
  }, [value, editing]);

  if (!editing) {
    return (
      <button
        type="button"
        onClick={() => { setDraft(value ?? ''); setEditing(true); setError(null); }}
        className={`inline-flex items-center gap-1 text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${accentColorClass} hover:bg-white/10 transition-colors`}
        aria-label={`Edit ${label}`}
      >
        <Pencil className="w-3 h-3" /> Edit
      </button>
    );
  }

  return (
    <div className="mt-2 flex flex-col gap-1.5">
      <textarea
        autoFocus
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        rows={2}
        placeholder={emptyText}
        className={`w-full px-2 py-1.5 text-sm rounded-md border resize-none outline-none ${
          isDark ? 'bg-slate-800 text-white border-white/10' : 'bg-white text-slate-900 border-slate-300'
        }`}
      />
      {error && (
        <div className="text-[11px] text-red-500 flex items-center gap-1">
          <AlertCircle className="w-3 h-3" /> {error}
        </div>
      )}
      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={saving}
          onClick={async () => {
            setSaving(true);
            setError(null);
            try {
              const trimmed = draft.trim();
              await onSave(trimmed === '' ? null : trimmed);
              setEditing(false);
            } catch (err: any) {
              setError(err?.message ?? `Failed to save ${label.toLowerCase()}`);
            } finally {
              setSaving(false);
            }
          }}
          className="inline-flex items-center gap-1 text-[11px] font-bold px-2 py-1 rounded-md bg-emerald-600 text-white hover:bg-emerald-700 disabled:opacity-50"
        >
          {saving ? <Loader2 className="w-3 h-3 animate-spin" /> : <Check className="w-3 h-3" />} Save
        </button>
        <button
          type="button"
          disabled={saving}
          onClick={() => { setEditing(false); setError(null); setDraft(value ?? ''); }}
          className={`inline-flex items-center gap-1 text-[11px] font-bold px-2 py-1 rounded-md ${
            isDark ? 'bg-white/10 text-white' : 'bg-slate-200 text-slate-800'
          } hover:opacity-80`}
        >
          <X className="w-3 h-3" /> Cancel
        </button>
        <span className={`text-[10px] ${subtleTextCls} ${headerTextCls === '' ? '' : ''}`}>
          Empty saves as cleared.
        </span>
      </div>
    </div>
  );
}

/**
 * Treats null / blank / "none" / "n/a" / "no known" as empty so we don't
 * render a comforting-looking value when the data is actually absent.
 * Casing-insensitive; trims whitespace.
 */
function isEmpty(v: string | null | undefined): boolean {
  if (!v) return true;
  const lower = v.trim().toLowerCase();
  return (
    lower === '' ||
    lower === 'none' ||
    lower === 'n/a' ||
    lower === 'na' ||
    lower === 'nil' ||
    lower === 'no known' ||
    lower === 'no known allergies' ||
    lower === 'no known conditions' ||
    lower === 'unknown'
  );
}

export function PatientProfilePanel({ patientId, patient: patientProp, editable = false }: Props) {
  const { isDark, glassCard } = useTheme();

  const [patient, setPatient] = useState<PatientResponse | null>(patientProp ?? null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Sync incoming `patient` prop without a refetch when caller already
  // holds the data. Preferred path on the registration surface where
  // applyLookupCandidate has just received the full PatientResponse.
  useEffect(() => {
    if (patientProp) {
      setPatient(patientProp);
      setError(null);
      setLoading(false);
    }
  }, [patientProp]);

  // Fetch by ID only when caller didn't pass `patient`. Used by the
  // VisitDetailPage where we have just patientId on VisitResponse.
  useEffect(() => {
    if (patientProp || !patientId) return;
    let cancelled = false;
    setLoading(true);
    setError(null);
    patientApi
      .getById(patientId)
      .then((p) => {
        if (cancelled) return;
        setPatient(p);
      })
      .catch((err: any) => {
        if (cancelled) return;
        setError(err?.message ?? 'Failed to load patient profile');
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [patientId, patientProp]);

  if (!patientId && !patientProp) return null;

  const cardCls = `rounded-xl shadow-md p-4 ${
    isDark ? glassCard + ' border border-white/10' : 'bg-white border border-gray-200'
  }`;
  const headerTextCls = isDark ? 'text-white' : 'text-gray-900';
  const subtleTextCls = isDark ? 'text-slate-400' : 'text-gray-500';

  if (loading) {
    return (
      <div className={cardCls}>
        <div className="flex items-center gap-2 text-sm">
          <Loader2 className={`w-4 h-4 animate-spin ${subtleTextCls}`} />
          <span className={subtleTextCls}>Loading patient profile…</span>
        </div>
      </div>
    );
  }
  if (error) {
    return (
      <div className={cardCls}>
        <div className="flex items-center gap-2 text-sm text-red-500">
          <AlertCircle className="w-4 h-4" />
          {error}
        </div>
      </div>
    );
  }
  if (!patient) return null;

  const allergiesEmpty   = isEmpty(patient.knownAllergies);
  const conditionsEmpty  = isEmpty(patient.chronicConditions);
  const bloodTypeEmpty   = isEmpty(patient.bloodType);

  // Allergy block uses the most aggressive visual treatment in the
  // app — red border, AlertTriangle icon, bold copy. The intent is "do
  // not let a clinician miss this." Empty case stays muted so absence
  // doesn't blend with presence.
  const allergyClasses = allergiesEmpty
    ? `${isDark ? 'bg-white/[0.04] border-white/10' : 'bg-gray-50 border-gray-200'}`
    : 'bg-red-50 border-red-300';

  const conditionsClasses = conditionsEmpty
    ? `${isDark ? 'bg-white/[0.04] border-white/10' : 'bg-gray-50 border-gray-200'}`
    : 'bg-amber-50 border-amber-300';

  return (
    <div className={cardCls}>
      <div className="flex items-center justify-between mb-3">
        <h3 className={`text-sm font-bold flex items-center gap-2 ${headerTextCls}`}>
          <Activity className="w-4 h-4" />
          Patient profile
        </h3>
        <div className="flex items-center gap-1.5 flex-wrap">
          {patient.medicalRecordNumber && (
            <span className={`text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${
              isDark ? 'bg-blue-500/20 text-blue-200' : 'bg-blue-100 text-blue-700'
            }`}>
              MRN {patient.medicalRecordNumber}
            </span>
          )}
          {patient.isPediatric && (
            <span className="text-[10px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-pink-100 text-pink-700 inline-flex items-center gap-1">
              <Baby className="w-3 h-3" />
              Pediatric
            </span>
          )}
        </div>
      </div>

      {/* ── Allergies (loudest treatment — safety critical) ───────── */}
      <div className={`rounded-lg border p-2.5 mb-2 ${allergyClasses}`}>
        <div className="flex items-start gap-2">
          <ShieldAlert className={`w-4 h-4 flex-shrink-0 mt-0.5 ${
            allergiesEmpty ? subtleTextCls : 'text-red-600'
          }`} />
          <div className="flex-1 min-w-0">
            <div className="flex items-center justify-between gap-2">
              <div className={`text-[11px] font-bold uppercase tracking-wider ${
                allergiesEmpty ? subtleTextCls : 'text-red-700'
              }`}>
                Allergies
              </div>
              {editable && (
                <EditableMedicalRow
                  label="Allergies"
                  value={patient.knownAllergies}
                  onSave={async (next) => {
                    const updated = await patientApi.updateAllergies(patient.id, next);
                    setPatient(updated);
                  }}
                  isDark={isDark}
                  subtleTextCls={subtleTextCls}
                  headerTextCls={headerTextCls}
                  emptyText="e.g. Penicillin (rash), latex"
                  accentColorClass={isDark ? 'text-red-300 bg-red-500/10' : 'text-red-700 bg-red-100'}
                />
              )}
            </div>
            <div className={`text-sm ${
              allergiesEmpty ? subtleTextCls : 'text-red-900 font-semibold'
            } break-words`}>
              {allergiesEmpty
                ? 'None on record'
                : patient.knownAllergies}
            </div>
            {!allergiesEmpty && (
              <p className={`mt-1 text-[10px] ${subtleTextCls} italic`}>
                Legacy free-text — captured before structured allergies were introduced.
                Record a structured entry below so the safety engine can grade severity.
              </p>
            )}

            {/* Structured allergies panel (V58 / Workflow 2). Replaces
                the legacy free-text textarea above for the safety check
                — the engine reads structured rows first, falls back to
                the free-text only when none exist. */}
            <div className="mt-2 pt-2 border-t border-red-200/60">
              <PatientAllergiesPanel patientId={patient.id} editable={editable} />
            </div>
          </div>
        </div>
      </div>

      {/* ── Chronic conditions (amber treatment — clinically relevant) */}
      <div className={`rounded-lg border p-2.5 mb-2 ${conditionsClasses}`}>
        <div className="flex items-start gap-2">
          <Heart className={`w-4 h-4 flex-shrink-0 mt-0.5 ${
            conditionsEmpty ? subtleTextCls : 'text-amber-600'
          }`} />
          <div className="flex-1 min-w-0">
            <div className="flex items-center justify-between gap-2">
              <div className={`text-[11px] font-bold uppercase tracking-wider ${
                conditionsEmpty ? subtleTextCls : 'text-amber-700'
              }`}>
                Chronic conditions
              </div>
              {editable && (
                <EditableMedicalRow
                  label="Chronic conditions"
                  value={patient.chronicConditions}
                  onSave={async (next) => {
                    const updated = await patientApi.updateChronicConditions(patient.id, next);
                    setPatient(updated);
                  }}
                  isDark={isDark}
                  subtleTextCls={subtleTextCls}
                  headerTextCls={headerTextCls}
                  emptyText="e.g. Hypertension, type 2 diabetes"
                  accentColorClass={isDark ? 'text-amber-300 bg-amber-500/10' : 'text-amber-700 bg-amber-100'}
                />
              )}
            </div>
            <div className={`text-sm ${
              conditionsEmpty ? subtleTextCls : 'text-amber-900 font-semibold'
            } break-words`}>
              {conditionsEmpty
                ? 'None on record'
                : patient.chronicConditions}
            </div>
          </div>
        </div>
      </div>

      {/* ── Blood type ────────────────────────────────────────────── */}
      <div className={`rounded-lg border p-2.5 mb-2 ${
        isDark ? 'bg-white/[0.04] border-white/10' : 'bg-gray-50 border-gray-200'
      }`}>
        <div className="flex items-center gap-2">
          <Droplet className={`w-4 h-4 ${bloodTypeEmpty ? subtleTextCls : 'text-red-600'}`} />
          <div className={`text-[11px] font-bold uppercase tracking-wider ${subtleTextCls}`}>
            Blood type
          </div>
          <div className={`text-sm font-bold ${
            bloodTypeEmpty ? subtleTextCls : headerTextCls
          }`}>
            {bloodTypeEmpty ? 'Unknown' : patient.bloodType}
          </div>
        </div>
      </div>

      {/* ── Guardian (only when pediatric or guardian fields populated) */}
      {(patient.isPediatric || patient.guardianName || patient.guardianPhone) && (
        <div className={`rounded-lg border p-2.5 ${
          isDark ? 'bg-purple-500/10 border-purple-500/30' : 'bg-purple-50 border-purple-200'
        }`}>
          <div className="flex items-start gap-2">
            <Baby className={`w-4 h-4 flex-shrink-0 mt-0.5 ${
              isDark ? 'text-purple-300' : 'text-purple-600'
            }`} />
            <div className="flex-1 min-w-0">
              <div className={`text-[11px] font-bold uppercase tracking-wider ${
                isDark ? 'text-purple-300' : 'text-purple-700'
              }`}>
                Guardian
              </div>
              {patient.guardianName ? (
                <>
                  <div className={`text-sm font-semibold ${headerTextCls}`}>
                    {patient.guardianName}
                    {patient.guardianRelationship && (
                      <span className={`text-xs font-normal ${subtleTextCls}`}>
                        {' '}({patient.guardianRelationship})
                      </span>
                    )}
                  </div>
                  {patient.guardianPhone && (
                    <div className={`text-xs flex items-center gap-1 mt-0.5 ${subtleTextCls}`}>
                      <Phone className="w-3 h-3" />
                      {patient.guardianPhone}
                    </div>
                  )}
                </>
              ) : (
                <div className={`text-sm ${subtleTextCls}`}>
                  {patient.isPediatric
                    ? 'No guardian on file — required for pediatric patients'
                    : 'No guardian on file'}
                </div>
              )}
            </div>
          </div>
        </div>
      )}

      {/* ── Emergency contact (folded in compactly when set) ─────── */}
      {patient.emergencyContactName && (
        <div className={`text-[11px] mt-2 ${subtleTextCls}`}>
          <span className="font-semibold">Emergency contact:</span>{' '}
          {patient.emergencyContactName}
          {patient.emergencyContactPhone && <> · {patient.emergencyContactPhone}</>}
        </div>
      )}
    </div>
  );
}

export default PatientProfilePanel;
