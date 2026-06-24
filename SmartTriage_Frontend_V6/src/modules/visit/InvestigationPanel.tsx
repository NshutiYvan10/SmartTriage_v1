/* ═══════════════════════════════════════════════════════════════
   InvestigationPanel — Fast, structured lab / diagnostic order entry.

   Replaces the previous free-text test-name input. The doctor never
   types a test name from scratch; they search the catalog and pick
   from the matched entries. The catalog is seeded with tests that are
   actually available in Rwandan hospitals (FBC, U&E, malaria RDT,
   HIV, GeneXpert, chest X-ray, FAST scan, ECG, etc.) and the most
   frequently used are pinned to the top of search results.

   Capabilities:
     - Searchable test catalog (LabTestCatalog seed in V24)
     - "Common in Rwanda" quick-pick row of routine ED tests
     - When a test is selected:
         · investigation type pre-fills from the catalog (LABORATORY /
           XRAY / CT_SCAN / ULTRASOUND / ECG / RAPID_TEST / etc.)
         · specimen type, clinical use, and turnaround time are
           surfaced as guidance
     - Urgency: STAT vs ROUTINE (radio chips)
     - Clinical indication (why this is being ordered) — separate from
       notes for the lab tech
     - Free-text lab-tech notes for special handling instructions
       (e.g. "patient on warfarin, please send earlier", "interpret
       Widal cautiously")

   Backend persists clinical indication + lab notes into the existing
   `notes` field, prefixed by labels so the lab tech can read them.
   No backend schema change required.
   ═══════════════════════════════════════════════════════════════ */

import React, { useState, useEffect, useRef, useCallback } from 'react';
import { Search, X, FlaskConical, ChevronDown, Sparkles, Send, Loader2, CheckCircle2, Clock } from 'lucide-react';
import { labCatalogApi, type LabTestCatalogResponse } from '@/api/labCatalog';
import type { OrderInvestigationRequest, InvestigationType } from '@/api/types';

const URGENCIES: Array<{ value: 'STAT' | 'URGENT' | 'ROUTINE'; label: string; helper: string; color: string }> = [
  { value: 'STAT',    label: 'STAT',    helper: 'Immediate — within minutes', color: 'bg-red-500 text-white' },
  { value: 'URGENT',  label: 'URGENT',  helper: 'Within an hour',             color: 'bg-amber-500 text-white' },
  { value: 'ROUTINE', label: 'ROUTINE', helper: 'Standard turnaround',        color: 'bg-emerald-500 text-white' },
];

interface Props {
  onSubmit: (req: Partial<OrderInvestigationRequest>) => Promise<void>;
  onClose: () => void;
  formLoading: boolean;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
}

export function InvestigationPanel({ onSubmit, onClose, formLoading, glassCard, glassInner, isDark, text }: Props) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<LabTestCatalogResponse[]>([]);
  const [searching, setSearching] = useState(false);
  const [common, setCommon] = useState<LabTestCatalogResponse[]>([]);
  const [showCommon, setShowCommon] = useState(false);
  const [selected, setSelected] = useState<LabTestCatalogResponse | null>(null);
  const searchSeq = useRef(0);

  // Form fields
  const [investigationType, setInvestigationType] = useState<InvestigationType>('LABORATORY');
  const [urgency, setUrgency] = useState<'STAT' | 'URGENT' | 'ROUTINE'>('ROUTINE');
  const [clinicalIndication, setClinicalIndication] = useState('');
  const [labNotes, setLabNotes] = useState('');

  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';

  useEffect(() => {
    let cancelled = false;
    labCatalogApi.getCommon()
      .then((rows) => { if (!cancelled) setCommon(Array.isArray(rows) ? rows : []); })
      .catch(() => { /* non-fatal */ });
    return () => { cancelled = true; };
  }, []);

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
      labCatalogApi.search(trimmed)
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

  const handleSelect = useCallback((entry: LabTestCatalogResponse) => {
    setSelected(entry);
    setQuery(entry.testName);
    setInvestigationType(entry.investigationType);
    setShowCommon(false);
  }, []);

  const handleClear = useCallback(() => {
    setSelected(null);
    setQuery('');
    setResults([]);
  }, []);

  const canSubmit = !!query.trim() && !formLoading;

  const handleSubmit = useCallback(async () => {
    if (!canSubmit) return;
    // Combine clinical indication + lab notes into the single `notes` field
    // the backend accepts. Labeled so the lab tech can read both clearly.
    const combinedNotes = [
      clinicalIndication.trim() && `Indication: ${clinicalIndication.trim()}`,
      labNotes.trim() && `Lab notes: ${labNotes.trim()}`,
    ].filter(Boolean).join('\n');

    await onSubmit({
      investigationType,
      testName: selected?.testName ?? query.trim(),
      priority: urgency,
      notes: combinedNotes || undefined,
    });
  }, [canSubmit, investigationType, selected, query, urgency, clinicalIndication, labNotes, onSubmit]);

  // Turnaround hint based on urgency + selected test
  const turnaroundHint = (() => {
    if (!selected) return null;
    if (urgency === 'STAT' && selected.statTurnaroundMinutes != null) {
      return `STAT turnaround ≈ ${selected.statTurnaroundMinutes} min`;
    }
    if (urgency !== 'STAT' && selected.routineTurnaroundMinutes != null) {
      return `Routine turnaround ≈ ${selected.routineTurnaroundMinutes} min`;
    }
    return null;
  })();

  return (
    <div className="rounded-2xl p-5 animate-fade-up space-y-4" style={glassCard}>
      <div className="flex items-center justify-between">
        <h4 className={`text-sm font-bold flex items-center gap-2 ${text.heading}`}>
          <FlaskConical className="w-4 h-4 text-cyan-500" />
          Order Investigation
        </h4>
        <div className="flex items-center gap-2">
          {common.length > 0 && (
            <button
              type="button"
              onClick={() => setShowCommon((v) => !v)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-lg bg-emerald-500/20 text-emerald-300 border border-emerald-500/30 hover:bg-emerald-500/30 transition-colors"
            >
              <Sparkles className="w-3.5 h-3.5" /> Common
              <ChevronDown className={`w-3 h-3 transition-transform ${showCommon ? 'rotate-180' : ''}`} />
            </button>
          )}
          <button type="button" onClick={onClose} className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5`} aria-label="Close">
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* Common tests quick-pick */}
      {showCommon && common.length > 0 && (
        <div className="rounded-xl border border-emerald-500/20 bg-emerald-500/5 max-h-56 overflow-y-auto">
          {common.map((c) => (
            <button
              key={c.id}
              type="button"
              onClick={() => handleSelect(c)}
              className="w-full text-left px-3 py-2 hover:bg-emerald-500/10 transition-colors border-b border-emerald-500/10 last:border-0"
            >
              <div className="flex items-center justify-between gap-2">
                <span className={`text-xs font-bold ${text.heading}`}>{c.testName}</span>
                {c.shortName && <span className={`text-[10px] font-mono ${text.muted}`}>{c.shortName}</span>}
              </div>
              {c.category && <p className={`text-[10px] ${text.muted}`}>{c.category}</p>}
            </button>
          ))}
        </div>
      )}

      {/* Test search / selected */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Test</label>
        {selected ? (
          <div className="rounded-xl border border-cyan-500/30 bg-cyan-500/5 p-3 flex items-start gap-3">
            <CheckCircle2 className="w-4 h-4 text-cyan-500 mt-0.5 flex-shrink-0" />
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`text-sm font-bold ${text.heading}`}>{selected.testName}</span>
                {selected.shortName && <span className={`text-[10px] font-mono px-1.5 py-0.5 rounded bg-slate-500/15 ${text.muted}`}>{selected.shortName}</span>}
                <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-slate-500/20 text-slate-300 border border-slate-500/30">
                  {selected.investigationType?.replace(/_/g, ' ')}
                </span>
                {selected.isCommonInRwanda && (
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-300 border border-emerald-500/30">Common in Rwanda</span>
                )}
              </div>
              {selected.specimenType && (
                <p className={`text-[11px] mt-0.5 ${text.muted}`}>Specimen: {selected.specimenType}</p>
              )}
              {selected.clinicalUse && (
                <p className={`text-[11px] mt-0.5 ${text.body}`}>{selected.clinicalUse}</p>
              )}
            </div>
            <button type="button" onClick={handleClear} className={`p-1 rounded ${text.muted} hover:bg-white/5`} aria-label="Clear">
              <X className="w-3.5 h-3.5" />
            </button>
          </div>
        ) : (
          <div className="relative">
            <div className="relative">
              <Search className={`w-4 h-4 absolute left-3 top-1/2 -translate-y-1/2 ${text.muted}`} />
              <input
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search for a test (e.g. FBC, malaria RDT, chest X-ray)…"
                autoFocus
                className={`w-full pl-9 pr-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
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
                    No catalog match. You can still order this test as free text — but it will not be linked to a catalog entry.
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
                      <span className={`text-sm font-bold ${text.heading}`}>{r.testName}</span>
                      {r.shortName && <span className={`text-[10px] font-mono ${text.muted}`}>{r.shortName}</span>}
                    </div>
                    <div className="flex items-center gap-2 mt-0.5">
                      <span className="text-[9px] font-bold uppercase px-1 py-0.5 rounded bg-slate-500/20 text-slate-300 border border-slate-500/30">
                        {r.investigationType?.replace(/_/g, ' ')}
                      </span>
                      {r.category && <span className={`text-[10px] ${text.muted}`}>{r.category}</span>}
                      {r.isCommonInRwanda && (
                        <span className="text-[9px] font-bold uppercase px-1 py-0.5 rounded bg-emerald-500/20 text-emerald-300 border border-emerald-500/30">Rwanda common</span>
                      )}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Urgency chips */}
      <div>
        <div className="flex items-center justify-between mb-1.5">
          <label className={`block text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Urgency</label>
          {turnaroundHint && (
            <span className={`text-[10px] inline-flex items-center gap-1 ${text.muted}`}>
              <Clock className="w-3 h-3" /> {turnaroundHint}
            </span>
          )}
        </div>
        <div className="grid grid-cols-3 gap-2">
          {URGENCIES.map((u) => {
            const active = urgency === u.value;
            return (
              <button
                key={u.value}
                type="button"
                onClick={() => setUrgency(u.value)}
                className={`px-3 py-2 rounded-xl text-xs font-bold transition-all ${
                  active ? u.color : `bg-slate-500/10 ${text.body} hover:bg-slate-500/20`
                }`}
              >
                <div>{u.label}</div>
                <div className="text-[10px] font-normal opacity-80 mt-0.5">{u.helper}</div>
              </button>
            );
          })}
        </div>
      </div>

      {/* Clinical indication */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
          Clinical Indication
          <span className={`ml-2 font-normal normal-case ${text.muted}`}>(why is this being ordered)</span>
        </label>
        <input
          value={clinicalIndication}
          onChange={(e) => setClinicalIndication(e.target.value)}
          placeholder="e.g. Suspected severe malaria, persistent fever > 48h, chest pain with ECG changes…"
          className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
          style={glassInner}
        />
      </div>

      {/* Lab tech notes */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
          Notes for Lab Technician
          <span className={`ml-2 font-normal normal-case ${text.muted}`}>(special handling, optional)</span>
        </label>
        <textarea
          value={labNotes}
          onChange={(e) => setLabNotes(e.target.value)}
          placeholder="e.g. Patient on warfarin — process on priority. Specimen drawn at 14:00."
          rows={2}
          className={`w-full px-3 py-2.5 rounded-xl text-sm resize-none focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
          style={glassInner}
        />
      </div>

      {/* Action row */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleSubmit}
          disabled={!canSubmit}
          className="inline-flex items-center gap-2 px-5 py-2.5 bg-gradient-to-r from-slate-800 to-slate-700 text-white rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50 disabled:cursor-not-allowed"
        >
          {formLoading ? <Loader2 className="w-3.5 h-3.5 animate-spin" /> : <Send className="w-3.5 h-3.5" />}
          {urgency === 'STAT' ? 'Order STAT' : 'Order'}
        </button>
        <button type="button" onClick={onClose} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
        {selected && (
          <span className={`ml-auto inline-flex items-center gap-1 text-[10px] ${text.muted}`}>
            <Sparkles className="w-3 h-3 text-cyan-500" />
            From catalog
          </span>
        )}
      </div>
    </div>
  );
}
