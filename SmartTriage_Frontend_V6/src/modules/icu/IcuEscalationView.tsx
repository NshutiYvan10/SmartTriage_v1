/* ═══════════════════════════════════════════════════════════════
   ICU Escalation Management — Module 16
   Hospital-wide ICU escalation tracking, bed capacity & transfer pipeline
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  BedDouble, RefreshCw, Loader2, CheckCircle2, Clock,
  AlertTriangle, Bell, MessageSquare, Hash, ArrowRight,
  XCircle, Zap, User, Activity, ShieldCheck,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { icuApi } from '@/api/icu';
import type { IcuEscalation, IcuCapacity } from '@/api/icu';
import { format } from 'date-fns';

/* ── Status pipeline ── */
const STATUS_PIPELINE = ['REQUESTED', 'ICU_NOTIFIED', 'ICU_RESPONDED', 'BED_ASSIGNED', 'TRANSFERRED'] as const;

/* ── Status color config ── */
const STATUS_CONFIG: Record<string, { color: string; bg: string }> = {
  REQUESTED:      { color: 'text-amber-400', bg: 'bg-amber-500/10' },
  ICU_NOTIFIED:   { color: 'text-blue-400', bg: 'bg-blue-500/10' },
  ICU_RESPONDED:  { color: 'text-cyan-400', bg: 'bg-cyan-500/10' },
  BED_ASSIGNED:   { color: 'text-purple-400', bg: 'bg-purple-500/10' },
  TRANSFERRED:    { color: 'text-emerald-400', bg: 'bg-emerald-500/10' },
  CANCELLED:      { color: 'text-red-400', bg: 'bg-red-500/10' },
};

/* ── Triage category colors ── */
const TRIAGE_COLORS: Record<string, { color: string; bg: string }> = {
  RED:       { color: 'text-red-400', bg: 'bg-red-500/10' },
  ORANGE:    { color: 'text-orange-400', bg: 'bg-orange-500/10' },
  YELLOW:    { color: 'text-yellow-400', bg: 'bg-yellow-500/10' },
  GREEN:     { color: 'text-emerald-400', bg: 'bg-emerald-500/10' },
  BLUE:      { color: 'text-blue-400', bg: 'bg-blue-500/10' },
  EMERGENCY: { color: 'text-red-500', bg: 'bg-red-500/15' },
};

/* ── Occupancy color helper ── */
function occupancyColor(pct: number): { text: string; bg: string; bar: string } {
  if (pct > 90) return { text: 'text-red-400', bg: 'bg-red-500/10', bar: 'bg-red-500' };
  if (pct >= 70) return { text: 'text-amber-400', bg: 'bg-amber-500/10', bar: 'bg-amber-500' };
  return { text: 'text-emerald-400', bg: 'bg-emerald-500/10', bar: 'bg-emerald-500' };
}

/* ── Elapsed time formatter ── */
function formatElapsed(startIso: string): string {
  const ms = Date.now() - new Date(startIso).getTime();
  if (ms < 0) return '0m';
  const totalMin = Math.floor(ms / 60000);
  const h = Math.floor(totalMin / 60);
  const m = totalMin % 60;
  if (h > 0) return `${h}h ${m}m`;
  return `${m}m`;
}

export function IcuEscalationView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [escalations, setEscalations] = useState<IcuEscalation[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [capacity, setCapacity] = useState<IcuCapacity | null>(null);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [page, setPage] = useState(0);

  /* ── Response dialog state ── */
  const [responseDialogId, setResponseDialogId] = useState<string | null>(null);
  const [responseAccepted, setResponseAccepted] = useState(true);
  const [responseDeclineReason, setResponseDeclineReason] = useState('');
  const [responseBedNumber, setResponseBedNumber] = useState('');

  /* ── Assign bed dialog state ── */
  const [bedDialogId, setBedDialogId] = useState<string | null>(null);
  const [bedNumber, setBedNumber] = useState('');

  /* ── Cancel dialog state ── */
  const [cancelDialogId, setCancelDialogId] = useState<string | null>(null);
  const [cancelReason, setCancelReason] = useState('');

  /* ── Load escalations ── */
  const loadEscalations = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const data = await icuApi.getActive(hospitalId, page);
      setEscalations(data.content || []);
      setTotalElements(data.totalElements || 0);
    } catch (err) {
      console.error('Failed to load ICU escalations:', err);
      setEscalations([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, page]);

  /* ── Load capacity ── */
  const loadCapacity = useCallback(async () => {
    if (!hospitalId) return;
    try {
      const data = await icuApi.getCapacity(hospitalId);
      setCapacity(data);
    } catch (err) {
      console.error('Failed to load ICU capacity:', err);
    }
  }, [hospitalId]);

  useEffect(() => { loadEscalations(); }, [loadEscalations]);
  useEffect(() => { loadCapacity(); }, [loadCapacity]);

  const refreshAll = useCallback(() => {
    loadEscalations();
    loadCapacity();
  }, [loadEscalations, loadCapacity]);

  /* ── Action handlers ── */
  const handleNotifyTeam = async (id: string) => {
    setActionLoading(id);
    try {
      await icuApi.notifyTeam(id);
      await refreshAll();
    } catch (err) {
      console.error('Failed to notify ICU team:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleRecordResponse = async () => {
    if (!responseDialogId) return;
    setActionLoading(responseDialogId);
    try {
      await icuApi.recordResponse(responseDialogId, {
        accepted: responseAccepted,
        declineReason: responseAccepted ? undefined : responseDeclineReason,
        bedNumber: responseAccepted ? responseBedNumber || undefined : undefined,
      });
      setResponseDialogId(null);
      setResponseAccepted(true);
      setResponseDeclineReason('');
      setResponseBedNumber('');
      await refreshAll();
    } catch (err) {
      console.error('Failed to record ICU response:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleAssignBed = async () => {
    if (!bedDialogId || !bedNumber.trim()) return;
    setActionLoading(bedDialogId);
    try {
      await icuApi.assignBed(bedDialogId, bedNumber.trim());
      setBedDialogId(null);
      setBedNumber('');
      await refreshAll();
    } catch (err) {
      console.error('Failed to assign bed:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleTransfer = async (id: string) => {
    setActionLoading(id);
    try {
      await icuApi.transfer(id);
      await refreshAll();
    } catch (err) {
      console.error('Failed to transfer patient:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleCancel = async () => {
    if (!cancelDialogId || !cancelReason.trim()) return;
    setActionLoading(cancelDialogId);
    try {
      await icuApi.cancel(cancelDialogId, cancelReason.trim());
      setCancelDialogId(null);
      setCancelReason('');
      await refreshAll();
    } catch (err) {
      console.error('Failed to cancel escalation:', err);
    } finally {
      setActionLoading(null);
    }
  };

  /* ── Status pipeline index ── */
  const statusIndex = (status: string) => STATUS_PIPELINE.indexOf(status as typeof STATUS_PIPELINE[number]);

  /* ── Pagination ── */
  const totalPages = Math.ceil(totalElements / 20);

  const occ = capacity ? occupancyColor(capacity.occupancyPercent) : null;

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-purple-500/20 flex items-center justify-center">
                  <BedDouble className="w-5 h-5 text-purple-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">ICU Escalation Management</h1>
                  <p className="text-white/50 text-xs">Active escalations, bed capacity &amp; transfer pipeline</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="px-3 py-1.5 rounded-lg bg-white/10">
                  <span className="text-white/70 text-xs font-bold">{totalElements} Active</span>
                </div>
                <button
                  onClick={refreshAll}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                >
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* ── ICU Capacity Card ── */}
        {capacity && occ && (
          <div className="rounded-2xl overflow-hidden animate-fade-up" style={glassCard}>
            <div className="px-5 py-4">
              <div className="flex items-center gap-2 mb-4">
                <Activity className="w-4 h-4 text-purple-400" />
                <span className={`text-xs font-bold uppercase tracking-wider ${text.heading}`}>ICU Bed Capacity</span>
              </div>

              <div className="grid grid-cols-3 gap-4 mb-4">
                <div className="rounded-xl p-3" style={glassInner}>
                  <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Total Beds</p>
                  <p className={`text-2xl font-black mt-1 ${text.heading}`}>{capacity.totalBeds}</p>
                </div>
                <div className="rounded-xl p-3" style={glassInner}>
                  <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Occupied</p>
                  <p className="text-2xl font-black mt-1 text-amber-400">{capacity.occupiedBeds}</p>
                </div>
                <div className="rounded-xl p-3" style={glassInner}>
                  <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Available</p>
                  <p className={`text-2xl font-black mt-1 ${capacity.availableBeds === 0 ? 'text-red-400' : 'text-emerald-400'}`}>
                    {capacity.availableBeds}
                  </p>
                </div>
              </div>

              {/* ── Occupancy Bar ── */}
              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Occupancy</span>
                  <span className={`text-xs font-black ${occ.text}`}>{capacity.occupancyPercent.toFixed(1)}%</span>
                </div>
                <div className={`h-3 rounded-full overflow-hidden ${isDark ? 'bg-white/5' : 'bg-slate-100'}`}>
                  <div
                    className={`h-full rounded-full transition-all duration-500 ${occ.bar}`}
                    style={{ width: `${Math.min(capacity.occupancyPercent, 100)}%` }}
                  />
                </div>
                <div className="flex justify-between mt-1">
                  <span className={`text-[9px] ${text.muted}`}>0%</span>
                  <div className="flex gap-3">
                    <span className="text-[9px] text-emerald-400">&lt;70% Normal</span>
                    <span className="text-[9px] text-amber-400">70-90% Warning</span>
                    <span className="text-[9px] text-red-400">&gt;90% Critical</span>
                  </div>
                  <span className={`text-[9px] ${text.muted}`}>100%</span>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ── Content ── */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
          </div>
        ) : escalations.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <CheckCircle2 className="w-10 h-10 mx-auto mb-3 text-emerald-500" />
            <p className={`text-sm font-bold ${text.heading}`}>No active ICU escalations</p>
            <p className={`text-xs mt-1 ${text.muted}`}>
              There are currently no pending ICU escalation requests for this hospital
            </p>
          </div>
        ) : (
          <div className="space-y-4">
            {escalations.map((esc, i) => {
              const statusCfg = STATUS_CONFIG[esc.status] || STATUS_CONFIG.REQUESTED;
              const triageCfg = TRIAGE_COLORS[esc.triageCategory?.toUpperCase()] || TRIAGE_COLORS.YELLOW;
              const currentStep = statusIndex(esc.status);

              return (
                <div
                  key={esc.id}
                  className="rounded-2xl overflow-hidden animate-fade-up"
                  style={{ ...glassCard, animationDelay: `${i * 0.04}s` }}
                >
                  {/* ── Card Header ── */}
                  <div className="px-5 py-4">
                    <div className="flex items-start justify-between gap-4">
                      <div className="flex items-start gap-4 flex-1 min-w-0">
                        {/* Patient icon */}
                        <div className={`shrink-0 w-12 h-12 rounded-xl ${triageCfg.bg} flex flex-col items-center justify-center`}>
                          <User className={`w-5 h-5 ${triageCfg.color}`} />
                          <span className={`text-[7px] font-bold uppercase mt-0.5 ${triageCfg.color}`}>{esc.triageCategory}</span>
                        </div>

                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 flex-wrap mb-1.5">
                            <span className={`text-sm font-bold ${text.heading}`}>{esc.patientName}</span>
                            <span className={`text-[10px] font-bold px-2 py-0.5 rounded-lg ${isDark ? 'bg-white/5 text-slate-300' : 'bg-slate-100 text-slate-600'}`}>
                              #{esc.visitNumber}
                            </span>
                            {/* Status badge */}
                            <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg ${statusCfg.bg} ${statusCfg.color}`}>
                              {esc.status.replace(/_/g, ' ')}
                            </span>
                            {/* Automatic flag */}
                            {esc.isAutomatic && (
                              <span className="text-[10px] font-bold px-2 py-0.5 rounded-lg bg-cyan-500/10 text-cyan-400 flex items-center gap-1">
                                <Zap className="w-3 h-3" /> Auto
                              </span>
                            )}
                          </div>

                          {/* Trigger & Reason */}
                          <div className="flex items-center gap-3 flex-wrap mb-2">
                            <span className={`text-[10px] font-bold px-2 py-0.5 rounded-lg ${isDark ? 'bg-white/5 text-slate-400' : 'bg-slate-100 text-slate-500'}`}>
                              {esc.triggerType.replace(/_/g, ' ')}
                            </span>
                            <p className={`text-xs ${text.body}`}>{esc.escalationReason}</p>
                          </div>

                          {/* Meta info */}
                          <div className="flex items-center gap-4 flex-wrap">
                            <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                              <User className="w-3 h-3" />
                              {esc.escalatedByName}
                            </span>
                            <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                              <Clock className="w-3 h-3" />
                              {format(new Date(esc.escalatedAt), 'dd MMM yyyy HH:mm')}
                            </span>
                            <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                              <AlertTriangle className="w-3 h-3" />
                              Elapsed: {formatElapsed(esc.escalatedAt)}
                            </span>
                            {esc.icuResponseMinutes !== null && (
                              <span className={`text-[10px] flex items-center gap-1 font-bold ${
                                esc.icuResponseMinutes <= 15 ? 'text-emerald-400' : esc.icuResponseMinutes <= 30 ? 'text-amber-400' : 'text-red-400'
                              }`}>
                                <Clock className="w-3 h-3" />
                                Response: {esc.icuResponseMinutes}min
                              </span>
                            )}
                            {esc.icuConsultant && (
                              <span className={`text-[10px] flex items-center gap-1 ${text.accent}`}>
                                <ShieldCheck className="w-3 h-3" />
                                {esc.icuConsultant}
                              </span>
                            )}
                            {esc.icuBedNumber && (
                              <span className="text-[10px] flex items-center gap-1 text-purple-400 font-bold">
                                <BedDouble className="w-3 h-3" />
                                Bed {esc.icuBedNumber}
                              </span>
                            )}
                          </div>
                        </div>
                      </div>
                    </div>

                    {/* ── Status Pipeline ── */}
                    <div className="mt-4 flex items-center gap-1">
                      {STATUS_PIPELINE.map((step, idx) => {
                        const isActive = idx <= currentStep;
                        const isCurrent = idx === currentStep;
                        return (
                          <div key={step} className="flex items-center gap-1 flex-1">
                            <div className="flex-1">
                              <div
                                className={`h-1.5 rounded-full transition-all duration-300 ${
                                  isActive ? 'bg-gradient-to-r from-purple-500 to-cyan-500' : isDark ? 'bg-white/5' : 'bg-slate-100'
                                }`}
                              />
                              <span className={`text-[8px] font-bold uppercase tracking-wider mt-1 block text-center ${
                                isCurrent ? 'text-purple-400' : isActive ? text.muted : isDark ? 'text-slate-600' : 'text-slate-300'
                              }`}>
                                {step.replace(/_/g, ' ')}
                              </span>
                            </div>
                            {idx < STATUS_PIPELINE.length - 1 && (
                              <ArrowRight className={`w-3 h-3 shrink-0 ${isActive ? 'text-purple-400' : isDark ? 'text-slate-700' : 'text-slate-200'}`} />
                            )}
                          </div>
                        );
                      })}
                    </div>

                    {/* ── Action Buttons ── */}
                    <div className="mt-4 flex items-center gap-2 flex-wrap">
                      {esc.status === 'REQUESTED' && (
                        <button
                          onClick={() => handleNotifyTeam(esc.id)}
                          disabled={actionLoading === esc.id}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-blue-500/10 text-blue-400 hover:bg-blue-500/20 transition-colors disabled:opacity-50"
                        >
                          {actionLoading === esc.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <Bell className="w-3 h-3" />}
                          Notify ICU Team
                        </button>
                      )}

                      {esc.status === 'ICU_NOTIFIED' && (
                        <button
                          onClick={() => {
                            setResponseDialogId(esc.id);
                            setResponseAccepted(true);
                            setResponseDeclineReason('');
                            setResponseBedNumber('');
                          }}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-cyan-500/10 text-cyan-400 hover:bg-cyan-500/20 transition-colors"
                        >
                          <MessageSquare className="w-3 h-3" />
                          Record Response
                        </button>
                      )}

                      {esc.status === 'ICU_RESPONDED' && (
                        <button
                          onClick={() => {
                            setBedDialogId(esc.id);
                            setBedNumber('');
                          }}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-purple-500/10 text-purple-400 hover:bg-purple-500/20 transition-colors"
                        >
                          <Hash className="w-3 h-3" />
                          Assign Bed
                        </button>
                      )}

                      {esc.status === 'BED_ASSIGNED' && (
                        <button
                          onClick={() => handleTransfer(esc.id)}
                          disabled={actionLoading === esc.id}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-emerald-500/10 text-emerald-400 hover:bg-emerald-500/20 transition-colors disabled:opacity-50"
                        >
                          {actionLoading === esc.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <ArrowRight className="w-3 h-3" />}
                          Transfer Patient
                        </button>
                      )}

                      {esc.status !== 'TRANSFERRED' && esc.status !== 'CANCELLED' && (
                        <button
                          onClick={() => {
                            setCancelDialogId(esc.id);
                            setCancelReason('');
                          }}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-red-500/10 text-red-400 hover:bg-red-500/20 transition-colors"
                        >
                          <XCircle className="w-3 h-3" />
                          Cancel
                        </button>
                      )}
                    </div>
                  </div>
                </div>
              );
            })}
          </div>
        )}

        {/* ── Pagination ── */}
        {totalPages > 1 && (
          <div className="flex items-center justify-center gap-2 pt-2">
            <button
              onClick={() => setPage((p) => Math.max(0, p - 1))}
              disabled={page === 0}
              className={`px-3 py-1.5 rounded-lg text-[11px] font-bold transition-colors disabled:opacity-30 ${
                isDark ? 'bg-white/5 text-slate-300 hover:bg-white/10' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              Previous
            </button>
            <span className={`text-[11px] font-bold ${text.muted}`}>
              Page {page + 1} of {totalPages}
            </span>
            <button
              onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
              disabled={page >= totalPages - 1}
              className={`px-3 py-1.5 rounded-lg text-[11px] font-bold transition-colors disabled:opacity-30 ${
                isDark ? 'bg-white/5 text-slate-300 hover:bg-white/10' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
              }`}
            >
              Next
            </button>
          </div>
        )}

        {/* ═══════════════════════════════════════════════════════════════
           Dialogs
           ═══════════════════════════════════════════════════════════════ */}

        {/* ── Record Response Dialog ── */}
        {responseDialogId && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4">
            <div className="w-full max-w-md rounded-2xl overflow-hidden" style={glassCard}>
              <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-bold text-white">Record ICU Response</h2>
                  <button onClick={() => setResponseDialogId(null)} className="text-white/50 hover:text-white">
                    <XCircle className="w-4 h-4" />
                  </button>
                </div>
              </div>
              <div className="p-5 space-y-4">
                {/* Accept / Decline toggle */}
                <div className="flex gap-2">
                  <button
                    onClick={() => setResponseAccepted(true)}
                    className={`flex-1 py-2 rounded-lg text-xs font-bold transition-colors ${
                      responseAccepted ? 'bg-emerald-500/20 text-emerald-400 border border-emerald-500/30' : isDark ? 'bg-white/5 text-slate-400' : 'bg-slate-100 text-slate-500'
                    }`}
                  >
                    Accept
                  </button>
                  <button
                    onClick={() => setResponseAccepted(false)}
                    className={`flex-1 py-2 rounded-lg text-xs font-bold transition-colors ${
                      !responseAccepted ? 'bg-red-500/20 text-red-400 border border-red-500/30' : isDark ? 'bg-white/5 text-slate-400' : 'bg-slate-100 text-slate-500'
                    }`}
                  >
                    Decline
                  </button>
                </div>

                {responseAccepted ? (
                  <div>
                    <label className={`text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Bed Number (optional)</label>
                    <input
                      type="text"
                      value={responseBedNumber}
                      onChange={(e) => setResponseBedNumber(e.target.value)}
                      placeholder="e.g. ICU-12"
                      className={`w-full mt-1 px-3 py-2 rounded-lg text-xs ${isDark ? 'bg-white/5 text-white border-white/10' : 'bg-white text-slate-800 border-slate-200'} border focus:outline-none focus:ring-2 focus:ring-cyan-500/30`}
                    />
                  </div>
                ) : (
                  <div>
                    <label className={`text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Decline Reason</label>
                    <textarea
                      value={responseDeclineReason}
                      onChange={(e) => setResponseDeclineReason(e.target.value)}
                      placeholder="Reason for declining..."
                      rows={3}
                      className={`w-full mt-1 px-3 py-2 rounded-lg text-xs resize-none ${isDark ? 'bg-white/5 text-white border-white/10' : 'bg-white text-slate-800 border-slate-200'} border focus:outline-none focus:ring-2 focus:ring-cyan-500/30`}
                    />
                  </div>
                )}

                <div className="flex justify-end gap-2">
                  <button
                    onClick={() => setResponseDialogId(null)}
                    className={`px-4 py-2 rounded-lg text-xs font-bold ${isDark ? 'bg-white/5 text-slate-300 hover:bg-white/10' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'} transition-colors`}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleRecordResponse}
                    disabled={!responseAccepted && !responseDeclineReason.trim()}
                    className="px-4 py-2 rounded-lg text-xs font-bold bg-cyan-500/20 text-cyan-400 hover:bg-cyan-500/30 transition-colors disabled:opacity-50"
                  >
                    {actionLoading === responseDialogId ? <Loader2 className="w-3 h-3 animate-spin" /> : 'Submit'}
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ── Assign Bed Dialog ── */}
        {bedDialogId && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4">
            <div className="w-full max-w-sm rounded-2xl overflow-hidden" style={glassCard}>
              <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-bold text-white">Assign ICU Bed</h2>
                  <button onClick={() => setBedDialogId(null)} className="text-white/50 hover:text-white">
                    <XCircle className="w-4 h-4" />
                  </button>
                </div>
              </div>
              <div className="p-5 space-y-4">
                <div>
                  <label className={`text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Bed Number</label>
                  <input
                    type="text"
                    value={bedNumber}
                    onChange={(e) => setBedNumber(e.target.value)}
                    placeholder="e.g. ICU-12"
                    className={`w-full mt-1 px-3 py-2 rounded-lg text-xs ${isDark ? 'bg-white/5 text-white border-white/10' : 'bg-white text-slate-800 border-slate-200'} border focus:outline-none focus:ring-2 focus:ring-cyan-500/30`}
                  />
                </div>
                <div className="flex justify-end gap-2">
                  <button
                    onClick={() => setBedDialogId(null)}
                    className={`px-4 py-2 rounded-lg text-xs font-bold ${isDark ? 'bg-white/5 text-slate-300 hover:bg-white/10' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'} transition-colors`}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleAssignBed}
                    disabled={!bedNumber.trim()}
                    className="px-4 py-2 rounded-lg text-xs font-bold bg-purple-500/20 text-purple-400 hover:bg-purple-500/30 transition-colors disabled:opacity-50"
                  >
                    {actionLoading === bedDialogId ? <Loader2 className="w-3 h-3 animate-spin" /> : 'Assign'}
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

        {/* ── Cancel Dialog ── */}
        {cancelDialogId && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4">
            <div className="w-full max-w-sm rounded-2xl overflow-hidden" style={glassCard}>
              <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-bold text-white">Cancel Escalation</h2>
                  <button onClick={() => setCancelDialogId(null)} className="text-white/50 hover:text-white">
                    <XCircle className="w-4 h-4" />
                  </button>
                </div>
              </div>
              <div className="p-5 space-y-4">
                <div>
                  <label className={`text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Cancellation Reason</label>
                  <textarea
                    value={cancelReason}
                    onChange={(e) => setCancelReason(e.target.value)}
                    placeholder="Reason for cancellation..."
                    rows={3}
                    className={`w-full mt-1 px-3 py-2 rounded-lg text-xs resize-none ${isDark ? 'bg-white/5 text-white border-white/10' : 'bg-white text-slate-800 border-slate-200'} border focus:outline-none focus:ring-2 focus:ring-cyan-500/30`}
                  />
                </div>
                <div className="flex justify-end gap-2">
                  <button
                    onClick={() => setCancelDialogId(null)}
                    className={`px-4 py-2 rounded-lg text-xs font-bold ${isDark ? 'bg-white/5 text-slate-300 hover:bg-white/10' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'} transition-colors`}
                  >
                    Back
                  </button>
                  <button
                    onClick={handleCancel}
                    disabled={!cancelReason.trim()}
                    className="px-4 py-2 rounded-lg text-xs font-bold bg-red-500/20 text-red-400 hover:bg-red-500/30 transition-colors disabled:opacity-50"
                  >
                    {actionLoading === cancelDialogId ? <Loader2 className="w-3 h-3 animate-spin" /> : 'Confirm Cancel'}
                  </button>
                </div>
              </div>
            </div>
          </div>
        )}

      </div>
    </div>
  );
}
