/* ═══════════════════════════════════════════════════════════════
   RegistrarHome — the REGISTRAR dashboard.

   Replaces the generic HospitalDashboard (ER triage metrics, arrival
   charts, whole-hospital clinical census) with the registration desk's
   own surface:
     • RFID tap-to-identify banner (a card tap surfaces the patient here)
     • Unresolved-identities queue — PROMINENT, with time-since-admission
       and escalation status, so a temporary patient is never forgotten
     • Live desk counters (today's intake, awaiting triage, active, unresolved)
     • One-tap actions: register, global registry search, desk reports
   Everything is hospital-scoped desk data — no clinical PHI over-share.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  UserPlus, Globe, ClipboardList, UserX, Loader2, RefreshCw,
  Users, Activity, AlertTriangle, ChevronRight, ScanLine,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { useTheme } from '@/hooks/useTheme';
import { registrarApi, type CensusResponse, type UnidentifiedPatientRow } from '@/api/registrar';
import { format } from 'date-fns';
import { RfidPatientFoundBanner } from './RfidPatientFoundBanner';

/** Escalation colour by hours the identity has been unresolved (mirrors the reminder tiers). */
function overdueTone(hours: number | null): { cls: string; label: string } {
  if (hours == null) return { cls: 'text-slate-400', label: '' };
  if (hours >= 2) return { cls: 'text-rose-500', label: 'Escalated to charge nurse' };
  if (hours >= 0.5) return { cls: 'text-amber-500', label: 'Registrar reminder active' };
  return { cls: 'text-emerald-500', label: 'Just arrived' };
}

export function RegistrarHome() {
  const navigate = useNavigate();
  const { cardClass, glassCard, isDark, text } = useTheme();
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const firstName = user?.fullName?.split(' ')?.[0] || 'there';

  const [census, setCensus] = useState<CensusResponse | null>(null);
  const [unidentified, setUnidentified] = useState<UnidentifiedPatientRow[]>([]);
  const [todayCount, setTodayCount] = useState<number | null>(null);
  const [loading, setLoading] = useState(true);

  const load = useCallback(async () => {
    if (!hospitalId) { setLoading(false); return; }
    setLoading(true);
    const today = format(new Date(), 'yyyy-MM-dd');
    const results = await Promise.allSettled([
      registrarApi.getCensus(hospitalId),
      registrarApi.getUnidentified(hospitalId),
      registrarApi.getIntakeLog(hospitalId, today, today),
    ]);
    if (results[0].status === 'fulfilled') setCensus(results[0].value);
    if (results[1].status === 'fulfilled') setUnidentified(results[1].value ?? []);
    if (results[2].status === 'fulfilled') setTodayCount((results[2].value ?? []).length);
    setLoading(false);
  }, [hospitalId]);

  useEffect(() => { void load(); }, [load]);

  const awaitingTriage = census
    ? (census.byStatus['AWAITING_TRIAGE'] ?? 0) + (census.byStatus['REGISTERED'] ?? 0)
    : 0;

  const tiles = [
    { key: 'today', label: 'Registered today', value: todayCount ?? '—', icon: UserPlus,
      tone: 'text-cyan-600', border: '1px solid rgba(6,182,212,0.25)', to: '/registrar-reports' },
    { key: 'awaiting', label: 'Awaiting triage', value: awaitingTriage, icon: Activity,
      tone: 'text-violet-600', border: '1px solid rgba(139,92,246,0.25)', to: '/patients' },
    { key: 'unresolved', label: 'Unresolved identities', value: unidentified.length, icon: UserX,
      tone: unidentified.length > 0 ? 'text-amber-600' : 'text-emerald-600',
      border: unidentified.length > 0 ? '1px solid rgba(245,158,11,0.3)' : '1px solid rgba(16,185,129,0.2)',
      to: '/registrar-reports' },
    { key: 'active', label: 'Active visits', value: census?.totalActive ?? '—', icon: Users,
      tone: 'text-emerald-600', border: '1px solid rgba(16,185,129,0.2)', to: '/patients' },
  ];

  const actions = [
    { label: 'Register patient', icon: UserPlus, to: '/entry', primary: true },
    { label: 'Patient registry (all hospitals)', icon: Globe, to: '/registry' },
    { label: 'Desk reports', icon: ClipboardList, to: '/registrar-reports' },
  ];

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">
        {/* Header */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5 flex items-center gap-3">
            <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
              <UserPlus className="w-5 h-5 text-cyan-300" />
            </div>
            <div className="flex-1">
              <h1 className="text-lg font-bold text-white">Registration Desk — Good day, {firstName}</h1>
              <p className="text-sm text-white/50">Intake, identity resolution, and patient lookup at a glance.</p>
            </div>
            <button onClick={load} disabled={loading}
              className="inline-flex items-center gap-2 px-3 py-2 text-xs font-bold rounded-xl bg-white/10 hover:bg-white/20 text-white transition-colors">
              {loading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <RefreshCw className="w-3.5 h-3.5" />}
              Refresh
            </button>
          </div>
        </div>

        {/* RFID tap-to-identify — a card tap at the desk reader surfaces the patient here. */}
        <RfidPatientFoundBanner />

        {/* Quick actions */}
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
          {actions.map((a) => {
            const Icon = a.icon;
            return (
              <button key={a.label} type="button" onClick={() => navigate(a.to)}
                className={`flex items-center gap-2.5 rounded-2xl px-4 py-3 text-sm font-bold transition-all hover:-translate-y-0.5 ${
                  a.primary ? 'bg-cyan-600 hover:bg-cyan-700 text-white' : ''
                }`}
                style={a.primary ? undefined : { ...glassCard, border: borderStyle }}>
                <Icon className={`w-4 h-4 ${a.primary ? 'text-white' : 'text-cyan-600'}`} />
                <span className={a.primary ? 'text-white' : text.heading}>{a.label}</span>
              </button>
            );
          })}
        </div>

        {/* Metric tiles */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
          {tiles.map((t) => {
            const Icon = t.icon;
            return (
              <button key={t.key} type="button" onClick={() => navigate(t.to)}
                className="text-left rounded-2xl p-4 transition-all hover:-translate-y-0.5"
                style={{ ...glassCard, border: t.border }}>
                <div className="flex items-center justify-between">
                  <Icon className={`w-5 h-5 ${t.tone}`} />
                  <span className={`text-2xl font-bold ${text.heading}`}>{t.value}</span>
                </div>
                <p className={`text-[11px] mt-1 font-semibold ${text.body}`}>{t.label}</p>
              </button>
            );
          })}
        </div>

        {/* Unresolved identities — prominent, never buried (goal item 3/6) */}
        <div className={`${cardClass} overflow-hidden`} style={glassCard}>
          <div className="px-4 py-2.5 flex items-center gap-2" style={{ borderBottom: borderStyle }}>
            <UserX className="w-4 h-4 text-amber-500" />
            <span className={`text-sm font-bold ${text.heading}`}>Unresolved identities</span>
            <span className={`text-[11px] ${text.muted}`}>Temporary patients awaiting identification — oldest first</span>
          </div>
          {loading && unidentified.length === 0 ? (
            <div className={`text-center py-10 ${text.muted}`}><Loader2 className="w-6 h-6 animate-spin mx-auto opacity-50" /></div>
          ) : unidentified.length === 0 ? (
            <p className={`px-4 py-8 text-center text-sm ${text.muted}`}>
              <ScanLine className="w-6 h-6 mx-auto mb-2 opacity-50" />
              No unresolved identities — every patient on the floor is identified.
            </p>
          ) : (
            <ul>
              {unidentified.map((u) => {
                const tone = overdueTone(u.hoursWaiting);
                return (
                  <li key={u.patientId}
                      className={`px-4 py-2.5 flex items-center gap-3 border-b last:border-0 ${isDark ? 'border-white/5 hover:bg-white/5' : 'border-slate-100 hover:bg-slate-50'} cursor-pointer`}
                      onClick={() => navigate(`/patients/${u.patientId}`)}>
                    <div className="flex-1 min-w-0">
                      <span className={`text-sm font-bold ${text.heading}`}>
                        {u.placeholderLabel ? `Unknown ${u.placeholderLabel}` : 'Unidentified patient'}
                      </span>
                      <div className="flex items-center gap-2 mt-0.5">
                        {u.placeholderAssignedAt && (
                          <span className={`text-[11px] ${text.muted}`}>
                            Since {format(new Date(u.placeholderAssignedAt), 'MMM d, HH:mm')}
                          </span>
                        )}
                        {tone.label && (
                          <span className={`inline-flex items-center gap-1 text-[10px] font-bold ${tone.cls}`}>
                            <AlertTriangle className="w-3 h-3" /> {tone.label}
                          </span>
                        )}
                      </div>
                    </div>
                    {u.hoursWaiting != null && (
                      <span className={`text-xs font-bold ${tone.cls}`}>
                        {u.hoursWaiting < 1 ? `${Math.round(u.hoursWaiting * 60)}m` : `${u.hoursWaiting.toFixed(1)}h`}
                      </span>
                    )}
                    <ChevronRight className={`w-4 h-4 ${text.muted}`} />
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
