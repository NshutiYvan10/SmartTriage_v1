/* ── Auth API ── */
import { get, post } from './client';
import type { AuthResponse, LoginRequest, RefreshTokenRequest, ActivateAccountRequest, InvitationTokenInfo, UserResponse } from './types';

export const authApi = {
  login: (data: LoginRequest) =>
    post<AuthResponse>('/auth/login', data),

  refresh: (data: RefreshTokenRequest) =>
    post<AuthResponse>('/auth/refresh', data),

  /** Validate an invitation token (public — no auth required) */
  validateToken: (token: string) =>
    get<InvitationTokenInfo>(`/auth/validate-token?token=${token}`),

  /** Activate account using invitation token (public — no auth required) */
  activate: (data: ActivateAccountRequest) =>
    post<UserResponse>('/auth/activate', data),
};
