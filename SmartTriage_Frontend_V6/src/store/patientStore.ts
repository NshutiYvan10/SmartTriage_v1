import { create } from 'zustand';
import { Patient, TriageCategory, EmergencySigns, TEWSInput, Override } from '@/types';
import { patientApi } from '@/api/patients';
import { visitApi } from '@/api/visits';
import type { PatientResponse, VisitResponse, VisitStatus } from '@/api/types';

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
    gender: string; nationalId?: string; phoneNumber?: string;
    address?: string; emergencyContactName?: string; emergencyContactPhone?: string;
    // Persistent clinical facts captured at registration. They surface
    // immediately on the doctor's Overview and feed the medication
    // safety engine without requiring a follow-up edit.
    bloodType?: string;
    knownAllergies?: string;
    chronicConditions?: string;
    guardianName?: string;
    guardianPhone?: string;
    guardianRelationship?: string;
    guardianNationalId?: string;
    chiefComplaint?: string; arrivalMode?: string; hospitalId: string;
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
      const visitsPage = await visitApi.getActiveForCallerByHospital(hospitalId, 0, 200);
      const visits: VisitResponse[] = visitsPage.content;

      // For each visit, fetch the patient details and combine
      const mapped: Patient[] = await Promise.all(
        visits.map(async (v) => {
          try {
            const p = await patientApi.getById(v.patientId);
            return mapToPatient(p, v);
          } catch {
            // If patient fetch fails, build partial record from visit
            return {
              id: v.id,
              fullName: v.patientName || 'Unknown',
              age: 0,
              gender: 'OTHER' as Patient['gender'],
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
            } as Patient;
          }
        })
      );

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
      const result = await patientApi.register({
        firstName: data.firstName,
        lastName: data.lastName,
        dateOfBirth: data.dateOfBirth || new Date().toISOString().split('T')[0],
        gender: data.gender as any,
        nationalId: data.nationalId,
        phoneNumber: data.phoneNumber,
        address: data.address,
        emergencyContactName: data.emergencyContactName,
        emergencyContactPhone: data.emergencyContactPhone,
        bloodType: data.bloodType,
        knownAllergies: data.knownAllergies,
        chronicConditions: data.chronicConditions,
        guardianName: data.guardianName,
        guardianPhone: data.guardianPhone,
        guardianRelationship: data.guardianRelationship,
        guardianNationalId: data.guardianNationalId,
        chiefComplaint: data.chiefComplaint || '',
        arrivalMode: (data.arrivalMode as any) || 'WALK_IN',
        hospitalId: data.hospitalId,
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
