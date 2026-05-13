import { useState, useEffect, useMemo } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Clock, Users, AlertTriangle, Baby, AlertCircle, CheckCircle,
  Filter, ChevronDown, Activity, ArrowUpRight, ArrowDownRight, Minus,
  Search, Bell, X, LogOut, User, Settings,
} from 'lucide-react';
import {
  AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip,
  ResponsiveContainer, ReferenceLine,
} from 'recharts';
import { usePatientStore } from '@/store/patientStore';
import { useAlertStore } from '@/store/alertStore';
import { useVitalStore } from '@/store/vitalStore';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { ROLE_META } from '@/types/roles';
import { Badge } from '@/components/ui/Badge';
import { AlertPanel } from '@/components/ui/AlertPanel';
import { Patient, TriageCategory } from '@/types';
import { getCategoryColor } from '@/utils/tewsCalculator';
import { safeFormatDistanceToNow } from '@/utils/safeDate';
import { useMyShift, getZoneForCategory } from '@/hooks/useMyShift';
import { ShiftStartBanner } from '@/components/ShiftStartBanner';
import { CriticalLabBanner } from '@/modules/lab/CriticalLabBanner';
import { InboundEmsBoard } from '@/modules/ems/InboundEmsBoard';

export function Dashboard() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const roleMeta = user ? ROLE_META[user.role] : null;
  const patients = usePatientStore((state) => state.patients);
  const alerts = useAlertStore((state) => state.getActiveAlerts());
  const acknowledgeAlert = useAlertStore((state) => state.acknowledgeAlert);
  const acknowledgeAlertApi = useAlertStore((state) => state.acknowledgeAlertApi);
  const { zone: myZone, assignment: myShiftAssignment } = useMyShift();

  // Zone label for display
  const ZONE_LABELS: Record<string, string> = {
    RESUS: 'Resuscitation', ACUTE: 'Acute Treatment', GENERAL: 'General / Sub-Acute',
    TRIAGE: 'Triage Station', OBSERVATION: 'Observation', ISOLATION: 'Isolation', PEDIATRIC: 'Pediatric',
  };
  const [showAlerts, setShowAlerts] = useState(true);
  const [statusFilter, setStatusFilter] = useState('all');
  const [categoryFilter, setCategoryFilter] = useState('all');
  const [arrivalFilter, setArrivalFilter] = useState('all');
  const [timeFilter, setTimeFilter] = useState('today');
  const [searchQuery, setSearchQuery] = useState('');
  const [showNotifications, setShowNotifications] = useState(false);
  const [showProfileMenu, setShowProfileMenu] = useState(false);

  // Derived glass styles for dashboard-specific elements
  const glassDropdown = isDark
    ? { background: 'rgba(8, 47, 73, 0.92)', backdropFilter: 'blur(40px)', WebkitBackdropFilter: 'blur(40px)', border: '1px solid rgba(2, 132, 199, 0.28)', boxShadow: '0 20px 60px rgba(0,0,0,0.35), 0 8px 24px rgba(0,0,0,0.2), inset 0 1px 0 rgba(255,255,255,0.05)' }
    : { background: 'rgba(255,255,255,0.92)', backdropFilter: 'blur(40px)', WebkitBackdropFilter: 'blur(40px)', border: '1px solid rgba(255,255,255,0.7)', boxShadow: '0 20px 60px rgba(0,0,0,0.12), 0 8px 24px rgba(0,0,0,0.06), inset 0 1px 0 rgba(255,255,255,0.9)' };

  const glassFilter = isDark
    ? { background: 'rgba(12, 74, 110, 0.10)', backdropFilter: 'blur(24px)', WebkitBackdropFilter: 'blur(24px)', border: '1px solid rgba(2, 132, 199, 0.22)', boxShadow: '0 4px 16px rgba(0,0,0,0.15), inset 0 1px 0 rgba(255,255,255,0.03)' }
    : { background: 'rgba(255,255,255,0.5)', backdropFilter: 'blur(24px)', WebkitBackdropFilter: 'blur(24px)', border: '1px solid rgba(255,255,255,0.6)', boxShadow: '0 4px 16px rgba(0,0,0,0.03), inset 0 1px 0 rgba(255,255,255,0.8)' };

  const tooltipStyle = isDark
    ? { backgroundColor: 'rgba(8, 47, 73, 0.92)', backdropFilter: 'blur(20px)', border: '1px solid rgba(2, 132, 199, 0.22)', borderRadius: 14, boxShadow: '0 8px 32px rgba(0,0,0,0.25)', padding: '10px 14px' }
    : { backgroundColor: 'rgba(255,255,255,0.85)', backdropFilter: 'blur(20px)', border: '1px solid rgba(255,255,255,0.6)', borderRadius: 14, boxShadow: '0 8px 32px rgba(0,0,0,0.08)', padding: '10px 14px' };

  const glassInnerItem = isDark
    ? { background: 'rgba(12,74,110,0.25)', border: '1px solid rgba(2,132,199,0.2)', boxShadow: '0 2px 8px rgba(0,0,0,0.15), 0 1px 2px rgba(0,0,0,0.08)' }
    : { background: 'rgba(255,255,255,0.6)', border: '1px solid rgba(203,213,225,0.4)', boxShadow: '0 2px 8px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.03)' };

  const glassSelectStyle = isDark
    ? { background: 'rgba(12,74,110,0.4)', border: '1px solid rgba(2,132,199,0.25)', color: '#e2e8f0' }
    : { background: 'rgba(241,245,249,0.6)', border: '1px solid rgba(226,232,240,0.5)' };

  const glassSubtleBg = isDark
    ? { background: 'rgba(8,47,73,0.95)' }
    : { background: 'rgba(248,250,252,1)' };

  // Self-healing hydration. The login pre-fetch + useDataInit both
  // try to populate the stores, but in practice the dashboard was
  // sometimes mounting with empty data right after login (the
  // user-reported "blank, need to reload" bug). This effect makes
  // the dashboard self-protecting: if it mounts with an empty
  // patient list AND we have an authenticated user, trigger the
  // fetches directly. Idempotent — extra calls just refresh.
  const fetchActiveVisits = usePatientStore((s) => s.fetchActiveVisits);
  const fetchAlerts = useAlertStore((s) => s.fetchAlerts);
  useEffect(() => {
    if (!user?.hospitalId) return;
    if (patients.length === 0) {
      void fetchActiveVisits(user.hospitalId);
      void fetchAlerts(user.hospitalId);
    }
    // Run on first mount with a logged-in user. Re-renders that
    // change patients.length should NOT re-trigger; that's what
    // useDataInit + the WebSocket subscriptions are for.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [user?.hospitalId]);

  // Zone-aware filter: DOCTOR/NURSE see only their zone's patients;
  // SUPER_ADMIN/HOSPITAL_ADMIN see all patients.
  const displayPatients = useMemo(() => {
    if (!myZone || user?.role === 'SUPER_ADMIN' || user?.role === 'HOSPITAL_ADMIN') return patients;
    return patients.filter(p => {
      if (!p.category) {
        // Untriaged patients only visible to TRIAGE zone staff
        return myZone === 'TRIAGE';
      }
      return getZoneForCategory(p.category) === myZone;
    });
  }, [patients, myZone, user?.role]);

  // Zone-aware alerts: filter to current zone if assigned
  const zoneAlerts = useMemo(() => {
    if (!myZone || user?.role === 'SUPER_ADMIN' || user?.role === 'HOSPITAL_ADMIN') return alerts;
    return alerts.filter(a => !a.targetZone || a.targetZone === myZone);
  }, [alerts, myZone, user?.role]);

  // Calculate statistics from zone-filtered patients
  const stats = {
    total: displayPatients.length,
    waiting: displayPatients.filter((p) => p.triageStatus === 'WAITING').length,
    inTriage: displayPatients.filter((p) => p.triageStatus === 'IN_TRIAGE').length,
    triaged: displayPatients.filter((p) => p.triageStatus === 'TRIAGED').length,
    critical: displayPatients.filter((p) => p.category === 'RED').length,
    pediatric: displayPatients.filter((p) => p.isPediatric).length,
    averageTEWS:
      displayPatients.filter((p) => p.tewsScore !== undefined).length > 0
        ? (
          displayPatients.reduce((sum, p) => sum + (p.tewsScore || 0), 0) /
          displayPatients.filter((p) => p.tewsScore !== undefined).length
        ).toFixed(1)
        : '0.0',
    categoryBreakdown: {
      RED: displayPatients.filter((p) => p.category === 'RED').length,
      ORANGE: displayPatients.filter((p) => p.category === 'ORANGE').length,
      YELLOW: displayPatients.filter((p) => p.category === 'YELLOW').length,
      GREEN: displayPatients.filter((p) => p.category === 'GREEN').length,
      BLUE: displayPatients.filter((p) => p.category === 'BLUE').length,
    },
  };

  const unreadAlerts = zoneAlerts.filter((a) => !a.acknowledgedAt).length;

  return (
    <div className="min-h-full">
      <div className="p-5 space-y-4">

        {/* Inbound ambulance board — self-hides when no inbound runs.
            Shows pre-arrivals + arrived-not-yet-handed-off so the
            charge nurse can prep bays and acknowledge MIST handovers
            in real time. */}
        <InboundEmsBoard />

        {/* Critical lab banner — Phase 1. Self-hides when zero
            unacknowledged criticals so it doesn't burn dashboard
            real estate when the lab is quiet. */}
        <CriticalLabBanner />

        {/* Shift-start briefing — top of dashboard so the doctor sees
            their zone, patient count, and outstanding work the moment
            they land on the page. Self-hides when off-shift, when
            cross-zone admin (no shift assignment), or when the user
            has dismissed it for this specific shift. */}
        <ShiftStartBanner
          assignment={myShiftAssignment}
          patients={displayPatients}
        />

        {/* ── Row 1: Header ── */}
        <div className="relative z-10 flex items-center justify-between animate-fade-in">
          <div className="relative">
            <div className="flex items-center gap-2 mb-1">
              <div className="w-2 h-2 rounded-full bg-emerald-400 animate-pulse" />
              <span className="text-xs font-semibold text-slate-500 uppercase tracking-wider">Live Dashboard</span>
            </div>
            <h1 className="text-2xl font-bold text-slate-800 tracking-tight">
              Welcome back, <span className="text-cyan-600">{user?.fullName ?? 'User'}</span> 👋
            </h1>
            <div className="flex items-center gap-2 mt-0.5">
              <p className="text-slate-500 text-sm font-medium">Real-time patient management & triage overview</p>
              {myZone && (
                <span className={`text-[11px] font-bold px-2.5 py-0.5 rounded-full ${isDark ? 'bg-cyan-500/20 text-cyan-300 border border-cyan-500/30' : 'bg-cyan-50 text-cyan-700 border border-cyan-200'}`}>
                  {ZONE_LABELS[myZone] || myZone} Zone
                </span>
              )}
            </div>
          </div>
          <div className="flex items-center gap-3">
            {/* Search Bar */}
            <div className="relative group">
              <Search className="absolute left-4 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400 transition-colors duration-300 group-focus-within:text-cyan-600" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search patients..."
                className={`pl-10 pr-5 py-2.5 w-80 rounded-full text-sm ${isDark ? 'text-white placeholder-slate-400' : 'text-slate-800 placeholder-slate-400'} focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300`}
                style={glassFilter}
              />
            </div>

            {/* Notification Bell */}
            <div className="relative">
              <button
                onClick={() => setShowNotifications(!showNotifications)}
                className="relative w-10 h-10 flex items-center justify-center rounded-full transition-all duration-300 group hover:-translate-y-0.5"
                style={glassFilter}
              >
                <Bell className="w-[18px] h-[18px] text-slate-600 group-hover:text-cyan-600 group-hover:scale-110 transition-all duration-300" />
              </button>
              {/* Floating count badge */}
              {unreadAlerts > 0 && (
                <span className="absolute -top-1.5 -right-1.5 min-w-[20px] h-5 bg-red-500 text-white text-[10px] font-bold rounded-full flex items-center justify-center px-1 shadow-lg pointer-events-none animate-pulse">
                  {unreadAlerts}
                </span>
              )}

              {/* Notification Dropdown — connected to real alert store */}
              {showNotifications && (
                <div className="absolute right-0 top-full mt-3 w-[440px] rounded-2xl shadow-2xl z-[9999] overflow-hidden animate-scale-in" style={glassDropdown}>
                  <div className={`flex items-center justify-between px-5 py-3.5 border-b ${isDark ? 'border-white/10' : 'border-gray-200/80'}`} style={{ background: isDark ? 'rgba(8, 47, 73, 0.95)' : 'rgba(248,250,252,1)' }}>
                    <span className={`text-sm font-bold tracking-wide ${text.heading}`}>Notifications {unreadAlerts > 0 ? `(${unreadAlerts})` : ''}</span>
                    <button onClick={() => setShowNotifications(false)} className="hover:bg-gray-200/80 rounded-lg p-1.5 transition-all duration-300">
                      <X className="w-4 h-4 text-gray-500" />
                    </button>
                  </div>
                  <div className="max-h-96 overflow-y-auto">
                    {zoneAlerts.length === 0 ? (
                      <div className="px-5 py-8 text-center">
                        <CheckCircle className="w-8 h-8 text-emerald-400 mx-auto mb-2" />
                        <p className={`text-sm font-semibold ${text.heading}`}>No active alerts</p>
                        <p className={`text-xs ${text.body} mt-1`}>All patients in your zone are stable</p>
                      </div>
                    ) : (
                      zoneAlerts.slice(0, 10).map((a, i) => {
                        const isCrit = a.severity === 'CRITICAL' || a.severity === 'HIGH';
                        const isDoctor = a.type === 'DOCTOR_NOTIFICATION';
                        return (
                          <div
                            key={a.id}
                            className={`px-5 py-3.5 border-b ${isDark ? 'border-white/5 hover:bg-white/5' : 'border-gray-100/80 hover:bg-gray-50/80'} cursor-pointer transition-all duration-200`}
                            onClick={() => { if (a.patientId) navigate(`/visit/${a.patientId}`); setShowNotifications(false); }}
                          >
                            <div className="flex items-start gap-3">
                              <div className={`w-8 h-8 rounded-xl flex items-center justify-center flex-shrink-0 shadow-sm ${isCrit ? (isDark ? 'bg-red-500/20' : 'bg-red-50') : (isDark ? 'bg-amber-500/20' : 'bg-amber-50')}`}>
                                {isCrit ? <AlertTriangle className="w-4 h-4 text-red-500" /> : <AlertCircle className="w-4 h-4 text-amber-500" />}
                              </div>
                              <div className="flex-1 min-w-0">
                                <div className="flex items-center gap-2">
                                  <p className={`text-sm font-bold ${isDark ? 'text-white' : 'text-gray-900'} truncate`}>
                                    {a.title || (isDoctor ? 'Doctor Notification' : 'Clinical Alert')}
                                  </p>
                                  {a.escalationTier && a.escalationTier > 1 && (
                                    <span className="text-[9px] font-bold bg-red-500 text-white px-1.5 py-0.5 rounded-md">TIER {a.escalationTier}</span>
                                  )}
                                </div>
                                <p className={`text-xs ${isDark ? 'text-slate-300' : 'text-gray-600'} mt-0.5 leading-relaxed line-clamp-2`}>
                                  {a.patientName && <span className="font-semibold">{a.patientName} — </span>}
                                  {a.message}
                                </p>
                                <div className="flex items-center gap-3 mt-1.5">
                                  <p className={`text-[11px] ${isDark ? 'text-slate-500' : 'text-gray-400'} font-medium`}>
                                    {safeFormatDistanceToNow(a.timestamp, { addSuffix: true })}
                                  </p>
                                  {a.satsTargetMinutes != null && a.satsTargetMinutes > 0 && (
                                    <span className="flex items-center gap-1">
                                      <Clock className="w-3 h-3 text-orange-500" />
                                      <SatsCountdown createdAt={a.timestamp} satsMinutes={a.satsTargetMinutes} />
                                    </span>
                                  )}
                                  {a.targetZone && (
                                    <span className={`text-[10px] font-bold px-1.5 py-0.5 rounded-md ${isDark ? 'bg-cyan-500/20 text-cyan-300' : 'bg-cyan-50 text-cyan-700'}`}>{a.targetZone}</span>
                                  )}
                                </div>
                              </div>
                              {!a.acknowledgedAt && (
                                <button
                                  onClick={(e) => { e.stopPropagation(); acknowledgeAlertApi(a.id); }}
                                  className="text-[10px] font-bold text-cyan-600 hover:text-cyan-500 bg-cyan-50 hover:bg-cyan-100 px-2 py-1 rounded-lg flex-shrink-0 transition-all"
                                >
                                  ACK
                                </button>
                              )}
                            </div>
                          </div>
                        );
                      })
                    )}
                  </div>
                  <div className={`px-5 py-3 border-t ${isDark ? 'border-white/10' : 'border-gray-200/80'}`} style={glassSubtleBg}>
                    <button
                      onClick={() => { setShowNotifications(false); navigate('/alert-dashboard'); }}
                      className={`text-sm font-bold ${isDark ? 'text-cyan-400 hover:text-cyan-300 hover:bg-white/5' : 'text-primary-700 hover:text-primary-900 hover:bg-primary-700/5'} w-full text-center py-2 rounded-xl transition-all duration-300`}
                    >
                      View All Alerts →
                    </button>
                  </div>
                </div>
              )}
            </div>

            {/* Profile Menu */}
            <div className="relative">
              <button
                onClick={() => setShowProfileMenu(!showProfileMenu)}
                className="relative w-10 h-10 flex items-center justify-center rounded-xl overflow-hidden transition-all duration-300 group hover:-translate-y-0.5"
                style={{ border: isDark ? '1px solid rgba(2,132,199,0.3)' : '1px solid rgba(255,255,255,0.6)', boxShadow: isDark ? '0 4px 16px rgba(0,0,0,0.2)' : '0 4px 16px rgba(0,0,0,0.04)' }}
              >
                <img
                  src={`https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(user?.fullName ?? 'User')}&backgroundColor=0369a1&radius=12`}
                  alt={user?.fullName ?? 'User'}
                  className="w-full h-full object-cover"
                />
              </button>

              {/* Profile Dropdown */}
              {showProfileMenu && (
                <div className="absolute right-0 top-full mt-3 w-72 rounded-2xl shadow-2xl z-[9999] overflow-hidden animate-scale-in" style={glassDropdown}>
                  <div className={`px-5 py-4 border-b ${isDark ? 'border-white/10' : 'border-gray-200'}`} style={{ background: 'linear-gradient(135deg, #1e293b 0%, #0f172a 100%)' }}>
                    <div className="flex items-center gap-3">
                      <div className="w-10 h-10 rounded-xl overflow-hidden border border-white/20">
                        <img
                          src={`https://api.dicebear.com/7.x/avataaars/svg?seed=${encodeURIComponent(user?.fullName ?? 'User')}&backgroundColor=0369a1&radius=8`}
                          alt={user?.fullName ?? 'User'}
                          className="w-full h-full object-cover"
                        />
                      </div>
                      <div>
                        <p className="text-sm font-bold text-white">{user?.fullName ?? 'User'}</p>
                        <p className="text-xs text-slate-400 mt-0.5 font-medium">{roleMeta?.label ?? 'Staff'}{user?.department ? ` · ${user.department}` : ''}</p>
                      </div>
                    </div>
                  </div>
                  <div className="py-3">
                    <button
                      onClick={() => { setShowProfileMenu(false); navigate('/profile'); }}
                      className={`w-full px-5 py-3.5 text-left text-sm ${isDark ? 'text-slate-200 hover:bg-white/5' : 'text-gray-700 hover:bg-primary-700/5'} flex items-center gap-3 transition-all duration-300 group`}
                    >
                      <div className={`w-10 h-10 rounded-2xl bg-gradient-to-br ${isDark ? 'from-white/10 to-white/5 group-hover:from-cyan-500/15 group-hover:to-cyan-500/5' : 'from-gray-100 to-gray-50 group-hover:from-primary-700/10 group-hover:to-primary-700/5'} flex items-center justify-center transition-all duration-300 shadow-sm`}>
                        <User className={`w-5 h-5 ${isDark ? 'text-slate-300 group-hover:text-cyan-400' : 'text-gray-600 group-hover:text-primary-700'} transition-colors duration-300`} />
                      </div>
                      <div className="flex-1">
                        <p className={`font-bold ${isDark ? 'text-white' : 'text-gray-900'}`}>My Profile</p>
                        <p className={`text-xs ${isDark ? 'text-slate-400' : 'text-gray-500'} font-medium`}>View and edit profile</p>
                      </div>
                    </button>
                    <button
                      onClick={() => { setShowProfileMenu(false); navigate('/settings'); }}
                      className={`w-full px-5 py-3.5 text-left text-sm ${isDark ? 'text-slate-200 hover:bg-white/5' : 'text-gray-700 hover:bg-primary-700/5'} flex items-center gap-3 transition-all duration-300 group`}
                    >
                      <div className={`w-10 h-10 rounded-2xl bg-gradient-to-br ${isDark ? 'from-white/10 to-white/5 group-hover:from-cyan-500/15 group-hover:to-cyan-500/5' : 'from-gray-100 to-gray-50 group-hover:from-primary-700/10 group-hover:to-primary-700/5'} flex items-center justify-center transition-all duration-300 shadow-sm`}>
                        <Settings className={`w-5 h-5 ${isDark ? 'text-slate-300 group-hover:text-cyan-400' : 'text-gray-600 group-hover:text-primary-700'} transition-colors duration-300`} />
                      </div>
                      <div className="flex-1">
                        <p className={`font-bold ${isDark ? 'text-white' : 'text-gray-900'}`}>Settings</p>
                        <p className={`text-xs ${isDark ? 'text-slate-400' : 'text-gray-500'} font-medium`}>Preferences & config</p>
                      </div>
                    </button>
                  </div>
                  <div className={`border-t ${isDark ? 'border-white/10' : 'border-gray-200/80'}`} style={glassSubtleBg}>
                    <button
                      onClick={() => { setShowProfileMenu(false); navigate('/'); }}
                      className={`w-full px-5 py-3.5 text-left text-sm ${isDark ? 'text-red-400 hover:bg-red-500/10' : 'text-red-600 hover:bg-red-50'} flex items-center gap-3 transition-all duration-300 group`}
                    >
                      <div className={`w-10 h-10 rounded-2xl ${isDark ? 'bg-red-500/15 group-hover:bg-red-500/25' : 'bg-red-50 group-hover:bg-red-100'} flex items-center justify-center transition-all duration-300 shadow-sm`}>
                        <LogOut className={`w-5 h-5 ${isDark ? 'text-red-400' : 'text-red-600'}`} />
                      </div>
                      <div className="flex-1">
                        <p className={`font-bold ${isDark ? 'text-red-400' : 'text-red-600'}`}>Logout</p>
                        <p className={`text-xs ${isDark ? 'text-red-400/70' : 'text-red-500'} font-medium`}>Sign out of your account</p>
                      </div>
                    </button>
                  </div>
                </div>
              )}
            </div>
          </div>
        </div>

        {/* ── Row 2: Filter Bar ── */}
        <div
          className="rounded-full px-4 py-2.5 animate-fade-up stagger-1"
          style={glassFilter}
        >
          <div className="flex items-center gap-3">
            <div className="flex items-center gap-1.5 text-slate-400">
              <Filter className="w-3.5 h-3.5" />
              <span className="text-[11px] font-semibold uppercase tracking-wider">Filters</span>
            </div>

            <div className="w-px h-5 bg-slate-200" />

            {/* Status */}
            <div className="relative">
              <select
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
                className={`appearance-none rounded-full px-3 py-1.5 pr-7 text-[12px] font-semibold ${isDark ? 'text-slate-200' : 'text-slate-700'} focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 cursor-pointer`}
                style={glassSelectStyle}
              >
                <option value="all">All Status</option>
                <option value="WAITING">Waiting</option>
                <option value="IN_TRIAGE">In Triage</option>
                <option value="TRIAGED">Triaged</option>
              </select>
              <ChevronDown className="absolute right-2 top-1/2 -translate-y-1/2 w-3 h-3 text-slate-400 pointer-events-none" />
            </div>

            {/* Category */}
            <div className="relative">
              <select
                value={categoryFilter}
                onChange={(e) => setCategoryFilter(e.target.value)}
                className={`appearance-none rounded-full px-3 py-1.5 pr-7 text-[12px] font-semibold ${isDark ? 'text-slate-200' : 'text-slate-700'} focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 cursor-pointer`}
                style={glassSelectStyle}
              >
                <option value="all">All Categories</option>
                <option value="RED">🔴 RED</option>
                <option value="ORANGE">🟠 ORANGE</option>
                <option value="YELLOW">🟡 YELLOW</option>
                <option value="GREEN">🟢 GREEN</option>
                <option value="BLUE">🔵 BLUE</option>
              </select>
              <ChevronDown className="absolute right-2 top-1/2 -translate-y-1/2 w-3 h-3 text-slate-400 pointer-events-none" />
            </div>

            {/* Arrival Mode */}
            <div className="relative">
              <select
                value={arrivalFilter}
                onChange={(e) => setArrivalFilter(e.target.value)}
                className={`appearance-none rounded-full px-3 py-1.5 pr-7 text-[12px] font-semibold ${isDark ? 'text-slate-200' : 'text-slate-700'} focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 cursor-pointer`}
                style={glassSelectStyle}
              >
                <option value="all">All Modes</option>
                <option value="WALK_IN">Walk-in</option>
                <option value="AMBULANCE">Ambulance</option>
                <option value="REFERRAL">Referral</option>
              </select>
              <ChevronDown className="absolute right-2 top-1/2 -translate-y-1/2 w-3 h-3 text-slate-400 pointer-events-none" />
            </div>

            {/* Time Period */}
            <div className="relative">
              <select
                value={timeFilter}
                onChange={(e) => setTimeFilter(e.target.value)}
                className={`appearance-none rounded-full px-3 py-1.5 pr-7 text-[12px] font-semibold ${isDark ? 'text-slate-200' : 'text-slate-700'} focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 cursor-pointer`}
                style={glassSelectStyle}
              >
                <option value="today">Today</option>
                <option value="week">This Week</option>
                <option value="month">This Month</option>
              </select>
              <ChevronDown className="absolute right-2 top-1/2 -translate-y-1/2 w-3 h-3 text-slate-400 pointer-events-none" />
            </div>

            <div className="flex-1" />

            {/* Compact Apply Button */}
            <button className="w-8 h-8 flex items-center justify-center rounded-full bg-cyan-600 hover:bg-cyan-700 text-white shadow-sm hover:shadow-md transition-all duration-300 hover:-translate-y-0.5">
              <Search className="w-3.5 h-3.5" />
            </button>
          </div>
        </div>

        {/* ── Row 3: 5 Metric Cards ── */}
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-5 gap-4 stagger-children">
          <MetricCard
            title="Total Patients"
            value={stats.total}
            unit="patients"
            subtitle="In department"
            icon={Users}
            trendValue="+12%"
            trendDirection="up"
            accentColor="border-cyan-400"
            accentBg="bg-cyan-50/80"
            accentText="text-cyan-600"
            delay={0}
          />
          <MetricCard
            title="Critical Cases"
            value={stats.critical}
            unit="RED"
            subtitle="Emergency priority"
            icon={AlertTriangle}
            trendValue={stats.critical > 0 ? `${stats.critical} active` : 'None'}
            trendDirection={stats.critical > 0 ? 'up' : 'neutral'}
            accentColor="border-rose-400"
            accentBg="bg-rose-50/80"
            accentText="text-rose-600"
            delay={1}
          />
          <MetricCard
            title="Avg TEWS Score"
            value={stats.averageTEWS}
            unit="score"
            subtitle="Severity index"
            icon={Activity}
            trendValue="-2.1%"
            trendDirection="down"
            accentColor="border-slate-400"
            accentBg="bg-slate-50/80"
            accentText="text-slate-600"
            delay={2}
          />
          <MetricCard
            title="Waiting Queue"
            value={stats.waiting}
            unit="patients"
            subtitle="Pending triage"
            icon={Clock}
            trendValue={`${stats.waiting} pending`}
            trendDirection="neutral"
            accentColor="border-amber-400"
            accentBg="bg-amber-50/80"
            accentText="text-amber-600"
            delay={3}
          />
          <MetricCard
            title="Pediatric"
            value={stats.pediatric}
            unit="children"
            subtitle="Under 15 years"
            icon={Baby}
            trendValue={`${stats.pediatric} active`}
            trendDirection={stats.pediatric > 0 ? 'up' : 'neutral'}
            accentColor="border-emerald-400"
            accentBg="bg-emerald-50/80"
            accentText="text-emerald-600"
            delay={4}
          />
        </div>

        {/* ── Row 4: Chart + Category Distribution (side by side) ── */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">

          {/* Patient Flow Chart — spans 2 cols */}
          <div
            className="lg:col-span-2 rounded-2xl p-5 animate-fade-up"
            style={{
              ...glassCard,
              animationDelay: '0.2s',
            }}
          >
            <div className="flex items-center justify-between mb-4">
              <div>
                <h3 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Patient Flow</h3>
                <p className={`text-xs ${text.body} font-medium mt-0.5`}>Arrivals vs discharges — real-time</p>
              </div>
              <div className="flex items-center gap-4">
                {[
                  { label: 'Arrivals', value: '24', color: 'text-cyan-600' },
                  { label: 'Discharged', value: '18', color: 'text-slate-600' },
                  { label: 'Throughput', value: '75%', color: 'text-emerald-600' },
                ].map((s, i) => (
                  <div
                    key={s.label}
                    className="text-center px-3 py-1.5 rounded-xl"
                    style={glassInnerItem}
                  >
                    <p className={`text-lg font-extrabold ${s.color} animate-number-pop`} style={{ animationDelay: `${i * 0.1}s` }}>{s.value}</p>
                    <p className="text-[10px] text-slate-400 font-semibold uppercase tracking-wider">{s.label}</p>
                  </div>
                ))}
              </div>
            </div>
            <div className="h-[210px]">
              <ResponsiveContainer width="100%" height="100%">
                <AreaChart
                  data={[
                    { time: '06:00', arrivals: 2, discharged: 0, capacity: 12 },
                    { time: '07:00', arrivals: 3, discharged: 1, capacity: 12 },
                    { time: '08:00', arrivals: 5, discharged: 1, capacity: 12 },
                    { time: '09:00', arrivals: 7, discharged: 2, capacity: 12 },
                    { time: '10:00', arrivals: 9, discharged: 3, capacity: 12 },
                    { time: '11:00', arrivals: 11, discharged: 5, capacity: 12 },
                    { time: '12:00', arrivals: 14, discharged: 7, capacity: 12 },
                    { time: '13:00', arrivals: 16, discharged: 9, capacity: 12 },
                    { time: '14:00', arrivals: 18, discharged: 11, capacity: 12 },
                    { time: '15:00', arrivals: 20, discharged: 13, capacity: 12 },
                    { time: '16:00', arrivals: 22, discharged: 15, capacity: 12 },
                    { time: '17:00', arrivals: 23, discharged: 17, capacity: 12 },
                    { time: '18:00', arrivals: 24, discharged: 18, capacity: 12 },
                  ]}
                  margin={{ top: 10, right: 10, left: -10, bottom: 0 }}
                >
                  <defs>
                    <linearGradient id="gradArrivals" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#0284c7" stopOpacity={0.15} />
                      <stop offset="95%" stopColor="#0284c7" stopOpacity={0} />
                    </linearGradient>
                    <linearGradient id="gradDischarged" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" stopColor="#22d3ee" stopOpacity={0.15} />
                      <stop offset="95%" stopColor="#22d3ee" stopOpacity={0} />
                    </linearGradient>
                  </defs>
                  <CartesianGrid strokeDasharray="3 3" stroke="rgba(148,163,184,0.15)" vertical={false} />
                  <XAxis dataKey="time" tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                  <YAxis tick={{ fontSize: 11, fill: '#94a3b8' }} axisLine={false} tickLine={false} />
                  <Tooltip
                    contentStyle={tooltipStyle}
                    labelStyle={{ fontWeight: 700, fontSize: 12, color: isDark ? '#e2e8f0' : '#1e293b', marginBottom: 4 }}
                    itemStyle={{ fontSize: 11, padding: '1px 0', color: isDark ? '#cbd5e1' : undefined }}
                  />
                  <ReferenceLine y={12} stroke="#ef4444" strokeDasharray="6 4" strokeWidth={1.5} label={{ value: 'Capacity', position: 'right', fill: '#ef4444', fontSize: 10, fontWeight: 600 }} />
                  <Area type="monotone" dataKey="arrivals" name="Arrivals" stroke="#0284c7" strokeWidth={2.5} fill="url(#gradArrivals)" dot={false} activeDot={{ r: 5, stroke: '#0284c7', strokeWidth: 2, fill: '#fff' }} />
                  <Area type="monotone" dataKey="discharged" name="Discharged" stroke="#22d3ee" strokeWidth={2.5} fill="url(#gradDischarged)" dot={false} activeDot={{ r: 5, stroke: '#22d3ee', strokeWidth: 2, fill: '#fff' }} />
                </AreaChart>
              </ResponsiveContainer>
            </div>
            <div className="flex items-center justify-center gap-6 mt-3">
              <div className="flex items-center gap-2"><span className="w-2.5 h-2.5 rounded-full bg-sky-600" /><span className="text-[11px] text-slate-500 font-medium">Arrivals</span></div>
              <div className="flex items-center gap-2"><span className="w-2.5 h-2.5 rounded-full bg-cyan-400" /><span className="text-[11px] text-slate-500 font-medium">Discharged</span></div>
              <div className="flex items-center gap-2"><span className="w-5 h-0 border-t-2 border-dashed border-red-400" /><span className="text-[11px] text-slate-500 font-medium">Capacity</span></div>
            </div>
          </div>

          {/* Category Distribution — right side */}
          <div
            className="rounded-2xl p-5 animate-fade-up"
            style={{
              ...glassCard,
              animationDelay: '0.25s',
            }}
          >
            <div className="mb-4">
              <h3 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Triage Categories</h3>
              <p className={`text-xs ${text.body} font-medium mt-0.5`}>Active patient breakdown</p>
            </div>

            <div className="space-y-2.5">
              {(['RED', 'ORANGE', 'YELLOW', 'GREEN', 'BLUE'] as TriageCategory[]).map((cat) => {
                const count = stats.categoryBreakdown[cat];
                const pct = stats.total > 0 ? ((count / stats.total) * 100) : 0;
                return (
                  <div
                    key={cat}
                    className="group flex items-center gap-3 p-2.5 rounded-xl hover:-translate-y-1 transition-all duration-400 cursor-pointer"
                    style={glassInnerItem}
                  >
                    <div
                      className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 group-hover:scale-110 transition-transform duration-400"
                      style={{ backgroundColor: `${getCategoryColor(cat)}18` }}
                    >
                      <span className="text-xs font-extrabold" style={{ color: getCategoryColor(cat) }}>{count}</span>
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center justify-between mb-1">
                        <span className="text-xs font-bold text-slate-700">{cat}</span>
                        <span className="text-[10px] font-semibold text-slate-400">{pct.toFixed(0)}%</span>
                      </div>
                      <div className="w-full h-1.5 rounded-full bg-slate-100 overflow-hidden">
                        <div
                          className="h-full rounded-full transition-all duration-1000 relative overflow-hidden"
                          style={{ width: `${pct}%`, backgroundColor: getCategoryColor(cat) }}
                        >
                          <div className="absolute inset-0 shimmer opacity-40" />
                        </div>
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Combined progress bar */}
            <div className="mt-4 pt-3 border-t" style={{ borderColor: 'rgba(148,163,184,0.15)' }}>
              <div className="flex rounded-full overflow-hidden h-2.5 shadow-inner" style={{ background: isDark ? 'rgba(12,74,110,0.3)' : 'rgba(241,245,249,0.6)' }}>
                {(['RED', 'ORANGE', 'YELLOW', 'GREEN', 'BLUE'] as TriageCategory[]).map((cat) => {
                  const pct = stats.total > 0 ? (stats.categoryBreakdown[cat] / stats.total) * 100 : 0;
                  if (pct === 0) return null;
                  return (
                    <div key={cat} className="h-full transition-all duration-1000 relative overflow-hidden" style={{ width: `${pct}%`, backgroundColor: getCategoryColor(cat) }}>
                      <div className="absolute inset-0 shimmer opacity-30" />
                    </div>
                  );
                })}
              </div>
              <p className="text-[10px] text-slate-400 font-medium text-center mt-2">{stats.total} total patients across all categories</p>
            </div>
          </div>
        </div>

        {/* ── Row 5: Quick Overview + Alerts + Department Summary (same line) ── */}
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">

            {/* Quick Overview Card */}
            <div
              className="rounded-2xl p-5 animate-fade-up"
              style={{
                ...glassCard,
                animationDelay: '0.3s',
              }}
            >
              <div className="mb-4">
                <h3 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Quick Overview</h3>
                <p className={`text-xs ${text.body} font-medium mt-0.5`}>Real-time snapshot</p>
              </div>
              <div className="space-y-2.5">
                {[
                  { label: 'Waiting', sublabel: 'Pending triage', value: stats.waiting, icon: Clock, iconBg: 'rgba(251,146,60,0.12)', iconColor: 'text-orange-500', valueBg: 'bg-orange-50', valueColor: 'text-orange-600', valueBorder: 'border-orange-200' },
                  { label: 'In Triage', sublabel: 'Being assessed', value: stats.inTriage, icon: Activity, iconBg: 'rgba(6,182,212,0.12)', iconColor: 'text-cyan-600', valueBg: 'bg-cyan-50', valueColor: 'text-cyan-700', valueBorder: 'border-cyan-200' },
                  { label: 'Triaged', sublabel: 'Category assigned', value: stats.triaged, icon: CheckCircle, iconBg: 'rgba(34,197,94,0.12)', iconColor: 'text-emerald-500', valueBg: 'bg-emerald-50', valueColor: 'text-emerald-600', valueBorder: 'border-emerald-200' },
                  { label: 'Pediatric', sublabel: 'Age < 15 years', value: stats.pediatric, icon: Baby, iconBg: 'rgba(99,102,241,0.12)', iconColor: 'text-indigo-500', valueBg: 'bg-indigo-50', valueColor: 'text-indigo-600', valueBorder: 'border-indigo-200' },
                ].map((item, i) => {
                  const Icon = item.icon;
                  return (
                    <div
                      key={item.label}
                      className="flex items-center justify-between p-3 rounded-xl hover:-translate-y-1 transition-all duration-400 group cursor-pointer"
                      style={glassInnerItem}
                    >
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-xl flex items-center justify-center group-hover:scale-110 transition-transform duration-400" style={{ backgroundColor: item.iconBg }}>
                          <Icon className={`w-[18px] h-[18px] ${item.iconColor}`} />
                        </div>
                        <div>
                          <div className="text-[13px] font-bold text-slate-800">{item.label}</div>
                          <div className="text-[11px] text-slate-400 font-medium">{item.sublabel}</div>
                        </div>
                      </div>
                      <div className={`min-w-[42px] h-[42px] rounded-xl ${item.valueBg} ${item.valueBorder} border flex items-center justify-center`}>
                        <span className={`text-lg font-extrabold ${item.valueColor} animate-number-pop`} style={{ animationDelay: `${i * 0.1}s` }}>{item.value}</span>
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Active Alerts Card — with SATS countdown timers */}
            <div
              className="rounded-2xl p-5 animate-fade-up"
              style={{
                ...glassCard,
                animationDelay: '0.35s',
              }}
            >
              <div className="flex items-center justify-between mb-4">
                <div>
                  <h3 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Active Alerts</h3>
                  <p className={`text-xs ${text.body} font-medium mt-0.5`}>
                    {myZone ? `${ZONE_LABELS[myZone] || myZone} zone` : 'AI monitoring signals'}
                  </p>
                </div>
                {zoneAlerts.length > 0 && (
                  <span className="text-[11px] font-bold bg-red-500 text-white px-2.5 py-1 rounded-full shadow-sm animate-pulse">{zoneAlerts.length}</span>
                )}
              </div>
              <div className="space-y-2">
                {zoneAlerts.slice(0, 4).map((alert) => (
                  <div
                    key={alert.id}
                    className="p-3 rounded-xl border-l-[3px] hover:-translate-y-1 transition-all duration-400 cursor-pointer group"
                    style={{
                      ...glassInnerItem,
                      borderLeftColor: alert.severity === 'CRITICAL' ? '#ef4444' : alert.severity === 'HIGH' ? '#f97316' : '#eab308',
                      borderLeftWidth: '3px',
                    }}
                    onClick={() => alert.patientId && navigate(`/visit/${alert.patientId}`)}
                  >
                    <div className="flex items-start gap-2.5">
                      {alert.severity === 'CRITICAL' ? (
                        <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: 'rgba(239,68,68,0.1)' }}>
                          <AlertTriangle className="w-3.5 h-3.5 text-red-500" />
                        </div>
                      ) : (
                        <div className="w-7 h-7 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: 'rgba(249,115,22,0.1)' }}>
                          <AlertCircle className="w-3.5 h-3.5 text-orange-500" />
                        </div>
                      )}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2">
                          <p className="text-[13px] font-semibold text-slate-800 leading-snug truncate">{alert.title || alert.message}</p>
                          {alert.escalationTier && alert.escalationTier > 1 && (
                            <span className="text-[8px] font-bold bg-red-500 text-white px-1 py-0.5 rounded">T{alert.escalationTier}</span>
                          )}
                        </div>
                        {alert.patientName && (
                          <p className="text-[11px] text-slate-500 mt-0.5 truncate">{alert.patientName} {alert.visitNumber ? `— ${alert.visitNumber}` : ''}</p>
                        )}
                        <div className="flex items-center gap-2 mt-1">
                          <p className="text-[11px] text-slate-400">{safeFormatDistanceToNow(alert.timestamp, { addSuffix: true })}</p>
                          {/* SATS Countdown Timer */}
                          {alert.satsTargetMinutes != null && alert.satsTargetMinutes > 0 && (
                            <span className="flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-orange-50 border border-orange-200">
                              <Clock className="w-3 h-3 text-orange-500" />
                              <SatsCountdown createdAt={alert.timestamp} satsMinutes={alert.satsTargetMinutes} />
                            </span>
                          )}
                        </div>
                      </div>
                      <div className="flex flex-col items-end gap-1">
                        <span className={`text-[9px] font-bold px-1.5 py-0.5 rounded-md flex-shrink-0 ${
                          alert.severity === 'CRITICAL' ? 'bg-red-50 text-red-600' :
                          alert.severity === 'HIGH' ? 'bg-orange-50 text-orange-600' :
                          'bg-yellow-50 text-yellow-600'
                        }`}>{alert.severity}</span>
                        {!alert.acknowledgedAt && (
                          <button
                            onClick={(e) => { e.stopPropagation(); acknowledgeAlertApi(alert.id); }}
                            className="text-[9px] font-bold text-cyan-600 hover:text-white hover:bg-cyan-600 bg-cyan-50 px-1.5 py-0.5 rounded-md transition-all"
                          >
                            ACK
                          </button>
                        )}
                      </div>
                    </div>
                  </div>
                ))}
                {zoneAlerts.length === 0 && (
                  <div className="text-center py-8">
                    <div className="w-12 h-12 rounded-xl flex items-center justify-center mx-auto mb-3" style={{ backgroundColor: 'rgba(34,197,94,0.1)' }}>
                      <CheckCircle className="w-6 h-6 text-emerald-400" />
                    </div>
                    <p className="text-sm text-slate-500 font-semibold">No active alerts</p>
                    <p className="text-[11px] text-slate-400 mt-1">
                      {myZone ? `All patients in ${ZONE_LABELS[myZone] || myZone} are stable` : 'All patients are stable'}
                    </p>
                  </div>
                )}
              </div>
              {zoneAlerts.length > 0 && (
                <button
                  onClick={() => navigate('/alert-dashboard')}
                  className={`mt-3 w-full text-center py-2 rounded-xl text-[12px] font-bold transition-all ${isDark ? 'text-cyan-300 hover:bg-white/5' : 'text-cyan-700 hover:bg-cyan-50'}`}
                >
                  View all {zoneAlerts.length} alert{zoneAlerts.length === 1 ? '' : 's'} →
                </button>
              )}
            </div>

            {/* Department Summary Card */}
            <div
              className="rounded-2xl p-5 animate-fade-up"
              style={{
                ...glassCard,
                animationDelay: '0.4s',
              }}
            >
              <div className="mb-4">
                <h3 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Department Summary</h3>
                <p className={`text-xs ${text.body} font-medium mt-0.5`}>Today's overview</p>
              </div>
              <div className="space-y-3">
                {[
                  { label: 'Ambulance Arrivals', value: displayPatients.filter((p) => p.arrivalMode === 'AMBULANCE').length },
                  { label: 'Walk-in Patients', value: displayPatients.filter((p) => p.arrivalMode === 'WALK_IN').length },
                  { label: 'Average TEWS', value: stats.averageTEWS },
                  { label: 'Pediatric Patients', value: stats.pediatric },
                ].map((item) => (
                  <div key={item.label} className="flex items-center justify-between">
                    <div className="flex items-center gap-2.5">
                      <div className="w-1.5 h-1.5 rounded-full bg-slate-300" />
                      <span className="text-[13px] text-slate-500 font-medium">{item.label}</span>
                    </div>
                    <div className="flex items-center gap-2">
                      <div className="flex-1 border-b border-dashed border-slate-200 min-w-[40px]" />
                      <span className="text-[14px] font-bold text-slate-800 tabular-nums">{item.value}</span>
                    </div>
                  </div>
                ))}
              </div>
            </div>
        </div>

        {/* Alert Panel */}
        {showAlerts && alerts.length > 0 && (
          <AlertPanel
            alerts={alerts}
            onAcknowledge={(id, comment) => acknowledgeAlert(id, 'DR001', comment)}
            onClose={() => setShowAlerts(false)}
          />
        )}
      </div>
    </div>
  );
}

/* ════════════════════════════════════════════
   Helper Components
   ════════════════════════════════════════════ */

/**
 * SatsCountdown — Live countdown timer for SATS target wait times.
 * ORANGE patients have a 10-minute target; RED is immediate (0 min).
 * Shows MM:SS counting down, turns red + "OVERDUE" when time expires.
 */
function SatsCountdown({ createdAt, satsMinutes }: { createdAt: Date; satsMinutes: number }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(id);
  }, []);

  const deadlineMs = new Date(createdAt).getTime() + satsMinutes * 60_000;
  const remainingMs = Math.max(0, deadlineMs - now);
  const remainingMin = Math.floor(remainingMs / 60_000);
  const remainingSec = Math.floor((remainingMs % 60_000) / 1000);
  const isExpired = remainingMs <= 0;
  const isUrgent = !isExpired && remainingMs < 2 * 60_000; // Less than 2 min

  if (isExpired) {
    const overdueMs = now - deadlineMs;
    const overdueMin = Math.floor(overdueMs / 60_000);
    return (
      <span className="text-[11px] font-bold tabular-nums text-red-500 animate-pulse">
        OVERDUE +{overdueMin}m
      </span>
    );
  }

  return (
    <span className={`text-[11px] font-bold tabular-nums ${isUrgent ? 'text-red-500 animate-pulse' : 'text-orange-600'}`}>
      {remainingMin}:{remainingSec.toString().padStart(2, '0')}
    </span>
  );
}

interface MetricCardProps {
  title: string;
  value: number | string;
  unit: string;
  subtitle: string;
  icon: any;
  trendValue?: string;
  trendDirection?: 'up' | 'down' | 'neutral';
  accentColor?: string;
  accentBg?: string;
  accentText?: string;
  delay?: number;
}

function MetricCard({ title, value, unit, subtitle, icon: Icon, trendValue, trendDirection, accentColor = 'bg-cyan-600', accentBg = 'bg-cyan-50/80', accentText = 'text-cyan-600', delay = 0 }: MetricCardProps) {
  const { glassCard, isDark, text: themeText } = useTheme();

  // Dark-mode-aware accent icon colors
  const darkAccentMap: Record<string, { bg: string; text: string }> = {
    'text-cyan-600':    { bg: 'rgba(6,182,212,0.18)',   text: '#22d3ee' },
    'text-rose-600':    { bg: 'rgba(244,63,94,0.18)',    text: '#fb7185' },
    'text-slate-600':   { bg: 'rgba(148,163,184,0.15)',  text: '#94a3b8' },
    'text-amber-600':   { bg: 'rgba(245,158,11,0.18)',   text: '#fbbf24' },
    'text-emerald-600': { bg: 'rgba(16,185,129,0.18)',   text: '#34d399' },
  };
  const darkAccent = isDark ? darkAccentMap[accentText] : null;

  const trendColors = {
    up: { bg: isDark ? 'rgba(16,185,129,0.15)' : 'rgba(16,185,129,0.1)', text: isDark ? 'text-emerald-400' : 'text-emerald-600', icon: ArrowUpRight },
    down: { bg: isDark ? 'rgba(244,63,94,0.15)' : 'rgba(244,63,94,0.1)', text: isDark ? 'text-rose-400' : 'text-rose-600', icon: ArrowDownRight },
    neutral: { bg: isDark ? 'rgba(148,163,184,0.12)' : 'rgba(148,163,184,0.1)', text: isDark ? 'text-slate-400' : 'text-slate-500', icon: Minus },
  };
  const trend = trendDirection ? trendColors[trendDirection] : null;
  const TrendIcon = trend?.icon;

  return (
    <div
      className={`relative rounded-2xl group hover:-translate-y-2 transition-all duration-500 cursor-default border ${accentColor}`}
      style={glassCard}
    >
      <div className="p-5">
        {/* Top row: Title + Icon */}
        <div className="flex items-start justify-between mb-5">
          <p className={`text-[13px] font-semibold ${themeText.body} tracking-wide`}>{title}</p>
          <div
            className={`w-10 h-10 rounded-xl flex items-center justify-center group-hover:scale-110 transition-transform duration-500 ${!isDark ? accentBg : ''}`}
            style={{ boxShadow: '0 2px 8px rgba(0,0,0,0.04)', ...(darkAccent ? { backgroundColor: darkAccent.bg } : {}) }}
          >
            <Icon className={`w-[18px] h-[18px] ${!isDark ? accentText : ''}`} style={darkAccent ? { color: darkAccent.text } : {}} />
          </div>
        </div>

        {/* Value row */}
        <div className="flex items-end justify-between">
          <div>
            <span className={`text-[28px] font-extrabold ${isDark ? 'text-white' : 'text-slate-900'} leading-none tracking-tight animate-number-pop`} style={{ animationDelay: `${delay * 0.1}s` }}>{value}</span>
            <p className={`text-[11px] ${themeText.muted} font-medium mt-1.5`}>{subtitle}</p>
          </div>
          {trendValue && trend && TrendIcon && (
            <div className="inline-flex items-center gap-0.5 px-2 py-1 rounded-lg" style={{ background: trend.bg }}>
              <TrendIcon className={`w-3 h-3 ${trend.text}`} />
              <span className={`text-[11px] font-bold ${trend.text}`}>{trendValue}</span>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

