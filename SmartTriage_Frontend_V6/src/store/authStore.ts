import { create } from 'zustand';
import type { UserRole } from '@/types/roles';
import { authApi } from '@/api/auth';
import { shiftApi } from '@/api/shifts';
import { setTokens, clearTokens, restoreTokens } from '@/api/client';
import { usePatientStore } from '@/store/patientStore';
import { useAlertStore } from '@/store/alertStore';
import { useDeviceStore } from '@/store/deviceStore';
import type { AuthResponse, EdZone, Role } from '@/api/types';

export interface AuthUser {
  id: string;
  fullName: string;
  email: string;
  role: UserRole;
  designation?: string;
  designationLabel?: string;
  avatar?: string;
  department?: string;
  hospital?: string;
  hospitalId?: string;
  /**
   * Phase 1 zone routing — the user's current shift assignment.
   * Resolved from /shifts/me/current after login or session restore;
   * null when the user is off-shift. The zone-scoped patient list
   * uses these to decide what to show.
   */
  currentZone?: EdZone | null;
  /** True when this user holds the shift-lead badge — cross-zone visibility. */
  isShiftLead?: boolean;
  /** True when the user has an active shift assignment of any kind. */
  isOnShift?: boolean;
  /**
   * RBAC fix — the user's ShiftFunction for the current shift (TRIAGE_NURSE,
   * ZONE_NURSE, CHARGE_NURSE, PRIMARY_DOCTOR, SUPERVISING_DOCTOR, RESIDENT).
   * Null when off-shift. Drives RoleGuard's shift-function gate so a NURSE
   * with ZONE_NURSE shift function can't access the /triage route.
   */
  currentShiftFunction?: ShiftFunction | null;
}

/** RBAC fix — frontend mirror of the backend ShiftFunction enum. */
export type ShiftFunction =
  | 'CHARGE_NURSE'
  | 'TRIAGE_NURSE'
  | 'ZONE_NURSE'
  | 'PRIMARY_DOCTOR'
  | 'SUPERVISING_DOCTOR'
  | 'RESIDENT';

interface AuthState {
  /** Currently authenticated user (null = not logged in) */
  user: AuthUser | null;
  /** Whether authentication is being verified */
  isLoading: boolean;
  /** Auth error message */
  error: string | null;

  /** Login via backend API */
  login: (email: string, password: string) => Promise<boolean>;
  /** Set the authenticated user */
  setUser: (user: AuthUser) => void;
  /** Clear auth (logout) */
  logout: () => void;
  /** Quick role setter (for dev / role-switcher) */
  switchRole: (role: UserRole) => void;
  /** Restore session from localStorage */
  restoreSession: () => void;
  /** Clear error */
  clearError: () => void;
  /**
   * Phase 1 zone routing — refresh the user's current shift assignment
   * from /shifts/me/current. Called automatically after successful
   * login and on session restore; can be called manually when the
   * user thinks their shift may have changed (e.g. after a charge
   * nurse re-assignment).
   */
  refreshCurrentShift: () => Promise<void>;
}

/** Map backend Role to frontend UserRole */
function mapRole(backendRole: Role): UserRole {
  switch (backendRole) {
    case 'SUPER_ADMIN': return 'SUPER_ADMIN';
    case 'HOSPITAL_ADMIN': return 'HOSPITAL_ADMIN';
    case 'DOCTOR': return 'DOCTOR';
    case 'NURSE': return 'NURSE';
    case 'REGISTRAR': return 'REGISTRAR';
    case 'PARAMEDIC': return 'PARAMEDIC';
    case 'LAB_TECHNICIAN': return 'LAB_TECHNICIAN';
    case 'READ_ONLY': return 'READ_ONLY';
    default: return 'READ_ONLY';
  }
}

function authResponseToUser(auth: AuthResponse): AuthUser {
  return {
    id: auth.userId,
    fullName: `${auth.firstName} ${auth.lastName}`,
    email: auth.email,
    role: mapRole(auth.role),
    designation: auth.designation ?? undefined,
    designationLabel: auth.designationLabel ?? undefined,
    hospital: auth.hospitalName,
    hospitalId: auth.hospitalId,
  };
}

/* ─── Default demo users per role (fallback when backend is unavailable) ─── */
const DEMO_USERS: Record<UserRole, AuthUser> = {
  SUPER_ADMIN: {
    id: 'U001',
    fullName: 'Dr. Kamanzi Patrick',
    email: 'admin@smarttriage.com',
    role: 'SUPER_ADMIN',
    department: 'System Administration',
    hospital: 'SmartTriage Central',
    hospitalId: 'a0000000-0000-0000-0000-000000000001',
  },
  HOSPITAL_ADMIN: {
    id: 'U002',
    fullName: 'Uwimana Marie Claire',
    email: 'marie.uwimana@kfh.rw',
    role: 'HOSPITAL_ADMIN',
    department: 'Administration',
    hospital: 'King Faisal Hospital',
    hospitalId: 'a0000000-0000-0000-0000-000000000001',
  },
  DOCTOR: {
    id: 'U003',
    fullName: 'Dr. Nkurunziza Jean',
    email: 'jean.nkurunziza@kfh.rw',
    role: 'DOCTOR',
    department: 'Emergency Medicine',
    hospital: 'King Faisal Hospital',
    hospitalId: 'a0000000-0000-0000-0000-000000000001',
  },
  NURSE: {
    id: 'U004',
    fullName: 'Mukiza Alice',
    email: 'alice.mukiza@kfh.rw',
    role: 'NURSE',
    department: 'Emergency Department',
    hospital: 'King Faisal Hospital',
    hospitalId: 'a0000000-0000-0000-0000-000000000001',
  },
  REGISTRAR: {
    id: 'U007',
    fullName: 'Mugisha Eric',
    email: 'eric.mugisha@kfh.rw',
    role: 'REGISTRAR',
    department: 'Patient Registration',
    hospital: 'King Faisal Hospital',
    hospitalId: 'a0000000-0000-0000-0000-000000000001',
  },
  PARAMEDIC: {
    id: 'U008',
    fullName: 'Uwera Diane',
    email: 'diane.uwera@kfh.rw',
    role: 'PARAMEDIC',
    department: 'Emergency Department',
    hospital: 'King Faisal Hospital',
    hospitalId: 'a0000000-0000-0000-0000-000000000001',
  },
  LAB_TECHNICIAN: {
    id: 'U009',
    fullName: 'Nsengimana Bosco',
    email: 'bosco.nsengimana@kfh.rw',
    role: 'LAB_TECHNICIAN',
    department: 'Laboratory',
    hospital: 'King Faisal Hospital',
    hospitalId: 'a0000000-0000-0000-0000-000000000001',
  },
  READ_ONLY: {
    id: 'U006',
    fullName: 'Ishimwe Grace',
    email: 'grace.ishimwe@kfh.rw',
    role: 'READ_ONLY',
    department: 'Quality Assurance',
    hospital: 'King Faisal Hospital',
    hospitalId: 'a0000000-0000-0000-0000-000000000001',
  },
};

/** Persist selected role in localStorage so it survives refresh */
function getPersistedRole(): UserRole {
  const stored = localStorage.getItem('st-active-role');
  if (stored && stored in DEMO_USERS) return stored as UserRole;
  return 'NURSE'; // default role
}

export const useAuthStore = create<AuthState>((set, get) => {
  // Try to restore user from localStorage (from a previous login session)
  const storedUser = localStorage.getItem('st-auth-user');
  let initialUser: AuthUser | null = null;

  if (storedUser) {
    try {
      initialUser = JSON.parse(storedUser);
      restoreTokens(); // Restore refresh token
    } catch {
      initialUser = null;
    }
  }

  // No fallback to demo users — require real authentication

  return {
    user: initialUser,
    isLoading: false,
    error: null,

    login: async (email: string, password: string) => {
      set({ isLoading: true, error: null });
      try {
        const auth = await authApi.login({ email, password });
        setTokens(auth.accessToken, auth.refreshToken);
        const user = authResponseToUser(auth);
        localStorage.setItem('st-auth-user', JSON.stringify(user));
        localStorage.setItem('st-active-role', user.role);
        // Set the user first so any subscribed component can render.
        set({ user, error: null });

        // Pre-fetch dashboard data + shift assignment IN PARALLEL so
        // the dashboard renders populated on its first mount instead
        // of empty-then-flashing-in. BUT — cap the wait at 3 seconds.
        // If the API is slow / one call hangs, we proceed to the
        // dashboard anyway with whatever loaded; remaining requests
        // resolve in the background and populate the stores when they
        // arrive. Without this cap, a single slow API call could keep
        // the LoginPage spinner spinning indefinitely (the
        // "blank screen / need to reload" bug).
        const hospitalId = user.hospitalId ?? '';
        const PREFETCH_CAP_MS = 3000;
        const prefetch = hospitalId
          ? Promise.allSettled([
              usePatientStore.getState().fetchActiveVisits(hospitalId),
              useAlertStore.getState().fetchAlerts(hospitalId),
              useDeviceStore.getState().fetchDevicesFromApi(hospitalId),
              get().refreshCurrentShift(),
            ])
          : get().refreshCurrentShift().catch(() => {});
        await Promise.race([
          prefetch,
          new Promise((resolve) => setTimeout(resolve, PREFETCH_CAP_MS)),
        ]);

        set({ isLoading: false });
        return true;
      } catch (err: unknown) {
        const message = err instanceof Error ? err.message : 'Login failed';
        set({ isLoading: false, error: message });
        return false;
      }
    },

    setUser: (user) => {
      localStorage.setItem('st-active-role', user.role);
      localStorage.setItem('st-auth-user', JSON.stringify(user));
      set({ user });
    },

    logout: () => {
      clearTokens();
      localStorage.removeItem('st-active-role');
      localStorage.removeItem('st-auth-user');
      set({ user: null });
    },

    switchRole: (role) => {
      const currentUser = get().user;
      // If we have a real authenticated user, just change the role for demo purposes
      const user = currentUser?.hospitalId
        ? { ...currentUser, role }
        : DEMO_USERS[role];
      localStorage.setItem('st-active-role', role);
      localStorage.setItem('st-auth-user', JSON.stringify(user));
      set({ user });
    },

    restoreSession: () => {
      restoreTokens();
      const storedUser = localStorage.getItem('st-auth-user');
      if (storedUser) {
        try {
          const user = JSON.parse(storedUser);
          set({ user });
          // Refresh the shift assignment after restoring — the cached
          // user's zone may be stale (shift changed since the tab was
          // last open).
          get().refreshCurrentShift().catch(() => {});
        } catch {
          // Ignore parse errors
        }
      }
    },

    clearError: () => set({ error: null }),

    refreshCurrentShift: async () => {
      const currentUser = get().user;
      if (!currentUser) return;
      try {
        const { assignment } = await shiftApi.getMyCurrent();
        // Backend sentinel: '' or null means "no active shift". Treat
        // both identically — user is off-shift.
        if (!assignment) {
          const updated: AuthUser = {
            ...currentUser,
            currentZone: null,
            isShiftLead: false,
            isOnShift: false,
            currentShiftFunction: null,
          };
          localStorage.setItem('st-auth-user', JSON.stringify(updated));
          set({ user: updated });
          return;
        }
        const updated: AuthUser = {
          ...currentUser,
          currentZone: (assignment.zone as EdZone) ?? null,
          isShiftLead: !!assignment.isShiftLead,
          isOnShift: true,
          // RBAC fix — surface the shift function so RoleGuard can gate
          // on TRIAGE_NURSE vs ZONE_NURSE vs CHARGE_NURSE etc.
          currentShiftFunction: (assignment.shiftFunction as ShiftFunction) ?? null,
        };
        localStorage.setItem('st-auth-user', JSON.stringify(updated));
        set({ user: updated });
      } catch (err) {
        console.warn('[auth] failed to refresh current shift', err);
      }
    },
  };
});

export { DEMO_USERS };
