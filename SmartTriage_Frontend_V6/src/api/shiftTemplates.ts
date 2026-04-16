import { get, post, put, del } from './client';
import type { ShiftTemplateResponse, UpsertShiftTemplateRequest } from './types';

/**
 * Shift Template API — reusable per-shift rosters that the backend
 * materializes into concrete assignments at shift boundaries.
 *
 * Only SUPER_ADMIN and the Hospital Admin for the target hospital may
 * mutate templates (enforced server-side by the permission evaluator).
 */
export const shiftTemplateApi = {
  /** List active templates for a hospital (normally one DAY + one NIGHT). */
  listForHospital: (hospitalId: string) =>
    get<ShiftTemplateResponse[]>(`/shift-templates/hospital/${hospitalId}`),

  /** Fetch a single template by id. */
  getById: (templateId: string) =>
    get<ShiftTemplateResponse>(`/shift-templates/${templateId}`),

  /**
   * Create a new template for (hospitalId, shiftPeriod). If an active
   * template already exists for that pair, the server soft-deletes it
   * first so the new one becomes canonical.
   */
  create: (hospitalId: string, data: UpsertShiftTemplateRequest) =>
    post<ShiftTemplateResponse>(`/shift-templates/hospital/${hospitalId}`, data),

  /**
   * Replace a template's full contents (name, description, period, roster).
   * The server treats the assignments list as canonical — old rows are
   * dropped via orphan removal.
   */
  update: (templateId: string, data: UpsertShiftTemplateRequest) =>
    put<ShiftTemplateResponse>(`/shift-templates/${templateId}`, data),

  /** Soft-delete a template so history stays queryable. */
  remove: (templateId: string) =>
    del<void>(`/shift-templates/${templateId}`),
};
