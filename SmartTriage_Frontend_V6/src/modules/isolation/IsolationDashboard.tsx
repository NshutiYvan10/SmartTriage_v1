/* ═══════════════════════════════════════════════════════════════
   Infection Control & Isolation Dashboard — Module 11
   Active isolations, PPE requirements, public health notifications
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  ShieldAlert, RefreshCw, Loader2, CheckCircle2, Clock,
  AlertTriangle, Building2, Phone, DoorOpen,
  Shield, Eye, Hand, Shirt, Footprints, ChevronRight,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useScopedView } from '@/hooks/useScopedView';
import { useAuthStore } from '@/store/authStore';
import { isolationApi } from '@/api/isolation';
import { PatientContextLine } from '@/components/PatientContextLine';
import { chartPath } from '@/lib/chartNav';
import { subscribeToIsolation } from '@/api/websocket';
import { useWebSocketGeneration } from '@/hooks/useWebSocket';
import { ApiError } from '@/api/client';
import { CrossZoneRestrictedPanel } from '@/components/CrossZoneRestrictedPanel';
import type { InfectionScreening } from '@/api/isolation';
import { format } from 'date-fns';

/* ── Risk level colours — keys MUST match the backend InfectionRiskLevel enum.
   An unknown value falls back to RED (never downgrade an unrecognised risk to a
   reassuring colour). ── */
const RISK_FALLBACK = { color: 'text-red-600', bg: 'bg-red-500/15', border: 'border-red-500/30', darkBg: 'bg-red-500/20', label: 'RISK' };
const RISK_CONFIG: Record<string, { color: string; bg: string; border: string; darkBg: string; label: string }> = {
  CONFIRMED:     { color: 'text-red-700',    bg: 'bg-red-600/15',    border: 'border-red-600/30',    darkBg: 'bg-red-600/20',  label: 'CONFIRMED' },
  HIGH_RISK:     { color: 'text-red-500',    bg: 'bg-red-500/10',    border: 'border-red-500/20',    darkBg: 'bg-red-500/15',  label: 'HIGH RISK' },
  MODERATE_RISK: { color: 'text-amber-500',  bg: 'bg-amber-500/10',  border: 'border-amber-500/20',  darkBg: 'bg-amber-500/15', label: 'MODERATE' },
  LOW_RISK:      { color: 'text-yellow-500', bg: 'bg-yellow-500/10', border: 'border-yellow-500/20', darkBg: 'bg-yellow-500/15', label: 'LOW' },
  CLEARED:       { color: 'text-emerald-500', bg: 'bg-emerald-500/10', border: 'border-emerald-500/20', darkBg: 'bg-emerald-500/15', label: 'CLEARED' },
};

/* ── Isolation type colours — unknown → red (never downgrade). ── */
const ISO_TYPE_FALLBACK = { color: 'text-red-600', bg: 'bg-red-500/15', border: 'border-red-500/30' };
const ISOLATION_TYPE_CONFIG: Record<string, { color: string; bg: string; border: string }> = {
  STRICT:     { color: 'text-red-700',    bg: 'bg-red-600/15',    border: 'border-red-600/30' },
  AIRBORNE:   { color: 'text-red-400',    bg: 'bg-red-500/10',    border: 'border-red-500/20' },
  DROPLET:    { color: 'text-orange-400', bg: 'bg-orange-500/10', border: 'border-orange-500/20' },
  CONTACT:    { color: 'text-yellow-500', bg: 'bg-yellow-500/10', border: 'border-yellow-500/20' },
  PROTECTIVE: { color: 'text-sky-400',    bg: 'bg-sky-500/10',    border: 'border-sky-500/20' },
};

export function IsolationDashboard() {
  const { glassCard, glassInner, text } = useTheme();
  const navigate = useNavigate();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';
  const scope = useScopedView();

  const wsGen = useWebSocketGeneration();
  const [isolations, setIsolations] = useState<InfectionScreening[]>([]);
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  /* Room assignment state */
  const [assignRoomId, setAssignRoomId] = useState<string | null>(null);
  const [roomInput, setRoomInput] = useState('');
  /* End-isolation (clearance reason is mandatory) */
  const [endId, setEndId] = useState<string | null>(null);
  const [endReason, setEndReason] = useState('');

  /* ── Data loading ──────────────────────────────────────── */
  const loadIsolations = useCallback(async () => {
    if (!hospitalId || scope.mode === 'RESTRICTED') return;
    setLoading(true);
    try {
      const data = await isolationApi.getActiveIsolations(
        hospitalId,
        scope.mode === 'ZONE_SCOPED' ? scope.zone ?? undefined : undefined,
      );
      setIsolations(Array.isArray(data) ? data : []);
      setError(null);
    } catch (err) {
      // Surface the failure — never render a green "no active isolations" when the load failed.
      console.error('Failed to load active isolations:', err);
      setIsolations([]);
      setError(err instanceof ApiError ? err.message : 'Failed to load active isolations');
    } finally {
      setLoading(false);
    }
  }, [hospitalId, scope.mode, scope.zone]);

  useEffect(() => { loadIsolations(); }, [loadIsolations]);

  /* ── Live refresh — dedicated isolation topic; re-subscribes on reconnect. ── */
  useEffect(() => {
    if (!hospitalId) return;
    const unsub = subscribeToIsolation(hospitalId, () => { loadIsolations(); });
    return () => unsub();
  }, [hospitalId, loadIsolations, wsGen]);

  /* ── Actions ───────────────────────────────────────────── */
  const handleAssignRoom = async (id: string) => {
    if (!roomInput.trim()) return;
    setActionLoading(id);
    setError(null);
    try {
      await isolationApi.assignRoom(id, roomInput.trim());
      setAssignRoomId(null);
      setRoomInput('');
      loadIsolations();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to assign isolation room');
      console.error('Failed to assign room:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleNotifyPublicHealth = async (id: string) => {
    setActionLoading(id);
    setError(null);
    try {
      await isolationApi.notifyPublicHealth(id);
      loadIsolations();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to record public-health notification');
      console.error('Failed to notify public health:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleEndIsolation = async (id: string) => {
    if (!endReason.trim()) return;
    setActionLoading(id);
    setError(null);
    try {
      await isolationApi.endIsolation(id, endReason.trim());
      setEndId(null);
      setEndReason('');
      loadIsolations();
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to end isolation');
      console.error('Failed to end isolation:', err);
    } finally {
      setActionLoading(null);
    }
  };

  /* ── Derived counts (backend InfectionRiskLevel values) ── */
  const activeCount = isolations.length;
  const highCount = isolations.filter((i) => i.riskLevel === 'HIGH_RISK' || i.riskLevel === 'CONFIRMED').length;
  const moderateCount = isolations.filter((i) => i.riskLevel === 'MODERATE_RISK').length;
  const lowCount = isolations.filter((i) => i.riskLevel === 'LOW_RISK').length;

  /* ── PPE badge helper ──────────────────────────────────── */
  const PpeBadge = ({ show, label, Icon }: { show: boolean; label: string; Icon: typeof Shield }) => {
    if (!show) return null;
    return (
      <div className={`inline-flex items-center gap-1 px-2 py-1 rounded-lg text-[10px] font-bold ${text.muted}`} style={glassInner}>
        <Icon className="w-3 h-3" />
        {label}
      </div>
    );
  };

  // Don't render the restriction panel until the shift fetch resolves —
  // otherwise the "you're off shift" card flashes for every user on first paint.
  if (scope.isLoading) {
    return (
      <div className="min-h-full flex items-center justify-center p-10">
        <div className="w-8 h-8 rounded-full border-2 border-slate-400/40 border-t-slate-500 animate-spin" />
      </div>
    );
  }

  if (scope.mode === 'RESTRICTED') {
    return (
      <CrossZoneRestrictedPanel
        pageTitle="Infection / Isolation"
        zone={null}
        reason="OFF_SHIFT"
      />
    );
  }

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ─────────────────────────────────────── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-red-500/20 flex items-center justify-center">
                  <ShieldAlert className="w-5 h-5 text-red-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Infection Control & Isolation</h1>
                  <p className="text-white/50 text-xs">Active isolations and public health notifications</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {activeCount > 0 && (
                  <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg bg-red-500/20 border border-red-500/30">
                    <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
                    <span className="text-red-400 text-xs font-bold">{activeCount} Active</span>
                  </div>
                )}
                <button onClick={loadIsolations} className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors">
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* ── Risk Level Summary Cards ───────────────────── */}
        <div className="grid grid-cols-3 gap-3 animate-fade-up" style={{ animationDelay: '0.05s' }}>
          {([
            { label: 'HIGH', count: highCount, config: RISK_CONFIG.HIGH_RISK },
            { label: 'MODERATE', count: moderateCount, config: RISK_CONFIG.MODERATE_RISK },
            { label: 'LOW', count: lowCount, config: RISK_CONFIG.LOW_RISK },
          ] as const).map(({ label, count, config }) => (
            <div
              key={label}
              className="rounded-2xl p-4 text-center"
              style={glassCard}
            >
              <div className={`w-10 h-10 rounded-xl ${config.darkBg} flex items-center justify-center mx-auto mb-2`}>
                <span className={`text-lg font-bold ${config.color}`}>{count}</span>
              </div>
              <p className={`text-[10px] font-bold uppercase tracking-wider ${config.color}`}>{label} Risk</p>
            </div>
          ))}
        </div>

        {/* ── Isolation List ─────────────────────────────── */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
          </div>
        ) : error ? (
          <div className="rounded-2xl px-4 py-3 flex items-start gap-2 bg-red-500/10 border border-red-500/20 animate-fade-up">
            <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
            <p className="text-[12px] font-semibold text-red-500">{error}</p>
          </div>
        ) : isolations.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <CheckCircle2 className="w-10 h-10 mx-auto mb-3 text-emerald-500" />
            <p className={`text-sm font-bold ${text.heading}`}>No active isolations</p>
            <p className={`text-xs mt-1 ${text.muted}`}>All infection screenings have been resolved</p>
          </div>
        ) : (
          <div className="space-y-3">
            {isolations.map((iso, i) => {
              const risk = RISK_CONFIG[iso.riskLevel] || RISK_FALLBACK;
              const isoType = iso.isolationType ? (ISOLATION_TYPE_CONFIG[iso.isolationType] || ISO_TYPE_FALLBACK) : null;
              const isNotifiable = !!iso.notifiableDisease;

              return (
                <div
                  key={iso.id}
                  className="rounded-2xl overflow-hidden transition-all animate-fade-up"
                  style={{ ...glassCard, animationDelay: `${i * 0.03}s` }}
                >
                  <div className="p-4">
                    <div className="flex items-start gap-4">
                      {/* Risk indicator */}
                      <div className={`w-12 h-12 rounded-xl ${risk.darkBg} flex items-center justify-center shrink-0`}>
                        <ShieldAlert className={`w-6 h-6 ${risk.color}`} />
                      </div>

                      <div className="flex-1 min-w-0">
                        {/* Identity FIRST — who + where, before the clinical payload.
                            Clickable through to the patient chart (singular /visit route). */}
                        <button
                          type="button"
                          onClick={() => iso.visitId && navigate(chartPath(iso.visitId))}
                          disabled={!iso.visitId}
                          title={iso.visitId ? 'Open patient chart' : undefined}
                          className="group/chart flex items-center gap-1.5 mb-1.5 text-left disabled:cursor-default"
                        >
                          <PatientContextLine
                            patientName={iso.patientName}
                            zone={iso.currentZone}
                            bedLabel={iso.currentBedLabel}
                            visitNumber={iso.visitNumber}
                            className={`text-[12px] ${text.heading}`}
                          />
                          {iso.visitId && (
                            <ChevronRight className={`w-3.5 h-3.5 ${text.muted} opacity-0 group-hover/chart:opacity-100 transition-opacity`} />
                          )}
                        </button>

                        {/* Top row — condition & badges */}
                        <div className="flex flex-wrap items-center gap-2 mb-1.5">
                          <span className={`text-sm font-bold ${text.heading}`}>
                            {iso.suspectedCondition || 'Unknown condition'}
                          </span>
                          <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg border ${risk.bg} ${risk.color} ${risk.border}`}>
                            {risk.label}
                          </span>
                          {isoType && (
                            <span className={`text-[10px] font-bold uppercase tracking-wider px-2 py-0.5 rounded-lg border ${isoType.bg} ${isoType.color} ${isoType.border}`}>
                              {iso.isolationType}
                            </span>
                          )}
                          {isNotifiable && (
                            <span
                              className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-red-600 animate-pulse"
                              style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}
                            >
                              NOTIFIABLE
                            </span>
                          )}
                        </div>

                        {/* Notifiable disease highlight */}
                        {iso.notifiableDisease && (
                          <p className="text-xs text-red-400 font-medium mb-1.5">
                            <AlertTriangle className="w-3 h-3 inline mr-1" />
                            Notifiable disease: {iso.notifiableDisease.replace(/_/g, ' ')}
                          </p>
                        )}

                        {/* Meta row */}
                        <div className="flex flex-wrap items-center gap-3 mb-2">
                          <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                            <Clock className="w-3 h-3" />
                            Screened {iso.screenedAt ? format(new Date(iso.screenedAt), 'dd MMM yyyy HH:mm') : '—'}
                          </span>
                          {iso.screenedByName && (
                            <span className={`text-[10px] ${text.muted}`}>by {iso.screenedByName}</span>
                          )}
                        </div>

                        {/* PPE Requirements */}
                        <div className="flex flex-wrap gap-1.5 mb-2">
                          <PpeBadge show={iso.requiresN95} label="N95" Icon={Shield} />
                          <PpeBadge show={iso.requiresGown} label="Gown" Icon={Shirt} />
                          <PpeBadge show={iso.requiresGloves} label="Gloves" Icon={Hand} />
                          <PpeBadge show={iso.requiresFaceShield} label="Face Shield" Icon={Eye} />
                          <PpeBadge show={iso.requiresApron} label="Apron" Icon={Shirt} />
                          <PpeBadge show={iso.requiresBootCovers} label="Boot Covers" Icon={Footprints} />
                        </div>

                        {/* Isolation room */}
                        {iso.isolationRoomAssigned && (
                          <div className="flex items-center gap-1.5 mb-2">
                            <Building2 className="w-3.5 h-3.5 text-cyan-500" />
                            <span className={`text-xs ${text.body}`}>Room: <strong className={text.heading}>{iso.isolationRoomAssigned}</strong></span>
                            {iso.isolationStartedAt && (
                              <span className={`text-[10px] ${text.muted}`}>
                                since {format(new Date(iso.isolationStartedAt), 'dd MMM HH:mm')}
                              </span>
                            )}
                          </div>
                        )}

                        {/* Public health notification status */}
                        {iso.publicHealthNotifiedAt && (
                          <div className="flex items-center gap-1.5 mb-2">
                            <Phone className="w-3.5 h-3.5 text-emerald-500" />
                            <span className="text-xs text-emerald-500 font-medium">
                              Public health notified {format(new Date(iso.publicHealthNotifiedAt), 'dd MMM HH:mm')}
                            </span>
                          </div>
                        )}

                        {iso.notes && (
                          <p className={`text-xs mt-1 ${text.muted}`}>{iso.notes}</p>
                        )}

                        {/* Actions */}
                        <div className="flex flex-wrap items-center gap-2 mt-3">
                          {!iso.isolationRoomAssigned && (
                            <>
                              {assignRoomId === iso.id ? (
                                <div className="flex items-center gap-2">
                                  <input
                                    type="text"
                                    value={roomInput}
                                    onChange={(e) => setRoomInput(e.target.value)}
                                    placeholder="Room name / number"
                                    style={glassInner}
                                    className={`w-40 px-3 py-2 text-xs rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-colors ${text.body}`}
                                  />
                                  <button
                                    onClick={() => handleAssignRoom(iso.id)}
                                    disabled={!roomInput.trim() || actionLoading === iso.id}
                                    className="inline-flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                                  >
                                    {actionLoading === iso.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
                                    Assign
                                  </button>
                                  <button
                                    onClick={() => { setAssignRoomId(null); setRoomInput(''); }}
                                    className={`px-3 py-2 text-[11px] font-bold rounded-xl transition-colors hover:bg-white/5 ${text.body}`}
                                  >
                                    Cancel
                                  </button>
                                </div>
                              ) : (
                                <button
                                  onClick={() => { setAssignRoomId(iso.id); setRoomInput(''); }}
                                  className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-cyan-500/10 text-cyan-500 hover:bg-cyan-500/20 transition-colors"
                                >
                                  <Building2 className="w-3.5 h-3.5" /> Assign Room
                                </button>
                              )}
                            </>
                          )}

                          {!iso.publicHealthNotifiedAt && iso.notifiableDisease && (
                            <button
                              onClick={() => handleNotifyPublicHealth(iso.id)}
                              disabled={actionLoading === iso.id}
                              className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-amber-500/10 text-amber-500 hover:bg-amber-500/20 transition-colors"
                            >
                              {actionLoading === iso.id ? (
                                <Loader2 className="w-3.5 h-3.5 animate-spin" />
                              ) : (
                                <Phone className="w-3.5 h-3.5" />
                              )}
                              Notify Public Health
                            </button>
                          )}

                          {endId === iso.id ? (
                            <div className="flex items-center gap-2 flex-wrap">
                              <input
                                type="text"
                                value={endReason}
                                onChange={(e) => setEndReason(e.target.value)}
                                placeholder="Clearance reason (required)"
                                style={glassInner}
                                className={`w-64 px-3 py-2 text-xs rounded-xl focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-colors ${text.body}`}
                              />
                              <button
                                onClick={() => handleEndIsolation(iso.id)}
                                disabled={!endReason.trim() || actionLoading === iso.id}
                                className="inline-flex items-center gap-1.5 px-3 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                              >
                                {actionLoading === iso.id ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
                                Clear
                              </button>
                              <button onClick={() => { setEndId(null); setEndReason(''); }}
                                className={`px-3 py-2 text-[11px] font-bold rounded-xl transition-colors hover:bg-white/5 ${text.body}`}>Cancel</button>
                            </div>
                          ) : (
                            <button
                              onClick={() => { setEndId(iso.id); setEndReason(''); }}
                              disabled={actionLoading === iso.id}
                              className="inline-flex items-center gap-1.5 px-4 py-2 text-[11px] font-bold rounded-xl bg-emerald-500/10 text-emerald-500 hover:bg-emerald-500/20 transition-colors"
                            >
                              <DoorOpen className="w-3.5 h-3.5" />
                              Clear Isolation
                            </button>
                          )}
                        </div>
                      </div>
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
