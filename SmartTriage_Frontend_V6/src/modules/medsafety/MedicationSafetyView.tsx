/* ═══════════════════════════════════════════════════════════════
   Module 13 — Medication Safety & Drug Formulary (REML)
   Prescription validation, safety checks, formulary management
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  Pill, Search, CheckCircle, XCircle, AlertTriangle, Shield,
  ChevronDown, ChevronUp, Loader2, RefreshCw, X, Clock,
  ShieldAlert, ShieldCheck, BookOpen, Plus, Eye, Activity,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { medsafetyApi } from '@/api/medsafety';
import type { MedicationSafetyCheck, DrugFormulary, ValidatePrescriptionRequest } from '@/api/medsafety';
import { format } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

// ── Check result styling ──
function getOverallStyle(check: MedicationSafetyCheck) {
  if (check.overriddenBy) return { bg: 'rgba(139,92,246,0.08)', text: 'text-violet-300', border: '1px solid rgba(139,92,246,0.2)', label: 'Overridden' };
  if (check.overallSafe) return { bg: 'rgba(34,197,94,0.08)', text: 'text-emerald-300', border: '1px solid rgba(34,197,94,0.2)', label: 'Safe' };
  const hasBlock = !check.allergyCheckPassed || !check.doseCheckPassed;
  if (hasBlock) return { bg: 'rgba(239,68,68,0.08)', text: 'text-red-300', border: '1px solid rgba(239,68,68,0.2)', label: 'Blocked' };
  return { bg: 'rgba(245,158,11,0.08)', text: 'text-amber-300', border: '1px solid rgba(245,158,11,0.2)', label: 'Warning' };
}

function getCheckIcon(passed: boolean) {
  return passed
    ? { Icon: CheckCircle, color: 'text-emerald-500', bg: 'rgba(34,197,94,0.1)' }
    : { Icon: XCircle, color: 'text-red-500', bg: 'rgba(239,68,68,0.1)' };
}

type MainTab = 'checks' | 'formulary';

export function MedicationSafetyView() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  // ── Tab state ──
  const [mainTab, setMainTab] = useState<MainTab>('checks');

  // ═══════════════════════════════════════════
  //  SAFETY CHECKS TAB
  // ═══════════════════════════════════════════
  const [checks, setChecks] = useState<MedicationSafetyCheck[]>([]);
  const [checksLoading, setChecksLoading] = useState(false);
  const [visitIdInput, setVisitIdInput] = useState('');
  const [activeVisitId, setActiveVisitId] = useState('');
  const [expandedCheckId, setExpandedCheckId] = useState<string | null>(null);

  // ── Override dialog ──
  const [overrideDialogOpen, setOverrideDialogOpen] = useState(false);
  const [overrideCheckId, setOverrideCheckId] = useState<string | null>(null);
  const [overrideReason, setOverrideReason] = useState('');
  const [overriding, setOverriding] = useState(false);

  // ── Validate form ──
  const [showValidateForm, setShowValidateForm] = useState(false);
  const [validateForm, setValidateForm] = useState<Partial<ValidatePrescriptionRequest>>({
    drugName: '',
    doseMg: 0,
    weightKg: undefined,
    medicationId: '',
  });
  const [validating, setValidating] = useState(false);

  // ═══════════════════════════════════════════
  //  FORMULARY TAB
  // ═══════════════════════════════════════════
  const [formulary, setFormulary] = useState<DrugFormulary[]>([]);
  const [formularyLoading, setFormularyLoading] = useState(false);
  const [formularySearch, setFormularySearch] = useState('');
  const [formularyPage, setFormularyPage] = useState(0);
  const [formularyTotal, setFormularyTotal] = useState(0);
  const [expandedDrugId, setExpandedDrugId] = useState<string | null>(null);

  // ── Add formulary entry form ──
  const [showAddDrug, setShowAddDrug] = useState(false);
  const [addDrugForm, setAddDrugForm] = useState<Partial<DrugFormulary>>({
    genericName: '',
    drugClass: '',
    atcCode: '',
    remlCategory: '',
    isHighAlert: false,
    isOnReml: true,
    requiresDoubleCheck: false,
  });
  const [addingDrug, setAddingDrug] = useState(false);

  // ── Load safety checks ──
  const loadChecks = useCallback(async () => {
    if (!activeVisitId) return;
    setChecksLoading(true);
    try {
      const result = await medsafetyApi.getForVisit(activeVisitId);
      setChecks(Array.isArray(result) ? result : []);
    } catch (err) {
      console.error('[MedicationSafety] Failed to load checks:', err);
      setChecks([]);
    } finally {
      setChecksLoading(false);
    }
  }, [activeVisitId]);

  useEffect(() => { if (mainTab === 'checks') loadChecks(); }, [loadChecks, mainTab]);

  // ── Load formulary ──
  const loadFormulary = useCallback(async () => {
    if (!hospitalId) return;
    setFormularyLoading(true);
    try {
      if (formularySearch.trim()) {
        const result = await medsafetyApi.searchFormulary(formularySearch.trim());
        setFormulary(Array.isArray(result) ? result : []);
        setFormularyTotal(Array.isArray(result) ? result.length : 0);
      } else {
        const result = await medsafetyApi.getFormulary(hospitalId, formularyPage);
        setFormulary(result.content || []);
        setFormularyTotal(result.totalElements || 0);
      }
    } catch (err) {
      console.error('[MedicationSafety] Failed to load formulary:', err);
      setFormulary([]);
    } finally {
      setFormularyLoading(false);
    }
  }, [hospitalId, formularyPage, formularySearch]);

  useEffect(() => { if (mainTab === 'formulary') loadFormulary(); }, [loadFormulary, mainTab]);

  // ── Search visit ──
  const handleSearchVisit = useCallback(() => {
    if (!visitIdInput.trim()) return;
    setActiveVisitId(visitIdInput.trim());
    setExpandedCheckId(null);
  }, [visitIdInput]);

  // ── Validate prescription ──
  const handleValidate = useCallback(async () => {
    if (!activeVisitId || !validateForm.drugName || !validateForm.doseMg) return;
    setValidating(true);
    try {
      await medsafetyApi.validate({
        visitId: activeVisitId,
        medicationId: validateForm.medicationId || '',
        drugName: validateForm.drugName,
        doseMg: validateForm.doseMg,
        weightKg: validateForm.weightKg,
      });
      setShowValidateForm(false);
      setValidateForm({ drugName: '', doseMg: 0, weightKg: undefined, medicationId: '' });
      loadChecks();
    } catch (err) {
      console.error('[MedicationSafety] Validate failed:', err);
    } finally {
      setValidating(false);
    }
  }, [activeVisitId, validateForm, loadChecks]);

  // ── Override ──
  const openOverrideDialog = (checkId: string) => {
    setOverrideCheckId(checkId);
    setOverrideReason('');
    setOverrideDialogOpen(true);
  };

  const handleOverride = useCallback(async () => {
    if (!overrideCheckId || !overrideReason.trim()) return;
    setOverriding(true);
    try {
      await medsafetyApi.override(overrideCheckId, {
        reason: overrideReason,
      });
      setOverrideDialogOpen(false);
      loadChecks();
    } catch (err) {
      console.error('[MedicationSafety] Override failed:', err);
    } finally {
      setOverriding(false);
    }
  }, [overrideCheckId, overrideReason, user, loadChecks]);

  // ── Add formulary entry ──
  const handleAddDrug = useCallback(async () => {
    if (!addDrugForm.genericName || !addDrugForm.drugClass) return;
    setAddingDrug(true);
    try {
      await medsafetyApi.addFormularyEntry(addDrugForm);
      setShowAddDrug(false);
      setAddDrugForm({ genericName: '', drugClass: '', atcCode: '', remlCategory: '', isHighAlert: false, isOnReml: true, requiresDoubleCheck: false });
      loadFormulary();
    } catch (err) {
      console.error('[MedicationSafety] Add formulary entry failed:', err);
    } finally {
      setAddingDrug(false);
    }
  }, [addDrugForm, loadFormulary]);

  // ── Stats ──
  const checkStats = {
    total: checks.length,
    safe: checks.filter(c => c.overallSafe).length,
    warnings: checks.filter(c => !c.overallSafe && !c.overriddenBy).length,
    overridden: checks.filter(c => !!c.overriddenBy).length,
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
                  <Pill className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Medication Safety</h1>
                  <p className="text-white/70 text-xs font-medium">Prescription validation, REML formulary & safety checks</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                  <ShieldCheck className="w-3.5 h-3.5 text-emerald-300" />
                  <span className="text-xs font-semibold text-white/90">5-Point Check</span>
                </div>
              </div>
            </div>
          </div>
        </div>

        {/* ── Main Tabs ── */}
        <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
          <div className="flex items-center gap-2">
            {([
              { key: 'checks' as MainTab, label: 'Safety Checks', icon: ShieldAlert },
              { key: 'formulary' as MainTab, label: 'Drug Formulary (REML)', icon: BookOpen },
            ]).map((tab) => {
              const Icon = tab.icon;
              return (
                <button
                  key={tab.key}
                  onClick={() => setMainTab(tab.key)}
                  className={`inline-flex items-center gap-2 px-5 py-2.5 text-xs font-bold rounded-xl transition-all ${
                    mainTab === tab.key
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                      : isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                  }`}
                >
                  <Icon className="w-4 h-4" />
                  {tab.label}
                </button>
              );
            })}
          </div>
        </div>

        {/* ══════════════════════════════════════════════════════
            SAFETY CHECKS TAB
           ══════════════════════════════════════════════════════ */}
        {mainTab === 'checks' && (
          <>
            {/* Visit ID Search */}
            <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
              <div className="flex flex-col sm:flex-row sm:items-center gap-3">
                <div className="relative flex-1">
                  <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                  <input
                    type="text"
                    value={visitIdInput}
                    onChange={(e) => setVisitIdInput(e.target.value)}
                    onKeyDown={(e) => e.key === 'Enter' && handleSearchVisit()}
                    placeholder="Enter Visit ID to load safety checks..."
                    className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all ${
                      isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                    }`}
                    style={glassInner}
                  />
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={handleSearchVisit}
                    className="px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white text-xs font-bold rounded-xl hover:shadow-lg transition-all"
                  >
                    Load Checks
                  </button>
                  {activeVisitId && (
                    <button
                      onClick={() => setShowValidateForm(!showValidateForm)}
                      className="inline-flex items-center gap-1.5 px-4 py-2.5 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl hover:shadow-lg transition-all"
                    >
                      <Plus className="w-3.5 h-3.5" />
                      Validate Rx
                    </button>
                  )}
                </div>
              </div>
            </div>

            {/* Validate form */}
            {showValidateForm && activeVisitId && (
              <div className="rounded-2xl overflow-hidden animate-fade-up" style={glassCard}>
                <div className="px-5 py-4 border-b border-white/10">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2.5">
                      <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(6,182,212,0.12)' }}>
                        <Activity className={`w-4 h-4 ${isDark ? 'text-cyan-400' : 'text-cyan-600'}`} />
                      </div>
                      <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Validate Prescription</h3>
                    </div>
                    <button onClick={() => setShowValidateForm(false)} className={`p-1.5 rounded-lg hover:bg-white/10 transition-colors ${text.muted}`}>
                      <X className="w-4 h-4" />
                    </button>
                  </div>
                </div>
                <div className="p-5 space-y-4">
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Drug Name</label>
                      <input
                        type="text"
                        value={validateForm.drugName || ''}
                        onChange={(e) => setValidateForm({ ...validateForm, drugName: e.target.value })}
                        placeholder="e.g. Amoxicillin"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Medication ID</label>
                      <input
                        type="text"
                        value={validateForm.medicationId || ''}
                        onChange={(e) => setValidateForm({ ...validateForm, medicationId: e.target.value })}
                        placeholder="Formulary medication ID..."
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Dose (mg)</label>
                      <input
                        type="number"
                        value={validateForm.doseMg || ''}
                        onChange={(e) => setValidateForm({ ...validateForm, doseMg: parseFloat(e.target.value) || 0 })}
                        placeholder="500"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Patient Weight (kg) <span className={text.muted}>(optional)</span></label>
                      <input
                        type="number"
                        value={validateForm.weightKg ?? ''}
                        onChange={(e) => setValidateForm({ ...validateForm, weightKg: e.target.value ? parseFloat(e.target.value) : undefined })}
                        placeholder="70"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                  </div>
                  <div className="flex items-center justify-end gap-3 pt-2">
                    <button
                      onClick={() => setShowValidateForm(false)}
                      className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                        isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-slate-100'
                      }`}
                    >
                      Cancel
                    </button>
                    <button
                      onClick={handleValidate}
                      disabled={validating || !validateForm.drugName || !validateForm.doseMg}
                      className="inline-flex items-center gap-1.5 px-5 py-2.5 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl hover:shadow-lg transition-all disabled:opacity-50"
                    >
                      {validating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <ShieldCheck className="w-3.5 h-3.5" />}
                      Run Safety Check
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* Stats */}
            {activeVisitId && (
              <div className="grid grid-cols-4 gap-3 animate-fade-up">
                {[
                  { label: 'Total Checks', value: checkStats.total, icon: Activity, color: 'text-cyan-500', bg: 'rgba(6,182,212,0.1)' },
                  { label: 'Safe', value: checkStats.safe, icon: CheckCircle, color: 'text-emerald-500', bg: 'rgba(34,197,94,0.1)' },
                  { label: 'Warnings', value: checkStats.warnings, icon: AlertTriangle, color: 'text-amber-500', bg: 'rgba(245,158,11,0.1)' },
                  { label: 'Overridden', value: checkStats.overridden, icon: Shield, color: 'text-violet-500', bg: 'rgba(139,92,246,0.1)' },
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
            )}

            {/* Checks list */}
            {!activeVisitId ? (
              <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
                <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(6,182,212,0.1)' }}>
                  <Search className="w-8 h-8 text-cyan-400" />
                </div>
                <p className={`text-sm font-bold ${text.heading}`}>Enter a Visit ID</p>
                <p className={`text-xs font-medium mt-1 ${text.muted}`}>
                  Search for a visit to view medication safety checks
                </p>
              </div>
            ) : checksLoading ? (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="w-7 h-7 animate-spin text-cyan-500" />
              </div>
            ) : checks.length === 0 ? (
              <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
                <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(34,197,94,0.1)' }}>
                  <ShieldCheck className="w-8 h-8 text-emerald-400" />
                </div>
                <p className={`text-sm font-bold ${text.heading}`}>No Safety Checks</p>
                <p className={`text-xs font-medium mt-1 ${text.muted}`}>
                  No medication safety checks found for this visit
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {checks
                  .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime())
                  .map((check, idx) => {
                    const overall = getOverallStyle(check);
                    const isExpanded = expandedCheckId === check.id;
                    const allPassed = check.allergyCheckPassed && check.doseCheckPassed && check.interactionCheckPassed && check.duplicateTherapyCheckPassed;

                    return (
                      <div
                        key={check.id}
                        className="rounded-2xl overflow-hidden transition-all animate-fade-up hover:-translate-y-0.5"
                        style={{
                          ...glassCard,
                          border: overall.border,
                          animationDelay: `${0.05 + idx * 0.04}s`,
                        } as React.CSSProperties}
                      >
                        {/* Card header */}
                        <button
                          onClick={() => setExpandedCheckId(isExpanded ? null : check.id)}
                          className="w-full text-left px-5 py-4"
                        >
                          <div className="flex items-start gap-4">
                            {/* Status icon */}
                            <div className="w-11 h-11 rounded-xl flex items-center justify-center flex-shrink-0" style={{ backgroundColor: overall.bg }}>
                              {allPassed || check.overriddenBy
                                ? <ShieldCheck className={`w-5 h-5 ${overall.text}`} />
                                : <ShieldAlert className={`w-5 h-5 ${overall.text}`} />
                              }
                            </div>

                            {/* Content */}
                            <div className="flex-1 min-w-0">
                              <div className="flex items-center gap-2.5 mb-1.5 flex-wrap">
                                {/* Overall badge */}
                                <span
                                  className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider ${overall.text}`}
                                  style={{ background: overall.bg, border: overall.border }}
                                >
                                  {overall.label}
                                </span>
                                {/* Individual check indicators */}
                                {[
                                  { label: 'Allergy', passed: check.allergyCheckPassed },
                                  { label: 'Dose', passed: check.doseCheckPassed },
                                  { label: 'Interaction', passed: check.interactionCheckPassed },
                                  { label: 'Duplicate', passed: check.duplicateTherapyCheckPassed },
                                ].map((c) => (
                                  <span
                                    key={c.label}
                                    className={`inline-flex items-center gap-1 px-2 py-0.5 text-[9px] font-bold rounded-md ${
                                      c.passed ? 'text-emerald-300' : 'text-red-300'
                                    }`}
                                    style={{
                                      background: c.passed ? 'rgba(34,197,94,0.06)' : 'rgba(239,68,68,0.06)',
                                      border: c.passed ? '1px solid rgba(34,197,94,0.15)' : '1px solid rgba(239,68,68,0.15)',
                                    }}
                                  >
                                    {c.passed ? <CheckCircle className="w-2.5 h-2.5" /> : <XCircle className="w-2.5 h-2.5" />}
                                    {c.label}
                                  </span>
                                ))}
                                {/* Date */}
                                <span className={`ml-auto text-[10px] font-medium flex items-center gap-1 ${text.muted}`}>
                                  <Clock className="w-3 h-3" />
                                  {check.checkedAt ? format(new Date(check.checkedAt), 'MMM d, yyyy HH:mm') : '--'}
                                </span>
                              </div>
                              {/* Drug name and dose */}
                              <p className={`text-sm font-bold leading-snug ${text.heading}`}>
                                {check.drugName} - {check.prescribedDoseMg}mg
                              </p>
                              <p className={`text-[11px] font-medium mt-0.5 ${text.muted}`}>
                                {check.patientWeightKg ? `Patient: ${check.patientWeightKg}kg` : 'Weight not recorded'}
                                {check.overriddenBy && ` | Overridden by ${check.overriddenBy}`}
                              </p>
                            </div>

                            {/* Expand chevron */}
                            <div className="flex-shrink-0 pt-1">
                              {isExpanded ? (
                                <ChevronUp className={`w-4 h-4 ${text.muted}`} />
                              ) : (
                                <ChevronDown className={`w-4 h-4 ${text.muted}`} />
                              )}
                            </div>
                          </div>
                        </button>

                        {/* Expanded detail */}
                        {isExpanded && (
                          <div className="px-5 pb-5 border-t border-white/10">
                            {/* Individual checks */}
                            <div className="mt-4 grid grid-cols-1 sm:grid-cols-2 gap-3">
                              {[
                                { label: 'Allergy Check', passed: check.allergyCheckPassed, warning: check.allergyWarning },
                                { label: 'Dose Check', passed: check.doseCheckPassed, warning: check.doseWarning },
                                { label: 'Interaction Check', passed: check.interactionCheckPassed, warning: check.interactionWarning },
                                { label: 'Duplicate Therapy', passed: check.duplicateTherapyCheckPassed, warning: check.duplicateWarning },
                              ].map((c) => {
                                const ci = getCheckIcon(c.passed);
                                const CIcon = ci.Icon;
                                return (
                                  <div key={c.label} className="rounded-xl p-3" style={glassInner}>
                                    <div className="flex items-center gap-2 mb-1">
                                      <div className="w-6 h-6 rounded-md flex items-center justify-center" style={{ backgroundColor: ci.bg }}>
                                        <CIcon className={`w-3.5 h-3.5 ${ci.color}`} />
                                      </div>
                                      <p className={`text-xs font-bold ${text.heading}`}>{c.label}</p>
                                      <span className={`ml-auto text-[10px] font-bold uppercase tracking-wider ${c.passed ? 'text-emerald-500' : 'text-red-500'}`}>
                                        {c.passed ? 'PASS' : 'FAIL'}
                                      </span>
                                    </div>
                                    {c.warning && (
                                      <p className={`text-[11px] mt-1 pl-8 ${c.passed ? text.muted : 'text-red-500'}`}>
                                        {c.warning}
                                      </p>
                                    )}
                                  </div>
                                );
                              })}
                            </div>

                            {/* Override info */}
                            {check.overriddenBy && (
                              <div className="mt-3 rounded-xl p-3" style={{ ...glassInner, border: '1px solid rgba(139,92,246,0.2)' }}>
                                <div className="flex items-center gap-2 mb-1">
                                  <Shield className="w-3.5 h-3.5 text-violet-500" />
                                  <p className="text-[10px] font-bold uppercase tracking-wider text-violet-500">Override Applied</p>
                                </div>
                                <p className={`text-xs ${text.body}`}>
                                  By {check.overriddenBy}
                                  {check.overriddenAt && ` on ${format(new Date(check.overriddenAt), 'MMM d, yyyy HH:mm')}`}
                                </p>
                                {check.overrideReason && (
                                  <p className={`text-xs mt-1 ${text.body}`}>Reason: {check.overrideReason}</p>
                                )}
                              </div>
                            )}

                            {/* Action buttons */}
                            {!check.overallSafe && !check.overriddenBy && (
                              <div className="mt-4 flex items-center gap-2">
                                <button
                                  onClick={() => openOverrideDialog(check.id)}
                                  className={`inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl transition-all ${
                                    isDark ? 'bg-amber-500/15 text-amber-400 hover:bg-amber-500/25' : 'bg-amber-50 text-amber-700 hover:bg-amber-100'
                                  }`}
                                >
                                  <Shield className="w-3.5 h-3.5" /> Override
                                </button>
                              </div>
                            )}
                          </div>
                        )}
                      </div>
                    );
                  })}
              </div>
            )}
          </>
        )}

        {/* ══════════════════════════════════════════════════════
            FORMULARY TAB
           ══════════════════════════════════════════════════════ */}
        {mainTab === 'formulary' && (
          <>
            {/* Search and actions */}
            <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
              <div className="flex flex-col sm:flex-row sm:items-center gap-3">
                <div className="relative flex-1">
                  <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                  <input
                    type="text"
                    value={formularySearch}
                    onChange={(e) => { setFormularySearch(e.target.value); setFormularyPage(0); }}
                    placeholder="Search REML formulary by drug name..."
                    className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all ${
                      isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                    }`}
                    style={glassInner}
                  />
                </div>
                <div className="flex items-center gap-2">
                  <button
                    onClick={loadFormulary}
                    className="w-9 h-9 rounded-xl flex items-center justify-center hover:bg-white/10 transition-colors"
                    style={glassInner}
                  >
                    <RefreshCw className={`w-4 h-4 ${formularyLoading ? 'animate-spin' : ''} ${text.muted}`} />
                  </button>
                  <button
                    onClick={() => setShowAddDrug(!showAddDrug)}
                    className="inline-flex items-center gap-1.5 px-4 py-2.5 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl hover:shadow-lg transition-all"
                  >
                    <Plus className="w-3.5 h-3.5" />
                    Add Entry
                  </button>
                </div>
              </div>
            </div>

            {/* Add drug form */}
            {showAddDrug && (
              <div className="rounded-2xl overflow-hidden animate-fade-up" style={glassCard}>
                <div className="px-5 py-4 border-b border-white/10">
                  <div className="flex items-center justify-between">
                    <div className="flex items-center gap-2.5">
                      <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(6,182,212,0.12)' }}>
                        <Plus className={`w-4 h-4 ${isDark ? 'text-cyan-400' : 'text-cyan-600'}`} />
                      </div>
                      <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Add Formulary Entry</h3>
                    </div>
                    <button onClick={() => setShowAddDrug(false)} className={`p-1.5 rounded-lg hover:bg-white/10 transition-colors ${text.muted}`}>
                      <X className="w-4 h-4" />
                    </button>
                  </div>
                </div>
                <div className="p-5 space-y-4">
                  <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Generic Name</label>
                      <input
                        type="text"
                        value={addDrugForm.genericName || ''}
                        onChange={(e) => setAddDrugForm({ ...addDrugForm, genericName: e.target.value })}
                        placeholder="e.g. Amoxicillin"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Drug Class</label>
                      <input
                        type="text"
                        value={addDrugForm.drugClass || ''}
                        onChange={(e) => setAddDrugForm({ ...addDrugForm, drugClass: e.target.value })}
                        placeholder="e.g. Antibiotic"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>ATC Code</label>
                      <input
                        type="text"
                        value={addDrugForm.atcCode || ''}
                        onChange={(e) => setAddDrugForm({ ...addDrugForm, atcCode: e.target.value })}
                        placeholder="e.g. J01CA04"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>REML Category</label>
                      <input
                        type="text"
                        value={addDrugForm.remlCategory || ''}
                        onChange={(e) => setAddDrugForm({ ...addDrugForm, remlCategory: e.target.value })}
                        placeholder="e.g. Essential"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Adult Min Dose (mg)</label>
                      <input
                        type="number"
                        value={addDrugForm.adultMinDoseMg ?? ''}
                        onChange={(e) => setAddDrugForm({ ...addDrugForm, adultMinDoseMg: e.target.value ? parseFloat(e.target.value) : null })}
                        placeholder="250"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Adult Max Dose (mg)</label>
                      <input
                        type="number"
                        value={addDrugForm.adultMaxDoseMg ?? ''}
                        onChange={(e) => setAddDrugForm({ ...addDrugForm, adultMaxDoseMg: e.target.value ? parseFloat(e.target.value) : null })}
                        placeholder="500"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Pediatric Min (mg/kg)</label>
                      <input
                        type="number"
                        value={addDrugForm.pediatricMinDoseMgPerKg ?? ''}
                        onChange={(e) => setAddDrugForm({ ...addDrugForm, pediatricMinDoseMgPerKg: e.target.value ? parseFloat(e.target.value) : null })}
                        placeholder="25"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Pediatric Max (mg/kg)</label>
                      <input
                        type="number"
                        value={addDrugForm.pediatricMaxDoseMgPerKg ?? ''}
                        onChange={(e) => setAddDrugForm({ ...addDrugForm, pediatricMaxDoseMgPerKg: e.target.value ? parseFloat(e.target.value) : null })}
                        placeholder="50"
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                  </div>
                  {/* Toggles */}
                  <div className="flex items-center gap-6 pt-1">
                    {[
                      { key: 'isHighAlert' as const, label: 'High Alert' },
                      { key: 'requiresDoubleCheck' as const, label: 'Double Check Required' },
                      { key: 'isOnReml' as const, label: 'On REML' },
                    ].map((toggle) => (
                      <label key={toggle.key} className="flex items-center gap-2 cursor-pointer">
                        <div
                          onClick={() => setAddDrugForm({ ...addDrugForm, [toggle.key]: !addDrugForm[toggle.key] })}
                          className={`w-9 h-5 rounded-full transition-colors relative cursor-pointer ${
                            addDrugForm[toggle.key] ? 'bg-cyan-500' : isDark ? 'bg-slate-600' : 'bg-slate-300'
                          }`}
                        >
                          <div className={`w-4 h-4 rounded-full bg-white absolute top-0.5 transition-transform shadow-sm ${
                            addDrugForm[toggle.key] ? 'translate-x-4' : 'translate-x-0.5'
                          }`} />
                        </div>
                        <span className={`text-xs font-bold ${text.label}`}>{toggle.label}</span>
                      </label>
                    ))}
                  </div>
                  <div className="flex items-center justify-end gap-3 pt-2">
                    <button
                      onClick={() => setShowAddDrug(false)}
                      className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                        isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-slate-100'
                      }`}
                    >
                      Cancel
                    </button>
                    <button
                      onClick={handleAddDrug}
                      disabled={addingDrug || !addDrugForm.genericName || !addDrugForm.drugClass}
                      className="inline-flex items-center gap-1.5 px-5 py-2.5 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl hover:shadow-lg transition-all disabled:opacity-50"
                    >
                      {addingDrug ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
                      Add to Formulary
                    </button>
                  </div>
                </div>
              </div>
            )}

            {/* List header */}
            <div className="flex items-center justify-between px-1 animate-fade-up">
              <div className="flex items-center gap-2.5">
                <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(99,102,241,0.12)' }}>
                  <BookOpen className={`w-4 h-4 ${isDark ? 'text-indigo-400' : 'text-indigo-500'}`} />
                </div>
                <div>
                  <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Rwanda Essential Medicines List</h3>
                  <p className={`text-[11px] font-medium ${text.muted}`}>
                    {formulary.length} medication{formulary.length !== 1 ? 's' : ''} loaded
                    {formularyTotal > 50 && !formularySearch && ` (page ${formularyPage + 1} of ${Math.ceil(formularyTotal / 50)})`}
                  </p>
                </div>
              </div>
            </div>

            {/* Formulary list */}
            {formularyLoading ? (
              <div className="flex items-center justify-center py-16">
                <Loader2 className="w-7 h-7 animate-spin text-cyan-500" />
              </div>
            ) : formulary.length === 0 ? (
              <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
                <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(148,163,184,0.1)' }}>
                  <Pill className="w-8 h-8 text-slate-400" />
                </div>
                <p className={`text-sm font-bold ${text.heading}`}>No Medications Found</p>
                <p className={`text-xs font-medium mt-1 ${text.muted}`}>
                  {formularySearch ? 'No medications match your search' : 'The formulary is empty'}
                </p>
              </div>
            ) : (
              <div className="space-y-3">
                {formulary.map((drug, idx) => {
                  const isExpanded = expandedDrugId === drug.id;
                  return (
                    <div
                      key={drug.id}
                      className="rounded-2xl overflow-hidden transition-all animate-fade-up hover:-translate-y-0.5"
                      style={{
                        ...glassCard,
                        border: drug.isHighAlert ? '1px solid rgba(239,68,68,0.25)' : undefined,
                        animationDelay: `${0.05 + idx * 0.04}s`,
                      } as React.CSSProperties}
                    >
                      <button
                        onClick={() => setExpandedDrugId(isExpanded ? null : drug.id)}
                        className="w-full text-left px-5 py-4"
                      >
                        <div className="flex items-start gap-4">
                          {/* Icon */}
                          <div className="w-11 h-11 rounded-xl flex items-center justify-center flex-shrink-0" style={{
                            backgroundColor: drug.isHighAlert ? 'rgba(239,68,68,0.1)' : 'rgba(6,182,212,0.1)'
                          }}>
                            <Pill className={`w-5 h-5 ${drug.isHighAlert ? 'text-red-500' : 'text-cyan-500'}`} />
                          </div>

                          {/* Content */}
                          <div className="flex-1 min-w-0">
                            <div className="flex items-center gap-2.5 mb-1.5 flex-wrap">
                              {/* Drug class badge */}
                              <span
                                className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider ${isDark ? 'text-cyan-400' : 'text-cyan-600'}`}
                                style={{ background: 'rgba(6,182,212,0.08)', border: '1px solid rgba(6,182,212,0.2)' }}
                              >
                                {drug.drugClass}
                              </span>
                              {/* ATC code */}
                              <span className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>
                                {drug.atcCode}
                              </span>
                              {/* High alert badge */}
                              {drug.isHighAlert && (
                                <span
                                  className="inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg text-red-300"
                                  style={{ background: 'rgba(239,68,68,0.08)', border: '1px solid rgba(239,68,68,0.2)' }}
                                >
                                  <AlertTriangle className="w-2.5 h-2.5" /> HIGH ALERT
                                </span>
                              )}
                              {drug.requiresDoubleCheck && (
                                <span
                                  className="inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg text-amber-300"
                                  style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}
                                >
                                  <Eye className="w-2.5 h-2.5" /> Double Check
                                </span>
                              )}
                              {/* REML category */}
                              {drug.remlCategory && (
                                <span className={`ml-auto text-[10px] font-bold px-2 py-0.5 rounded-lg ${isDark ? 'bg-indigo-500/10 text-indigo-400' : 'bg-indigo-50 text-indigo-600'}`}>
                                  {drug.remlCategory}
                                </span>
                              )}
                            </div>
                            {/* Drug name */}
                            <p className={`text-sm font-bold leading-snug ${text.heading}`}>{drug.genericName}</p>
                            {drug.brandNames && (
                              <p className={`text-[11px] font-medium mt-0.5 ${text.muted}`}>Brands: {drug.brandNames}</p>
                            )}
                          </div>

                          {/* Expand */}
                          <div className="flex-shrink-0 pt-1">
                            {isExpanded ? <ChevronUp className={`w-4 h-4 ${text.muted}`} /> : <ChevronDown className={`w-4 h-4 ${text.muted}`} />}
                          </div>
                        </div>
                      </button>

                      {/* Expanded detail */}
                      {isExpanded && (
                        <div className="px-5 pb-5 border-t border-white/10">
                          <div className="mt-4 grid grid-cols-2 lg:grid-cols-4 gap-3">
                            <div className="rounded-xl p-3" style={glassInner}>
                              <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Adult Dose Range</p>
                              <p className={`text-xs font-semibold mt-0.5 ${text.heading}`}>
                                {drug.adultMinDoseMg != null && drug.adultMaxDoseMg != null
                                  ? `${drug.adultMinDoseMg} - ${drug.adultMaxDoseMg} mg`
                                  : drug.adultMinDoseMg != null ? `${drug.adultMinDoseMg}+ mg` : '--'}
                              </p>
                            </div>
                            <div className="rounded-xl p-3" style={glassInner}>
                              <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Pediatric Dose</p>
                              <p className={`text-xs font-semibold mt-0.5 ${text.heading}`}>
                                {drug.pediatricMinDoseMgPerKg != null && drug.pediatricMaxDoseMgPerKg != null
                                  ? `${drug.pediatricMinDoseMgPerKg} - ${drug.pediatricMaxDoseMgPerKg} mg/kg`
                                  : drug.pediatricMinDoseMgPerKg != null ? `${drug.pediatricMinDoseMgPerKg}+ mg/kg` : '--'}
                              </p>
                            </div>
                            <div className="rounded-xl p-3" style={glassInner}>
                              <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Pregnancy Category</p>
                              <p className={`text-xs font-semibold mt-0.5 ${text.heading}`}>
                                {drug.pregnancyCategory || '--'}
                              </p>
                            </div>
                            <div className="rounded-xl p-3" style={glassInner}>
                              <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>On REML</p>
                              <p className={`text-xs font-semibold mt-0.5 ${drug.isOnReml ? 'text-emerald-500' : 'text-red-500'}`}>
                                {drug.isOnReml ? 'Yes' : 'No'}
                              </p>
                            </div>
                          </div>
                          {/* Allergens */}
                          {drug.allergenGroups && (
                            <div className="mt-3 rounded-xl p-3" style={{ ...glassInner, border: '1px solid rgba(245,158,11,0.2)' }}>
                              <div className="flex items-center gap-2 mb-1">
                                <AlertTriangle className="w-3.5 h-3.5 text-amber-500" />
                                <p className="text-[10px] font-bold uppercase tracking-wider text-amber-500">Allergen Groups</p>
                              </div>
                              <p className={`text-xs ${text.body}`}>{drug.allergenGroups}</p>
                            </div>
                          )}
                          {/* Major interactions */}
                          {drug.majorInteractions && (
                            <div className="mt-3 rounded-xl p-3" style={{ ...glassInner, border: '1px solid rgba(239,68,68,0.2)' }}>
                              <div className="flex items-center gap-2 mb-1">
                                <XCircle className="w-3.5 h-3.5 text-red-500" />
                                <p className="text-[10px] font-bold uppercase tracking-wider text-red-500">Major Interactions</p>
                              </div>
                              <p className={`text-xs ${text.body}`}>{drug.majorInteractions}</p>
                            </div>
                          )}
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}

            {/* Pagination */}
            {!formularySearch && formularyTotal > 50 && (
              <div className="flex items-center justify-center gap-3 pt-2 animate-fade-up">
                <button
                  onClick={() => setFormularyPage(Math.max(0, formularyPage - 1))}
                  disabled={formularyPage === 0}
                  className={`px-4 py-2 text-xs font-bold rounded-xl transition-all disabled:opacity-40 ${
                    isDark ? 'text-slate-300 hover:bg-white/5' : 'text-slate-600 hover:bg-slate-100'
                  }`}
                >
                  Previous
                </button>
                <span className={`text-xs font-semibold ${text.muted}`}>
                  Page {formularyPage + 1} of {Math.ceil(formularyTotal / 50)}
                </span>
                <button
                  onClick={() => setFormularyPage(formularyPage + 1)}
                  disabled={(formularyPage + 1) * 50 >= formularyTotal}
                  className={`px-4 py-2 text-xs font-bold rounded-xl transition-all disabled:opacity-40 ${
                    isDark ? 'text-slate-300 hover:bg-white/5' : 'text-slate-600 hover:bg-slate-100'
                  }`}
                >
                  Next
                </button>
              </div>
            )}
          </>
        )}

        {/* ── Override Dialog ── */}
        {overrideDialogOpen && (
          <div className="fixed inset-0 z-[9999] flex items-center justify-center p-4" style={{ background: 'rgba(2,11,20,0.55)' }}>
            <div className="absolute inset-0" onClick={() => setOverrideDialogOpen(false)} />
            <div className="relative w-full max-w-lg rounded-2xl overflow-hidden shadow-2xl animate-scale-in" style={glassCard}>
              <div className="px-5 py-4 border-b border-white/10">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2.5">
                    <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(245,158,11,0.12)' }}>
                      <Shield className="w-4 h-4 text-amber-500" />
                    </div>
                    <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Override Safety Check</h3>
                  </div>
                  <button onClick={() => setOverrideDialogOpen(false)} className={`p-1.5 rounded-lg hover:bg-white/10 transition-colors ${text.muted}`}>
                    <X className="w-4 h-4" />
                  </button>
                </div>
              </div>
              <div className="p-5 space-y-4">
                <div className="rounded-xl p-3" style={{ ...glassInner, border: '1px solid rgba(245,158,11,0.2)' }}>
                  <div className="flex items-center gap-2">
                    <AlertTriangle className="w-4 h-4 text-amber-500" />
                    <p className={`text-xs font-bold text-amber-500`}>
                      This will override the safety check. Clinical justification is required.
                    </p>
                  </div>
                </div>
                <div>
                  <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Override Reason</label>
                  <textarea
                    value={overrideReason}
                    onChange={(e) => setOverrideReason(e.target.value)}
                    placeholder="Provide clinical justification for overriding this safety check..."
                    rows={4}
                    className={`w-full px-4 py-3 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 resize-y ${
                      isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                    }`}
                    style={glassInner}
                  />
                </div>
                <div className="flex items-center justify-end gap-3 pt-2">
                  <button
                    onClick={() => setOverrideDialogOpen(false)}
                    className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                      isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-slate-100'
                    }`}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleOverride}
                    disabled={overriding || !overrideReason.trim()}
                    className="inline-flex items-center gap-1.5 px-5 py-2.5 bg-gradient-to-r from-amber-600 to-amber-500 text-white text-xs font-bold rounded-xl hover:shadow-lg transition-all disabled:opacity-50"
                  >
                    {overriding && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                    Confirm Override
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
