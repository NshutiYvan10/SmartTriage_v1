/* ═══════════════════════════════════════════════════════════════
   DiagnosisPanel — Fast, structured diagnosis entry.

   Replaces the previous free-text ICD-code form. The doctor never types
   an ICD-10 code from memory; they search by condition name and the code
   auto-fills.

   Capabilities:
     - Searchable ICD-10 catalog (backend /api/v1/icd-codes/search)
       seeded with conditions common in the Rwandan ED context (malaria,
       typhoid, sepsis, TB, pneumonia, trauma, eclampsia, etc.)
     - "Common in Rwanda" quick-pick row pre-loaded so the most frequent
       diagnoses are one tap away
     - When a code is selected, description is auto-filled and clinical
       notes from the catalog (e.g. "Confirm with mRDT or thick smear")
       are surfaced as guidance — the doctor can edit before saving
     - Diagnosis type (Provisional / Confirmed / Differential / Working),
       Primary flag, and clinician notes are first-class fields

   Clinical contract:
     - Description is what gets persisted as the canonical diagnosis text;
       the ICD code is reference, not display. Doctor's edits to the
       description override the catalog default — e.g. they may want to
       say "Suspected falciparum malaria, awaiting RDT" rather than the
       generic catalog text.
     - Differential and Working flags are real types — not all diagnoses
       on a chart are confirmed; the form shouldn't force the doctor to
       commit prematurely.
   ═══════════════════════════════════════════════════════════════ */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Search, X, Stethoscope, ChevronDown, Sparkles, Send, Loader2, CheckCircle2 } from 'lucide-react';
import { icdApi, type IcdCodeResponse } from '@/api/icdCodes';
import type { CreateDiagnosisRequest, DiagnosisType } from '@/api/types';

const DIAGNOSIS_TYPES: DiagnosisType[] = ['PROVISIONAL', 'CONFIRMED', 'DIFFERENTIAL', 'WORKING'];
const TYPE_DESCRIPTIONS: Record<DiagnosisType, string> = {
  PROVISIONAL: 'Initial impression, awaiting confirmation',
  CONFIRMED: 'Confirmed by investigation or clinical certainty',
  DIFFERENTIAL: 'Considered but not confirmed',
  WORKING: 'Active working diagnosis driving management',
};

interface Props {
  onSubmit: (req: Partial<CreateDiagnosisRequest>) => Promise<void>;
  onClose: () => void;
  formLoading: boolean;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
}

export function DiagnosisPanel({ onSubmit, onClose, formLoading, glassCard, glassInner, isDark, text }: Props) {
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
  // ── Search state ──
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<IcdCodeResponse[]>([]);
  const [searching, setSearching] = useState(false);
  const [common, setCommon] = useState<IcdCodeResponse[]>([]);
  const [showCommon, setShowCommon] = useState(false);
  const [selected, setSelected] = useState<IcdCodeResponse | null>(null);
  const searchSeq = useRef(0);

  // ── Form fields ──
  const [diagnosisType, setDiagnosisType] = useState<DiagnosisType>('PROVISIONAL');
  const [description, setDescription] = useState('');
  const [isPrimary, setIsPrimary] = useState(false);
  const [notes, setNotes] = useState('');

  // Load common Rwandan diagnoses once for the quick-pick chips.
  useEffect(() => {
    let cancelled = false;
    icdApi
      .getCommon()
      .then((rows) => { if (!cancelled) setCommon(Array.isArray(rows) ? rows : []); })
      .catch(() => { /* non-fatal — search still works */ });
    return () => { cancelled = true; };
  }, []);

  // Debounced search.
  useEffect(() => {
    if (selected) return;
    const trimmed = query.trim();
    if (trimmed.length < 2) {
      setResults([]);
      setSearching(false);
      return;
    }
    const seq = ++searchSeq.current;
    setSearching(true);
    const timer = setTimeout(() => {
      icdApi
        .search(trimmed)
        .then((rows) => {
          if (seq !== searchSeq.current) return;
          setResults(Array.isArray(rows) ? rows : []);
        })
        .catch(() => {
          if (seq !== searchSeq.current) return;
          setResults([]);
        })
        .finally(() => {
          if (seq === searchSeq.current) setSearching(false);
        });
    }, 250);
    return () => clearTimeout(timer);
  }, [query, selected]);

  const handleSelect = useCallback((entry: IcdCodeResponse) => {
    setSelected(entry);
    setQuery(`${entry.code} — ${entry.description}`);
    // Pre-fill description from the catalog. Doctor can edit before saving
    // (e.g. add "suspected", "post-treatment", or qualifying detail).
    setDescription(entry.description);
    // Surface clinical notes from the catalog as a starting point for
    // the doctor's notes; they remain editable.
    if (entry.clinicalNotes && !notes.trim()) {
      setNotes(`Catalog guidance: ${entry.clinicalNotes}`);
    }
    setShowCommon(false);
  }, [notes]);

  const handleClear = useCallback(() => {
    setSelected(null);
    setQuery('');
    setResults([]);
    setDescription('');
  }, []);

  const canSubmit = !!description.trim() && !formLoading;

  const handleSubmit = useCallback(async () => {
    if (!canSubmit) return;
    await onSubmit({
      diagnosisType,
      description: description.trim(),
      icdCode: selected?.code || undefined,
      isPrimary,
      notes: notes.trim() || undefined,
    });
  }, [canSubmit, diagnosisType, description, selected, isPrimary, notes, onSubmit]);

  return (
    <div className="rounded-2xl p-5 animate-fade-up space-y-4" style={glassCard}>
      <div className="flex items-center justify-between">
        <h4 className={`text-sm font-bold flex items-center gap-2 ${text.heading}`}>
          <Stethoscope className="w-4 h-4 text-cyan-500" />
          New Diagnosis
        </h4>
        <div className="flex items-center gap-2">
          {common.length > 0 && (
            <button
              type="button"
              onClick={() => setShowCommon((v) => !v)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-lg bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 hover:bg-emerald-500/30 transition-colors"
            >
              <Sparkles className="w-3.5 h-3.5" /> Common in Rwanda
              <ChevronDown className={`w-3 h-3 transition-transform ${showCommon ? 'rotate-180' : ''}`} />
            </button>
          )}
          <button type="button" onClick={onClose} className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5`} aria-label="Close">
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Common-in-Rwanda quick-pick — collapsible. */}
      {showCommon && common.length > 0 && (
        <div className="rounded-xl border border-emerald-500/20 bg-emerald-500/5 max-h-56 overflow-y-auto">
          {common.map((c) => (
            <button
              key={c.id}
              type="button"
              onClick={() => handleSelect(c)}
              className="w-full text-left px-3 py-2 hover:bg-emerald-500/10 transition-colors border-b border-emerald-500/10 last:border-0"
            >
              <div className="flex items-center justify-between">
                <span className={`text-xs font-bold ${text.heading}`}>{c.description}</span>
                <span className={`text-[10px] font-mono ${text.muted}`}>{c.code}</span>
              </div>
              {c.category && <p className={`text-[10px] ${text.muted}`}>{c.category}</p>}
            </button>
          ))}
        </div>
      )}

      {/* ICD search */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Diagnosis (ICD-10)</label>
        {selected ? (
          <div className="rounded-xl border border-cyan-500/30 bg-cyan-500/5 p-3 flex items-start gap-3">
            <CheckCircle2 className="w-4 h-4 text-cyan-500 mt-0.5 flex-shrink-0" />
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`text-sm font-bold ${text.heading}`}>{selected.description}</span>
                <span className={`inline-flex items-center text-[10px] font-mono px-2.5 py-0.5 rounded-lg ${text.muted}`} style={{ background: 'rgba(100,116,139,0.08)', border: '1px solid rgba(100,116,139,0.2)' }}>{selected.code}</span>
                {selected.isCommonInRwanda && (
                  <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-emerald-600" style={{ background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }}>Common in Rwanda</span>
                )}
              </div>
              {selected.category && <p className={`text-[11px] mt-0.5 ${text.muted}`}>{selected.category}</p>}
            </div>
            <button type="button" onClick={handleClear} className={`p-1 rounded ${text.muted} hover:bg-white/5`} aria-label="Clear">
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        ) : (
          <div className="relative">
            <div className="relative">
              <Search className="w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 text-slate-400" />
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search by condition name (e.g. malaria, sepsis, pneumonia)…"
                autoFocus
                className={`w-full pl-9 pr-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner}
              />
            </div>
            {(searching || results.length > 0) && (
              <div className="absolute z-10 mt-1 w-full rounded-xl max-h-72 overflow-y-auto" style={{ ...glassCard, border: borderStyle }}>
                {searching && results.length === 0 && (
                  <p className={`text-xs text-center py-3 ${text.muted}`}>Searching…</p>
                )}
                {!searching && results.length === 0 && query.trim().length >= 2 && (
                  <p className={`text-xs text-center py-3 ${text.muted}`}>
                    No catalog match. You can still save with description only — ICD code will be left blank.
                  </p>
                )}
                {results.map((r) => (
                  <button
                    key={r.id}
                    type="button"
                    onClick={() => handleSelect(r)}
                    className="w-full text-left px-3 py-2 hover:bg-cyan-500/10 transition-colors border-b border-cyan-500/10 last:border-0"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className={`text-sm font-bold ${text.heading}`}>{r.description}</span>
                      <span className={`text-[10px] font-mono ${text.muted}`}>{r.code}</span>
                    </div>
                    <div className="flex items-center gap-2 mt-0.5">
                      {r.category && <span className={`text-[10px] ${text.muted}`}>{r.category}</span>}
                      {r.isCommonInRwanda && (
                        <span className="inline-flex items-center px-2.5 py-0.5 text-[9px] font-bold rounded-lg uppercase tracking-wider text-emerald-600" style={{ background: 'rgba(16,185,129,0.08)', border: '1px solid rgba(16,185,129,0.2)' }}>Rwanda common</span>
                      )}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Description — pre-filled from catalog, fully editable */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Description</label>
        <input
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="Diagnosis text (auto-filled from catalog; edit if needed)"
          className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
          style={glassInner}
        />
      </div>

      {/* Type + Primary flag */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        <div>
          <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Type</label>
          <select
            value={diagnosisType}
            onChange={(e) => setDiagnosisType(e.target.value as DiagnosisType)}
            className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`}
            style={glassInner}
          >
            {DIAGNOSIS_TYPES.map((t) => (
              <option key={t} value={t}>{t}</option>
            ))}
          </select>
          <p className={`text-[10px] mt-1 ${text.muted}`}>{TYPE_DESCRIPTIONS[diagnosisType]}</p>
        </div>
        <div className="flex items-center gap-2 pt-7">
          <input
            id="diag-primary"
            type="checkbox"
            checked={isPrimary}
            onChange={(e) => setIsPrimary(e.target.checked)}
            className="rounded"
          />
          <label htmlFor="diag-primary" className={`text-xs font-medium ${text.body}`}>
            Primary diagnosis (the main reason for this visit)
          </label>
        </div>
      </div>

      {/* Notes */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Notes (optional)</label>
        <textarea
          value={notes}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="Clinical reasoning, qualifying detail, plan…"
          rows={3}
          className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
          style={glassInner}
        />
      </div>

      {/* Action row */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!canSubmit}
          className="inline-flex items-center gap-2 px-5 py-2.5 bg-cyan-600 hover:bg-cyan-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
          Save Diagnosis
        </button>
        <button type="button" onClick={onClose} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
        {selected && (
          <span className={`ml-auto inline-flex items-center gap-1 text-[10px] ${text.muted}`}>
            <Sparkles className="w-3 h-3 text-cyan-500" />
            ICD-10: {selected.code}
          </span>
        )}
      </div>
    </div>
  );
}
