/* ═══════════════════════════════════════════════════════════════
   MedicationPanel — REML-aware prescribing entry.

   Replaces the previous free-text drugName / dose / route / frequency
   form with a structured search against the DrugFormulary (REML
   formulary, V18+). Once a drug is selected:
     - REML / high-alert / pregnancy-category badges surface
     - Available routes pre-fill from formulary; the doctor picks one
       from the matched routes (no longer free text)
     - Adult dose range and pediatric mg/kg range are shown in-line
     - Pediatric weight (from latest triage childWeightKg, or visit
       weight) drives a recommended dose calculation: weight ×
       pediatric mg/kg → suggested mg dose; the doctor can accept or
       override
     - Allergen group surfaces against the patient's known allergies
       so the doctor sees a soft-warning before they hit Submit
     - Free-text fallback is preserved when the catalog has no match —
       prescribing a non-formulary drug is still possible, with no
       structured guidance.

   Intentional non-goals:
     - This panel does NOT enforce safety checks. Allergy / interaction /
       dose-range hard validation lives in handlePrescribeMedication →
       PrescribeSafetyDialog. The panel's job is to make data entry
       fast and structured; the safety engine remains the gatekeeper.
     - This panel does NOT support compound prescriptions or tapering
       schedules. One drug, one dose, one frequency — same shape as
       PrescribeMedicationRequest.
   ═══════════════════════════════════════════════════════════════ */

import { useState, useEffect, useRef, useCallback, useMemo } from 'react';
import { Search, X, Pill, Send, Loader2, CheckCircle2, AlertTriangle, Sparkles, Calculator, ShieldAlert } from 'lucide-react';
import { medsafetyApi, type DrugFormulary } from '@/api/medsafety';
import type { PrescribeMedicationRequest, MedicationRoute, MedicationPriority, PatientResponse, VisitResponse, TriageRecordResponse } from '@/api/types';
import { MEDICATION_PRIORITIES } from '@/api/types';

const ROUTE_LABELS: Record<string, string> = {
  PO: 'Oral',
  IV: 'IV',
  IM: 'IM',
  SC: 'SC',
  SL: 'Sublingual',
  PR: 'PR',
  INH: 'Inhaled',
  NEB: 'Nebuliser',
  TOP: 'Topical',
  NASAL: 'Nasal',
  OPHTHALMIC: 'Ophthalmic',
  OTIC: 'Ear',
  ETT: 'ETT',
  IO: 'IO',
  OTHER: 'Other',
};

interface Props {
  onSubmit: (req: Partial<PrescribeMedicationRequest>) => Promise<void>;
  onClose: () => void;
  formLoading: boolean;
  patient: PatientResponse | null;
  visit: VisitResponse;
  latestTriage: TriageRecordResponse | null;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
}

/**
 * Parse the formulary's availableRoutes free-text into the route enum
 * subset we should offer. Some entries store "PO, IV" or "IV/IM"; we
 * split on commas / slashes / whitespace and keep tokens that match a
 * known MedicationRoute.
 */
function parseAvailableRoutes(s: string | null | undefined): MedicationRoute[] {
  if (!s) return [];
  const tokens = s.split(/[,/|\s]+/).map((t) => t.trim().toUpperCase()).filter(Boolean);
  const known = new Set(Object.keys(ROUTE_LABELS));
  const out: MedicationRoute[] = [];
  for (const t of tokens) {
    if (known.has(t) && !out.includes(t as MedicationRoute)) out.push(t as MedicationRoute);
  }
  return out;
}

/**
 * Soft-match a drug's allergen group against the patient's free-text
 * allergies. Lowercase substring match — the structured allergyCheck.ts
 * util in handlePrescribeMedication handles the authoritative match;
 * this is just a hint shown alongside the selected drug.
 */
function softAllergenHit(allergenGroups: string | null | undefined, knownAllergies: string | null | undefined): string | null {
  if (!allergenGroups || !knownAllergies) return null;
  const groups = allergenGroups.toLowerCase().split(/[,;|]/).map((s) => s.trim()).filter(Boolean);
  const allergies = knownAllergies.toLowerCase();
  for (const g of groups) {
    if (g && allergies.includes(g)) return g;
  }
  return null;
}

export function MedicationPanel({
  onSubmit, onClose, formLoading, patient, visit, latestTriage,
  glassCard, glassInner, isDark, text,
}: Props) {
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<DrugFormulary[]>([]);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<DrugFormulary | null>(null);
  const searchSeq = useRef(0);

  // Form fields
  const [route, setRoute] = useState<MedicationRoute>('PO');
  const [dose, setDose] = useState('');
  const [frequency, setFrequency] = useState('');
  const [notes, setNotes] = useState('');
  // Workflow 3 — structured urgency. Defaults ROUTINE; the doctor
  // explicitly upgrades to URGENT/STAT for time-critical orders.
  // STAT raises the order to the top of the nurse queue and starts
  // a 10-min SLA timer.
  const [priority, setPriority] = useState<MedicationPriority>('ROUTINE');

  // Patient weight resolution: latest triage's childWeightKg is the
  // most recently confirmed value (pediatric registration captures it,
  // and re-triage updates it); fall back to nothing if absent.
  const weightKg: number | null = useMemo(() => {
    const w = latestTriage?.childWeightKg;
    return typeof w === 'number' && w > 0 ? w : null;
  }, [latestTriage]);

  // Search-as-you-type with 250ms debounce + sequence guard so a
  // slow earlier response can't overwrite a faster later one.
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
      medsafetyApi.searchFormulary(trimmed)
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

  // When a formulary entry is picked, pre-fill route from its first
  // available route. Doctor can still change it via the chip row.
  const handleSelect = useCallback((entry: DrugFormulary) => {
    setSelected(entry);
    setQuery(entry.genericName);
    const parsed = parseAvailableRoutes(entry.availableRoutes);
    if (parsed.length > 0) setRoute(parsed[0]);
  }, []);

  const handleClear = useCallback(() => {
    setSelected(null);
    setQuery('');
    setResults([]);
  }, []);

  // Pediatric recommended-dose calculator — weight × max mg/kg.
  // Uses MAX (not min) because conservatism here goes the wrong way:
  // an underdose of an antibiotic for severe infection is worse than
  // a small overshoot caught by the engine. The doctor sees both ends
  // of the range and the suggestion explicitly.
  const recommendedPediatricDoseMg: number | null = useMemo(() => {
    if (!selected || !weightKg) return null;
    const mgPerKg = selected.pediatricMaxDoseMgPerKg;
    if (typeof mgPerKg !== 'number' || mgPerKg <= 0) return null;
    return Number((weightKg * mgPerKg).toFixed(2));
  }, [selected, weightKg]);

  const allergenHit = useMemo(() => {
    return softAllergenHit(selected?.allergenGroups, patient?.knownAllergies);
  }, [selected, patient]);

  const availableRoutes: MedicationRoute[] = useMemo(() => {
    if (!selected) return Object.keys(ROUTE_LABELS) as MedicationRoute[];
    const parsed = parseAvailableRoutes(selected.availableRoutes);
    return parsed.length > 0 ? parsed : (Object.keys(ROUTE_LABELS) as MedicationRoute[]);
  }, [selected]);

  const canSubmit = !!query.trim() && !!route && !formLoading;

  const handleApplyRecommended = useCallback(() => {
    if (recommendedPediatricDoseMg != null) setDose(`${recommendedPediatricDoseMg} mg`);
  }, [recommendedPediatricDoseMg]);

  const handleSubmit = useCallback(async () => {
    if (!canSubmit) return;
    await onSubmit({
      visitId: visit.id,
      drugName: selected?.genericName ?? query.trim(),
      dose: dose.trim() || undefined,
      route,
      frequency: frequency.trim() || undefined,
      priority,
      notes: notes.trim() || undefined,
    });
  }, [canSubmit, onSubmit, visit.id, selected, query, dose, route, frequency, priority, notes]);

  return (
    <div className="rounded-2xl p-5 animate-fade-up space-y-4" style={glassCard}>
      <div className="flex items-center justify-between">
        <h4 className={`text-sm font-bold flex items-center gap-2 ${text.heading}`}>
          <Pill className="w-4 h-4 text-emerald-500" />
          Prescribe Medication
        </h4>
        <button type="button" onClick={onClose} className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5`} aria-label="Close">
          <X className="w-4 h-4" />
        </button>
      </div>

      {/* Drug search / selected */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Drug</label>
        {selected ? (
          <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/5 p-3 flex items-start gap-3">
            <CheckCircle2 className="w-4 h-4 text-emerald-500 mt-0.5 flex-shrink-0" />
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`text-sm font-bold ${text.heading}`}>{selected.genericName}</span>
                {selected.brandNames && (
                  <span className={`text-[10px] ${text.muted}`}>({selected.brandNames})</span>
                )}
                {selected.isOnReml && (
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-emerald-500/15 text-emerald-600">REML</span>
                )}
                {selected.isHighAlert && (
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-red-500/15 text-red-600 inline-flex items-center gap-1">
                    <ShieldAlert className="w-3 h-3" /> High alert
                  </span>
                )}
                {selected.requiresDoubleCheck && (
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-700">Double-check</span>
                )}
                {selected.pregnancyCategory && (
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-purple-500/15 text-purple-700">
                    Preg {selected.pregnancyCategory}
                  </span>
                )}
              </div>
              <div className={`text-[11px] mt-1 ${text.muted}`}>
                {selected.drugClass}{selected.atcCode ? ` · ATC ${selected.atcCode}` : ''}{selected.remlCategory ? ` · ${selected.remlCategory}` : ''}
              </div>
              {allergenHit && (
                <div className="mt-2 p-2 rounded-lg bg-red-500/10 border border-red-500/30 flex items-center gap-2">
                  <AlertTriangle className="w-3.5 h-3.5 text-red-600 flex-shrink-0" />
                  <span className="text-[11px] text-red-700 font-semibold">
                    Allergen group "{allergenHit}" may match this patient's known allergies. Hard check runs on submit.
                  </span>
                </div>
              )}
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
                placeholder="Search REML formulary (e.g. paracetamol, amoxicillin, oxytocin)…"
                autoFocus
                className={`w-full pl-9 pr-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner}
              />
            </div>
            {(searching || results.length > 0) && (
              <div className="absolute z-10 mt-1 w-full rounded-xl border border-slate-200/30 max-h-72 overflow-y-auto" style={glassCard}>
                {searching && results.length === 0 && (
                  <p className={`text-xs text-center py-3 ${text.muted}`}>Searching…</p>
                )}
                {!searching && results.length === 0 && query.trim().length >= 2 && (
                  <p className={`text-xs text-center py-3 ${text.muted}`}>
                    No formulary match. You can still prescribe this drug as free text — but no structured dose guidance will apply.
                  </p>
                )}
                {results.map((r) => (
                  <button
                    key={r.id}
                    type="button"
                    onClick={() => handleSelect(r)}
                    className="w-full text-left px-3 py-2 hover:bg-emerald-500/10 transition-colors border-b border-slate-200/10 last:border-0"
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className={`text-sm font-bold ${text.heading}`}>{r.genericName}</span>
                      <div className="flex items-center gap-1">
                        {r.isOnReml && <span className="text-[9px] font-bold uppercase px-1 py-0.5 rounded bg-emerald-500/15 text-emerald-600">REML</span>}
                        {r.isHighAlert && <span className="text-[9px] font-bold uppercase px-1 py-0.5 rounded bg-red-500/15 text-red-600">HA</span>}
                      </div>
                    </div>
                    <div className={`text-[10px] ${text.muted}`}>
                      {r.drugClass}{r.brandNames ? ` · ${r.brandNames}` : ''}
                    </div>
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* Dose-range hint + pediatric calculator */}
      {selected && (
        <div className="rounded-xl p-3 border" style={{ ...glassInner, borderColor: 'rgba(16,185,129,0.2)' }}>
          <div className="flex items-center gap-2 mb-2">
            <Calculator className="w-3.5 h-3.5 text-emerald-500" />
            <span className={`text-[11px] font-bold uppercase tracking-wider ${text.label}`}>Dose Reference</span>
          </div>
          <div className="grid grid-cols-2 gap-3 text-[11px]">
            <div>
              <div className={`font-bold ${text.heading}`}>Adult</div>
              <div className={text.body}>
                {selected.adultMinDoseMg != null && selected.adultMaxDoseMg != null
                  ? `${selected.adultMinDoseMg}–${selected.adultMaxDoseMg} mg`
                  : selected.adultMaxDoseMg != null
                    ? `Up to ${selected.adultMaxDoseMg} mg`
                    : '—'}
              </div>
            </div>
            <div>
              <div className={`font-bold ${text.heading}`}>Pediatric (mg/kg)</div>
              <div className={text.body}>
                {selected.pediatricMinDoseMgPerKg != null && selected.pediatricMaxDoseMgPerKg != null
                  ? `${selected.pediatricMinDoseMgPerKg}–${selected.pediatricMaxDoseMgPerKg} mg/kg`
                  : selected.pediatricMaxDoseMgPerKg != null
                    ? `Up to ${selected.pediatricMaxDoseMgPerKg} mg/kg`
                    : '—'}
              </div>
            </div>
          </div>
          {visit.isPediatric && weightKg && recommendedPediatricDoseMg != null && (
            <div className="mt-3 flex items-center justify-between rounded-lg p-2 bg-emerald-500/10 border border-emerald-500/30">
              <span className={`text-[11px] font-semibold ${text.body}`}>
                Suggested for {weightKg} kg: <span className="font-bold">{recommendedPediatricDoseMg} mg</span>
              </span>
              <button
                type="button"
                onClick={handleApplyRecommended}
                className="px-2.5 py-1 rounded-md text-[10px] font-bold bg-emerald-600 text-white hover:bg-emerald-700"
              >
                Apply
              </button>
            </div>
          )}
          {visit.isPediatric && !weightKg && (
            <div className="mt-2 text-[11px] text-amber-600">
              Pediatric patient but no weight on record — record weight in triage to enable dose calculator.
            </div>
          )}
        </div>
      )}

      {/* Urgency tier — Workflow 3. STAT pushes to the top of the
          nurse queue and starts a 10-minute SLA timer. */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
          Urgency
        </label>
        <div className="grid grid-cols-3 gap-2">
          {MEDICATION_PRIORITIES.map((p) => {
            const active = priority === p.value;
            return (
              <button
                key={p.value}
                type="button"
                onClick={() => setPriority(p.value)}
                title={p.description}
                className={`px-3 py-2 rounded-xl text-xs font-bold transition-all border ${
                  active ? p.tint : 'bg-slate-500/10 text-slate-500 border-transparent hover:bg-slate-500/20'
                }`}
              >
                <div>{p.label}</div>
                <div className="text-[10px] font-normal opacity-80 mt-0.5">SLA {p.slaMinutes}m</div>
              </button>
            );
          })}
        </div>
      </div>

      {/* Dose, route, frequency, notes */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        <div>
          <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Dose</label>
          <input
            value={dose}
            onChange={(e) => setDose(e.target.value)}
            placeholder="e.g. 500 mg, 10 mg/kg, 1 g"
            className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
            style={glassInner}
          />
        </div>
        <div>
          <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Route</label>
          <div className="flex flex-wrap gap-1.5">
            {availableRoutes.map((r) => {
              const active = route === r;
              return (
                <button
                  key={r}
                  type="button"
                  onClick={() => setRoute(r)}
                  className={`px-2.5 py-1.5 rounded-lg text-[11px] font-bold transition-all ${
                    active
                      ? 'bg-gradient-to-r from-emerald-500 to-emerald-600 text-white shadow-md'
                      : isDark
                        ? 'bg-white/5 text-slate-300 hover:bg-white/10'
                        : 'bg-slate-100 text-slate-600 hover:bg-slate-200'
                  }`}
                  title={ROUTE_LABELS[r] ?? r}
                >
                  {r}
                </button>
              );
            })}
          </div>
        </div>
        <div>
          <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Frequency</label>
          <input
            value={frequency}
            onChange={(e) => setFrequency(e.target.value)}
            placeholder="e.g. q8h, BID, stat, every 4–6h prn"
            className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
            style={glassInner}
          />
        </div>
        <div>
          <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
            Notes <span className={`ml-1 font-normal normal-case ${text.muted}`}>(optional)</span>
          </label>
          <input
            value={notes}
            onChange={(e) => setNotes(e.target.value)}
            placeholder="e.g. Give with food, monitor BP at 30 min"
            className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
            style={glassInner}
          />
        </div>
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
          Prescribe
        </button>
        <button type="button" onClick={onClose} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
        {selected && (
          <span className={`ml-auto inline-flex items-center gap-1 text-[10px] ${text.muted}`}>
            <Sparkles className="w-3 h-3 text-emerald-500" />
            From REML formulary
          </span>
        )}
      </div>
    </div>
  );
}
