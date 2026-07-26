/* ===================================================================
   Clinical Governance — Module 24
   Policy lifecycle management with audit trail
   =================================================================== */

import { useState, useEffect, useCallback } from 'react';
import {
  Scale, Plus, CheckCircle2, ShieldCheck, Archive, ArchiveRestore, PauseCircle,
  ChevronDown, ChevronRight, Loader2, RefreshCw, Clock, FileText,
  Search, History, User, AlertTriangle, ArrowRight, Filter, X,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { dialog } from '@/components/dialog';
import { useAuthStore } from '@/store/authStore';
import { governanceApi } from '@/api/governance';
import type { ClinicalPolicy, PolicyAuditLog } from '@/api/governance';
import { hospitalApi } from '@/api/hospitals';
import type { HospitalResponse } from '@/api/types';
import { format } from 'date-fns';

/* -- Constants ---------------------------------------------------- */

const POLICY_TYPES = [
  { value: 'TRIAGE_RULE', label: 'Triage Rule' },
  { value: 'DRUG_PROTOCOL', label: 'Drug Protocol' },
  { value: 'CLINICAL_GUIDELINE', label: 'Clinical Guideline' },
  { value: 'INFECTION_CONTROL', label: 'Infection Control' },
  { value: 'STAFFING_REQUIREMENT', label: 'Staffing Requirement' },
  { value: 'EQUIPMENT_PROTOCOL', label: 'Equipment Protocol' },
  { value: 'QUALITY_STANDARD', label: 'Quality Standard' },
  { value: 'CONSENT_FORM', label: 'Consent Form' },
  { value: 'DISCHARGE_CRITERIA', label: 'Discharge Criteria' },
  { value: 'OTHER', label: 'Other' },
] as const;

const POLICY_TYPE_CONFIG: Record<string, { color: string; bg: string; border: string }> = {
  TRIAGE_RULE:           { color: 'text-blue-600',    bg: 'rgba(59,130,246,0.08)',  border: '1px solid rgba(59,130,246,0.2)' },
  DRUG_PROTOCOL:         { color: 'text-emerald-600', bg: 'rgba(16,185,129,0.08)',  border: '1px solid rgba(16,185,129,0.2)' },
  CLINICAL_GUIDELINE:    { color: 'text-cyan-600',    bg: 'rgba(6,182,212,0.08)',   border: '1px solid rgba(6,182,212,0.2)' },
  INFECTION_CONTROL:     { color: 'text-red-600',     bg: 'rgba(239,68,68,0.08)',   border: '1px solid rgba(239,68,68,0.2)' },
  STAFFING_REQUIREMENT:  { color: 'text-pink-600',    bg: 'rgba(236,72,153,0.08)',  border: '1px solid rgba(236,72,153,0.2)' },
  EQUIPMENT_PROTOCOL:    { color: 'text-teal-600',    bg: 'rgba(20,184,166,0.08)',  border: '1px solid rgba(20,184,166,0.2)' },
  QUALITY_STANDARD:      { color: 'text-violet-600',  bg: 'rgba(139,92,246,0.08)',  border: '1px solid rgba(139,92,246,0.2)' },
  CONSENT_FORM:          { color: 'text-amber-600',   bg: 'rgba(245,158,11,0.08)',  border: '1px solid rgba(245,158,11,0.2)' },
  DISCHARGE_CRITERIA:    { color: 'text-orange-600',  bg: 'rgba(249,115,22,0.08)',  border: '1px solid rgba(249,115,22,0.2)' },
  OTHER:                 { color: 'text-slate-600',   bg: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' },
};

const STATUS_CONFIG: Record<string, { color: string; bg: string; border: string; label: string }> = {
  DRAFT:             { color: 'text-slate-600',   bg: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)', label: 'Draft' },
  PENDING_APPROVAL:  { color: 'text-amber-600',   bg: 'rgba(245,158,11,0.08)',  border: '1px solid rgba(245,158,11,0.2)',  label: 'Pending Approval' },
  APPROVED:          { color: 'text-blue-600',    bg: 'rgba(59,130,246,0.08)',  border: '1px solid rgba(59,130,246,0.2)',  label: 'Approved' },
  ACTIVE:            { color: 'text-emerald-600', bg: 'rgba(16,185,129,0.08)',  border: '1px solid rgba(16,185,129,0.2)',  label: 'Active' },
  SUSPENDED:         { color: 'text-red-600',     bg: 'rgba(239,68,68,0.08)',   border: '1px solid rgba(239,68,68,0.2)',   label: 'Suspended' },
  ARCHIVED:          { color: 'text-slate-600',   bg: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)', label: 'Archived' },
};

// Admin one-step lifecycle: a DRAFT policy is approved + activated in a
// single action (the audit trail still records both steps). The
// intermediate PENDING_APPROVAL / APPROVED statuses remain valid states
// (styled via STATUS_CONFIG) but are no longer the advertised path.
const STATUS_PIPELINE = ['DRAFT', 'ACTIVE'];
const ALL_STATUSES = ['', 'DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE', 'SUSPENDED', 'ARCHIVED'];

function getTypeLabel(type: string): string {
  return POLICY_TYPES.find((t) => t.value === type)?.label || type;
}

/* -- Tabs --------------------------------------------------------- */
type TabId = 'policies' | 'audit';

/* ================================================================= */

export function GovernanceAdmin() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const isSuperAdmin = user?.role === 'SUPER_ADMIN';
  // SUPER_ADMIN is a national role — its own hospitalId is the phantom system
  // hospital, so it picks a target hospital (mirrors ReportsView). Everyone else
  // is pinned to their own hospital.
  const [hospitals, setHospitals] = useState<HospitalResponse[]>([]);
  const [selectedHospitalId, setSelectedHospitalId] = useState('');
  useEffect(() => {
    if (!isSuperAdmin) return;
    hospitalApi.getAll(0, 50).then((page) => {
      const rows = page.content || [];
      setHospitals(rows);
      const firstReal = rows.find((h) => h.id !== user?.hospitalId) || rows[0];
      if (firstReal) setSelectedHospitalId((cur) => cur || firstReal.id);
    }).catch((e) => console.error('[Governance] hospitals load failed:', e));
  }, [isSuperAdmin, user?.hospitalId]);
  const hospitalId = (isSuperAdmin ? selectedHospitalId : user?.hospitalId) || '';

  /* -- Shared state ----------------------------------------------- */
  const [activeTab, setActiveTab] = useState<TabId>('policies');
  const [loading, setLoading] = useState(true);
  const [actionLoading, setActionLoading] = useState<string | null>(null);

  /* -- Policies state --------------------------------------------- */
  const [policies, setPolicies] = useState<ClinicalPolicy[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);
  const [expandedId, setExpandedId] = useState<string | null>(null);
  const [statusFilter, setStatusFilter] = useState('');
  const [showCreateForm, setShowCreateForm] = useState(false);

  /* Create form */
  const [formType, setFormType] = useState('TRIAGE_RULE');
  const [formError, setFormError] = useState<string | null>(null);
  const [formName, setFormName] = useState('');
  const [formCode, setFormCode] = useState('');
  const [formDesc, setFormDesc] = useState('');
  const [formContent, setFormContent] = useState('');
  const [formEffective, setFormEffective] = useState('');

  /* Suspend modal */
  const [suspendTarget, setSuspendTarget] = useState<string | null>(null);
  const [suspendReason, setSuspendReason] = useState('');

  /* Approve modal */
  const [approveTarget, setApproveTarget] = useState<string | null>(null);
  const [approveNotes, setApproveNotes] = useState('');

  /* Version history */
  const [historyTarget, setHistoryTarget] = useState<string | null>(null);
  const [historyItems, setHistoryItems] = useState<ClinicalPolicy[]>([]);
  const [historyLoading, setHistoryLoading] = useState(false);

  /* -- Audit state ------------------------------------------------ */
  const [auditPolicyId, setAuditPolicyId] = useState('');
  const [auditLogs, setAuditLogs] = useState<PolicyAuditLog[]>([]);
  const [auditTotal, setAuditTotal] = useState(0);
  const [auditPage, setAuditPage] = useState(0);
  const [auditLoading, setAuditLoading] = useState(false);

  /* -- Data loading ----------------------------------------------- */
  const loadPolicies = useCallback(async () => {
    if (!hospitalId) return;
    setLoading(true);
    try {
      const res = await governanceApi.getAll(hospitalId, page, statusFilter || undefined);
      setPolicies(res.content);
      setTotalElements(res.totalElements);
    } catch {
      /* keep existing data */
    } finally {
      setLoading(false);
    }
  }, [hospitalId, page, statusFilter]);

  useEffect(() => {
    loadPolicies();
  }, [loadPolicies]);

  const loadAuditLog = useCallback(async (policyId: string, pg = 0) => {
    if (!policyId) return;
    setAuditLoading(true);
    try {
      const res = await governanceApi.getAuditLog(policyId, pg);
      setAuditLogs(res.content);
      setAuditTotal(res.totalElements);
    } catch {
      /* handled */
    } finally {
      setAuditLoading(false);
    }
  }, []);

  const loadHistory = useCallback(async (policyId: string) => {
    setHistoryLoading(true);
    try {
      const items = await governanceApi.getHistory(policyId);
      setHistoryItems(items);
    } catch {
      /* handled */
    } finally {
      setHistoryLoading(false);
    }
  }, []);

  /* -- Policy actions --------------------------------------------- */
  const handleCreate = useCallback(async () => {
    if (!hospitalId || !formName || !formContent || !formEffective) return;
    setActionLoading('create');
    setFormError(null);
    try {
      await governanceApi.create({
        hospitalId,
        policyType: formType,
        policyName: formName,
        policyCode: formCode || null,
        description: formDesc || null,
        policyContent: formContent,
        // <input type="date"> yields 'yyyy-MM-dd'; the API expects an ISO instant.
        effectiveFrom: new Date(`${formEffective}T00:00:00`).toISOString(),
        createdByName: user?.fullName || undefined,
      });
      setShowCreateForm(false);
      setFormName('');
      setFormCode('');
      setFormDesc('');
      setFormContent('');
      setFormEffective('');
      await loadPolicies();
    } catch (e) {
      setFormError(e instanceof Error ? e.message : 'Failed to create policy.');
    } finally { setActionLoading(null); }
  }, [hospitalId, formType, formName, formCode, formDesc, formContent, formEffective, user?.fullName, loadPolicies]);

  // One-step approve + activate (admin lifecycle). The backend records
  // APPROVED and ACTIVATED audit rows separately.
  const handleApprove = useCallback(async () => {
    if (!approveTarget) return;
    setActionLoading(approveTarget);
    try {
      await governanceApi.approveActivate(approveTarget, approveNotes || undefined);
      setApproveTarget(null);
      setApproveNotes('');
      await loadPolicies();
    } catch (err: any) {
      dialog.notify(err?.message ?? 'Failed to approve and activate policy.', { type: 'error' });
    } finally { setActionLoading(null); }
  }, [approveTarget, approveNotes, loadPolicies]);

  const handleActivate = useCallback(async (id: string) => {
    setActionLoading(id);
    try { await governanceApi.activate(id); await loadPolicies(); }
    catch { /* */ } finally { setActionLoading(null); }
  }, [loadPolicies]);

  const handleSuspend = useCallback(async () => {
    if (!suspendTarget || !suspendReason) return;
    setActionLoading(suspendTarget);
    try {
      await governanceApi.suspend(suspendTarget, suspendReason);
      setSuspendTarget(null);
      setSuspendReason('');
      await loadPolicies();
    } catch { /* */ } finally { setActionLoading(null); }
  }, [suspendTarget, suspendReason, loadPolicies]);

  // Archiving retires a clinical policy — confirm in-app first (the
  // neighbouring Suspend action already has a reason dialog; Archive
  // previously fired on a single click).
  const [archiveTarget, setArchiveTarget] = useState<string | null>(null);

  const handleArchive = useCallback(async (id: string) => {
    setActionLoading(id);
    try { await governanceApi.archive(id); await loadPolicies(); }
    catch { /* */ } finally { setActionLoading(null); setArchiveTarget(null); }
  }, [loadPolicies]);

  // Restore an archived policy → DRAFT (must re-pass approval before it is
  // active again — the backend enforces the same rule).
  const handleUnarchive = useCallback(async (id: string) => {
    const ok = await dialog.confirm({
      title: 'Restore policy',
      message: 'Restore this archived policy to DRAFT? It will need to go through submit, approval, and activation again before it is back in clinical use.',
      confirmLabel: 'Restore to draft',
      tone: 'primary',
    });
    if (!ok) return;
    setActionLoading(id);
    try {
      await governanceApi.unarchive(id);
      dialog.notify('Policy restored to draft.', { type: 'success' });
      await loadPolicies();
    } catch (err: any) {
      dialog.notify(err?.message ?? 'Failed to restore policy.', { type: 'error' });
    } finally { setActionLoading(null); }
  }, [loadPolicies]);

  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  const totalPages = Math.ceil(totalElements / 20);
  const auditTotalPages = Math.ceil(auditTotal / 20);

  /* =============================================================== */
  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-7xl mx-auto space-y-4 animate-fade-in">

        {/* -- Header Banner ---------------------------------------- */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <div className="w-10 h-10 rounded-xl bg-cyan-500/20 flex items-center justify-center">
                  <Scale className="w-5 h-5 text-cyan-300" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white">Clinical Governance</h1>
                  <p className="text-sm text-white/50">Policy lifecycle management, approval workflows & audit trail</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {isSuperAdmin && hospitals.length > 0 && (
                  <select
                    value={selectedHospitalId}
                    onChange={(e) => { setSelectedHospitalId(e.target.value); setPage(0); }}
                    title="Which hospital's policies to manage (national role)"
                    className="px-3 py-2 rounded-xl text-xs font-bold bg-white/15 text-white border border-white/15 focus:outline-none [&>option]:text-slate-800"
                  >
                    {hospitals.map((h) => <option key={h.id} value={h.id}>{h.name}</option>)}
                  </select>
                )}
                <button
                  onClick={() => { setShowCreateForm(!showCreateForm); setActiveTab('policies'); }}
                  className="flex items-center gap-2 px-4 py-2 bg-white/15 hover:bg-white/25 backdrop-blur rounded-xl text-white text-xs font-semibold transition-all duration-300 border border-white/10"
                >
                  <Plus className="w-3.5 h-3.5" />
                  New Policy
                </button>
                <button
                  onClick={loadPolicies}
                  disabled={loading}
                  className="flex items-center gap-2 px-3 py-2 bg-white/10 hover:bg-white/20 backdrop-blur rounded-xl text-white text-xs font-semibold transition-all duration-300 border border-white/10"
                >
                  <RefreshCw className={`w-3.5 h-3.5 ${loading ? 'animate-spin' : ''}`} />
                </button>
              </div>
            </div>
          </div>
        </div>

        {/* -- Tabs ------------------------------------------------- */}
        <div className="flex items-center gap-1 rounded-2xl p-1.5 animate-fade-up" style={{ ...glassCard, animationDelay: '0.06s' } as any}>
          {([
            { id: 'policies' as TabId, icon: FileText, label: 'Policies' },
            { id: 'audit' as TabId, icon: History, label: 'Audit Log' },
          ]).map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`flex items-center gap-2 px-4 py-2.5 rounded-xl text-xs font-bold transition-all duration-300 ${
                activeTab === tab.id
                  ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                  : `${text.body} hover:bg-white/5 border border-transparent`
              }`}
            >
              <tab.icon className="w-3.5 h-3.5" />
              {tab.label}
            </button>
          ))}
        </div>

        {/* -- Status Pipeline -------------------------------------- */}
        {activeTab === 'policies' && (
          <div className="rounded-2xl p-4 animate-fade-up" style={{ ...glassCard, animationDelay: '0.1s' } as any}>
            <p className={`text-[10px] font-bold ${text.muted} uppercase tracking-wider mb-3`}>Policy Lifecycle</p>
            <div className="flex items-center gap-2 flex-wrap">
              {STATUS_PIPELINE.map((st, i) => {
                const cfg = STATUS_CONFIG[st];
                return (
                  <div key={st} className="flex items-center gap-2">
                    <span className={`text-[10px] font-bold ${cfg.color} px-2.5 py-1 rounded-lg`} style={{ background: cfg.bg, border: cfg.border }}>
                      {cfg.label}
                    </span>
                    {i < STATUS_PIPELINE.length - 1 && <ArrowRight className={`w-3 h-3 ${text.muted}`} />}
                  </div>
                );
              })}
              <span className={`text-[10px] ${text.muted} mx-1`}>|</span>
              <span className="text-[10px] font-bold text-red-600 px-2.5 py-1 rounded-lg" style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}>
                Suspended
              </span>
              <span className="text-[10px] font-bold text-slate-600 px-2.5 py-1 rounded-lg" style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
                Archived
              </span>
            </div>
          </div>
        )}

        {/* ========================================================= */}
        {/* POLICIES TAB                                                */}
        {/* ========================================================= */}
        {activeTab === 'policies' && (
          <>
            {/* -- Create Form -------------------------------------- */}
            {showCreateForm && (
              <div className="rounded-2xl p-5 animate-fade-up" style={glassCard}>
                <div className="flex items-center gap-2 mb-4">
                  <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(139,92,246,0.12)' }}>
                    <Plus className="w-4 h-4 text-violet-500" />
                  </div>
                  <h3 className={`text-sm font-extrabold ${text.heading}`}>Create New Policy</h3>
                </div>

                <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                  {/* Policy Type */}
                  <div>
                    <label className={`block text-[11px] font-bold ${text.muted} uppercase tracking-wider mb-1.5`}>Policy Type</label>
                    <select
                      value={formType}
                      onChange={(e) => setFormType(e.target.value)}
                      className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300`}
                      style={glassInner}
                    >
                      {POLICY_TYPES.map((t) => (
                        <option key={t.value} value={t.value}>{t.label}</option>
                      ))}
                    </select>
                  </div>

                  {/* Policy Name */}
                  <div>
                    <label className={`block text-[11px] font-bold ${text.muted} uppercase tracking-wider mb-1.5`}>Policy Name</label>
                    <input
                      type="text"
                      value={formName}
                      onChange={(e) => setFormName(e.target.value)}
                      placeholder="e.g. Emergency Triage Standard Operating Procedure"
                      className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300`}
                      style={glassInner}
                    />
                  </div>

                  {/* Policy Code */}
                  <div>
                    <label className={`block text-[11px] font-bold ${text.muted} uppercase tracking-wider mb-1.5`}>Policy Code (optional)</label>
                    <input
                      type="text"
                      value={formCode}
                      onChange={(e) => setFormCode(e.target.value)}
                      placeholder="e.g. POL-TRI-001"
                      className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300`}
                      style={glassInner}
                    />
                  </div>

                  {/* Effective From */}
                  <div>
                    <label className={`block text-[11px] font-bold ${text.muted} uppercase tracking-wider mb-1.5`}>Effective From</label>
                    <input
                      type="date"
                      value={formEffective}
                      onChange={(e) => setFormEffective(e.target.value)}
                      className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300`}
                      style={glassInner}
                    />
                  </div>
                </div>

                {/* Description */}
                <div className="mt-4">
                  <label className={`block text-[11px] font-bold ${text.muted} uppercase tracking-wider mb-1.5`}>Description (optional)</label>
                  <input
                    type="text"
                    value={formDesc}
                    onChange={(e) => setFormDesc(e.target.value)}
                    placeholder="Brief description of the policy scope and purpose"
                    className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300`}
                    style={glassInner}
                  />
                </div>

                {/* Content */}
                <div className="mt-4">
                  <label className={`block text-[11px] font-bold ${text.muted} uppercase tracking-wider mb-1.5`}>Policy Content</label>
                  <textarea
                    value={formContent}
                    onChange={(e) => setFormContent(e.target.value)}
                    placeholder="Full policy content..."
                    rows={8}
                    className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300 resize-none`}
                    style={glassInner}
                  />
                </div>

                {formError && (
                  <div className="mt-4 flex items-start gap-2 rounded-xl px-3 py-2.5 bg-red-500/10 border border-red-500/20">
                    <AlertTriangle className="w-4 h-4 text-red-500 shrink-0 mt-0.5" />
                    <p className="text-[12px] font-semibold text-red-500">{formError}</p>
                  </div>
                )}

                <div className="flex items-center justify-end gap-3 mt-4 pt-3" style={{ borderTop: borderStyle }}>
                  <button
                    onClick={() => { setShowCreateForm(false); setFormError(null); }}
                    className={`px-4 py-2 text-xs font-semibold ${text.body} hover:opacity-80 transition-all duration-300 rounded-xl`}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleCreate}
                    disabled={!formName || !formContent || !formEffective || actionLoading === 'create'}
                    className="flex items-center gap-2 px-5 py-2 bg-cyan-600 hover:bg-cyan-700 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed"
                  >
                    {actionLoading === 'create' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
                    Create Policy
                  </button>
                </div>
              </div>
            )}

            {/* -- Status Filter ------------------------------------ */}
            <div className="flex items-center gap-3 px-1 animate-fade-up" style={{ animationDelay: '0.14s' } as any}>
              <div className="flex items-center gap-2">
                <Filter className={`w-3.5 h-3.5 ${text.muted}`} />
                <span className={`text-[11px] font-bold ${text.muted} uppercase tracking-wider`}>Status:</span>
              </div>
              <select
                value={statusFilter}
                onChange={(e) => { setStatusFilter(e.target.value); setPage(0); }}
                className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${text.heading} focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300`}
                style={glassInner}
              >
                <option value="">All Statuses</option>
                {ALL_STATUSES.filter(Boolean).map((st) => (
                  <option key={st} value={st}>{STATUS_CONFIG[st]?.label || st}</option>
                ))}
              </select>
              <span className={`text-[10px] ${text.muted} font-medium ml-auto`}>{policies.length} policies shown</span>
            </div>

            {/* -- Policy List -------------------------------------- */}
            <div className="space-y-2">
              {loading && policies.length === 0 ? (
                <div className="rounded-2xl p-12 text-center" style={glassCard}>
                  <Loader2 className={`w-8 h-8 ${text.muted} animate-spin mx-auto mb-3`} />
                  <p className={`text-sm font-bold ${text.body}`}>Loading policies...</p>
                </div>
              ) : policies.length === 0 ? (
                <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
                  <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.08)' }}>
                    <Scale className="w-8 h-8 text-slate-300" />
                  </div>
                  <p className={`text-sm font-bold ${text.heading}`}>No Policies Found</p>
                  <p className={`text-xs ${text.muted} mt-1`}>Create a new clinical policy using the button above</p>
                </div>
              ) : (
                <div className="space-y-2">
                  {policies.map((policy, idx) => {
                    const isExpanded = expandedId === policy.id;
                    const typeCfg = POLICY_TYPE_CONFIG[policy.policyType] || POLICY_TYPE_CONFIG.TRIAGE_PROTOCOL;
                    const statusCfg = STATUS_CONFIG[policy.status] || STATUS_CONFIG.DRAFT;
                    const isLoading = actionLoading === policy.id;

                    return (
                      <div
                        key={policy.id}
                        className="rounded-2xl overflow-hidden transition-all duration-300 animate-fade-up hover:-translate-y-0.5"
                        style={{ ...glassCard, animationDelay: `${0.16 + idx * 0.03}s` } as any}
                      >
                        {/* Row header */}
                        <button
                          onClick={() => setExpandedId(isExpanded ? null : policy.id)}
                          className="w-full text-left p-4"
                        >
                          <div className="flex items-center gap-3">
                            <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0" style={{ backgroundColor: typeCfg.bg }}>
                              <Scale className={`w-5 h-5 ${typeCfg.color}`} />
                            </div>

                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2 mb-0.5 flex-wrap">
                                <span className={`text-[10px] font-bold ${typeCfg.color} px-2 py-0.5 rounded-lg uppercase tracking-wider`} style={{ background: typeCfg.bg, border: typeCfg.border }}>
                                  {getTypeLabel(policy.policyType)}
                                </span>
                                <span className={`text-[10px] font-bold ${statusCfg.color} px-2 py-0.5 rounded-lg uppercase tracking-wider`} style={{ background: statusCfg.bg, border: statusCfg.border }}>
                                  {statusCfg.label}
                                </span>
                                {policy.policyVersion && (
                                  <span className="text-[10px] font-semibold text-slate-600 px-2 py-0.5 rounded-lg" style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>
                                    v{policy.policyVersion}
                                  </span>
                                )}
                              </div>
                              <p className={`text-[12px] font-semibold ${text.heading} truncate`}>{policy.policyName}</p>
                              <div className="flex items-center gap-3 mt-1 flex-wrap">
                                {policy.policyCode && (
                                  <span className={`text-[10px] font-mono ${text.muted}`}>{policy.policyCode}</span>
                                )}
                                <span className={`text-[10px] ${text.muted}`}>
                                  by <span className={`font-semibold ${text.body}`}>{policy.createdByName}</span>
                                </span>
                                <span className={`text-[10px] ${text.muted} flex items-center gap-1`}>
                                  <Clock className="w-2.5 h-2.5" />
                                  Effective {format(new Date(policy.effectiveFrom), 'dd MMM yyyy')}
                                  {policy.effectiveTo && ` — ${format(new Date(policy.effectiveTo), 'dd MMM yyyy')}`}
                                </span>
                              </div>
                            </div>

                            <div className="flex-shrink-0">
                              {isExpanded ? <ChevronDown className={`w-4 h-4 ${text.muted}`} /> : <ChevronRight className={`w-4 h-4 ${text.muted}`} />}
                            </div>
                          </div>
                        </button>

                        {/* Expanded detail */}
                        {isExpanded && (
                          <div className="px-4 pb-4 pt-1" style={{ borderTop: borderStyle }}>
                            {/* Meta grid */}
                            <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 mt-2">
                              <div className="rounded-xl p-3" style={glassInner}>
                                <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Created At</p>
                                <p className={`text-[11px] font-semibold ${text.heading}`}>{format(new Date(policy.createdAt), 'dd MMM yyyy HH:mm')}</p>
                              </div>
                              {policy.approvedByName && (
                                <div className="rounded-xl p-3" style={glassInner}>
                                  <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Approved By</p>
                                  <p className={`text-[11px] font-semibold ${text.heading}`}>{policy.approvedByName}</p>
                                </div>
                              )}
                              {policy.approvedAt && (
                                <div className="rounded-xl p-3" style={glassInner}>
                                  <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Approved At</p>
                                  <p className={`text-[11px] font-semibold ${text.heading}`}>{format(new Date(policy.approvedAt), 'dd MMM yyyy HH:mm')}</p>
                                </div>
                              )}
                              <div className="rounded-xl p-3" style={glassInner}>
                                <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Policy ID</p>
                                <p className={`text-[11px] font-mono ${text.heading} truncate`}>{policy.id}</p>
                              </div>
                            </div>

                            {/* Description */}
                            {policy.description && (
                              <div className="mt-3 rounded-xl p-3" style={glassInner}>
                                <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Description</p>
                                <p className={`text-xs ${text.body}`}>{policy.description}</p>
                              </div>
                            )}

                            {/* Content preview */}
                            <div className="mt-3 rounded-xl p-3" style={glassInner}>
                              <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-1`}>Policy Content</p>
                              <p className={`text-xs ${text.body} whitespace-pre-wrap max-h-48 overflow-y-auto`}>{policy.policyContent}</p>
                            </div>

                            {/* Action buttons */}
                            <div className="flex items-center gap-2 mt-4 pt-3 flex-wrap" style={{ borderTop: borderStyle }}>
                              {/* Policy administration is SA/HA-owned — the
                                  admin answers to no higher reviewer, so a
                                  DRAFT (or already-submitted) policy goes
                                  live in ONE action. The audit trail still
                                  records APPROVED + ACTIVATED separately. */}
                              {(policy.status === 'DRAFT' || policy.status === 'PENDING_APPROVAL') && (
                                <button
                                  onClick={() => setApproveTarget(policy.id)}
                                  disabled={isLoading}
                                  className="flex items-center gap-2 px-4 py-2 bg-cyan-600 hover:bg-cyan-700 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                                >
                                  {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5" />}
                                  Approve &amp; Activate
                                </button>
                              )}
                              {policy.status === 'APPROVED' && (
                                <button
                                  onClick={() => handleActivate(policy.id)}
                                  disabled={isLoading}
                                  className="flex items-center gap-2 px-4 py-2 bg-cyan-600 hover:bg-cyan-700 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                                >
                                  {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5" />}
                                  Activate
                                </button>
                              )}
                              {policy.status === 'ACTIVE' && (
                                <button
                                  onClick={() => setSuspendTarget(policy.id)}
                                  disabled={isLoading}
                                  className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-red-600 to-red-700 hover:from-red-700 hover:to-red-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                                >
                                  <PauseCircle className="w-3.5 h-3.5" />
                                  Suspend
                                </button>
                              )}
                              {(policy.status === 'SUSPENDED' || policy.status === 'ACTIVE') && (
                                <button
                                  onClick={() => setArchiveTarget(policy.id)}
                                  disabled={isLoading}
                                  className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-slate-600 to-slate-700 hover:from-slate-700 hover:to-slate-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                                >
                                  {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Archive className="w-3.5 h-3.5" />}
                                  Archive
                                </button>
                              )}
                              {policy.status === 'ARCHIVED' && (
                                <button
                                  onClick={() => handleUnarchive(policy.id)}
                                  disabled={isLoading}
                                  className="flex items-center gap-2 px-4 py-2 bg-cyan-600 hover:bg-cyan-700 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                                >
                                  {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ArchiveRestore className="w-3.5 h-3.5" />}
                                  Restore to Draft
                                </button>
                              )}

                              {/* Version history button */}
                              <button
                                onClick={() => { setHistoryTarget(policy.id); loadHistory(policy.id); }}
                                className={`flex items-center gap-2 px-3 py-2 text-xs font-semibold ${text.body} hover:opacity-80 transition-all duration-300 rounded-xl`}
                                style={glassInner}
                              >
                                <History className="w-3.5 h-3.5" />
                                Version History
                              </button>

                              {/* Audit log link */}
                              <button
                                onClick={() => { setAuditPolicyId(policy.id); setAuditPage(0); setActiveTab('audit'); loadAuditLog(policy.id, 0); }}
                                className={`flex items-center gap-2 px-3 py-2 text-xs font-semibold ${text.body} hover:opacity-80 transition-all duration-300 rounded-xl`}
                                style={glassInner}
                              >
                                <Search className="w-3.5 h-3.5" />
                                View Audit Log
                              </button>
                            </div>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}

              {/* Pagination */}
              {totalPages > 1 && (
                <div className="flex items-center justify-center gap-2 pt-3">
                  <button
                    onClick={() => setPage((p) => Math.max(0, p - 1))}
                    disabled={page === 0}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all duration-300 disabled:opacity-40 ${text.body}`}
                    style={glassInner}
                  >
                    Previous
                  </button>
                  <span className={`text-xs font-semibold ${text.muted}`}>Page {page + 1} of {totalPages}</span>
                  <button
                    onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                    disabled={page >= totalPages - 1}
                    className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all duration-300 disabled:opacity-40 ${text.body}`}
                    style={glassInner}
                  >
                    Next
                  </button>
                </div>
              )}
            </div>
          </>
        )}

        {/* ========================================================= */}
        {/* AUDIT LOG TAB                                               */}
        {/* ========================================================= */}
        {activeTab === 'audit' && (
          <div className="space-y-3 animate-fade-up" style={{ animationDelay: '0.12s' } as any}>
            {/* Policy selector for audit */}
            <div className="rounded-2xl p-4" style={glassCard}>
              <div className="flex items-center gap-3 flex-wrap">
                <div className="flex items-center gap-2">
                  <History className={`w-4 h-4 ${text.accent}`} />
                  <span className={`text-[11px] font-bold ${text.muted} uppercase tracking-wider`}>Audit Log for Policy:</span>
                </div>
                <select
                  value={auditPolicyId}
                  onChange={(e) => {
                    const id = e.target.value;
                    setAuditPolicyId(id);
                    setAuditPage(0);
                    if (id) loadAuditLog(id, 0);
                    else { setAuditLogs([]); setAuditTotal(0); }
                  }}
                  className={`flex-1 min-w-[200px] px-3 py-2 rounded-xl text-xs font-semibold ${text.heading} focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all duration-300`}
                  style={glassInner}
                >
                  <option value="">Select a policy...</option>
                  {policies.map((p) => (
                    <option key={p.id} value={p.id}>{p.policyName} ({p.policyCode || p.id.slice(0, 8)})</option>
                  ))}
                </select>
              </div>
            </div>

            {/* Audit entries */}
            {!auditPolicyId ? (
              <div className="rounded-2xl p-12 text-center" style={glassCard}>
                <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.08)' }}>
                  <History className="w-8 h-8 text-slate-300" />
                </div>
                <p className={`text-sm font-bold ${text.heading}`}>Select a Policy</p>
                <p className={`text-xs ${text.muted} mt-1`}>Choose a policy from the dropdown to view its audit log</p>
              </div>
            ) : auditLoading ? (
              <div className="rounded-2xl p-12 text-center" style={glassCard}>
                <Loader2 className={`w-8 h-8 ${text.muted} animate-spin mx-auto mb-3`} />
                <p className={`text-sm font-bold ${text.body}`}>Loading audit log...</p>
              </div>
            ) : auditLogs.length === 0 ? (
              <div className="rounded-2xl p-12 text-center" style={glassCard}>
                <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(100,116,139,0.08)' }}>
                  <History className="w-8 h-8 text-slate-300" />
                </div>
                <p className={`text-sm font-bold ${text.heading}`}>No Audit Entries</p>
                <p className={`text-xs ${text.muted} mt-1`}>No audit trail recorded for this policy yet</p>
              </div>
            ) : (
              <div className="space-y-2">
                {auditLogs.map((log, idx) => (
                  <div
                    key={log.id}
                    className="rounded-2xl p-4 transition-all duration-300 animate-fade-up"
                    style={{ ...glassCard, animationDelay: `${0.14 + idx * 0.03}s` } as any}
                  >
                    <div className="flex items-start gap-3">
                      <div className="w-10 h-10 rounded-xl flex items-center justify-center flex-shrink-0 mt-0.5" style={{ backgroundColor: 'rgba(139,92,246,0.10)' }}>
                        <History className="w-5 h-5 text-violet-500" />
                      </div>
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-1 flex-wrap">
                          <span className="text-[10px] font-bold text-violet-600 px-2 py-0.5 rounded-lg uppercase tracking-wider" style={{ background: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.2)' }}>
                            {log.action}
                          </span>
                          <span className={`text-[10px] ${text.muted} flex items-center gap-1`}>
                            <Clock className="w-2.5 h-2.5" />
                            {format(new Date(log.actionAt), 'dd MMM yyyy HH:mm:ss')}
                          </span>
                        </div>
                        <div className="flex items-center gap-2 mt-1">
                          <User className={`w-3 h-3 ${text.muted}`} />
                          <span className={`text-[11px] font-semibold ${text.heading}`}>{log.actionByName}</span>
                        </div>
                        {log.reason && (
                          <div className="mt-2 rounded-lg p-2.5" style={glassInner}>
                            <p className={`text-[9px] font-bold ${text.muted} uppercase tracking-wider mb-0.5`}>Reason</p>
                            <p className={`text-xs ${text.body}`}>{log.reason}</p>
                          </div>
                        )}
                      </div>
                    </div>
                  </div>
                ))}

                {/* Audit pagination */}
                {auditTotalPages > 1 && (
                  <div className="flex items-center justify-center gap-2 pt-3">
                    <button
                      onClick={() => { const p = Math.max(0, auditPage - 1); setAuditPage(p); loadAuditLog(auditPolicyId, p); }}
                      disabled={auditPage === 0}
                      className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all duration-300 disabled:opacity-40 ${text.body}`}
                      style={glassInner}
                    >
                      Previous
                    </button>
                    <span className={`text-xs font-semibold ${text.muted}`}>Page {auditPage + 1} of {auditTotalPages}</span>
                    <button
                      onClick={() => { const p = Math.min(auditTotalPages - 1, auditPage + 1); setAuditPage(p); loadAuditLog(auditPolicyId, p); }}
                      disabled={auditPage >= auditTotalPages - 1}
                      className={`px-3 py-1.5 text-xs font-semibold rounded-lg transition-all duration-300 disabled:opacity-40 ${text.body}`}
                      style={glassInner}
                    >
                      Next
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        )}

        {/* -- Approve Modal ---------------------------------------- */}
        {approveTarget && (
          <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'var(--modal-backdrop)' }}>
            <div className="rounded-2xl overflow-hidden shadow-2xl animate-scale-in p-6 w-full max-w-md" style={glassCard}>
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-blue-500/20 border border-blue-500/30">
                    <CheckCircle2 className="w-5 h-5 text-blue-400" />
                  </div>
                  <div>
                    <h3 className={`text-sm font-extrabold ${text.heading}`}>Approve &amp; Activate Policy</h3>
                    <p className={`text-[10px] ${text.muted}`}>Goes live immediately — approving as {user?.fullName || 'Unknown'}</p>
                  </div>
                </div>
                <button
                  onClick={() => { setApproveTarget(null); setApproveNotes(''); }}
                  className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5 transition-all duration-300`}
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
              <div>
                <label className={`block text-[11px] font-bold ${text.muted} uppercase tracking-wider mb-1.5`}>Notes (optional)</label>
                <textarea
                  value={approveNotes}
                  onChange={(e) => setApproveNotes(e.target.value)}
                  placeholder="Optional approval notes..."
                  rows={3}
                  className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-blue-500/20 transition-all duration-300 resize-none`}
                  style={glassInner}
                />
              </div>
              <div className="flex items-center justify-end gap-3 mt-4">
                <button
                  onClick={() => { setApproveTarget(null); setApproveNotes(''); }}
                  className={`px-4 py-2 text-xs font-semibold ${text.body} hover:opacity-80 transition-all duration-300 rounded-xl`}
                >
                  Cancel
                </button>
                <button
                  onClick={handleApprove}
                  disabled={actionLoading === approveTarget}
                  className="flex items-center gap-2 px-5 py-2 bg-cyan-600 hover:bg-cyan-700 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                >
                  {actionLoading === approveTarget ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5" />}
                  Approve &amp; Activate
                </button>
              </div>
            </div>
          </div>
        )}

        {/* -- Suspend Modal ---------------------------------------- */}
        {suspendTarget && (
          <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'var(--modal-backdrop)' }}>
            <div className="rounded-2xl overflow-hidden shadow-2xl animate-scale-in p-6 w-full max-w-md" style={glassCard}>
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-red-500/20 border border-red-500/30">
                    <PauseCircle className="w-5 h-5 text-red-400" />
                  </div>
                  <div>
                    <h3 className={`text-sm font-extrabold ${text.heading}`}>Suspend Policy</h3>
                    <p className={`text-[10px] ${text.muted}`}>Provide a reason for suspension</p>
                  </div>
                </div>
                <button
                  onClick={() => { setSuspendTarget(null); setSuspendReason(''); }}
                  className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5 transition-all duration-300`}
                >
                  <X className="w-4 h-4" />
                </button>
              </div>
              <textarea
                value={suspendReason}
                onChange={(e) => setSuspendReason(e.target.value)}
                placeholder="Reason for suspension..."
                rows={4}
                className={`w-full px-3 py-2.5 rounded-xl text-sm ${text.heading} placeholder-slate-400 focus:outline-none focus:ring-2 focus:ring-red-500/20 transition-all duration-300 resize-none`}
                style={glassInner}
              />
              <div className="flex items-center justify-end gap-3 mt-4">
                <button
                  onClick={() => { setSuspendTarget(null); setSuspendReason(''); }}
                  className={`px-4 py-2 text-xs font-semibold ${text.body} hover:opacity-80 transition-all duration-300 rounded-xl`}
                >
                  Cancel
                </button>
                <button
                  onClick={handleSuspend}
                  disabled={!suspendReason.trim() || actionLoading === suspendTarget}
                  className="flex items-center gap-2 px-5 py-2 bg-gradient-to-r from-red-600 to-red-700 hover:from-red-700 hover:to-red-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed"
                >
                  {actionLoading === suspendTarget ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <PauseCircle className="w-3.5 h-3.5" />}
                  Suspend Policy
                </button>
              </div>
            </div>
          </div>
        )}

        {/* -- Version History Modal -------------------------------- */}
        {historyTarget && (
          <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4 backdrop-blur-sm" style={{ background: 'var(--modal-backdrop)' }}>
            <div className="rounded-2xl overflow-hidden shadow-2xl animate-scale-in p-6 w-full max-w-lg max-h-[80vh] overflow-y-auto" style={glassCard}>
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-cyan-500/20 border border-cyan-500/30">
                    <History className="w-5 h-5 text-cyan-400" />
                  </div>
                  <div>
                    <h3 className={`text-sm font-extrabold ${text.heading}`}>Version History</h3>
                    <p className={`text-[10px] ${text.muted}`}>{historyItems.length} version(s) found</p>
                  </div>
                </div>
                <button
                  onClick={() => { setHistoryTarget(null); setHistoryItems([]); }}
                  className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5 transition-all duration-300`}
                >
                  <X className="w-4 h-4" />
                </button>
              </div>

              {historyLoading ? (
                <div className="text-center py-8">
                  <Loader2 className={`w-6 h-6 ${text.muted} animate-spin mx-auto`} />
                </div>
              ) : historyItems.length === 0 ? (
                <div className="text-center py-8">
                  <p className={`text-xs ${text.muted}`}>No version history available</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {historyItems.map((ver, i) => {
                    const sCfg = STATUS_CONFIG[ver.status] || STATUS_CONFIG.DRAFT;
                    return (
                      <div key={ver.id + '-' + i} className="rounded-xl p-3" style={glassInner}>
                        <div className="flex items-center gap-2 mb-1">
                          {ver.policyVersion && (
                            <span className={`text-[10px] font-bold ${text.accent}`}>v{ver.policyVersion}</span>
                          )}
                          <span className={`text-[10px] font-bold ${sCfg.color} px-2 py-0.5 rounded-lg`} style={{ background: sCfg.bg, border: sCfg.border }}>
                            {sCfg.label}
                          </span>
                          <span className={`text-[10px] ${text.muted} flex items-center gap-1 ml-auto`}>
                            <Clock className="w-2.5 h-2.5" />
                            {format(new Date(ver.createdAt), 'dd MMM yyyy HH:mm')}
                          </span>
                        </div>
                        <p className={`text-xs font-semibold ${text.heading}`}>{ver.policyName}</p>
                        <p className={`text-[10px] ${text.muted} mt-0.5`}>by {ver.createdByName}</p>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>
        )}

        <ConfirmDialog
          open={archiveTarget !== null}
          title="Archive policy"
          message="Are you sure you want to archive this policy? It will no longer be active for clinical use. You can restore it later, but it returns as a DRAFT and must re-pass approval."
          confirmLabel="Archive policy"
          busy={actionLoading === archiveTarget}
          onConfirm={() => archiveTarget && handleArchive(archiveTarget)}
          onClose={() => setArchiveTarget(null)}
        />
      </div>
    </div>
  );
}
