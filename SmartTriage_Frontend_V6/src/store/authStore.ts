import { create } from 'zustand';
import type { UserRole } from '@/types/roles';
import { authApi } from '@/api/auth';
import { setTokens, clearTokens, restoreTokens } from '@/api/client';
import type { AuthResponse, Role } from '@/api/types';

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
}

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
}

/** Map backend Role to frontend UserRole */
function mapRole(backendRole: Role): UserRole {
  switch (backendRole) {
    case 'SUPER_ADMIN': return 'SUPER_ADMIN';
    case 'HOSPITAL_ADMIN': return 'HOSPITAL_ADMIN';
    case 'DOCTOR': return 'DOCTOR';
    case 'TRIAGE_NURSE': return 'TRIAGE_NURSE';
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
  TRIAGE_NURSE: {
    id: 'U005',
    fullName: 'Habimana Claude',
    email: 'claude.habimana@kfh.rw',
    role: 'TRIAGE_NURSE',
    department: 'Emergency Triage',
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
        set({ user, isLoading: false, error: null });
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
        } catch {
          // Ignore parse errors
        }
      }
    },

    clearError: () => set({ error: null }),
  };
});

export { DEMO_USERS };
