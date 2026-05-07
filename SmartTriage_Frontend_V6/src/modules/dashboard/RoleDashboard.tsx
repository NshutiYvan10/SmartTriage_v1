import { useNavigate } from 'react-router-dom';
import {
  Users, Settings, Shield, Building2, Server,
  Activity, BarChart3, UserPlus, AlertTriangle,
  ClipboardList, Stethoscope, Monitor, Bell,
  FileText, ArrowRight,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { usePatientStore } from '@/store/patientStore';
import { ROLE_META, ROLE_PAGES } from '@/types/roles';
import type { UserRole, AppPage } from '@/types/roles';

/* ─── Quick-action card ─── */
interface QuickAction {
  label: string;
  description: string;
  icon: typeof Users;
  route: string;
  color: string;
  iconColor: string;
}

const ActionCard = ({ action, onClick }: { action: QuickAction; onClick: () => void }) => (
  <button
    onClick={onClick}
    className="group bg-white rounded-2xl border border-gray-200 p-5 text-left hover:shadow-lg hover:-translate-y-0.5 transition-all duration-300"
  >
    <div className="flex items-start justify-between mb-3">
      <div className={`w-11 h-11 rounded-xl ${action.color} flex items-center justify-center`}>
        <action.icon className={`w-5 h-5 ${action.iconColor}`} />
      </div>
      <ArrowRight className="w-4 h-4 text-gray-300 group-hover:text-gray-500 group-hover:translate-x-0.5 transition-all duration-300" />
    </div>
    <h3 className="text-sm font-bold text-gray-900 mb-0.5">{action.label}</h3>
    <p className="text-xs text-gray-500 leading-relaxed">{action.description}</p>
  </button>
);

/* ─── Stat card ─── */
const StatCard = ({ label, value, icon: Icon, accent }: {
  label: string; value: string | number; icon: typeof Users; accent: string;
}) => (
  <div className="bg-white rounded-2xl border border-gray-200 p-5">
    <div className="flex items-center gap-3 mb-2">
      <div className={`w-9 h-9 rounded-lg ${accent} flex items-center justify-center`}>
        <Icon className="w-4 h-4 text-white" />
      </div>
      <span className="text-sm font-semibold text-gray-500">{label}</span>
    </div>
    <p className="text-2xl font-bold text-gray-900">{value}</p>
  </div>
);

/* ─── Role-specific quick actions ─── */
function getActionsForRole(role: UserRole): QuickAction[] {
  const base: Record<string, QuickAction> = {
    register: { label: 'Register Patient', description: 'Create a new patient record', icon: UserPlus, route: '/entry', color: 'bg-emerald-50', iconColor: 'text-emerald-600' },
    triage: { label: 'Triage Queue', description: 'View and manage triage queue', icon: Stethoscope, route: '/triage', color: 'bg-cyan-50', iconColor: 'text-cyan-600' },
    patients: { label: 'Patient List', description: 'Browse all registered patients', icon: ClipboardList, route: '/patients', color: 'bg-blue-50', iconColor: 'text-blue-600' },
    monitoring: { label: 'Monitoring', description: 'Real-time patient monitoring', icon: Monitor, route: '/monitoring', color: 'bg-violet-50', iconColor: 'text-violet-600' },
    alerts: { label: 'AI Alerts', description: 'Review active alert notifications', icon: Bell, route: '/alerts', color: 'bg-rose-50', iconColor: 'text-rose-600' },
    audit: { label: 'Audit Trail', description: 'View system activity logs', icon: FileText, route: '/audit-trail', color: 'bg-amber-50', iconColor: 'text-amber-600' },
    reports: { label: 'Reports', description: 'Analytics and report generation', icon: BarChart3, route: '/reports', color: 'bg-indigo-50', iconColor: 'text-indigo-600' },
    settings: { label: 'Settings', description: 'System and user configuration', icon: Settings, route: '/settings', color: 'bg-slate-100', iconColor: 'text-slate-600' },
    hospitals: { label: 'Manage Hospitals', description: 'Create and manage hospitals', icon: Users, route: '/admin/hospitals', color: 'bg-violet-50', iconColor: 'text-violet-600' },
    users: { label: 'Manage Staff', description: 'Create and manage hospital staff', icon: Users, route: '/admin/users', color: 'bg-violet-50', iconColor: 'text-violet-600' },
    governance: { label: 'Governance', description: 'National governance & compliance', icon: Settings, route: '/governance', color: 'bg-indigo-50', iconColor: 'text-indigo-600' },
    lab: { label: 'Lab Orders', description: 'View lab orders and results', icon: Monitor, route: '/lab', color: 'bg-purple-50', iconColor: 'text-purple-600' },
    handover: { label: 'Handover', description: 'Shift handover reports', icon: FileText, route: '/handover', color: 'bg-amber-50', iconColor: 'text-amber-600' },
  };

  switch (role) {
    case 'SUPER_ADMIN':
      // National-level: hospitals, governance, reports, audit
      return [base.hospitals, base.users, base.governance, base.reports, base.audit, base.settings];
    case 'HOSPITAL_ADMIN':
      // Hospital management: staff, patients overview, reports, audit
      return [base.users, base.patients, base.reports, base.audit, base.monitoring, base.settings];
    case 'DOCTOR':
      // Full clinical: patients, triage, monitoring, alerts
      return [base.patients, base.triage, base.monitoring, base.alerts, base.reports];
    case 'NURSE':
      // V29: covers all nurse designations (Charge / Triage / Senior /
      // Staff / Student). Designation-specific tiles can be added later
      // if needed (e.g. Charge Nurse → Shift Planner shortcut).
      return [base.register, base.triage, base.patients, base.monitoring, base.alerts];
    case 'REGISTRAR':
      // Registration only
      return [base.register, base.patients];
    case 'PARAMEDIC':
      // Pre-hospital: register, patients, handover
      return [base.register, base.patients, base.handover];
    case 'LAB_TECHNICIAN':
      // Lab-focused
      return [base.patients, base.lab];
    case 'READ_ONLY':
      // View-only: reports, audit, patients
      return [base.patients, base.audit, base.reports];
    default:
      return [base.patients];
  }
}

/* ─── Page icons for the access list ─── */
const PAGE_ICONS: Partial<Record<AppPage, typeof Users>> = {
  dashboard: Activity,
  entry: UserPlus,
  patients: ClipboardList,
  triage: Stethoscope,
  monitoring: Monitor,
  alerts: Bell,
  'audit-trail': FileText,
  reports: BarChart3,
  settings: Settings,
  notifications: Bell,
  profile: Users,
  admin: Settings,
};

const PAGE_LABELS: Partial<Record<AppPage, string>> = {
  dashboard: 'Dashboard',
  entry: 'Registration',
  patients: 'Patients',
  triage: 'Triage Queue',
  monitoring: 'Monitoring',
  alerts: 'AI Alerts',
  'audit-trail': 'Audit Trail',
  reports: 'Reports',
  settings: 'Settings',
  notifications: 'Notifications',
  profile: 'Profile',
  admin: 'Administration',
};

/* ─── Main component ─── */
export function RoleDashboard() {
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();
  const patients = usePatientStore((s) => s.patients);

  if (!user) return null;

  const meta = ROLE_META[user.role];
  const actions = getActionsForRole(user.role);
  const accessiblePages = ROLE_PAGES[user.role];

  const stats = {
    total: patients.length,
    waiting: patients.filter(p => p.triageStatus === 'WAITING').length,
    inTriage: patients.filter(p => p.triageStatus === 'IN_TRIAGE').length,
    critical: patients.filter(p => p.category === 'RED').length,
  };

  return (
    <div className="min-h-full">
      <div className="p-5 space-y-5">

        {/* ── Welcome Header ── */}
        <div className="bg-white rounded-2xl border border-gray-200 overflow-hidden">
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-white/60 font-medium mb-1">Welcome back,</p>
                <h1 className="text-xl font-bold text-white tracking-tight">{user.fullName}</h1>
              </div>
              <div className="flex items-center gap-3">
                <span className={`inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-bold text-white ${meta.color}`}>
                  <Shield className="w-3.5 h-3.5" />
                  {meta.label}
                </span>
              </div>
            </div>
            <div className="flex flex-wrap items-center gap-x-5 gap-y-1 mt-3 text-sm text-white/50">
              {user.department && (
                <span className="flex items-center gap-1.5">
                  <Building2 className="w-3.5 h-3.5" />
                  {user.department}
                </span>
              )}
              {user.hospital && (
                <span className="flex items-center gap-1.5">
                  <Server className="w-3.5 h-3.5" />
                  {user.hospital}
                </span>
              )}
            </div>
          </div>
          <div className="px-6 py-3 bg-gray-50 border-t border-gray-100">
            <p className="text-xs text-gray-500">{meta.description}</p>
          </div>
        </div>

        {/* ── Quick Stats (for clinical roles) ── */}
        {['HOSPITAL_ADMIN', 'DOCTOR', 'NURSE', 'REGISTRAR', 'PARAMEDIC'].includes(user.role) && (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <StatCard label="Total Patients" value={stats.total} icon={Users} accent="bg-blue-500" />
            <StatCard label="Waiting" value={stats.waiting} icon={Activity} accent="bg-amber-500" />
            <StatCard label="In Triage" value={stats.inTriage} icon={Stethoscope} accent="bg-cyan-500" />
            <StatCard label="Critical" value={stats.critical} icon={AlertTriangle} accent="bg-red-500" />
          </div>
        )}

        {/* ── Quick Actions ── */}
        <div>
          <h2 className="text-sm font-bold text-gray-900 mb-3">Quick Actions</h2>
          <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4">
            {actions.map((action) => (
              <ActionCard
                key={action.route + action.label}
                action={action}
                onClick={() => navigate(action.route)}
              />
            ))}
          </div>
        </div>

        {/* ── Your Access ── */}
        <div className="bg-white rounded-2xl border border-gray-200 p-5">
          <h2 className="text-sm font-bold text-gray-900 mb-3">Your Access</h2>
          <div className="flex flex-wrap gap-2">
            {accessiblePages
              .filter((p) => p !== 'profile' && p !== 'notifications')
              .map((page) => {
                const Icon = PAGE_ICONS[page];
                const label = PAGE_LABELS[page];
                // Skip pages without an icon/label registered — happens for
                // designation-only pages (e.g. shift-planner) that show via
                // the sidebar designation override but don't need a tile here.
                if (!Icon || !label) return null;
                return (
                  <button
                    key={page}
                    onClick={() => navigate(`/${page}`)}
                    className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-gray-50 border border-gray-200 text-xs font-semibold text-gray-700 hover:bg-gray-100 hover:border-gray-300 transition-all duration-200"
                  >
                    <Icon className="w-3.5 h-3.5 text-gray-400" />
                    {label}
                  </button>
                );
              })}
          </div>
        </div>
      </div>
    </div>
  );
}
