/**
 * useDataInit – Hydrates Zustand stores from the backend API
 * after successful authentication.
 *
 * Called once in App.tsx. Two trigger points:
 *
 *   1. Session restore from localStorage on page reload — user is
 *      already set at first mount; the effect fires once on mount.
 *   2. A second logged-in user on the same session (rare) — the
 *      user-id dependency catches the change and re-hydrates.
 *
 * Notes:
 *   - Depends on `user?.id` rather than `user` so a shift-refresh
 *     `set({ user: updated })` (new object identity, same identity)
 *     does NOT cause hydrate() to fire a second time.
 *   - Login itself pre-fetches the same stores synchronously
 *     (authStore.login awaits Promise.allSettled before navigating),
 *     so on a fresh login the dashboard renders populated on its
 *     first mount. This hook handles the page-reload path.
 */
import { useEffect, useRef } from 'react';
import { useAuthStore } from '@/store/authStore';
import { usePatientStore } from '@/store/patientStore';
import { useAlertStore } from '@/store/alertStore';
import { useDeviceStore } from '@/store/deviceStore';

export function useDataInit() {
  const userId = useAuthStore((s) => s.user?.id ?? null);
  const hospitalId = useAuthStore((s) => s.user?.hospitalId ?? null);
  // Track the last *successful* fetch key. Reset to null whenever
  // userId becomes null (logout) so a subsequent log-in as the same
  // user still triggers a fresh fetch. Previously the ref persisted
  // across logout, which silently suppressed the post-login hydrate
  // for any logout-relogin-same-user cycle in the same tab.
  const lastFetchedFor = useRef<string | null>(null);

  useEffect(() => {
    if (!userId || !hospitalId) {
      // Clear the gate so the next login re-fetches.
      lastFetchedFor.current = null;
      return;
    }
    const key = `${userId}:${hospitalId}`;
    if (lastFetchedFor.current === key) return;

    lastFetchedFor.current = key;
    Promise.allSettled([
      usePatientStore.getState().fetchActiveVisits(hospitalId),
      useAlertStore.getState().fetchAlerts(hospitalId),
      useDeviceStore.getState().fetchDevicesFromApi(hospitalId),
    ]).catch(() => { /* individual store catches log already */ });
  }, [userId, hospitalId]);
}
