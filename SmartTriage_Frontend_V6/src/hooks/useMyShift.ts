/**
 * useMyShift – Returns the current user's active shift assignment.
 * Used to determine which ED zone the user is assigned to,
 * so the Dashboard can filter patients and subscribe to zone alerts.
 */
import { useState, useEffect } from 'react';
import { useAuthStore } from '@/store/authStore';
import { shiftApi } from '@/api/shifts';
import type { ShiftAssignmentResponse, EdZone } from '@/api/types';

interface MyShiftInfo {
  /** The zone the current user is assigned to, or null if not on shift */
  zone: EdZone | null;
  /** Full shift assignment details */
  assignment: ShiftAssignmentResponse | null;
  /** All shift assignments for the current period (useful for zone lookup) */
  allAssignments: ShiftAssignmentResponse[];
  /** Whether the data is still loading */
  isLoading: boolean;
  /** Refresh shift data */
  refresh: () => Promise<void>;
}

export function useMyShift(): MyShiftInfo {
  const user = useAuthStore((s) => s.user);
  const [assignment, setAssignment] = useState<ShiftAssignmentResponse | null>(null);
  const [allAssignments, setAllAssignments] = useState<ShiftAssignmentResponse[]>([]);
  const [isLoading, setIsLoading] = useState(true);

  const fetchShift = async () => {
    if (!user?.hospitalId) {
      setIsLoading(false);
      return;
    }

    try {
      const hospitalId = user.hospitalId || 'a0000000-0000-0000-0000-000000000001';
      const assignments = await shiftApi.getCurrentShift(hospitalId);
      setAllAssignments(assignments);

      // Find the current user's assignment
      const mine = assignments.find((a) => a.userId === user.id && a.active);
      setAssignment(mine || null);
    } catch (err) {
      console.error('[useMyShift] Failed to fetch shift:', err);
    } finally {
      setIsLoading(false);
    }
  };

  useEffect(() => {
    fetchShift();
  }, [user?.id, user?.hospitalId]);

  return {
    zone: assignment?.zone || null,
    assignment,
    allAssignments,
    isLoading,
    refresh: fetchShift,
  };
}

/**
 * Utility: derive the ED zone from a triage category.
 * Mirrors the backend EdZone.fromTriageCategory() logic.
 */
export function getZoneForCategory(category: string | undefined): EdZone | null {
  switch (category) {
    case 'RED': return 'RESUS';
    case 'ORANGE': return 'ACUTE';
    case 'YELLOW': return 'GENERAL';
    case 'GREEN': return 'GENERAL';
    case 'BLUE': return 'GENERAL';
    default: return null;
  }
}
