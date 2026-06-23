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
  /** Doctor-side aggregate "Investigations I ordered" view (Workflow 2
   *  refinement). Read-only — doctors track their orders but don't
   *  manage the lab queue. */
  | 'investigations'
  | 'med-queue'
  /** V67 — zone medication board: due/overdue doses, PRN quick-give,
   *  infusions, high-alert approvals. The nurse's main dose surface. */
  | 'med-board'
  | 'ems'
  | 'pathways'
  | 'icu'
  | 'safety-incidents'
  | 'handover'
  | 'quality'
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
 * REGISTRAR    → Registration only: entry, patient list.
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
    'audit-trail', 'reports', 'quality',
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
    // Shift management — READ-ONLY governance view. The editor UI
    // suppresses mutate controls when role === 'HOSPITAL_ADMIN'; the
    // backend ShiftAssignmentAuthz.canAssign denies mutation attempts
    // for defence-in-depth (see canViewShift vs canAssign).
    'shift-planner', 'shift-calendar', 'shift-assignment',
    'swap-approvals', 'leave-approvals', 'delegations',
  ],

  // ── Full clinical access ──
  // Workflow 2 refinement: doctors no longer manage the Lab inbox
  // (that's a tech surface). They get a read-only aggregate
  // "Investigations" view of every order they placed, grouped by
  // status. The visit-detail Investigations tab continues to work
  // for per-patient ordering + drill-down.
  DOCTOR: [
    'dashboard', 'patients', 'triage', 'monitoring', 'alerts', 'beds',
    'notifications', 'profile',
    // Clinical tools
    'sepsis', 'fast-track', 'hypoglycemia', 'isolation',
    'pathways', 'med-safety', 'med-safety-overrides', 'icu',
    // Documentation & handover (Lab inbox removed — see 'investigations').
    'documentation', 'handover', 'ems',
    // Aggregate "my investigations" view (Workflow 2 refinement).
    'investigations',
    // Medication administration queue (Workflow 3) — doctors can see
    // the queue too because they often need to verify a colleague's
    // STAT order landed and is being acted on. The separation-of-
    // duties backend check still prevents them administering their
    // own prescriptions.
    'med-queue',
    // V67 — dose-level medication board (scheduled/PRN/infusions).
    'med-board',
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
    'pathways',
    // Documentation & handover
    'documentation', 'handover', 'lab', 'ems',
    // Medication administration queue (Workflow 3) — the main
    // surface for the nurse: every PRESCRIBED med across the
    // hospital, STAT first, real-time push.
    'med-queue',
    // V67 — dose-level medication board (scheduled/PRN/infusions),
    // zone-scoped with real-time due/overdue push.
    'med-board',
    // Safety reporting
    'safety-incidents',
    // Self-service shift surfaces only. A regular nurse sees their own
    // schedule and can read the team calendar — they CANNOT see the
    // Shift Zones board (which exposes zone reassignment + shift-lead
    // badge transfer; abuse risk if every nurse could change someone
    // else's zone). 'shift-assignment' is gated to Charge Nurse via
    // designation override in the Sidebar + RoleGuard. Same for
    // shift-planner / swap-approvals / leave-approvals / delegations
    // — explicitly NOT in this list.
    'shift-calendar', 'my-schedule',
  ],

  // ── Registration only ──
  // No 'my-schedule' / 'shift-calendar' — the registration desk
  // operates on its own departmental rota, not the Charge Nurse's
  // daily ED roster. The ShiftFunction enum has no REGISTRAR value
  // (see common/enums/ShiftFunction.java), so the page would show
  // an empty schedule and only mislead the user.
  REGISTRAR: [
    'dashboard', 'entry', 'patients',
    'notifications', 'profile',
  ],

  // ── Pre-hospital / transport ──
  // No 'my-schedule' for now — there is no PARAMEDIC ShiftFunction
  // in the shift module today, so the page would render empty. EMS
  // crews in real Rwandan practice DO work 24/7 rotations, but
  // their roster is currently managed outside SmartTriage. Re-add
  // this entry the moment a PARAMEDIC ShiftFunction is introduced.
  PARAMEDIC: [
    'dashboard', 'ems', 'entry', 'patients',
    'notifications', 'profile',
    'handover',
  ],

  // ── Lab-focused ──
  // No 'my-schedule' — lab technicians work fixed-hour departmental
  // shifts, not the rotating Charge-Nurse-managed ED roster. The
  // ShiftFunction enum has no LAB_TECHNICIAN value, so the page
  // would show an empty schedule.
  LAB_TECHNICIAN: [
    'dashboard', 'patients', 'lab',
    'notifications', 'profile',
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
