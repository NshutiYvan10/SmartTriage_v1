import { Navigate } from 'react-router-dom';
import { useAuthStore } from '@/store/authStore';
import { canAccessPage, hasFeature } from '@/types/roles';
import type { AppPage, AppFeature } from '@/types/roles';
import { ShieldX } from 'lucide-react';

interface RoleGuardProps {
  /** The page permission required to render children */
  page?: AppPage;
  /** An optional feature-level permission to check instead  */
  feature?: AppFeature;
  /**
   * Optional designation-based override. If the user's `designation` matches
   * any value in this list, access is granted regardless of the role-level
   * `page` / `feature` check. Used for cases where a specific sub-role (e.g.
   * a Charge Nurse) should reach a page that the parent role (NURSE) lacks.
   */
  allowDesignations?: string[];
  /** Where to redirect if the user lacks access (defaults to /dashboard) */
  redirectTo?: string;
  /** If true, shows a "no access" message instead of redirecting */
  inline?: boolean;
  children: React.ReactNode;
}

/**
 * Wraps a route or section and only renders children if the
 * current user's role has the required page or feature access.
 */
export function RoleGuard({ page, feature, allowDesignations, redirectTo = '/dashboard', inline = false, children }: RoleGuardProps) {
  const user = useAuthStore((s) => s.user);

  if (!user) {
    return <Navigate to="/" replace />;
  }

  const roleHasAccess =
    (page ? canAccessPage(user.role, page) : true) &&
    (feature ? hasFeature(user.role, feature) : true);

  const designationGrants =
    !!allowDesignations && !!user.designation && allowDesignations.includes(user.designation);

  const hasAccess = roleHasAccess || designationGrants;

  if (!hasAccess) {
    if (inline) {
      return (
        <div className="flex flex-col items-center justify-center py-16 text-center">
          <div className="w-16 h-16 rounded-2xl bg-red-50 border border-red-200 flex items-center justify-center mb-4">
            <ShieldX className="w-8 h-8 text-red-400" />
          </div>
          <h3 className="text-lg font-bold text-gray-900 mb-1">Access Restricted</h3>
          <p className="text-sm text-gray-500 max-w-xs">
            Your role does not have permission to view this section.
          </p>
        </div>
      );
    }
    return <Navigate to={redirectTo} replace />;
  }

  return <>{children}</>;
}

/**
 * Hook: quick boolean check for use inside components.
 * e.g. `const canRegister = useHasFeature('register_patient');`
 */
export function useHasFeature(feature: AppFeature): boolean {
  const user = useAuthStore((s) => s.user);
  if (!user) return false;
  return hasFeature(user.role, feature);
}

/**
 * Hook: check page access.
 */
export function useCanAccessPage(page: AppPage): boolean {
  const user = useAuthStore((s) => s.user);
  if (!user) return false;
  return canAccessPage(user.role, page);
}
