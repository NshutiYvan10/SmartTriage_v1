import React, { useState, useCallback, useMemo, useEffect } from 'react';
import {
  User,
  MapPin,
  Phone,
  Stethoscope,
  FileText,
  ChevronRight,
  ChevronLeft,
  Check,
  Baby,
  ArrowLeft,
  ClipboardList,
  Shield,
  Heart,
  Siren,
  Footprints,
  Building2,
  AlertTriangle,
  Upload,
  X,
  UserCheck,
  Users,
  Accessibility,
} from 'lucide-react';
import { usePatientStore } from '@/store/patientStore';
import { useAuditStore } from '@/store/auditStore';
import { useAuthStore } from '@/store/authStore';
import { Gender, ArrivalMode, Mobility } from '@/types';
import { useNavigate } from 'react-router-dom';
import { useTheme } from '@/hooks/useTheme';
import { userApi } from '@/api/users';
import { visitApi } from '@/api/visits';
import type { UserResponse, PatientResponse } from '@/api/types';
import { PatientLookupPanel } from './PatientLookupPanel';
import { PatientHistoryPanel } from './PatientHistoryPanel';
import { PatientProfilePanel } from './PatientProfilePanel';
import { RwandaLocationPicker } from '@/components/RwandaLocationPicker';

/* ─── Constants ─── */
const ALLERGIES = ['Penicillin', 'Latex', 'Pollen', 'Food', 'Dairy', 'Other'];
const CONDITIONS = ['Diabetes', 'HIV/AIDS', 'Heart Disease', 'Hypertension', 'Asthma', 'Other'];
// PROVINCES constant removed — provinces now come from the live
// /api/v1/locations/rw/provinces endpoint via RwandaLocationPicker.
const CHIEF_COMPLAINTS = [
  'Chest Pain',
  'Shortness of Breath',
  'Abdominal Pain',
  'Headache',
  'Fever',
  'Trauma/Injury',
  'Bleeding',
  'Altered Mental Status',
  'Other',
];

const RELATIONSHIPS = ['Parent', 'Spouse', 'Sibling', 'Child', 'Guardian', 'Friend', 'Colleague', 'Other'];
const GUARDIAN_RELATIONSHIPS = ['Mother', 'Father', 'Guardian', 'Grandparent', 'Aunt/Uncle', 'Sibling', 'Other'];

/**
 * Roles eligible to be the on-shift triage nurse for a new
 * registration. Triage is performed by NURSE-role users — the
 * triage station is a per-shift assignment
 * (ShiftAssignment.shiftFunction = TRIAGE_NURSE), not a role of
 * its own. Any nurse can be assigned to triage; the assignment
 * itself is enforced by the shift planner / charge nurse.
 */
const TRIAGE_ROLES = ['NURSE'];

const STEPS = [
  { id: 1, label: 'Personal', icon: User },
  { id: 2, label: 'Address', icon: MapPin },
  { id: 3, label: 'Contact', icon: Phone },
  { id: 4, label: 'Medical', icon: Stethoscope },
  { id: 5, label: 'Review', icon: FileText },
];

/* ─── Form State ─── */
interface FormData {
  // Step 1 — Personal
  firstName: string;
  lastName: string;
  age: string;
  gender: Gender | '';
  nationalId: string;
  weight: string;
  dateOfBirth: string;
  // Step 2 — Address
  // streetAddress is the building / landmark (e.g. "near KK 15 Avenue
  // church, plot 27") — Rwandan addresses are described relationally,
  // not by numbered streets, so this field is now optional. Province
  // → village granularity comes from the structured FK chain below.
  streetAddress: string;
  zipcode: string;
  province: string;
  district: string;
  sector: string;
  cell: string;
  village: string;
  // V46+ structured IDs alongside the legacy free-text fields above.
  // The picker fills these; the free-text fields are kept populated
  // (with the human-readable name) so legacy address concatenation
  // continues to work without a separate name lookup.
  provinceId?: string;
  districtId?: string;
  sectorId?: string;
  cellId?: string;
  villageId?: string;
  // Step 3 — Contact, Arrival, Guardian, Nurse
  phoneNumber: string;
  emergencyContactName: string;
  emergencyContactPhone: string;
  // Contact Person
  contactPersonName: string;
  contactPersonPhone: string;
  contactPersonRelationship: string;
  // Guardian (pediatric)
  guardianName: string;
  guardianPhone: string;
  guardianRelationship: string;
  guardianNationalId: string;
  // Arrival
  arrivalMode: ArrivalMode | '';
  mobility: Mobility | '';
  referringFacility: string;
  referralDocumentFile: File | null;
  // Nurse Assignment
  assignedNurseId: string;
  // Step 4 — Medical History
  bloodType: string;
  allergies: string[];
  existingConditions: string[];
  currentMedications: string;
  chiefComplaints: string[];
  chiefComplaintOther: string;
}

/** ABO/Rh blood-type values supported on the backend; "" = unknown. */
const BLOOD_TYPES = ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-'] as const;

const INITIAL_FORM: FormData = {
  firstName: '',
  lastName: '',
  age: '',
  gender: '',
  nationalId: '',
  weight: '',
  dateOfBirth: '',
  streetAddress: '',
  zipcode: '',
  province: '',
  district: '',
  sector: '',
  cell: '',
  village: '',
  phoneNumber: '',
  emergencyContactName: '',
  emergencyContactPhone: '',
  contactPersonName: '',
  contactPersonPhone: '',
  contactPersonRelationship: '',
  guardianName: '',
  guardianPhone: '',
  guardianRelationship: '',
  guardianNationalId: '',
  arrivalMode: '',
  mobility: '',
  referringFacility: '',
  referralDocumentFile: null,
  assignedNurseId: '',
  bloodType: '',
  allergies: [],
  existingConditions: [],
  currentMedications: '',
  chiefComplaints: [],
  chiefComplaintOther: '',
};

/* ─── Component ─── */
export function EntryRegistration() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const registerPatientApi = usePatientStore((state) => state.registerPatientApi);
  const findByNationalId = usePatientStore((state) => state.findByNationalId);
  const addAuditEntry = useAuditStore((state) => state.addEntry);
  const authUser = useAuthStore((s) => s.user);
  const navigate = useNavigate();

  const [step, setStep] = useState(1);
  const [formData, setFormData] = useState<FormData>(INITIAL_FORM);
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [showSuccess, setShowSuccess] = useState(false);
  // Re-entrancy guard for the Confirm button — prevents duplicate
  // patient/visit creation from rapid multi-tapping while the
  // registration API call is in flight.
  const [isSubmitting, setIsSubmitting] = useState(false);

  /**
   * Federated-lookup mode flag.
   *  - 'lookup'   → render PatientLookupPanel above Step 1, hide form fields
   *  - 'register' → render the standard registration form
   * The nurse either picks a candidate (auto-switches to 'register' with
   * fields pre-filled) or clicks "Skip — register new" to bypass lookup.
   */
  const [mode, setMode] = useState<'lookup' | 'register'>('lookup');
  /**
   * If non-null, this registration is for an existing patient that we found
   * via lookup. Used purely to surface a banner today; future work can route
   * to a "create new visit only" endpoint instead of full registration.
   */
  const [existingPatientId, setExistingPatientId] = useState<string | null>(null);
  /**
   * The full PatientResponse captured when the nurse picked a candidate
   * via lookup. Stored so we can pass it directly to PatientProfilePanel
   * without forcing a re-fetch.
   */
  const [pickedPatient, setPickedPatient] = useState<PatientResponse | null>(null);

  /* ── Load real nurses from backend ── */
  const [nurses, setNurses] = useState<{ id: string; name: string }[]>([]);
  useEffect(() => {
    const hospitalId = authUser?.hospitalId || 'a0000000-0000-0000-0000-000000000001';
    userApi.getByHospital(hospitalId, 0, 100)
      .then((res) => {
        const list = (res.content || [])
          .filter((u: UserResponse) => TRIAGE_ROLES.includes(u.role))
          .map((u: UserResponse) => ({ id: u.id, name: `${u.firstName} ${u.lastName}` }));
        // Also include the current user if they're a nurse-type role and not already listed
        if (authUser && TRIAGE_ROLES.includes(authUser.role as any) && !list.find((n: {id: string}) => n.id === authUser.id)) {
          list.unshift({ id: authUser.id, name: authUser.fullName });
        }
        // If no nurses found from API, add current user as fallback
        if (list.length === 0 && authUser) {
          list.push({ id: authUser.id, name: authUser.fullName });
        }
        setNurses(list);
      })
      .catch(() => {
        // Fallback to current user
        if (authUser) setNurses([{ id: authUser.id, name: authUser.fullName }]);
      });
  }, [authUser]);

  // Rwanda mSAT triage form boundary: Adult Triage Form "Over 12
  // years"; Child Triage Form "3–12 years". A 12-year-old is on the
  // child form, a 13-year-old is on the adult form. KFH's peds
  // triage form follows the same boundary. The backend's
  // Patient.isPediatric() uses the same <13 rule, so this keeps
  // the registration UI in sync with what the system actually
  // stores when the patient gets persisted.
  const isPediatric = formData.age ? parseInt(formData.age) < 13 : false;
  const isReferral = formData.arrivalMode === 'REFERRAL';

  /* \u2500\u2500 Duplicate NID detection \u2500\u2500 */
  const duplicatePatient = useMemo(() => {
    if (!formData.nationalId || formData.nationalId.length < 3) return null;
    return findByNationalId(formData.nationalId);
  }, [formData.nationalId, findByNationalId]);

  /* \u2500\u2500 Guardian missing warning \u2500\u2500 */
  const guardianMissing = isPediatric && !formData.guardianName.trim();

  /* \u2500\u2500 Selected nurse name \u2500\u2500 */
  const selectedNurseName = useMemo(() => {
    const nurse = nurses.find((n) => n.id === formData.assignedNurseId);
    return nurse?.name || '';
  }, [formData.assignedNurseId, nurses]);

  /* ── Helpers ── */
  const set = (field: keyof FormData, value: string) =>
    setFormData((prev) => ({ ...prev, [field]: value }));

  const handleDateOfBirth = (dob: string) => {
    set('dateOfBirth', dob);
    if (dob) {
      const birth = new Date(dob);
      const today = new Date();
      let age = today.getFullYear() - birth.getFullYear();
      const monthDiff = today.getMonth() - birth.getMonth();
      if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) {
        age--;
      }
      if (age >= 0 && age <= 120) {
        set('age', String(age));
      }
    }
  };

  const handleFileUpload = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0] || null;
    setFormData((prev) => ({ ...prev, referralDocumentFile: file }));
  }, []);

  const removeFile = useCallback(() => {
    setFormData((prev) => ({ ...prev, referralDocumentFile: null }));
  }, []);

  const toggleArray = (field: 'allergies' | 'existingConditions' | 'chiefComplaints', value: string) => {
    setFormData((prev) => {
      const arr = prev[field] as string[];
      return {
        ...prev,
        [field]: arr.includes(value) ? arr.filter((v) => v !== value) : [...arr, value],
      };
    });
  };

  /**
   * Pre-fill the form with an existing patient's identity + contact +
   * medical-history fields. Address, arrival/mobility, chief-complaint, and
   * nurse assignment remain blank — those are visit-specific and the nurse
   * collects them fresh each time.
   */
  const applyLookupCandidate = (patient: PatientResponse) => {
    // Compute age from DOB if present (mirrors handleDateOfBirth's logic)
    let computedAge = '';
    if (patient.dateOfBirth) {
      const birth = new Date(patient.dateOfBirth);
      const today = new Date();
      let age = today.getFullYear() - birth.getFullYear();
      const monthDiff = today.getMonth() - birth.getMonth();
      if (monthDiff < 0 || (monthDiff === 0 && today.getDate() < birth.getDate())) age--;
      if (age >= 0 && age <= 120) computedAge = String(age);
    } else if (typeof patient.ageInYears === 'number') {
      computedAge = String(patient.ageInYears);
    }

    setFormData((prev) => ({
      ...prev,
      firstName: patient.firstName ?? '',
      lastName:  patient.lastName ?? '',
      dateOfBirth: patient.dateOfBirth ?? '',
      age: computedAge,
      gender: (patient.gender as Gender) ?? '',
      nationalId: patient.nationalId ?? '',
      phoneNumber: patient.phoneNumber ?? '',
      // PatientResponse exposes a single concatenated address string —
      // drop it into streetAddress so it's at least visible; the nurse can
      // re-enter the structured province/district/sector fields.
      streetAddress: patient.address ?? '',
      emergencyContactName:  patient.emergencyContactName ?? '',
      emergencyContactPhone: patient.emergencyContactPhone ?? '',
      guardianName:         patient.guardianName ?? '',
      guardianPhone:        patient.guardianPhone ?? '',
      guardianRelationship: patient.guardianRelationship ?? '',
      guardianNationalId:   patient.guardianNationalId ?? '',
      // Blood type round-trips cleanly — the backend stores the same
      // ABO/Rh string the chip selector emits.
      bloodType: patient.bloodType ?? '',
      // Medical-history fields (chronicConditions/knownAllergies are free
      // text on the backend, our form uses string-array checkboxes — we
      // can't safely round-trip them into the checkboxes, so we leave the
      // checkbox arrays empty and the nurse re-confirms).
    }));
    setExistingPatientId(patient.id);
    setPickedPatient(patient);
    setMode('register');
    // Clear any stale validation errors so the freshly-filled form is clean.
    setErrors({});
  };

  /** Reset lookup → return to the lookup panel and clear the form. */
  const resetToLookup = () => {
    setFormData(INITIAL_FORM);
    setErrors({});
    setExistingPatientId(null);
    setPickedPatient(null);
    setMode('lookup');
    setStep(1);
  };

  /* ── Validation per step ── */
  const validateStep = (s: number): boolean => {
    const e: Record<string, string> = {};

    if (s === 1) {
      if (!formData.firstName.trim()) e.firstName = 'First name is required';
      if (!formData.lastName.trim()) e.lastName = 'Last name is required';
      if (!formData.age) e.age = 'Age is required';
      else if (+formData.age < 0 || +formData.age > 120) e.age = 'Invalid age';
      if (!formData.gender) e.gender = 'Gender is required';
      if (isPediatric && !formData.weight) e.weight = 'Weight is mandatory for pediatric patients';
    }

    if (s === 2) {
      // Structured-location validation: require at least province +
      // district. Sector/cell/village are encouraged but optional —
      // a clinician may legitimately know only down to district when
      // registering a stranger or an unconscious patient. The
      // RwandaLocationPicker enforces cascade integrity so we never
      // get a (province, district) pair that doesn't match.
      // streetAddress (building / landmark) is now optional — Rwandan
      // addresses are mostly relational and many patients won't have
      // one to give.
      if (!formData.provinceId) e.province = 'Province is required';
      if (!formData.districtId) e.district = 'District is required';
    }

    if (s === 3) {
      if (!formData.arrivalMode) e.arrivalMode = 'Arrival mode is required';
      if (!formData.mobility) e.mobility = 'Mobility / transport mode is required';
      if (isReferral && !formData.referringFacility.trim())
        e.referringFacility = 'Referring facility is required';
      // Guardian is mandatory for pediatric patients
      if (isPediatric) {
        if (!formData.guardianName.trim()) e.guardianName = 'Guardian name is required for pediatric patients';
        if (!formData.guardianPhone.trim()) e.guardianPhone = 'Guardian phone is required';
        if (!formData.guardianRelationship) e.guardianRelationship = 'Guardian relationship is required';
      }
      // Nurse assignment required
      if (!formData.assignedNurseId) e.assignedNurseId = 'Triage nurse must be assigned before proceeding';
    }

    if (s === 4) {
      if (formData.chiefComplaints.length === 0 && !formData.chiefComplaintOther.trim()) e.chiefComplaints = 'At least one reason for visit is required';
    }

    setErrors(e);
    return Object.keys(e).length === 0;
  };

  const goNext = () => {
    if (validateStep(step)) setStep((s) => Math.min(s + 1, 5));
  };

  const goPrev = () => setStep((s) => Math.max(s - 1, 1));

  /* ── Submit ── */
  const handleSubmit = async () => {
    // Re-entrancy guard: a rapid double-tap must not fire two
    // registration calls (which would create duplicate patients/visits).
    if (isSubmitting) return;

    if (!validateStep(4)) {
      setStep(4);
      return;
    }

    // Final gate: nurse must be assigned
    if (!formData.assignedNurseId) {
      setErrors({ assignedNurseId: 'Triage nurse must be assigned before registration' });
      setStep(3);
      return;
    }

    setIsSubmitting(true);

    // ── Persist to backend API (single source of truth) ──
    const hospitalId = authUser?.hospitalId || 'a0000000-0000-0000-0000-000000000001';
    const fullName = `${formData.firstName.trim()} ${formData.lastName.trim()}`;
    const chiefComplaint = [...formData.chiefComplaints, formData.chiefComplaintOther.trim()]
      .filter(Boolean).join('; ') || undefined;

    // Branch:
    //  - existingPatientId set ⇒ nurse picked an existing patient via lookup;
    //    do NOT call /patients/register (that would tripwire the duplicate
    //    detector on NID/passport/birth-cert). Just create a fresh Visit.
    //  - otherwise ⇒ standard atomic patient + visit registration.
    let patientId: string;
    if (existingPatientId) {
      try {
        await visitApi.create({
          patientId: existingPatientId,
          hospitalId,
          arrivalMode: formData.arrivalMode || undefined,
          chiefComplaint,
          referringFacility: formData.referringFacility || undefined,
        });
      } catch (err: any) {
        setErrors({ assignedNurseId: err?.message ?? 'Failed to create visit for this patient.' });
        setIsSubmitting(false);
        return;
      }
      patientId = existingPatientId;
    } else {
      // Convert checkbox arrays → free-text the backend stores. We only
      // emit a value when the nurse selected at least one chip; an empty
      // array becomes undefined so the column stays NULL ("we never
      // asked") rather than "" ("we asked, none reported"). The
      // PatientProfilePanel treats both as "None on record" but the
      // distinction matters for downstream audit.
      const allergiesText = formData.allergies.length > 0 ? formData.allergies.join(', ') : undefined;
      const conditionsText = formData.existingConditions.length > 0 ? formData.existingConditions.join(', ') : undefined;

      // DOB resolution: if the nurse picked an exact DOB, use it; otherwise
      // derive a synthetic DOB from the entered age so the backend can still
      // compute Patient.isPediatric correctly. Without this, an age-only
      // registration sends no DOB and the patient defaults to adult routing,
      // which is right MOST of the time but wrong for paediatric patients
      // whose age was entered numerically (the common Rwandan field path
      // when DOB is unknown). The synthetic DOB anchors at today minus the
      // entered years; rough but clinically-accurate-enough — the doctor
      // can correct it later from the patient record.
      const ageNum = formData.age ? parseInt(formData.age, 10) : NaN;
      const syntheticDob = !formData.dateOfBirth
        && Number.isFinite(ageNum) && ageNum >= 0 && ageNum <= 120
        ? (() => {
            const today = new Date();
            const dob = new Date(today.getFullYear() - ageNum, today.getMonth(), today.getDate());
            return dob.toISOString().split('T')[0];
          })()
        : undefined;

      const patient = await registerPatientApi({
        firstName: formData.firstName.trim(),
        lastName: formData.lastName.trim(),
        dateOfBirth: formData.dateOfBirth || syntheticDob || undefined,
        gender: formData.gender,
        nationalId: formData.nationalId || undefined,
        phoneNumber: formData.contactPersonPhone || undefined,
        // Address is the optional building / landmark string. The
        // administrative location (province → village) lives on the
        // structured FK chain below.
        address: formData.streetAddress.trim() || undefined,
        emergencyContactName: formData.contactPersonName || formData.guardianName || undefined,
        emergencyContactPhone: formData.contactPersonPhone || formData.guardianPhone || undefined,
        // Persistent clinical facts captured at registration so they
        // appear immediately on the doctor's Overview without an extra
        // edit step. Blood type & medical history feed the medication
        // safety engine; guardian fields drive consent paths for
        // pediatric patients.
        bloodType: formData.bloodType || undefined,
        knownAllergies: allergiesText,
        chronicConditions: conditionsText,
        guardianName: formData.guardianName || undefined,
        guardianPhone: formData.guardianPhone || undefined,
        guardianRelationship: formData.guardianRelationship || undefined,
        guardianNationalId: formData.guardianNationalId || undefined,
        chiefComplaint,
        arrivalMode: formData.arrivalMode || undefined,
        hospitalId,
        // V46+ structured Rwanda location IDs — sent to the backend
        // alongside the legacy concatenated `address` for the FK
        // chain. The picker enforces cascade integrity (a district
        // is always under its province, etc.) so we never produce
        // an incompatible (province, district) pair.
        provinceId: formData.provinceId || undefined,
        districtId: formData.districtId || undefined,
        sectorId: formData.sectorId || undefined,
        cellId: formData.cellId || undefined,
        villageId: formData.villageId || undefined,
      });

      if (!patient) {
        setErrors({ assignedNurseId: 'Failed to register patient. Please try again.' });
        setIsSubmitting(false);
        return;
      }
      patientId = patient.id;
    }

    // Audit log — distinguish "new visit for existing patient" from
    // "fresh registration" so the trail is honest about what happened.
    addAuditEntry({
      action: existingPatientId ? 'VISIT_CREATED_FOR_EXISTING_PATIENT' : 'PATIENT_REGISTERED',
      performedBy: formData.assignedNurseId,
      performedByName: selectedNurseName,
      patientId,
      details: existingPatientId
        ? `New visit created for existing patient "${fullName}". Arrival: ${formData.arrivalMode}, Mobility: ${formData.mobility || 'N/A'}, Nurse: ${selectedNurseName}${isPediatric ? ', Pediatric patient' : ''}`
        : `Patient "${fullName}" registered. Arrival: ${formData.arrivalMode}, Mobility: ${formData.mobility || 'N/A'}, Nurse: ${selectedNurseName}${isPediatric ? ', Pediatric patient' : ''}`,
    });

    addAuditEntry({
      action: 'NURSE_ASSIGNED',
      performedBy: formData.assignedNurseId,
      performedByName: selectedNurseName,
      patientId,
      details: `Triage nurse "${selectedNurseName}" assigned to patient "${fullName}"`,
    });

    setShowSuccess(true);
    setTimeout(() => {
      setShowSuccess(false);
      navigate('/dashboard');
    }, 2500);
  };

  /* ── Shared styling helpers ── */
  const inputClass = (fieldName: string) =>
    `w-full px-4 py-3 rounded-xl text-sm text-slate-800 placeholder-slate-400 transition-all duration-300 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-500 font-medium ${errors[fieldName] ? 'border-red-400 ring-2 ring-red-100 bg-red-50/80' : ''
    }`;

  const selectClass = (fieldName: string) =>
    `w-full appearance-none px-4 py-3 rounded-xl text-sm text-slate-800 transition-all duration-300 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-500 cursor-pointer font-medium ${errors[fieldName] ? 'border-red-400 ring-2 ring-red-100 bg-red-50/80' : ''
    }`;

  const labelCls = 'block text-xs font-semibold text-slate-500 uppercase tracking-wider mb-1.5';

  /* ── Progress percentage ── */
  const progress = ((step - 1) / 4) * 100;

  /* ───── MAIN RETURN ───── */
  return (
    <div className="min-h-full p-5 animate-fade-in">
      <div className="space-y-5">

        {/* ── Header ── */}
        <div className="flex items-start justify-between animate-fade-up">
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={() => navigate('/dashboard')}
              className="w-10 h-10 rounded-xl flex items-center justify-center transition-all duration-300 hover:-translate-x-0.5 group flex-shrink-0"
              style={glassInner}
            >
              <ArrowLeft className="w-4 h-4 text-slate-500 group-hover:text-cyan-600 transition-all duration-300" />
            </button>
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-cyan-500 to-cyan-700 flex items-center justify-center shadow-lg shadow-cyan-500/20 flex-shrink-0">
              <ClipboardList className="w-5 h-5 text-white" />
            </div>
            <div>
              <h1 className="text-xl font-bold text-slate-800 tracking-tight leading-tight">
                Patient Registration
              </h1>
              <p className="text-sm text-slate-400 mt-0.5 font-medium">
                Step {step} of 5 — {STEPS[step - 1].label}
              </p>
            </div>
          </div>

          {/* Step counter badge */}
          <div className="flex items-center gap-3">
            <div className="px-4 py-2 rounded-full text-xs font-bold" style={glassInner}>
              <span className={progress === 100 ? 'text-emerald-600' : progress > 0 ? 'text-emerald-500' : 'text-slate-400'}>{Math.round(progress)}%</span>
              <span className="text-slate-400 ml-1">complete</span>
            </div>
          </div>
        </div>

        {/* ── Stepper — floating, no card ── */}
        <div className="animate-fade-up" style={{ animationDelay: '0.05s' }}>
          {/* Progress track */}
          <div className="h-1.5 rounded-full overflow-hidden mb-6" style={{ background: isDark ? 'rgba(2,132,199,0.15)' : 'rgba(203,213,225,0.25)' }}>
            <div
              className="h-full rounded-full transition-all duration-1000 ease-out"
              style={{
                width: `${progress}%`,
                background: progress === 100
                  ? 'linear-gradient(90deg, #10b981, #34d399)'
                  : 'linear-gradient(90deg, #10b981, #6ee7b7)',
                boxShadow: '0 0 12px rgba(16,185,129,0.4)',
              }}
            />
          </div>

          {/* Step indicators */}
          <div className="flex items-center justify-between">
            {STEPS.map((s, i) => {
              const isCompleted = step > s.id;
              const isActive = step === s.id;
              const Icon = s.icon;

              return (
                <React.Fragment key={s.id}>
                  <button
                    type="button"
                    onClick={() => setStep(s.id)}
                    title={s.label}
                    className="flex flex-col items-center gap-2 group cursor-pointer"
                  >
                    {/* Circle */}
                    <div
                      className={`relative w-11 h-11 rounded-full flex items-center justify-center transition-all duration-500 ${
                        isActive
                          ? 'bg-gradient-to-br from-cyan-500 to-cyan-600 shadow-lg shadow-cyan-500/30 scale-110'
                          : isCompleted
                            ? 'bg-gradient-to-br from-emerald-500 to-emerald-600 shadow-md shadow-emerald-500/25 scale-100'
                            : 'scale-100 group-hover:scale-105'
                      }`}
                      style={!isActive && !isCompleted ? { ...glassInner, background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.7)' } : undefined}
                    >
                      {isCompleted ? (
                        <Check className="w-5 h-5 text-white" />
                      ) : (
                        <Icon className={`w-[18px] h-[18px] transition-all duration-300 ${
                          isActive ? 'text-white' : 'text-slate-400 group-hover:text-slate-600'
                        }`} />
                      )}
                      {/* Active pulse ring */}
                      {isActive && (
                        <span className="absolute inset-0 rounded-full border-2 border-cyan-400/50 animate-ping" style={{ animationDuration: '2s' }} />
                      )}
                    </div>
                    {/* Label */}
                    <span className={`text-[11px] font-bold tracking-wide transition-all duration-300 ${
                      isActive
                        ? 'text-cyan-600'
                        : isCompleted
                          ? 'text-emerald-600'
                          : 'text-slate-400 group-hover:text-slate-500'
                    }`}>
                      {s.label}
                    </span>
                  </button>

                  {/* Connector line — dotted */}
                  {i < STEPS.length - 1 && (
                    <div className="flex-1 mx-1 flex items-center" style={{ marginTop: '-20px' }}>
                      <div
                        className="w-full transition-all duration-700"
                        style={{
                          height: '2px',
                          backgroundImage: step > s.id
                            ? 'radial-gradient(circle, #10b981 1.5px, transparent 1.5px)'
                            : `radial-gradient(circle, ${isDark ? 'rgba(2,132,199,0.4)' : 'rgba(203,213,225,0.5)'} 1.5px, transparent 1.5px)`,
                          backgroundSize: '10px 2px',
                          backgroundRepeat: 'repeat-x',
                        }}
                      />
                    </div>
                  )}
                </React.Fragment>
              );
            })}
          </div>
        </div>

        {/* ── Success Toast ── */}
        {showSuccess && (
          <div className="p-6 rounded-2xl flex items-center gap-5 animate-scale-in" style={{ ...glassCard, border: '1px solid rgba(6,182,212,0.3)', boxShadow: isDark ? '0 8px 32px rgba(6,182,212,0.15)' : '0 8px 32px rgba(6,182,212,0.1), inset 0 1px 0 rgba(255,255,255,0.8)' }}>
            <div className="w-14 h-14 bg-gradient-to-br from-cyan-500 to-cyan-600 rounded-2xl flex items-center justify-center shadow-lg shadow-cyan-500/30 animate-bounce-gentle">
              <Check className="w-7 h-7 text-white" />
            </div>
            <div>
              <p className="font-bold text-sm text-cyan-700 tracking-tight">Patient Registered Successfully!</p>
              <p className="text-xs text-cyan-600/70 font-semibold mt-1">Redirecting to dashboard...</p>
            </div>
          </div>
        )}

        {/* ── Federated lookup gate ──
            Default landing for Step 1: nurse searches for an existing
            patient by NID/passport/birth-cert/MRN/phone+DOB/guardian/etc.
            Picking a candidate pre-fills the form and switches to
            'register' mode. "Skip — register new" goes straight to the
            blank form. */}
        {mode === 'lookup' && step === 1 && (
          <div className="animate-fade-up" style={{ animationDelay: '0.1s' }}>
            <PatientLookupPanel
              hospitalId={authUser?.hospitalId || 'a0000000-0000-0000-0000-000000000001'}
              onCandidatePicked={applyLookupCandidate}
              onRegisterNew={() => setMode('register')}
            />
          </div>
        )}

        {/* ── Existing-patient banner (visible once a candidate has been
            picked from lookup). Lets the nurse reset back to the lookup
            panel if they grabbed the wrong record. */}
        {existingPatientId && mode === 'register' && (
          <div
            className="rounded-xl px-4 py-3 flex items-center gap-3 animate-fade-in"
            style={{ background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.3)' }}
          >
            <UserCheck className="w-4 h-4 text-emerald-600 flex-shrink-0" />
            <div className="flex-1 min-w-0">
              <p className="text-sm font-bold text-emerald-700">
                Existing patient loaded
              </p>
              <p className="text-xs text-emerald-600/70 font-medium mt-0.5">
                Identity and contact fields were pre-filled. Please verify, then
                continue through the steps to register today's visit.
              </p>
            </div>
            <button
              type="button"
              onClick={resetToLookup}
              className="text-xs font-semibold text-emerald-700 hover:text-emerald-900 px-2.5 py-1 rounded-md hover:bg-emerald-100 transition-colors"
            >
              Search again
            </button>
          </div>
        )}

        {/* ── Returning-patient context block ──
            Two side-by-side panels (stacks on mobile):
              • Patient profile — persistent facts (allergies, chronic
                conditions, blood type, guardian). Safety-critical info
                that the nurse must see before working with this person.
              • Prior visits — timeline of past encounters with chief
                complaint, triage colour, disposition. Each row is a
                drilldown to the full visit detail page. */}
        {existingPatientId && mode === 'register' && (
          <div
            className="grid grid-cols-1 lg:grid-cols-2 gap-4 animate-fade-up"
            style={{ animationDelay: '0.05s' }}
          >
            <PatientProfilePanel patient={pickedPatient} patientId={existingPatientId} />
            <PatientHistoryPanel patientId={existingPatientId} />
          </div>
        )}

        {/* ── Form Card — all steps always mounted, hidden when inactive.
            Hidden entirely while we're still in the lookup gate. ── */}
        {mode === 'register' && (
        <form
          onSubmit={(e) => {
            e.preventDefault();
            if (step === 5) handleSubmit();
            else goNext();
          }}
          className="rounded-2xl p-6 lg:p-8 animate-fade-up"
          style={{ ...glassCard, animationDelay: '0.1s' }}
        >

          {/* ════════ STEP 1 — Personal Information ════════ */}
          <div className={step === 1 ? 'animate-fade-up' : 'hidden'} key={`step-1-${step === 1}`}>
            <div className="space-y-6">
              <div className="flex items-center gap-3 mb-1">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-cyan-500 to-cyan-700 flex items-center justify-center shadow-md shadow-cyan-500/20">
                  <User className="w-5 h-5 text-white" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-slate-800 tracking-tight">Personal Information</h3>
                  <p className="text-xs text-slate-400 font-medium">Basic patient details and identification</p>
                </div>
              </div>

              {isPediatric && (
                <div className="p-4 rounded-xl flex items-center gap-4 animate-fade-in" style={{ ...glassInner, border: '1px solid rgba(244,114,182,0.3)', background: 'rgba(244,114,182,0.06)' }}>
                  <div className="w-11 h-11 bg-gradient-to-br from-pink-400 to-pink-500 rounded-xl flex items-center justify-center shadow-md shadow-pink-400/20">
                    <Baby className="w-5 h-5 text-white" />
                  </div>
                  <div>
                    <p className="font-bold text-pink-600 text-sm">Pediatric Mode Active</p>
                    <p className="text-xs text-pink-500/70 font-medium mt-0.5">Age-adjusted TEWS thresholds will apply. Guardian info required in Step 3.</p>
                  </div>
                </div>
              )}

              <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                <div>
                  <label className={labelCls}>First Name <span className="text-red-400">*</span></label>
                  <input type="text" className={inputClass('firstName')} style={glassInner} value={formData.firstName} onChange={(e) => set('firstName', e.target.value)} placeholder="Enter first name" />
                  {errors.firstName && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.firstName}</p>}
                </div>
                <div>
                  <label className={labelCls}>Last Name <span className="text-red-400">*</span></label>
                  <input type="text" className={inputClass('lastName')} style={glassInner} value={formData.lastName} onChange={(e) => set('lastName', e.target.value)} placeholder="Enter last name" />
                  {errors.lastName && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.lastName}</p>}
                </div>

                <div>
                  <label className={labelCls}>Date of Birth</label>
                  <input type="date" className={inputClass('dateOfBirth')} style={glassInner} value={formData.dateOfBirth} onChange={(e) => handleDateOfBirth(e.target.value)} max={new Date().toISOString().split('T')[0]} />
                </div>

                <div>
                  <label className={labelCls}>Age (years) <span className="text-red-400">*</span></label>
                  <input type="number" className={`${inputClass('age')} ${formData.dateOfBirth ? 'opacity-70 cursor-not-allowed' : ''}`} style={glassInner} value={formData.age} onChange={(e) => set('age', e.target.value)} placeholder="0" min="0" max="120" readOnly={!!formData.dateOfBirth} />
                  {formData.dateOfBirth && <p className="text-emerald-500 text-xs mt-1.5 ml-1 font-medium">Auto-calculated from DOB</p>}
                  {errors.age && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.age}</p>}
                </div>

                <div>
                  <label className={labelCls}>Gender <span className="text-red-400">*</span></label>
                  <select className={selectClass('gender')} style={glassInner} value={formData.gender} onChange={(e) => set('gender', e.target.value)}>
                    <option value="">Select gender</option>
                    <option value="MALE">Male</option>
                    <option value="FEMALE">Female</option>
                  </select>
                  {errors.gender && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.gender}</p>}
                </div>

                <div>
                  <label className={labelCls}>National ID</label>
                  <input type="text" className={inputClass('nationalId')} style={glassInner} value={formData.nationalId} onChange={(e) => set('nationalId', e.target.value)} placeholder="ID number (optional)" />
                  {/* Duplicate NID Warning */}
                  {duplicatePatient && (
                    <div className="flex items-center gap-2 mt-2 p-2.5 rounded-lg animate-fade-in" style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.3)' }}>
                      <AlertTriangle className="w-4 h-4 text-amber-500 flex-shrink-0" />
                      <p className="text-xs font-semibold text-amber-700">
                        Duplicate ID detected &mdash; Patient &ldquo;{duplicatePatient.fullName}&rdquo; (ID: {duplicatePatient.id}) already has this National ID.
                      </p>
                    </div>
                  )}
                </div>

                {isPediatric && (
                  <div>
                    <label className={labelCls}>Weight (kg) <span className="text-red-400">*</span></label>
                    <input type="number" step="0.1" className={inputClass('weight')} style={glassInner} value={formData.weight} onChange={(e) => set('weight', e.target.value)} placeholder="0.0" />
                    {errors.weight && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.weight}</p>}
                  </div>
                )}
              </div>
            </div>
          </div>

          {/* ════════ STEP 2 — Address Information ════════ */}
          <div className={step === 2 ? 'animate-fade-up' : 'hidden'} key={`step-2-${step === 2}`}>
            <div className="space-y-6">
              <div className="flex items-center gap-3 mb-1">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-emerald-500 to-emerald-700 flex items-center justify-center shadow-md shadow-emerald-500/20">
                  <MapPin className="w-5 h-5 text-white" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-slate-800 tracking-tight">Address Information</h3>
                  <p className="text-xs text-slate-400 font-medium">Patient's residential location details</p>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-5">
                {/* Street Address — optional free-text for the local
                    detail (building / landmark / plot). Rwandan addresses
                    are mostly described relationally ("near KK 15 Avenue
                    church", "Block A plot 27") so this stays free-text;
                    the structured Province → Village hierarchy below
                    captures the administrative granularity. */}
                <div className="sm:col-span-2">
                  <label className={labelCls}>
                    Street Address
                    <span className="ml-2 text-[10px] font-medium text-slate-400 uppercase tracking-wider">Optional</span>
                  </label>
                  <input
                    type="text"
                    className={inputClass('streetAddress')}
                    style={glassInner}
                    value={formData.streetAddress}
                    onChange={(e) => set('streetAddress', e.target.value)}
                    placeholder="e.g. near KK 15 Ave Church, Block A plot 27"
                  />
                </div>

                <div>
                  <label className={labelCls}>
                    Zipcode
                    <span className="ml-2 text-[10px] font-medium text-slate-400 uppercase tracking-wider">Optional</span>
                  </label>
                  <input type="text" className={inputClass('zipcode')} style={glassInner} value={formData.zipcode} onChange={(e) => set('zipcode', e.target.value)} placeholder="Enter zipcode" />
                </div>

                {/* Cascading Rwanda location picker — V46+
                    replaces the old flat dropdowns that mixed Kigali
                    City districts with Northern districts in one
                    list. Picking province narrows district, etc.
                    Backend persists the FK chain; the free-text
                    `streetAddress` field above stays for
                    building/landmark detail. */}
                <div className="sm:col-span-2">
                  <RwandaLocationPicker
                    value={{
                      provinceId: formData.provinceId,
                      districtId: formData.districtId,
                      sectorId: formData.sectorId,
                      cellId: formData.cellId,
                      villageId: formData.villageId,
                    }}
                    onChange={(next) => setFormData((f) => ({
                      ...f,
                      provinceId: next.provinceId,
                      districtId: next.districtId,
                      sectorId: next.sectorId,
                      cellId: next.cellId,
                      villageId: next.villageId,
                      // Mirror the human-readable names so the Step
                      // 4 confirmation screen ("Address Information"
                      // panel) shows the picked values. The picker
                      // emits these alongside the IDs.
                      province: next.provinceName ?? '',
                      district: next.districtName ?? '',
                      sector: next.sectorName ?? '',
                      cell: next.cellName ?? '',
                      village: next.villageName ?? '',
                    }))}
                    showHeader={false}
                  />
                  {(errors.province || errors.district || errors.sector) && (
                    <p className="text-red-500 text-xs mt-2 ml-1 font-medium">
                      {errors.province || errors.district || errors.sector}
                    </p>
                  )}
                </div>
              </div>
            </div>
          </div>

          {/* ════════ STEP 3 — Contact, Arrival, Guardian & Nurse Assignment ════════ */}
          <div className={step === 3 ? 'animate-fade-up' : 'hidden'} key={`step-3-${step === 3}`}>
            <div className="space-y-6">
              <div className="flex items-center gap-3 mb-1">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-amber-500 to-amber-700 flex items-center justify-center shadow-md shadow-amber-500/20">
                  <Phone className="w-5 h-5 text-white" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-slate-800 tracking-tight">Contact, Arrival & Assignment</h3>
                  <p className="text-xs text-slate-400 font-medium">Contacts, transport details, and triage nurse</p>
                </div>
              </div>

              <div className="grid grid-cols-1 md:grid-cols-2 gap-5">

                {/* ─── Adult contact fields (hidden for pediatric — guardian covers this) ─── */}
                {!isPediatric && (
                  <>
                    <div className="md:col-span-2">
                      <label className={labelCls}>Phone Number</label>
                      <input type="tel" className={inputClass('phoneNumber')} style={glassInner} value={formData.phoneNumber} onChange={(e) => set('phoneNumber', e.target.value)} placeholder="+250 7XX XXX XXX" />
                    </div>

                    {/* Emergency Contact section divider */}
                    <div className="md:col-span-2 flex items-center gap-3 py-1">
                      <div className="flex-1 h-px" style={{ background: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.4)' }} />
                      <span className="text-[10px] font-bold uppercase tracking-widest text-slate-400 flex items-center gap-1.5">
                        <Shield className="w-3 h-3" /> Emergency Contact
                      </span>
                      <div className="flex-1 h-px" style={{ background: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.4)' }} />
                    </div>

                    <div>
                      <label className={labelCls}>Emergency Contact Name</label>
                      <input type="text" className={inputClass('emergencyContactName')} style={glassInner} value={formData.emergencyContactName} onChange={(e) => set('emergencyContactName', e.target.value)} placeholder="Full name" />
                    </div>
                    <div>
                      <label className={labelCls}>Emergency Contact Phone</label>
                      <input type="tel" className={inputClass('emergencyContactPhone')} style={glassInner} value={formData.emergencyContactPhone} onChange={(e) => set('emergencyContactPhone', e.target.value)} placeholder="+250 7XX XXX XXX" />
                    </div>

                    {/* ─── Contact Person (Next of Kin) ─── */}
                    <div className="md:col-span-2 flex items-center gap-3 py-1">
                      <div className="flex-1 h-px" style={{ background: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.4)' }} />
                      <span className="text-[10px] font-bold uppercase tracking-widest text-slate-400 flex items-center gap-1.5">
                        <Users className="w-3 h-3" /> Contact Person / Next of Kin
                      </span>
                      <div className="flex-1 h-px" style={{ background: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.4)' }} />
                    </div>

                    <div>
                      <label className={labelCls}>Contact Person Name</label>
                      <input type="text" className={inputClass('contactPersonName')} style={glassInner} value={formData.contactPersonName} onChange={(e) => set('contactPersonName', e.target.value)} placeholder="Full name" />
                    </div>
                    <div>
                      <label className={labelCls}>Contact Person Phone</label>
                      <input type="tel" className={inputClass('contactPersonPhone')} style={glassInner} value={formData.contactPersonPhone} onChange={(e) => set('contactPersonPhone', e.target.value)} placeholder="+250 7XX XXX XXX" />
                    </div>
                    <div>
                      <label className={labelCls}>Relationship</label>
                      <select className={selectClass('contactPersonRelationship')} style={glassInner} value={formData.contactPersonRelationship} onChange={(e) => set('contactPersonRelationship', e.target.value)}>
                        <option value="">Select relationship</option>
                        {RELATIONSHIPS.map((r) => <option key={r} value={r}>{r}</option>)}
                      </select>
                    </div>
                  </>
                )}

                {/* ─── Guardian (Pediatric Only) ─── */}
                {isPediatric && (
                  <>
                    <div className="md:col-span-2 flex items-center gap-3 py-1">
                      <div className="flex-1 h-px" style={{ background: 'rgba(244,114,182,0.3)' }} />
                      <span className="text-[10px] font-bold uppercase tracking-widest text-pink-500 flex items-center gap-1.5">
                        <Baby className="w-3 h-3" /> Guardian Information (Required)
                      </span>
                      <div className="flex-1 h-px" style={{ background: 'rgba(244,114,182,0.3)' }} />
                    </div>

                    {/* Pediatric without guardian alert */}
                    {guardianMissing && (
                      <div className="md:col-span-2 p-4 rounded-xl flex items-center gap-4 animate-fade-in" style={{ background: 'rgba(239,68,68,0.06)', border: '1px solid rgba(239,68,68,0.25)' }}>
                        <div className="w-10 h-10 bg-gradient-to-br from-red-400 to-red-500 rounded-xl flex items-center justify-center shadow-md shadow-red-400/20 flex-shrink-0">
                          <AlertTriangle className="w-5 h-5 text-white" />
                        </div>
                        <div>
                          <p className="font-bold text-red-600 text-sm">Guardian Required</p>
                          <p className="text-xs text-red-500/70 font-medium mt-0.5">Pediatric patients (age &lt; 15) must have a guardian/caregiver registered. This is mandatory to proceed.</p>
                        </div>
                      </div>
                    )}

                    <div>
                      <label className={labelCls}>Guardian Name <span className="text-red-400">*</span></label>
                      <input type="text" className={inputClass('guardianName')} style={glassInner} value={formData.guardianName} onChange={(e) => set('guardianName', e.target.value)} placeholder="Guardian full name" />
                      {errors.guardianName && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.guardianName}</p>}
                    </div>
                    <div>
                      <label className={labelCls}>Guardian Phone <span className="text-red-400">*</span></label>
                      <input type="tel" className={inputClass('guardianPhone')} style={glassInner} value={formData.guardianPhone} onChange={(e) => set('guardianPhone', e.target.value)} placeholder="+250 7XX XXX XXX" />
                      {errors.guardianPhone && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.guardianPhone}</p>}
                    </div>
                    <div>
                      <label className={labelCls}>Guardian Relationship <span className="text-red-400">*</span></label>
                      <select className={selectClass('guardianRelationship')} style={glassInner} value={formData.guardianRelationship} onChange={(e) => set('guardianRelationship', e.target.value)}>
                        <option value="">Select relationship</option>
                        {GUARDIAN_RELATIONSHIPS.map((r) => <option key={r} value={r}>{r}</option>)}
                      </select>
                      {errors.guardianRelationship && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.guardianRelationship}</p>}
                    </div>
                    <div>
                      <label className={labelCls}>Guardian National ID</label>
                      <input type="text" className={inputClass('guardianNationalId')} style={glassInner} value={formData.guardianNationalId} onChange={(e) => set('guardianNationalId', e.target.value)} placeholder="Guardian ID (optional)" />
                    </div>
                  </>
                )}

                {/* ─── Arrival Mode ─── */}
                <div className="md:col-span-2 flex items-center gap-3 py-1">
                  <div className="flex-1 h-px" style={{ background: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.4)' }} />
                  <span className="text-[10px] font-bold uppercase tracking-widest text-slate-400 flex items-center gap-1.5">
                    <Siren className="w-3 h-3" /> Arrival Mode
                  </span>
                  <div className="flex-1 h-px" style={{ background: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.4)' }} />
                </div>

                <div className="md:col-span-2">
                  <label className={labelCls}>How did the patient arrive? <span className="text-red-400">*</span></label>
                  <div className="grid grid-cols-3 gap-4 mt-2">
                    {([
                      { mode: 'WALK_IN' as ArrivalMode, label: 'Walk-in', icon: Footprints, color: 'from-emerald-500 to-emerald-600' },
                      { mode: 'AMBULANCE' as ArrivalMode, label: 'Ambulance', icon: Siren, color: 'from-red-500 to-red-600' },
                      { mode: 'REFERRAL' as ArrivalMode, label: 'Referral', icon: Building2, color: 'from-blue-500 to-blue-600' },
                    ]).map(({ mode, label, icon: ModeIcon, color }) => (
                      <button
                        key={mode}
                        type="button"
                        onClick={() => set('arrivalMode', mode)}
                        className={`p-4 rounded-xl transition-all duration-300 font-semibold text-sm flex flex-col items-center gap-3 hover:-translate-y-1 ${formData.arrivalMode === mode
                            ? 'ring-2 ring-cyan-500/40 scale-[1.02]'
                            : ''
                          }`}
                        style={formData.arrivalMode === mode
                          ? { ...glassInner, background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.3)' }
                          : glassInner
                        }
                      >
                        <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${color} flex items-center justify-center shadow-md ${formData.arrivalMode === mode ? 'shadow-lg scale-110' : 'opacity-60'} transition-all duration-300`}>
                          <ModeIcon className="w-5 h-5 text-white" />
                        </div>
                        <span className={formData.arrivalMode === mode ? 'text-cyan-700' : 'text-slate-500'}>{label}</span>
                      </button>
                    ))}
                  </div>
                  {errors.arrivalMode && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.arrivalMode}</p>}
                </div>

                {isReferral && (
                  <>
                    <div className="md:col-span-2">
                      <label className={labelCls}>Referring Facility <span className="text-red-400">*</span></label>
                      <input type="text" className={inputClass('referringFacility')} style={glassInner} value={formData.referringFacility} onChange={(e) => set('referringFacility', e.target.value)} placeholder="Enter facility name" />
                      {errors.referringFacility && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.referringFacility}</p>}
                    </div>

                    {/* ─── Referral Document Upload ─── */}
                    <div className="md:col-span-2">
                      <label className={labelCls}>Referral Document</label>
                      {formData.referralDocumentFile ? (
                        <div className="flex items-center gap-3 p-4 rounded-xl animate-fade-in" style={{ ...glassInner, border: '1px solid rgba(16,185,129,0.3)', background: 'rgba(16,185,129,0.05)' }}>
                          <div className="w-10 h-10 bg-gradient-to-br from-emerald-500 to-emerald-600 rounded-xl flex items-center justify-center shadow-md">
                            <FileText className="w-5 h-5 text-white" />
                          </div>
                          <div className="flex-1 min-w-0">
                            <p className="text-sm font-semibold text-emerald-700 truncate">{formData.referralDocumentFile.name}</p>
                            <p className="text-xs text-emerald-600/60 font-medium">
                              {(formData.referralDocumentFile.size / 1024).toFixed(1)} KB
                            </p>
                          </div>
                          <button type="button" onClick={removeFile} className="w-8 h-8 rounded-lg flex items-center justify-center hover:bg-red-50 transition-all duration-300 group">
                            <X className="w-4 h-4 text-slate-400 group-hover:text-red-500" />
                          </button>
                        </div>
                      ) : (
                        <label className="flex flex-col items-center justify-center p-6 rounded-xl cursor-pointer hover:-translate-y-1 transition-all duration-300 group" style={{ ...glassInner, border: isDark ? '2px dashed rgba(2,132,199,0.25)' : '2px dashed rgba(203,213,225,0.5)' }}>
                          <div className="w-12 h-12 rounded-xl bg-gradient-to-br from-slate-200 to-slate-300 flex items-center justify-center mb-3 group-hover:from-cyan-100 group-hover:to-cyan-200 transition-all duration-300">
                            <Upload className="w-6 h-6 text-slate-400 group-hover:text-cyan-600 transition-all duration-300" />
                          </div>
                          <p className="text-sm font-semibold text-slate-500 group-hover:text-cyan-600 transition-all duration-300">Click to upload referral document</p>
                          <p className="text-xs text-slate-400 mt-1">PDF, JPG, PNG up to 10MB</p>
                          <input type="file" className="hidden" accept=".pdf,.jpg,.jpeg,.png,.doc,.docx" onChange={handleFileUpload} />
                        </label>
                      )}
                    </div>
                  </>
                )}

                {/* ─── Mobility / Physical Transport Mode ─── */}
                <div className="md:col-span-2 flex items-center gap-3 py-1">
                  <div className="flex-1 h-px" style={{ background: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.4)' }} />
                  <span className="text-[10px] font-bold uppercase tracking-widest text-slate-400 flex items-center gap-1.5">
                    <Accessibility className="w-3 h-3" /> Mobility / Transport
                  </span>
                  <div className="flex-1 h-px" style={{ background: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.4)' }} />
                </div>

                <div className="md:col-span-2">
                  <label className={labelCls}>Patient transport mode on arrival <span className="text-red-400">*</span></label>
                  <div className="grid grid-cols-3 gap-4 mt-2">
                    {([
                      { mode: 'AMBULATORY' as Mobility, label: 'Walking', icon: Footprints, color: 'from-emerald-500 to-emerald-600' },
                      { mode: 'WHEELCHAIR' as Mobility, label: 'Wheelchair', icon: Accessibility, color: 'from-amber-500 to-amber-600' },
                      { mode: 'STRETCHER' as Mobility, label: 'Stretcher', icon: Heart, color: 'from-red-500 to-red-600' },
                    ]).map(({ mode, label, icon: ModeIcon, color }) => (
                      <button
                        key={mode}
                        type="button"
                        onClick={() => set('mobility', mode)}
                        className={`p-4 rounded-xl transition-all duration-300 font-semibold text-sm flex flex-col items-center gap-3 hover:-translate-y-1 ${formData.mobility === mode
                            ? 'ring-2 ring-cyan-500/40 scale-[1.02]'
                            : ''
                          }`}
                        style={formData.mobility === mode
                          ? { ...glassInner, background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.3)' }
                          : glassInner
                        }
                      >
                        <div className={`w-10 h-10 rounded-xl bg-gradient-to-br ${color} flex items-center justify-center shadow-md ${formData.mobility === mode ? 'shadow-lg scale-110' : 'opacity-60'} transition-all duration-300`}>
                          <ModeIcon className="w-5 h-5 text-white" />
                        </div>
                        <span className={formData.mobility === mode ? 'text-cyan-700' : 'text-slate-500'}>{label}</span>
                      </button>
                    ))}
                  </div>
                  {errors.mobility && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.mobility}</p>}
                </div>

                {/* ─── Triage Nurse Assignment ─── */}
                <div className="md:col-span-2 flex items-center gap-3 py-1">
                  <div className="flex-1 h-px" style={{ background: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.4)' }} />
                  <span className="text-[10px] font-bold uppercase tracking-widest text-slate-400 flex items-center gap-1.5">
                    <UserCheck className="w-3 h-3" /> Triage Nurse Assignment
                  </span>
                  <div className="flex-1 h-px" style={{ background: isDark ? 'rgba(2,132,199,0.2)' : 'rgba(203,213,225,0.4)' }} />
                </div>

                <div className="md:col-span-2">
                  <label className={labelCls}>Assign Triage Nurse <span className="text-red-400">*</span></label>
                  <p className="text-xs text-slate-400 font-medium mb-3">A triage nurse must be assigned before the patient can proceed to triage.</p>
                  <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                    {nurses.map((nurse) => (
                      <button
                        key={nurse.id}
                        type="button"
                        onClick={() => set('assignedNurseId', nurse.id)}
                        className={`p-4 rounded-xl transition-all duration-300 font-semibold text-sm flex items-center gap-3 hover:-translate-y-1 ${formData.assignedNurseId === nurse.id
                            ? 'ring-2 ring-cyan-500/40 scale-[1.02]'
                            : ''
                          }`}
                        style={formData.assignedNurseId === nurse.id
                          ? { ...glassInner, background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.3)' }
                          : glassInner
                        }
                      >
                        <div className={`w-9 h-9 rounded-lg bg-gradient-to-br ${formData.assignedNurseId === nurse.id ? 'from-cyan-500 to-cyan-600' : 'from-slate-300 to-slate-400'} flex items-center justify-center shadow-sm transition-all duration-300`}>
                          <UserCheck className={`w-4 h-4 ${formData.assignedNurseId === nurse.id ? 'text-white' : 'text-white/80'}`} />
                        </div>
                        <span className={formData.assignedNurseId === nurse.id ? 'text-cyan-700' : 'text-slate-500'}>{nurse.name}</span>
                        {formData.assignedNurseId === nurse.id && (
                          <Check className="w-4 h-4 text-cyan-600 ml-auto" />
                        )}
                      </button>
                    ))}
                  </div>
                  {errors.assignedNurseId && <p className="text-red-500 text-xs mt-2 ml-1 font-medium">{errors.assignedNurseId}</p>}
                </div>
              </div>
            </div>
          </div>

          {/* ════════ STEP 4 — Medical History & Chief Complaint ════════ */}
          <div className={step === 4 ? 'animate-fade-up' : 'hidden'} key={`step-4-${step === 4}`}>
            <div className="space-y-6">
              <div className="flex items-center gap-3 mb-1">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-rose-500 to-rose-700 flex items-center justify-center shadow-md shadow-rose-500/20">
                  <Heart className="w-5 h-5 text-white" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-slate-800 tracking-tight">Medical History & Initial Complaint</h3>
                  <p className="text-xs text-slate-400 font-medium">Allergies, conditions, and reason for visit</p>
                </div>
              </div>

              {/* Blood Type — captured at registration so the medication
                  safety engine and any transfusion-related downstream
                  consumer has it available without a follow-up edit.
                  "Unknown" is a real value — empty string clears any
                  previously-set type. */}
              <div>
                <label className={labelCls}>Blood Type</label>
                <p className="text-xs text-slate-400 font-medium mb-2">Select if known. Leave Unknown if not yet typed.</p>
                <div className="flex flex-wrap gap-2 mt-1">
                  {BLOOD_TYPES.map((bt) => {
                    const active = formData.bloodType === bt;
                    return (
                      <button
                        key={bt}
                        type="button"
                        onClick={() => set('bloodType', active ? '' : bt)}
                        className={`px-3.5 py-1.5 rounded-lg text-xs font-bold tracking-wider transition-all duration-300 hover:-translate-y-0.5 ${active
                          ? 'bg-gradient-to-r from-red-500 to-red-600 text-white shadow-md shadow-red-500/20'
                          : 'text-slate-500 hover:text-slate-700'
                        }`}
                        style={!active ? glassInner : undefined}
                      >
                        {bt}
                      </button>
                    );
                  })}
                  <button
                    type="button"
                    onClick={() => set('bloodType', '')}
                    className={`px-3.5 py-1.5 rounded-lg text-xs font-bold tracking-wider transition-all duration-300 ${formData.bloodType === ''
                      ? 'bg-slate-700 text-white'
                      : 'text-slate-500 hover:text-slate-700'
                    }`}
                    style={formData.bloodType !== '' ? glassInner : undefined}
                  >
                    Unknown
                  </button>
                </div>
              </div>

              {/* Allergies */}
              <div>
                <label className={labelCls}>Allergies</label>
                <div className="grid grid-cols-2 md:grid-cols-3 gap-3 mt-2">
                  {ALLERGIES.map((a) => (
                    <label
                      key={a}
                      className={`flex items-center gap-3 p-3 rounded-xl cursor-pointer transition-all duration-300 hover:-translate-y-0.5 ${formData.allergies.includes(a) ? 'ring-2 ring-cyan-500/30' : ''}`}
                      style={formData.allergies.includes(a)
                        ? { ...glassInner, background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.3)' }
                        : glassInner
                      }
                    >
                      <input type="checkbox" checked={formData.allergies.includes(a)} onChange={() => toggleArray('allergies', a)} className="w-4 h-4 rounded border-slate-300 text-cyan-600 focus:ring-cyan-500" />
                      <span className={`text-sm font-medium ${formData.allergies.includes(a) ? 'text-cyan-700' : 'text-slate-600'}`}>{a}</span>
                    </label>
                  ))}
                </div>
              </div>

              {/* Existing Conditions */}
              <div>
                <label className={labelCls}>Existing Condition(s)</label>
                <div className="grid grid-cols-2 md:grid-cols-3 gap-3 mt-2">
                  {CONDITIONS.map((c) => (
                    <label
                      key={c}
                      className={`flex items-center gap-3 p-3 rounded-xl cursor-pointer transition-all duration-300 hover:-translate-y-0.5 ${formData.existingConditions.includes(c) ? 'ring-2 ring-cyan-500/30' : ''}`}
                      style={formData.existingConditions.includes(c)
                        ? { ...glassInner, background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.3)' }
                        : glassInner
                      }
                    >
                      <input type="checkbox" checked={formData.existingConditions.includes(c)} onChange={() => toggleArray('existingConditions', c)} className="w-4 h-4 rounded border-slate-300 text-cyan-600 focus:ring-cyan-500" />
                      <span className={`text-sm font-medium ${formData.existingConditions.includes(c) ? 'text-cyan-700' : 'text-slate-600'}`}>{c}</span>
                    </label>
                  ))}
                </div>
              </div>

              {/* Current Medications */}
              <div>
                <label className={labelCls}>Current Medication(s)</label>
                <textarea className={`${inputClass('currentMedications')} resize-none`} style={glassInner} value={formData.currentMedications} onChange={(e) => set('currentMedications', e.target.value)} placeholder="List all current medications..." rows={3} />
              </div>

              {/* Reason(s) for Visit — multi-select */}
              <div>
                <label className={labelCls}>Reason(s) For Visit <span className="text-red-400">*</span></label>
                <p className="text-xs text-slate-400 font-medium mb-2">Select all that apply</p>
                <div className="flex flex-wrap gap-2 mb-3">
                  {CHIEF_COMPLAINTS.filter(c => c !== 'Other').map((cc) => (
                    <button
                      key={cc}
                      type="button"
                      onClick={() => toggleArray('chiefComplaints', cc)}
                      className={`px-3.5 py-1.5 rounded-lg text-xs font-semibold transition-all duration-300 hover:-translate-y-0.5 ${formData.chiefComplaints.includes(cc)
                          ? 'bg-gradient-to-r from-cyan-500 to-cyan-600 text-white shadow-md shadow-cyan-500/20'
                          : 'text-slate-500 hover:text-slate-700'
                        }`}
                      style={!formData.chiefComplaints.includes(cc) ? glassInner : undefined}
                    >
                      {cc}
                    </button>
                  ))}
                </div>
                {formData.chiefComplaints.length > 0 && (
                  <p className="text-xs text-cyan-600 font-medium mb-2">
                    Selected: {formData.chiefComplaints.join(', ')}
                  </p>
                )}
                <label className={labelCls}>Additional Details / Other Complaints</label>
                <textarea className={`${inputClass('chiefComplaintOther')} resize-none`} style={glassInner} value={formData.chiefComplaintOther} onChange={(e) => set('chiefComplaintOther', e.target.value)} placeholder="Describe any additional complaints or provide more detail..." rows={3} />
                {errors.chiefComplaints && <p className="text-red-500 text-xs mt-1.5 ml-1 font-medium">{errors.chiefComplaints}</p>}
              </div>
            </div>
          </div>

          {/* ════════ STEP 5 — Review & Confirm ════════ */}
          <div className={step === 5 ? 'animate-fade-up' : 'hidden'} key={`step-5-${step === 5}`}>
            <div className="space-y-5">
              <div className="flex items-center gap-3 mb-1">
                <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-slate-600 to-slate-800 flex items-center justify-center shadow-md">
                  <FileText className="w-5 h-5 text-white" />
                </div>
                <div>
                  <h3 className="text-sm font-bold text-slate-800 tracking-tight">Review & Confirm</h3>
                  <p className="text-xs text-slate-400 font-medium">Please verify all information before submitting</p>
                </div>
              </div>

              {/* Duplicate NID Warning at review level */}
              {duplicatePatient && (
                <div className="p-4 rounded-xl flex items-center gap-4 animate-fade-in" style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.3)' }}>
                  <div className="w-10 h-10 bg-gradient-to-br from-amber-400 to-amber-500 rounded-xl flex items-center justify-center shadow-md flex-shrink-0">
                    <AlertTriangle className="w-5 h-5 text-white" />
                  </div>
                  <div>
                    <p className="font-bold text-amber-700 text-sm">Potential Duplicate Patient</p>
                    <p className="text-xs text-amber-600/70 font-medium mt-0.5">
                      National ID &ldquo;{formData.nationalId}&rdquo; is already assigned to patient &ldquo;{duplicatePatient.fullName}&rdquo; ({duplicatePatient.id}). Proceed with caution.
                    </p>
                  </div>
                </div>
              )}

              {/* Nurse assignment gate warning */}
              {!formData.assignedNurseId && (
                <div className="p-4 rounded-xl flex items-center gap-4 animate-fade-in" style={{ background: 'rgba(239,68,68,0.06)', border: '1px solid rgba(239,68,68,0.25)' }}>
                  <div className="w-10 h-10 bg-gradient-to-br from-red-400 to-red-500 rounded-xl flex items-center justify-center shadow-md flex-shrink-0">
                    <AlertTriangle className="w-5 h-5 text-white" />
                  </div>
                  <div>
                    <p className="font-bold text-red-600 text-sm">Triage Nurse Not Assigned</p>
                    <p className="text-xs text-red-500/70 font-medium mt-0.5">
                      Cannot proceed to triage without assigning a clinician. Go back to Step 3 to assign a nurse.
                    </p>
                  </div>
                </div>
              )}

              {/* Personal */}
              <div className="rounded-xl p-5" style={glassInner}>
                <h4 className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-4 flex items-center gap-2">
                  <div className="w-6 h-6 rounded-lg bg-gradient-to-br from-cyan-500 to-cyan-600 flex items-center justify-center">
                    <User className="w-3.5 h-3.5 text-white" />
                  </div>
                  Personal Information
                </h4>
                {[
                  ['First Name', formData.firstName],
                  ['Last Name', formData.lastName],
                  ['Age', formData.age ? `${formData.age} years` : ''],
                  ['Gender', formData.gender],
                  ['National ID', formData.nationalId],
                  ...(isPediatric ? [['Weight', formData.weight ? `${formData.weight} kg` : '']] : []),
                ].map(([label, value]) => (
                  <div key={label} className="flex justify-between py-2.5" style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.15)' : '1px solid rgba(203,213,225,0.25)' }}>
                    <span className="text-sm text-slate-400 font-medium">{label}</span>
                    <span className="text-sm font-semibold text-slate-700 text-right max-w-[60%]">{value || '\u2014'}</span>
                  </div>
                ))}
              </div>

              {/* Address */}
              <div className="rounded-xl p-5" style={glassInner}>
                <h4 className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-4 flex items-center gap-2">
                  <div className="w-6 h-6 rounded-lg bg-gradient-to-br from-emerald-500 to-emerald-600 flex items-center justify-center">
                    <MapPin className="w-3.5 h-3.5 text-white" />
                  </div>
                  Address Information
                </h4>
                {[
                  ['Street Address', formData.streetAddress],
                  ['Province', formData.province],
                  ['District', formData.district],
                  ['Sector / Cell / Village', [formData.sector, formData.cell, formData.village].filter(Boolean).join(' / ')],
                ].map(([label, value]) => (
                  <div key={label} className="flex justify-between py-2.5" style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.15)' : '1px solid rgba(203,213,225,0.25)' }}>
                    <span className="text-sm text-slate-400 font-medium">{label}</span>
                    <span className="text-sm font-semibold text-slate-700 text-right max-w-[60%]">{value || '\u2014'}</span>
                  </div>
                ))}
              </div>

              {/* Contact, Guardian & Arrival */}
              <div className="rounded-xl p-5" style={glassInner}>
                <h4 className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-4 flex items-center gap-2">
                  <div className="w-6 h-6 rounded-lg bg-gradient-to-br from-amber-500 to-amber-600 flex items-center justify-center">
                    <Phone className="w-3.5 h-3.5 text-white" />
                  </div>
                  Contact, Guardian & Arrival
                </h4>
                {[
                  ['Phone', formData.phoneNumber],
                  ['Emergency Contact', [formData.emergencyContactName, formData.emergencyContactPhone].filter(Boolean).join(' \u2014 ')],
                  ['Contact Person', formData.contactPersonName ? `${formData.contactPersonName} (${formData.contactPersonRelationship || 'N/A'}) \u2014 ${formData.contactPersonPhone || 'No phone'}` : ''],
                  ...(isPediatric ? [['Guardian', formData.guardianName ? `${formData.guardianName} (${formData.guardianRelationship}) \u2014 ${formData.guardianPhone}` : 'NOT SET']] : []),
                  ['Arrival Mode', formData.arrivalMode ? formData.arrivalMode.replace('_', ' ') : ''],
                  ['Mobility', formData.mobility ? formData.mobility.replace('_', ' ') : ''],
                  ...(isReferral ? [['Referring Facility', formData.referringFacility]] : []),
                  ...(isReferral && formData.referralDocumentFile ? [['Referral Document', formData.referralDocumentFile.name]] : []),
                ].map(([label, value]) => (
                  <div key={label} className="flex justify-between py-2.5" style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.15)' : '1px solid rgba(203,213,225,0.25)' }}>
                    <span className="text-sm text-slate-400 font-medium">{label}</span>
                    <span className={`text-sm font-semibold text-right max-w-[60%] ${label === 'Guardian' && value === 'NOT SET' ? 'text-red-500' : 'text-slate-700'}`}>{value || '\u2014'}</span>
                  </div>
                ))}
              </div>

              {/* Assigned Nurse */}
              <div className="rounded-xl p-5" style={glassInner}>
                <h4 className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-4 flex items-center gap-2">
                  <div className="w-6 h-6 rounded-lg bg-gradient-to-br from-indigo-500 to-indigo-600 flex items-center justify-center">
                    <UserCheck className="w-3.5 h-3.5 text-white" />
                  </div>
                  Triage Assignment
                </h4>
                <div className="flex justify-between py-2.5" style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.15)' : '1px solid rgba(203,213,225,0.25)' }}>
                  <span className="text-sm text-slate-400 font-medium">Assigned Triage Nurse</span>
                  <span className={`text-sm font-semibold ${selectedNurseName ? 'text-emerald-600' : 'text-red-500'}`}>
                    {selectedNurseName || 'NOT ASSIGNED'}
                  </span>
                </div>
              </div>

              {/* Medical */}
              <div className="rounded-xl p-5" style={glassInner}>
                <h4 className="text-xs font-bold text-slate-500 uppercase tracking-wider mb-4 flex items-center gap-2">
                  <div className="w-6 h-6 rounded-lg bg-gradient-to-br from-rose-500 to-rose-600 flex items-center justify-center">
                    <Heart className="w-3.5 h-3.5 text-white" />
                  </div>
                  Medical History
                </h4>
                {[
                  ['Blood Type', formData.bloodType || 'Unknown'],
                  ['Allergies', formData.allergies.join(', ') || 'None reported'],
                  ['Conditions', formData.existingConditions.join(', ') || 'None reported'],
                  ['Medications', formData.currentMedications || 'None reported'],
                  ['Reason(s) for Visit', [...formData.chiefComplaints, formData.chiefComplaintOther.trim()].filter(Boolean).join('; ')],
                ].map(([label, value]) => (
                  <div key={label} className="flex justify-between py-2.5" style={{ borderBottom: isDark ? '1px solid rgba(2,132,199,0.15)' : '1px solid rgba(203,213,225,0.25)' }}>
                    <span className="text-sm text-slate-400 font-medium">{label}</span>
                    <span className="text-sm font-semibold text-slate-700 text-right max-w-[60%]">{value || '\u2014'}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* Required fields note + Navigation inline */}
          <div className="flex items-center justify-between mt-8 pt-6" style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.18)' : '1px solid rgba(203,213,225,0.3)' }}>
            <div>
              {step > 1 ? (
                <button
                  type="button"
                  onClick={goPrev}
                  className="inline-flex items-center gap-2 px-6 py-3 rounded-xl text-sm font-bold text-slate-600 hover:text-slate-800 transition-all duration-300 hover:-translate-y-1 hover:shadow-lg"
                  style={glassInner}
                >
                  <ChevronLeft className="w-4 h-4" />
                  Previous
                </button>
              ) : (
                <p className="text-xs text-slate-400 font-medium">
                  <span className="text-red-400">*</span> indicates required fields
                </p>
              )}
            </div>

            {step < 5 ? (
              <button
                type="button"
                onClick={goNext}
                className="inline-flex items-center gap-2 px-8 py-3 bg-gradient-to-r from-cyan-500 to-cyan-600 hover:from-cyan-600 hover:to-cyan-700 text-white rounded-xl text-sm font-bold transition-all duration-300 shadow-lg shadow-cyan-500/25 hover:-translate-y-1 hover:shadow-xl hover:shadow-cyan-500/30"
              >
                Continue
                <ChevronRight className="w-4 h-4" />
              </button>
            ) : (
              <button
                type="button"
                onClick={handleSubmit}
                disabled={!formData.assignedNurseId || isSubmitting || showSuccess}
                className={`inline-flex items-center gap-2 px-10 py-3 rounded-xl text-sm font-bold transition-all duration-300 shadow-lg ${
                  formData.assignedNurseId && !isSubmitting && !showSuccess
                    ? 'bg-gradient-to-r from-cyan-500 to-cyan-600 hover:from-cyan-600 hover:to-cyan-700 text-white shadow-cyan-500/25 hover:-translate-y-1 hover:shadow-xl hover:shadow-cyan-500/30'
                    : 'bg-slate-300 text-slate-500 cursor-not-allowed shadow-none'
                }`}
              >
                {isSubmitting ? (
                  <>
                    <span className="w-4 h-4 rounded-full border-2 border-white/40 border-t-white animate-spin" />
                    Registering…
                  </>
                ) : (
                  <>
                    <Check className="w-4 h-4" />
                    Confirm Registration
                  </>
                )}
              </button>
            )}
          </div>
        </form>
        )}
      </div>
    </div>
  );
}
