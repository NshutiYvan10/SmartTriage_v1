import { Settings, Moon, Sun, Cpu, Database, Zap, Palette, CheckCircle } from 'lucide-react';
import { useTheme } from '../../hooks/useTheme';

/* ═══════════════════════════════════════════════════════════════
   Settings (admin) — deliberately lean.

   This page used to carry a wall of non-functional toggles
   (notifications, auto-refresh interval, multi-language, and fake
   "triage protocol" switches that never touched the triage engine).
   They persisted nothing and, in the triage case, implied a clinician
   could reconfigure life-critical protocol behaviour that is actually
   hardcoded — worse than useless. All of that has been removed.

   What remains is what is real and useful:
     • Dark Mode — wired to the global theme store (persists per device).
     • System Information — read-only environment facts.

   Personal preferences (incl. the critical-alert sound) live on the
   Profile page so every role has them, not just admins.
   ═══════════════════════════════════════════════════════════════ */

export function SettingsView() {
  const { isDark, toggle, glassCard, glassInner, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-3xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-4">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center shadow-lg">
              <Settings className="w-5 h-5 text-white" />
            </div>
            <div>
              <h1 className="text-lg font-bold text-white tracking-wide">Settings</h1>
              <p className="text-white/50 text-xs font-medium">Appearance and system information</p>
            </div>
          </div>
        </div>

        {/* ── Appearance ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={{ ...glassCard, animationDelay: '0.08s' }}>
          <div className="px-5 py-4" style={{ borderBottom: borderStyle }}>
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-xl bg-cyan-500/20 flex items-center justify-center shadow-sm">
                <Palette className="w-4 h-4 text-cyan-400" />
              </div>
              <div>
                <h2 className={`text-sm font-bold ${text.heading}`}>Appearance</h2>
                <p className={`text-xs ${text.muted} mt-0.5`}>Display preference for this device</p>
              </div>
            </div>
          </div>
          <div className="p-5">
            <div className="flex items-center justify-between">
              <div className="flex items-start gap-3">
                <div className={`w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0 ${isDark ? 'bg-cyan-500/15' : 'bg-amber-100'}`}>
                  {isDark ? <Moon className="w-4 h-4 text-cyan-500" /> : <Sun className="w-4 h-4 text-amber-500" />}
                </div>
                <div>
                  <p className={`text-sm font-semibold ${text.heading}`}>Dark Mode</p>
                  <p className={`text-xs ${text.muted} mt-0.5 leading-relaxed`}>
                    Easier on the eyes during night shifts. Applied immediately and remembered on this device.
                  </p>
                </div>
              </div>
              <Toggle enabled={isDark} onToggle={toggle} ariaLabel="Toggle dark mode" />
            </div>
          </div>
        </div>

        {/* ── System Information ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={{ ...glassCard, animationDelay: '0.16s' }}>
          <div className="px-5 py-4" style={{ borderBottom: borderStyle }}>
            <div className="flex items-center gap-3">
              <div className="w-9 h-9 rounded-xl bg-cyan-500/20 flex items-center justify-center shadow-sm">
                <Cpu className="w-4 h-4 text-cyan-400" />
              </div>
              <div>
                <h2 className={`text-sm font-bold ${text.heading}`}>System Information</h2>
                <p className={`text-xs ${text.muted} mt-0.5`}>Current environment</p>
              </div>
            </div>
          </div>
          <div className="p-5 space-y-3">
            {[
              { label: 'Application', value: 'SmartTriage', icon: Cpu },
              { label: 'Database', value: 'Connected', icon: Database, ok: true },
              { label: 'API', value: 'Connected', icon: Zap, ok: true },
            ].map((item) => (
              <div key={item.label} className="flex items-center justify-between rounded-xl px-3 py-2" style={glassInner}>
                <div className="flex items-center gap-2.5">
                  <item.icon className={`w-4 h-4 ${text.muted}`} />
                  <span className={`text-xs font-medium ${text.body}`}>{item.label}</span>
                </div>
                <span className={`inline-flex items-center gap-1.5 text-xs font-semibold ${text.heading}`}>
                  {item.ok && <CheckCircle className="w-3.5 h-3.5 text-emerald-500" />}
                  {item.value}
                </span>
              </div>
            ))}
          </div>
        </div>

      </div>
    </div>
  );
}

// ── Toggle switch ──
function Toggle({ enabled, onToggle, ariaLabel }: { enabled: boolean; onToggle: () => void; ariaLabel?: string }) {
  return (
    <button
      onClick={onToggle}
      role="switch"
      aria-checked={enabled}
      aria-label={ariaLabel}
      className={`relative w-12 h-7 rounded-full transition-all duration-300 flex-shrink-0 ml-4 ${
        enabled
          ? 'bg-gradient-to-r from-cyan-600 to-cyan-500 shadow-md shadow-cyan-600/30'
          : 'bg-slate-300 hover:bg-slate-400 dark:bg-white/10 dark:hover:bg-white/20'
      }`}
    >
      <span
        className={`absolute top-0.5 left-0.5 w-6 h-6 rounded-full bg-white shadow-md transition-all duration-300 ${
          enabled ? 'translate-x-5' : 'translate-x-0'
        }`}
      />
    </button>
  );
}
