import { useState, useMemo, useEffect } from 'react';
import {
  User, Mail, Phone, MapPin, Shield, Clock, Edit3, Camera, Save,
  Award, Briefcase, Calendar, Globe, Lock, Bell, Palette,
  ChevronRight, LogOut, KeyRound, FileText, CheckCircle,
  Crown, MapPin as MapPinIcon, RefreshCw, Loader2, AlertCircle, Sun, Moon,
} from 'lucide-react';
import { useTheme } from '../../hooks/useTheme';
import { useAuthStore } from '../../store/authStore';
import { userApi } from '../../api/users';
import { useMyShift } from '../../hooks/useMyShift';
import { ROLE_META } from '../../types/roles';
import type { UserRole } from '../../types/roles';
import type { EdZone, ShiftFunction, ShiftPeriod } from '../../api/types';

/* Local lookups so the card can label zones / functions without depending on the shift module. */
const ZONE_LABELS: Record<EdZone, { label: string; icon: string }> = {
  RESUS:       { label: 'Resuscitation', icon: '🔴' },
  ACUTE:       { label: 'Acute',         icon: '🟠' },
  GENERAL:     { label: 'General',       icon: '🟡' },
  AMBULATORY:  { label: 'Ambulatory',    icon: '🟢' },
  TRIAGE:      { label: 'Triage',        icon: '🔵' },
  OBSERVATION: { label: 'Observation',   icon: '🟢' },
  ISOLATION:   { label: 'Isolation',     icon: '🟣' },
  PEDIATRIC:   { label: 'Pediatric',     icon: '🩷' },
  NEONATAL:    { label: 'Neonatal',      icon: '👶' },
};

const FUNCTION_LABELS: Record<ShiftFunction, string> = {
  PRIMARY_DOCTOR:     'Primary Doctor',
  SUPERVISING_DOCTOR: 'Supervising Doctor',
  RESIDENT:           'Resident',
  CHARGE_NURSE:       'Charge Nurse',
  TRIAGE_NURSE:       'Triage Nurse',
  ZONE_NURSE:         'Zone Nurse',
};

const PERIOD_META: Record<ShiftPeriod, { label: string; time: string; icon: typeof Sun }> = {
  DAY:   { label: 'Day Shift',   time: '07:00 – 19:00', icon: Sun },
  NIGHT: { label: 'Night Shift', time: '19:00 – 07:00', icon: Moon },
};

interface UserProfile {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  role: string;
  designation: string;
  department: string;
  employeeId: string;
  specialization: string;
  joinDate: string;
  location: string;
  language: string;
  bio: string;
  avatar: string;
}



function buildProfileFromAuth(user: { fullName: string; email: string; role: UserRole; phone?: string; designationLabel?: string; department?: string; hospital?: string }): UserProfile {
  const nameParts = user.fullName.split(' ');
  const firstName = nameParts.slice(0, -1).join(' ') || nameParts[0];
  const lastName = nameParts[nameParts.length - 1];
  const roleMeta = ROLE_META[user.role];

  return {
    firstName,
    lastName,
    email: user.email,
    phone: user.phone ?? '',
    role: roleMeta.label,
    designation: user.designationLabel || 'Not set',
    department: user.department || 'General',
    employeeId: '',
    specialization: '',
    joinDate: '',
    location: user.hospital ? `${user.hospital}, Kigali` : 'Kigali, Rwanda',
    language: '',
    bio: '',
    avatar: '',
  };
}

export function ProfilePage() {
  const { isDark, toggle: toggleTheme, glassCard, glassInner, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const user = useAuthStore((s) => s.user);
  const setUser = useAuthStore((s) => s.setUser);
  const roleMeta = user ? ROLE_META[user.role] : null;

  const activeProfile = useMemo(() => {
    if (!user) return null;
    return buildProfileFromAuth(user);
  }, [user?.id, user?.role, user?.fullName, user?.phone]);

  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [isEditing, setIsEditing] = useState(false);
  const [editedProfile, setEditedProfile] = useState<UserProfile | null>(null);
  const [activeTab, setActiveTab] = useState<'overview' | 'settings' | 'security'>('overview');

  // Sync profile when active user changes
  const displayProfile = profile ?? activeProfile;
  const displayEditProfile = editedProfile ?? activeProfile;

  // Reset local edits when user/role changes
  useEffect(() => {
    setProfile(null);
    setEditedProfile(null);
    setIsEditing(false);
  }, [user?.id, user?.role]);

  // Preferences — wired to REAL mechanisms (no mockups).
  //  • Dark mode → the global theme store (persists per device).
  //  • Critical-alert sound → the flag CriticalAlertNotifier reads
  //    ('smarttriage:critical-mute'; '1' = muted). sessionStorage by design,
  //    so the audible cue for deteriorating patients re-arms each session.
  const [soundOn, setSoundOn] = useState(
    () => sessionStorage.getItem('smarttriage:critical-mute') !== '1',
  );
  const toggleSound = () => {
    setSoundOn((prev) => {
      const next = !prev;
      sessionStorage.setItem('smarttriage:critical-mute', next ? '0' : '1');
      return next;
    });
  };

  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  const handleSave = async () => {
    if (!displayEditProfile || !user) return;
    const firstName = displayEditProfile.firstName.trim();
    const lastName = displayEditProfile.lastName.trim();
    if (!firstName || !lastName) {
      setSaveError('First and last name are required.');
      return;
    }
    setSaving(true);
    setSaveError(null);
    try {
      const phoneNumber = displayEditProfile.phone?.trim() || undefined;
      // Persist to the backend (PUT /users/me/profile).
      await userApi.updateMyProfile({ firstName, lastName, phoneNumber });
      // Reflect the saved values app-wide (header, etc.) and into localStorage so
      // they survive a reload — setUser persists the user.
      setUser({
        ...user,
        fullName: `${firstName} ${lastName}`,
        phone: phoneNumber,
      });
      setProfile(displayEditProfile);
      setIsEditing(false);
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Failed to save profile. Please try again.');
    } finally {
      setSaving(false);
    }
  };

  const handleCancel = () => {
    setEditedProfile(displayProfile);
    setSaveError(null);
    setIsEditing(false);
  };

  // ── Change-password (Security tab) ──
  const [pwCurrent, setPwCurrent] = useState('');
  const [pwNew, setPwNew] = useState('');
  const [pwConfirm, setPwConfirm] = useState('');
  const [pwSaving, setPwSaving] = useState(false);
  const [pwError, setPwError] = useState<string | null>(null);
  const [pwSuccess, setPwSuccess] = useState(false);

  const handleChangePassword = async () => {
    setPwError(null);
    setPwSuccess(false);
    if (!pwCurrent) { setPwError('Enter your current password.'); return; }
    if (pwNew.length < 8) { setPwError('New password must be at least 8 characters.'); return; }
    if (pwNew !== pwConfirm) { setPwError('New password and confirmation do not match.'); return; }
    setPwSaving(true);
    try {
      await userApi.changeMyPassword({ currentPassword: pwCurrent, newPassword: pwNew });
      setPwSuccess(true);
      setPwCurrent('');
      setPwNew('');
      setPwConfirm('');
    } catch (e) {
      setPwError(e instanceof Error ? e.message : 'Failed to change password. Please try again.');
    } finally {
      setPwSaving(false);
    }
  };

  const tabs = [
    { id: 'overview' as const, label: 'Overview', icon: User, description: 'Personal information' },
    { id: 'settings' as const, label: 'Preferences', icon: Bell, description: 'Notification & display' },
    { id: 'security' as const, label: 'Security', icon: Lock, description: 'Password & 2FA' },
  ];

  // Guard: if no user, show nothing (shouldn't happen with auth)
  if (!displayProfile || !user) {
    return (
      <div className="min-h-full flex items-center justify-center">
        <p className={`text-sm ${text.muted}`}>No user profile available.</p>
      </div>
    );
  }

  // Compute avatar initials from current user's full name
  const initials = user.fullName
    .split(' ')
    .filter(Boolean)
    .map((w) => w[0])
    .slice(0, 2)
    .join('')
    .toUpperCase();

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Profile Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          {/* Dark Banner */}
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-6 relative">
            <div className="absolute inset-0 bg-[url('data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMjAwIiBoZWlnaHQ9IjIwMCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48ZGVmcz48cGF0dGVybiBpZD0iYSIgcGF0dGVyblVuaXRzPSJ1c2VyU3BhY2VPblVzZSIgd2lkdGg9IjQwIiBoZWlnaHQ9IjQwIiBwYXR0ZXJuVHJhbnNmb3JtPSJyb3RhdGUoNDUpIj48cmVjdCB3aWR0aD0iMSIgaGVpZ2h0PSI0MCIgZmlsbD0icmdiYSgyNTUsMjU1LDI1NSwwLjA1KSIvPjwvcGF0dGVybj48L2RlZnM+PHJlY3QgZmlsbD0idXJsKCNhKSIgd2lkdGg9IjIwMCIgaGVpZ2h0PSIyMDAiLz48L3N2Zz4=')] opacity-50" />
            <div className="relative flex flex-col sm:flex-row sm:items-end gap-4">
              {/* Avatar */}
              <div className="relative">
                <div className="w-20 h-20 rounded-2xl bg-gradient-to-br from-white/20 to-white/5 border-2 border-white/30 shadow-2xl flex items-center justify-center backdrop-blur-sm">
                  <span className="text-2xl font-bold text-white">{initials}</span>
                </div>
                <button className="absolute -bottom-1 -right-1 w-8 h-8 rounded-full bg-white shadow-lg flex items-center justify-center hover:bg-gray-50 transition-all duration-300 hover:-translate-y-0.5">
                  <Camera className="w-4 h-4 text-gray-600" />
                </button>
              </div>

              {/* Info */}
              <div className="flex-1 sm:mb-1">
                <h1 className="text-xl font-bold text-white tracking-tight">
                  {displayProfile.firstName} {displayProfile.lastName}
                </h1>
                <div className="flex items-center gap-2 mt-1">
                  <p className="text-sm text-white/70 flex items-center gap-1.5 font-medium">
                    <Briefcase className="w-3.5 h-3.5" />
                    {displayProfile.designation !== 'Not set' ? displayProfile.designation : displayProfile.role} · {displayProfile.department}
                  </p>
                  {roleMeta && (
                    <span className={`inline-flex items-center px-2 py-0.5 text-[10px] font-bold rounded-md ${roleMeta.color} text-white uppercase tracking-wide`}>
                      {user.role.replace('_', ' ')}
                    </span>
                  )}
                </div>
              </div>

              {/* Actions */}
              <div className="flex gap-2">
                {isEditing ? (
                  <>
                    <button
                      onClick={handleCancel}
                      className="px-4 py-2.5 text-xs font-bold text-white/80 bg-white/15 hover:bg-white/25 backdrop-blur-sm rounded-xl transition-all duration-300 border border-white/20"
                    >
                      Cancel
                    </button>
                    <button
                      onClick={handleSave}
                      disabled={saving}
                      className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold text-slate-800 bg-white hover:bg-gray-50 rounded-xl transition-all duration-300 shadow-lg hover:-translate-y-0.5 disabled:opacity-60 disabled:cursor-not-allowed"
                    >
                      {saving ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Save className="w-3.5 h-3.5" />}
                      {saving ? 'Saving…' : 'Save Changes'}
                    </button>
                  </>
                ) : (
                  <button
                    onClick={() => setIsEditing(true)}
                    className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold text-white bg-white/15 hover:bg-white/25 backdrop-blur-sm rounded-xl transition-all duration-300 border border-white/20 hover:-translate-y-0.5"
                  >
                    <Edit3 className="w-3.5 h-3.5" />
                    Edit Profile
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* ── Save error ── */}
        {saveError && (
          <div className="rounded-2xl px-5 py-3 flex items-center gap-2 animate-fade-up border-l-4 border-red-500" style={glassInner}>
            <AlertCircle className="w-4 h-4 text-red-500 flex-shrink-0" />
            <span className="text-sm font-semibold text-red-500">{saveError}</span>
          </div>
        )}

        {/* ── Tab Navigation ── */}
        <div className="rounded-2xl p-1.5 animate-fade-up" style={{ ...glassCard, animationDelay: '0.1s' }}>
          <div className="flex items-center gap-1.5">
            {tabs.map((tab) => {
              const Icon = tab.icon;
              const isActive = activeTab === tab.id;
              return (
                <button
                  key={tab.id}
                  onClick={() => setActiveTab(tab.id)}
                  className={`flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-xl text-xs font-semibold transition-all duration-300 ${
                    isActive
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md shadow-slate-800/20'
                      : `${text.body} hover:bg-white/10`
                  }`}
                >
                  <Icon className={`w-3.5 h-3.5 ${isActive ? 'text-white' : text.muted}`} />
                  <span className={`font-bold ${isActive ? 'text-white' : text.heading}`}>{tab.label}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* ── Tab Content ── */}

        {/* Overview Tab */}
        {activeTab === 'overview' && (
          <div className="space-y-4">
            {/* Shift Check-in card */}
            <ShiftCheckInCard />

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-4 animate-fade-up" style={{ animationDelay: '0.3s' }}>
            {/* Personal Information */}
            <div className="lg:col-span-2 rounded-3xl overflow-hidden" style={glassCard}>
              <div className="px-5 py-4" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-cyan-600 to-cyan-500 flex items-center justify-center shadow-sm">
                    <User className="w-4 h-4 text-white" />
                  </div>
                  <div>
                    <h2 className={`text-sm font-bold ${text.heading}`}>Personal Information</h2>
                    <p className={`text-xs ${text.muted} mt-0.5`}>Your professional details</p>
                  </div>
                </div>
              </div>
              <div className="p-5">
                <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
                  {[
                    { label: 'Full Name', value: `${displayProfile.firstName} ${displayProfile.lastName}`, icon: User, field: 'firstName' },
                    { label: 'Email Address', value: displayProfile.email, icon: Mail, field: 'email' },
                    { label: 'Phone Number', value: displayProfile.phone, icon: Phone, field: 'phone' },
                    { label: 'Employee ID', value: displayProfile.employeeId, icon: Award, field: 'employeeId' },
                    { label: 'Specialization', value: displayProfile.specialization, icon: Shield, field: 'specialization' },
                    { label: 'Location', value: displayProfile.location, icon: MapPin, field: 'location' },
                    { label: 'Join Date', value: new Date(displayProfile.joinDate).toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' }), icon: Calendar, field: 'joinDate' },
                    { label: 'Languages', value: displayProfile.language, icon: Globe, field: 'language' },
                  ].map((item) => {
                    const Icon = item.icon;
                    return (
                      <div key={item.label}>
                        <label className={`text-xs font-semibold ${text.muted} flex items-center gap-1.5 mb-2 uppercase tracking-wider`}>
                          <Icon className="w-3.5 h-3.5" />
                          {item.label}
                        </label>
                        {isEditing && item.field !== 'employeeId' && item.field !== 'joinDate' ? (
                          <input
                            type="text"
                            value={(displayEditProfile as any)[item.field]}
                            onChange={(e) =>
                              setEditedProfile((prev) => ({ ...(prev ?? displayProfile!), [item.field]: e.target.value }))
                            }
                            style={glassInner}
                            className={`w-full px-4 py-2.5 text-sm rounded-2xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 font-medium ${text.body}`}
                          />
                        ) : (
                          <p className={`text-sm font-semibold ${text.heading}`}>{item.value}</p>
                        )}
                      </div>
                    );
                  })}
                </div>

                {/* Bio */}
                <div className="mt-5 pt-5" style={{ borderTop: borderStyle }}>
                  <label className={`text-xs font-semibold ${text.muted} flex items-center gap-1.5 mb-2 uppercase tracking-wider`}>
                    <FileText className="w-3.5 h-3.5" />
                    Bio
                  </label>
                  {isEditing ? (
                    <textarea
                      value={displayEditProfile!.bio}
                      onChange={(e) => setEditedProfile((prev) => ({ ...(prev ?? displayProfile!), bio: e.target.value }))}
                      rows={3}
                      style={glassInner}
                      className={`w-full px-4 py-3 text-sm rounded-2xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 font-medium resize-none ${text.body}`}
                    />
                  ) : (
                    <p className={`text-sm ${text.body} leading-relaxed`}>{displayProfile.bio}</p>
                  )}
                </div>
              </div>
            </div>

            {/* Sidebar */}
            <div className="space-y-4">
              {/* Recent Activity */}
              <div className="rounded-3xl overflow-hidden" style={glassCard}>
                <div className="px-5 py-4" style={{ borderBottom: borderStyle }}>
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-emerald-500 to-emerald-600 flex items-center justify-center shadow-sm">
                      <Clock className="w-4 h-4 text-white" />
                    </div>
                    <h2 className={`text-sm font-bold ${text.heading}`}>Recent Activity</h2>
                  </div>
                </div>
                <div className="p-5">
                  <p className={`text-xs ${text.muted} text-center py-4`}>No recent activity</p>
                </div>
              </div>

              {/* Quick Actions */}
              <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
                <div className="mb-4">
                  <h3 className={`text-base font-extrabold ${text.heading} tracking-tight`}>Quick Actions</h3>
                  <p className={`text-xs ${text.body} font-medium mt-0.5`}>Shortcuts & navigation</p>
                </div>
                <div className="space-y-2.5">
                  {[
                    { label: 'Download Activity Report', sublabel: 'Export shift summary', icon: FileText, iconBg: 'rgba(6,182,212,0.12)', iconColor: 'text-cyan-600' },
                    { label: 'Change Password', sublabel: 'Update credentials', icon: KeyRound, iconBg: 'rgba(99,102,241,0.12)', iconColor: 'text-indigo-500' },
                    { label: 'Manage Notifications', sublabel: 'Alert preferences', icon: Bell, iconBg: 'rgba(251,146,60,0.12)', iconColor: 'text-orange-500' },
                    { label: 'Sign Out', sublabel: 'End current session', icon: LogOut, iconBg: 'rgba(239,68,68,0.1)', iconColor: 'text-red-500', danger: true },
                  ].map((item) => {
                    const Icon = item.icon;
                    return (
                      <button
                        key={item.label}
                        className="w-full flex items-center justify-between p-3 rounded-xl hover:-translate-y-1 transition-all duration-400 group cursor-pointer"
                        style={glassInner}
                      >
                        <div className="flex items-center gap-3">
                          <div
                            className="w-10 h-10 rounded-xl flex items-center justify-center group-hover:scale-110 transition-transform duration-400"
                            style={{ backgroundColor: item.iconBg }}
                          >
                            <Icon className={`w-[18px] h-[18px] ${item.iconColor}`} />
                          </div>
                          <div className="text-left">
                            <div className={`text-[13px] font-bold ${item.danger ? 'text-red-500' : text.heading}`}>{item.label}</div>
                            <div className={`text-[11px] ${text.muted} font-medium`}>{item.sublabel}</div>
                          </div>
                        </div>
                        <ChevronRight className={`w-4 h-4 ${item.danger ? 'text-red-400' : text.muted} group-hover:translate-x-1 transition-transform duration-300`} />
                      </button>
                    );
                  })}
                </div>
              </div>
            </div>
            </div>
          </div>
        )}

        {/* Preferences Tab — only real, working, persisted settings. */}
        {activeTab === 'settings' && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 animate-fade-up" style={{ animationDelay: '0.3s' }}>
            {/* Alerts */}
            <div className="rounded-3xl overflow-hidden" style={glassCard}>
              <div className="px-5 py-4" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-rose-500 to-rose-600 flex items-center justify-center shadow-sm">
                    <Bell className="w-4 h-4 text-white" />
                  </div>
                  <div>
                    <h2 className={`text-sm font-bold ${text.heading}`}>Alerts</h2>
                    <p className={`text-xs ${text.muted} mt-0.5`}>Audible cue for deteriorating patients</p>
                  </div>
                </div>
              </div>
              <div className="p-5">
                <SettingToggle
                  label="Critical alert sound"
                  description="Play a tone when a new CRITICAL patient alert arrives. Re-enables each session so a deteriorating patient is never silently missed."
                  enabled={soundOn}
                  onToggle={toggleSound}
                />
              </div>
            </div>

            {/* Appearance */}
            <div className="rounded-3xl overflow-hidden" style={glassCard}>
              <div className="px-5 py-4" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-purple-500 to-purple-600 flex items-center justify-center shadow-sm">
                    <Palette className="w-4 h-4 text-white" />
                  </div>
                  <div>
                    <h2 className={`text-sm font-bold ${text.heading}`}>Appearance</h2>
                    <p className={`text-xs ${text.muted} mt-0.5`}>Display preference for this device</p>
                  </div>
                </div>
              </div>
              <div className="p-5">
                <SettingToggle
                  label="Dark Mode"
                  description="Switch to the dark theme across the application. Applied immediately and remembered on this device."
                  enabled={isDark}
                  onToggle={toggleTheme}
                />
              </div>
            </div>
          </div>
        )}

        {/* Security Tab */}
        {activeTab === 'security' && (
          <div className="max-w-xl animate-fade-up" style={{ animationDelay: '0.3s' }}>
            {/* Change Password */}
            <div className="rounded-3xl overflow-hidden" style={glassCard}>
              <div className="px-5 py-4" style={{ borderBottom: borderStyle }}>
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-slate-800 to-slate-700 flex items-center justify-center shadow-sm">
                    <KeyRound className="w-4 h-4 text-white" />
                  </div>
                  <div>
                    <h2 className={`text-sm font-bold ${text.heading}`}>Change Password</h2>
                    <p className={`text-xs ${text.muted} mt-0.5`}>Keep your account secure</p>
                  </div>
                </div>
              </div>
              <div className="p-5 space-y-4">
                <div>
                  <label className={`text-xs font-semibold ${text.muted} mb-2 block uppercase tracking-wider`}>Current Password</label>
                  <input
                    type="password"
                    placeholder="Enter current password"
                    value={pwCurrent}
                    onChange={(e) => setPwCurrent(e.target.value)}
                    autoComplete="current-password"
                    style={glassInner}
                    className={`w-full px-4 py-3 text-sm rounded-2xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 font-medium ${text.body}`}
                  />
                </div>
                <div>
                  <label className={`text-xs font-semibold ${text.muted} mb-2 block uppercase tracking-wider`}>New Password</label>
                  <input
                    type="password"
                    placeholder="Enter new password (min 8 characters)"
                    value={pwNew}
                    onChange={(e) => setPwNew(e.target.value)}
                    autoComplete="new-password"
                    style={glassInner}
                    className={`w-full px-4 py-3 text-sm rounded-2xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 font-medium ${text.body}`}
                  />
                </div>
                <div>
                  <label className={`text-xs font-semibold ${text.muted} mb-2 block uppercase tracking-wider`}>Confirm New Password</label>
                  <input
                    type="password"
                    placeholder="Confirm new password"
                    value={pwConfirm}
                    onChange={(e) => setPwConfirm(e.target.value)}
                    autoComplete="new-password"
                    style={glassInner}
                    className={`w-full px-4 py-3 text-sm rounded-2xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 font-medium ${text.body}`}
                  />
                </div>
                {pwError && (
                  <div className="flex items-center gap-2 text-xs font-semibold text-red-600">
                    <AlertCircle className="w-3.5 h-3.5 flex-shrink-0" />
                    {pwError}
                  </div>
                )}
                {pwSuccess && (
                  <div className="flex items-center gap-2 text-xs font-semibold text-emerald-600">
                    <CheckCircle className="w-3.5 h-3.5 flex-shrink-0" />
                    Password changed successfully.
                  </div>
                )}
                <button
                  onClick={handleChangePassword}
                  disabled={pwSaving}
                  className="w-full py-3 text-xs font-bold text-white bg-gradient-to-r from-slate-800 to-slate-700 rounded-2xl transition-all duration-300 shadow-lg shadow-slate-800/20 hover:shadow-xl hover:-translate-y-0.5 disabled:opacity-60 disabled:cursor-not-allowed inline-flex items-center justify-center gap-2"
                >
                  {pwSaving && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                  {pwSaving ? 'Updating…' : 'Update Password'}
                </button>
              </div>
            </div>

          </div>
        )}

      </div>
    </div>
  );
}

// ═══════════════════════════════════════════════════════════════
// Shift Check-in Card — shows the user's live shift status
// ═══════════════════════════════════════════════════════════════

function ShiftCheckInCard() {
  const { isDark, glassCard, glassInner, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const { assignment, isLoading, refresh } = useMyShift();
  const [refreshing, setRefreshing] = useState(false);

  const handleRefresh = async () => {
    setRefreshing(true);
    try {
      await refresh();
    } finally {
      setRefreshing(false);
    }
  };

  const isLead = !!(assignment?.isShiftLead && assignment?.active);
  const hasShift = !!(assignment && assignment.active);

  if (isLoading) {
    return (
      <div className="rounded-3xl px-5 py-5 flex items-center gap-3" style={glassCard}>
        <Loader2 className="w-5 h-5 animate-spin text-cyan-500" />
        <p className={`text-sm ${text.body} font-medium`}>Checking your shift assignment…</p>
      </div>
    );
  }

  /* ── Case 1: Not scheduled ── */
  if (!hasShift) {
    return (
      <div className="rounded-3xl overflow-hidden" style={glassCard}>
        <div className="px-5 py-5">
          <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 rounded-2xl flex items-center justify-center" style={glassInner}>
                <AlertCircle className={`w-5 h-5 ${text.muted}`} />
              </div>
              <div>
                <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
                  Shift Check-in
                </p>
                <h3 className={`text-base font-bold ${text.heading}`}>Not scheduled for this shift</h3>
                <p className={`text-[11px] ${text.muted} mt-0.5`}>
                  Your name isn't on today's roster. Contact your shift lead if you believe this is a mistake.
                </p>
              </div>
            </div>
            <button
              onClick={handleRefresh}
              disabled={refreshing}
              style={glassInner}
              className={`inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold ${text.label} rounded-xl transition-all duration-300 disabled:opacity-40`}
            >
              {refreshing ? (
                <Loader2 className="w-3.5 h-3.5 animate-spin" />
              ) : (
                <RefreshCw className="w-3.5 h-3.5" />
              )}
              Refresh
            </button>
          </div>
        </div>
      </div>
    );
  }

  /* ── Case 2: On shift ── */
  const zone = assignment.zone;
  const zoneMeta = ZONE_LABELS[zone];
  const functionLabel = FUNCTION_LABELS[assignment.shiftFunction];
  const periodMeta = PERIOD_META[assignment.shiftPeriod];
  const PeriodIcon = periodMeta.icon;

  return (
    <div
      style={glassCard}
      className={`rounded-3xl overflow-hidden ${
        isLead ? 'ring-2 ring-amber-500/40' : ''
      }`}
    >
      <div
        className={`px-5 py-5 bg-gradient-to-r ${
          isLead
            ? 'from-amber-500/15 via-yellow-400/10 to-orange-500/15'
            : 'from-emerald-500/10 via-green-400/5 to-cyan-500/10'
        }`}
      >
        <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4">
          <div className="flex items-center gap-4">
            <div
              className={`w-12 h-12 rounded-2xl flex items-center justify-center shadow-lg flex-shrink-0 ${
                isLead
                  ? 'bg-gradient-to-br from-amber-500 to-yellow-600 shadow-amber-500/30'
                  : 'bg-gradient-to-br from-emerald-500 to-green-600 shadow-emerald-500/30'
              }`}
            >
              {isLead ? (
                <Crown className="w-6 h-6 text-white" />
              ) : (
                <CheckCircle className="w-6 h-6 text-white" />
              )}
            </div>
            <div className="min-w-0">
              <div className="flex items-center gap-2 mb-0.5">
                <p className={`text-[10px] font-bold uppercase tracking-wider ${text.body}`}>
                  {isLead ? 'Shift-Lead On Duty' : "You're On Duty"}
                </p>
                {isLead && (
                  <span className="inline-flex items-center gap-1 px-1.5 py-0.5 rounded-md bg-amber-500 text-white text-[9px] font-bold uppercase tracking-wider">
                    <Crown className="w-2.5 h-2.5" />
                    Charge
                  </span>
                )}
              </div>
              <h3 className={`text-base font-bold ${text.heading}`}>
                {zoneMeta.icon} {zoneMeta.label} · {functionLabel}
              </h3>
              <div className="flex items-center gap-2 mt-1 flex-wrap">
                <span className={`inline-flex items-center gap-1 text-[11px] font-semibold ${text.body}`}>
                  <PeriodIcon className="w-3 h-3" />
                  {periodMeta.label}
                </span>
                <span className={`text-[10px] ${text.muted}`}>·</span>
                <span className={`text-[11px] font-medium ${text.muted}`}>{periodMeta.time}</span>
                <span className={`text-[10px] ${text.muted}`}>·</span>
                <span className={`text-[11px] font-medium ${text.muted}`}>{assignment.shiftDate}</span>
              </div>
              {isLead && (
                <p className="text-[11px] text-amber-700 font-medium mt-2 leading-relaxed">
                  You hold the shift-lead badge. All Tier-1 escalations route to you until you
                  transfer the badge or the shift ends.
                </p>
              )}
            </div>
          </div>

          <div className="flex items-center gap-2 flex-shrink-0">
            <button
              onClick={handleRefresh}
              disabled={refreshing}
              title="Refresh shift status"
              style={glassInner}
              className="w-10 h-10 rounded-xl flex items-center justify-center transition-all duration-300 disabled:opacity-40"
            >
              {refreshing ? (
                <Loader2 className={`w-4 h-4 animate-spin ${text.muted}`} />
              ) : (
                <RefreshCw className={`w-4 h-4 ${text.muted}`} />
              )}
            </button>
            <button
              className={`inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold rounded-xl transition-all duration-300 shadow-lg ${
                isLead
                  ? 'bg-gradient-to-r from-amber-500 to-yellow-600 text-white shadow-amber-500/30 hover:-translate-y-0.5'
                  : 'bg-gradient-to-r from-emerald-500 to-green-600 text-white shadow-emerald-500/30 hover:-translate-y-0.5'
              }`}
            >
              <CheckCircle className="w-3.5 h-3.5" />
              I'm here
            </button>
          </div>
        </div>

        {/* Tier info strip */}
        <div className="flex items-center gap-3 mt-4 pt-4 flex-wrap" style={{ borderTop: borderStyle }}>
          <div className={`inline-flex items-center gap-1.5 text-[11px] ${text.body}`}>
            <MapPinIcon className="w-3 h-3 text-cyan-500" />
            <span className="font-semibold">Zone {zoneMeta.label}</span> patients routed to you
          </div>
          <span className={`text-[10px] ${text.muted}`}>·</span>
          <div className={`inline-flex items-center gap-1.5 text-[11px] ${text.body}`}>
            <Clock className="w-3 h-3 text-violet-500" />
            Tier-1 alerts escalate in <strong>2 min</strong>
          </div>
        </div>
      </div>
    </div>
  );
}

// ── Premium Setting Toggle ──
interface SettingToggleProps {
  label: string;
  description: string;
  enabled: boolean;
  onToggle: () => void;
}

function SettingToggle({ label, description, enabled, onToggle }: SettingToggleProps) {
  const { text } = useTheme();
  return (
    <div className="flex items-center justify-between py-4">
      <div>
        <p className={`text-sm font-semibold ${text.heading}`}>{label}</p>
        <p className={`text-xs ${text.muted} mt-0.5 leading-relaxed`}>{description}</p>
      </div>
      <button
        onClick={onToggle}
        className={`relative w-12 h-7 rounded-full transition-all duration-300 flex-shrink-0 ml-4 ${
          enabled
            ? 'bg-gradient-to-r from-cyan-600 to-cyan-500 shadow-md shadow-cyan-600/30'
            : 'bg-gray-300 hover:bg-gray-400'
        }`}
      >
        <span
          className={`absolute top-0.5 left-0.5 w-6 h-6 rounded-full bg-white shadow-md transition-all duration-300 flex items-center justify-center ${
            enabled ? 'translate-x-5' : 'translate-x-0'
          }`}
        >
          {enabled && (
            <CheckCircle className="w-3 h-3 text-cyan-600" />
          )}
        </span>
      </button>
    </div>
  );
}
