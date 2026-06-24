import { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import {
  ArrowLeft, User, Calendar, MapPin, Phone, CreditCard,
  Stethoscope, FileText, Baby, Clock, Siren, Building2,
  Users, Shield, Footprints,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { usePatientStore } from '@/store/patientStore';
import { useAuthStore } from '@/store/authStore';
import { patientApi } from '@/api/patients';
import { ReplaceCardModal } from './ReplaceCardModal';
import type { Patient } from '@/types';
import type { UserRole } from '@/types/roles';

const CARD_ADMIN_ROLES: UserRole[] = ['SUPER_ADMIN', 'HOSPITAL_ADMIN', 'REGISTRAR'];

/* ─── Reusable info row ─── */
const InfoRow = ({ label, value }: { label: string; value?: string | number | null }) => (
  <div className="flex items-start justify-between py-2.5 border-b border-gray-100 last:border-0">
    <span className="text-sm text-gray-500 font-medium">{label}</span>
    <span className="text-sm font-semibold text-gray-900 text-right max-w-[60%]">
      {value || <span className="text-gray-300 font-normal italic">—</span>}
    </span>
  </div>
);

/* ─── Section card ─── */
const SectionCard = ({ title, icon: Icon, children, accent = 'cyan' }: {
  title: string;
  icon: typeof User;
  children: React.ReactNode;
  accent?: string;
}) => {
  const accentMap: Record<string, { iconBg: string; iconText: string; border: string }> = {
    cyan: { iconBg: 'bg-cyan-50', iconText: 'text-cyan-600', border: 'border-cyan-200' },
    violet: { iconBg: 'bg-violet-50', iconText: 'text-violet-600', border: 'border-violet-200' },
    amber: { iconBg: 'bg-amber-50', iconText: 'text-amber-600', border: 'border-amber-200' },
    emerald: { iconBg: 'bg-emerald-50', iconText: 'text-emerald-600', border: 'border-emerald-200' },
    rose: { iconBg: 'bg-rose-50', iconText: 'text-rose-600', border: 'border-rose-200' },
    blue: { iconBg: 'bg-blue-50', iconText: 'text-blue-600', border: 'border-blue-200' },
  };
  const a = accentMap[accent] || accentMap.cyan;

  return (
    <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden hover:shadow-md transition-shadow duration-300">
      <div className={`flex items-center gap-3 px-5 py-3.5 border-b ${a.border} bg-gradient-to-r from-gray-50 to-white`}>
        <div className={`w-8 h-8 rounded-lg ${a.iconBg} flex items-center justify-center`}>
          <Icon className={`w-4 h-4 ${a.iconText}`} />
        </div>
        <h3 className="text-sm font-bold text-gray-900 tracking-tight">{title}</h3>
      </div>
      <div className="px-5 py-3">{children}</div>
    </div>
  );
};

export function PatientDetailView() {
  const { patientId } = useParams<{ patientId: string }>();
  const navigate = useNavigate();
  const { glassCard } = useTheme();

  const patient: (Patient & Record<string, any>) | undefined =
    usePatientStore((s) => s.getPatient(patientId || ''));
  const updatePatient = usePatientStore((s) => s.updatePatient);
  const role = useAuthStore((s) => s.user?.role);
  const [showReplaceCard, setShowReplaceCard] = useState(false);
  // The replace endpoint is keyed on the real patient id; the visit projection carries it on
  // `patientId`, otherwise the route param IS the patient id.
  const realPatientId = (patient as any)?.patientId || patientId || '';
  const canManageCard = role != null && CARD_ADMIN_ROLES.includes(role);

  // Lazy hydrate the full patient record. The bulk list-view fetch
  // (fetchActiveVisits) skips per-patient detail to avoid the N+1
  // dashboard slowdown — it only carries visit-projection fields like
  // name, complaint, category. When the user opens THIS page, fetch
  // the full patient row and merge into the store. Best-effort:
  // failure leaves the placeholder fields in place.
  useEffect(() => {
    if (!patient) return;
    // The `patient.id` is the visit id (used as the list key); the
    // real patient row is at `patient.patientId` when the visit
    // projection carried it. The list mapper doesn't currently put
    // patientId into the Patient shape, so fall back to fetching by
    // a separate lookup if needed. For now we lazily fetch by visitId
    // → patient via a derived endpoint, or skip if details already
    // populated (presence of nationalId/phone/dateOfBirth suggests a
    // prior detail fetch).
    const alreadyHydrated = patient.nationalId || patient.phoneNumber || patient.dateOfBirth;
    if (alreadyHydrated) return;
    const targetPatientId = (patient as any).patientId;
    if (!targetPatientId) return;
    let cancelled = false;
    patientApi.getById(targetPatientId)
      .then((p) => {
        if (cancelled) return;
        updatePatient(patient.id, {
          age: p.ageInYears ?? patient.age,
          gender: p.gender as Patient['gender'],
          nationalId: p.nationalId || undefined,
          phone: p.phoneNumber || undefined,
          phoneNumber: p.phoneNumber || undefined,
          address: p.address || undefined,
          emergencyContactName: p.emergencyContactName || undefined,
          emergencyContactPhone: p.emergencyContactPhone || undefined,
          bloodType: p.bloodType || undefined,
          knownAllergies: p.knownAllergies || undefined,
          chronicConditions: p.chronicConditions || undefined,
          medicalRecordNumber: p.medicalRecordNumber || undefined,
          dateOfBirth: p.dateOfBirth || undefined,
          rfidCardId: p.rfidCardId || undefined,
        } as Partial<Patient> & { rfidCardId?: string });
      })
      .catch(() => { /* best-effort; placeholder fields persist */ });
    return () => { cancelled = true; };
  }, [patient, updatePatient]);

  if (!patient || !patientId) {
    return (
      <div className="min-h-screen bg-gray-50 flex items-center justify-center">
        <div className="text-center bg-white rounded-2xl p-8 shadow-lg border border-gray-200">
          <User className="w-16 h-16 text-gray-400 mx-auto mb-4" />
          <h2 className="text-xl font-bold text-gray-900 mb-2">Patient Not Found</h2>
          <p className="text-gray-600 mb-6">The requested patient could not be located.</p>
          <button
            onClick={() => navigate(-1)}
            className="inline-flex items-center gap-2 px-6 py-3 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl hover:from-slate-700 hover:to-slate-600 transition-all duration-300 font-medium hover:shadow-lg hover:-translate-y-0.5"
          >
            <ArrowLeft className="w-4 h-4" />
            Go Back
          </button>
        </div>
      </div>
    );
  }

  /* ─── Helpers ─── */
  const formatDate = (d?: Date | string) => {
    if (!d) return undefined;
    const date = d instanceof Date ? d : new Date(d);
    return date.toLocaleDateString('en-GB', { day: '2-digit', month: 'short', year: 'numeric' }) +
      ' ' + date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
  };

  const arrivalModeLabel: Record<string, string> = {
    WALK_IN: 'Walk-in',
    AMBULANCE: 'Ambulance',
    REFERRAL: 'Referral',
  };

  const mobilityLabel: Record<string, string> = {
    AMBULATORY: 'Ambulatory',
    WHEELCHAIR: 'Wheelchair',
    STRETCHER: 'Stretcher',
  };

  const statusLabel: Record<string, string> = {
    WAITING: 'Waiting',
    IN_TRIAGE: 'In Triage',
    TRIAGED: 'Triaged',
    IN_TREATMENT: 'In Treatment',
  };

  const categoryColors: Record<string, { bg: string; text: string }> = {
    RED: { bg: 'bg-red-500', text: 'text-white' },
    ORANGE: { bg: 'bg-orange-500', text: 'text-white' },
    YELLOW: { bg: 'bg-yellow-400', text: 'text-yellow-900' },
    GREEN: { bg: 'bg-green-500', text: 'text-white' },
    BLUE: { bg: 'bg-blue-500', text: 'text-white' },
  };

  const catCfg = patient.category ? categoryColors[patient.category] : null;

  return (
    <div className="min-h-full">
      <div className="p-5 space-y-5">

        {/* ── Header ── */}
        <div className="bg-white rounded-2xl shadow-sm border border-gray-200 overflow-hidden">
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-4">
            <div className="flex items-center justify-between">
              <button
                onClick={() => navigate(-1)}
                className="inline-flex items-center gap-2 text-white/80 hover:text-white transition-all duration-300 group"
              >
                <ArrowLeft className="w-4 h-4 group-hover:-translate-x-1 transition-transform duration-300" />
                <span className="text-sm font-medium">Back</span>
              </button>
              <span className="text-xs text-white/60 font-mono">Patient Registration Record</span>
            </div>
          </div>

          <div className="p-6">
            <div className="flex flex-col sm:flex-row sm:items-center gap-5">
              {/* Avatar */}
              <div className="relative shrink-0">
                <div className="w-20 h-20 rounded-2xl bg-gradient-to-br from-slate-800 to-cyan-600 flex items-center justify-center shadow-lg">
                  {patient.isPediatric ? (
                    <Baby className="w-10 h-10 text-white" />
                  ) : (
                    <User className="w-10 h-10 text-white" />
                  )}
                </div>
                {patient.isPediatric && (
                  <span className="absolute -top-1.5 -right-1.5 px-1.5 py-0.5 rounded-md bg-violet-500 text-white text-[10px] font-bold shadow">
                    PEDS
                  </span>
                )}
              </div>

              {/* Name & quick info */}
              <div className="flex-1 min-w-0">
                <h1 className="text-xl font-bold text-gray-900 tracking-tight truncate">{patient.fullName}</h1>
                <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-gray-500 text-sm mt-1">
                  <span className="flex items-center gap-1">
                    <Calendar className="w-3.5 h-3.5" />
                    {patient.age} yrs &middot; {patient.gender === 'MALE' ? 'Male' : patient.gender === 'FEMALE' ? 'Female' : '—'}
                  </span>
                  {patient.nationalId && (
                    <span className="flex items-center gap-1">
                      <CreditCard className="w-3.5 h-3.5" />
                      {patient.nationalId}
                    </span>
                  )}
                  {patient.medicalRecordNumber && (
                    <span className="font-mono text-xs text-gray-400">MRN: {patient.medicalRecordNumber}</span>
                  )}
                </div>
              </div>

              {/* Badges */}
              <div className="flex flex-wrap gap-2 shrink-0">
                {catCfg && (
                  <span className={`px-3 py-1 rounded-lg text-xs font-bold ${catCfg.bg} ${catCfg.text}`}>
                    {patient.category}
                  </span>
                )}
                {patient.tewsScore != null && (
                  <span className="px-3 py-1 rounded-lg text-xs font-bold bg-blue-500 text-white">
                    TEWS {patient.tewsScore}
                  </span>
                )}
                <span className="px-3 py-1 rounded-lg text-xs font-semibold bg-gray-100 text-gray-600">
                  {statusLabel[patient.triageStatus] || patient.triageStatus}
                </span>
              </div>
            </div>
          </div>
        </div>

        {/* ── Registration Details Grid ── */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-5">

          {/* Personal Information */}
          <SectionCard title="Personal Information" icon={User} accent="cyan">
            <InfoRow label="Full Name" value={patient.fullName} />
            <InfoRow label="Date of Birth" value={patient.dateOfBirth ? formatDate(patient.dateOfBirth) : undefined} />
            <InfoRow label="Age" value={`${patient.age} years`} />
            <InfoRow label="Gender" value={patient.gender === 'MALE' ? 'Male' : patient.gender === 'FEMALE' ? 'Female' : '—'} />
            <InfoRow label="National ID" value={patient.nationalId} />
            {/* RFID card (V95) — system-wide identifier on the shared identity. Registration-desk
                roles can replace it (lost/damaged-card workflow). */}
            {(patient.rfidCardId || canManageCard) && (
              <div className="flex items-center justify-between py-2.5 border-b border-gray-100 last:border-0">
                <span className="text-xs font-medium text-slate-400">RFID Card</span>
                <div className="flex items-center gap-2">
                  <span className="text-sm font-semibold text-slate-700">{patient.rfidCardId || '—'}</span>
                  {canManageCard && realPatientId && (
                    <button
                      onClick={() => setShowReplaceCard(true)}
                      className="text-[11px] font-bold text-teal-700 hover:text-teal-900 px-2 py-0.5 rounded-md hover:bg-teal-50 transition-colors"
                    >
                      {patient.rfidCardId ? 'Replace' : 'Assign'}
                    </button>
                  )}
                </div>
              </div>
            )}
            <InfoRow label="Medical Record No." value={patient.medicalRecordNumber} />
            <InfoRow label="Patient Type" value={patient.isPediatric || (typeof patient.age === 'number' && patient.age < 18) ? 'Pediatric' : 'Adult'} />
            <InfoRow label="Blood Type" value={patient.bloodType} />
            {patient.weight && <InfoRow label="Weight" value={`${patient.weight} kg`} />}
          </SectionCard>

          {/* Address */}
          <SectionCard title="Address" icon={MapPin} accent="violet">
            <InfoRow label="Address" value={patient.address} />
          </SectionCard>

          {/* Contact Information */}
          <SectionCard title="Contact Information" icon={Phone} accent="emerald">
            <InfoRow label="Phone" value={patient.phone || patient.phoneNumber} />
            <InfoRow label="Emergency Contact" value={patient.emergencyContactName} />
            <InfoRow label="Emergency Phone" value={patient.emergencyContactPhone} />
            {patient.contactPerson && (
              <>
                <InfoRow label="Contact Person" value={patient.contactPerson.name} />
                <InfoRow label="Contact Phone" value={patient.contactPerson.phone} />
                <InfoRow label="Relationship" value={patient.contactPerson.relationship} />
              </>
            )}
          </SectionCard>

          {/* Guardian (pediatric) */}
          {patient.isPediatric && (
            <SectionCard title="Guardian Information" icon={Shield} accent="rose">
              {patient.guardian ? (
                <>
                  <InfoRow label="Guardian Name" value={patient.guardian.name} />
                  <InfoRow label="Guardian Phone" value={patient.guardian.phone} />
                  <InfoRow label="Relationship" value={patient.guardian.relationship} />
                  <InfoRow label="Guardian NID" value={patient.guardian.nationalId} />
                </>
              ) : (
                <>
                  <InfoRow label="Guardian Name" value={patient.guardianName} />
                  <InfoRow label="Guardian Phone" value={patient.guardianPhone} />
                  <InfoRow label="Relationship" value={patient.guardianRelationship} />
                  <InfoRow label="Guardian NID" value={patient.guardianNationalId} />
                </>
              )}
            </SectionCard>
          )}

          {/* Arrival & Assignment */}
          <SectionCard title="Arrival & Assignment" icon={Siren} accent="amber">
            <InfoRow label="Arrival Mode" value={arrivalModeLabel[patient.arrivalMode] || patient.arrivalMode} />
            <InfoRow label="Mobility" value={patient.mobility ? mobilityLabel[patient.mobility] || patient.mobility : undefined} />
            <InfoRow label="Arrival Time" value={formatDate(patient.arrivalTimestamp)} />
            <InfoRow label="Registered At" value={formatDate(patient.registeredAt || patient.registrationCompletedAt)} />
            {patient.referringFacility && (
              <InfoRow label="Referring Facility" value={patient.referringFacility} />
            )}
            <InfoRow label="Assigned Nurse" value={patient.assignedNurseName} />
          </SectionCard>

          {/* Medical Information */}
          <SectionCard title="Medical Information" icon={Stethoscope} accent="blue">
            <InfoRow label="Chief Complaint" value={patient.chiefComplaint} />
            <InfoRow label="Triage Category" value={patient.category || 'Pending'} />
            <InfoRow label="TEWS Score" value={patient.tewsScore} />
            <InfoRow label="Triage Status" value={statusLabel[patient.triageStatus] || patient.triageStatus} />
            {patient.currentMedications && (
              <InfoRow label="Current Medications" value={patient.currentMedications} />
            )}

            {/* Allergies — prefer tag array if present, fall back to backend string */}
            {patient.allergies && patient.allergies.length > 0 ? (
              <div className="py-2.5 border-b border-gray-100">
                <span className="text-sm text-gray-500 font-medium block mb-1.5">Allergies</span>
                <div className="flex flex-wrap gap-1.5">
                  {patient.allergies.map((a: string) => (
                    <span key={a} className="px-2.5 py-0.5 rounded-full bg-red-50 text-red-600 text-xs font-semibold border border-red-200">
                      {a}
                    </span>
                  ))}
                </div>
              </div>
            ) : (
              <InfoRow label="Known Allergies" value={patient.knownAllergies} />
            )}

            {/* Existing conditions — prefer tag array if present, fall back to backend string */}
            {patient.existingConditions && patient.existingConditions.length > 0 ? (
              <div className="py-2.5">
                <span className="text-sm text-gray-500 font-medium block mb-1.5">Existing Conditions</span>
                <div className="flex flex-wrap gap-1.5">
                  {patient.existingConditions.map((c: string) => (
                    <span key={c} className="px-2.5 py-0.5 rounded-full bg-amber-50 text-amber-700 text-xs font-semibold border border-amber-200">
                      {c}
                    </span>
                  ))}
                </div>
              </div>
            ) : (
              <InfoRow label="Chronic Conditions" value={patient.chronicConditions} />
            )}
          </SectionCard>
        </div>
      </div>

      {showReplaceCard && realPatientId && (
        <ReplaceCardModal
          patientId={realPatientId}
          patientName={patient.fullName}
          currentCardId={(patient as any).rfidCardId}
          onClose={() => setShowReplaceCard(false)}
          onReplaced={(newCard) => {
            updatePatient(patient.id, { rfidCardId: newCard } as Partial<Patient> & { rfidCardId?: string });
            setShowReplaceCard(false);
          }}
        />
      )}
    </div>
  );
}