/**
 * useMyShift – returns the current user's active shift assignment.
 *
 * Phase 1 zone routing: now reads from /shifts/me/current (which only
 * returns the authenticated user's own assignment) rather than
 * fetching every shift at the hospital and filtering client-side.
 *
 * The auth store also caches `currentZone` + `isShiftLead` on the
 * AuthUser after each shift refresh; this hook surfaces them with
 * a stable shape and a manual refresh entry point.
 */
import { useState, useEffect, useCallback } from 'react';
import { useAuthStore } from '@/store/authStore';
import { shiftApi } from '@/api/shifts';
import type { ShiftAssignmentResponse, EdZone } from '@/api/types';

interface MyShiftInfo {
  /** The zone the current user is assigned to, or null if off-shift. */
  zone: EdZone | null;
  /** True when this user holds the shift-lead badge — cross-zone visibility. */
  isShiftLead: boolean;
  /** True when there is any active shift assignment for this user. */
  isOnShift: boolean;
  /**
   * V44+ off-duty indicator. True when the user has an APPROVED leave
   * row covering today. Backend canAssign denies all shift-management
   * actions for this user; the sidebar surfaces an "On Leave" badge.
   */
  isOnApprovedLeave: boolean;
  /** Full shift assignment details (null when off-shift). */
  assignment: ShiftAssignmentResponse | null;
  /** Whether the initial fetch is still in flight. */
  isLoading: boolean;
  /** Manual refresh — call after a charge nurse re-assigns the user. */
  refresh: () => Promise<void>;
}

export function useMyShift(): MyShiftInfo {
  const user = useAuthStore((s) => s.user);
  const refreshAuthShift = useAuthStore((s) => s.refreshCurrentShift);
  const [assignment, setAssignment] = useState<ShiftAssignmentResponse | null>(null);
  const [isOnApprovedLeave, setIsOnApprovedLeave] = useState(false);
  const [isLoading, setIsLoading] = useState(true);

  const fetchShift = useCallback(async () => {
    if (!user?.id) {
      setIsLoading(false);
      return;
    }
    setIsLoading(true);
    try {
      const res = await shiftApi.getMyCurrent();
      // Backend sentinel: '' or null means "no active shift".
      setAssignment(res.assignment ? (res.assignment as ShiftAssignmentResponse) : null);
      // V44+ off-duty signal — drives the sidebar "On Leave" badge.
      setIsOnApprovedLeave(!!res.isOnApprovedLeave);
      // Also push the result into the auth store so other components
      // reading user.currentZone / user.isShiftLead stay consistent.
      await refreshAuthShift();
    } catch (err) {
      console.error('[useMyShift] Failed to fetch shift:', err);
      setAssignment(null);
      setIsOnApprovedLeave(false);
    } finally {
      setIsLoading(false);
    }
  }, [user?.id, refreshAuthShift]);

  useEffect(() => {
    fetchShift();
  }, [fetchShift]);

  return {
    zone: assignment?.zone ?? null,
    isShiftLead: !!assignment?.isShiftLead,
    isOnShift: !!assignment,
    isOnApprovedLeave,
    assignment,
    isLoading,
    refresh: fetchShift,
  };
}

/**
 * Utility: derive the ED zone from a triage category.
 * Mirrors the backend EdZone.fromTriageCategory() simple mapping.
 * Patient-placement decisions use a richer mapping server-side
 * (per-hospital peds resus + ambulatory zone configuration); this
 * helper is for label-rendering only.
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
