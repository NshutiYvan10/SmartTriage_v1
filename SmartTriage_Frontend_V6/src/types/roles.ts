/* ─── Role-Based Access Control Types ─── */

export type UserRole =
  | 'SUPER_ADMIN'
  | 'HOSPITAL_ADMIN'
  | 'DOCTOR'
  | 'NURSE'
  | 'REGISTRAR'
  | 'PARAMEDIC'
  | 'LAB_TECHNICIAN'
  | 'READ_ONLY';

/** Every routable view / feature the sidebar can reference */
export type AppPage =
  | 'dashboard'
  | 'entry'
  | 'patients'
  | 'triage'
  | 'monitoring'
  | 'alerts'
  | 'audit-trail'
  | 'reports'
  | 'settings'
  | 'admin'
  | 'admin-hospitals'
  | 'admin-users'
  | 'iot-devices'
  | 'notifications'
  | 'profile'
  | 'sepsis'
  | 'fast-track'
  | 'hypoglycemia'
  | 'isolation'
  | 'documentation'
  | 'med-safety'
  | 'med-safety-overrides'
  | 'lab'
  | 'pathways'
  | 'icu'
  | 'referral'
  | 'safety-incidents'
  | 'handover'
  | 'quality'
  | 'prediction'
  | 'moh-reports'
  | 'governance'
  | 'shift-planner'
  | 'shift-assignment'
  | 'shift-calendar'
  | 'swap-approvals'
  | 'leave-approvals'
  | 'delegations'
  | 'my-schedule'
  | 'beds'
  | 'admin-beds';

/** Feature-level permissions (things that can be toggled inside a page) */
export type AppFeature =
  | 'register_patient'
  | 'start_triage'
  | 'record_vitals'
  | 'override_category'
  | 'acknowledge_alert'
  | 'add_clinical_note'
  | 'export_report'
  | 'manage_users'
  | 'manage_settings'
  | 'view_audit'
  | 'view_reports'
  | 'view_monitoring';

/** Metadata for displaying a role in the UI */
export interface RoleMeta {
  label: string;
  description: string;
  color: string;          // tailwind bg class
  textColor: string;      // tailwind text class
  borderColor: string;    // tailwind border class
}

/* ─── Role metadata ─── */
export const ROLE_META: Record<UserRole, RoleMeta> = {
  SUPER_ADMIN: {
    label: 'Super Admin',
    description: 'System-wide configuration & multi-tenant management',
    color: 'bg-violet-500',
    textColor: 'text-violet-600',
    borderColor: 'border-violet-300',
  },
  HOSPITAL_ADMIN: {
    label: 'Hospital Admin',
    description: 'Hospital-level user & configuration management',
    color: 'bg-indigo-500',
    textColor: 'text-indigo-600',
    borderColor: 'border-indigo-300',
  },
  DOCTOR: {
    label: 'Doctor',
    description: 'Reviews triage results & makes disposition decisions',
    color: 'bg-cyan-500',
    textColor: 'text-cyan-600',
    borderColor: 'border-cyan-300',
  },
  NURSE: {
    label: 'Nurse',
    description: 'Performs primary triage & records patient vitals',
    color: 'bg-emerald-500',
    textColor: 'text-emerald-600',
    borderColor: 'border-emerald-300',
  },
  REGISTRAR: {
    label: 'Registrar',
    description: 'Patient registration and admission processing',
    color: 'bg-teal-500',
    textColor: 'text-teal-600',
    borderColor: 'border-teal-300',
  },
  PARAMEDIC: {
    label: 'Paramedic',
    description: 'Pre-hospital emergency care and patient transport',
    color: 'bg-orange-500',
    textColor: 'text-orange-600',
    borderColor: 'border-orange-300',
  },
  LAB_TECHNICIAN: {
    label: 'Lab Technician',
    description: 'Laboratory investigations and results management',
    color: 'bg-purple-500',
    textColor: 'text-purple-600',
    borderColor: 'border-purple-300',
  },
  READ_ONLY: {
    label: 'Read Only',
    description: 'Audit, reporting & observation-only access',
    color: 'bg-slate-500',
    textColor: 'text-slate-600',
    borderColor: 'border-slate-300',
  },
};

/* ─── Page-level permissions per role ───
 *
 * SUPER_ADMIN  → National-level: hospital management, system governance, national reports.
 *                Does NOT access day-to-day clinical operations.
 * HOSPITAL_ADMIN → Hospital management: staff, settings, audit, reports. Can view clinical data.
 * DOCTOR       → Full clinical: patients, triage, all clinical tools, monitoring, documentation.
 * NURSE        → Clinical care: patients, triage, vitals, monitoring, core clinical tools, documentation.
 *                Triage-station assignment is a per-shift function (ShiftAssignment.shiftFunction =
 *                TRIAGE_NURSE), NOT a separate role.
 * REGISTRAR    → Registration only: entry, patient list, referrals.
 * PARAMEDIC    → Pre-hospital: registration, handover, basic patient view.
 * LAB_TECHNICIAN → Lab-focused: lab orders/results, patient data for context.
 * READ_ONLY    → View-only: reports, audit, quality metrics.
 */
export const ROLE_PAGES: Record<UserRole, AppPage[]> = {

  // ── National-level administration only ──
  // Shift management is owned by the Charge Nurse on the floor — see
  // CHARGE_NURSE_PAGES below and the allowDesignations props on the
  // /shift-* routes in App.tsx. SUPER_ADMIN is a national role and does
  // not run individual hospital shifts; if explicit fallback authority
  // is ever needed, an admin would impersonate a HOSPITAL_ADMIN, not
  // see shift management as a default sidebar item.
  SUPER_ADMIN: [
    'dashboard', 'admin', 'admin-hospitals', 'admin-users', 'iot-devices',
    'settings', 'notifications', 'profile',
    'audit-trail', 'reports', 'quality', 'prediction',
    'moh-reports', 'governance', 'safety-incidents',
  ],

  // ── Hospital-level administration only ──
  // Manages hospital staff, settings, IoT monitors (devices), reports
  // and audit. Does NOT have access to clinical workflow pages (triage,
  // vitals, alerts, etc.) and does NOT manage daily shifts. Daily shift
  // planning, zone assignment, leave/swap approvals and delegations are
  // owned by the Charge Nurse on the floor (Designation.CHARGE_NURSE).
  // The backend ShiftAssignmentAuthz still permits HOSPITAL_ADMIN as a
  // fallback authority if an endpoint is invoked directly, but those
  // pages are intentionally not surfaced in the admin sidebar.
  HOSPITAL_ADMIN: [
    'dashboard', 'admin', 'admin-users', 'iot-devices', 'admin-beds',
    'settings', 'notifications', 'profile',
    'audit-trail', 'reports', 'quality',
    'safety-incidents', 'med-safety-overrides', 'moh-reports',
  ],

  // ── Full clinical access ──
  DOCTOR: [
    'dashboard', 'patients', 'triage', 'monitoring', 'alerts', 'beds',
    'notifications', 'profile',
    // Clinical tools
    'sepsis', 'fast-track', 'hypoglycemia', 'isolation',
    'pathways', 'med-safety', 'med-safety-overrides', 'icu', 'referral',
    // Documentation & handover
    'documentation', 'handover', 'lab',
    // Reports (own)
    'reports',
    // Self-service shift surface
    'my-schedule',
  ],

  // ── Clinical care ──
  NURSE: [
    'dashboard', 'entry', 'patients', 'triage', 'monitoring', 'alerts', 'beds',
    'notifications', 'profile',
    // Core clinical tools
    'sepsis', 'fast-track', 'hypoglycemia', 'isolation',
    'pathways', 'referral',
    // Documentation & handover
    'documentation', 'handover', 'lab',
    // Safety reporting
    'safety-incidents',
    // Shift planning surfaces — Charge Nurses use these; access is
    // page-level only and the actual mutation endpoints check
    // designation server-side via @shiftAssignmentAuthz. Note that
    // 'swap-approvals' is intentionally NOT in this list — it's a
    // CN-only surface, granted via RoleGuard.allowDesignations on
    // the route, so a regular nurse can't open it by accident.
    'shift-assignment', 'shift-calendar', 'my-schedule',
  ],

  // ── Registration only ──
  REGISTRAR: [
    'dashboard', 'entry', 'patients',
    'notifications', 'profile',
    'referral',
    'my-schedule',
  ],

  // ── Pre-hospital / transport ──
  PARAMEDIC: [
    'dashboard', 'entry', 'patients',
    'notifications', 'profile',
    'handover', 'referral',
    'my-schedule',
  ],

  // ── Lab-focused ──
  LAB_TECHNICIAN: [
    'dashboard', 'patients', 'lab',
    'notifications', 'profile',
    'my-schedule',
  ],

  // ── View-only ──
  READ_ONLY: [
    'dashboard', 'patients', 'profile',
    'audit-trail', 'reports', 'quality', 'med-safety-overrides', 'moh-reports',
  ],
};

/* ─── Feature-level permissions per role ─── */
export const ROLE_FEATURES: Record<UserRole, AppFeature[]> = {
  SUPER_ADMIN: [
    'manage_users', 'manage_settings',
    'view_audit', 'view_reports', 'export_report',
  ],
  HOSPITAL_ADMIN: [
    'manage_users', 'manage_settings',
    'view_audit', 'view_reports', 'view_monitoring', 'export_report',
    'acknowledge_alert',
  ],
  DOCTOR: [
    'override_category', 'acknowledge_alert', 'add_clinical_note',
    'export_report', 'view_reports', 'view_monitoring',
  ],
  NURSE: [
    'register_patient', 'start_triage', 'record_vitals',
    'acknowledge_alert', 'add_clinical_note', 'view_monitoring',
  ],
  REGISTRAR: [
    'register_patient',
  ],
  PARAMEDIC: [
    'register_patient', 'record_vitals',
    'view_monitoring',
  ],
  LAB_TECHNICIAN: [
    'view_reports',
  ],
  READ_ONLY: [
    'view_audit', 'view_reports',
  ],
};

/* ─── Helper functions ─── */

/** Check if a role has access to a given page */
export function canAccessPage(role: UserRole, page: AppPage): boolean {
  return ROLE_PAGES[role].includes(page);
}

/** Check if a role has a given feature permission */
export function hasFeature(role: UserRole, feature: AppFeature): boolean {
  return ROLE_FEATURES[role].includes(feature);
}

/** Get the default landing page for a role */
export function getDefaultPage(_role: UserRole): string {
  return 'dashboard';
}
