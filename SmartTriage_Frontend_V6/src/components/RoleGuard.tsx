import { Navigate } from 'react-router-dom';
import { useAuthStore, type ShiftFunction } from '@/store/authStore';
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
  /**
   * RBAC fix — gate by today's active shift function. When set, the user
   * must hold one of these ShiftFunction values on their current shift
   * assignment. Cross-zone authorities (Charge Nurse designation,
   * shift-lead badge) bypass this check, matching backend ClinicalAuthz.
   * Admins (SUPER_ADMIN / HOSPITAL_ADMIN) are denied — admins are not
   * clinical actors and must not reach shift-function-gated pages.
   */
  requiresShiftFunction?: ShiftFunction[];
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
export function RoleGuard({ page, feature, allowDesignations, requiresShiftFunction, redirectTo = '/dashboard', inline = false, children }: RoleGuardProps) {
  const user = useAuthStore((s) => s.user);

  if (!user) {
    return <Navigate to="/" replace />;
  }

  const roleHasAccess =
    (page ? canAccessPage(user.role, page) : true) &&
    (feature ? hasFeature(user.role, feature) : true);

  const designationGrants =
    !!allowDesignations && !!user.designation && allowDesignations.includes(user.designation);

  // RBAC: shift-function gate. Authority follows TODAY'S assignment, not
  // permanent designation — a senior nurse rostered as ZONE_NURSE today
  // is a zone nurse today and cannot reach triage-only pages on the
  // strength of their permanent CHARGE_NURSE title.
  //
  // Allowed:
  //   - matching shift function on today's assignment
  //   - shift-lead badge holder (daily, transferable — covers the
  //     "Triage Nurse called out, senior nurse steps in" case)
  //   - shift function == CHARGE_NURSE today
  // Denied:
  //   - admins (never clinical)
  //   - users whose permanent designation is CHARGE_NURSE but whose
  //     shift function today is something else
  let shiftGatePasses = true;
  if (requiresShiftFunction && requiresShiftFunction.length > 0) {
    if (user.role === 'SUPER_ADMIN' || user.role === 'HOSPITAL_ADMIN') {
      shiftGatePasses = false;
    } else {
      const fn = user.currentShiftFunction;
      const onDutyChargeNurse =
        user.isShiftLead === true || fn === 'CHARGE_NURSE';
      shiftGatePasses = onDutyChargeNurse
        || (!!fn && requiresShiftFunction.includes(fn));
    }
  }

  const hasAccess = (roleHasAccess || designationGrants) && shiftGatePasses;

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
