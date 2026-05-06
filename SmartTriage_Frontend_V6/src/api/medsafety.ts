import { get, post, put } from './client';

export interface MedicationSafetyCheck {
  id: string;
  visitId: string;
  medicationId: string;
  checkedAt: string;
  drugName: string;
  prescribedDoseMg: number;
  patientWeightKg: number | null;
  allergyCheckPassed: boolean;
  allergyWarning: string | null;
  doseCheckPassed: boolean;
  doseWarning: string | null;
  interactionCheckPassed: boolean;
  interactionWarning: string | null;
  duplicateTherapyCheckPassed: boolean;
  duplicateWarning: string | null;
  overallSafe: boolean;
  overriddenBy: string | null;
  overrideReason: string | null;
  overriddenAt: string | null;
  createdAt: string;
}

export interface DrugFormulary {
  id: string;
  genericName: string;
  brandNames: string | null;
  drugClass: string;
  atcCode: string;
  remlCategory: string;
  adultMinDoseMg: number | null;
  adultMaxDoseMg: number | null;
  pediatricMinDoseMgPerKg: number | null;
  pediatricMaxDoseMgPerKg: number | null;
  /** Comma- or slash-separated routes the drug supports (e.g. "PO, IV"). */
  availableRoutes: string | null;
  isHighAlert: boolean;
  requiresDoubleCheck: boolean;
  isOnReml: boolean;
  allergenGroups: string | null;
  majorInteractions: string | null;
  pregnancyCategory: string | null;
}

export interface ValidatePrescriptionRequest {
  visitId: string;
  medicationId: string;
  drugName: string;
  doseMg: number;
  weightKg?: number;
}

export const medsafetyApi = {
  validate: (data: ValidatePrescriptionRequest) => post<MedicationSafetyCheck>('/med-safety/validate', data),
  override: (checkId: string, data: { reason: string; overriddenBy: string }) => put<MedicationSafetyCheck>(`/med-safety/${checkId}/override`, data),
  getForVisit: (visitId: string) => get<MedicationSafetyCheck[]>(`/med-safety/visit/${visitId}`),
  getFormulary: (hospitalId: string, page = 0) => get<{ content: DrugFormulary[]; totalElements: number }>(`/med-safety/formulary/${hospitalId}?page=${page}&size=50`),
  searchFormulary: (query: string) => get<DrugFormulary[]>(`/med-safety/formulary/search?query=${encodeURIComponent(query)}`),
  addFormularyEntry: (data: Partial<DrugFormulary>) => post<DrugFormulary>('/med-safety/formulary', data),
};
