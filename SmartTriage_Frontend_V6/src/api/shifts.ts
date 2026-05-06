import { get, post, put, patch, del } from './client';
import type { CreateShiftAssignmentRequest, ShiftAssignmentResponse, ShiftPeriodInfo, EdZone } from './types';

export const shiftApi = {
  /** Assign a staff member to a zone for the current shift */
  assign: (hospitalId: string, data: CreateShiftAssignmentRequest) =>
    post<ShiftAssignmentResponse>(`/shifts/hospital/${hospitalId}/assign`, data),

  /** Get all assignments for the current shift */
  getCurrentShift: (hospitalId: string) =>
    get<ShiftAssignmentResponse[]>(`/shifts/hospital/${hospitalId}/current`),

  /** Get assignments for a specific zone */
  getZoneAssignments: (hospitalId: string, zone: EdZone) =>
    get<ShiftAssignmentResponse[]>(`/shifts/hospital/${hospitalId}/zone/${zone}`),

  /** Get current shift period info */
  getCurrentPeriod: () =>
    get<ShiftPeriodInfo>('/shifts/current-period'),

  /** Remove a shift assignment */
  remove: (assignmentId: string) =>
    del<void>(`/shifts/${assignmentId}`),

  /** Get assignments for a specific date */
  getByDate: (hospitalId: string, date: string) =>
    get<ShiftAssignmentResponse[]>(`/shifts/hospital/${hospitalId}/date/${date}`),

  /** Get shift history for a user */
  getUserHistory: (userId: string) =>
    get<ShiftAssignmentResponse[]>(`/shifts/user/${userId}`),

  /** Update a shift assignment (change zone or function) */
  update: (assignmentId: string, data: CreateShiftAssignmentRequest) =>
    put<ShiftAssignmentResponse>(`/shifts/${assignmentId}`, data),

  /** End a shift assignment */
  endShift: (assignmentId: string) =>
    patch<ShiftAssignmentResponse>(`/shifts/${assignmentId}/end`),

  /**
   * Get the current shift-lead (Charge Nurse) for a hospital. Returns null
   * when nobody holds the badge yet — the backend sends `data: null` in that
   * case, which the `get<T>` helper surfaces as a null-valued resolved value.
   */
  getShiftLead: (hospitalId: string) =>
    get<ShiftAssignmentResponse | null>(`/shifts/hospital/${hospitalId}/shift-lead`),

  /**
   * Transfer the shift-lead badge to a specific existing assignment. Clears
   * the badge from any prior holder for that shift in the same call.
   */
  transferShiftLead: (assignmentId: string) =>
    post<ShiftAssignmentResponse>(`/shifts/${assignmentId}/shift-lead`, {}),

  /**
   * Phase 1 zone routing — fetch the authenticated user's current
   * shift assignment. Returns `{assignment: null}` when the user is
   * off-shift (which the helper coerces to null-valued when the
   * backend sends an empty string sentinel). The frontend's auth
   * store calls this on login and on shift change to derive the
   * user's currently-assigned zone + shift-lead status.
   */
  getMyCurrent: () =>
    get<{ assignment: ShiftAssignmentResponse | null | '' }>('/shifts/me/current'),
};
