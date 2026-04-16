import { useState } from 'react';
import {
  User, Mail, Phone, MapPin, Shield, Clock, Edit3, Camera, Save,
  Award, Briefcase, Calendar, Globe, Lock, Bell, Palette,
  ChevronRight, LogOut, KeyRound, FileText, Activity,
} from 'lucide-react';

interface UserProfile {
  firstName: string;
  lastName: string;
  email: string;
  phone: string;
  role: string;
  department: string;
  employeeId: string;
  specialization: string;
  joinDate: string;
  location: string;
  language: string;
  bio: string;
  avatar: string;
}

const defaultProfile: UserProfile = {
  firstName: 'Dr. Admin',
  lastName: 'Nkurunziza',
  email: 'admin.nkurunziza@smarttriage.rw',
  phone: '+250 788 123 456',
  role: 'Emergency Physician',
  department: 'Emergency Department',
  employeeId: 'EMP-2024-001',
  specialization: 'Emergency Medicine & Trauma',
  joinDate: '2023-01-15',
  location: 'CHUK Hospital, Kigali',
  language: 'English, Kinyarwanda, French',
  bio: 'Senior emergency physician with 12+ years of experience in trauma care and emergency triage. Lead developer of the SmartTriage AI-assisted triage protocol at CHUK Hospital.',
  avatar: '',
};

interface SettingToggleProps {
  label: string;
  description: string;
  enabled: boolean;
  onToggle: () => void;
}

function SettingToggle({ label, description, enabled, onToggle }: SettingToggleProps) {
  return (
    <div className="flex items-center justify-between py-3">
      <div>
        <p className="text-sm font-medium text-gray-900">{label}</p>
        <p className="text-xs text-gray-500 mt-0.5">{description}</p>
      </div>
      <button
        onClick={onToggle}
        className={`relative w-11 h-6 rounded-full transition-colors ${
          enabled ? 'bg-cyan-600' : 'bg-gray-300'
        }`}
      >
        <span
          className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white shadow-sm transition-transform ${
            enabled ? 'translate-x-5' : 'translate-x-0'
          }`}
        />
      </button>
    </div>
  );
}

export function ProfilePage() {
  const [profile, setProfile] = useState<UserProfile>(defaultProfile);
  const [isEditing, setIsEditing] = useState(false);
  const [editedProfile, setEditedProfile] = useState<UserProfile>(defaultProfile);
  const [activeTab, setActiveTab] = useState<'overview' | 'settings' | 'security'>('overview');

  // Settings state
  const [emailNotifs, setEmailNotifs] = useState(true);
  const [pushNotifs, setPushNotifs] = useState(true);
  const [soundAlerts, setSoundAlerts] = useState(false);
  const [criticalOnly, setCriticalOnly] = useState(false);
  const [darkMode, setDarkMode] = useState(false);
  const [compactView, setCompactView] = useState(false);

  const handleSave = () => {
    setProfile(editedProfile);
    setIsEditing(false);
  };

  const handleCancel = () => {
    setEditedProfile(profile);
    setIsEditing(false);
  };

  const tabs = [
    { id: 'overview' as const, label: 'Overview', icon: User },
    { id: 'settings' as const, label: 'Preferences', icon: Bell },
    { id: 'security' as const, label: 'Security', icon: Lock },
  ];

  const statsCards = [
    { label: 'Patients Triaged', value: '1,247', icon: Activity, iconBg: 'bg-gradient-to-br from-slate-800 to-slate-700' },
    { label: 'Shift Hours', value: '2,340', icon: Clock, iconBg: 'bg-gradient-to-br from-emerald-500 to-emerald-600' },
    { label: 'Alerts Handled', value: '89', icon: Shield, iconBg: 'bg-gradient-to-br from-amber-500 to-amber-600' },
    { label: 'Team Members', value: '12', icon: Briefcase, iconBg: 'bg-gradient-to-br from-blue-500 to-blue-600' },
  ];

  return (
    <div className="p-4 max-w-6xl mx-auto space-y-4">
      {/* Profile Header Card */}
      <div className="bg-white rounded-2xl border border-gray-200 shadow-sm overflow-hidden">
        {/* Banner */}
        <div className="h-24 bg-gradient-to-r from-slate-800 via-slate-700 to-cyan-600 relative">
          <div className="absolute inset-0 bg-[url('data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMjAwIiBoZWlnaHQ9IjIwMCIgeG1sbnM9Imh0dHA6Ly93d3cudzMub3JnLzIwMDAvc3ZnIj48ZGVmcz48cGF0dGVybiBpZD0iYSIgcGF0dGVyblVuaXRzPSJ1c2VyU3BhY2VPblVzZSIgd2lkdGg9IjQwIiBoZWlnaHQ9IjQwIiBwYXR0ZXJuVHJhbnNmb3JtPSJyb3RhdGUoNDUpIj48cmVjdCB3aWR0aD0iMSIgaGVpZ2h0PSI0MCIgZmlsbD0icmdiYSgyNTUsMjU1LDI1NSwwLjA1KSIvPjwvcGF0dGVybj48L2RlZnM+PHJlY3QgZmlsbD0idXJsKCNhKSIgd2lkdGg9IjIwMCIgaGVpZ2h0PSIyMDAiLz48L3N2Zz4=')] opacity-50" />
        </div>

        {/* Profile Info */}
        <div className="px-6 pb-6 -mt-12 relative">
          <div className="flex flex-col sm:flex-row sm:items-end gap-4">
            {/* Avatar */}
            <div className="relative">
              <div className="w-20 h-20 rounded-2xl bg-gradient-to-br from-slate-800 to-cyan-500 border-4 border-white shadow-lg flex items-center justify-center">
                <span className="text-2xl font-bold text-white">DA</span>
              </div>
              <button className="absolute -bottom-1 -right-1 w-8 h-8 rounded-full bg-white border border-gray-200 shadow-sm flex items-center justify-center hover:bg-gray-50 transition-colors">
                <Camera className="w-4 h-4 text-gray-500" />
              </button>
            </div>

            {/* Name & Role */}
            <div className="flex-1 sm:mb-1">
              <h1 className="text-xl font-bold text-gray-900">
                {profile.firstName} {profile.lastName}
              </h1>
              <p className="text-sm text-gray-500 flex items-center gap-1.5 mt-0.5">
                <Briefcase className="w-3.5 h-3.5" />
                {profile.role} · {profile.department}
              </p>
            </div>

            {/* Actions */}
            <div className="flex gap-2">
              {isEditing ? (
                <>
                  <button
                    onClick={handleCancel}
                    className="px-4 py-2 text-xs font-medium text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-xl transition-colors"
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleSave}
                    className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-medium text-white bg-gradient-to-r from-slate-800 to-slate-700 hover:from-slate-700 hover:to-slate-600 rounded-xl transition-colors shadow-sm"
                  >
                    <Save className="w-3.5 h-3.5" />
                    Save Changes
                  </button>
                </>
              ) : (
                <button
                  onClick={() => setIsEditing(true)}
                  className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-medium text-cyan-700 bg-cyan-50 hover:bg-cyan-100 border border-cyan-200 rounded-xl transition-colors"
                >
                  <Edit3 className="w-3.5 h-3.5" />
                  Edit Profile
                </button>
              )}
            </div>
          </div>
        </div>
      </div>

      {/* Stats Cards */}
      <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
        {statsCards.map((stat) => {
          const Icon = stat.icon;
          return (
            <div
              key={stat.label}
              className="bg-white rounded-2xl border border-gray-100 shadow-sm hover:shadow-xl hover:-translate-y-0.5 transition-all duration-300 p-4"
            >
              <div className={`w-10 h-10 rounded-full ${stat.iconBg} flex items-center justify-center mb-3 shadow-sm`}>
                <Icon className="w-5 h-5 text-white" />
              </div>
              <p className="text-lg font-bold text-gray-900 mb-1">{stat.value}</p>
              <p className="text-xs font-semibold text-gray-600 uppercase tracking-wide">{stat.label}</p>
            </div>
          );
        })}
      </div>

      {/* Tabs */}
      <div className="flex gap-1 bg-gray-100 rounded-xl p-1 w-fit">
        {tabs.map((tab) => {
          const Icon = tab.icon;
          return (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`inline-flex items-center gap-1.5 px-4 py-2 text-xs font-medium rounded-lg transition-all ${
                activeTab === tab.id
                  ? 'bg-white text-cyan-700 shadow-sm'
                  : 'text-gray-500 hover:text-gray-700'
              }`}
            >
              <Icon className="w-3.5 h-3.5" />
              {tab.label}
            </button>
          );
        })}
      </div>

      {/* Tab Content */}
      {activeTab === 'overview' && (
        <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
          {/* Personal Information */}
          <div className="lg:col-span-2 bg-white rounded-2xl border border-gray-200 shadow-sm p-6">
            <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-5">
              <User className="w-4 h-4 text-cyan-600" />
              Personal Information
            </h2>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-5">
              {[
                { label: 'Full Name', value: `${profile.firstName} ${profile.lastName}`, icon: User, field: 'firstName' },
                { label: 'Email Address', value: profile.email, icon: Mail, field: 'email' },
                { label: 'Phone Number', value: profile.phone, icon: Phone, field: 'phone' },
                { label: 'Employee ID', value: profile.employeeId, icon: Award, field: 'employeeId' },
                { label: 'Specialization', value: profile.specialization, icon: Shield, field: 'specialization' },
                { label: 'Location', value: profile.location, icon: MapPin, field: 'location' },
                { label: 'Join Date', value: new Date(profile.joinDate).toLocaleDateString('en-US', { year: 'numeric', month: 'long', day: 'numeric' }), icon: Calendar, field: 'joinDate' },
                { label: 'Languages', value: profile.language, icon: Globe, field: 'language' },
              ].map((item) => {
                const Icon = item.icon;
                return (
                  <div key={item.label}>
                    <label className="text-xs font-medium text-gray-500 flex items-center gap-1.5 mb-1.5">
                      <Icon className="w-3.5 h-3.5" />
                      {item.label}
                    </label>
                    {isEditing && item.field !== 'employeeId' && item.field !== 'joinDate' ? (
                      <input
                        type="text"
                        value={(editedProfile as any)[item.field]}
                        onChange={(e) =>
                          setEditedProfile((prev) => ({ ...prev, [item.field]: e.target.value }))
                        }
                        className="w-full px-3 py-2 text-sm bg-gray-50 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-500 transition-all"
                      />
                    ) : (
                      <p className="text-sm font-medium text-gray-900">{item.value}</p>
                    )}
                  </div>
                );
              })}
            </div>

            {/* Bio */}
            <div className="mt-5 pt-5 border-t border-gray-100">
              <label className="text-xs font-medium text-gray-500 flex items-center gap-1.5 mb-1.5">
                <FileText className="w-3.5 h-3.5" />
                Bio
              </label>
              {isEditing ? (
                <textarea
                  value={editedProfile.bio}
                  onChange={(e) => setEditedProfile((prev) => ({ ...prev, bio: e.target.value }))}
                  rows={3}
                  className="w-full px-3 py-2 text-sm bg-gray-50 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-500 transition-all resize-none"
                />
              ) : (
                <p className="text-sm text-gray-700 leading-relaxed">{profile.bio}</p>
              )}
            </div>
          </div>

          {/* Activity & Quick Links */}
          <div className="space-y-6">
            {/* Recent Activity */}
            <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6">
              <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-4">
                <Clock className="w-4 h-4 text-cyan-600" />
                Recent Activity
              </h2>
              <div className="space-y-3">
                {[
                  { action: 'Triaged patient Emmanuel H.', time: '15 min ago', color: 'bg-cyan-600' },
                  { action: 'Updated vital signs for Bay 3', time: '42 min ago', color: 'bg-emerald-500' },
                  { action: 'Acknowledged critical alert', time: '1h ago', color: 'bg-red-500' },
                  { action: 'Completed shift handoff', time: '3h ago', color: 'bg-blue-500' },
                  { action: 'Registered new patient', time: '4h ago', color: 'bg-amber-500' },
                ].map((item, i) => (
                  <div key={i} className="flex items-start gap-3">
                    <div className={`w-2 h-2 rounded-full ${item.color} mt-1.5 flex-shrink-0`} />
                    <div className="flex-1 min-w-0">
                      <p className="text-xs font-medium text-gray-800 truncate">{item.action}</p>
                      <p className="text-[11px] text-gray-400">{item.time}</p>
                    </div>
                  </div>
                ))}
              </div>
            </div>

            {/* Quick Links */}
            <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6">
              <h2 className="text-sm font-bold text-gray-900 mb-4">Quick Actions</h2>
              <div className="space-y-2">
                {[
                  { label: 'Download Activity Report', icon: FileText },
                  { label: 'Change Password', icon: KeyRound },
                  { label: 'Manage Notifications', icon: Bell },
                  { label: 'Sign Out', icon: LogOut, danger: true },
                ].map((item) => {
                  const Icon = item.icon;
                  return (
                    <button
                      key={item.label}
                      className={`w-full flex items-center justify-between px-3 py-2.5 rounded-xl text-xs font-medium transition-colors ${
                        item.danger
                          ? 'text-red-600 hover:bg-red-50'
                          : 'text-gray-700 hover:bg-gray-50'
                      }`}
                    >
                      <span className="flex items-center gap-2">
                        <Icon className="w-4 h-4" />
                        {item.label}
                      </span>
                      <ChevronRight className="w-3.5 h-3.5 text-gray-400" />
                    </button>
                  );
                })}
              </div>
            </div>
          </div>
        </div>
      )}

      {activeTab === 'settings' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Notification Preferences */}
          <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6">
            <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-4">
              <Bell className="w-4 h-4 text-cyan-600" />
              Notification Preferences
            </h2>
            <div className="divide-y divide-gray-100">
              <SettingToggle
                label="Email Notifications"
                description="Receive notification summaries via email"
                enabled={emailNotifs}
                onToggle={() => setEmailNotifs(!emailNotifs)}
              />
              <SettingToggle
                label="Push Notifications"
                description="Browser push notifications for real-time alerts"
                enabled={pushNotifs}
                onToggle={() => setPushNotifs(!pushNotifs)}
              />
              <SettingToggle
                label="Sound Alerts"
                description="Play sound for critical notifications"
                enabled={soundAlerts}
                onToggle={() => setSoundAlerts(!soundAlerts)}
              />
              <SettingToggle
                label="Critical Only"
                description="Only receive notifications for critical events"
                enabled={criticalOnly}
                onToggle={() => setCriticalOnly(!criticalOnly)}
              />
            </div>
          </div>

          {/* Appearance Preferences */}
          <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6">
            <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-4">
              <Palette className="w-4 h-4 text-cyan-600" />
              Appearance
            </h2>
            <div className="divide-y divide-gray-100">
              <SettingToggle
                label="Dark Mode"
                description="Switch to dark theme across the application"
                enabled={darkMode}
                onToggle={() => setDarkMode(!darkMode)}
              />
              <SettingToggle
                label="Compact View"
                description="Reduce spacing for more information density"
                enabled={compactView}
                onToggle={() => setCompactView(!compactView)}
              />
            </div>

            <div className="mt-5 pt-4 border-t border-gray-100">
              <label className="text-xs font-medium text-gray-500 mb-2 block">Theme Color</label>
              <div className="flex gap-2">
                {[
                  'bg-cyan-600',
                  'bg-blue-500',
                  'bg-emerald-500',
                  'bg-emerald-500',
                  'bg-rose-500',
                  'bg-amber-500',
                ].map((color, i) => (
                  <button
                    key={color}
                    className={`w-8 h-8 rounded-full ${color} ${
                      i === 0 ? 'ring-2 ring-offset-2 ring-cyan-600' : ''
                    } transition-transform hover:scale-110`}
                  />
                ))}
              </div>
            </div>
          </div>
        </div>
      )}

      {activeTab === 'security' && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          {/* Change Password */}
          <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6">
            <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-5">
              <KeyRound className="w-4 h-4 text-cyan-600" />
              Change Password
            </h2>
            <div className="space-y-4">
              <div>
                <label className="text-xs font-medium text-gray-500 mb-1.5 block">Current Password</label>
                <input
                  type="password"
                  placeholder="Enter current password"
                  className="w-full px-3 py-2.5 text-sm bg-gray-50 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-500 transition-all"
                />
              </div>
              <div>
                <label className="text-xs font-medium text-gray-500 mb-1.5 block">New Password</label>
                <input
                  type="password"
                  placeholder="Enter new password"
                  className="w-full px-3 py-2.5 text-sm bg-gray-50 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-500 transition-all"
                />
              </div>
              <div>
                <label className="text-xs font-medium text-gray-500 mb-1.5 block">Confirm New Password</label>
                <input
                  type="password"
                  placeholder="Confirm new password"
                  className="w-full px-3 py-2.5 text-sm bg-gray-50 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 focus:border-cyan-500 transition-all"
                />
              </div>
              <button className="w-full py-2.5 text-xs font-medium text-white bg-gradient-to-r from-slate-800 to-slate-700 hover:from-slate-700 hover:to-slate-600 rounded-xl transition-colors shadow-sm">
                Update Password
              </button>
            </div>
          </div>

          {/* Security Settings */}
          <div className="space-y-6">
            <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6">
              <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-4">
                <Shield className="w-4 h-4 text-cyan-600" />
                Two-Factor Authentication
              </h2>
              <p className="text-xs text-gray-500 mb-4">
                Add an extra layer of security to your account by enabling two-factor authentication.
              </p>
              <button className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-medium text-cyan-700 bg-cyan-50 hover:bg-cyan-100 border border-cyan-200 rounded-xl transition-colors">
                <Shield className="w-3.5 h-3.5" />
                Enable 2FA
              </button>
            </div>

            <div className="bg-white rounded-2xl border border-gray-200 shadow-sm p-6">
              <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-4">
                <Clock className="w-4 h-4 text-cyan-600" />
                Login History
              </h2>
              <div className="space-y-3">
                {[
                  { device: 'Chrome on Windows', location: 'Kigali, Rwanda', time: 'Now', active: true },
                  { device: 'Safari on iPhone', location: 'Kigali, Rwanda', time: '2 hours ago', active: false },
                  { device: 'Chrome on Windows', location: 'Kigali, Rwanda', time: 'Yesterday', active: false },
                ].map((session, i) => (
                  <div key={i} className="flex items-center justify-between py-2">
                    <div>
                      <p className="text-xs font-medium text-gray-800">{session.device}</p>
                      <p className="text-[11px] text-gray-400">
                        {session.location} · {session.time}
                      </p>
                    </div>
                    {session.active && (
                      <span className="px-2 py-0.5 text-[10px] font-semibold rounded-md bg-emerald-100 text-emerald-700">
                        Active
                      </span>
                    )}
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
