/* ===================================================================
   Clinical Governance — Module 24
   Policy lifecycle management with audit trail
   =================================================================== */

import { useState, useEffect, useCallback } from 'react';
import {
  Scale, Plus, Send, CheckCircle2, ShieldCheck, Archive, PauseCircle,
  ChevronDown, ChevronRight, Loader2, RefreshCw, Clock, FileText,
  Search, History, User, AlertTriangle, ArrowRight, Filter, X,
} from 'lucide-react';
import { useTheme } from '@/hooks/useTheme';
import { useAuthStore } from '@/store/authStore';
import { governanceApi } from '@/api/governance';
import type { ClinicalPolicy, PolicyAuditLog } from '@/api/governance';
import { format } from 'date-fns';

/* -- Constants ---------------------------------------------------- */

const POLICY_TYPES = [
  { value: 'TRIAGE_PROTOCOL', label: 'Triage Protocol' },
  { value: 'MEDICATION_GUIDELINE', label: 'Medication Guideline' },
  { value: 'INFECTION_CONTROL', label: 'Infection Control' },
  { value: 'ICU_CRITERIA', label: 'ICU Criteria' },
  { value: 'DOCUMENTATION_STANDARD', label: 'Documentation Standard' },
  { value: 'SAFETY_PROTOCOL', label: 'Safety Protocol' },
  { value: 'QUALITY_STANDARD', label: 'Quality Standard' },
  { value: 'TRAINING_REQUIREMENT', label: 'Training Requirement' },
  { value: 'EQUIPMENT_MAINTENANCE', label: 'Equipment Maintenance' },
  { value: 'STAFFING_GUIDELINE', label: 'Staffing Guideline' },
] as const;

const POLICY_TYPE_CONFIG: Record<string, { color: string; bg: string }> = {
  TRIAGE_PROTOCOL:         { color: 'text-blue-400',    bg: 'rgba(59,130,246,0.10)' },
  MEDICATION_GUIDELINE:    { color: 'text-emerald-400', bg: 'rgba(34,197,94,0.10)' },
  INFECTION_CONTROL:       { color: 'text-red-400',     bg: 'rgba(239,68,68,0.10)' },
  ICU_CRITERIA:            { color: 'text-orange-400',  bg: 'rgba(249,115,22,0.10)' },
  DOCUMENTATION_STANDARD:  { color: 'text-slate-400',   bg: 'rgba(100,116,139,0.10)' },
  SAFETY_PROTOCOL:         { color: 'text-amber-400',   bg: 'rgba(245,158,11,0.10)' },
  QUALITY_STANDARD:        { color: 'text-violet-400',  bg: 'rgba(139,92,246,0.10)' },
  TRAINING_REQUIREMENT:    { color: 'text-cyan-400',    bg: 'rgba(6,182,212,0.10)' },
  EQUIPMENT_MAINTENANCE:   { color: 'text-teal-400',    bg: 'rgba(20,184,166,0.10)' },
  STAFFING_GUIDELINE:      { color: 'text-pink-400',    bg: 'rgba(236,72,153,0.10)' },
};

const STATUS_CONFIG: Record<string, { color: string; bg: string; label: string }> = {
  DRAFT:             { color: 'text-slate-400',   bg: 'rgba(100,116,139,0.10)', label: 'Draft' },
  PENDING_APPROVAL:  { color: 'text-amber-400',   bg: 'rgba(245,158,11,0.10)', label: 'Pending Approval' },
  APPROVED:          { color: 'text-blue-400',    bg: 'rgba(59,130,246,0.10)',  label: 'Approved' },
  ACTIVE:            { color: 'text-emerald-400', bg: 'rgba(34,197,94,0.10)',   label: 'Active' },
  SUSPENDED:         { color: 'text-red-400',     bg: 'rgba(239,68,68,0.10)',   label: 'Suspended' },
  ARCHIVED:          { color: 'text-slate-400',   bg: 'rgba(100,116,139,0.08)', label: 'Archived' },
};

const STATUS_PIPELINE = ['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'ACTIVE'];
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
  const hospitalId = user?.hospitalId || '';

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
  const [formType, setFormType] = useState('TRIAGE_PROTOCOL');
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
      const res = await governanceApi.getAll(hospitalId, page);
      let filtered = res.content;
      if (statusFilter) {
        filtered = filtered.filter((p) => p.status === statusFilter);
      }
      setPolicies(filtered);
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
    try {
      await governanceApi.create({
        hospitalId,
        policyType: formType,
        policyName: formName,
        policyCode: formCode || null,
        description: formDesc || null,
        policyContent: formContent,
        effectiveFrom: formEffective,
      });
      setShowCreateForm(false);
      setFormName('');
      setFormCode('');
      setFormDesc('');
      setFormContent('');
      setFormEffective('');
      await loadPolicies();
    } catch { /* */ } finally { setActionLoading(null); }
  }, [hospitalId, formType, formName, formCode, formDesc, formContent, formEffective, loadPolicies]);

  const handleSubmitForApproval = useCallback(async (id: string) => {
    setActionLoading(id);
    try { await governanceApi.submitForApproval(id); await loadPolicies(); }
    catch { /* */ } finally { setActionLoading(null); }
  }, [loadPolicies]);

  const handleApprove = useCallback(async () => {
    if (!approveTarget) return;
    setActionLoading(approveTarget);
    try {
      await governanceApi.approve(approveTarget, {
        approverName: user?.fullName || 'Unknown',
        notes: approveNotes || undefined,
      });
      setApproveTarget(null);
      setApproveNotes('');
      await loadPolicies();
    } catch { /* */ } finally { setActionLoading(null); }
  }, [approveTarget, approveNotes, user, loadPolicies]);

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

  const handleArchive = useCallback(async (id: string) => {
    setActionLoading(id);
    try { await governanceApi.archive(id); await loadPolicies(); }
    catch { /* */ } finally { setActionLoading(null); }
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
                <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                  <div className="w-2 h-2 bg-cyan-400 rounded-full animate-pulse" />
                  <span className="text-xs font-semibold text-white/90">Module 24</span>
                </div>
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
                  ? 'bg-cyan-500/20 text-cyan-400 border border-cyan-500/30'
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
                    <span className={`text-[10px] font-bold ${cfg.color} px-2.5 py-1 rounded-lg`} style={{ background: cfg.bg }}>
                      {cfg.label}
                    </span>
                    {i < STATUS_PIPELINE.length - 1 && <ArrowRight className={`w-3 h-3 ${text.muted}`} />}
                  </div>
                );
              })}
              <span className={`text-[10px] ${text.muted} mx-1`}>|</span>
              <span className="text-[10px] font-bold text-red-400 px-2.5 py-1 rounded-lg" style={{ background: 'rgba(239,68,68,0.10)' }}>
                Suspended
              </span>
              <span className="text-[10px] font-bold text-slate-400 px-2.5 py-1 rounded-lg" style={{ background: 'rgba(100,116,139,0.08)' }}>
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

                <div className="flex items-center justify-end gap-3 mt-4 pt-3" style={{ borderTop: borderStyle }}>
                  <button
                    onClick={() => setShowCreateForm(false)}
                    className={`px-4 py-2 text-xs font-semibold ${text.body} hover:opacity-80 transition-all duration-300 rounded-xl`}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleCreate}
                    disabled={!formName || !formContent || !formEffective || actionLoading === 'create'}
                    className="flex items-center gap-2 px-5 py-2 bg-gradient-to-r from-violet-600 to-purple-600 hover:from-violet-700 hover:to-purple-700 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50 disabled:cursor-not-allowed"
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
                                <span className={`text-[10px] font-bold ${typeCfg.color} px-2 py-0.5 rounded-md uppercase tracking-wider`} style={{ background: typeCfg.bg }}>
                                  {getTypeLabel(policy.policyType)}
                                </span>
                                <span className={`text-[10px] font-bold ${statusCfg.color} px-2 py-0.5 rounded-md uppercase tracking-wider`} style={{ background: statusCfg.bg }}>
                                  {statusCfg.label}
                                </span>
                                {policy.policyVersion && (
                                  <span className={`text-[10px] font-semibold ${text.muted} px-2 py-0.5 rounded-md`} style={{ background: isDark ? 'rgba(12,74,110,0.18)' : 'rgba(100,116,139,0.06)' }}>
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
                              {policy.status === 'DRAFT' && (
                                <button
                                  onClick={() => handleSubmitForApproval(policy.id)}
                                  disabled={isLoading}
                                  className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-amber-500 to-amber-600 hover:from-amber-600 hover:to-amber-700 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                                >
                                  {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
                                  Submit for Approval
                                </button>
                              )}
                              {policy.status === 'PENDING_APPROVAL' && (
                                <button
                                  onClick={() => setApproveTarget(policy.id)}
                                  disabled={isLoading}
                                  className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-blue-600 to-blue-700 hover:from-blue-700 hover:to-blue-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                                >
                                  {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
                                  Approve
                                </button>
                              )}
                              {policy.status === 'APPROVED' && (
                                <button
                                  onClick={() => handleActivate(policy.id)}
                                  disabled={isLoading}
                                  className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-emerald-600 to-emerald-700 hover:from-emerald-700 hover:to-emerald-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
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
                                  onClick={() => handleArchive(policy.id)}
                                  disabled={isLoading}
                                  className="flex items-center gap-2 px-4 py-2 bg-gradient-to-r from-slate-600 to-slate-700 hover:from-slate-700 hover:to-slate-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                                >
                                  {isLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Archive className="w-3.5 h-3.5" />}
                                  Archive
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
                                onClick={() => { setAuditPolicyId(policy.id); setActiveTab('audit'); loadAuditLog(policy.id, 0); }}
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
                          <span className="text-[10px] font-bold text-violet-400 px-2 py-0.5 rounded-md uppercase tracking-wider" style={{ background: 'rgba(139,92,246,0.10)' }}>
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
          <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4" style={{ background: 'rgba(2,11,20,0.55)' }}>
            <div className="rounded-2xl overflow-hidden shadow-2xl animate-scale-in p-6 w-full max-w-md" style={glassCard}>
              <div className="flex items-center justify-between mb-4">
                <div className="flex items-center gap-3">
                  <div className="w-10 h-10 rounded-xl flex items-center justify-center bg-blue-500/20 border border-blue-500/30">
                    <CheckCircle2 className="w-5 h-5 text-blue-400" />
                  </div>
                  <div>
                    <h3 className={`text-sm font-extrabold ${text.heading}`}>Approve Policy</h3>
                    <p className={`text-[10px] ${text.muted}`}>Approving as {user?.fullName || 'Unknown'}</p>
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
                  className="flex items-center gap-2 px-5 py-2 bg-gradient-to-r from-blue-600 to-blue-700 hover:from-blue-700 hover:to-blue-800 text-white text-xs font-bold rounded-xl shadow-lg transition-all duration-300 disabled:opacity-50"
                >
                  {actionLoading === approveTarget ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <CheckCircle2 className="w-3.5 h-3.5" />}
                  Approve Policy
                </button>
              </div>
            </div>
          </div>
        )}

        {/* -- Suspend Modal ---------------------------------------- */}
        {suspendTarget && (
          <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4" style={{ background: 'rgba(2,11,20,0.55)' }}>
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
          <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4" style={{ background: 'rgba(2,11,20,0.55)' }}>
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
                          <span className={`text-[10px] font-bold ${sCfg.color} px-2 py-0.5 rounded-md`} style={{ background: sCfg.bg }}>
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

      </div>
    </div>
  );
}
