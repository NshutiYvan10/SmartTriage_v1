/* ═══════════════════════════════════════════════════════════════
   AI Alert Intelligence — Real-time clinical alerts from API
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  AlertCircle, AlertTriangle, Clock, CheckCircle, Shield, Search, Eye,
  Activity, Brain, TrendingUp, XCircle,
  Loader2, RefreshCw, X, MessageSquare, ExternalLink, BellRing,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { alertApi } from '@/api/alerts';
import { subscribeToAlerts } from '@/api/websocket';
import { categoryFor, styleFor } from '@/utils/alertCategory';
import type { ClinicalAlertResponse } from '@/api/types';
import { formatDistanceToNow } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

// ── Severity config ──
const SEVERITY_STYLE: Record<string, {
  iconBg: string; iconColor: string; badgeBg: string;
  badgeBorder: string; badgeText: string; cardBorder: string;
}> = {
  CRITICAL: {
    iconBg: 'rgba(239,68,68,0.1)', iconColor: 'text-red-500',
    badgeBg: 'rgba(239,68,68,0.08)', badgeBorder: '1px solid rgba(239,68,68,0.2)',
    badgeText: 'text-red-600', cardBorder: '1px solid rgba(239,68,68,0.25)',
  },
  HIGH: {
    iconBg: 'rgba(245,158,11,0.1)', iconColor: 'text-amber-500',
    badgeBg: 'rgba(245,158,11,0.08)', badgeBorder: '1px solid rgba(245,158,11,0.2)',
    badgeText: 'text-amber-600', cardBorder: '1px solid rgba(245,158,11,0.25)',
  },
  MEDIUM: {
    iconBg: 'rgba(234,179,8,0.1)', iconColor: 'text-yellow-500',
    badgeBg: 'rgba(234,179,8,0.08)', badgeBorder: '1px solid rgba(234,179,8,0.2)',
    badgeText: 'text-yellow-600', cardBorder: '1px solid rgba(234,179,8,0.25)',
  },
  LOW: {
    iconBg: 'rgba(59,130,246,0.1)', iconColor: 'text-blue-500',
    badgeBg: 'rgba(59,130,246,0.08)', badgeBorder: '1px solid rgba(59,130,246,0.2)',
    badgeText: 'text-blue-600', cardBorder: '1px solid rgba(59,130,246,0.25)',
  },
  INFO: {
    iconBg: 'rgba(148,163,184,0.12)', iconColor: 'text-slate-400',
    badgeBg: 'rgba(148,163,184,0.1)', badgeBorder: '1px solid rgba(148,163,184,0.2)',
    badgeText: 'text-slate-500', cardBorder: '1px solid rgba(148,163,184,0.15)',
  },
};

function getStyle(severity: string, acknowledged: boolean) {
  if (acknowledged) return SEVERITY_STYLE.INFO;
  return SEVERITY_STYLE[severity] || SEVERITY_STYLE.INFO;
}

type FilterMode = 'all' | 'active' | 'acknowledged';

export function AlertsView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [alerts, setAlerts] = useState<ClinicalAlertResponse[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<FilterMode>('all');
  const [categoryFilter, setCategoryFilter] = useState<'all' | 'CLINICAL' | 'OPERATIONAL' | 'SYSTEM'>('all');
  const [searchQuery, setSearchQuery] = useState('');

  // ── Comment dialog state ──
  const [dialogOpen, setDialogOpen] = useState(false);
  const [dialogMode, setDialogMode] = useState<'acknowledge' | 'dismiss'>('acknowledge');
  const [dialogAlertId, setDialogAlertId] = useState<string | null>(null);
  const [dialogComment, setDialogComment] = useState('');
  const [dialogSubmitting, setDialogSubmitting] = useState(false);

  // ── Data loading ──
  const loadAlerts = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const page = await alertApi.getAll(hospitalId, 0, 200);
      setAlerts(page.content || []);
    } catch (err) {
      console.error('[AlertsView] Failed to load alerts:', err);
      setAlerts([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId]);

  useEffect(() => { loadAlerts(); }, [loadAlerts]);

  // Live WebSocket subscription. Replaces the previous 30-second poll
  // so a CRITICAL alert appears here instantly, not on the next tick.
  // The hospital topic catches everything; cross-zone reach is the
  // server's responsibility to gate. Backstop refresh every 5 minutes
  // catches the rare case of a missed frame during reconnect.
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToAlerts(hospitalId, (incoming) => {
      setAlerts((prev) => {
        // Dedupe by id — the server publishes both create and update
        // events, and the user-targeted topic may overlap with the
        // hospital topic.
        const next = prev.filter((a) => a.id !== incoming.id);
        next.unshift(incoming);
        return next;
      });
    });
    const iv = setInterval(loadAlerts, 5 * 60 * 1000);
    return () => { unsub(); clearInterval(iv); };
  }, [hospitalId, loadAlerts]);

  // ── Filtering ──
  const filteredAlerts = alerts
    .filter((a) => {
      if (filter === 'active') return !a.acknowledged;
      if (filter === 'acknowledged') return a.acknowledged;
      return true;
    })
    .filter((a) => {
      if (categoryFilter === 'all') return true;
      return categoryFor(a.alertType) === categoryFilter;
    })
    .filter((a) => {
      if (!searchQuery.trim()) return true;
      const q = searchQuery.toLowerCase();
      return (
        a.message?.toLowerCase().includes(q) ||
        a.alertType?.toLowerCase().includes(q) ||
        a.patientName?.toLowerCase().includes(q)
      );
    })
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

  const stats = {
    total: alerts.length,
    active: alerts.filter(a => !a.acknowledged).length,
    acknowledged: alerts.filter(a => a.acknowledged).length,
    critical: alerts.filter(a => a.severity === 'CRITICAL' && !a.acknowledged).length,
  };

  // ── Acknowledge (direct) ──
  const handleAcknowledge = async (alertId: string) => {
    try {
      await alertApi.acknowledge(alertId);
      loadAlerts();
    } catch (err) { console.error(err); }
  };

  // ── Open comment dialog ──
  const openDialog = (mode: 'acknowledge' | 'dismiss', alertId: string) => {
    setDialogMode(mode);
    setDialogAlertId(alertId);
    setDialogComment('');
    setDialogOpen(true);
  };

  // ── Submit dialog ──
  const submitDialog = async () => {
    if (!dialogAlertId) return;
    setDialogSubmitting(true);
    try {
      await alertApi.acknowledge(dialogAlertId);
      setDialogOpen(false);
      loadAlerts();
    } catch (err) {
      console.error(err);
    } finally {
      setDialogSubmitting(false);
    }
  };

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center shadow-lg">
                  <Brain className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">AI Alert Intelligence</h1>
                  <p className="text-white/70 text-xs font-medium">Real-time automated monitoring & clinical alerts</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                  <div className="w-2 h-2 bg-emerald-400 rounded-full animate-pulse" />
                  <span className="text-xs font-semibold text-white/90">Live</span>
                </div>
                {stats.critical > 0 && (
                  <div className="bg-red-500/20 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2 border border-red-400/30">
                    <AlertTriangle className="w-3.5 h-3.5 text-red-300" />
                    <span className="text-xs font-bold text-red-200">{stats.critical} Critical</span>
                  </div>
                )}
                <button
                  onClick={loadAlerts}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                >
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* ── Stats Cards ── */}
        <div className="grid grid-cols-4 gap-3">
          {[
            { label: 'Total', value: stats.total, icon: BellRing, color: 'text-cyan-500', bg: 'rgba(6,182,212,0.1)' },
            { label: 'Active', value: stats.active, icon: AlertCircle, color: 'text-amber-500', bg: 'rgba(245,158,11,0.1)' },
            { label: 'Acknowledged', value: stats.acknowledged, icon: CheckCircle, color: 'text-emerald-500', bg: 'rgba(34,197,94,0.1)' },
            { label: 'Critical', value: stats.critical, icon: Shield, color: 'text-red-500', bg: 'rgba(239,68,68,0.1)' },
          ].map((s) => {
            const Icon = s.icon;
            return (
              <div key={s.label} className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-xl flex items-center justify-center" style={{ backgroundColor: s.bg }}>
                    <Icon className={`w-4 h-4 ${s.color}`} />
                  </div>
                  <div>
                    <p className={`text-xl font-black ${s.color}`}>{s.value}</p>
                    <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>{s.label}</p>
                  </div>
                </div>
              </div>
            );
          })}
        </div>

        {/* ── AI Capabilities ── */}
        <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
          <div className="flex items-center justify-between mb-3">
            <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>AI Monitoring Capabilities</h3>
          </div>
          <div className="grid grid-cols-3 gap-3">
            {[
              { icon: Activity, label: 'Vital Sign Monitoring', desc: 'Continuous analysis of patient vitals', color: 'text-cyan-600', bg: 'rgba(6,182,212,0.1)' },
              { icon: TrendingUp, label: 'Predictive Analytics', desc: 'Early-warning deterioration detection', color: 'text-indigo-500', bg: 'rgba(99,102,241,0.1)' },
              { icon: CheckCircle, label: 'Clinical Validation', desc: 'Acknowledge & document interventions', color: 'text-emerald-500', bg: 'rgba(34,197,94,0.1)' },
            ].map((cap) => {
              const Icon = cap.icon;
              return (
                <div key={cap.label} className="flex items-center gap-2.5">
                  <div className="w-8 h-8 rounded-lg flex items-center justify-center flex-shrink-0" style={{ backgroundColor: cap.bg }}>
                    <Icon className={`w-4 h-4 ${cap.color}`} />
                  </div>
                  <div className="min-w-0">
                    <p className={`text-[11px] font-bold leading-tight ${text.heading}`}>{cap.label}</p>
                    <p className={`text-[10px] font-medium leading-tight ${text.muted}`}>{cap.desc}</p>
                  </div>
                </div>
              );
            })}
          </div>
        </div>

        {/* ── Search & Filter ── */}
        <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
          <div className="flex flex-col sm:flex-row sm:items-center gap-3">
            <div className="relative flex-1">
              <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                value={searchQuery}
                onChange={(e) => setSearchQuery(e.target.value)}
                placeholder="Search alerts by message, type, or patient…"
                className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
              />
            </div>
            <div className="flex items-center gap-2 flex-wrap">
              {(['all', 'active', 'acknowledged'] as FilterMode[]).map((f) => (
                <button
                  key={f}
                  onClick={() => setFilter(f)}
                  className={`inline-flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold rounded-lg transition-all ${
                    filter === f
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                      : isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                  }`}
                >
                  {f === 'all' && <AlertCircle className="w-3 h-3" />}
                  {f === 'active' && <Clock className="w-3 h-3" />}
                  {f === 'acknowledged' && <CheckCircle className="w-3 h-3" />}
                  {f.charAt(0).toUpperCase() + f.slice(1)}
                </button>
              ))}
            </div>
          </div>

          {/* Category filter — clinical alerts (life-threatening),
              operational (workflow), system (devices). Lets a user
              focus on the bucket that matches their job in the
              moment without needing to scan every row. */}
          <div className="mt-2 flex items-center gap-2 flex-wrap">
            <span className="text-[10px] uppercase font-bold text-slate-400 tracking-wider">
              Category
            </span>
            {(['all', 'CLINICAL', 'OPERATIONAL', 'SYSTEM'] as const).map((c) => (
              <button
                key={c}
                onClick={() => setCategoryFilter(c)}
                className={`px-2.5 py-1 text-[10px] font-bold uppercase tracking-wide rounded-lg border transition-all ${
                  categoryFilter === c
                    ? c === 'CLINICAL'    ? 'bg-rose-600 text-white border-rose-700'
                    : c === 'OPERATIONAL' ? 'bg-sky-600 text-white border-sky-700'
                    : c === 'SYSTEM'      ? 'bg-slate-600 text-white border-slate-700'
                    : 'bg-slate-800 text-white border-slate-900'
                    : isDark ? 'text-slate-400 border-slate-700 hover:text-white' : 'text-slate-500 border-slate-200 hover:text-slate-800 hover:bg-white/60'
                }`}
              >
                {c === 'all' ? 'All' : c.charAt(0) + c.slice(1).toLowerCase()}
              </button>
            ))}
          </div>
        </div>

        {/* ── List Header ── */}
        <div className="flex items-center justify-between px-1 animate-fade-up">
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(99,102,241,0.12)' }}>
              <Brain className={`w-4 h-4 ${isDark ? 'text-indigo-400' : 'text-indigo-500'}`} />
            </div>
            <div>
              <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>AI-Generated Alerts</h3>
              <p className={`text-[11px] font-medium ${text.muted}`}>
                {filteredAlerts.length} alert{filteredAlerts.length !== 1 ? 's' : ''} matching your criteria
              </p>
            </div>
          </div>
        </div>

        {/* ── Alerts List ── */}
        {loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 className="w-7 h-7 animate-spin text-cyan-500" />
          </div>
        ) : filteredAlerts.length === 0 ? (
          <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
            <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(34,197,94,0.1)' }}>
              <CheckCircle className="w-8 h-8 text-emerald-400" />
            </div>
            <p className={`text-sm font-bold ${text.heading}`}>All Clear</p>
            <p className={`text-xs font-medium mt-1 ${text.muted}`}>
              {filter === 'all' && 'No alerts in the system right now'}
              {filter === 'active' && 'No active alerts requiring attention'}
              {filter === 'acknowledged' && 'No acknowledged alerts to display'}
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {filteredAlerts.map((alert, idx) => {
              const style = getStyle(alert.severity, alert.acknowledged);
              const isCritical = alert.severity === 'CRITICAL' && !alert.acknowledged;

              return (
                <div
                  key={alert.id}
                  className={`rounded-2xl p-5 transition-all animate-fade-up ${
                    alert.acknowledged ? 'opacity-65' : 'hover:-translate-y-0.5'
                  } ${isCritical ? 'animate-critical-border' : ''}`}
                  style={{
                    ...glassCard,
                    border: style.cardBorder,
                    animationDelay: `${0.05 + idx * 0.04}s`,
                  } as React.CSSProperties}
                >
                  <div className="flex items-start gap-4">
                    {/* Severity Icon */}
                    <div className="w-11 h-11 rounded-xl flex items-center justify-center flex-shrink-0" style={{ backgroundColor: style.iconBg }}>
                      {alert.severity === 'CRITICAL'
                        ? <AlertTriangle className={`w-5 h-5 ${style.iconColor}`} />
                        : <AlertCircle className={`w-5 h-5 ${style.iconColor}`} />}
                    </div>

                    {/* Content */}
                    <div className="flex-1 min-w-0">
                      {/* Badges */}
                      <div className="flex items-center gap-2.5 mb-2 flex-wrap">
                        <span
                          className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider ${style.badgeText}`}
                          style={{ background: style.badgeBg, border: style.badgeBorder }}
                        >
                          {alert.severity}
                        </span>
                        {/* Category chip — clinical / operational / system.
                            Lets a clinician scan past operational noise to
                            the life-threatening stuff at a glance. */}
                        {(() => {
                          const cat = categoryFor(alert.alertType);
                          const cs = styleFor(cat);
                          return (
                            <span className={`inline-flex items-center px-2 py-0.5 text-[10px] font-bold rounded-md border uppercase tracking-wide ${cs.chipClass}`}>
                              {cs.label}
                            </span>
                          );
                        })()}
                        <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
                          {alert.alertType?.replace(/_/g, ' ')}
                        </span>
                        {alert.targetZone && (
                          <span className={`text-[10px] font-bold px-2 py-0.5 rounded-lg ${isDark ? 'bg-cyan-500/10 text-cyan-400' : 'bg-cyan-50 text-cyan-600'}`}>
                            {alert.targetZone}
                          </span>
                        )}
                        <span className={`ml-auto text-[10px] font-medium flex items-center gap-1 ${text.muted}`}>
                          <Clock className="w-3 h-3" />
                          {alert.createdAt ? formatDistanceToNow(new Date(alert.createdAt), { addSuffix: true }) : '—'}
                        </span>
                      </div>

                      {/* Message */}
                      <p className={`text-[13px] font-bold leading-relaxed ${alert.acknowledged ? text.muted : text.heading}`}>
                        {alert.message}
                      </p>

                      {alert.title && (
                        <p className={`text-xs mt-1 ${text.body}`}>{alert.title}</p>
                      )}

                      {/* Patient info */}
                      {alert.patientName && (
                        <p className={`text-[11px] font-medium mt-1.5 ${text.accent}`}>
                          Patient: <span className="font-bold">{alert.patientName}</span>
                          {alert.visitNumber && <span className={`text-[10px] ml-2 ${text.muted}`}>#{alert.visitNumber}</span>}
                        </p>
                      )}

                      {/* Doctor escalation info */}
                      {alert.targetDoctorName && (
                        <p className={`text-[10px] mt-1 ${text.muted}`}>
                          Escalated to: <span className="font-bold">{alert.targetDoctorName}</span>
                          {alert.escalationTier > 1 && ` • Tier ${alert.escalationTier}`}
                        </p>
                      )}

                      {/* Acknowledged info */}
                      {alert.acknowledged && alert.acknowledgedAt && (
                        <div
                          className="flex items-center gap-2 mt-3 px-3 py-2.5 rounded-xl"
                          style={{ background: 'rgba(34,197,94,0.06)', border: '1px solid rgba(34,197,94,0.15)' }}
                        >
                          <CheckCircle className="w-3.5 h-3.5 text-emerald-500 flex-shrink-0" />
                          <span className={`text-[11px] font-medium ${isDark ? 'text-emerald-400' : 'text-emerald-700'}`}>
                            Acknowledged by <span className="font-bold">{alert.acknowledgedByName || 'Staff'}</span>
                            {' '}{formatDistanceToNow(new Date(alert.acknowledgedAt), { addSuffix: true })}
                          </span>
                        </div>
                      )}
                    </div>

                    {/* Actions */}
                    {!alert.acknowledged && (
                      <div className="flex-shrink-0 pt-1 flex flex-col gap-2">
                        <button
                          onClick={() => openDialog('acknowledge', alert.id)}
                          className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold text-white bg-gradient-to-r from-slate-800 to-slate-700 hover:shadow-lg hover:-translate-y-0.5 rounded-xl transition-all shadow-md shadow-slate-800/15"
                        >
                          <Eye className="w-3.5 h-3.5" /> Acknowledge
                        </button>
                        {alert.visitId && (
                          <button
                            onClick={async () => {
                              await handleAcknowledge(alert.id);
                              navigate(`/visit/${alert.visitId}`);
                            }}
                            className="inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold rounded-xl bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-all"
                          >
                            <ExternalLink className="w-3.5 h-3.5" /> View Patient
                          </button>
                        )}
                        <button
                          onClick={() => openDialog('dismiss', alert.id)}
                          className={`inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                            isDark ? 'text-slate-400 bg-white/5 hover:bg-white/10 border border-white/10' : 'text-slate-500 bg-white/60 hover:bg-white/80 border border-slate-200/60'
                          }`}
                        >
                          <XCircle className="w-3.5 h-3.5" /> Dismiss
                        </button>
                      </div>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>

      {/* ═══════════════════════════════════════════════════════════════
         Comment Dialog — replaces native prompt()
         ═══════════════════════════════════════════════════════════════ */}
      {dialogOpen && (
        <div className="fixed inset-0 z-[100] flex items-center justify-center">
          {/* Backdrop */}
          <div
            className="absolute inset-0 bg-black/50 backdrop-blur-sm"
            onClick={() => !dialogSubmitting && setDialogOpen(false)}
          />

          {/* Dialog card */}
          <div
            className="relative w-full max-w-md mx-4 rounded-2xl p-6 shadow-2xl animate-scale-in"
            style={glassCard}
          >
            {/* Header */}
            <div className="flex items-center justify-between mb-5">
              <div className="flex items-center gap-3">
                <div className={`w-10 h-10 rounded-xl flex items-center justify-center ${
                  dialogMode === 'dismiss' ? 'bg-red-500/10' : 'bg-emerald-500/10'
                }`}>
                  {dialogMode === 'dismiss'
                    ? <XCircle className="w-5 h-5 text-red-500" />
                    : <CheckCircle className="w-5 h-5 text-emerald-500" />}
                </div>
                <div>
                  <h3 className={`text-sm font-bold ${text.heading}`}>
                    {dialogMode === 'dismiss' ? 'Dismiss Alert' : 'Acknowledge Alert'}
                  </h3>
                  <p className={`text-[11px] ${text.muted}`}>
                    {dialogMode === 'dismiss'
                      ? 'Please provide a reason for dismissing this alert'
                      : 'Add an optional clinical note'}
                  </p>
                </div>
              </div>
              <button
                onClick={() => !dialogSubmitting && setDialogOpen(false)}
                className={`w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                  isDark ? 'hover:bg-white/10 text-slate-400' : 'hover:bg-slate-100 text-slate-500'
                }`}
              >
                <X className="w-4 h-4" />
              </button>
            </div>

            {/* Comment Input */}
            <div className="mb-5">
              <label className={`block text-[11px] font-bold uppercase tracking-wider mb-2 ${text.muted}`}>
                <MessageSquare className="w-3 h-3 inline mr-1" />
                {dialogMode === 'dismiss' ? 'Reason *' : 'Comment (optional)'}
              </label>
              <textarea
                value={dialogComment}
                onChange={(e) => setDialogComment(e.target.value)}
                placeholder={dialogMode === 'dismiss' ? 'Enter reason for dismissal…' : 'Add a clinical note…'}
                rows={3}
                autoFocus
                className={`w-full px-4 py-3 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/30 transition-all ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
              />
            </div>

            {/* Actions */}
            <div className="flex items-center justify-end gap-3">
              <button
                onClick={() => !dialogSubmitting && setDialogOpen(false)}
                disabled={dialogSubmitting}
                className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                  isDark ? 'text-slate-400 hover:bg-white/5' : 'text-slate-500 hover:bg-slate-50'
                }`}
              >
                Cancel
              </button>
              <button
                onClick={submitDialog}
                disabled={dialogSubmitting || (dialogMode === 'dismiss' && !dialogComment.trim())}
                className={`inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold text-white rounded-xl shadow-lg transition-all disabled:opacity-50 disabled:cursor-not-allowed ${
                  dialogMode === 'dismiss'
                    ? 'bg-gradient-to-r from-red-500 to-red-600 shadow-red-500/20 hover:shadow-red-500/30'
                    : 'bg-gradient-to-r from-emerald-500 to-emerald-600 shadow-emerald-500/20 hover:shadow-emerald-500/30'
                } ${dialogSubmitting ? '' : 'hover:-translate-y-0.5'}`}
              >
                {dialogSubmitting ? (
                  <Loader2 className="w-3.5 h-3.5 animate-spin" />
                ) : dialogMode === 'dismiss' ? (
                  <XCircle className="w-3.5 h-3.5" />
                ) : (
                  <CheckCircle className="w-3.5 h-3.5" />
                )}
                {dialogSubmitting
                  ? 'Processing…'
                  : dialogMode === 'dismiss' ? 'Dismiss Alert' : 'Acknowledge'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
