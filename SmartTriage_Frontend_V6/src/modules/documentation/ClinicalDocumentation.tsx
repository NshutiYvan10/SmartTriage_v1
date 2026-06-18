/* ═══════════════════════════════════════════════════════════════
   Module 12 — Clinical Documentation Management
   Per-visit document creation, signing, co-signing, amending
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useCallback } from 'react';
import {
  FileText, Search, Plus, CheckCircle, Clock, PenTool, Shield,
  ChevronDown, ChevronUp, Loader2, RefreshCw, X, AlertTriangle,
  FileSignature, ClipboardList, FilePlus2, Eye,
} from 'lucide-react';
import { useAuthStore } from '@/store/authStore';
import { documentationApi } from '@/api/documentation';
import type { ClinicalDocument, CreateDocumentRequest } from '@/api/documentation';
import { format } from 'date-fns';
import { useTheme } from '@/hooks/useTheme';

// ── Document type config (must match backend ClinicalDocumentType enum) ──
const DOC_TYPES = [
  'ALL',
  'INITIAL_ASSESSMENT',
  'PROGRESS_NOTE',
  'PROCEDURE_NOTE',
  'OPERATIVE_NOTE',
  'CONSULTATION_NOTE',
  'NURSING_ASSESSMENT',
  'TRIAGE_NARRATIVE',
  'DISCHARGE_SUMMARY',
  'TRANSFER_SUMMARY',
  'HANDOVER_DOCUMENT',
  'INFORMED_CONSENT',
  'DEATH_CERTIFICATE',
  'AGAINST_MEDICAL_ADVICE',
] as const;

const DOC_TYPE_LABELS: Record<string, string> = {
  ALL: 'All',
  INITIAL_ASSESSMENT: 'Initial Assessment',
  PROGRESS_NOTE: 'Progress Note',
  PROCEDURE_NOTE: 'Procedure Note',
  OPERATIVE_NOTE: 'Operative Note',
  CONSULTATION_NOTE: 'Consultation Note',
  NURSING_ASSESSMENT: 'Nursing Assessment',
  TRIAGE_NARRATIVE: 'Triage Narrative',
  DISCHARGE_SUMMARY: 'Discharge Summary',
  TRANSFER_SUMMARY: 'Transfer Summary',
  HANDOVER_DOCUMENT: 'Handover',
  INFORMED_CONSENT: 'Informed Consent',
  DEATH_CERTIFICATE: 'Death Certificate',
  AGAINST_MEDICAL_ADVICE: 'Against Medical Advice',
};

const DOC_TYPE_COLORS: Record<string, { bg: string; text: string; border: string }> = {
  INITIAL_ASSESSMENT:     { bg: 'rgba(139,92,246,0.08)', text: 'text-violet-600',  border: '1px solid rgba(139,92,246,0.2)' },
  PROGRESS_NOTE:          { bg: 'rgba(59,130,246,0.08)', text: 'text-blue-600',    border: '1px solid rgba(59,130,246,0.2)' },
  PROCEDURE_NOTE:         { bg: 'rgba(245,158,11,0.08)', text: 'text-amber-600',   border: '1px solid rgba(245,158,11,0.2)' },
  OPERATIVE_NOTE:         { bg: 'rgba(234,88,12,0.08)',  text: 'text-orange-600',  border: '1px solid rgba(234,88,12,0.2)' },
  CONSULTATION_NOTE:      { bg: 'rgba(14,165,233,0.08)', text: 'text-sky-600',     border: '1px solid rgba(14,165,233,0.2)' },
  NURSING_ASSESSMENT:     { bg: 'rgba(16,185,129,0.08)', text: 'text-emerald-600', border: '1px solid rgba(16,185,129,0.2)' },
  TRIAGE_NARRATIVE:       { bg: 'rgba(139,92,246,0.08)', text: 'text-violet-600',  border: '1px solid rgba(139,92,246,0.2)' },
  DISCHARGE_SUMMARY:      { bg: 'rgba(34,197,94,0.08)',  text: 'text-emerald-600', border: '1px solid rgba(34,197,94,0.2)' },
  TRANSFER_SUMMARY:       { bg: 'rgba(6,182,212,0.08)',  text: 'text-cyan-600',    border: '1px solid rgba(6,182,212,0.2)' },
  HANDOVER_DOCUMENT:      { bg: 'rgba(6,182,212,0.08)',  text: 'text-cyan-600',    border: '1px solid rgba(6,182,212,0.2)' },
  INFORMED_CONSENT:       { bg: 'rgba(100,116,139,0.08)', text: 'text-slate-600',  border: '1px solid rgba(100,116,139,0.2)' },
  DEATH_CERTIFICATE:      { bg: 'rgba(100,116,139,0.08)', text: 'text-slate-600',  border: '1px solid rgba(100,116,139,0.2)' },
  AGAINST_MEDICAL_ADVICE: { bg: 'rgba(244,63,94,0.08)',  text: 'text-rose-600',    border: '1px solid rgba(244,63,94,0.2)' },
};

function getDocTypeStyle(type: string) {
  return DOC_TYPE_COLORS[type] || { bg: 'rgba(148,163,184,0.1)', text: 'text-slate-500', border: '1px solid rgba(148,163,184,0.2)' };
}

type ActiveTab = typeof DOC_TYPES[number];

export function ClinicalDocumentation() {
  const { glassCard, glassInner, isDark, text } = useTheme();
  const user = useAuthStore((s) => s.user);
  const hospitalId = user?.hospitalId || '';

  // ── State ──
  const [documents, setDocuments] = useState<ClinicalDocument[]>([]);
  const [loading, setLoading] = useState(false);
  const [visitIdInput, setVisitIdInput] = useState('');
  const [activeVisitId, setActiveVisitId] = useState('');
  const [activeTab, setActiveTab] = useState<ActiveTab>('ALL');
  const [searchQuery, setSearchQuery] = useState('');
  const [expandedDocId, setExpandedDocId] = useState<string | null>(null);
  const [totalElements, setTotalElements] = useState(0);
  const [page, setPage] = useState(0);

  // ── Create form state ──
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [createForm, setCreateForm] = useState<Partial<CreateDocumentRequest>>({
    documentType: 'PROGRESS_NOTE',
    title: '',
    content: '',
  });
  const [creating, setCreating] = useState(false);

  // ── Sign dialog state ──
  const [signDialogOpen, setSignDialogOpen] = useState(false);
  const [signDialogMode, setSignDialogMode] = useState<'sign' | 'cosign' | 'amend'>('sign');
  const [signDocId, setSignDocId] = useState<string | null>(null);
  // Sign / co-sign carry no identity input — the signer is the authenticated user.
  // Only amend needs free-text fields (reason + updated content).
  const [signForm, setSignForm] = useState({ content: '', amendmentReason: '' });
  const [signing, setSigning] = useState(false);

  // ── Generating state ──
  const [generating, setGenerating] = useState<string | null>(null);

  // ── Data loading ──
  const loadDocuments = useCallback(async () => {
    if (!activeVisitId) return;
    setLoading(true);
    try {
      const result = await documentationApi.getForVisit(activeVisitId, page);
      setDocuments(result.content || []);
      setTotalElements(result.totalElements || 0);
    } catch (err) {
      console.error('[ClinicalDocumentation] Failed to load documents:', err);
      setDocuments([]);
    } finally {
      setLoading(false);
    }
  }, [activeVisitId, page]);

  useEffect(() => { loadDocuments(); }, [loadDocuments]);

  // ── Search visit ──
  const handleSearchVisit = useCallback(() => {
    if (!visitIdInput.trim()) return;
    setActiveVisitId(visitIdInput.trim());
    setPage(0);
    setExpandedDocId(null);
  }, [visitIdInput]);

  // ── Filtering ──
  const filteredDocs = documents
    .filter((d) => activeTab === 'ALL' || d.documentType === activeTab)
    .filter((d) => {
      if (!searchQuery.trim()) return true;
      const q = searchQuery.toLowerCase();
      return (
        d.title?.toLowerCase().includes(q) ||
        d.authorName?.toLowerCase().includes(q) ||
        d.documentType?.toLowerCase().includes(q) ||
        d.content?.toLowerCase().includes(q)
      );
    })
    .sort((a, b) => new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime());

  // ── Stats ──
  const stats = {
    total: documents.length,
    signed: documents.filter(d => d.isSigned).length,
    pending: documents.filter(d => !d.isSigned).length,
    amended: documents.filter(d => d.isAmendment).length,
  };

  // ── Create document ──
  const handleCreate = useCallback(async () => {
    if (!activeVisitId || !createForm.title || !createForm.content || !createForm.documentType) return;
    setCreating(true);
    try {
      // No author fields are sent — the backend records the authenticated user as author.
      const isProcedure = createForm.documentType === 'PROCEDURE_NOTE' || createForm.documentType === 'OPERATIVE_NOTE';
      const isDeath = createForm.documentType === 'DEATH_CERTIFICATE';
      await documentationApi.create({
        visitId: activeVisitId,
        documentType: createForm.documentType,
        title: createForm.title,
        content: createForm.content,
        ...(isProcedure ? {
          procedurePerformed: createForm.procedurePerformed,
          procedureIndication: createForm.procedureIndication,
          procedureFindings: createForm.procedureFindings,
          procedureComplications: createForm.procedureComplications,
          procedureOutcome: createForm.procedureOutcome,
          procedurePerformedBy: createForm.procedurePerformedBy,
          anaesthesiaType: createForm.anaesthesiaType,
        } : {}),
        ...(isDeath ? {
          timeOfDeath: createForm.timeOfDeath ? new Date(createForm.timeOfDeath).toISOString() : undefined,
          causeOfDeath: createForm.causeOfDeath,
          antecedentCauses: createForm.antecedentCauses,
          mannerOfDeath: createForm.mannerOfDeath,
        } : {}),
      });
      setShowCreateForm(false);
      setCreateForm({ documentType: 'PROGRESS_NOTE', title: '', content: '' });
      loadDocuments();
    } catch (err) {
      console.error('[ClinicalDocumentation] Create failed:', err);
    } finally {
      setCreating(false);
    }
  }, [activeVisitId, createForm, user, loadDocuments]);

  // ── Sign / Co-sign / Amend ──
  const openSignDialog = (mode: 'sign' | 'cosign' | 'amend', docId: string) => {
    setSignDialogMode(mode);
    setSignDocId(docId);
    setSignForm({ content: '', amendmentReason: '' });
    setSignDialogOpen(true);
  };

  const handleSignSubmit = useCallback(async () => {
    if (!signDocId) return;
    setSigning(true);
    try {
      if (signDialogMode === 'sign') {
        // Signer = authenticated user; no name/license is sent.
        await documentationApi.sign(signDocId);
      } else if (signDialogMode === 'cosign') {
        await documentationApi.coSign(signDocId);
      } else if (signDialogMode === 'amend') {
        await documentationApi.amend(signDocId, { content: signForm.content, amendmentReason: signForm.amendmentReason });
      }
      setSignDialogOpen(false);
      loadDocuments();
    } catch (err) {
      console.error('[ClinicalDocumentation] Sign action failed:', err);
    } finally {
      setSigning(false);
    }
  }, [signDocId, signDialogMode, signForm, loadDocuments]);

  // ── Generate actions ──
  const handleGenerateDischarge = useCallback(async () => {
    if (!activeVisitId) return;
    setGenerating('discharge');
    try {
      await documentationApi.generateDischargeSummary(activeVisitId);
      loadDocuments();
    } catch (err) {
      console.error('[ClinicalDocumentation] Generate discharge summary failed:', err);
    } finally {
      setGenerating(null);
    }
  }, [activeVisitId, loadDocuments]);

  const handleGenerateHandover = useCallback(async () => {
    if (!activeVisitId) return;
    setGenerating('handover');
    try {
      await documentationApi.generateHandover(activeVisitId);
      loadDocuments();
    } catch (err) {
      console.error('[ClinicalDocumentation] Generate handover failed:', err);
    } finally {
      setGenerating(null);
    }
  }, [activeVisitId, loadDocuments]);

  return (
    <div className="min-h-full">
      <div className="p-4 lg:p-6 max-w-6xl mx-auto space-y-4 animate-fade-in">

        {/* ── Header Banner ── */}
        <div className="rounded-3xl overflow-hidden animate-fade-up" style={glassCard}>
          <div className="bg-gradient-to-r from-slate-800 to-slate-700 px-6 py-5">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-4">
                <div className="w-12 h-12 bg-white/20 rounded-2xl flex items-center justify-center shadow-lg">
                  <FileText className="w-6 h-6 text-white" />
                </div>
                <div>
                  <h1 className="text-lg font-bold text-white tracking-wide">Clinical Documentation</h1>
                  <p className="text-white/70 text-xs font-medium">Create, sign, and manage clinical documents per visit</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                {activeVisitId && (
                  <div className="bg-white/15 backdrop-blur rounded-xl px-3 py-1.5 flex items-center gap-2">
                    <ClipboardList className="w-3.5 h-3.5 text-white/80" />
                    <span className="text-xs font-semibold text-white/90">Visit: {activeVisitId.slice(0, 8)}...</span>
                  </div>
                )}
                {activeVisitId && (
                  <button
                    onClick={loadDocuments}
                    className="w-9 h-9 rounded-xl bg-white/10 flex items-center justify-center hover:bg-white/20 transition-colors"
                  >
                    <RefreshCw className={`w-4 h-4 text-white ${loading ? 'animate-spin' : ''}`} />
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>

        {/* ── Visit ID Search ── */}
        <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
          <div className="flex flex-col sm:flex-row sm:items-center gap-3">
            <div className="relative flex-1">
              <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
              <input
                type="text"
                value={visitIdInput}
                onChange={(e) => setVisitIdInput(e.target.value)}
                onKeyDown={(e) => e.key === 'Enter' && handleSearchVisit()}
                placeholder="Enter Visit ID to load documents..."
                className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all ${
                  isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                }`}
                style={glassInner}
              />
            </div>
            <button
              onClick={handleSearchVisit}
              className="px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white text-xs font-bold rounded-xl hover:shadow-lg transition-all"
            >
              Load Documents
            </button>
          </div>
        </div>

        {/* ── Stats ── */}
        {activeVisitId && (
          <div className="grid grid-cols-4 gap-3 animate-fade-up">
            {[
              { label: 'Total', value: stats.total, icon: FileText, color: 'text-cyan-500', bg: 'rgba(6,182,212,0.1)' },
              { label: 'Signed', value: stats.signed, icon: CheckCircle, color: 'text-emerald-500', bg: 'rgba(34,197,94,0.1)' },
              { label: 'Pending', value: stats.pending, icon: Clock, color: 'text-amber-500', bg: 'rgba(245,158,11,0.1)' },
              { label: 'Amended', value: stats.amended, icon: PenTool, color: 'text-blue-500', bg: 'rgba(59,130,246,0.1)' },
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

        {/* ── Actions Row ── */}
        {activeVisitId && (
          <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
            <div className="flex flex-col sm:flex-row sm:items-center gap-3">
              {/* Text search within documents */}
              <div className="relative flex-1">
                <Search className="absolute left-3.5 top-1/2 -translate-y-1/2 w-4 h-4 text-slate-400" />
                <input
                  type="text"
                  value={searchQuery}
                  onChange={(e) => setSearchQuery(e.target.value)}
                  placeholder="Filter documents by title, author, content..."
                  className={`w-full pl-10 pr-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all ${
                    isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                  }`}
                  style={glassInner}
                />
              </div>
              <div className="flex items-center gap-2">
                <button
                  onClick={() => setShowCreateForm(!showCreateForm)}
                  className="inline-flex items-center gap-1.5 px-4 py-2.5 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl hover:shadow-lg transition-all"
                >
                  <Plus className="w-3.5 h-3.5" />
                  New Document
                </button>
                <button
                  onClick={handleGenerateDischarge}
                  disabled={generating === 'discharge'}
                  className={`inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                    isDark ? 'bg-emerald-500/15 text-emerald-400 hover:bg-emerald-500/25' : 'bg-emerald-50 text-emerald-700 hover:bg-emerald-100'
                  }`}
                >
                  {generating === 'discharge' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <FilePlus2 className="w-3.5 h-3.5" />}
                  Discharge Summary
                </button>
                <button
                  onClick={handleGenerateHandover}
                  disabled={generating === 'handover'}
                  className={`inline-flex items-center gap-1.5 px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                    isDark ? 'bg-indigo-500/15 text-indigo-400 hover:bg-indigo-500/25' : 'bg-indigo-50 text-indigo-700 hover:bg-indigo-100'
                  }`}
                >
                  {generating === 'handover' ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <FileSignature className="w-3.5 h-3.5" />}
                  Handover
                </button>
              </div>
            </div>
          </div>
        )}

        {/* ── Create Document Form ── */}
        {showCreateForm && activeVisitId && (
          <div className="rounded-2xl overflow-hidden animate-fade-up" style={glassCard}>
            <div className="px-5 py-4 border-b border-white/10">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2.5">
                  <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(6,182,212,0.12)' }}>
                    <FilePlus2 className={`w-4 h-4 ${isDark ? 'text-cyan-400' : 'text-cyan-600'}`} />
                  </div>
                  <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Create New Document</h3>
                </div>
                <button onClick={() => setShowCreateForm(false)} className={`p-1.5 rounded-lg hover:bg-white/10 transition-colors ${text.muted}`}>
                  <X className="w-4 h-4" />
                </button>
              </div>
            </div>
            <div className="p-5 space-y-4">
              {/* Document type */}
              <div>
                <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Document Type</label>
                <select
                  value={createForm.documentType || 'PROGRESS_NOTE'}
                  onChange={(e) => setCreateForm({ ...createForm, documentType: e.target.value })}
                  className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all ${
                    isDark ? 'text-white' : 'text-slate-800'
                  }`}
                  style={glassInner}
                >
                  {DOC_TYPES.filter(t => t !== 'ALL').map((t) => (
                    <option key={t} value={t}>{DOC_TYPE_LABELS[t]}</option>
                  ))}
                </select>
              </div>
              {/* Title */}
              <div>
                <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Title</label>
                <input
                  type="text"
                  value={createForm.title || ''}
                  onChange={(e) => setCreateForm({ ...createForm, title: e.target.value })}
                  placeholder="Document title..."
                  className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all ${
                    isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                  }`}
                  style={glassInner}
                />
              </div>
              {/* Content */}
              <div>
                <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Content</label>
                <textarea
                  value={createForm.content || ''}
                  onChange={(e) => setCreateForm({ ...createForm, content: e.target.value })}
                  placeholder="Enter clinical documentation content..."
                  rows={8}
                  className={`w-full px-4 py-3 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 transition-all resize-y ${
                    isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                  }`}
                  style={glassInner}
                />
              </div>

              {/* Type-specific structured fields — procedure / operative note */}
              {(createForm.documentType === 'PROCEDURE_NOTE' || createForm.documentType === 'OPERATIVE_NOTE') && (
                <div className="space-y-2.5 rounded-xl p-4" style={glassInner}>
                  <p className={`text-[11px] font-bold uppercase tracking-wider ${text.label}`}>Procedure details</p>
                  <input value={createForm.procedurePerformed || ''} onChange={(e) => setCreateForm({ ...createForm, procedurePerformed: e.target.value })} placeholder="Procedure performed" className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
                  <input value={createForm.procedurePerformedBy || ''} onChange={(e) => setCreateForm({ ...createForm, procedurePerformedBy: e.target.value })} placeholder="Performed by (operator / team)" className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
                  <input value={createForm.anaesthesiaType || ''} onChange={(e) => setCreateForm({ ...createForm, anaesthesiaType: e.target.value })} placeholder="Anaesthesia type (if any)" className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
                  <textarea value={createForm.procedureIndication || ''} onChange={(e) => setCreateForm({ ...createForm, procedureIndication: e.target.value })} placeholder="Indication" rows={2} className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
                  <textarea value={createForm.procedureFindings || ''} onChange={(e) => setCreateForm({ ...createForm, procedureFindings: e.target.value })} placeholder="Findings" rows={2} className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
                  <textarea value={createForm.procedureComplications || ''} onChange={(e) => setCreateForm({ ...createForm, procedureComplications: e.target.value })} placeholder="Complications" rows={2} className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
                  <textarea value={createForm.procedureOutcome || ''} onChange={(e) => setCreateForm({ ...createForm, procedureOutcome: e.target.value })} placeholder="Outcome" rows={2} className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
                </div>
              )}

              {/* Type-specific structured fields — death certificate */}
              {createForm.documentType === 'DEATH_CERTIFICATE' && (
                <div className="space-y-2.5 rounded-xl p-4" style={glassInner}>
                  <p className={`text-[11px] font-bold uppercase tracking-wider ${text.label}`}>Death certificate details</p>
                  <label className={`text-[10px] font-bold uppercase ${text.muted}`}>Time of death</label>
                  <input type="datetime-local" value={createForm.timeOfDeath || ''} onChange={(e) => setCreateForm({ ...createForm, timeOfDeath: e.target.value })} className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner} />
                  <textarea value={createForm.causeOfDeath || ''} onChange={(e) => setCreateForm({ ...createForm, causeOfDeath: e.target.value })} placeholder="Immediate cause of death" rows={2} className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
                  <textarea value={createForm.antecedentCauses || ''} onChange={(e) => setCreateForm({ ...createForm, antecedentCauses: e.target.value })} placeholder="Antecedent / underlying causes" rows={2} className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
                  <input value={createForm.mannerOfDeath || ''} onChange={(e) => setCreateForm({ ...createForm, mannerOfDeath: e.target.value })} placeholder="Manner of death (e.g. Natural, Accident)" className={`w-full px-3 py-2 rounded-lg text-sm ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800'}`} style={glassInner} />
                </div>
              )}

              {/* Submit */}
              <div className="flex items-center justify-end gap-3 pt-2">
                <button
                  onClick={() => setShowCreateForm(false)}
                  className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                    isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-slate-100'
                  }`}
                >
                  Cancel
                </button>
                <button
                  onClick={handleCreate}
                  disabled={creating || !createForm.title || !createForm.content}
                  className="inline-flex items-center gap-1.5 px-5 py-2.5 bg-gradient-to-r from-cyan-600 to-cyan-500 text-white text-xs font-bold rounded-xl hover:shadow-lg transition-all disabled:opacity-50"
                >
                  {creating ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Plus className="w-3.5 h-3.5" />}
                  Create Document
                </button>
              </div>
            </div>
          </div>
        )}

        {/* ── Document Type Tabs ── */}
        {activeVisitId && (
          <div className="rounded-2xl p-4 animate-fade-up" style={glassCard}>
            <div className="flex items-center gap-2 flex-wrap">
              {DOC_TYPES.map((t) => (
                <button
                  key={t}
                  onClick={() => setActiveTab(t)}
                  className={`inline-flex items-center gap-1.5 px-3.5 py-2 text-[11px] font-bold rounded-lg transition-all ${
                    activeTab === t
                      ? 'bg-gradient-to-r from-slate-800 to-slate-700 text-white shadow-md'
                      : isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-white/60'
                  }`}
                >
                  {DOC_TYPE_LABELS[t]}
                  {t !== 'ALL' && (
                    <span className={`ml-1 text-[9px] px-1.5 py-0.5 rounded-md ${
                      activeTab === t ? 'bg-white/20' : isDark ? 'bg-white/5' : 'bg-slate-200/60'
                    }`}>
                      {documents.filter(d => d.documentType === t).length}
                    </span>
                  )}
                </button>
              ))}
            </div>
          </div>
        )}

        {/* ── List Header ── */}
        {activeVisitId && (
          <div className="flex items-center justify-between px-1 animate-fade-up">
            <div className="flex items-center gap-2.5">
              <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ backgroundColor: 'rgba(99,102,241,0.12)' }}>
                <ClipboardList className={`w-4 h-4 ${isDark ? 'text-indigo-400' : 'text-indigo-500'}`} />
              </div>
              <div>
                <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>Documents</h3>
                <p className={`text-[11px] font-medium ${text.muted}`}>
                  {filteredDocs.length} document{filteredDocs.length !== 1 ? 's' : ''} found
                  {totalElements > 20 && ` (page ${page + 1} of ${Math.ceil(totalElements / 20)})`}
                </p>
              </div>
            </div>
          </div>
        )}

        {/* ── Document List ── */}
        {!activeVisitId ? (
          <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
            <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(6,182,212,0.1)' }}>
              <Search className="w-8 h-8 text-cyan-400" />
            </div>
            <p className={`text-sm font-bold ${text.heading}`}>Enter a Visit ID</p>
            <p className={`text-xs font-medium mt-1 ${text.muted}`}>
              Search for a visit to view and manage clinical documents
            </p>
          </div>
        ) : loading ? (
          <div className="flex items-center justify-center py-16">
            <Loader2 className="w-7 h-7 animate-spin text-cyan-500" />
          </div>
        ) : filteredDocs.length === 0 ? (
          <div className="rounded-2xl p-12 text-center animate-fade-up" style={glassCard}>
            <div className="w-16 h-16 rounded-2xl flex items-center justify-center mx-auto mb-4" style={{ backgroundColor: 'rgba(148,163,184,0.1)' }}>
              <FileText className="w-8 h-8 text-slate-400" />
            </div>
            <p className={`text-sm font-bold ${text.heading}`}>No Documents Found</p>
            <p className={`text-xs font-medium mt-1 ${text.muted}`}>
              {documents.length === 0 ? 'No documents exist for this visit yet' : 'No documents match the current filter'}
            </p>
          </div>
        ) : (
          <div className="space-y-3">
            {filteredDocs.map((doc, idx) => {
              const typeStyle = getDocTypeStyle(doc.documentType);
              const isExpanded = expandedDocId === doc.id;

              return (
                <div
                  key={doc.id}
                  className="rounded-2xl overflow-hidden transition-all animate-fade-up hover:-translate-y-0.5"
                  style={{
                    ...glassCard,
                    animationDelay: `${0.05 + idx * 0.04}s`,
                  } as React.CSSProperties}
                >
                  {/* Card header */}
                  <button
                    onClick={() => setExpandedDocId(isExpanded ? null : doc.id)}
                    className="w-full text-left px-5 py-4"
                  >
                    <div className="flex items-start gap-4">
                      {/* Type icon */}
                      <div className="w-11 h-11 rounded-xl flex items-center justify-center flex-shrink-0" style={{ backgroundColor: typeStyle.bg }}>
                        <FileText className={`w-5 h-5 ${typeStyle.text}`} />
                      </div>

                      {/* Content */}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2.5 mb-1.5 flex-wrap">
                          {/* Type badge */}
                          <span
                            className={`inline-flex items-center px-2.5 py-1 text-[10px] font-bold rounded-lg uppercase tracking-wider ${typeStyle.text}`}
                            style={{ background: typeStyle.bg, border: typeStyle.border }}
                          >
                            {DOC_TYPE_LABELS[doc.documentType] || doc.documentType.replace(/_/g, ' ')}
                          </span>
                          {/* Signature status */}
                          {doc.isSigned ? (
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg text-emerald-600"
                              style={{ background: 'rgba(34,197,94,0.08)', border: '1px solid rgba(34,197,94,0.2)' }}
                            >
                              <CheckCircle className="w-3 h-3" /> Signed
                            </span>
                          ) : doc.isAmendment ? (
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg text-blue-600"
                              style={{ background: 'rgba(59,130,246,0.08)', border: '1px solid rgba(59,130,246,0.2)' }}
                            >
                              <PenTool className="w-3 h-3" /> Amended
                            </span>
                          ) : (
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg text-amber-600"
                              style={{ background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.2)' }}
                            >
                              <Clock className="w-3 h-3" /> Pending Signature
                            </span>
                          )}
                          {doc.coSignedByName && (
                            <span className="inline-flex items-center gap-1 px-2.5 py-1 text-[10px] font-bold rounded-lg text-violet-600"
                              style={{ background: 'rgba(139,92,246,0.08)', border: '1px solid rgba(139,92,246,0.2)' }}
                            >
                              <Shield className="w-3 h-3" /> Co-signed
                            </span>
                          )}
                          {/* Date */}
                          <span className={`ml-auto text-[10px] font-medium flex items-center gap-1 ${text.muted}`}>
                            <Clock className="w-3 h-3" />
                            {doc.createdAt ? format(new Date(doc.createdAt), 'MMM d, yyyy HH:mm') : '--'}
                          </span>
                        </div>
                        {/* Title */}
                        <p className={`text-sm font-bold leading-snug ${text.heading}`}>{doc.title}</p>
                        {/* Author */}
                        <p className={`text-[11px] font-medium mt-0.5 ${text.muted}`}>
                          By {doc.authorName}{doc.authorRole ? ` (${doc.authorRole})` : ''}
                          {doc.signedAt && ` -- Signed ${format(new Date(doc.signedAt), 'MMM d, yyyy HH:mm')}`}
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
                      {/* Document content */}
                      <div className="mt-4 rounded-xl p-4" style={glassInner}>
                        <label className={`text-[10px] font-bold uppercase tracking-wider block mb-2 ${text.muted}`}>Document Content</label>
                        <div className={`text-sm leading-relaxed whitespace-pre-wrap ${text.body}`}>
                          {doc.content}
                        </div>
                      </div>

                      {/* Type-specific structured details */}
                      {(doc.procedurePerformed || doc.procedureFindings || doc.procedureOutcome
                        || doc.causeOfDeath || doc.timeOfDeath) && (
                        <div className="mt-3 rounded-xl p-4 space-y-1" style={glassInner}>
                          <label className={`text-[10px] font-bold uppercase tracking-wider block mb-1 ${text.muted}`}>Structured Details</label>
                          {doc.procedurePerformed && <p className={`text-xs ${text.body}`}><b>Procedure:</b> {doc.procedurePerformed}</p>}
                          {doc.procedurePerformedBy && <p className={`text-xs ${text.body}`}><b>Performed by:</b> {doc.procedurePerformedBy}</p>}
                          {doc.anaesthesiaType && <p className={`text-xs ${text.body}`}><b>Anaesthesia:</b> {doc.anaesthesiaType}</p>}
                          {doc.procedureIndication && <p className={`text-xs ${text.body}`}><b>Indication:</b> {doc.procedureIndication}</p>}
                          {doc.procedureFindings && <p className={`text-xs ${text.body}`}><b>Findings:</b> {doc.procedureFindings}</p>}
                          {doc.procedureComplications && <p className={`text-xs ${text.body}`}><b>Complications:</b> {doc.procedureComplications}</p>}
                          {doc.procedureOutcome && <p className={`text-xs ${text.body}`}><b>Outcome:</b> {doc.procedureOutcome}</p>}
                          {doc.timeOfDeath && <p className={`text-xs ${text.body}`}><b>Time of death:</b> {format(new Date(doc.timeOfDeath), 'MMM d, yyyy HH:mm')}</p>}
                          {doc.causeOfDeath && <p className={`text-xs ${text.body}`}><b>Cause of death:</b> {doc.causeOfDeath}</p>}
                          {doc.antecedentCauses && <p className={`text-xs ${text.body}`}><b>Antecedent causes:</b> {doc.antecedentCauses}</p>}
                          {doc.mannerOfDeath && <p className={`text-xs ${text.body}`}><b>Manner of death:</b> {doc.mannerOfDeath}</p>}
                        </div>
                      )}

                      {/* Meta info */}
                      <div className="mt-3 grid grid-cols-2 lg:grid-cols-4 gap-3">
                        <div className="rounded-xl p-3" style={glassInner}>
                          <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Author</p>
                          <p className={`text-xs font-semibold mt-0.5 ${text.heading}`}>{doc.authorName}</p>
                        </div>
                        <div className="rounded-xl p-3" style={glassInner}>
                          <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>License #</p>
                          <p className={`text-xs font-semibold mt-0.5 ${text.heading}`}>{doc.authorLicenseNumber || '--'}</p>
                        </div>
                        <div className="rounded-xl p-3" style={glassInner}>
                          <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Template</p>
                          <p className={`text-xs font-semibold mt-0.5 ${text.heading}`}>{doc.templateUsed || 'None'}</p>
                        </div>
                        <div className="rounded-xl p-3" style={glassInner}>
                          <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Co-signed By</p>
                          <p className={`text-xs font-semibold mt-0.5 ${text.heading}`}>
                            {doc.coSignedByName || '--'}
                            {doc.coSignedAt && ` (${format(new Date(doc.coSignedAt), 'MMM d')})`}
                          </p>
                        </div>
                      </div>

                      {/* Amendment info */}
                      {doc.isAmendment && (
                        <div className="mt-3 rounded-xl p-3" style={{ ...glassInner, border: '1px solid rgba(59,130,246,0.2)' }}>
                          <div className="flex items-center gap-2 mb-1">
                            <AlertTriangle className="w-3.5 h-3.5 text-blue-500" />
                            <p className={`text-[10px] font-bold uppercase tracking-wider text-blue-500`}>Amendment</p>
                          </div>
                          <p className={`text-xs ${text.body}`}>Reason: {doc.amendmentReason || 'No reason provided'}</p>
                          {doc.originalDocumentId && (
                            <p className={`text-[10px] mt-1 ${text.muted}`}>Original: {doc.originalDocumentId}</p>
                          )}
                        </div>
                      )}

                      {/* Notes */}
                      {doc.notes && (
                        <div className="mt-3 rounded-xl p-3" style={glassInner}>
                          <p className={`text-[10px] font-bold uppercase tracking-wider ${text.muted}`}>Notes</p>
                          <p className={`text-xs mt-0.5 ${text.body}`}>{doc.notes}</p>
                        </div>
                      )}

                      {/* Action buttons */}
                      <div className="mt-4 flex items-center gap-2 flex-wrap">
                        {!doc.isSigned && (
                          <button
                            onClick={() => openSignDialog('sign', doc.id)}
                            className="inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl transition-all bg-gradient-to-r from-emerald-600 to-emerald-500 text-white hover:shadow-lg"
                          >
                            <CheckCircle className="w-3.5 h-3.5" /> Sign Document
                          </button>
                        )}
                        {doc.isSigned && !doc.coSignedByName && (
                          <button
                            onClick={() => openSignDialog('cosign', doc.id)}
                            className={`inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl transition-all ${
                              isDark ? 'bg-violet-500/15 text-violet-400 hover:bg-violet-500/25' : 'bg-violet-50 text-violet-700 hover:bg-violet-100'
                            }`}
                          >
                            <Shield className="w-3.5 h-3.5" /> Co-Sign
                          </button>
                        )}
                        <button
                          onClick={() => openSignDialog('amend', doc.id)}
                          className={`inline-flex items-center gap-1.5 px-4 py-2 text-xs font-bold rounded-xl transition-all ${
                            isDark ? 'bg-blue-500/15 text-blue-400 hover:bg-blue-500/25' : 'bg-blue-50 text-blue-700 hover:bg-blue-100'
                          }`}
                        >
                          <PenTool className="w-3.5 h-3.5" /> Amend
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        )}

        {/* ── Pagination ── */}
        {activeVisitId && totalElements > 20 && (
          <div className="flex items-center justify-center gap-3 pt-2 animate-fade-up">
            <button
              onClick={() => setPage(Math.max(0, page - 1))}
              disabled={page === 0}
              className={`px-4 py-2 text-xs font-bold rounded-xl transition-all disabled:opacity-40 ${
                isDark ? 'text-slate-300 hover:bg-white/5' : 'text-slate-600 hover:bg-slate-100'
              }`}
            >
              Previous
            </button>
            <span className={`text-xs font-semibold ${text.muted}`}>
              Page {page + 1} of {Math.ceil(totalElements / 20)}
            </span>
            <button
              onClick={() => setPage(page + 1)}
              disabled={(page + 1) * 20 >= totalElements}
              className={`px-4 py-2 text-xs font-bold rounded-xl transition-all disabled:opacity-40 ${
                isDark ? 'text-slate-300 hover:bg-white/5' : 'text-slate-600 hover:bg-slate-100'
              }`}
            >
              Next
            </button>
          </div>
        )}

        {/* ── Sign / Co-sign / Amend Dialog ── */}
        {signDialogOpen && (
          <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
            <div className="absolute inset-0 bg-black/40 backdrop-blur-sm" onClick={() => setSignDialogOpen(false)} />
            <div className="relative w-full max-w-lg rounded-2xl overflow-hidden animate-fade-up" style={glassCard}>
              <div className="px-5 py-4 border-b border-white/10">
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2.5">
                    <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{
                      backgroundColor: signDialogMode === 'sign' ? 'rgba(34,197,94,0.12)' : signDialogMode === 'cosign' ? 'rgba(139,92,246,0.12)' : 'rgba(59,130,246,0.12)'
                    }}>
                      {signDialogMode === 'sign' && <CheckCircle className="w-4 h-4 text-emerald-500" />}
                      {signDialogMode === 'cosign' && <Shield className="w-4 h-4 text-violet-500" />}
                      {signDialogMode === 'amend' && <PenTool className="w-4 h-4 text-blue-500" />}
                    </div>
                    <h3 className={`text-sm font-extrabold tracking-tight ${text.heading}`}>
                      {signDialogMode === 'sign' && 'Sign Document'}
                      {signDialogMode === 'cosign' && 'Co-Sign Document'}
                      {signDialogMode === 'amend' && 'Amend Document'}
                    </h3>
                  </div>
                  <button onClick={() => setSignDialogOpen(false)} className={`p-1.5 rounded-lg hover:bg-white/10 transition-colors ${text.muted}`}>
                    <X className="w-4 h-4" />
                  </button>
                </div>
              </div>
              <div className="p-5 space-y-4">
                {(signDialogMode === 'sign' || signDialogMode === 'cosign') && (
                  <div className="rounded-xl p-4" style={glassInner}>
                    <div className="flex items-start gap-3">
                      <div
                        className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0"
                        style={{ backgroundColor: signDialogMode === 'sign' ? 'rgba(34,197,94,0.12)' : 'rgba(139,92,246,0.12)' }}
                      >
                        <Shield className={`w-4 h-4 ${signDialogMode === 'sign' ? 'text-emerald-500' : 'text-violet-500'}`} />
                      </div>
                      <div className="min-w-0">
                        <p className={`text-sm font-bold ${text.heading}`}>
                          {signDialogMode === 'sign' ? 'Sign as' : 'Co-sign as'} {user?.fullName || 'your account'}
                          {user?.role ? <span className={`ml-1 font-medium ${text.muted}`}>({user.role})</span> : null}
                        </p>
                        <p className={`text-[11px] mt-1 leading-relaxed ${text.muted}`}>
                          This electronic signature is bound to your authenticated account and recorded
                          server-side with your name and license. It cannot be entered on behalf of anyone else.
                        </p>
                      </div>
                    </div>
                  </div>
                )}
                {signDialogMode === 'amend' && (
                  <>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Amendment Reason</label>
                      <input
                        type="text"
                        value={signForm.amendmentReason}
                        onChange={(e) => setSignForm({ ...signForm, amendmentReason: e.target.value })}
                        placeholder="Reason for amendment..."
                        className={`w-full px-4 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                    <div>
                      <label className={`text-xs font-bold uppercase tracking-wider mb-1.5 block ${text.label}`}>Updated Content</label>
                      <textarea
                        value={signForm.content}
                        onChange={(e) => setSignForm({ ...signForm, content: e.target.value })}
                        placeholder="Enter amended content..."
                        rows={6}
                        className={`w-full px-4 py-3 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 resize-y ${
                          isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'
                        }`}
                        style={glassInner}
                      />
                    </div>
                  </>
                )}
                <div className="flex items-center justify-end gap-3 pt-2">
                  <button
                    onClick={() => setSignDialogOpen(false)}
                    className={`px-4 py-2.5 text-xs font-bold rounded-xl transition-all ${
                      isDark ? 'text-slate-400 hover:text-white hover:bg-white/5' : 'text-slate-500 hover:text-slate-800 hover:bg-slate-100'
                    }`}
                  >
                    Cancel
                  </button>
                  <button
                    onClick={handleSignSubmit}
                    disabled={signing}
                    className={`inline-flex items-center gap-1.5 px-5 py-2.5 text-xs font-bold rounded-xl hover:shadow-lg transition-all disabled:opacity-50 ${
                      signDialogMode === 'sign'
                        ? 'bg-gradient-to-r from-emerald-600 to-emerald-500 text-white'
                        : signDialogMode === 'cosign'
                        ? 'bg-gradient-to-r from-violet-600 to-violet-500 text-white'
                        : 'bg-gradient-to-r from-blue-600 to-blue-500 text-white'
                    }`}
                  >
                    {signing && <Loader2 className="w-3.5 h-3.5 animate-spin" />}
                    {signDialogMode === 'sign' && 'Sign'}
                    {signDialogMode === 'cosign' && 'Co-Sign'}
                    {signDialogMode === 'amend' && 'Submit Amendment'}
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
