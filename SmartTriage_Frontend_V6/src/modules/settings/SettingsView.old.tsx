import { useState } from 'react';
import { Bell, Shield, Save, Settings, Monitor, Globe, Volume2, Mail, MessageSquare, RefreshCw, Baby, Scale, UserCheck, CheckCircle } from 'lucide-react';

export function SettingsView() {
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

  const handleSave = () => {
    setSaved(true);
    setTimeout(() => setSaved(false), 3000);
  };

  return (
    <div className="p-4 lg:p-5 max-w-5xl mx-auto space-y-5 animate-fade-in">
      {/* Header */}
      <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
        <div>
          <h1 className="text-xl font-extrabold text-gray-900 flex items-center gap-3 tracking-tight">
            <div className="w-10 h-10 rounded-xl bg-gradient-to-br from-slate-800 to-slate-700 flex items-center justify-center shadow-lg shadow-slate-800/20">
              <Settings className="w-5 h-5 text-white" />
            </div>
            Settings
          </h1>
          <p className="text-sm text-gray-500 mt-1 font-medium">
            Configure system preferences and triage protocol rules
          </p>
        </div>

        <button
          onClick={handleSave}
          className="inline-flex items-center gap-1.5 px-5 py-2.5 text-xs font-bold text-white bg-gradient-to-r from-slate-800 to-slate-700 hover:shadow-lg hover:-translate-y-0.5 rounded-xl transition-all duration-300 shadow-lg shadow-slate-800/20"
        >
          <Save className="w-3.5 h-3.5" />
          Save Changes
        </button>
      </div>

      {/* Success Toast */}
      {saved && (
        <div className="flex items-center gap-3 px-5 py-3.5 bg-gradient-to-r from-emerald-50 to-emerald-100/50 border-2 border-emerald-200 rounded-2xl shadow-md backdrop-blur-sm animate-fade-up">
          <CheckCircle className="w-4 h-4 text-emerald-600 flex-shrink-0" />
          <span className="text-sm font-medium text-emerald-800">Settings saved successfully!</span>
        </div>
      )}

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Notifications */}
        <div className="glass-card rounded-3xl p-4 animate-fade-up" style={{ animationDelay: '0.1s' }}>
          <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-4">
            <Bell className="w-4 h-4 text-cyan-600" />
            Notifications
          </h2>
          <p className="text-xs text-gray-500 mb-5">Manage how you receive alerts and updates</p>

          <div className="divide-y divide-gray-100">
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

        {/* System */}
        <div className="glass-card rounded-3xl p-4 animate-fade-up" style={{ animationDelay: '0.15s' }}>
          <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-4">
            <Monitor className="w-4 h-4 text-cyan-600" />
            System
          </h2>
          <p className="text-xs text-gray-500 mb-5">Configure general system behavior</p>

          <div className="divide-y divide-gray-100">
            <SettingToggle
              icon={RefreshCw}
              label="Auto Refresh Dashboard"
              description="Automatically update patient queue"
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
              <label className="text-xs font-medium text-gray-500 flex items-center gap-1.5 mb-1.5">
                <RefreshCw className="w-3.5 h-3.5" />
                Refresh Interval
              </label>
              <select
                className="w-full px-3 py-2.5 text-sm bg-white/80 backdrop-blur-sm border-2 border-gray-200/60 rounded-2xl focus:outline-none focus:ring-4 focus:ring-cyan-500/10 focus:border-cyan-500 transition-all duration-300 shadow-sm"
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

            <div>
              <label className="text-xs font-medium text-gray-500 flex items-center gap-1.5 mb-1.5">
                <Globe className="w-3.5 h-3.5" />
                Language
              </label>
              <select
                className="w-full px-3 py-2.5 text-sm bg-white/80 backdrop-blur-sm border-2 border-gray-200/60 rounded-2xl focus:outline-none focus:ring-4 focus:ring-cyan-500/10 focus:border-cyan-500 transition-all duration-300 shadow-sm"
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
          </div>
        </div>

        {/* Triage Rules — spans full width */}
        <div className="lg:col-span-2 glass-card rounded-3xl p-4 animate-fade-up" style={{ animationDelay: '0.2s' }}>
          <h2 className="text-sm font-bold text-gray-900 flex items-center gap-2 mb-4">
            <Shield className="w-4 h-4 text-cyan-600" />
            Triage Rules
          </h2>
          <p className="text-xs text-gray-500 mb-5">Configure mSAT protocol settings and overrides</p>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-x-8">
            <div className="divide-y divide-gray-100">
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
            <div className="divide-y divide-gray-100">
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
    </div>
  );
}

// Setting Toggle Component
interface SettingToggleProps {
  icon?: any;
  label: string;
  description: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}

function SettingToggle({ icon: Icon, label, description, checked, onChange }: SettingToggleProps) {
  return (
    <div className="flex items-center justify-between py-3">
      <div className="flex items-start gap-2.5 flex-1 min-w-0">
        {Icon && <Icon className="w-4 h-4 text-gray-400 mt-0.5 flex-shrink-0" />}
        <div className="min-w-0">
          <p className="text-sm font-medium text-gray-900">{label}</p>
          <p className="text-xs text-gray-500 mt-0.5">{description}</p>
        </div>
      </div>
      <button
        onClick={() => onChange(!checked)}
        className={`relative w-11 h-6 rounded-full transition-all duration-300 flex-shrink-0 ml-4 ${checked ? 'bg-gradient-to-r from-cyan-600 to-cyan-500 shadow-md shadow-cyan-600/30' : 'bg-gray-300'
          }`}
      >
        <span
          className={`absolute top-0.5 left-0.5 w-5 h-5 rounded-full bg-white shadow-md transition-all duration-300 ${checked ? 'translate-x-5' : 'translate-x-0'
            }`}
        />
      </button>
    </div>
  );
}
