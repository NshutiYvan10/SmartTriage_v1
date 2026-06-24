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
import { Search, X, Pill, Send, Loader2, CheckCircle2, AlertTriangle, Sparkles, Calculator, ShieldAlert, Repeat, Droplet, Zap } from 'lucide-react';
import { medsafetyApi, type DrugFormulary } from '@/api/medsafety';
import type {
  PrescribeMedicationRequest, MedicationRoute, MedicationPriority,
  PatientResponse, VisitResponse, TriageRecordResponse,
  PrescriptionType, MedicationProductType, VitalGateParameter, VitalGateComparator,
} from '@/api/types';
import { MEDICATION_PRIORITIES, PRESCRIPTION_TYPES, VITAL_GATE_PARAMETERS } from '@/api/types';

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
  const borderStyle = isDark ? '1px solid rgba(2,132,199,0.12)' : '1px solid rgba(203,213,225,0.3)';
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

  // ── V67 typed orders ──
  const [rxType, setRxType] = useState<PrescriptionType>('ONE_TIME');
  const [productType, setProductType] = useState<MedicationProductType>('DRUG');
  const [productDetail, setProductDetail] = useState('');
  // SCHEDULED
  const [intervalHours, setIntervalHours] = useState('');
  const [maxDoses, setMaxDoses] = useState('');
  const [endAt, setEndAt] = useState('');
  // PRN
  const [prnIndication, setPrnIndication] = useState('');
  const [prnMinIntervalHours, setPrnMinIntervalHours] = useState('');
  const [prnMaxDosesPerDay, setPrnMaxDosesPerDay] = useState('');
  const [gateParameter, setGateParameter] = useState<VitalGateParameter | ''>('');
  const [gateComparator, setGateComparator] = useState<VitalGateComparator>('GTE');
  const [gateThreshold, setGateThreshold] = useState('');
  // CONTINUOUS
  const [rateValue, setRateValue] = useState('');
  const [rateUnit, setRateUnit] = useState('mL/hr');
  // High-alert emergency override (skips the charge-approval gate)
  const [emergencyOverride, setEmergencyOverride] = useState(false);
  const [emergencyJustification, setEmergencyJustification] = useState('');

  /** Parse the free-text dose field into a structured value + unit
   *  ("500 mg" → 500/"mg") so the backend can verify administrations.
   *  Unparseable text (e.g. "1 sachet in 1L") sends no structured dose
   *  — verification is simply skipped for that order. */
  const structuredDose = useMemo((): { value: number; unit: string } | null => {
    const m = dose.trim().match(/^([0-9]+(?:\.[0-9]+)?)\s*([a-zA-Zµ/%]+)?$/);
    if (!m) return null;
    const value = Number(m[1]);
    if (!Number.isFinite(value) || value <= 0) return null;
    return { value, unit: (m[2] ?? 'mg').toLowerCase() };
  }, [dose]);

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

  // Per-type required fields (mirrors the backend's validation so the
  // doctor gets instant feedback instead of a 422).
  const typeFieldsValid = useMemo(() => {
    if (rxType === 'SCHEDULED') return Number(intervalHours) > 0;
    if (rxType === 'PRN') return prnIndication.trim().length > 0
      && (gateParameter === '' || gateThreshold.trim() !== '');
    if (rxType === 'CONTINUOUS') return Number(rateValue) > 0 && rateUnit.trim().length > 0;
    return true;
  }, [rxType, intervalHours, prnIndication, gateParameter, gateThreshold, rateValue, rateUnit]);

  const emergencyValid = !emergencyOverride || emergencyJustification.trim().length >= 10;

  const canSubmit = !!query.trim() && !!route && !formLoading && typeFieldsValid && emergencyValid;

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
      // ── V67 typed order ──
      prescriptionType: rxType,
      productType,
      productDetail: productType !== 'DRUG' && productDetail.trim() ? productDetail.trim() : undefined,
      doseValue: structuredDose?.value,
      doseUnit: structuredDose?.unit,
      ...(rxType === 'SCHEDULED' ? {
        intervalHours: Number(intervalHours),
        maxDoses: maxDoses ? Number(maxDoses) : undefined,
        endAt: endAt ? new Date(endAt).toISOString() : undefined,
      } : {}),
      ...(rxType === 'PRN' ? {
        prnIndication: prnIndication.trim(),
        prnMinIntervalHours: prnMinIntervalHours ? Number(prnMinIntervalHours) : undefined,
        prnMaxDosesPerDay: prnMaxDosesPerDay ? Number(prnMaxDosesPerDay) : undefined,
        ...(gateParameter !== '' && gateThreshold ? {
          gateParameter,
          gateComparator,
          gateThreshold: Number(gateThreshold),
        } : {}),
      } : {}),
      ...(rxType === 'CONTINUOUS' ? {
        rateValue: Number(rateValue),
        rateUnit: rateUnit.trim(),
      } : {}),
      ...(emergencyOverride ? {
        emergencyOverride: true,
        emergencyJustification: emergencyJustification.trim(),
      } : {}),
    });
  }, [canSubmit, onSubmit, visit.id, selected, query, dose, route, frequency, priority, notes,
      rxType, productType, productDetail, structuredDose, intervalHours, maxDoses, endAt,
      prnIndication, prnMinIntervalHours, prnMaxDosesPerDay, gateParameter, gateComparator,
      gateThreshold, rateValue, rateUnit, emergencyOverride, emergencyJustification]);

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
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-emerald-500/20 text-emerald-300 border border-emerald-500/30">REML</span>
                )}
                {selected.isHighAlert && (
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-red-500/20 text-red-300 border border-red-500/30 inline-flex items-center gap-1">
                    <ShieldAlert className="w-3 h-3" /> High alert
                  </span>
                )}
                {selected.requiresDoubleCheck && (
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-amber-500/20 text-amber-300 border border-amber-500/30">Double-check</span>
                )}
                {selected.pregnancyCategory && (
                  <span className="text-[9px] font-bold uppercase px-1.5 py-0.5 rounded bg-purple-500/20 text-purple-300 border border-purple-500/30">
                    Preg {selected.pregnancyCategory}
                  </span>
                )}
              </div>
              <div className={`text-[11px] mt-1 ${text.muted}`}>
                {selected.drugClass}{selected.atcCode ? ` · ATC ${selected.atcCode}` : ''}{selected.remlCategory ? ` · ${selected.remlCategory}` : ''}
              </div>
              {allergenHit && (
                <div className="mt-2 p-2 rounded-lg bg-red-500/10 border border-red-500/30 flex items-center gap-2">
                  <AlertTriangle className="w-3.5 h-3.5 text-red-400 flex-shrink-0" />
                  <span className="text-[11px] text-red-300 font-semibold">
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
                className={`w-full pl-9 pr-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`}
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
                    No formulary match. You can still prescribe this drug as free text — but no structured dose guidance will apply.
                  </p>
                )}
                {results.map((r) => (
                  <button
                    key={r.id}
                    type="button"
                    onClick={() => handleSelect(r)}
                    className={`w-full text-left px-3 py-2 hover:bg-emerald-500/10 transition-colors border-b last:border-0 ${isDark ? 'border-sky-500/10' : 'border-slate-200/40'}`}
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className={`text-sm font-bold ${text.heading}`}>{r.genericName}</span>
                      <div className="flex items-center gap-1">
                        {r.isOnReml && <span className="text-[9px] font-bold uppercase px-1 py-0.5 rounded bg-emerald-500/20 text-emerald-300 border border-emerald-500/30">REML</span>}
                        {r.isHighAlert && <span className="text-[9px] font-bold uppercase px-1 py-0.5 rounded bg-red-500/20 text-red-300 border border-red-500/30">HA</span>}
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
            <div className="mt-2 text-[11px] text-amber-400">
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
                  active ? p.tint : `${text.body} border-transparent hover:bg-white/5`
                }`}
              >
                <div>{p.label}</div>
                <div className="text-[10px] font-normal opacity-80 mt-0.5">SLA {p.slaMinutes}m</div>
              </button>
            );
          })}
        </div>
      </div>

      {/* ── V67: prescription type ── */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
          Prescription type
        </label>
        <div className="grid grid-cols-2 md:grid-cols-4 gap-2">
          {PRESCRIPTION_TYPES.map((t) => {
            const active = rxType === t.value;
            const Icon = t.value === 'SCHEDULED' ? Repeat
              : t.value === 'CONTINUOUS' ? Droplet
              : t.value === 'PRN' ? Zap : Pill;
            return (
              <button
                key={t.value}
                type="button"
                onClick={() => setRxType(t.value)}
                title={t.description}
                className={`px-3 py-2 rounded-xl text-xs font-bold transition-all border text-left ${
                  active
                    ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/30'
                    : `${text.body} border-transparent hover:bg-white/5`
                }`}
              >
                <div className="flex items-center gap-1.5"><Icon className="w-3.5 h-3.5" />{t.label}</div>
              </button>
            );
          })}
        </div>
      </div>

      {/* ── V67: per-type parameters ── */}
      {rxType === 'SCHEDULED' && (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3 rounded-xl p-3" style={glassInner}>
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
              Every (hours) <span className="text-red-500">*</span>
            </label>
            <input type="number" min="0.5" step="0.5" value={intervalHours}
              onChange={(e) => setIntervalHours(e.target.value)} placeholder="e.g. 8"
              className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`} style={glassInner} />
          </div>
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
              Total doses <span className={`font-normal normal-case ${text.muted}`}>(optional)</span>
            </label>
            <input type="number" min="1" value={maxDoses}
              onChange={(e) => setMaxDoses(e.target.value)} placeholder="until discontinued"
              className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`} style={glassInner} />
          </div>
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
              Stop at <span className={`font-normal normal-case ${text.muted}`}>(optional)</span>
            </label>
            <input type="datetime-local" value={endAt}
              onChange={(e) => setEndAt(e.target.value)}
              className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`} style={glassInner} />
          </div>
          <p className={`md:col-span-3 text-[10px] ${text.muted}`}>
            The first dose is due immediately; each administration schedules the next.
            Nurses are re-notified at every interval, with overdue and missed-dose escalation.
          </p>
        </div>
      )}

      {rxType === 'PRN' && (
        <div className="space-y-3 rounded-xl p-3" style={glassInner}>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-3">
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
                Indication <span className="text-red-500">*</span>
              </label>
              <input value={prnIndication} onChange={(e) => setPrnIndication(e.target.value)}
                placeholder="e.g. pain, nausea"
                className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`} style={glassInner} />
            </div>
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
                Min hours between doses
              </label>
              <input type="number" min="0.5" step="0.5" value={prnMinIntervalHours}
                onChange={(e) => setPrnMinIntervalHours(e.target.value)} placeholder="e.g. 6"
                className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`} style={glassInner} />
            </div>
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
                Max doses / 24h
              </label>
              <input type="number" min="1" value={prnMaxDosesPerDay}
                onChange={(e) => setPrnMaxDosesPerDay(e.target.value)} placeholder="e.g. 4"
                className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`} style={glassInner} />
            </div>
          </div>
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
              Vitals gate <span className={`font-normal normal-case ${text.muted}`}>(optional — "administer only if …")</span>
            </label>
            <div className="flex items-center gap-2 flex-wrap">
              <select value={gateParameter}
                onChange={(e) => setGateParameter(e.target.value as VitalGateParameter | '')}
                className={`px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner}>
                <option value="">No gate</option>
                {VITAL_GATE_PARAMETERS.map((p) => (
                  <option key={p.value} value={p.value}>{p.label}</option>
                ))}
              </select>
              {gateParameter !== '' && (
                <>
                  <select value={gateComparator}
                    onChange={(e) => setGateComparator(e.target.value as VitalGateComparator)}
                    className={`px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 ${isDark ? 'text-white' : 'text-slate-800'}`} style={glassInner}>
                    <option value="GTE">≥</option>
                    <option value="LTE">≤</option>
                  </select>
                  <input type="number" value={gateThreshold}
                    onChange={(e) => setGateThreshold(e.target.value)}
                    placeholder="threshold"
                    className={`w-28 px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`} style={glassInner} />
                  <span className={`text-[11px] ${text.muted}`}>
                    {VITAL_GATE_PARAMETERS.find((p) => p.value === gateParameter)?.unit}
                    {' '}— checked against the latest vitals at administration; blocked if unmet (override requires justification).
                  </span>
                </>
              )}
            </div>
          </div>
        </div>
      )}

      {rxType === 'CONTINUOUS' && (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3 rounded-xl p-3" style={glassInner}>
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
              Rate <span className="text-red-500">*</span>
            </label>
            <input type="number" min="0.1" step="0.1" value={rateValue}
              onChange={(e) => setRateValue(e.target.value)} placeholder="e.g. 100"
              className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`} style={glassInner} />
          </div>
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
              Rate unit <span className="text-red-500">*</span>
            </label>
            <input value={rateUnit} onChange={(e) => setRateUnit(e.target.value)}
              placeholder="mL/hr, units/hr, mcg/kg/min"
              className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`} style={glassInner} />
          </div>
          <p className={`md:col-span-2 text-[10px] ${text.muted}`}>
            The nurse confirms initiation at the bedside; rate changes and the stop are each audited events.
          </p>
        </div>
      )}

      {/* ── V67: product type (special administrations) ── */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>
          Product
        </label>
        <div className="flex items-center gap-2 flex-wrap">
          {([['DRUG', 'Medication'], ['BLOOD_PRODUCT', 'Blood product'], ['IV_FLUID', 'IV fluid'], ['OTHER', 'Other']] as Array<[MedicationProductType, string]>).map(([value, label]) => {
            const active = productType === value;
            return (
              <button key={value} type="button" onClick={() => setProductType(value)}
                className={`px-2.5 py-1.5 rounded-lg text-[11px] font-bold transition-all ${
                  active
                    ? value === 'BLOOD_PRODUCT'
                      ? 'bg-rose-600 text-white shadow-md'
                      : 'bg-gradient-to-r from-emerald-500 to-emerald-600 text-white shadow-md'
                    : `${text.body} hover:bg-white/5`
                }`}>
                {label}
              </button>
            );
          })}
          {productType !== 'DRUG' && (
            <input value={productDetail} onChange={(e) => setProductDetail(e.target.value)}
              placeholder={productType === 'BLOOD_PRODUCT' ? 'e.g. PRBC 2 units, FFP 4 units' : 'detail'}
              className={`flex-1 min-w-[180px] px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`} style={glassInner} />
          )}
        </div>
        {productType === 'BLOOD_PRODUCT' && (
          <p className="mt-1 text-[10px] text-rose-500 font-semibold">
            Blood products always require a second-clinician witness at administration.
          </p>
        )}
      </div>

      {/* ── V67: high-alert approval notice + emergency override ── */}
      {selected?.isHighAlert && (
        <div className="rounded-xl p-3 border border-red-400/40 bg-red-500/5 space-y-2">
          <div className="flex items-center gap-2">
            <ShieldAlert className="w-4 h-4 text-red-500" />
            <span className={`text-[11px] font-bold ${text.heading}`}>
              High-alert drug — requires charge-nurse approval before administration.
            </span>
          </div>
          <label className={`flex items-center gap-2 text-[11px] font-semibold ${text.body}`}>
            <input type="checkbox" checked={emergencyOverride}
              onChange={(e) => setEmergencyOverride(e.target.checked)} />
            Emergency — skip the approval gate (justification required, department-visible)
          </label>
          {emergencyOverride && (
            <textarea rows={2} value={emergencyJustification}
              onChange={(e) => setEmergencyJustification(e.target.value)}
              placeholder="Why this cannot wait for approval (min 10 characters)…"
              className={`w-full px-3 py-2 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-red-500/30 border border-red-500/30 placeholder-slate-400 ${text.body}`} style={glassInner} />
          )}
        </div>
      )}

      {/* Dose, route, frequency, notes */}
      <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
        <div>
          <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Dose</label>
          <input
            value={dose}
            onChange={(e) => setDose(e.target.value)}
            placeholder="e.g. 500 mg, 10 mg/kg, 1 g"
            className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`}
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
                      : `${text.body} hover:bg-white/5`
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
            className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`}
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
            className={`w-full px-3 py-2.5 rounded-xl text-sm focus:outline-none focus:ring-2 focus:ring-cyan-500/20 placeholder-slate-400 ${text.body}`}
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
