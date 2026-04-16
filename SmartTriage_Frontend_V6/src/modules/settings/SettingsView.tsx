import { useState } from 'react';
import {
  Bell, Shield, Save, Settings, Monitor, Globe, Volume2, Mail,
  MessageSquare, RefreshCw, Baby, Scale, UserCheck, CheckCircle,
  Sliders, Database, Cpu, Lock, Zap, Activity,
} from 'lucide-react';
import { useTheme } from '../../hooks/useTheme';

export function SettingsView() {
  const { isDark } = useTheme();
  const [settings, setSettings] = useState({
    notifications: {
      emailAlerts: true,
      smsAlerts: false,
      criticalOnly: false,
      soundEnabled: true,
    },
    system: {
      autoRefresh: true,
      refreshInterval: 5,
      darkMode: false,
      language: 'en',
    },
    triage: {
      strictMode: true,
      allowOverride: false,
      pediatricAutoDetect: true,
      requireWeight: true,
    },
  });

  const [saved, setSaved] = useState(false);
  const [activeSection, setActiveSection] = useState<'notifications' | 'system' | 'triage'>('notifications');

  const handleSave = () => {
    setSaved(true);
    setTimeout(() => setSaved(false), 3000);
  };

  const sections = [
    { id: 'notifications' as const, label: 'Notifications', icon: Bell, description: 'Alert & notification preferences' },
    { id: 'system' as const, label: 'System', icon: Monitor, description: 'General system configuration' },
    { id: 'triage' as const, label: 'Triage Rules', icon: Shield, description: 'mSAT protocol settings' },
  ];

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Dark Header Banner ── */}
        <div className="glass-card-dark rounded-3xl overflow-hidden animate-fade-up">
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center justify-between">
            <div className="flex items-center gap-4">
              <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center shadow-lg">
                <Settings className="w-6 h-6 text-white" />
              </div>
              <div>
                <h1 className="text-lg font-bold text-white tracking-wide">Settings</h1>
                <p className="text-white/70 text-xs font-medium">Configure system preferences and triage protocol rules</p>
              </div>
            </div>
            <div className="flex items-center gap-3">
              <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                <Sliders className="w-3.5 h-3.5 text-white/70" />
                <span className="text-xs font-semibold text-white/90">Configuration</span>
              </div>
              <button
                onClick={handleSave}
                className="inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-slate-800 bg-white hover:bg-gray-50 rounded-xl transition-all duration-300 shadow-lg hover:-translate-y-0.5"
              >
                <Save className="w-3.5 h-3.5" />
                Save Changes
              </button>
            </div>
          </div>
        </div>

        {/* ── Success Toast ── */}
        {saved && (
          <div className="glass-card rounded-3xl overflow-hidden animate-fade-up">
            <div className="flex items-center gap-3 px-5 py-3.5 bg-gradient-to-r from-emerald-50/80 to-emerald-100/50 border-l-4 border-emerald-500">
              <div className="w-8 h-8 rounded-xl bg-emerald-100 flex items-center justify-center flex-shrink-0">
                <CheckCircle className="w-4 h-4 text-emerald-600" />
              </div>
              <div>
                <span className="text-sm font-bold text-emerald-800">Settings saved successfully!</span>
                <p className="text-xs text-emerald-600 mt-0.5">Your preferences have been updated</p>
              </div>
            </div>
          </div>
        )}

        {/* ── System Overview Panel ── */}
        <div
          className="rounded-2xl p-5 animate-fade-up"
          style={{
            background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.55)',
            backdropFilter: 'blur(24px)',
            WebkitBackdropFilter: 'blur(24px)',
            border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(255,255,255,0.6)',
            boxShadow: isDark ? '0 8px 32px rgba(0,0,0,0.3)' : '0 8px 32px rgba(0,0,0,0.04), inset 0 1px 0 rgba(255,255,255,0.8)',
            animationDelay: '0.08s',
          }}
        >
          <div className="flex items-center justify-between mb-4">
            <div>
              <h3 className="text-base font-extrabold text-slate-800 tracking-tight">System Overview</h3>
              <p className="text-xs text-slate-500 font-medium mt-0.5">Current configuration snapshot</p>
            </div>
            <div className="flex items-center gap-1.5">
              <div className="w-2 h-2 bg-emerald-400 rounded-full animate-pulse" />
              <span className="text-[10px] font-bold text-emerald-600 uppercase tracking-wider">Active</span>
            </div>
          </div>
          <div className="grid grid-cols-2 lg:grid-cols-4 gap-3">
            {[
              { label: 'Notification Channels', value: settings.notifications.emailAlerts ? 'Email Active' : 'Email Off', sublabel: settings.notifications.smsAlerts ? 'SMS Active' : 'SMS Off', icon: Bell, iconBg: 'rgba(6,182,212,0.12)', iconColor: 'text-cyan-600' },
              { label: 'Refresh Interval', value: `${settings.system.refreshInterval}s`, sublabel: settings.system.autoRefresh ? 'Auto-refresh on' : 'Manual only', icon: RefreshCw, iconBg: 'rgba(34,197,94,0.12)', iconColor: 'text-emerald-500' },
              { label: 'Triage Protocol', value: settings.triage.strictMode ? 'Strict Mode' : 'Flexible', sublabel: settings.triage.allowOverride ? 'Overrides allowed' : 'No overrides', icon: Shield, iconBg: 'rgba(99,102,241,0.12)', iconColor: 'text-indigo-500' },
              { label: 'System Language', value: settings.system.language === 'en' ? 'English' : settings.system.language === 'fr' ? 'Français' : 'Kinyarwanda', sublabel: 'SmartTriage', icon: Globe, iconBg: 'rgba(251,146,60,0.12)', iconColor: 'text-orange-500' },
            ].map((item) => {
              const Icon = item.icon;
              return (
                <div
                  key={item.label}
                  className="flex items-center gap-3 p-3 rounded-xl hover:-translate-y-0.5 transition-all duration-400 group cursor-default"
                  style={{
                    background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(255,255,255,0.6)',
                    border: isDark ? '1px solid rgba(2,132,199,0.22)' : '1px solid rgba(203,213,225,0.4)',
                    boxShadow: isDark ? '0 2px 8px rgba(0,0,0,0.2)' : '0 2px 8px rgba(0,0,0,0.04), 0 1px 2px rgba(0,0,0,0.03)',
                  }}
                >
                  <div
                    className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 group-hover:scale-110 transition-transform duration-400"
                    style={{ backgroundColor: item.iconBg }}
                  >
                    <Icon className={`w-[18px] h-[18px] ${item.iconColor}`} />
                  </div>
                  <div className="min-w-0">
                    <div className="text-[13px] font-bold text-slate-800 truncate">{item.value}</div>
                    <div className="text-[11px] text-slate-400 font-medium truncate">{item.sublabel}</div>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* ── Section Navigation ── */}
        <div className="glass-card rounded-2xl p-1.5 animate-fade-up" style={{ animationDelay: '0.15s' }}>
          <div className="flex items-center gap-1.5">
            {sections.map((section) => {
              const Icon = section.icon;
              const isActive = activeSection === section.id;
              return (
                <button
                  key={section.id}
                  onClick={() => setActiveSection(section.id)}
                  className={`flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-xl text-xs font-semibold transition-all duration-300 ${
                    isActive
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md shadow-slate-800/20'
                      : 'text-gray-600 hover:bg-white/60'
                  }`}
                >
                  <Icon className={`w-3.5 h-3.5 ${isActive ? 'text-white' : 'text-gray-400'}`} />
                  <span className={`font-bold ${isActive ? 'text-white' : 'text-gray-800'}`}>{section.label}</span>
                </button>
              );
            })}
          </div>
        </div>

        {/* ── Notifications Section ── */}
        {activeSection === 'notifications' && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 animate-fade-up" style={{ animationDelay: '0.3s' }}>
            {/* Email & SMS */}
            <div className="glass-card rounded-3xl overflow-hidden">
              <div className="px-5 py-4 border-b border-gray-100/60">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-cyan-600 to-cyan-500 flex items-center justify-center shadow-sm">
                    <Mail className="w-4 h-4 text-white" />
                  </div>
                  <div>
                    <h2 className="text-sm font-bold text-gray-900">Communication Channels</h2>
                    <p className="text-xs text-gray-500 mt-0.5">How you receive alerts and updates</p>
                  </div>
                </div>
              </div>
              <div className="p-5 space-y-1 divide-y divide-gray-100/60">
                <SettingToggle
                  icon={Mail}
                  label="Email Alerts"
                  description="Receive email notifications for critical alerts"
                  checked={settings.notifications.emailAlerts}
                  onChange={(checked) =>
                    setSettings({
                      ...settings,
                      notifications: { ...settings.notifications, emailAlerts: checked },
                    })
                  }
                />
                <SettingToggle
                  icon={MessageSquare}
                  label="SMS Alerts"
                  description="Receive text messages for urgent cases"
                  checked={settings.notifications.smsAlerts}
                  onChange={(checked) =>
                    setSettings({
                      ...settings,
                      notifications: { ...settings.notifications, smsAlerts: checked },
                    })
                  }
                />
              </div>
            </div>

            {/* Alert Preferences */}
            <div className="glass-card rounded-3xl overflow-hidden">
              <div className="px-5 py-4 border-b border-gray-100/60">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-amber-500 to-amber-600 flex items-center justify-center shadow-sm">
                    <Bell className="w-4 h-4 text-white" />
                  </div>
                  <div>
                    <h2 className="text-sm font-bold text-gray-900">Alert Behavior</h2>
                    <p className="text-xs text-gray-500 mt-0.5">Control sound and filter severity</p>
                  </div>
                </div>
              </div>
              <div className="p-5 space-y-1 divide-y divide-gray-100/60">
                <SettingToggle
                  icon={Shield}
                  label="Critical Only Mode"
                  description="Only notify for RED category patients"
                  checked={settings.notifications.criticalOnly}
                  onChange={(checked) =>
                    setSettings({
                      ...settings,
                      notifications: { ...settings.notifications, criticalOnly: checked },
                    })
                  }
                />
                <SettingToggle
                  icon={Volume2}
                  label="Sound Notifications"
                  description="Play sound for new alerts"
                  checked={settings.notifications.soundEnabled}
                  onChange={(checked) =>
                    setSettings({
                      ...settings,
                      notifications: { ...settings.notifications, soundEnabled: checked },
                    })
                  }
                />
              </div>
            </div>
          </div>
        )}

        {/* ── System Section ── */}
        {activeSection === 'system' && (
          <div className="grid grid-cols-1 lg:grid-cols-2 gap-4 animate-fade-up" style={{ animationDelay: '0.3s' }}>
            {/* Auto Refresh */}
            <div className="glass-card rounded-3xl overflow-hidden">
              <div className="px-5 py-4 border-b border-gray-100/60">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-emerald-500 to-emerald-600 flex items-center justify-center shadow-sm">
                    <RefreshCw className="w-4 h-4 text-white" />
                  </div>
                  <div>
                    <h2 className="text-sm font-bold text-gray-900">Dashboard Behavior</h2>
                    <p className="text-xs text-gray-500 mt-0.5">Auto-refresh and live data settings</p>
                  </div>
                </div>
              </div>
              <div className="p-5">
                <div className="divide-y divide-gray-100/60">
                  <SettingToggle
                    icon={RefreshCw}
                    label="Auto Refresh Dashboard"
                    description="Automatically update patient queue in real-time"
                    checked={settings.system.autoRefresh}
                    onChange={(checked) =>
                      setSettings({
                        ...settings,
                        system: { ...settings.system, autoRefresh: checked },
                      })
                    }
                  />
                </div>

                <div className="mt-5 space-y-4">
                  <div>
                    <label className="text-xs font-semibold text-gray-500 flex items-center gap-1.5 mb-2 uppercase tracking-wider">
                      <Activity className="w-3.5 h-3.5" />
                      Refresh Interval
                    </label>
                    <select
                      className="w-full px-4 py-3 text-sm bg-white/80 backdrop-blur-sm border border-gray-200/60 rounded-2xl focus:outline-none focus:ring-4 focus:ring-cyan-500/10 focus:border-cyan-500 transition-all duration-300 shadow-sm font-medium text-gray-700"
                      value={settings.system.refreshInterval}
                      onChange={(e) =>
                        setSettings({
                          ...settings,
                          system: { ...settings.system, refreshInterval: parseInt(e.target.value) },
                        })
                      }
                    >
                      <option value={5}>5 seconds</option>
                      <option value={10}>10 seconds</option>
                      <option value={30}>30 seconds</option>
                      <option value={60}>1 minute</option>
                    </select>
                  </div>
                </div>
              </div>
            </div>

            {/* Localization */}
            <div className="glass-card rounded-3xl overflow-hidden">
              <div className="px-5 py-4 border-b border-gray-100/60">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-blue-500 to-blue-600 flex items-center justify-center shadow-sm">
                    <Globe className="w-4 h-4 text-white" />
                  </div>
                  <div>
                    <h2 className="text-sm font-bold text-gray-900">Localization & Display</h2>
                    <p className="text-xs text-gray-500 mt-0.5">Language and interface preferences</p>
                  </div>
                </div>
              </div>
              <div className="p-5 space-y-5">
                <div>
                  <label className="text-xs font-semibold text-gray-500 flex items-center gap-1.5 mb-2 uppercase tracking-wider">
                    <Globe className="w-3.5 h-3.5" />
                    Language
                  </label>
                  <select
                    className="w-full px-4 py-3 text-sm bg-white/80 backdrop-blur-sm border border-gray-200/60 rounded-2xl focus:outline-none focus:ring-4 focus:ring-cyan-500/10 focus:border-cyan-500 transition-all duration-300 shadow-sm font-medium text-gray-700"
                    value={settings.system.language}
                    onChange={(e) =>
                      setSettings({
                        ...settings,
                        system: { ...settings.system, language: e.target.value },
                      })
                    }
                  >
                    <option value="en">English</option>
                    <option value="fr">Français</option>
                    <option value="rw">Kinyarwanda</option>
                  </select>
                </div>

                {/* System Info */}
                <div className="bg-white/60 backdrop-blur-sm rounded-2xl border border-white/60 p-4 space-y-3">
                  <p className="text-xs font-bold text-gray-500 uppercase tracking-wider">System Information</p>
                  {[
                    { label: 'Version', value: 'SmartTriage', icon: Cpu },
                    { label: 'Database', value: 'Connected', icon: Database },
                    { label: 'API Status', value: 'Connected', icon: Zap },
                  ].map((item) => (
                    <div key={item.label} className="flex items-center gap-3">
                      <item.icon className="w-3.5 h-3.5 text-gray-400 flex-shrink-0" />
                      <div className="flex items-center gap-2 flex-1 min-w-0">
                        <span className="text-xs font-medium text-gray-500">{item.label}:</span>
                        <span className="text-xs font-semibold text-gray-800">{item.value}</span>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ── Triage Rules Section ── */}
        {activeSection === 'triage' && (
          <div className="space-y-4 animate-fade-up" style={{ animationDelay: '0.3s' }}>
            {/* Protocol Info Banner */}
            <div className="glass-card-dark rounded-3xl overflow-hidden">
              <div className="p-5">
                <div className="flex items-start gap-4">
                  <div className="w-14 h-14 rounded-2xl bg-gradient-to-br from-slate-800 to-cyan-600 flex items-center justify-center shadow-lg flex-shrink-0">
                    <Shield className="w-7 h-7 text-white" />
                  </div>
                  <div className="flex-1 min-w-0">
                    <h2 className="text-base font-bold text-gray-900 mb-1">mSAT Triage Protocol Configuration</h2>
                    <p className="text-xs text-gray-600 leading-relaxed">
                      Configure the modified South African Triage Scale (mSAT) protocol rules. These settings control how the triage engine processes patient assessments, handles overrides, and manages pediatric cases.
                    </p>
                    <div className="flex items-center gap-4 mt-3">
                      <div className="flex items-center gap-1.5">
                        <div className="w-2 h-2 bg-emerald-400 rounded-full animate-pulse" />
                        <span className="text-[10px] font-semibold text-emerald-700">Protocol Active</span>
                      </div>
                      <div className="flex items-center gap-1.5">
                        <Lock className="w-3 h-3 text-gray-400" />
                        <span className="text-[10px] font-semibold text-gray-500">Admin Access Required</span>
                      </div>
                    </div>
                  </div>
                </div>
              </div>
            </div>

            {/* Rules Grid */}
            <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
              {/* Enforcement Rules */}
              <div className="glass-card rounded-3xl overflow-hidden">
                <div className="px-5 py-4 border-b border-gray-100/60">
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-red-500 to-red-600 flex items-center justify-center shadow-sm">
                      <Shield className="w-4 h-4 text-white" />
                    </div>
                    <div>
                      <h2 className="text-sm font-bold text-gray-900">Enforcement Rules</h2>
                      <p className="text-xs text-gray-500 mt-0.5">Strict mode and override policies</p>
                    </div>
                  </div>
                </div>
                <div className="p-5 divide-y divide-gray-100/60">
                  <SettingToggle
                    icon={Shield}
                    label="Strict Mode"
                    description="Enforce all mSAT protocol rules without exceptions"
                    checked={settings.triage.strictMode}
                    onChange={(checked) =>
                      setSettings({
                        ...settings,
                        triage: { ...settings.triage, strictMode: checked },
                      })
                    }
                  />
                  <SettingToggle
                    icon={UserCheck}
                    label="Allow Senior Override"
                    description="Permit manual category changes by senior staff"
                    checked={settings.triage.allowOverride}
                    onChange={(checked) =>
                      setSettings({
                        ...settings,
                        triage: { ...settings.triage, allowOverride: checked },
                      })
                    }
                  />
                </div>
              </div>

              {/* Pediatric Rules */}
              <div className="glass-card rounded-3xl overflow-hidden">
                <div className="px-5 py-4 border-b border-gray-100/60">
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-xl bg-gradient-to-br from-pink-500 to-pink-600 flex items-center justify-center shadow-sm">
                      <Baby className="w-4 h-4 text-white" />
                    </div>
                    <div>
                      <h2 className="text-sm font-bold text-gray-900">Pediatric Settings</h2>
                      <p className="text-xs text-gray-500 mt-0.5">Age detection and weight requirements</p>
                    </div>
                  </div>
                </div>
                <div className="p-5 divide-y divide-gray-100/60">
                  <SettingToggle
                    icon={Baby}
                    label="Auto-detect Pediatric"
                    description="Automatically enable pediatric mode for age < 15"
                    checked={settings.triage.pediatricAutoDetect}
                    onChange={(checked) =>
                      setSettings({
                        ...settings,
                        triage: { ...settings.triage, pediatricAutoDetect: checked },
                      })
                    }
                  />
                  <SettingToggle
                    icon={Scale}
                    label="Require Weight for Pediatric"
                    description="Make weight field mandatory for pediatric patients"
                    checked={settings.triage.requireWeight}
                    onChange={(checked) =>
                      setSettings({
                        ...settings,
                        triage: { ...settings.triage, requireWeight: checked },
                      })
                    }
                  />
                </div>
              </div>
            </div>
          </div>
        )}

      </div>
    </div>
  );
}

// ── Premium Setting Toggle Component ──
interface SettingToggleProps {
  icon?: any;
  label: string;
  description: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}

function SettingToggle({ icon: Icon, label, description, checked, onChange }: SettingToggleProps) {
  return (
    <div className="flex items-center justify-between py-4 group">
      <div className="flex items-start gap-3 flex-1 min-w-0">
        {Icon && (
          <div className={`w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0 mt-0.5 transition-all duration-300 ${
            checked
              ? 'bg-cyan-100 shadow-sm'
              : 'bg-gray-100'
          }`}>
            <Icon className={`w-4 h-4 transition-all duration-300 ${
              checked ? 'text-cyan-600' : 'text-gray-400'
            }`} />
          </div>
        )}
        <div className="min-w-0">
          <p className="text-sm font-semibold text-gray-900">{label}</p>
          <p className="text-xs text-gray-500 mt-0.5 leading-relaxed">{description}</p>
        </div>
      </div>
      <button
        onClick={() => onChange(!checked)}
        className={`relative w-12 h-7 rounded-full transition-all duration-300 flex-shrink-0 ml-4 ${
          checked
            ? 'bg-gradient-to-r from-cyan-600 to-cyan-500 shadow-md shadow-cyan-600/30'
            : 'bg-gray-300 hover:bg-gray-400'
        }`}
      >
        <span
          className={`absolute top-0.5 left-0.5 w-6 h-6 rounded-full bg-white shadow-md transition-all duration-300 flex items-center justify-center ${
            checked ? 'translate-x-5' : 'translate-x-0'
          }`}
        >
          {checked && (
            <CheckCircle className="w-3 h-3 text-cyan-600" />
          )}
        </span>
      </button>
    </div>
  );
}
