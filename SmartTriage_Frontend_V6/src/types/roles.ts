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
// V29: TRIAGE_NURSE used to live here. It moved to the Designation enum
// (Designation.TRIAGE_NURSE under Role.NURSE) because a triage nurse is
// still a nurse — "triage" is the function she performs on the unit, not
// a separate profession. To check whether a user is doing triage, look at
// their designation, not their role.

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
 * SUPER_ADMIN    → National-level: hospital management, system governance,
 *                  national reports. Does NOT access day-to-day clinical
 *                  operations or shift management (those are HOSPITAL_ADMIN
 *                  / Charge Nurse responsibilities).
 * HOSPITAL_ADMIN → Hospital management: staff, settings, audit, reports.
 *                  Can view clinical data. Does NOT manage daily shifts —
 *                  shift planning belongs to the Charge Nurse on the floor
 *                  (Designation.CHARGE_NURSE), surfaced by designation
 *                  overrides in App routes / Sidebar.
 * DOCTOR         → Full clinical: patients, triage, all clinical tools,
 *                  monitoring, documentation.
 * NURSE          → Clinical care: patients, triage, vitals, monitoring,
 *                  core clinical tools, documentation. Triage / charge /
 *                  staff specialisation comes from the user's Designation.
 * REGISTRAR      → Registration only: entry, patient list, referrals.
 * PARAMEDIC      → Pre-hospital: registration, handover, basic patient view.
 * LAB_TECHNICIAN → Lab-focused: lab orders/results, patient data for context.
 * READ_ONLY      → View-only: reports, audit, quality metrics.
 *
 * Designation-based overrides (NOT in this matrix) — applied separately:
 *   Designation.CHARGE_NURSE on Role.NURSE → also gains shift-planner,
 *     shift-assignment. See `chargeNursePages()` below and the
 *     allowDesignations props on /shift-* routes in App.tsx.
 */
export const ROLE_PAGES: Record<UserRole, AppPage[]> = {

  // ── National-level administration only ──
  // V29: SUPER_ADMIN no longer carries shift-planner / shift-assignment.
  // National-level admins do not run individual hospital shifts — that's
  // the Charge Nurse's job. SUPER_ADMIN can still impersonate a hospital
  // admin to act if absolutely needed; not a default sidebar item.
  SUPER_ADMIN: [
    'dashboard', 'admin', 'admin-hospitals', 'admin-users', 'iot-devices',
    'settings', 'notifications', 'profile',
    'audit-trail', 'reports', 'quality', 'prediction',
    'moh-reports', 'governance', 'safety-incidents',
  ],

  // ── Hospital-level administration only ──
  // Manages hospital staff, settings, IoT monitors (devices), reports and
  // audit. Does NOT have access to clinical workflow pages (triage, vitals,
  // alerts, etc.) and — V29 — no longer surfaces shift-planner /
  // shift-assignment. Day-to-day shift management is the Charge Nurse's
  // responsibility on the floor; the backend ShiftAssignmentAuthz still
  // permits HOSPITAL_ADMIN as a fallback authority if explicitly invoked.
  HOSPITAL_ADMIN: [
    'dashboard', 'admin', 'admin-users', 'iot-devices', 'admin-beds',
    'settings', 'notifications', 'profile',
    'audit-trail', 'reports', 'quality',
    'safety-incidents', 'moh-reports',
  ],

  // ── Full clinical access ──
  DOCTOR: [
    'dashboard', 'patients', 'triage', 'monitoring', 'alerts', 'beds',
    'notifications', 'profile',
    // Clinical tools
    'sepsis', 'fast-track', 'hypoglycemia', 'isolation',
    'pathways', 'med-safety', 'icu', 'referral',
    // Documentation & handover
    'documentation', 'handover', 'lab',
    // Reports (own)
    'reports',
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
  ],

  // V29: TRIAGE_NURSE used to be its own role with this same set of pages.
  // It is now a Designation under NURSE. Triage nurses inherit the full
  // NURSE page set above — they don't lose access; they gain everything
  // a NURSE has. The triage specialisation is captured by the designation,
  // not by reduced permissions.

  // ── Registration only ──
  REGISTRAR: [
    'dashboard', 'entry', 'patients',
    'notifications', 'profile',
    'referral',
  ],

  // ── Pre-hospital / transport ──
  PARAMEDIC: [
    'dashboard', 'entry', 'patients',
    'notifications', 'profile',
    'handover', 'referral',
  ],

  // ── Lab-focused ──
  LAB_TECHNICIAN: [
    'dashboard', 'patients', 'lab',
    'notifications', 'profile',
  ],

  // ── View-only ──
  READ_ONLY: [
    'dashboard', 'patients', 'profile',
    'audit-trail', 'reports', 'quality', 'moh-reports',
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
  // V29: TRIAGE_NURSE features fold into NURSE — same set above.
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

/* ─── Designation-based page overrides ───
 *
 * Charge Nurse runs the floor in real Rwandan EDs — they own daily shift
 * planning, zone assignment, and the related approval flows. Those pages
 * are NOT in HOSPITAL_ADMIN's matrix above; they're surfaced via this
 * override when the user holds Designation.CHARGE_NURSE on Role.NURSE.
 *
 * Used by Sidebar.tsx to extend a user's visible-pages list, and by
 * App.tsx as the `allowDesignations` prop on the relevant <RoleGuard>
 * routes so the React Router gate also lets them through. Backend
 * authorization (ShiftAssignmentAuthz) already keys off
 * Designation.CHARGE_NURSE — this just unifies the frontend.
 */
export const CHARGE_NURSE_PAGES: AppPage[] = [
  'shift-planner',
  'shift-assignment',
];

/**
 * Returns true if the user (role + designation pair) has access to the
 * given page through either their role permissions OR a designation-based
 * override (currently: Charge Nurse → shift management).
 */
export function canAccessPageWithDesignation(
  role: UserRole | undefined | null,
  designation: string | undefined | null,
  page: AppPage,
): boolean {
  if (!role) return false;
  if ((ROLE_PAGES[role] ?? []).includes(page)) return true;
  if (designation === 'CHARGE_NURSE' && CHARGE_NURSE_PAGES.includes(page)) return true;
  return false;
}

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
