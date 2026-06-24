/* ═══════════════════════════════════════════════════════════════
   Registrar Reporting (R11) — operational front-desk reports
   for the registration desk:
     • Intake log     — registrations in a date window (+ CSV)
     • Unidentified   — the identity-reconciliation safety queue (+ CSV)
     • Census         — live active-visit counts by status / by zone
   Hospital-scoped; gated to REGISTRAR / HOSPITAL_ADMIN.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ClipboardList, UserX, Activity, Download, RefreshCw, Loader2,
  ChevronRight, AlertTriangle, Users, MapPin,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { registrarApi } from '@/api/registrar';
import type { IntakeLogRow, UnidentifiedPatientRow, CensusResponse } from '@/api/registrar';
import { saveBlob } from '@/api/client';
import { format } from 'date-fns';

type Tab = 'intake' | 'unidentified' | 'census';

const TABS: { value: Tab; label: string; icon: typeof ClipboardList }[] = [
  { value: 'intake', label: 'Intake Log', icon: ClipboardList },
  { value: 'unidentified', label: 'Unidentified Queue', icon: UserX },
  { value: 'census', label: 'Census', icon: Activity },
];

/** How overdue an unidentified patient is, by hours since placeholder assigned. */
const overdueColor = (hours: number | null): string => {
  if (hours == null) return 'text-slate-400';
  if (hours >= 24) return 'text-red-400';
  if (hours >= 4) return 'text-amber-400';
  return 'text-emerald-400';
};

export function RegistrarReportsView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [tab, setTab] = useState<Tab>('intake');

  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  /* ── Intake log ── */
  const today = format(new Date(), 'yyyy-MM-dd');
  const weekAgo = format(new Date(Date.now() - 6 * 24 * 60 * 60 * 1000), 'yyyy-MM-dd');
  const [from, setFrom] = useState(weekAgo);
  const [to, setTo] = useState(today);
  const [intake, setIntake] = useState<IntakeLogRow[]>([]);
  const [intakeLoading, setIntakeLoading] = useState(false);
  const [intakeErr, setIntakeErr] = useState<string | null>(null);

  const loadIntake = useCallback(async () => {
    if (!hospitalId) return;
    setIntakeLoading(true);
    setIntakeErr(null);
    try {
      setIntake(await registrarApi.getIntakeLog(hospitalId, from, to));
    } catch (e) {
      setIntakeErr(e instanceof Error ? e.message : 'Failed to load intake log');
      setIntake([]);
    } finally {
      setIntakeLoading(false);
    }
  }, [hospitalId, from, to]);

  /* ── Unidentified queue ── */
  const [unidentified, setUnidentified] = useState<UnidentifiedPatientRow[]>([]);
  const [unidLoading, setUnidLoading] = useState(false);
  const [unidErr, setUnidErr] = useState<string | null>(null);

  const loadUnidentified = useCallback(async () => {
    if (!hospitalId) return;
    setUnidLoading(true);
    setUnidErr(null);
    try {
      setUnidentified(await registrarApi.getUnidentified(hospitalId));
    } catch (e) {
      setUnidErr(e instanceof Error ? e.message : 'Failed to load unidentified queue');
      setUnidentified([]);
    } finally {
      setUnidLoading(false);
    }
  }, [hospitalId]);

  /* ── Census ── */
  const [census, setCensus] = useState<CensusResponse | null>(null);
  const [censusLoading, setCensusLoading] = useState(false);
  const [censusErr, setCensusErr] = useState<string | null>(null);

  const loadCensus = useCallback(async () => {
    if (!hospitalId) return;
    setCensusLoading(true);
    setCensusErr(null);
    try {
      setCensus(await registrarApi.getCensus(hospitalId));
    } catch (e) {
      setCensusErr(e instanceof Error ? e.message : 'Failed to load census');
      setCensus(null);
    } finally {
      setCensusLoading(false);
    }
  }, [hospitalId]);

  /* Load the active tab's data on switch (intake re-loads on range change too). */
  useEffect(() => {
    if (tab === 'intake') loadIntake();
    else if (tab === 'unidentified') loadUnidentified();
    else loadCensus();
  }, [tab, loadIntake, loadUnidentified, loadCensus]);

  /* ── CSV exports ── */
  const [exporting, setExporting] = useState(false);
  const exportIntakeCsv = async () => {
    if (!hospitalId) return;
    setExporting(true);
    try {
      const { blob, filename } = await registrarApi.exportIntakeLogCsv(hospitalId, from, to);
      saveBlob(blob, filename);
    } catch (e) {
      console.error('Failed to export intake CSV:', e);
    } finally {
      setExporting(false);
    }
  };
  const exportUnidCsv = async () => {
    if (!hospitalId) return;
    setExporting(true);
    try {
      const { blob, filename } = await registrarApi.exportUnidentifiedCsv(hospitalId);
      saveBlob(blob, filename);
    } catch (e) {
      console.error('Failed to export unidentified CSV:', e);
    } finally {
      setExporting(false);
    }
  };

  const fmtDateTime = (iso: string | null) =>
    iso ? format(new Date(iso), 'MMM dd, HH:mm') : '—';

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 rounded-xl bg-teal-500/20 flex items-center justify-center">
                <ClipboardList className="w-5 h-5 text-teal-400" />
              </div>
              <div>
                <h1 className="text-lg font-bold text-white tracking-wide">Registrar Reporting</h1>
                <p className="text-white/50 text-xs">
                  Front-desk intake log, identity-reconciliation queue &amp; live census
                </p>
              </div>
            </div>
          </div>

          {/* ── Tabs ── */}
          <div className="flex gap-1 px-4 py-2" style={{ borderTop: borderStyle }}>
            {TABS.map(({ value, label, icon: Icon }) => (
              <button
                key={value}
                onClick={() => setTab(value)}
                className={`flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold transition-all ${
                  tab === value
                    ? 'bg-teal-500/20 text-teal-400 border border-teal-500/30'
                    : `${text.body} hover:bg-white/5`
                }`}
              >
                <Icon className="w-3.5 h-3.5" />
                {label}
              </button>
            ))}
          </div>
        </div>

        {/* ════════ INTAKE LOG ════════ */}
        {tab === 'intake' && (
          <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
            {/* Controls */}
            <div className="px-5 py-3 flex flex-wrap items-center gap-3" style={{ borderBottom: borderStyle }}>
              <label className={`text-xs font-semibold ${text.label}`}>From</label>
              <input
                type="date" value={from} max={to}
                onChange={(e) => setFrom(e.target.value)}
                style={glassInner}
                className={`px-2 py-1 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              />
              <label className={`text-xs font-semibold ${text.label}`}>To</label>
              <input
                type="date" value={to} min={from} max={today}
                onChange={(e) => setTo(e.target.value)}
                style={glassInner}
                className={`px-2 py-1 rounded-lg text-xs focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${text.body}`}
              />
              <div className="flex-1" />
              <button
                onClick={loadIntake}
                style={glassInner}
                className="w-8 h-8 rounded-lg flex items-center justify-center hover:bg-white/5 transition-colors"
                title="Refresh"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${text.body}`} />
              </button>
              <button
                onClick={exportIntakeCsv}
                disabled={exporting || intake.length === 0}
                style={glassInner}
                className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-bold hover:bg-white/5 transition-colors disabled:opacity-50 ${text.heading}`}
              >
                {exporting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Download className="w-3.5 h-3.5" />}
                Export CSV
              </button>
            </div>

            {intakeLoading ? (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="w-7 h-7 animate-spin text-teal-400" />
              </div>
            ) : intakeErr ? (
              <div className="flex flex-col items-center justify-center py-12 px-6">
                <AlertTriangle className="w-8 h-8 text-red-400 mb-2" />
                <p className="text-sm font-semibold text-red-400">{intakeErr}</p>
              </div>
            ) : intake.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 px-6">
                <ClipboardList className={`w-10 h-10 mb-3 ${text.muted}`} />
                <p className={`text-sm font-semibold ${text.heading}`}>No registrations in this window</p>
              </div>
            ) : (
              <div className="overflow-x-auto">
                <table className="w-full text-left">
                  <thead>
                    <tr className="text-[10px] uppercase tracking-wider" style={{ borderBottom: borderStyle }}>
                      {['Visit #', 'Arrival', 'Mode', 'Patient', 'Age', 'Sex', 'Zone', 'Status'].map((h) => (
                        <th key={h} className={`px-4 py-2.5 font-bold ${text.body}`}>{h}</th>
                      ))}
                    </tr>
                  </thead>
                  <tbody>
                    {intake.map((r) => (
                      <tr key={r.visitNumber} className="hover:bg-white/[0.03] transition-colors" style={{ borderBottom: borderStyle }}>
                        <td className={`px-4 py-2.5 text-xs font-mono ${text.heading}`}>{r.visitNumber}</td>
                        <td className={`px-4 py-2.5 text-xs ${text.body}`}>{fmtDateTime(r.arrivalTime)}</td>
                        <td className={`px-4 py-2.5 text-xs ${text.body}`}>{r.arrivalMode || '—'}</td>
                        <td className={`px-4 py-2.5 text-xs font-semibold ${text.heading}`}>
                          {r.patientName}
                          {r.unidentified && (
                            <span className="ml-2 px-1.5 py-0.5 rounded text-[9px] font-bold bg-amber-500/20 text-amber-300 border border-amber-500/30">
                              UNIDENTIFIED
                            </span>
                          )}
                        </td>
                        <td className={`px-4 py-2.5 text-xs ${text.body}`}>{r.ageYears != null ? r.ageYears : '—'}</td>
                        <td className={`px-4 py-2.5 text-xs ${text.body}`}>{r.sex || '—'}</td>
                        <td className={`px-4 py-2.5 text-xs ${text.body}`}>{r.zone || '—'}</td>
                        <td className={`px-4 py-2.5 text-xs ${text.body}`}>{r.status || '—'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className={`px-4 py-3 text-[11px] ${text.body}`}>
                  {intake.length} registration{intake.length === 1 ? '' : 's'} · admissions log by arrival time
                  (the system does not attribute a registering user per visit).
                </p>
              </div>
            )}
          </div>
        )}

        {/* ════════ UNIDENTIFIED QUEUE ════════ */}
        {tab === 'unidentified' && (
          <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
            <div className="px-5 py-3 flex items-center gap-3" style={{ borderBottom: borderStyle }}>
              <div className="flex items-center gap-2">
                <UserX className="w-4 h-4 text-amber-400" />
                <h2 className={`text-sm font-bold ${text.heading}`}>Identity-Reconciliation Queue</h2>
              </div>
              <div className="flex-1" />
              <button
                onClick={loadUnidentified}
                style={glassInner}
                className="w-8 h-8 rounded-lg flex items-center justify-center hover:bg-white/5 transition-colors"
                title="Refresh"
              >
                <RefreshCw className={`w-3.5 h-3.5 ${text.body}`} />
              </button>
              <button
                onClick={exportUnidCsv}
                disabled={exporting || unidentified.length === 0}
                style={glassInner}
                className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-bold hover:bg-white/5 transition-colors disabled:opacity-50 ${text.heading}`}
              >
                {exporting ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Download className="w-3.5 h-3.5" />}
                Export CSV
              </button>
            </div>

            {unidLoading ? (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="w-7 h-7 animate-spin text-amber-400" />
              </div>
            ) : unidErr ? (
              <div className="flex flex-col items-center justify-center py-12 px-6">
                <AlertTriangle className="w-8 h-8 text-red-400 mb-2" />
                <p className="text-sm font-semibold text-red-400">{unidErr}</p>
              </div>
            ) : unidentified.length === 0 ? (
              <div className="flex flex-col items-center justify-center py-12 px-6">
                <UserX className="w-10 h-10 text-emerald-500 mb-3" />
                <p className={`text-sm font-semibold ${text.heading}`}>No unidentified patients</p>
                <p className={`text-xs mt-1 ${text.body}`}>Every active patient has a resolved identity.</p>
              </div>
            ) : (
              <div>
                <p className={`px-5 pt-3 text-[11px] ${text.body}`}>
                  Patients still registered under a placeholder. Open a row to resolve their real identity.
                  Oldest first — the longer they wait, the more overdue.
                </p>
                <div className="px-3 py-3 space-y-2">
                  {unidentified.map((r) => (
                    <button
                      key={r.patientId}
                      onClick={() => navigate(`/patients/${r.patientId}`)}
                      className="w-full flex items-center justify-between p-3.5 rounded-xl hover:-translate-y-0.5 transition-all group text-left"
                      style={{
                        ...glassInner,
                        border: isDark ? '1px solid rgba(245,158,11,0.25)' : '1px solid rgba(245,158,11,0.3)',
                      }}
                    >
                      <div className="flex items-center gap-3">
                        <div className="w-10 h-10 rounded-xl bg-amber-500/15 flex items-center justify-center flex-shrink-0">
                          <UserX className="w-[18px] h-[18px] text-amber-400" />
                        </div>
                        <div>
                          <div className={`text-[13px] font-bold ${text.heading}`}>{r.placeholderLabel || 'Unidentified patient'}</div>
                          <div className={`text-[11px] ${text.body}`}>Placeholder assigned {fmtDateTime(r.placeholderAssignedAt)}</div>
                        </div>
                      </div>
                      <div className="flex items-center gap-3">
                        <div className="text-right">
                          <div className={`text-sm font-bold ${overdueColor(r.hoursWaiting)}`}>
                            {r.hoursWaiting != null ? `${r.hoursWaiting}h` : '—'}
                          </div>
                          <div className={`text-[10px] ${text.body}`}>waiting</div>
                        </div>
                        <ChevronRight className={`w-4 h-4 group-hover:text-amber-400 transition-colors flex-shrink-0 ${text.muted}`} />
                      </div>
                    </button>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {/* ════════ CENSUS ════════ */}
        {tab === 'census' && (
          <div className="space-y-4">
            <div className="flex items-center justify-end">
              <button
                onClick={loadCensus}
                style={glassInner}
                className={`flex items-center gap-2 px-3 py-1.5 rounded-lg text-xs font-bold hover:bg-white/5 transition-colors ${text.heading}`}
              >
                <RefreshCw className="w-3.5 h-3.5" /> Refresh
              </button>
            </div>

            {censusLoading ? (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="w-7 h-7 animate-spin text-teal-400" />
              </div>
            ) : censusErr ? (
              <div className="rounded-3xl overflow-hidden" style={glassCard}>
                <div className="flex flex-col items-center justify-center py-12 px-6">
                  <AlertTriangle className="w-8 h-8 text-red-400 mb-2" />
                  <p className="text-sm font-semibold text-red-400">{censusErr}</p>
                </div>
              </div>
            ) : census ? (
              <>
                {/* Total */}
                <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
                  <div className="px-6 py-5 flex items-center gap-4">
                    <div className="w-12 h-12 rounded-2xl bg-teal-500/15 flex items-center justify-center">
                      <Users className="w-6 h-6 text-teal-400" />
                    </div>
                    <div>
                      <p className={`text-3xl font-extrabold ${text.heading}`}>{census.totalActive}</p>
                      <p className={`text-xs ${text.body}`}>
                        Active patients · as of {fmtDateTime(census.generatedAt)}
                      </p>
                    </div>
                  </div>
                </div>

                <div className="grid grid-cols-1 lg:grid-cols-2 gap-4">
                  {/* By status */}
                  <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
                    <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                      <div className="flex items-center gap-2">
                        <Activity className="w-4 h-4 text-cyan-400" />
                        <h2 className={`text-sm font-bold ${text.heading}`}>By Visit Status</h2>
                      </div>
                    </div>
                    <div className="px-5 py-4 space-y-2">
                      {Object.keys(census.byStatus).length === 0 ? (
                        <p className={`text-xs ${text.body}`}>No active visits.</p>
                      ) : (
                        Object.entries(census.byStatus)
                          .sort((a, b) => b[1] - a[1])
                          .map(([status, count]) => (
                            <div key={status} className="flex items-center justify-between">
                              <span className={`text-xs font-medium ${text.body}`}>{status.replace(/_/g, ' ')}</span>
                              <span className={`text-sm font-bold ${text.heading}`}>{count}</span>
                            </div>
                          ))
                      )}
                    </div>
                  </div>

                  {/* By zone */}
                  <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
                    <div className="px-5 py-3" style={{ borderBottom: borderStyle }}>
                      <div className="flex items-center gap-2">
                        <MapPin className="w-4 h-4 text-violet-400" />
                        <h2 className={`text-sm font-bold ${text.heading}`}>By ED Zone</h2>
                      </div>
                    </div>
                    <div className="px-5 py-4 space-y-2">
                      {Object.keys(census.byZone).length === 0 ? (
                        <p className={`text-xs ${text.body}`}>No active visits.</p>
                      ) : (
                        Object.entries(census.byZone)
                          .sort((a, b) => b[1] - a[1])
                          .map(([zone, count]) => (
                            <div key={zone} className="flex items-center justify-between">
                              <span className={`text-xs font-medium ${text.body}`}>{zone.replace(/_/g, ' ')}</span>
                              <span className={`text-sm font-bold ${text.heading}`}>{count}</span>
                            </div>
                          ))
                      )}
                    </div>
                  </div>
                </div>
              </>
            ) : null}
          </div>
        )}

      </div>
    </div>
  );
}
