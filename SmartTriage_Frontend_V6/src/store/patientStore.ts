import { create } from 'zustand';
import { Patient, TriageCategory, EmergencySigns, TEWSInput, Override } from '@/types';
import { patientApi } from '@/api/patients';
import { visitApi } from '@/api/visits';
import type { PatientResponse, VisitResponse, VisitStatus } from '@/api/types';

/**
 * Compute a patient's display age from an ISO DOB string.
 *
 * Returns:
 *   - completed integer years for age ≥ 1 (matches the conventional
 *     "completed years since birthday" interpretation; mirrors the
 *     backend's Period.between(dob, now).getYears())
 *   - fractional years for age < 1 so the display layer's
 *     `< 1 ? Math.round(age * 12) + 'mo' : age + 'y'` rendering
 *     produces "4mo" for a 4-month-old.
 *
 * Returns 0 when DOB is missing or unparseable — display sites
 * tolerate this (renders as "0mo"). Future-dated DOBs also fall back
 * to 0 rather than negative numbers.
 */
function ageInYearsFromDob(dob: string | null | undefined): number {
  if (!dob) return 0;
  const birth = new Date(dob);
  if (isNaN(birth.getTime())) return 0;
  const now = new Date();
  const ms = now.getTime() - birth.getTime();
  if (ms <= 0) return 0;
  // 365.2425 days/year matches the Gregorian average; avoids leap-year
  // drift on long-lived patients. Floor to whole years once we're past
  // the first birthday so adults render as "25y" not "25.92y".
  const rawYears = ms / (1000 * 60 * 60 * 24 * 365.2425);
  return rawYears >= 1 ? Math.floor(rawYears) : rawYears;
}

// ── Map backend VisitStatus → frontend triageStatus ──
function mapVisitStatus(status: VisitStatus): Patient['triageStatus'] {
  switch (status) {
    case 'REGISTERED':
    case 'AWAITING_TRIAGE':
      return 'WAITING';
    case 'TRIAGED':
      return 'TRIAGED';
    case 'AWAITING_ASSESSMENT':
      return 'IN_TRIAGE';
    case 'UNDER_ASSESSMENT':
    case 'UNDER_TREATMENT':
    case 'UNDER_OBSERVATION':
      return 'IN_TREATMENT';
    default:
      return 'TRIAGED';
  }
}

// ── Map backend DTO → frontend Patient ──
function mapToPatient(p: PatientResponse, v?: VisitResponse): Patient & Record<string, any> {
  // Jackson can serialize a Java `boolean isPediatric` field as either
  // `isPediatric` (when @JsonProperty is honored) or `pediatric` (default
  // "is" prefix stripping). Accept either, then fall back to age-based
  // computation so records always classify correctly.
  const raw = p as unknown as Record<string, unknown>;
  const flagPediatric =
    typeof raw.isPediatric === 'boolean' ? (raw.isPediatric as boolean)
    : typeof raw.pediatric === 'boolean' ? (raw.pediatric as boolean)
    : undefined;
  const ageYears = p.ageInYears;
  const resolvedIsPediatric =
    flagPediatric !== undefined ? flagPediatric
    : typeof ageYears === 'number' && ageYears >= 0 && ageYears < 18;

  return {
    id: v?.id || p.id, // Use visit ID as the key for triage workflows
    patientId: p.id,
    fullName: `${p.firstName} ${p.lastName}`,
    age: ageYears,
    gender: p.gender as Patient['gender'],
    nationalId: p.nationalId || undefined,
    chiefComplaint: v?.chiefComplaint || '',
    arrivalMode: (v?.arrivalMode as Patient['arrivalMode']) || 'WALK_IN',
    arrivalTimestamp: v?.arrivalTime ? new Date(v.arrivalTime) : new Date(p.createdAt),
    isPediatric: resolvedIsPediatric,
    triageStatus: v ? mapVisitStatus(v.status) : 'WAITING',
    category: v?.currentTriageCategory as TriageCategory | undefined,
    tewsScore: v?.currentTewsScore ?? undefined,
    aiAlerts: [],
    overrideHistory: [],
    contactPerson: p.emergencyContactName
      ? { name: p.emergencyContactName, phone: p.emergencyContactPhone || '', relationship: '' }
      : undefined,
    registrationCompletedAt: new Date(p.createdAt),
    // Additional fields for patient detail view
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
    referringFacility: v?.referringFacility || undefined,
    // Shift-handoff aggregate signals — backend populates these on
    // active-visits list endpoints. Carry them through so patient-card
    // surfaces (Monitoring, PatientsList) can render priority badges
    // without re-fetching per row. Absent when the source response
    // didn't include them (e.g. a single visit-by-id read).
    pendingInvestigationsCount: v?.pendingInvestigationsCount ?? undefined,
    unacknowledgedCriticalResultsCount: v?.unacknowledgedCriticalResultsCount ?? undefined,
    pendingMedicationsCount: v?.pendingMedicationsCount ?? undefined,
    hasOpenIcuEscalation: v?.hasOpenIcuEscalation ?? undefined,
  };
}

interface PatientState {
  patients: Patient[];
  isLoading: boolean;
  addPatient: (patient: Omit<Patient, 'id' | 'arrivalTimestamp' | 'isPediatric' | 'triageStatus' | 'aiAlerts' | 'overrideHistory' | 'registrationCompletedAt'>) => Patient;
  /** Register patient + create visit via API, then add to local store */
  registerPatientApi: (data: {
    firstName: string; lastName: string; dateOfBirth?: string;
    gender: string; nationalId?: string; rfidCardId?: string; phoneNumber?: string;
    address?: string; emergencyContactName?: string; emergencyContactPhone?: string;
    // Persistent clinical facts captured at registration. They surface
    // immediately on the doctor's Overview and feed the medication
    // safety engine without requiring a follow-up edit.
    bloodType?: string;
    knownAllergies?: string;
    chronicConditions?: string;
    /** S8 — optional body weight in kg, captured at registration. */
    weightKg?: number;
    guardianName?: string;
    guardianPhone?: string;
    guardianRelationship?: string;
    guardianNationalId?: string;
    chiefComplaint?: string; arrivalMode?: string; hospitalId: string;
    // V46+ structured Rwanda location IDs — any subset accepted.
    provinceId?: string; districtId?: string; sectorId?: string;
    cellId?: string; villageId?: string;
  }) => Promise<Patient | null>;
  /** Fetch active visits from backend and populate patient store */
  fetchActiveVisits: (hospitalId: string) => Promise<void>;
  ensurePatient: (patient: Patient) => void;
  updatePatient: (id: string, updates: Partial<Patient>) => void;
  getPatient: (id: string) => Patient | undefined;
  setTriageStatus: (id: string, status: Patient['triageStatus']) => void;
  setEmergencySigns: (id: string, signs: EmergencySigns) => void;
  setTEWSInput: (id: string, input: TEWSInput) => void;
  assignCategory: (id: string, category: TriageCategory, tewsScore?: number) => void;
  addOverride: (id: string, override: Override) => void;
  getPatientsByStatus: (status: Patient['triageStatus']) => Patient[];
  getPatientsByCategory: (category: TriageCategory) => Patient[];
  findByNationalId: (nationalId: string) => Patient | undefined;
}

export const usePatientStore = create<PatientState>((set, get) => ({
  patients: [],
  isLoading: false,

  /** Fetch active visits from API, match with patient data, populate store.
   *
   *  Calls the caller-aware endpoint so the result is automatically
   *  zone-scoped to the user's active shift assignment (cross-zone for
   *  HOSPITAL_ADMIN / SUPER_ADMIN / shift-lead / Charge Nurse; their own
   *  zone for everyone else; empty page for off-shift clinicians).
   *  Without this, every page that reads from the store (Monitoring,
   *  PatientsList, Dashboard, ...) was loading every patient hospital-
   *  wide regardless of who was logged in. The backend now refuses the
   *  unscoped call from non-leads with a 403 — switching the store
   *  default keeps the UI working AND honoring the zone boundary. */
  fetchActiveVisits: async (hospitalId: string) => {
    set({ isLoading: true });
    try {
      // Single bulk fetch — drops the N+1 patient-detail call per
      // visit that previously made the dashboard wait 2–5s with even
      // a modest patient load. The visit projection carries everything
      // the list view needs (name, complaint, category, score, peds
      // flag, status, arrival time); detailed patient fields (gender,
      // age, allergies, etc.) are lazy-loaded by PatientDetailView
      // when the nurse clicks into a specific patient.
      const visitsPage = await visitApi.getActiveForCallerByHospital(hospitalId, 0, 200);
      const visits: VisitResponse[] = visitsPage.content;

      const mapped: Patient[] = visits.map((v) => ({
        id: v.id,
        // Real backing patient row id so PatientDetailView can lazy-
        // hydrate the full record without a second list lookup.
        patientId: v.patientId,
        fullName: v.patientName || 'Unknown',
        // Derive age in years (with fractional months for infants) from
        // the DOB carried on the visit response. Previously hardcoded to
        // 0, which made every adult render as "0mo · M" in the queue.
        // Falls back to 0 only when DOB is genuinely missing — display
        // sites tolerate that (renders as "0mo").
        age: ageInYearsFromDob(v.patientDateOfBirth),
        gender: (v.patientGender as Patient['gender']) || 'MALE',
        dateOfBirth: v.patientDateOfBirth ?? undefined,
        chiefComplaint: v.chiefComplaint || '',
        arrivalMode: (v.arrivalMode as Patient['arrivalMode']) || 'WALK_IN',
        arrivalTimestamp: new Date(v.arrivalTime),
        isPediatric: v.isPediatric ?? false,
        triageStatus: mapVisitStatus(v.status),
        category: v.currentTriageCategory as TriageCategory | undefined,
        tewsScore: v.currentTewsScore ?? undefined,
        aiAlerts: [],
        overrideHistory: [],
        registrationCompletedAt: new Date(v.arrivalTime),
        // Current location + visit number so patient cards show WHERE to
        // go (zone/bed) without opening the chart.
        visitNumber: v.visitNumber ?? undefined,
        currentEdZone: v.currentEdZone ?? null,
        currentBedLabel: v.currentBedLabel ?? null,
        // Shift-handoff aggregate signals already on the visit response.
        pendingInvestigationsCount: v.pendingInvestigationsCount ?? undefined,
        unacknowledgedCriticalResultsCount: v.unacknowledgedCriticalResultsCount ?? undefined,
        pendingMedicationsCount: v.pendingMedicationsCount ?? undefined,
        hasOpenIcuEscalation: v.hasOpenIcuEscalation ?? undefined,
      } as Patient));

      set({ patients: mapped, isLoading: false });
    } catch (err) {
      console.error('[patientStore] fetchActiveVisits failed:', err);
      set({ isLoading: false });
    }
  },

  /** Register patient + create visit via single atomic API call */
  registerPatientApi: async (data) => {
    try {
      // Single atomic call — creates both patient + visit in one transaction
      //
      // dateOfBirth must NEVER be defaulted to "today" when the caller
      // didn't provide one. The backend uses Patient.dateOfBirth to
      // derive Patient.isPediatric (DOB + 13y > now), and a today-default
      // silently makes every DOB-less patient a 0-month-old infant —
      // routing them to the pediatric triage form and the pediatric
      // calculator. This is the clinical-safety regression we hit on
      // Luna Gisa. Send undefined when no DOB was captured; the backend
      // tolerates a null DOB (Patient.isPediatric returns false) and the
      // patient defaults to adult routing — the conservative direction.
      const result = await patientApi.register({
        firstName: data.firstName,
        lastName: data.lastName,
        dateOfBirth: data.dateOfBirth || undefined,
        gender: data.gender as any,
        nationalId: data.nationalId,
        rfidCardId: data.rfidCardId,
        phoneNumber: data.phoneNumber,
        address: data.address,
        emergencyContactName: data.emergencyContactName,
        emergencyContactPhone: data.emergencyContactPhone,
        bloodType: data.bloodType,
        knownAllergies: data.knownAllergies,
        chronicConditions: data.chronicConditions,
        weightKg: data.weightKg,
        guardianName: data.guardianName,
        guardianPhone: data.guardianPhone,
        guardianRelationship: data.guardianRelationship,
        guardianNationalId: data.guardianNationalId,
        chiefComplaint: data.chiefComplaint || '',
        arrivalMode: (data.arrivalMode as any) || 'WALK_IN',
        hospitalId: data.hospitalId,
        provinceId: data.provinceId,
        districtId: data.districtId,
        sectorId: data.sectorId,
        cellId: data.cellId,
        villageId: data.villageId,
      });

      // Map and add to local store
      const patient = mapToPatient(result.patient, result.visit);
      set((state) => ({ patients: [...state.patients, patient] }));
      return patient;
    } catch (err) {
      console.error('[patientStore] registerPatientApi failed:', err);
      return null;
    }
  },

  addPatient: (patientData) => {
    const patient: Patient = {
      ...patientData,
      id: `PT${Date.now()}${Math.random().toString(36).substr(2, 9).toUpperCase()}`,
      arrivalTimestamp: new Date(),
      // Rwanda mSAT boundary — child form covers 3–12, adult form 13+.
      // Matches Patient.isPediatric() on the backend and the
      // EntryRegistration form's pediatric-fields toggle.
      isPediatric: patientData.age < 13,
      triageStatus: 'WAITING',
      aiAlerts: [],
      overrideHistory: [],
      registrationCompletedAt: new Date(),
    };
    set((state) => ({ patients: [...state.patients, patient] }));
    return patient;
  },

  ensurePatient: (patient) => {
    const exists = get().patients.some((p) => p.id === patient.id);
    if (!exists) {
      set((state) => ({ patients: [...state.patients, patient] }));
    }
  },

  updatePatient: (id, updates) => {
    set((state) => ({
      patients: state.patients.map((p) =>
        p.id === id ? { ...p, ...updates } : p
      ),
    }));
  },

  getPatient: (id) => {
    return get().patients.find((p) => p.id === id);
  },

  setTriageStatus: (id, status) => {
    set((state) => ({
      patients: state.patients.map((p) =>
        p.id === id ? { ...p, triageStatus: status } : p
      ),
    }));
  },

  setEmergencySigns: (id, signs) => {
    set((state) => ({
      patients: state.patients.map((p) =>
        p.id === id ? { ...p, emergencySigns: signs } : p
      ),
    }));
  },

  setTEWSInput: (id, input) => {
    set((state) => ({
      patients: state.patients.map((p) =>
        p.id === id ? { ...p, tewsInput: input } : p
      ),
    }));
  },

  assignCategory: (id, category, tewsScore) => {
    set((state) => ({
      patients: state.patients.map((p) =>
        p.id === id
          ? {
              ...p,
              category,
              tewsScore,
              categoryAssignedAt: new Date(),
              triageStatus: 'TRIAGED',
            }
          : p
      ),
    }));
  },

  addOverride: (id, override) => {
    set((state) => ({
      patients: state.patients.map((p) =>
        p.id === id
          ? {
              ...p,
              overrideHistory: [...p.overrideHistory, override],
              category: override.newCategory,
            }
          : p
      ),
    }));
  },

  getPatientsByStatus: (status) => {
    return get().patients.filter((p) => p.triageStatus === status);
  },

  getPatientsByCategory: (category) => {
    return get().patients.filter((p) => p.category === category);
  },

  findByNationalId: (nationalId) => {
    if (!nationalId) return undefined;
    return get().patients.find(
      (p) => p.nationalId && p.nationalId.toLowerCase() === nationalId.toLowerCase()
    );
  },
}));
