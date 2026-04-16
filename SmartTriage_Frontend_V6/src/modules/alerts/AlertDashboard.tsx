/* ═══════════════════════════════════════════════════════════════
   Alert Dashboard — Hospital-wide clinical alerts view
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  BellRing, AlertTriangle, CheckCircle2, RefreshCw,
  Loader2, ShieldAlert, Activity, Clock, ExternalLink,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { alertApi } from '@/api/alerts';
import type { ClinicalAlertResponse } from '@/api/types';
import { format } from 'date-fns';

const SEVERITY_CONFIG: Record<string, { color: string; bg: string; border: string; icon: typeof AlertTriangle }> = {
  CRITICAL: { color: 'text-red-500', bg: 'bg-red-500/10', border: 'border-red-500/20', icon: ShieldAlert },
  HIGH:     { color: 'text-orange-500', bg: 'bg-orange-500/10', border: 'border-orange-500/20', icon: AlertTriangle },
  MEDIUM:   { color: 'text-amber-500', bg: 'bg-amber-500/10', border: 'border-amber-500/20', icon: Activity },
  LOW:      { color: 'text-blue-500', bg: 'bg-blue-500/10', border: 'border-blue-500/20', icon: BellRing },
  INFO:     { color: 'text-slate-500', bg: 'bg-slate-500/10', border: 'border-slate-500/20', icon: BellRing },
};

type FilterMode = 'all' | 'unacknowledged' | 'critical';

export function AlertDashboard() {
  const { glassCard, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const navigate = useNavigate();
  const hospitalId = user?.hospitalId || 'a0000000-0000-0000-0000-000000000001';

  const [alerts, setAlerts] = useState<ClinicalAlertResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<FilterMode>('unacknowledged');

  const loadAlerts = useCallback(async () => {
    setLoading(true);
    try {
      let data;
      switch (filter) {
        case 'unacknowledged':
          data = await alertApi.getUnacknowledged(hospitalId, 0, 100);
          break;
        case 'critical':
          data = await alertApi.getCritical(hospitalId, 0, 100);
          break;
        default:
          data = await alertApi.getAll(hospitalId, 0, 100);
      }
      setAlerts(data.content || []);
    } catch (err) {
      console.error('Failed to load alerts:', err);
      setAlerts([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, filter]);

  useEffect(() => { loadAlerts(); }, [loadAlerts]);

  const handleAcknowledge = async (alertId: string) => {
    try {
      await alertApi.acknowledge(alertId);
      loadAlerts();
    } catch (err) {
      console.error(err);
    }
  };

  const unackCount = alerts.filter(a => !a.acknowledged).length;
  const critCount = alerts.filter(a => a.severity === 'CRITICAL' && !a.acknowledged).length;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-red-500/20 flex items-center justify-center">
                  <BellRing className="w-5 h-5 text-red-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Alert Dashboard</h1>
                  <p className="text-white/50 text-xs">Hospital-wide clinical alerts monitoring</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {critCount > 0 && (
                  <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-red-500/20 border border-red-500/30">
                    <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
                    <span className="text-red-400 text-xs font-bold">{critCount} Critical</span>
                  </div>
                )}
                <div className="px-3 py-1.5 rounded-lg bg-white/10">
                  <span className="text-white/70 text-xs font-bold">{unackCount} Unacknowledged</span>
                </div>
                <button onClick={loadAlerts} className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors">
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>

          {/* Filter Tabs */}
          <div className="flex gap-1 px-4 py-2" style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)' }}>
            {([['all', 'All Alerts'], ['unacknowledged', 'Unacknowledged'], ['critical', 'Critical Only']] as [FilterMode, string][]).map(([key, label]) => (
              <button
                key={key}
                onClick={() => setFilter(key)}
                className={`px-4 py-2 text-[11px] font-bold rounded-lg transition-all ${
                  filter === key
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                    : isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                }`}
              >
                {label}
              </button>
            ))}
          </div>
        </div>

        {/* Alerts List */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
          </div>
        ) : alerts.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <CheckCircle2 className="w-10 h-10 mx-auto mb-3 text-emerald-500" />
            <p className={`text-sm font-bold ${text.heading}`}>All clear!</p>
            <p className={text.muted}>No alerts matching the current filter</p>
          </div>
        ) : (
          <div className="space-y-3">
            {alerts.map((alert, i) => {
              const sev = SEVERITY_CONFIG[alert.severity] || SEVERITY_CONFIG.INFO;
              const Icon = sev.icon;
              return (
                <div
                  key={alert.id}
                  className={`rounded-2xl p-4 border transition-all animate-fade-up ${alert.acknowledged ? 'opacity-60' : ''}`}
                  style={{ ...glassCard, animationDelay: `${i * 0.03}s`, borderColor: alert.acknowledged ? 'transparent' : undefined }}
                >
                  <div className="flex items-start gap-4">
                    <div className={`w-10 h-10 rounded-xl ${sev.bg} flex items-center justify-center shrink-0`}>
                      <Icon className={`w-5 h-5 ${sev.color}`} />
                    </div>
                    <div className="flex-1 min-w-0">
                      <div className="flex items-center gap-2 mb-1">
                        <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg border ${sev.bg} ${sev.color} ${sev.border}`}>
                          {alert.severity}
                        </span>
                        <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>{alert.alertType?.replace(/_/g, ' ')}</span>
                        <span className={`ml-auto text-[10px] flex items-center gap-1 ${text.muted}`}>
                          <Clock className="w-3 h-3" />
                          {alert.createdAt ? format(new Date(alert.createdAt), 'dd MMM yyyy HH:mm') : '—'}
                        </span>
                      </div>
                      <p className={`text-sm font-medium ${text.heading}`}>{alert.message}</p>
                      {alert.title && <p className={`text-xs mt-1 ${text.body}`}>{alert.title}</p>}
                      {alert.patientName && <p className={`text-xs mt-1 ${text.accent}`}>Patient: {alert.patientName}</p>}

                      {alert.acknowledged ? (
                        <p className="text-[10px] mt-2 text-emerald-500 flex items-center gap-1">
                          <CheckCircle2 className="w-3 h-3" />
                          Acknowledged by {alert.acknowledgedByName} — {alert.acknowledgedAt ? format(new Date(alert.acknowledgedAt), 'dd MMM HH:mm') : ''}
                        </p>
                      ) : (
                        <div className="flex items-center gap-2 mt-3">
                          <button
                            onClick={() => handleAcknowledge(alert.id)}
                            className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors"
                          >
                            <CheckCircle2 className="w-3.5 h-3.5" /> Acknowledge
                          </button>
                          {alert.visitId && (
                            <button
                              onClick={async () => {
                                await handleAcknowledge(alert.id);
                                navigate(`/visit/${alert.visitId}`);
                              }}
                              className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors"
                            >
                              <ExternalLink className="w-3.5 h-3.5" /> Acknowledge & View Patient
                            </button>
                          )}
                        </div>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
