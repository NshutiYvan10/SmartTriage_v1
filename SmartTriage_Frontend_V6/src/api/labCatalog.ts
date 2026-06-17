/* ── Laboratory / diagnostic test catalog API ──
 *
 * Read-only catalog used by the investigation-order form. Conditions
 * common in the Rwandan ED context (FBC, U&E, malaria RDT, HIV, ABG,
 * chest X-ray, ECG, etc.) are pinned to the top of search results.
 */
import { get } from './client';
import type { InvestigationType } from './types';

export interface LabTestCatalogResponse {
  id: string;
  testName: string;
  shortName: string | null;
  investigationType: InvestigationType;
  category: string | null;
  specimenType: string | null;
  statTurnaroundMinutes: number | null;
  routineTurnaroundMinutes: number | null;
  clinicalUse: string | null;
  isCommonInRwanda: boolean;
  /** Canonical result unit + reference range / critical thresholds (V81). Null for
   *  panels and qualitative tests. Used to pre-fill + unit-guard result entry. */
  resultUnit: string | null;
  referenceLow: number | null;
  referenceHigh: number | null;
  criticalLow: number | null;
  criticalHigh: number | null;
}

export const labCatalogApi = {
  /** Substring search across testName and shortName. Empty query → common list. */
  search: (query: string) =>
    get<LabTestCatalogResponse[]>(`/lab-catalog/search?query=${encodeURIComponent(query)}`),

  /** All catalog entries of a specific InvestigationType. */
  byType: (type: InvestigationType) =>
    get<LabTestCatalogResponse[]>(`/lab-catalog/by-type/${type}`),

  /** Curated short-list of tests common in the Rwandan ED. */
  getCommon: () =>
    get<LabTestCatalogResponse[]>('/lab-catalog/common'),
};
