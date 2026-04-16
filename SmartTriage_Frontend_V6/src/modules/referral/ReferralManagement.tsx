/* ═══════════════════════════════════════════════════════════════
   Referral & Transfer Management — Module 17
   Rwanda national referral system: District -> Provincial -> National
   with ABCDE stabilization checklist & RHMIS/SAMU integration
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  ArrowRightLeft, RefreshCw, Loader2, CheckCircle2, Clock,
  ArrowRight, XCircle, Building2, Truck, FileText,
  ShieldCheck, CheckSquare, Square, ChevronDown, ChevronUp,
  Send, MapPin, Phone, User,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { referralApi } from '@/api/referral';
import type { Referral } from '@/api/referral';
import { format } from 'date-fns';

/* ── Filter modes ── */
type FilterMode = 'active' | 'completed' | 'cancelled';

/* ── Status pipeline ── */
const STATUS_PIPELINE = [
  'INITIATED',
  'RECEIVING_CONTACTED',
  'ACCEPTED',
  'STABILIZING',
  'IN_TRANSIT',
  'ARRIVED',
  'COMPLETED',
] as const;

/* ── Status color config ── */
const STATUS_CONFIG: Record<string, { color: string; bg: string }> = {
  INITIATED:            { color: 'text-amber-400', bg: 'bg-amber-500/10' },
  RECEIVING_CONTACTED:  { color: 'text-blue-400', bg: 'bg-blue-500/10' },
  ACCEPTED:             { color: 'text-cyan-400', bg: 'bg-cyan-500/10' },
  STABILIZING:          { color: 'text-orange-400', bg: 'bg-orange-500/10' },
  IN_TRANSIT:           { color: 'text-purple-400', bg: 'bg-purple-500/10' },
  ARRIVED:              { color: 'text-indigo-400', bg: 'bg-indigo-500/10' },
  COMPLETED:            { color: 'text-emerald-400', bg: 'bg-emerald-500/10' },
  CANCELLED:            { color: 'text-red-400', bg: 'bg-red-500/10' },
};

/* ── Referral type colors ── */
const REFERRAL_TYPE_CONFIG: Record<string, { color: string; bg: string; label: string }> = {
  UPWARD:     { color: 'text-red-400', bg: 'bg-red-500/10', label: 'Upward' },
  LATERAL:    { color: 'text-blue-400', bg: 'bg-blue-500/10', label: 'Lateral' },
  DOWNWARD:   { color: 'text-emerald-400', bg: 'bg-emerald-500/10', label: 'Downward' },
  SPECIALIST: { color: 'text-purple-400', bg: 'bg-purple-500/10', label: 'Specialist' },
};

/* ── Transport mode config ── */
const TRANSPORT_CONFIG: Record<string, { icon: typeof Truck; label: string }> = {
  AMBULANCE:       { icon: Truck, label: 'Truck' },
  SAMU:            { icon: Truck, label: 'SAMU' },
  PRIVATE_VEHICLE: { icon: Truck, label: 'Private Vehicle' },
  HELICOPTER:      { icon: Send, label: 'Helicopter' },
  OTHER:           { icon: Truck, label: 'Other' },
};

/* ── ABCDE Stabilization checklist ── */
const STABILIZATION_ITEMS: { key: string; label: string; description: string }[] = [
  { key: 'airwaySecured', label: 'Airway', description: 'Airway patent and secured' },
  { key: 'breathingStable', label: 'Breathing', description: 'Adequate ventilation, O2 if needed' },
  { key: 'circulationStable', label: 'Circulation', description: 'IV access, fluids, bleeding controlled' },
  { key: 'disabilityAssessed', label: 'Disability', description: 'GCS assessed, pupils checked' },
  { key: 'exposureManaged', label: 'Exposure', description: 'Temperature managed, injuries exposed' },
];

/* ── Triage category colors ── */
const TRIAGE_COLORS: Record<string, { color: string; bg: string }> = {
  RED:       { color: 'text-red-400', bg: 'bg-red-500/10' },
  ORANGE:    { color: 'text-orange-400', bg: 'bg-orange-500/10' },
  YELLOW:    { color: 'text-yellow-400', bg: 'bg-yellow-500/10' },
  GREEN:     { color: 'text-emerald-400', bg: 'bg-emerald-500/10' },
  BLUE:      { color: 'text-blue-400', bg: 'bg-blue-500/10' },
};

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

export function ReferralManagement() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  const [referrals, setReferrals] = useState<Referral[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<FilterMode>('active');
  const [actionLoading, setActionLoading] = useState<string | null>(null);
  const [page, setPage] = useState(0);
  const [expandedId, setExpandedId] = useState<string | null>(null);

  /* ── Contact receiving dialog ── */
  const [contactDialogId, setContactDialogId] = useState<string | null>(null);
  const [contactClinician, setContactClinician] = useState('');
  const [contactPhone, setContactPhone] = useState('');

  /* ── Cancel dialog ── */
  const [cancelDialogId, setCancelDialogId] = useState<string | null>(null);
  const [cancelReason, setCancelReason] = useState('');

  /* ── Stabilization state per referral ── */
  const [stabilizationState, setStabilizationState] = useState<Record<string, Record<string, boolean>>>({});

  /* ── Load referrals ── */
  const loadReferrals = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const data = await referralApi.getActive(hospitalId, page);
      setReferrals(data.content || []);
      setTotalElements(data.totalElements || 0);
    } catch (err) {
      console.error('Failed to load referrals:', err);
      setReferrals([]);
    } finally {
      setLoading(false);
    }
  }, [hospitalId, page]);

  useEffect(() => { loadReferrals(); }, [loadReferrals]);

  /* ── Filter referrals ── */
  const filtered = referrals.filter((r) => {
    switch (filter) {
      case 'completed': return r.status === 'COMPLETED';
      case 'cancelled': return r.status === 'CANCELLED';
      default: return r.status !== 'COMPLETED' && r.status !== 'CANCELLED';
    }
  });

  const activeCount = referrals.filter((r) => r.status !== 'COMPLETED' && r.status !== 'CANCELLED').length;
  const completedCount = referrals.filter((r) => r.status === 'COMPLETED').length;
  const cancelledCount = referrals.filter((r) => r.status === 'CANCELLED').length;

  /* ── Action handlers ── */
  const handleContactReceiving = async () => {
    if (!contactDialogId || !contactClinician.trim()) return;
    setActionLoading(contactDialogId);
    try {
      await referralApi.contactReceiving(contactDialogId, {
        receivingClinician: contactClinician.trim(),
        receivingClinicianPhone: contactPhone.trim() || undefined,
      });
      setContactDialogId(null);
      setContactClinician('');
      setContactPhone('');
      await loadReferrals();
    } catch (err) {
      console.error('Failed to contact receiving hospital:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleAccept = async (id: string) => {
    setActionLoading(id);
    try {
      await referralApi.acceptReferral(id);
      await loadReferrals();
    } catch (err) {
      console.error('Failed to accept referral:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleToggleStabilization = async (referralId: string, key: string) => {
    const current = stabilizationState[referralId] || {};
    const newValue = !current[key];
    const updated = { ...current, [key]: newValue };
    setStabilizationState((prev) => ({ ...prev, [referralId]: updated }));

    setActionLoading(`${referralId}-${key}`);
    try {
      await referralApi.updateStabilization(referralId, { [key]: newValue });
      await loadReferrals();
    } catch (err) {
      console.error('Failed to update stabilization:', err);
      // Revert on error
      setStabilizationState((prev) => ({ ...prev, [referralId]: current }));
    } finally {
      setActionLoading(null);
    }
  };

  const handleMarkDeparted = async (id: string) => {
    setActionLoading(id);
    try {
      await referralApi.markDeparted(id);
      await loadReferrals();
    } catch (err) {
      console.error('Failed to mark departed:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleMarkArrived = async (id: string) => {
    setActionLoading(id);
    try {
      await referralApi.markArrived(id);
      await loadReferrals();
    } catch (err) {
      console.error('Failed to mark arrived:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleComplete = async (id: string) => {
    setActionLoading(id);
    try {
      await referralApi.complete(id);
      await loadReferrals();
    } catch (err) {
      console.error('Failed to complete referral:', err);
    } finally {
      setActionLoading(null);
    }
  };

  const handleCancel = async () => {
    if (!cancelDialogId || !cancelReason.trim()) return;
    setActionLoading(cancelDialogId);
    try {
      await referralApi.cancel(cancelDialogId, cancelReason.trim());
      setCancelDialogId(null);
      setCancelReason('');
      await loadReferrals();
    } catch (err) {
      console.error('Failed to cancel referral:', err);
    } finally {
      setActionLoading(null);
    }
  };

  /* ── Status pipeline index ── */
  const statusIndex = (status: string) => STATUS_PIPELINE.indexOf(status as typeof STATUS_PIPELINE[number]);

  /* ── Pagination ── */
  const totalPages = Math.ceil(totalElements / 20);

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-blue-500/20 flex items-center justify-center">
                  <ArrowRightLeft className="w-5 h-5 text-blue-400" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Referral &amp; Transfer Management</h1>
                  <p className="text-white/50 text-xs">National referral pathway: District &rarr; Provincial &rarr; National</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="px-3 py-1.5 rounded-lg bg-white/10">
                  <span className="text-white/70 text-xs font-bold">{totalElements} Total</span>
                </div>
                <button
                  onClick={loadReferrals}
                  className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                >
                  <RefreshCw className="w-4 h-4 text-white" />
                </button>
              </div>
            </div>
          </div>

          {/* ── Filter Tabs ── */}
          <div
            className="flex gap-1 px-4 py-2"
            style={{ borderTop: isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)' }}
          >
            {([
              ['active', 'Active', activeCount],
              ['completed', 'Completed', completedCount],
              ['cancelled', 'Cancelled', cancelledCount],
            ] as [FilterMode, string, number][]).map(([key, label, count]) => (
              <button
                key={key}
                onClick={() => setFilter(key)}
                className={`px-4 py-2 text-[11px] font-bold rounded-lg transition-all ${
                  filter === key
                    ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                    : isDark
                      ? 'text-slate-400 hover:text-white hover:bg-white/5'
                      : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                }`}
              >
                {label}
                {count > 0 && (
                  <span className={`ml-1.5 px-1.5 py-0.5 text-[9px] rounded-full ${
                    key === 'cancelled' ? 'bg-red-500/20 text-red-400' : key === 'completed' ? 'bg-emerald-500/20 text-emerald-400' : 'bg-cyan-500/20 text-cyan-400'
                  }`}>
                    {count}
                  </span>
                )}
              </button>
            ))}
          </div>
        </div>

        {/* ── Content ── */}
        {loading ? (
          <div className="flex items-center justify-center py-12">
            <Loader2 className="w-6 h-6 animate-spin text-cyan-500" />
          </div>
        ) : filtered.length === 0 ? (
          <div className="rounded-2xl p-8 text-center animate-fade-up" style={glassCard}>
            <CheckCircle2 className="w-10 h-10 mx-auto mb-3 text-emerald-500" />
            <p className={`text-sm font-bold ${text.heading}`}>
              {filter === 'active' ? 'No active referrals' : filter === 'completed' ? 'No completed referrals' : 'No cancelled referrals'}
            </p>
            <p className={`text-xs mt-1 ${text.muted}`}>
              {filter === 'active'
                ? 'There are currently no pending referral or transfer requests'
                : `No ${filter} referrals found for this hospital`}
            </p>
          </div>
        ) : (
          <div className="space-y-4">
            {filtered.map((ref, i) => {
              const statusCfg = STATUS_CONFIG[ref.status] || STATUS_CONFIG.INITIATED;
              const typeCfg = REFERRAL_TYPE_CONFIG[ref.referralType] || REFERRAL_TYPE_CONFIG.UPWARD;
              const triageCfg = TRIAGE_COLORS[ref.currentTriageCategory?.toUpperCase() || ''] || null;
              const transportCfg = TRANSPORT_CONFIG[ref.transportMode || ''] || null;
              const currentStep = statusIndex(ref.status);
              const isExpanded = expandedId === ref.id;
              const stabState = stabilizationState[ref.id] || {};

              return (
                <div
                  key={ref.id}
                  className="rounded-2xl overflow-hidden animate-fade-up"
                  style={{ ...glassCard, animationDelay: `${i * 0.04}s` }}
                >
                  {/* ── Card Header ── */}
                  <div className="px-5 py-4">
                    <div className="flex items-start justify-between gap-4">
                      <div className="flex items-start gap-4 flex-1 min-w-0">
                        {/* Hospital icon */}
                        <div className={`shrink-0 w-12 h-12 rounded-xl ${typeCfg.bg} flex flex-col items-center justify-center`}>
                          <Building2 className={`w-5 h-5 ${typeCfg.color}`} />
                          <span className={`text-[7px] font-bold uppercase mt-0.5 ${typeCfg.color}`}>{typeCfg.label}</span>
                        </div>

                        <div className="flex-1 min-w-0">
                          <div className="flex items-center gap-2 flex-wrap mb-1.5">
                            {/* Receiving hospital */}
                            <span className={`text-sm font-bold ${text.heading}`}>{ref.receivingHospitalName}</span>
                            {/* Referral type badge */}
                            <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg ${typeCfg.bg} ${typeCfg.color}`}>
                              {ref.referralType}
                            </span>
                            {/* Status badge */}
                            <span className={`text-[10px] font-bold uppercase tracking-wider px-2.5 py-1 rounded-lg ${statusCfg.bg} ${statusCfg.color}`}>
                              {ref.status.replace(/_/g, ' ')}
                            </span>
                            {/* Triage badge */}
                            {triageCfg && ref.currentTriageCategory && (
                              <span className={`text-[10px] font-bold px-2 py-0.5 rounded-lg ${triageCfg.bg} ${triageCfg.color}`}>
                                {ref.currentTriageCategory}
                              </span>
                            )}
                          </div>

                          {/* Referral reason */}
                          <p className={`text-xs ${text.body} mb-2`}>{ref.referralReason}</p>

                          {/* Meta info */}
                          <div className="flex items-center gap-4 flex-wrap">
                            <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                              <User className="w-3 h-3" />
                              {ref.referringClinician}
                            </span>
                            <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                              <Clock className="w-3 h-3" />
                              {format(new Date(ref.initiatedAt), 'dd MMM yyyy HH:mm')}
                            </span>
                            {ref.status !== 'COMPLETED' && ref.status !== 'CANCELLED' && (
                              <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                                <Clock className="w-3 h-3" />
                                Elapsed: {formatElapsed(ref.initiatedAt)}
                              </span>
                            )}
                            {transportCfg && (
                              <span className={`text-[10px] flex items-center gap-1 font-bold ${text.accent}`}>
                                <transportCfg.icon className="w-3 h-3" />
                                {transportCfg.label}
                              </span>
                            )}
                            {ref.estimatedTransferTimeMinutes !== null && (
                              <span className={`text-[10px] flex items-center gap-1 ${text.muted}`}>
                                <MapPin className="w-3 h-3" />
                                ~{ref.estimatedTransferTimeMinutes}min transfer
                              </span>
                            )}
                          </div>

                          {/* RHMIS / SAMU numbers */}
                          {(ref.rhmisCaseNumber || ref.samuRequestNumber) && (
                            <div className="flex items-center gap-3 flex-wrap mt-2">
                              {ref.rhmisCaseNumber && (
                                <span className={`text-[10px] font-bold px-2 py-0.5 rounded-lg ${isDark ? 'bg-white/5 text-slate-300' : 'bg-slate-100 text-slate-600'}`}>
                                  <FileText className="w-3 h-3 inline mr-1" />
                                  RHMIS: {ref.rhmisCaseNumber}
                                </span>
                              )}
                              {ref.samuRequestNumber && (
                                <span className={`text-[10px] font-bold px-2 py-0.5 rounded-lg bg-red-500/10 text-red-400`}>
                                  <Truck className="w-3 h-3 inline mr-1" />
                                  SAMU: {ref.samuRequestNumber}
                                </span>
                              )}
                            </div>
                          )}
                        </div>

                        {/* Expand toggle */}
                        <button
                          onClick={() => setExpandedId(isExpanded ? null : ref.id)}
                          className={`shrink-0 w-8 h-8 rounded-lg flex items-center justify-center transition-colors ${
                            isDark ? 'hover:bg-white/5' : 'hover:bg-slate-100'
                          }`}
                        >
                          {isExpanded ? (
                            <ChevronUp className={`w-4 h-4 ${text.muted}`} />
                          ) : (
                            <ChevronDown className={`w-4 h-4 ${text.muted}`} />
                          )}
                        </button>
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
                                  isActive ? 'bg-gradient-to-r from-blue-500 to-cyan-500' : isDark ? 'bg-white/5' : 'bg-slate-100'
                                }`}
                              />
                              <span className={`text-[7px] font-bold uppercase tracking-wider mt-1 block text-center ${
                                isCurrent ? 'text-blue-400' : isActive ? text.muted : isDark ? 'text-slate-600' : 'text-slate-300'
                              }`}>
                                {step.replace(/_/g, ' ')}
                              </span>
                            </div>
                            {idx < STATUS_PIPELINE.length - 1 && (
                              <ArrowRight className={`w-2.5 h-2.5 shrink-0 ${isActive ? 'text-blue-400' : isDark ? 'text-slate-700' : 'text-slate-200'}`} />
                            )}
                          </div>
                        );
                      })}
                    </div>

                    {/* ── Expanded Section: Clinical Summary & Stabilization ── */}
                    {isExpanded && (
                      <div className="mt-4 space-y-3">
                        {/* Clinical summary */}
                        {ref.clinicalSummary && (
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className={`text-[10px] font-bold uppercase tracking-wider mb-1 ${text.muted}`}>Clinical Summary</p>
                            <p className={`text-xs ${text.body}`}>{ref.clinicalSummary}</p>
                          </div>
                        )}

                        {/* Current diagnosis */}
                        {ref.currentDiagnosis && (
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className={`text-[10px] font-bold uppercase tracking-wider mb-1 ${text.muted}`}>Current Diagnosis</p>
                            <p className={`text-xs font-semibold ${text.heading}`}>{ref.currentDiagnosis}</p>
                          </div>
                        )}

                        {/* Notes */}
                        {ref.notes && (
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className={`text-[10px] font-bold uppercase tracking-wider mb-1 ${text.muted}`}>Notes</p>
                            <p className={`text-xs ${text.body}`}>{ref.notes}</p>
                          </div>
                        )}

                        {/* ABCDE Stabilization Checklist */}
                        {(ref.status === 'ACCEPTED' || ref.status === 'STABILIZING') && (
                          <div className="rounded-xl p-3" style={glassInner}>
                            <div className="flex items-center gap-2 mb-3">
                              <ShieldCheck className="w-4 h-4 text-orange-400" />
                              <span className={`text-[10px] font-bold uppercase tracking-wider ${text.heading}`}>
                                ABCDE Stabilization Checklist
                              </span>
                            </div>
                            <div className="space-y-2">
                              {STABILIZATION_ITEMS.map((item) => {
                                const isChecked = stabState[item.key] || false;
                                const isItemLoading = actionLoading === `${ref.id}-${item.key}`;
                                return (
                                  <button
                                    key={item.key}
                                    onClick={() => handleToggleStabilization(ref.id, item.key)}
                                    disabled={isItemLoading}
                                    className={`w-full flex items-center gap-3 px-3 py-2 rounded-lg text-left transition-colors ${
                                      isChecked
                                        ? 'bg-emerald-500/10 border border-emerald-500/20'
                                        : isDark ? 'bg-white/3 border border-white/5 hover:bg-white/5' : 'bg-white/40 border border-slate-200/50 hover:bg-white/60'
                                    }`}
                                  >
                                    {isItemLoading ? (
                                      <Loader2 className="w-4 h-4 animate-spin text-cyan-500 shrink-0" />
                                    ) : isChecked ? (
                                      <CheckSquare className="w-4 h-4 text-emerald-400 shrink-0" />
                                    ) : (
                                      <Square className={`w-4 h-4 shrink-0 ${text.muted}`} />
                                    )}
                                    <div className="flex-1 min-w-0">
                                      <span className={`text-xs font-bold ${isChecked ? 'text-emerald-400' : text.heading}`}>
                                        {item.label}
                                      </span>
                                      <p className={`text-[10px] ${text.muted}`}>{item.description}</p>
                                    </div>
                                  </button>
                                );
                              })}
                            </div>
                            <div className="mt-2 flex items-center gap-2">
                              <div className={`flex-1 h-1.5 rounded-full overflow-hidden ${isDark ? 'bg-white/5' : 'bg-slate-100'}`}>
                                <div
                                  className="h-full rounded-full bg-emerald-500 transition-all duration-300"
                                  style={{ width: `${(Object.values(stabState).filter(Boolean).length / STABILIZATION_ITEMS.length) * 100}%` }}
                                />
                              </div>
                              <span className={`text-[10px] font-bold ${text.muted}`}>
                                {Object.values(stabState).filter(Boolean).length}/{STABILIZATION_ITEMS.length}
                              </span>
                            </div>
                          </div>
                        )}

                        {/* Completed info */}
                        {ref.completedAt && (
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className={`text-[10px] font-bold uppercase tracking-wider mb-1 ${text.muted}`}>Completed</p>
                            <p className={`text-xs font-semibold ${text.heading}`}>
                              {format(new Date(ref.completedAt), 'dd MMM yyyy HH:mm')}
                            </p>
                          </div>
                        )}

                        {/* Hospital code */}
                        {ref.receivingHospitalCode && (
                          <div className="rounded-xl p-3" style={glassInner}>
                            <p className={`text-[10px] font-bold uppercase tracking-wider mb-1 ${text.muted}`}>Receiving Hospital Code</p>
                            <p className={`text-xs font-mono font-semibold ${text.heading}`}>{ref.receivingHospitalCode}</p>
                          </div>
                        )}
                      </div>
                    )}

                    {/* ── Action Buttons ── */}
                    <div className="mt-4 flex items-center gap-2 flex-wrap">
                      {ref.status === 'INITIATED' && (
                        <button
                          onClick={() => {
                            setContactDialogId(ref.id);
                            setContactClinician('');
                            setContactPhone('');
                          }}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-blue-500/10 text-blue-400 hover:bg-blue-500/20 transition-colors"
                        >
                          <Phone className="w-3 h-3" />
                          Contact Receiving
                        </button>
                      )}

                      {ref.status === 'RECEIVING_CONTACTED' && (
                        <button
                          onClick={() => handleAccept(ref.id)}
                          disabled={actionLoading === ref.id}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-cyan-500/10 text-cyan-400 hover:bg-cyan-500/20 transition-colors disabled:opacity-50"
                        >
                          {actionLoading === ref.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <CheckCircle2 className="w-3 h-3" />}
                          Accept Referral
                        </button>
                      )}

                      {(ref.status === 'ACCEPTED' || ref.status === 'STABILIZING') && (
                        <button
                          onClick={() => handleMarkDeparted(ref.id)}
                          disabled={actionLoading === ref.id}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-purple-500/10 text-purple-400 hover:bg-purple-500/20 transition-colors disabled:opacity-50"
                        >
                          {actionLoading === ref.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <Send className="w-3 h-3" />}
                          Mark Departed
                        </button>
                      )}

                      {ref.status === 'IN_TRANSIT' && (
                        <button
                          onClick={() => handleMarkArrived(ref.id)}
                          disabled={actionLoading === ref.id}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-indigo-500/10 text-indigo-400 hover:bg-indigo-500/20 transition-colors disabled:opacity-50"
                        >
                          {actionLoading === ref.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <MapPin className="w-3 h-3" />}
                          Mark Arrived
                        </button>
                      )}

                      {ref.status === 'ARRIVED' && (
                        <button
                          onClick={() => handleComplete(ref.id)}
                          disabled={actionLoading === ref.id}
                          className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-[11px] font-bold bg-emerald-500/10 text-emerald-400 hover:bg-emerald-500/20 transition-colors disabled:opacity-50"
                        >
                          {actionLoading === ref.id ? <Loader2 className="w-3 h-3 animate-spin" /> : <CheckCircle2 className="w-3 h-3" />}
                          Complete Transfer
                        </button>
                      )}

                      {ref.status !== 'COMPLETED' && ref.status !== 'CANCELLED' && (
                        <button
                          onClick={() => {
                            setCancelDialogId(ref.id);
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

        {/* ── Contact Receiving Dialog ── */}
        {contactDialogId && (
          <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/50 backdrop-blur-sm p-4">
            <div className="w-full max-w-md rounded-2xl overflow-hidden" style={glassCard}>
              <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-5 py-4">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-bold text-white">Contact Receiving Hospital</h2>
                  <button onClick={() => setContactDialogId(null)} className="text-white/50 hover:text-white">
                    <XCircle className="w-4 h-4" />
                  </button>
                </div>
              </div>
              <div className="p-5 space-y-4">
                <div>
                  <label className={`text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Receiving Clinician Name</label>
                  <input
                    type="text"
                    value={contactClinician}
                    onChange={(e) => setContactClinician(e.target.value)}
                    placeholder="Dr. Uwimana"
                    className={`w-full mt-1 px-3 py-2 rounded-lg text-xs ${isDark ? 'bg-white/5 text-white border-white/10' : 'bg-white text-slate-800 border-slate-200'} border focus:outline-none focus:ring-2 focus:ring-cyan-500/30`}
                  />
                </div>
                <div>
                  <label className={`text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Phone Number (optional)</label>
                  <input
                    type="tel"
                    value={contactPhone}
                    onChange={(e) => setContactPhone(e.target.value)}
                    placeholder="+250 78 XXX XXXX"
                    className={`w-full mt-1 px-3 py-2 rounded-lg text-xs ${isDark ? 'bg-white/5 text-white border-white/10' : 'bg-white text-slate-800 border-slate-200'} border focus:outline-none focus:ring-2 focus:ring-cyan-500/30`}
                  />
                </div>
                <div className="flex justify-end gap-2">
                  <button
                    onClick={() => setContactDialogId(null)}
                    className={`px-4 py-2 rounded-lg text-xs font-bold ${isDark ? 'bg-white/5 text-slate-300 hover:bg-white/10' : 'bg-slate-100 text-slate-600 hover:bg-slate-200'} transition-colors`}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleContactReceiving}
                    disabled={!contactClinician.trim()}
                    className="px-4 py-2 rounded-lg text-xs font-bold bg-blue-500/20 text-blue-400 hover:bg-blue-500/30 transition-colors disabled:opacity-50"
                  >
                    {actionLoading === contactDialogId ? <Loader2 className="w-3 h-3 animate-spin" /> : 'Submit'}
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
                  <h2 className="text-sm font-bold text-white">Cancel Referral</h2>
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
