/* ── EditPatientModal ────────────────────────────────────────────────
 *
 * Registrar demographic correction. Fixes data-entry errors on an existing
 * patient (name / DOB / gender / national ID / passport / birth-cert / phone /
 * address / emergency contact / blood type). Persists via PUT /patients/{id}
 * (patientStore.updatePatientApi) and merges the authoritative response back
 * into the store so the chart reflects the correction immediately.
 *
 * RFID card reassignment is a SEPARATE action (ReplaceCardModal) — the card is
 * a shared-identity anchor with its own conflict rules.
 */
import { useState } from 'react';
import { AlertTriangle, Check, Loader2, Pencil, X } from 'lucide-react';
import { usePatientStore } from '@/store/patientStore';
import { ApiError } from '@/api/client';
import type { Gender, UpdatePatientRequest } from '@/api/types';
import type { Patient } from '@/types';
import { useTheme } from '@/hooks/useTheme';

interface Props {
  patient: Patient & Record<string, any>;
  /** The real patient UUID (the list row's id is the visit id). */
  realPatientId: string;
  onClose: () => void;
}

/** Module-scope so it isn't redefined each render (which would remount inputs and drop focus). */
function Field({ label, mutedCls, children }: { label: string; mutedCls: string; children: React.ReactNode }) {
  return (
    <div>
      <label className={`block text-[11px] font-semibold mb-1 ${mutedCls}`}>{label}</label>
      {children}
    </div>
  );
}

/** Store Patient carries dateOfBirth as Date|string; normalise to yyyy-MM-dd for the date input. */
function toDateInput(d: unknown): string {
  if (!d) return '';
  const date = d instanceof Date ? d : new Date(String(d));
  if (Number.isNaN(date.getTime())) return '';
  return date.toISOString().split('T')[0];
}

export function EditPatientModal({ patient, realPatientId, onClose }: Props) {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const updatePatientApi = usePatientStore((s) => s.updatePatientApi);

  const [firstName, setFirstName] = useState(patient.firstName || (patient.fullName || '').split(' ')[0] || '');
  const [lastName, setLastName] = useState(patient.lastName || (patient.fullName || '').split(' ').slice(1).join(' ') || '');
  const [dateOfBirth, setDateOfBirth] = useState(toDateInput(patient.dateOfBirth));
  const [gender, setGender] = useState<Gender | ''>((patient.gender as Gender) || '');
  const [nationalId, setNationalId] = useState(patient.nationalId || '');
  const [phoneNumber, setPhoneNumber] = useState(patient.phoneNumber || patient.phone || '');
  const [address, setAddress] = useState(patient.address || '');
  const [emergencyContactName, setEmergencyContactName] = useState(patient.emergencyContactName || '');
  const [emergencyContactPhone, setEmergencyContactPhone] = useState(patient.emergencyContactPhone || '');
  const [bloodType, setBloodType] = useState(patient.bloodType || '');

  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const valid = firstName.trim().length >= 1 && lastName.trim().length >= 1 && !saving;

  const save = async () => {
    if (!valid) return;
    setSaving(true);
    setError(null);
    const data: UpdatePatientRequest = {
      firstName: firstName.trim(),
      lastName: lastName.trim(),
      dateOfBirth: dateOfBirth || undefined,
      gender: gender || undefined,
      nationalId: nationalId.trim(),
      phoneNumber: phoneNumber.trim(),
      address: address.trim(),
      emergencyContactName: emergencyContactName.trim(),
      emergencyContactPhone: emergencyContactPhone.trim(),
      bloodType: bloodType || '',
    };
    try {
      await updatePatientApi(patient.id, realPatientId, data);
      onClose();
    } catch (e) {
      // The backend returns 409 when a corrected national ID collides with another patient here.
      setError(e instanceof ApiError
        ? (e.status === 409 ? 'That national ID already belongs to another patient at this hospital.' : e.message)
        : 'Could not save the correction.');
    } finally {
      setSaving(false);
    }
  };

  const inputCls = `w-full px-3 py-2 rounded-xl text-sm outline-none focus:ring-2 focus:ring-cyan-500/40 ${text.body}`;

  return (
    <div
      className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm"
      style={{ background: 'var(--modal-backdrop)' }}
      onClick={saving ? undefined : onClose}
    >
      <div style={glassCard} className="w-full max-w-lg rounded-2xl shadow-2xl overflow-hidden animate-scale-in"
        onClick={(e) => e.stopPropagation()}>
        <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-3 text-white flex items-center justify-between">
          <div className="flex items-center gap-2">
            <div className="w-8 h-8 rounded-lg bg-cyan-500/20 flex items-center justify-center">
              <Pencil className="w-4 h-4 text-cyan-300" />
            </div>
            <div>
              <p className="text-sm font-bold">Edit patient details</p>
              <p className="text-[11px] text-white/50">Correct a data-entry error. Card reassignment is a separate action.</p>
            </div>
          </div>
          <button onClick={saving ? undefined : onClose} aria-label="Close" className="text-white/70 hover:text-white">
            <X className="w-4 h-4" />
          </button>
        </div>

        <div className="p-5 space-y-3 max-h-[70vh] overflow-y-auto">
          <div className="grid grid-cols-2 gap-3">
            <Field label="First name *" mutedCls={text.muted}>
              <input value={firstName} onChange={(e) => setFirstName(e.target.value)} maxLength={100} style={glassInner} className={inputCls} />
            </Field>
            <Field label="Last name *" mutedCls={text.muted}>
              <input value={lastName} onChange={(e) => setLastName(e.target.value)} maxLength={100} style={glassInner} className={inputCls} />
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Date of birth" mutedCls={text.muted}>
              <input type="date" value={dateOfBirth} onChange={(e) => setDateOfBirth(e.target.value)} style={glassInner} className={inputCls} />
            </Field>
            <Field label="Gender" mutedCls={text.muted}>
              <select value={gender} onChange={(e) => setGender(e.target.value as Gender | '')} style={glassInner} className={inputCls}>
                <option value="">—</option>
                <option value="MALE">Male</option>
                <option value="FEMALE">Female</option>
              </select>
            </Field>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <Field label="National ID" mutedCls={text.muted}>
              <input value={nationalId} onChange={(e) => setNationalId(e.target.value)} maxLength={30} style={glassInner} className={`${inputCls} font-mono`} />
            </Field>
            <Field label="Phone" mutedCls={text.muted}>
              <input value={phoneNumber} onChange={(e) => setPhoneNumber(e.target.value)} maxLength={20} style={glassInner} className={inputCls} />
            </Field>
          </div>
          <Field label="Address" mutedCls={text.muted}>
            <input value={address} onChange={(e) => setAddress(e.target.value)} style={glassInner} className={inputCls} />
          </Field>
          <div className="grid grid-cols-2 gap-3">
            <Field label="Emergency contact name" mutedCls={text.muted}>
              <input value={emergencyContactName} onChange={(e) => setEmergencyContactName(e.target.value)} maxLength={200} style={glassInner} className={inputCls} />
            </Field>
            <Field label="Emergency contact phone" mutedCls={text.muted}>
              <input value={emergencyContactPhone} onChange={(e) => setEmergencyContactPhone(e.target.value)} maxLength={20} style={glassInner} className={inputCls} />
            </Field>
          </div>
          <Field label="Blood type" mutedCls={text.muted}>
            <select value={bloodType} onChange={(e) => setBloodType(e.target.value)} style={glassInner} className={inputCls}>
              <option value="">—</option>
              {['O+', 'O-', 'A+', 'A-', 'B+', 'B-', 'AB+', 'AB-'].map((bt) => <option key={bt} value={bt}>{bt}</option>)}
            </select>
          </Field>

          {error && (
            <div className="rounded-lg p-3 flex items-start gap-2 bg-rose-500/20 border border-rose-500/30">
              <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0 text-rose-400" />
              <p className="text-xs font-semibold text-rose-300">{error}</p>
            </div>
          )}
        </div>

        <div className="flex items-center justify-end gap-2 px-5 py-3" style={{ borderTop: borderStyle }}>
          <button onClick={saving ? undefined : onClose} disabled={saving}
            className={`px-3 py-1.5 rounded-xl text-xs font-bold hover:bg-white/5 disabled:opacity-50 ${text.body}`}>
            Cancel
          </button>
          <button onClick={save} disabled={!valid}
            className={`inline-flex items-center gap-1.5 px-4 py-1.5 rounded-xl text-xs font-bold text-white ${valid ? 'bg-cyan-600 hover:bg-cyan-700' : 'bg-slate-400 cursor-not-allowed'}`}>
            {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Check className="w-3.5 h-3.5" />}
            {saving ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </div>
    </div>
  );
}
