/* ── Users API ── */
import { get, post, put, patch, del } from './client';
import type { CreateUserRequest, InviteUserRequest, UserResponse, Page, Role, Designation } from './types';

export const userApi = {
  create: (data: Partial<CreateUserRequest>) =>
    post<UserResponse>('/users', data),

  /** Invite a user by email (modern flow — no password required) */
  invite: (data: InviteUserRequest) =>
    post<UserResponse>('/users/invite', data),

  /** Resend invitation email for a pending user */
  resendInvite: (userId: string) =>
    post<void>(`/users/${userId}/resend-invite`, {}),

  /**
   * Cancel a pending invitation. Soft-deletes the user and invalidates
   * any outstanding token so the email link stops working immediately.
   * Only valid for accounts in PENDING_ACTIVATION status — for an
   * already-activated user, call {@link delete} (deactivate) instead.
   */
  cancelInvite: (userId: string) =>
    del<void>(`/users/${userId}/invite`),

  update: (id: string, data: Partial<CreateUserRequest>) =>
    put<UserResponse>(`/users/${id}`, data),

  /**
   * Self-service profile edit — the signed-in user updates their OWN name and
   * phone. Hits PUT /users/me/profile, which always acts on the authenticated
   * principal (no id needed). This is the real save the Profile page uses.
   */
  updateMyProfile: (data: { firstName: string; lastName: string; phoneNumber?: string }) =>
    put<UserResponse>('/users/me/profile', data),

  getById: (id: string) =>
    get<UserResponse>(`/users/${id}`),

  getByHospital: (hospitalId: string, page = 0, size = 20, includeInactive = false) =>
    get<Page<UserResponse>>(`/users/hospital/${hospitalId}?page=${page}&size=${size}${includeInactive ? '&includeInactive=true' : ''}`),

  delete: (id: string) =>
    del<void>(`/users/${id}`),

  /** Reactivate a previously-deactivated user (restores login + ACTIVE status) */
  reactivate: (id: string) =>
    post<void>(`/users/${id}/reactivate`, {}),

  /** Update only the designation of a user (admin only) */
  updateDesignation: (id: string, designation: Designation) =>
    patch<UserResponse>(`/users/${id}/designation`, { designation }),

  /** Get designations allowed for a specific role (for dropdown) */
  getDesignations: (role: Role) =>
    get<{ value: string; label: string }[]>(`/users/designations?role=${role}`),
};
