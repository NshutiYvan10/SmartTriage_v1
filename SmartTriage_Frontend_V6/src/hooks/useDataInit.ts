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
  const lastFetchedFor = useRef<string | null>(null);

  useEffect(() => {
    if (!userId || !hospitalId) return;
    // Skip if we already hydrated for this user. Resetting on logout
    // happens by way of `userId` becoming null and then a new value.
    if (lastFetchedFor.current === userId) return;

    lastFetchedFor.current = userId;
    Promise.allSettled([
      usePatientStore.getState().fetchActiveVisits(hospitalId),
      useAlertStore.getState().fetchAlerts(hospitalId),
      useDeviceStore.getState().fetchDevicesFromApi(hospitalId),
    ]).catch(() => { /* individual store catches log already */ });
  }, [userId, hospitalId]);
}
