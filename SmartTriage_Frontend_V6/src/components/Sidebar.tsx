import { useState, useEffect } from 'react';
import {
  LayoutDashboard,
  UserPlus,
  BellRing,
  BarChart3,
  Stethoscope,
  Monitor,
  ChevronRight,
  ChevronLeft,
  Sun,
  Moon,
  ClipboardList,
  ScrollText,
  Settings,
  Shield,
  Building2,
  Users,
  Cpu,
  ShieldAlert,
  LogOut,
  CalendarClock,
  CalendarDays,
  HeartPulse,
  Thermometer,
  Zap,
  Droplets,
  ShieldCheck,
  FileText,
  Pill,
  FlaskConical,
  Siren,
  Route,
  BedDouble,
  ArrowRightLeft,
  ClipboardCheck,
  TrendingUp,
  FileBarChart,
  Scale,
} from 'lucide-react';
import { useThemeStore } from '@/store/themeStore';
import { useAuthStore } from '@/store/authStore';
import { useAlertStore } from '@/store/alertStore';
import { canAccessPage, ROLE_META } from '@/types/roles';
import type { AppPage } from '@/types/roles';
import { useMyShift } from '@/hooks/useMyShift';
import { useNavigate } from 'react-router-dom';

interface SidebarProps {
  currentView: string;
  onNavigate: (view: string) => void;
  onCollapse?: () => void;
  onExpand?: () => void;
  isExpanded?: boolean;
}

export function Sidebar({ currentView, onNavigate, onCollapse, onExpand, isExpanded: parentIsExpanded }: SidebarProps) {
  const [isExpanded, setIsExpanded] = useState(parentIsExpanded ?? false);
  const [hoveredItem, setHoveredItem] = useState<string | null>(null);
  const isDarkMode = useThemeStore((s) => s.isDark);
  const toggleDarkMode = useThemeStore((s) => s.toggle);
  const user = useAuthStore((s) => s.user);
  const logout = useAuthStore((s) => s.logout);
  const sidebarNavigate = useNavigate();
  const userRole = (user?.role && user.role in ROLE_META) ? user.role : 'NURSE';
  const roleMeta = ROLE_META[userRole];
  // V44+ off-duty signal — drives the "On Leave" badge below.
  const { isOnApprovedLeave } = useMyShift();

  // Live count of unacknowledged alerts — drives the "AI Alerts" badge.
  // Previously the badge was hardcoded "2" which lied about system state;
  // a real ED needs the count to reflect what's actually pending.
  const unackAlertCount = useAlertStore((s) =>
    s.alerts.filter((a) => !a.acknowledged).length,
  );
  const criticalUnackCount = useAlertStore((s) =>
    s.alerts.filter((a) => !a.acknowledged && a.severity === 'CRITICAL').length,
  );

  useEffect(() => {
    if (parentIsExpanded !== undefined) {
      setIsExpanded(parentIsExpanded);
    }
  }, [parentIsExpanded]);

  // Sections: Navigation, Triage, Shift Management, Clinical Tools,
  //           Lab & Docs, Analytics, Administration, System
  const allSections = [
    {
      label: 'Navigation',
      items: [
        { id: 'dashboard', label: 'Dashboard', icon: LayoutDashboard, pageId: 'dashboard' as AppPage },
        { id: 'entry', label: 'Registration', icon: UserPlus, pageId: 'entry' as AppPage },
        { id: 'patients', label: 'Patients', icon: ClipboardList, pageId: 'patients' as AppPage },
      ],
    },
    {
      label: 'Triage',
      items: [
        { id: 'triage', label: 'Triage Queue', icon: Stethoscope, pageId: 'triage' as AppPage },
        { id: 'doctor-workspace', label: 'My Patients', icon: HeartPulse, pageId: 'triage' as AppPage },
        { id: 'monitoring', label: 'Monitoring', icon: Monitor, pageId: 'monitoring' as AppPage },
        { id: 'beds', label: 'Bed Management', icon: BedDouble, pageId: 'beds' as AppPage },
        { id: 'iot-devices', label: 'IoT Devices', icon: Cpu, pageId: 'iot-devices' as AppPage },
      ],
    },
    {
      // ── Shift Management ────────────────────────────────────────
      // All shift-related screens live in one logical section so they
      // are easy to find. Per-item visibility is then controlled by
      // ROLE_PAGES + the CHARGE_NURSE designation override (cnPages
      // below). Ordered self-service → operational → approvals.
      label: 'Shift Management',
      items: [
        { id: 'my-schedule',     label: 'My Schedule',     icon: CalendarClock,   pageId: 'my-schedule'     as AppPage },
        { id: 'shift-calendar',  label: 'Shift Calendar',  icon: CalendarDays,    pageId: 'shift-calendar'  as AppPage },
        { id: 'shift-planner',   label: 'Shift Templates', icon: CalendarDays,    pageId: 'shift-planner'   as AppPage },
        { id: 'zone-transfers',  label: 'Zone Transfers',  icon: ArrowRightLeft,  pageId: 'shift-assignment' as AppPage },
        { id: 'swap-approvals',  label: 'Swap Approvals',  icon: ClipboardCheck,  pageId: 'swap-approvals'  as AppPage },
        { id: 'leave-approvals', label: 'Leave Approvals', icon: ClipboardCheck,  pageId: 'leave-approvals' as AppPage },
        { id: 'delegations',     label: 'Delegations',     icon: ShieldCheck,     pageId: 'delegations'     as AppPage },
      ],
    },
    {
      label: 'Clinical Tools',
      items: [
        { id: 'sepsis', label: 'Sepsis Screening', icon: Thermometer, pageId: 'sepsis' as AppPage },
        { id: 'fast-track', label: 'Fast-Track', icon: Zap, pageId: 'fast-track' as AppPage },
        { id: 'hypoglycemia', label: 'Hypoglycemia', icon: Droplets, pageId: 'hypoglycemia' as AppPage },
        { id: 'isolation', label: 'Isolation', icon: ShieldCheck, pageId: 'isolation' as AppPage },
        { id: 'pathways', label: 'Pathways', icon: Route, pageId: 'pathways' as AppPage },
        { id: 'med-safety', label: 'Med Safety', icon: Pill, pageId: 'med-safety' as AppPage },
        { id: 'icu', label: 'ICU Escalation', icon: BedDouble, pageId: 'icu' as AppPage },
        { id: 'referral', label: 'Referrals', icon: ArrowRightLeft, pageId: 'referral' as AppPage },
      ],
    },
    {
      label: 'Lab & Docs',
      items: [
        { id: 'lab', label: 'Lab Orders', icon: FlaskConical, pageId: 'lab' as AppPage },
        { id: 'ems', label: 'Siren', icon: Siren, pageId: 'ems' as AppPage },
        { id: 'documentation', label: 'Documentation', icon: FileText, pageId: 'documentation' as AppPage },
        { id: 'handover', label: 'Handover', icon: ClipboardCheck, pageId: 'handover' as AppPage },
      ],
    },
    {
      label: 'Analytics',
      items: [
        {
          id: 'alerts', label: 'AI Alerts', icon: BellRing, pageId: 'alerts' as AppPage,
          // Live unack count, with red ring when at least one CRITICAL is
          // pending so the sidebar reflects actual urgency.
          badge: unackAlertCount > 0 ? String(unackAlertCount) : undefined,
          badgeColor: criticalUnackCount > 0 ? 'bg-rose-500 animate-pulse' : 'bg-amber-500',
        },
        { id: 'alert-dashboard', label: 'Alert Center', icon: ShieldAlert, pageId: 'alerts' as AppPage },
        { id: 'med-safety/overrides', label: 'Override Audit', icon: Pill, pageId: 'med-safety-overrides' as AppPage },
        { id: 'quality', label: 'Quality Metrics', icon: BarChart3, pageId: 'quality' as AppPage },
        { id: 'prediction', label: 'Surge Prediction', icon: TrendingUp, pageId: 'prediction' as AppPage },
        { id: 'audit-trail', label: 'Audit Trail', icon: ScrollText, pageId: 'audit-trail' as AppPage },
        { id: 'reports', label: 'Reports', icon: BarChart3, pageId: 'reports' as AppPage },
      ],
    },
    {
      label: 'Administration',
      items: [
        { id: 'admin/hospitals', label: 'Hospitals', icon: Building2, pageId: 'admin-hospitals' as AppPage },
        { id: 'admin/users', label: 'Users', icon: Users, pageId: 'admin-users' as AppPage },
        { id: 'admin/beds', label: 'Bed Inventory', icon: BedDouble, pageId: 'admin-beds' as AppPage },
        // Shift management items moved to their own "Shift Management"
        // section above so charge-nurse and admin shift surfaces are
        // grouped together and visually consistent.
        { id: 'safety-incidents', label: 'Safety Incidents', icon: ShieldAlert, pageId: 'safety-incidents' as AppPage },
        { id: 'moh-reports', label: 'MoH Reports', icon: FileBarChart, pageId: 'moh-reports' as AppPage },
        { id: 'governance', label: 'Governance', icon: Scale, pageId: 'governance' as AppPage },
      ],
    },
    {
      label: 'System',
      items: [
        { id: 'settings', label: 'Settings', icon: Settings, pageId: 'settings' as AppPage },
      ],
    },
  ];

  // ─── Per-role access matrix for the Shift Management section ───
  // The sidebar surfaces three classes of shift items:
  //
  //   1. Self-service (every staff member who works shifts):
  //      my-schedule (your own roster) and shift-calendar (read the
  //      team week, no edits). Visibility from ROLE_PAGES on the
  //      user's role.
  //
  //   2. Charge-Nurse-only (the unit-management surfaces):
  //      shift-assignment (Shift Zones — also exposes zone reassignment
  //      and shift-lead badge transfer, abuse risk if regular nurses
  //      could change others' zones), shift-planner (rota templates),
  //      swap-approvals, leave-approvals, delegations. Granted via
  //      Designation.CHARGE_NURSE only — never via Role. Hospital
  //      admins retain backend fallback authority for emergencies via
  //      ShiftAssignmentAuthz, but the sidebar does not surface these
  //      by default.
  //
  //   3. Charge-Nurse + Hospital-Admin: zone-transfers — Hospital
  //      Admin needs cross-zone transfer visibility for governance.
  //      SUPER_ADMIN is intentionally excluded: super-admin is a
  //      system-level role (multi-hospital configuration, governance,
  //      MoH reporting), not an operational floor role; pending
  //      zone transfers are floor-level concerns the on-site
  //      Hospital Admin owns.
  const isChargeNurse = user?.designation === 'CHARGE_NURSE';
  const isHospitalAdmin = userRole === 'HOSPITAL_ADMIN';

  // CN-only sidebar items — NEVER fall through to the user's
  // ROLE_PAGES grant. Required: CHARGE_NURSE designation.
  const chargeNurseOnly = new Set([
    'shift-calendar',     // Interactive calendar — single planning surface for the CN
    'shift-planner',      // Rota templates (renamed from "Shift Planner" → "Shift Templates")
    'swap-approvals',     // Approve / decline swap requests
    'leave-approvals',    // Approve / decline leave requests
    'delegations',        // Configure CN authority delegations
  ]);

  // CN-or-Hospital-Admin sidebar items.
  // SUPER_ADMIN is NOT included — super admin is the system role, not
  // an operational floor role. Pending zone transfers are floor-level.
  const chargeNurseOrHospitalAdmin = new Set(['zone-transfers']);

  // Filter sections based on role permissions
  const sections = allSections
    .map((section) => ({
      ...section,
      items: section.items.filter((item) => {
        // Charge-nurse-only — admins do NOT see these by default.
        if (chargeNurseOnly.has(item.id)) {
          return isChargeNurse;
        }
        // Charge Nurse + Hospital Admin — Zone Transfers governance.
        if (chargeNurseOrHospitalAdmin.has(item.id)) {
          return isChargeNurse || isHospitalAdmin;
        }
        // Doctor Workspace only for DOCTOR
        if (item.id === 'doctor-workspace') return userRole === 'DOCTOR';
        return canAccessPage(userRole, item.pageId);
      }),
    }))
    .filter((section) => section.items.length > 0);

  const handleMenuItemClick = (id: string) => {
    onNavigate(id);
  };

  const handleLogout = () => {
    logout();
    sidebarNavigate('/login');
  };

  const handleExpandClick = () => {
    setIsExpanded(true);
    if (onExpand) onExpand();
  };

  const handleToggleCollapse = () => {
    setIsExpanded(false);
    if (onCollapse) onCollapse();
  };

  // ── COLLAPSED SIDEBAR ──
  if (!isExpanded) {
    return (
      <aside
        className="fixed left-4 top-4 bottom-4 z-50 w-[72px] flex flex-col items-center py-5 rounded-2xl transition-all duration-500 ease-out"
        style={{
          background: 'linear-gradient(180deg, #1e293b 0%, #0f172a 100%)',
          boxShadow: '0 20px 60px -15px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(255,255,255,0.05)',
        }}
        role="navigation"
        aria-label="Main navigation"
      >
        {/* Expand Arrow — edge-mounted tab, aligned with divider */}
        <button
          className="absolute -right-3 top-[78px] z-[60] w-6 h-6 flex items-center justify-center rounded-full bg-slate-800 border border-white/10 hover:border-cyan-400/40 hover:bg-slate-700 shadow-lg shadow-black/30 transition-all duration-300 group"
          aria-label="Expand sidebar"
          type="button"
          onClick={handleExpandClick}
        >
          <ChevronRight className="w-3.5 h-3.5 text-slate-400 group-hover:text-cyan-400 transition-all duration-300" />
        </button>

        {/* Logo */}
        <div
          className="flex items-center justify-center mb-6"
          aria-label="SmartTriage"
        >
          <div className="w-11 h-11 rounded-xl flex items-center justify-center bg-gradient-to-br from-cyan-500/20 to-cyan-600/10 border border-cyan-400/20">
            <img
              src="/Logo.png"
              alt="SmartTriage Logo"
              className="w-8 h-8 object-contain animate-bounce-gentle"
            />
          </div>
        </div>

        {/* Divider */}
        <div className="w-8 h-px bg-gradient-to-r from-transparent via-white/15 to-transparent mb-4" />

        {/* Navigation Icons */}
        <nav className="flex-1 flex flex-col items-center gap-2 overflow-y-auto overflow-x-hidden scrollbar-thin px-3 w-full">
          {sections.map((section, sIdx) => (
            <div key={section.label} className="w-full flex flex-col items-center gap-2">
              {sIdx > 0 && (
                <div className="w-7 h-px bg-gradient-to-r from-transparent via-white/10 to-transparent mb-0.5 mt-1" />
              )}
              {section.items.map((item) => {
                const Icon = item.icon;
                const isActive = currentView === item.id;

                return (
                  <div key={item.id} className="relative w-full flex flex-col items-center">
                    <button
                      className={`relative w-12 h-12 flex items-center justify-center rounded-xl transition-all duration-300 group ${
                        isActive
                          ? ''
                          : 'hover:bg-white/10'
                      }`}
                      onClick={() => handleMenuItemClick(item.id)}
                      onMouseEnter={() => setHoveredItem(item.id)}
                      onMouseLeave={() => setHoveredItem(null)}
                      aria-label={item.label}
                      type="button"
                    >
                      <Icon className={`w-5 h-5 transition-all duration-300 ${
                        isActive ? 'text-cyan-400' : 'text-slate-300 group-hover:text-white'
                      }`} />
                      
                      {/* Badge */}
                      {item.badge && (
                        <span className={`absolute -top-1.5 -right-1.5 w-5 h-5 text-[10px] font-bold rounded-full ${item.badgeColor || 'bg-cyan-500'} text-white flex items-center justify-center ring-2 ring-[#1e293b]`}>
                          {item.badge}
                        </span>
                      )}
                    </button>

                    {/* Tooltip */}
                    <div className={`absolute left-[68px] top-1/2 -translate-y-1/2 z-[60] px-3.5 py-2 rounded-lg whitespace-nowrap pointer-events-none transition-all duration-200 ${
                      hoveredItem === item.id ? 'opacity-100 translate-x-0' : 'opacity-0 -translate-x-2'
                    }`}
                      style={{ background: '#0f172a', border: '1px solid rgba(255,255,255,0.12)', boxShadow: '0 8px 30px rgba(0,0,0,0.5)' }}
                    >
                      <span className="text-white text-[13px] font-semibold">{item.label}</span>
                      {item.badge && (
                        <span className={`ml-2 ${item.badgeColor || 'bg-cyan-500'} text-white text-[10px] font-bold px-1.5 py-0.5 rounded-full`}>
                          {item.badge}
                        </span>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          ))}
        </nav>

        {/* Footer */}
        <div className="flex flex-col items-center gap-2 w-full px-3 mt-2">
          <div className="w-7 h-px bg-gradient-to-r from-transparent via-white/10 to-transparent mb-1" />
          
          {/* Theme Toggle */}
          <button
            className="w-12 h-12 flex items-center justify-center rounded-xl transition-all duration-500 group bg-white/[0.04] hover:bg-white/10"
            aria-label="Toggle theme"
            type="button"
            onClick={toggleDarkMode}
          >
            {isDarkMode ? (
              <Moon className="w-5 h-5 text-amber-300 group-hover:text-amber-200 transition-all duration-300" />
            ) : (
              <Sun className="w-5 h-5 text-slate-300 group-hover:text-amber-300 transition-all duration-300" />
            )}
          </button>

          <button
            className="w-12 h-12 flex items-center justify-center rounded-xl overflow-hidden border border-white/10 hover:border-cyan-400/30 transition-all duration-300 shadow-lg shadow-black/20"
            aria-label="User profile"
            type="button"
            onClick={() => onNavigate('profile')}
          >
            <img
              src={`https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(user?.fullName || 'User')}&backgroundColor=0369a1&radius=12`}
              alt={user?.fullName || 'User'}
              className="w-full h-full object-cover"
            />
          </button>

          {/* Logout */}
          <button
            className="w-12 h-12 flex items-center justify-center rounded-xl transition-all duration-300 group hover:bg-red-500/10"
            aria-label="Logout"
            type="button"
            onClick={handleLogout}
          >
            <LogOut className="w-5 h-5 text-slate-400 group-hover:text-red-400 transition-all duration-300" />
          </button>
        </div>
      </aside>
    );
  }

  // ── EXPANDED SIDEBAR ──
  return (
    <aside
      className="fixed left-4 top-4 bottom-4 z-50 w-64 flex flex-col rounded-2xl transition-all duration-500 ease-out"
      style={{
        background: 'linear-gradient(180deg, #1e293b 0%, #0f172a 100%)',
        boxShadow: '0 20px 60px -15px rgba(0, 0, 0, 0.4), 0 0 0 1px rgba(255,255,255,0.05)',
      }}
      role="navigation"
      aria-label="Main navigation"
    >
      {/* Collapse Arrow — edge-mounted tab, aligned with divider */}
      <button
        className="absolute -right-3 top-[78px] z-[60] w-6 h-6 flex items-center justify-center rounded-full bg-slate-800 border border-white/10 hover:border-cyan-400/40 hover:bg-slate-700 shadow-lg shadow-black/30 transition-all duration-300 group"
        aria-label="Collapse sidebar"
        type="button"
        onClick={handleToggleCollapse}
      >
        <ChevronLeft className="w-3.5 h-3.5 text-slate-400 group-hover:text-cyan-400 transition-all duration-300" />
      </button>

      {/* ── Header ── */}
      <header className="w-full px-5 pt-5 pb-4 flex-shrink-0">
        <div className="flex items-center gap-3">
          <div className="w-11 h-11 rounded-xl flex items-center justify-center bg-gradient-to-br from-cyan-500/20 to-cyan-600/10 border border-cyan-400/20 flex-shrink-0">
            <img
              src="/Logo.png"
              alt="SmartTriage Logo"
              className="w-8 h-8 object-contain animate-bounce-gentle"
            />
          </div>
          <div className="flex flex-col items-start">
            <h1 className="font-bold text-[15px] tracking-tight text-white leading-tight">
              Smart<span className="text-cyan-400">Triage</span>
            </h1>
            <p className="text-slate-500 text-[11px] font-medium">Healthcare Platform</p>
          </div>
        </div>
      </header>

      {/* Divider */}
      <div className="mx-5 h-px bg-gradient-to-r from-transparent via-white/10 to-transparent" />

      {/* ── Navigation Sections ── */}
      <nav className="flex-1 px-3 pb-2 flex flex-col gap-1 overflow-y-auto scrollbar-thin">
        {sections.map((section, sIdx) => (
          <div key={section.label}>
            {/* Section label */}
            <div className={`px-2 ${sIdx === 0 ? 'pt-4' : 'pt-3'} pb-1`}>
              {sIdx > 0 && (
                <div className="h-px bg-gradient-to-r from-white/8 via-white/5 to-transparent mb-2" />
              )}
              <span className="text-[10px] font-bold uppercase tracking-[0.18em] text-slate-500">{section.label}</span>
            </div>

            {/* Section items */}
            <div className="flex flex-col gap-1">
              {section.items.map((item) => {
                const isActive = currentView === item.id;
                const Icon = item.icon;

                return (
                  <div key={item.id} className="flex flex-col">
                    <button
                      className={`relative w-full h-11 flex items-center gap-3 px-3 rounded-xl transition-all duration-300 group ${
                        isActive
                          ? 'bg-cyan-500/10 text-white'
                          : 'hover:bg-white/[0.04] text-slate-400 hover:text-white'
                      }`}
                      onClick={() => handleMenuItemClick(item.id)}
                      aria-current={isActive ? 'page' : undefined}
                      type="button"
                    >
                      {isActive && (
                        <div className="absolute left-0 w-[3px] h-6 rounded-r-full bg-cyan-400" />
                      )}
                      <Icon className={`w-[18px] h-[18px] flex-shrink-0 transition-all duration-300 ${
                        isActive
                          ? 'text-cyan-400'
                          : 'text-slate-300 group-hover:text-white'
                      }`} />
                      <span className={`flex-1 text-[13px] font-semibold text-left ${
                        isActive ? 'text-white' : 'text-slate-300 group-hover:text-white'
                      }`}>
                        {item.label}
                      </span>
                      {item.badge && (
                        <span className={`text-[10px] font-bold ${item.badgeColor || 'bg-cyan-500'} text-white px-2 py-0.5 rounded-md min-w-[22px] text-center`}>
                          {item.badge}
                        </span>
                      )}
                    </button>
                  </div>
                );
              })}
            </div>
          </div>
        ))}
      </nav>

      {/* ── Footer ── */}
      <footer className="w-full px-3 pb-4 flex-shrink-0 space-y-1.5">
        <div className="px-2 pb-1">
          <div className="h-px bg-gradient-to-r from-white/8 via-white/5 to-transparent" />
          <span className="text-[10px] font-bold uppercase tracking-[0.18em] text-slate-500 mt-2 block">Account</span>
        </div>
        
        {/* Theme Toggle */}
        <button
          onClick={toggleDarkMode}
          className="relative w-full h-11 flex items-center gap-3 px-3 rounded-xl transition-all duration-300 group hover:bg-white/[0.04] text-slate-400 hover:text-white"
          type="button"
        >
          {isDarkMode ? (
            <Moon className="w-[18px] h-[18px] flex-shrink-0 text-amber-300 transition-all duration-500" />
          ) : (
            <Sun className="w-[18px] h-[18px] flex-shrink-0 text-slate-300 group-hover:text-amber-300 transition-all duration-500" />
          )}
          <span className="text-[13px] font-semibold text-slate-300 group-hover:text-white">
            {isDarkMode ? 'Dark Mode' : 'Light Mode'}
          </span>
        </button>

        {/* User Profile Card */}
        <button
          onClick={() => onNavigate('profile')}
          className="w-full flex items-center gap-3 px-3 py-2.5 rounded-xl bg-white/[0.04] border border-white/[0.06] hover:bg-white/[0.08] hover:border-white/10 transition-all duration-300 group"
          type="button"
        >
          <div className="w-9 h-9 rounded-xl flex-shrink-0 overflow-hidden shadow-lg shadow-cyan-500/20 group-hover:shadow-cyan-500/30 transition-all duration-300 border border-white/10">
            <img
              src={`https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(user?.fullName || 'User')}&backgroundColor=0369a1&radius=8`}
              alt={user?.fullName || 'User'}
              className="w-full h-full object-cover"
            />
          </div>
          <div className="flex flex-col flex-1 min-w-0 text-left">
            <span className="font-semibold text-white text-[13px] truncate leading-tight flex items-center gap-1.5">
              {user?.fullName || 'User'}
              {/* V44+ off-duty badge — visible whenever the authenticated
                  user has an APPROVED leave row covering today. Backend
                  canAssign denies their shift-management actions; this
                  is the matching visual cue. */}
              {isOnApprovedLeave && (
                <span
                  className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-full
                             bg-amber-500/25 border border-amber-400/60 text-amber-200
                             text-[9px] font-bold uppercase tracking-wider flex-shrink-0"
                  title="You have an approved leave row covering today. Shift-management actions are blocked while on leave."
                >
                  On Leave
                </span>
              )}
            </span>
            <span className="text-slate-400 text-[11px] truncate leading-tight flex items-center gap-1">
              <Shield className="w-3 h-3" />
              {user?.designationLabel || roleMeta.label}
            </span>
          </div>
          <ChevronRight className="w-4 h-4 text-slate-500 group-hover:text-slate-300 transition-all duration-300 flex-shrink-0" />
        </button>

        {/* Logout */}
        <button
          onClick={handleLogout}
          className="w-full h-11 flex items-center gap-3 px-3 rounded-xl transition-all duration-300 group hover:bg-red-500/10 text-slate-400 hover:text-red-400"
          type="button"
        >
          <LogOut className="w-[18px] h-[18px] flex-shrink-0 transition-all duration-300" />
          <span className="text-[13px] font-semibold transition-all duration-300">
            Logout
          </span>
        </button>
      </footer>
    </aside>
  );
}
