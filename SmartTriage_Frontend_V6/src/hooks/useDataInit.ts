/**
 * useDataInit – Hydrates Zustand stores from the backend API
 * after successful authentication.
 *
 * Called once in App.tsx.  Automatically re-fetches when
 * authentication state changes.
 */
import { useEffect, useRef } from 'react';
import { useAuthStore } from '@/store/authStore';
import { usePatientStore } from '@/store/patientStore';
import { useAlertStore } from '@/store/alertStore';
import { useDeviceStore } from '@/store/deviceStore';

export function useDataInit() {
  const user = useAuthStore((s) => s.user);
  const hasFetched = useRef(false);

  useEffect(() => {
    if (!user || hasFetched.current) return;

    const hospitalId = user.hospitalId || 'a0000000-0000-0000-0000-000000000001';

    async function hydrate() {
      console.log('[useDataInit] Hydrating stores for hospital', hospitalId);

      // Fire all independent fetches in parallel
      await Promise.allSettled([
        usePatientStore.getState().fetchActiveVisits(hospitalId),
        useAlertStore.getState().fetchAlerts(hospitalId),
        useDeviceStore.getState().fetchDevicesFromApi(hospitalId),
      ]);

      console.log('[useDataInit] Hydration complete');
    }

    hasFetched.current = true;
    hydrate();

    // Reset flag when user logs out so next login re-fetches
    return () => {
      hasFetched.current = false;
    };
  }, [user]);
}
