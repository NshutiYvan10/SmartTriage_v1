import { get, post } from './client';
import type {
  CreateStaffLeaveRequest,
  LeaveDecisionRequest,
  StaffLeaveResponse,
} from './types';

export const leaveApi = {
  /** Submit a leave request — self-service (omit userId) or on behalf. */
  create: (body: CreateStaffLeaveRequest) =>
    post<StaffLeaveResponse>('/shifts/leaves', body),

  /** CN/admin: approve a pending leave row. */
  approve: (leaveId: string, body?: LeaveDecisionRequest) =>
    post<StaffLeaveResponse>(`/shifts/leaves/${leaveId}/approve`, body ?? {}),

  /** CN/admin: reject a pending leave row (note required). */
  reject: (leaveId: string, body: LeaveDecisionRequest) =>
    post<StaffLeaveResponse>(`/shifts/leaves/${leaveId}/reject`, body),

  /** Owner or CN/admin: cancel a leave (REQUESTED or APPROVED). */
  cancel: (leaveId: string) =>
    post<StaffLeaveResponse>(`/shifts/leaves/${leaveId}/cancel`, {}),

  /** Pending-approval queue at a hospital, oldest-first. */
  listPending: (hospitalId: string) =>
    get<StaffLeaveResponse[]>(`/shifts/leaves/hospital/${hospitalId}/pending`),

  /** Approved leave overlapping a date range — feeds the calendar overlay. */
  listOverlapping: (hospitalId: string, from: string, to: string) =>
    get<StaffLeaveResponse[]>(
      `/shifts/leaves/hospital/${hospitalId}/overlapping?from=${from}&to=${to}`,
    ),

  /** All leave for a specific user (admin/CN-side history view). */
  listForUser: (userId: string) =>
    get<StaffLeaveResponse[]>(`/shifts/leaves/user/${userId}`),

  /** Self-service: my own leave history. */
  listMine: () => get<StaffLeaveResponse[]>('/shifts/leaves/me'),
};
