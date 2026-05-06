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
  drugClass: string | null;
  atcCode: string | null;
  remlCategory: string | null;

  // Dosing — drives common-dose suggestions and pediatric mg/kg calculation.
  // Despite the column names referencing "mg", the numeric values are
  // interpreted in the unit specified by `doseUnit` below.
  adultMinDoseMg: number | null;
  adultMaxDoseMg: number | null;
  adultMaxDailyDoseMg: number | null;
  pediatricMinDoseMgPerKg: number | null;
  pediatricMaxDoseMgPerKg: number | null;
  pediatricMaxDailyDoseMgPerKg: number | null;
  /**
   * Unit for the numeric dose ranges. Most drugs are MG, but insulin is
   * UNITS, oxytocin is IU, misoprostol is MCG, magnesium sulfate is G,
   * dextrose 50% is ML, ORS is SACHETS. The prescribe UI renders the
   * unit suffix and skips numeric mg validation for non-MG drugs.
   */
  doseUnit: string;
  geriatricAdjustmentPercent: number | null;
  renalAdjustmentRequired: boolean;
  hepaticAdjustmentRequired: boolean;

  // Routes (comma-separated) — drives the route dropdown's pre-fill.
  availableRoutes: string | null;

  // Interactions / safety.
  contraindications: string | null;
  majorInteractions: string | null;
  allergenGroups: string | null;
  isHighAlert: boolean;
  requiresDoubleCheck: boolean;
  blackBoxWarning: string | null;
  pregnancyCategory: string | null;
  isOnReml: boolean;

  hospitalId: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/**
 * Mirrors backend `ValidatePrescriptionRequest`. Note the field names and
 * nullability — earlier this interface had a required `drugName` and `doseMg`
 * that the backend does not actually require, which would make the request
 * fail @Valid on the server when prescribing without an explicit numeric
 * dose (e.g. "1 tablet"). Backend resolves the drug name from the medication
 * record by id.
 */
export interface ValidatePrescriptionRequest {
  visitId: string;
  medicationId: string;
  /** Optional. Required only for pediatric weight-based dose validation. */
  weightKg?: number | null;
  /** Optional. Numeric mg parsed from the free-text dose; omit if not parseable. */
  doseMg?: number | null;
}

export const medsafetyApi = {
  validate: (data: ValidatePrescriptionRequest) => post<MedicationSafetyCheck>('/med-safety/validate', data),
  /**
   * Records a doctor's override of a failed safety check.
   *
   * Backend `MedicationSafetyController.overrideSafetyCheck` declares
   *   @RequestParam String reason
   *   @RequestParam String overriddenBy
   * — those are QUERY parameters, not a body. Sending them as a body silently
   * 400s and the override is never recorded. A doctor would believe their
   * override was logged when in fact nothing was. The query-string form below
   * matches the backend contract.
   */
  override: (checkId: string, reason: string, overriddenBy: string) =>
    put<MedicationSafetyCheck>(
      `/med-safety/${checkId}/override?reason=${encodeURIComponent(reason)}&overriddenBy=${encodeURIComponent(overriddenBy)}`
    ),
  getForVisit: (visitId: string) => get<MedicationSafetyCheck[]>(`/med-safety/visit/${visitId}`),
  getFormulary: (hospitalId: string, page = 0) => get<{ content: DrugFormulary[]; totalElements: number }>(`/med-safety/formulary/${hospitalId}?page=${page}&size=50`),
  searchFormulary: (query: string) => get<DrugFormulary[]>(`/med-safety/formulary/search?query=${encodeURIComponent(query)}`),
  addFormularyEntry: (data: Partial<DrugFormulary>) => post<DrugFormulary>('/med-safety/formulary', data),
};
