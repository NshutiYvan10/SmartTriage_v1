import { useEffect, useState } from 'react';
import { useAuthStore } from '@/store/authStore';
import { hospitalApi } from '@/api/hospitals';
import type { HospitalResponse } from '@/api/types';

/**
 * Hospital scoping for admin views.
 *
 * SUPER_ADMIN is a national role whose own `hospitalId` points at the phantom
 * "SmartTriage Central" system hospital, which holds no clinical data — so a
 * super-admin viewing any hospital-scoped screen (dashboard metrics, audit
 * trail, override register, …) against that id sees nothing. This hook mirrors
 * the pattern already used by Reports and User Management: for a super admin it
 * loads the hospital list and defaults the selection to the first REAL hospital
 * (not the system one), exposing a setter so a picker can switch. Every other
 * role is pinned to their own hospital and never sees the picker.
 *
 * `hospitalId` is the effective id to query with; `showPicker` gates the
 * `<HospitalScopePicker>` UI; `hospitals` / `selectedHospitalId` / `setSelectedHospitalId`
 * drive it.
 */
export interface HospitalScope {
  isSuperAdmin: boolean;
  showPicker: boolean;
  hospitals: HospitalResponse[];
  selectedHospitalId: string;
  setSelectedHospitalId: (id: string) => void;
  hospitalId: string;
}

export function useHospitalScope(): HospitalScope {
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = user?.role === 'SUPER_ADMIN';
  const [hospitals, setHospitals] = useState<HospitalResponse[]>([]);
  const [selectedHospitalId, setSelectedHospitalId] = useState('');

  useEffect(() => {
    if (!isSuperAdmin) return;
    hospitalApi.getAll(0, 50).then((page) => {
      const rows = page.content || [];
      setHospitals(rows);
      // Default to the first hospital that ISN'T the super admin's own phantom
      // system hospital, so the view lands on real data instead of an empty one.
      const firstReal = rows.find((h) => h.id !== user?.hospitalId) || rows[0];
      if (firstReal) setSelectedHospitalId((cur) => cur || firstReal.id);
    }).catch((e) => console.error('[useHospitalScope] hospitals load failed:', e));
  }, [isSuperAdmin, user?.hospitalId]);

  const hospitalId = (isSuperAdmin ? selectedHospitalId : user?.hospitalId) || user?.hospitalId || '';

  return {
    isSuperAdmin,
    showPicker: isSuperAdmin && hospitals.length > 0,
    hospitals,
    selectedHospitalId,
    setSelectedHospitalId,
    hospitalId,
  };
}
