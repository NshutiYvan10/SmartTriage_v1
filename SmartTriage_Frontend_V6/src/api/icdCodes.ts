/* ── ICD-10 reference catalog API ──
 *
 * Read-only catalog used by the diagnosis-entry form to autocomplete codes
 * and pre-fill clinical notes. Conditions common in the Rwandan ED context
 * are pinned to the top of search results so a doctor typing "mal" sees
 * Plasmodium falciparum malaria first.
 */
import { get } from './client';

export interface IcdCodeResponse {
  id: string;
  code: string;
  description: string;
  category: string | null;
  isCommonInRwanda: boolean;
  clinicalNotes: string | null;
}

export const icdApi = {
  /**
   * Substring search across `code` and `description`. Empty query returns
   * the curated common-in-Rwanda list as the starting state — saves the
   * doctor from staring at an empty dropdown.
   */
  search: (query: string) =>
    get<IcdCodeResponse[]>(`/icd-codes/search?query=${encodeURIComponent(query)}`),

  /** Curated short-list of the most frequent Rwandan ED diagnoses. */
  getCommon: () =>
    get<IcdCodeResponse[]>('/icd-codes/common'),
};
