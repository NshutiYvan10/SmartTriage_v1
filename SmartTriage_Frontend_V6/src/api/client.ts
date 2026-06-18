/* ═══════════════════════════════════════════════════════════════
   API Client — Centralized HTTP client with JWT auth,
   automatic token refresh, response unwrapping, error handling
   ═══════════════════════════════════════════════════════════════ */

import type { ApiResponse, AuthResponse, RefreshTokenRequest } from './types';

const API_BASE = '/api/v1';

// ── Token storage (in-memory for security) ──
let accessToken: string | null = null;
let refreshToken: string | null = null;
let isRefreshing = false;
let refreshSubscribers: Array<(token: string) => void> = [];

export function getAccessToken(): string | null {
  return accessToken;
}

export function getRefreshToken(): string | null {
  return refreshToken;
}

export function setTokens(access: string, refresh: string) {
  accessToken = access;
  refreshToken = refresh;
  // Persist refresh token for page reloads (secure in production via httpOnly cookie)
  localStorage.setItem('st-refresh-token', refresh);
}

export function clearTokens() {
  accessToken = null;
  refreshToken = null;
  localStorage.removeItem('st-refresh-token');
  localStorage.removeItem('st-auth-user');
}

/** Restore refresh token from storage on app start */
export function restoreTokens() {
  const stored = localStorage.getItem('st-refresh-token');
  if (stored) refreshToken = stored;
}

// ── Refresh queue ──
function onRefreshed(token: string) {
  refreshSubscribers.forEach((cb) => cb(token));
  refreshSubscribers = [];
}

function addRefreshSubscriber(callback: (token: string) => void): Promise<string> {
  return new Promise((resolve) => {
    refreshSubscribers.push((token: string) => {
      callback(token);
      resolve(token);
    });
  });
}

async function refreshAccessToken(): Promise<boolean> {
  if (!refreshToken) return false;

  if (isRefreshing) {
    await addRefreshSubscriber(() => {});
    return !!accessToken;
  }

  isRefreshing = true;
  try {
    const body: RefreshTokenRequest = { refreshToken };
    const response = await fetch(`${API_BASE}/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
    });

    if (!response.ok) {
      clearTokens();
      return false;
    }

    const apiResponse: ApiResponse<AuthResponse> = await response.json();
    if (apiResponse.success && apiResponse.data) {
      setTokens(apiResponse.data.accessToken, apiResponse.data.refreshToken);
      onRefreshed(apiResponse.data.accessToken);
      return true;
    }
    clearTokens();
    return false;
  } catch {
    clearTokens();
    return false;
  } finally {
    isRefreshing = false;
  }
}

/**
 * Ensure an in-memory ACCESS token is available, refreshing from the persisted refresh
 * token if needed. The access token lives only in memory, so after a page reload it is null
 * until the first 401→refresh — which would otherwise leave the authenticated WebSocket
 * (which now REQUIRES a token at CONNECT) unable to connect until some REST call happens.
 * The WS layer calls this on (re)connect so realtime comes back immediately after a reload.
 * Returns true if a usable access token is present afterwards.
 */
export async function ensureAccessToken(): Promise<boolean> {
  if (accessToken) return true;
  return refreshAccessToken();
}

// ── Core request function ──

export class ApiError extends Error {
  constructor(
    message: string,
    public status: number,
    public data?: Record<string, string> | null
  ) {
    super(message);
    this.name = 'ApiError';
  }
}

export async function apiRequest<T>(
  path: string,
  options?: RequestInit & { skipUnwrap?: boolean }
): Promise<T> {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    ...(options?.headers as Record<string, string>),
  };

  if (accessToken) {
    headers['Authorization'] = `Bearer ${accessToken}`;
  }

  const response = await fetch(`${API_BASE}${path}`, {
    ...options,
    headers,
  });

  // Handle 401 — try refresh
  if (response.status === 401 && refreshToken) {
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      // Retry with new token
      headers['Authorization'] = `Bearer ${accessToken}`;
      const retryResponse = await fetch(`${API_BASE}${path}`, {
        ...options,
        headers,
      });
      return handleResponse<T>(retryResponse);
    }
    // Refresh failed — redirect to login
    window.location.href = '/';
    throw new ApiError('Session expired', 401);
  }

  return handleResponse<T>(response);
}

async function handleResponse<T>(response: Response): Promise<T> {
  const text = await response.text();
  
  if (!text) {
    if (response.ok) return null as T;
    throw new ApiError('Empty response', response.status);
  }

  let apiResponse: ApiResponse<T>;
  try {
    apiResponse = JSON.parse(text);
  } catch {
    throw new ApiError('Invalid JSON response', response.status);
  }

  if (!response.ok || !apiResponse.success) {
    throw new ApiError(
      apiResponse.message || `Request failed with status ${response.status}`,
      response.status,
      apiResponse.data as Record<string, string> | null
    );
  }

  return apiResponse.data;
}

// ── Convenience shortcuts ──

export function get<T>(path: string): Promise<T> {
  return apiRequest<T>(path, { method: 'GET' });
}

export function post<T>(path: string, data?: unknown): Promise<T> {
  return apiRequest<T>(path, {
    method: 'POST',
    body: data ? JSON.stringify(data) : undefined,
  });
}

export function put<T>(path: string, data?: unknown): Promise<T> {
  return apiRequest<T>(path, {
    method: 'PUT',
    body: data ? JSON.stringify(data) : undefined,
  });
}

export function patch<T>(path: string, data?: unknown): Promise<T> {
  return apiRequest<T>(path, {
    method: 'PATCH',
    body: data ? JSON.stringify(data) : undefined,
  });
}

export function del<T>(path: string): Promise<T> {
  return apiRequest<T>(path, { method: 'DELETE' });
}

/**
 * Authed binary GET for file downloads (e.g. PDF). Returns the Blob plus the
 * server's Content-Disposition filename (falling back to `fallbackName`).
 * Handles a single 401 → refresh → retry like apiRequest.
 */
export async function downloadBlob(
  path: string,
  fallbackName = 'download'
): Promise<{ blob: Blob; filename: string }> {
  const doFetch = () => {
    const headers: Record<string, string> = {};
    if (accessToken) headers['Authorization'] = `Bearer ${accessToken}`;
    return fetch(`${API_BASE}${path}`, { method: 'GET', headers });
  };
  let response = await doFetch();
  if (response.status === 401 && refreshToken) {
    const refreshed = await refreshAccessToken();
    if (!refreshed) {
      window.location.href = '/';
      throw new ApiError('Session expired', 401);
    }
    response = await doFetch();
  }
  if (!response.ok) {
    throw new ApiError(`Download failed with status ${response.status}`, response.status);
  }
  const blob = await response.blob();
  const cd = response.headers.get('Content-Disposition') || '';
  const match = /filename="?([^"]+)"?/.exec(cd);
  return { blob, filename: match ? match[1] : fallbackName };
}

/** Trigger a browser download of a Blob with the given filename. */
export function saveBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}
