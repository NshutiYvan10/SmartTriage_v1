/* ═══════════════════════════════════════════════════════════════
   PrescribePanel — Fast, safe medication prescribing for the ED.

   Replaces the previous free-text drug-name form. Designed for a doctor
   who has seconds, not minutes, and must produce a complete and SAFE
   medication order.

   Capabilities:
     - Autocomplete drug search against the Rwanda Essential Medicines List
       (DrugFormulary, /med-safety/formulary/search)
     - On drug select, pre-fills:
         · default route (first entry in availableRoutes)
         · suggested adult or pediatric dose (mg/kg × weight for pediatric)
         · suggested frequency from drug class
     - Real-time client-side allergy precheck using the patient's free-text
       knownAllergies vs the formulary's allergenGroups. Shows a red blocking
       banner BEFORE submit. Server still re-validates after prescribe so
       the audit trail is complete.
     - Reorder: dropdown of this patient's past prescriptions across all
       visits. One tap copies drug / dose / route / frequency into the form.
     - Pediatric weight-based dose calculator: shows mg/kg × weight = total
       mg with one click to accept. Uses latestTriage.weightKg.
     - Submit calls existing prescribe API; component is the only thing in
       the prescribe path that touches the new safety-engine endpoints.

   Clinical-safety contracts:
     - Allergy match is RED, prominent, and present BEFORE the submit
       button is reachable. Doctor can still proceed (override is recorded
       server-side via med-safety override endpoint), but never silently.
     - High-alert drug status (epinephrine, opioids, benzos, etc.) is
       surfaced as an amber banner with the requires_double_check flag.
     - Patient-profile failure does not silently fall back to "no allergies":
       the parent (VisitDetailPage) shows a "verify before prescribing"
       state in PatientSafetyBanner, and this panel respects the same idea —
       if patient is null we render a hard warning and disable submit.
   ═══════════════════════════════════════════════════════════════ */

import React, { useState, useEffect, useMemo, useRef, useCallback } from 'react';
import {
  Search, X, AlertTriangle, ShieldAlert, Pill, Send, Loader2, History,
  Calculator, CheckCircle2, ChevronDown, Sparkles, Baby,
} from 'lucide-react';
import { medsafetyApi, type DrugFormulary } from '@/api/medsafety';
import { medicationApi } from '@/api/medications';
import type {
  PatientResponse, PregnancyStatus, TriageRecordResponse, MedicationResponse,
  PrescribeMedicationRequest, MedicationRoute,
} from '@/api/types';

/**
 * Parses a free-text dose into a numeric mg value FOR mg-based drugs.
 * Returns null for any drug whose canonical unit is not mg — the safety
 * engine's numeric dose check is meaningless for those (insulin units,
 * IU, ml, sachets), so we omit doseMg and let allergy + interaction
 * checks run alone.
 *
 * Conservative by design: when in doubt, return null rather than guess.
 * A miscalculated doseMg that *passes* the validator is worse than an
 * unparseable dose that runs the rest of the safety checks without it.
 */
export function parseDoseToMg(raw: string | undefined | null, drugDoseUnit?: string | null): number | null {
  if (!raw) return null;
  // Drug-level unit gate — if the formulary says this drug is dosed in
  // anything other than mg, mcg, or g, we never produce a numeric mg.
  // The dose-unit column is authoritative.
  const unit = (drugDoseUnit || 'MG').toUpperCase();
  if (unit !== 'MG' && unit !== 'MCG' && unit !== 'G') {
    return null;
  }
  const s = raw.trim().toLowerCase();
  if (!s) return null;
  // Bail out if the doctor wrote a non-mg unit even on a mg drug — better
  // to skip than misconvert "1 tablet of paracetamol" into a number we
  // pretend is mg.
  if (/\b(units?|iu|tab(let)?s?|cap(sule)?s?|drops?|sachets?|ml(?!\w)|puffs?)\b/.test(s)) {
    return null;
  }
  const match = s.match(/(\d+(?:\.\d+)?)\s*(mcg|ug|µg|g|gm|mg)?/);
  if (!match) return null;
  const value = parseFloat(match[1]);
  if (!Number.isFinite(value)) return null;
  const writtenUnit = match[2] ?? unit.toLowerCase();
  if (writtenUnit === 'g' || writtenUnit === 'gm') return value * 1000;
  if (writtenUnit === 'mcg' || writtenUnit === 'ug' || writtenUnit === 'µg') return value / 1000;
  return value;
}

/** Human-readable suffix for the drug's canonical dose unit. */
function doseUnitLabel(unit: string | null | undefined): string {
  switch ((unit || 'MG').toUpperCase()) {
    case 'MG': return 'mg';
    case 'MCG': return 'mcg';
    case 'G': return 'g';
    case 'UNITS': return 'units';
    case 'IU': return 'IU';
    case 'ML': return 'ml';
    case 'SACHETS': return 'sachet(s)';
    case 'TABLETS': return 'tab(s)';
    case 'PUFFS': return 'puff(s)';
    case 'DROPS': return 'drop(s)';
    default: return unit?.toLowerCase() || 'mg';
  }
}

/**
 * Decides whether prescribing this drug to this patient triggers a
 * pregnancy / teratogen warning. Drives the override-reason flow and
 * the safety banner.
 *
 * Severity policy:
 *   - HIGH    = pregnancyCategory X for any reproductive-risk patient,
 *               OR D for confirmed PREGNANT
 *   - MODERATE = D for POSSIBLY_PREGNANT or BREASTFEEDING,
 *                or X for BREASTFEEDING (pregnancy category isn't strictly
 *                a lactation safety scale — surfacing it as moderate keeps
 *                the doctor informed without silently passing every Cat-X
 *                drug through to a breastfeeding patient)
 *
 * UNKNOWN gender or pregnancyStatus = UNKNOWN does NOT auto-trigger; the
 * banner already prompts the clinician to confirm pregnancy status before
 * prescribing, and we don't want to throw a hard warning on every
 * prescription for a male patient with UNKNOWN gender.
 */
type PregnancyHazard = {
  severity: 'high' | 'moderate';
  category: 'D' | 'X';
  status: PregnancyStatus;
  message: string;
};

export function detectPregnancyHazard(
  drug: DrugFormulary | null,
  patient: PatientResponse | null,
): PregnancyHazard | null {
  if (!drug || !patient) return null;
  const cat = (drug.pregnancyCategory || '').toUpperCase();
  if (cat !== 'D' && cat !== 'X') return null;
  const status = patient.pregnancyStatus;
  if (!status) return null;
  if (status === 'NOT_PREGNANT' || status === 'NOT_APPLICABLE' || status === 'UNKNOWN') {
    return null;
  }
  // From here, status ∈ { PREGNANT, POSSIBLY_PREGNANT, BREASTFEEDING }
  let severity: 'high' | 'moderate';
  if (cat === 'X') {
    severity = status === 'BREASTFEEDING' ? 'moderate' : 'high';
  } else {
    // cat === 'D'
    severity = status === 'PREGNANT' ? 'high' : 'moderate';
  }
  const message = cat === 'X'
    ? 'Pregnancy Category X — contraindicated. Verify alternatives before prescribing.'
    : 'Pregnancy Category D — positive evidence of risk. Use only if benefit outweighs risk.';
  return { severity, category: cat as 'D' | 'X', status, message };
}

const ROUTE_LABELS: Record<MedicationRoute, string> = {
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
  OPHTHALMIC: 'Eye',
  OTIC: 'Ear',
  ETT: 'ETT',
  IO: 'IO',
  OTHER: 'Other',
};

const ALL_ROUTES: MedicationRoute[] = [
  'PO', 'IV', 'IM', 'SC', 'SL', 'PR', 'INH', 'NEB', 'TOP', 'NASAL', 'OPHTHALMIC', 'OTIC', 'ETT', 'IO', 'OTHER',
];

// Reasonable defaults for frequency offered as quick-pick chips. We don't
// guess from the formulary because frequency is highly drug- and
// indication-specific; the doctor should explicitly pick or type.
const COMMON_FREQUENCIES = ['STAT', 'OD', 'BD', 'TDS', 'QID', 'Q6H', 'Q8H', 'Q12H', 'PRN'];

/**
 * Safety context the parent needs to run the full prescribe → validate →
 * (optional) override chain. It is intentionally separate from the wire-format
 * `PrescribeMedicationRequest`: the override reason and the parsed doseMg
 * never get sent to /medications, only to /med-safety.
 */
export interface PrescribeSafetyContext {
  /** Numeric mg parsed from the free-text dose; null if unparseable. */
  doseMg: number | null;
  /** Patient weight at last triage, for pediatric weight-based validation. */
  weightKg: number | null;
  /** Why the doctor chose to proceed despite a known hazard. Empty when no hazard. */
  overrideReason: string;
  /** What kind of hazard the override is for, for the audit trail. */
  overrideKind: 'allergy' | 'pregnancy' | 'allergy-and-pregnancy' | null;
}

interface Props {
  visitId: string;
  patient: PatientResponse | null;
  latestTriage: TriageRecordResponse | null;
  onSubmit: (req: Partial<PrescribeMedicationRequest>, safety: PrescribeSafetyContext) => Promise<void>;
  onClose: () => void;
  formLoading: boolean;
  glassCard: React.CSSProperties;
  glassInner: React.CSSProperties;
  isDark: boolean;
  text: any;
}

/**
 * Phrases that mean "no known allergies" in free text. When the patient's
 * field reduces to one of these (after normalisation), we return zero
 * tokens so no false-positive cross-reactivity warnings fire.
 *
 * "NKDA" = No Known Drug Allergies (clinical shorthand). The other phrases
 * are common nurse / patient phrasings. We match exactly to avoid catching
 * "no penicillin" or "none specified except sulfa" — those still mean
 * something.
 */
const NO_ALLERGY_PHRASES = new Set<string>([
  'nkda',
  'nka',
  'no known allergies',
  'no known drug allergies',
  'no known allergy',
  'none',
  'none reported',
  'none on record',
  'no allergies',
  'no allergy',
  'denied',
  'denies allergies',
]);

/**
 * Brand-name → allergen-token aliases. The patient often writes a brand
 * name ("Augmentin", "Zithromax"); the formulary's allergenGroups lists
 * the generic class ("amoxicillin,penicillin,beta-lactam"). Substring
 * matching alone misses this. The aliases below expand the patient's
 * tokens with the underlying class so cross-reactivity fires correctly.
 *
 * Conservative: only mappings we're confident about. Adding a wrong
 * mapping here would create false-positive allergy banners, which would
 * train clinicians to ignore the warning — worse than missing the warning.
 */
const BRAND_ALIASES: Record<string, string[]> = {
  augmentin:    ['amoxicillin', 'penicillin', 'beta-lactam', 'clavulanate'],
  amoxil:       ['amoxicillin', 'penicillin', 'beta-lactam'],
  cipro:        ['ciprofloxacin', 'fluoroquinolone', 'quinolone'],
  ciproxin:     ['ciprofloxacin', 'fluoroquinolone', 'quinolone'],
  rocephin:     ['ceftriaxone', 'cephalosporin', 'beta-lactam'],
  zithromax:    ['azithromycin', 'macrolide'],
  klacid:       ['clarithromycin', 'macrolide'],
  flagyl:       ['metronidazole', 'nitroimidazole'],
  bactrim:      ['cotrimoxazole', 'sulfa', 'sulfonamide', 'trimethoprim'],
  septrin:      ['cotrimoxazole', 'sulfa', 'sulfonamide', 'trimethoprim'],
  vibramycin:   ['doxycycline', 'tetracycline'],
  panadol:      ['paracetamol', 'acetaminophen'],
  tylenol:      ['paracetamol', 'acetaminophen'],
  brufen:       ['ibuprofen', 'nsaid'],
  advil:        ['ibuprofen', 'nsaid'],
  nurofen:      ['ibuprofen', 'nsaid'],
  voltaren:     ['diclofenac', 'nsaid'],
  cataflam:     ['diclofenac', 'nsaid'],
  aspirin:      ['aspirin', 'salicylate', 'asa', 'nsaid'],
  asa:          ['aspirin', 'salicylate', 'nsaid'],
  ventolin:     ['salbutamol', 'beta-agonist'],
  albuterol:    ['salbutamol', 'beta-agonist'],
  morphine:     ['morphine', 'opioid'],
  pethidine:    ['pethidine', 'opioid'],
  tramal:       ['tramadol', 'opioid'],
  codeine:      ['codeine', 'opioid'],
  insulin:      ['insulin'],
};

/**
 * Tokenise the patient's free-text knownAllergies into lowercase tokens
 * suitable for cross-reactivity matching. Returns [] when the field
 * reduces to one of the "no known allergies" phrases — avoids the
 * substring "no" matching against "no...something".
 *
 * Each token is also expanded via BRAND_ALIASES so a patient who wrote
 * "Augmentin" gets the underlying class tokens (penicillin, beta-lactam)
 * checked against the formulary's allergen_groups.
 */
function tokenizeAllergies(raw: string | null | undefined): string[] {
  if (!raw) return [];
  const normalized = raw.toLowerCase().trim();
  if (!normalized) return [];
  // Whole-string match against the no-allergy phrases first — they
  // override any token-level interpretation.
  if (NO_ALLERGY_PHRASES.has(normalized)) return [];
  // Multiple separator forms; collapse extra whitespace.
  const rawTokens = normalized
    .split(/[,;\n/]+|\bplus\b|\band\b|\s{2,}/)
    .map((s) => s.replace(/[()]/g, ' ').trim())
    .map((s) => s.replace(/\s+/g, ' '))
    .filter((s) => s.length >= 3);
  // Drop any token that's itself a no-allergy phrase (e.g. an entry like
  // "shellfish, none reported, peanuts" — keep shellfish and peanuts,
  // drop the "none reported").
  const tokens = rawTokens.filter((t) => !NO_ALLERGY_PHRASES.has(t));
  // Expand brand names to their underlying class tokens. We keep the
  // original token AND add the aliases — both might match different
  // allergen groups in the formulary.
  const expanded = new Set<string>(tokens);
  for (const t of tokens) {
    // Try the full token, plus the first word (brand names are usually
    // single-word, e.g. "augmentin 1g daily" → "augmentin").
    const firstWord = t.split(' ')[0];
    const candidates = [t, firstWord];
    for (const c of candidates) {
      if (BRAND_ALIASES[c]) {
        for (const a of BRAND_ALIASES[c]) expanded.add(a);
      }
    }
  }
  return Array.from(expanded);
}

/**
 * Returns the list of allergen-group tokens from the formulary that overlap
 * with the patient's known allergies.
 *
 * Matching strategy (most-specific first):
 *   1. Exact equality between patient token and drug allergen token —
 *      strongest signal, lowest false-positive rate
 *   2. Whole-word substring match: drug allergen appears as a complete
 *      word inside the patient token, or vice versa. "penicillin" inside
 *      "penicillin g" matches. "ami" inside "amikacin" doesn't (not a
 *      word boundary).
 *   3. Substring fallback ONLY for tokens ≥ 5 characters — protects
 *      against e.g. "ica" inside "ciprofloxacin" or "iv" matching anything
 *      with "iv" in it.
 *
 * Every match path is bidirectional. Cross-reactivity is encoded by
 * overlapping token sets in the formulary's allergen_groups column
 * ("amoxicillin,penicillin,beta-lactam" so any of the three triggers).
 */
function detectAllergyMatches(drug: DrugFormulary | null, patient: PatientResponse | null): string[] {
  if (!drug || !patient) return [];
  const allergyTokens = tokenizeAllergies(patient.knownAllergies);
  if (allergyTokens.length === 0) return [];
  const drugAllergens = (drug.allergenGroups || '')
    .toLowerCase()
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
  if (drugAllergens.length === 0) return [];

  const matches = new Set<string>();
  for (const patientToken of allergyTokens) {
    for (const drugToken of drugAllergens) {
      if (patientToken === drugToken) {
        matches.add(drugToken);
        continue;
      }
      // Whole-word match: \bdrugToken\b inside patientToken, or vice versa.
      const reDrug = new RegExp(`\\b${escapeRegex(drugToken)}\\b`);
      const rePatient = new RegExp(`\\b${escapeRegex(patientToken)}\\b`);
      if (reDrug.test(patientToken) || rePatient.test(drugToken)) {
        matches.add(drugToken);
        continue;
      }
      // Substring fallback only for non-trivial tokens — protects against
      // 2- or 3-letter false positives ("iv", "ami", "no").
      if (drugToken.length >= 5 && patientToken.length >= 5) {
        if (drugToken.includes(patientToken) || patientToken.includes(drugToken)) {
          matches.add(drugToken);
        }
      }
    }
  }
  return Array.from(matches);
}

/** Escape special regex characters in a literal string. */
function escapeRegex(s: string): string {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/**
 * Derives the recommended pediatric single dose from formulary + weight.
 * Returns null when we can't compute (no weight, no peds range, etc.).
 * Uses the midpoint of the mg/kg range so the suggestion is centred.
 */
function pediatricDoseRecommendation(
  drug: DrugFormulary | null,
  weightKg: number | null | undefined,
): { perKg: number; total: number; rangeLabel: string; unit: string } | null {
  if (!drug || !weightKg || weightKg <= 0) return null;
  const min = drug.pediatricMinDoseMgPerKg;
  const max = drug.pediatricMaxDoseMgPerKg;
  if (min == null && max == null) return null;
  // Centre of the range; if only one side defined, use it directly.
  const perKg = min != null && max != null ? (min + max) / 2 : (min ?? max!);
  const total = +(perKg * weightKg).toFixed(1);
  const unit = doseUnitLabel(drug.doseUnit);
  const rangeLabel = min != null && max != null
    ? `${min}–${max} ${unit}/kg`
    : `${perKg} ${unit}/kg`;
  return { perKg, total, rangeLabel, unit };
}

/** Adult typical-dose label, using the drug's canonical unit. */
function adultDoseLabel(drug: DrugFormulary | null): string | null {
  if (!drug) return null;
  const min = drug.adultMinDoseMg;
  const max = drug.adultMaxDoseMg;
  if (min == null && max == null) return null;
  const unit = doseUnitLabel(drug.doseUnit);
  if (min != null && max != null && min !== max) return `${min}–${max} ${unit}`;
  return `${min ?? max} ${unit}`;
}

export function PrescribePanel({
  visitId, patient, latestTriage, onSubmit, onClose, formLoading,
  glassCard, glassInner, isDark, text,
}: Props) {
  // ── Search & autocomplete ──
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<DrugFormulary[]>([]);
  const [searching, setSearching] = useState(false);
  const [selected, setSelected] = useState<DrugFormulary | null>(null);
  // Prevents an in-flight search response from over-writing a newer one.
  const searchSeq = useRef(0);

  // ── Form fields ──
  const [dose, setDose] = useState('');
  const [route, setRoute] = useState<MedicationRoute>('PO');
  const [frequency, setFrequency] = useState('');
  const [notes, setNotes] = useState('');
  // Required when an allergy or pregnancy hazard is detected. Captured
  // in the form so it can be included on the audit-trail override record
  // without a second prompt after submit.
  const [overrideReason, setOverrideReason] = useState('');

  // ── Reorder ──
  const [history, setHistory] = useState<MedicationResponse[]>([]);
  const [showHistory, setShowHistory] = useState(false);

  // Fetch patient med history once (one-tap reorder)
  useEffect(() => {
    if (!patient?.id) return;
    let cancelled = false;
    medicationApi
      .getPatientHistory(patient.id)
      .then((rows) => { if (!cancelled) setHistory(Array.isArray(rows) ? rows : []); })
      .catch(() => { /* non-fatal — reorder simply won't be available */ });
    return () => { cancelled = true; };
  }, [patient?.id]);

  // Debounced formulary search (250ms)
  useEffect(() => {
    if (selected) return; // Once a drug is selected, stop searching
    const trimmed = query.trim();
    if (trimmed.length < 2) {
      setResults([]);
      setSearching(false);
      return;
    }
    const seq = ++searchSeq.current;
    setSearching(true);
    const timer = setTimeout(() => {
      medsafetyApi
        .searchFormulary(trimmed)
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

  // ── Pre-fill on selection ──
  // Defaults intentionally chosen to be *safe and editable*. Doctor must
  // still see and confirm the dose before submit; we pre-fill so the
  // common case is one click rather than three lookups.
  const handleSelectDrug = useCallback((drug: DrugFormulary) => {
    setSelected(drug);
    setQuery(drug.genericName);
    // Pre-select first available route.
    const firstRoute = (drug.availableRoutes || '').split(',').map((s) => s.trim()).filter(Boolean)[0];
    if (firstRoute && (ALL_ROUTES as string[]).includes(firstRoute)) {
      setRoute(firstRoute as MedicationRoute);
    }
    // Pre-fill dose using the drug's canonical unit (mg/units/IU/g/etc.).
    // Pediatric calc if patient is pediatric and weight known, else use
    // adult midpoint. Values are formatted with the right unit suffix
    // — pre-filling "10 mg" of insulin when the canonical dose is units
    // would mislead the doctor catastrophically.
    const unitLabel = doseUnitLabel(drug.doseUnit);
    if (patient?.isPediatric) {
      const ped = pediatricDoseRecommendation(drug, latestTriage?.weightKg);
      if (ped) {
        setDose(`${ped.total} ${ped.unit}`);
      } else {
        // No peds data — leave blank rather than pre-fill an adult dose
        // for a child. Doctor must enter explicitly.
        setDose('');
      }
    } else {
      const min = drug.adultMinDoseMg;
      const max = drug.adultMaxDoseMg;
      if (min != null && max != null) {
        const mid = Math.round((min + max) / 2);
        setDose(`${mid} ${unitLabel}`);
      } else if (min != null) {
        setDose(`${min} ${unitLabel}`);
      } else if (max != null) {
        setDose(`${max} ${unitLabel}`);
      } else {
        setDose('');
      }
    }
  }, [patient?.isPediatric, latestTriage?.weightKg]);

  const handleClearDrug = useCallback(() => {
    setSelected(null);
    setQuery('');
    setResults([]);
    setDose('');
    setFrequency('');
  }, []);

  // ── One-tap reorder from a past prescription ──
  const handleReorder = useCallback((past: MedicationResponse) => {
    setQuery(past.drugName);
    setDose(past.dose ?? '');
    if (past.route && (ALL_ROUTES as string[]).includes(past.route)) {
      setRoute(past.route as MedicationRoute);
    }
    setFrequency(past.frequency ?? '');
    setNotes('');
    setShowHistory(false);
    // Try to find the drug in the formulary so we still get safety checks.
    medsafetyApi.searchFormulary(past.drugName).then((rows) => {
      const exact = (rows || []).find(
        (r) => r.genericName.toLowerCase() === past.drugName.toLowerCase(),
      );
      if (exact) setSelected(exact);
    }).catch(() => { /* non-fatal */ });
  }, []);

  // ── Allergy precheck (client-side, real-time) ──
  const allergyMatches = useMemo(
    () => detectAllergyMatches(selected, patient),
    [selected, patient],
  );
  const hasAllergyConflict = allergyMatches.length > 0;

  // ── Pediatric dose calculation hint ──
  const pedRec = useMemo(
    () => pediatricDoseRecommendation(selected, latestTriage?.weightKg),
    [selected, latestTriage?.weightKg],
  );

  // ── Pregnancy / teratogen hazard ──
  // Fires when the selected drug's pregnancy_category is D or X and the
  // patient's pregnancy_status is one of PREGNANT / POSSIBLY_PREGNANT /
  // BREASTFEEDING. Drives a separate red banner and feeds into the
  // override-required flow alongside the allergy check. The data is on
  // every drug we seed; the warning is what was missing.
  const pregnancyHazard = useMemo(
    () => detectPregnancyHazard(selected, patient),
    [selected, patient],
  );

  // ── Combined override gate ──
  // Both allergy and pregnancy hazards require the doctor to record a
  // reason before proceeding. We show the reason field, gate submit on
  // a non-empty reason, and pass the reason + a "kind" tag through to
  // the parent so the safety-engine override is tagged with what the
  // doctor was overriding for.
  const overrideKind: 'allergy' | 'pregnancy' | 'allergy-and-pregnancy' | null =
    hasAllergyConflict && pregnancyHazard ? 'allergy-and-pregnancy'
    : hasAllergyConflict ? 'allergy'
    : pregnancyHazard ? 'pregnancy'
    : null;
  const needsOverride = overrideKind !== null;

  // Reset override reason when the gate clears (e.g. doctor switches to
  // a non-conflicting drug) so a stale reason can't carry into the next
  // prescription.
  useEffect(() => {
    if (!needsOverride) setOverrideReason('');
  }, [needsOverride]);

  // ── Parsed dose for validate() ──
  // Free-text dose ("500 mg", "10 mg/kg", "1 tablet") is parsed once.
  // The drug's canonical dose unit drives the parse: for non-mg drugs
  // (insulin units, oxytocin IU, ORS sachets, etc.) we deliberately
  // return null so the safety engine skips numeric-mg validation. null
  // is a real, useful value here — guessing wrong would silently pass a
  // dose that should have been flagged.
  const doseMg = useMemo(
    () => parseDoseToMg(dose, selected?.doseUnit),
    [dose, selected?.doseUnit],
  );

  // ── Submit handler ──
  const canSubmit =
    !!patient
    && !!query.trim()
    && !!dose.trim()
    && !!frequency.trim()
    && !formLoading
    && (!needsOverride || overrideReason.trim().length >= 5);

  const handleSubmit = useCallback(async () => {
    if (!canSubmit) return;
    await onSubmit(
      {
        visitId,
        drugName: selected?.genericName ?? query.trim(),
        dose: dose.trim(),
        route,
        frequency: frequency.trim(),
        notes: notes.trim() || undefined,
      },
      {
        doseMg,
        weightKg: latestTriage?.weightKg ?? null,
        overrideReason: needsOverride ? overrideReason.trim() : '',
        overrideKind,
      },
    );
  }, [
    canSubmit, onSubmit, visitId, selected, query, dose, route, frequency,
    notes, doseMg, latestTriage?.weightKg, overrideReason, needsOverride, overrideKind,
  ]);

  const showRoutes = useMemo(() => {
    // If the formulary entry lists routes, prefer those; else show all.
    if (!selected?.availableRoutes) return ALL_ROUTES;
    const filtered = selected.availableRoutes
      .split(',')
      .map((s) => s.trim())
      .filter((s): s is MedicationRoute => (ALL_ROUTES as string[]).includes(s));
    return filtered.length > 0 ? filtered : ALL_ROUTES;
  }, [selected]);

  return (
    <div className="rounded-2xl p-5 animate-fade-up space-y-4" style={glassCard}>
      <div className="flex items-center justify-between">
        <h4 className={`text-sm font-bold flex items-center gap-2 ${text.heading}`}>
          <Pill className="w-4 h-4 text-cyan-500" />
          Prescribe Medication
        </h4>
        <div className="flex items-center gap-2">
          {history.length > 0 && (
            <button
              type="button"
              onClick={() => setShowHistory((v) => !v)}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-[11px] font-bold rounded-lg bg-violet-500/10 text-violet-500 hover:bg-violet-500/20 transition-colors"
            >
              <History className="w-3.5 h-3.5" /> Reorder
              <ChevronDown className={`w-3 h-3 transition-transform ${showHistory ? 'rotate-180' : ''}`} />
            </button>
          )}
          <button
            type="button"
            onClick={onClose}
            className={`p-1.5 rounded-lg ${text.muted} hover:bg-white/5`}
            aria-label="Close prescribe panel"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      </div>

      {/* ── Patient profile unavailable: hard warning + submit blocked ── */}
      {!patient && (
        <div className="rounded-xl border border-amber-500/30 bg-amber-500/10 p-3 flex items-start gap-2">
          <AlertTriangle className="w-4 h-4 text-amber-600 mt-0.5 flex-shrink-0" />
          <div className="text-xs">
            <p className={`font-bold ${isDark ? 'text-amber-300' : 'text-amber-800'}`}>Patient profile unavailable</p>
            <p className={isDark ? 'text-amber-200/80' : 'text-amber-700'}>
              We could not load this patient's allergies or weight. Prescribing is disabled until the profile loads.
              Refresh the page or verify with the patient before continuing.
            </p>
          </div>
        </div>
      )}

      {/* ── Reorder dropdown (collapsible) ── */}
      {showHistory && history.length > 0 && (
        <div className="rounded-xl border border-violet-500/20 bg-violet-500/5 max-h-56 overflow-y-auto">
          {history.slice(0, 20).map((m) => (
            <button
              key={m.id}
              type="button"
              onClick={() => handleReorder(m)}
              className="w-full text-left px-3 py-2 hover:bg-violet-500/10 transition-colors border-b border-violet-500/10 last:border-0"
            >
              <div className="flex items-center justify-between">
                <span className={`text-xs font-bold ${text.heading}`}>{m.drugName}</span>
                <span className={`text-[10px] ${text.muted}`}>{m.route} · {m.dose || '—'}</span>
              </div>
              {m.frequency && <p className={`text-[10px] ${text.muted}`}>{m.frequency}</p>}
            </button>
          ))}
          {history.length > 20 && (
            <p className={`text-[10px] text-center py-2 ${text.muted}`}>
              Showing most recent 20 of {history.length}
            </p>
          )}
        </div>
      )}

      {/* ── Drug search / selected display ── */}
      <div>
        <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Drug</label>
        {selected ? (
          <div className="rounded-xl border border-cyan-500/30 bg-cyan-500/5 p-3 flex items-start gap-3">
            <CheckCircle2 className="w-4 h-4 text-cyan-500 mt-0.5 flex-shrink-0" />
            <div className="flex-1 min-w-0">
              <div className="flex items-center gap-2 flex-wrap">
                <span className={`text-sm font-bold ${text.heading}`}>{selected.genericName}</span>
                {selected.isOnReml && (
                  <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-emerald-500/15 text-emerald-600">REML</span>
                )}
                {selected.isHighAlert && (
                  <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-red-500/15 text-red-600">High-alert</span>
                )}
                {selected.requiresDoubleCheck && (
                  <span className="text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded bg-amber-500/15 text-amber-600">2-clinician check</span>
                )}
                {selected.pregnancyCategory && (
                  <span className={`text-[9px] font-bold uppercase tracking-wider px-1.5 py-0.5 rounded ${
                    selected.pregnancyCategory === 'D' || selected.pregnancyCategory === 'X'
                      ? 'bg-red-500/15 text-red-600'
                      : 'bg-slate-500/15 text-slate-600'
                  }`}>Preg cat {selected.pregnancyCategory}</span>
                )}
              </div>
              {selected.brandNames && (
                <p className={`text-[10px] mt-0.5 ${text.muted}`}>aka {selected.brandNames}</p>
              )}
              {selected.drugClass && (
                <p className={`text-[11px] mt-1 ${text.body}`}>{selected.drugClass}</p>
              )}
            </div>
            <button type="button" onClick={handleClearDrug} className={`p-1 rounded ${text.muted} hover:bg-white/5`} aria-label="Clear">
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
                placeholder="Type drug name (e.g. paracetamol)…"
                autoFocus
                className={`w-full pl-9 pr-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner}
              />
            </div>
            {(searching || results.length > 0) && (
              <div className="absolute z-10 mt-1 w-full rounded-xl border border-slate-200/30 max-h-60 overflow-y-auto" style={glassCard}>
                {searching && results.length === 0 && (
                  <p className={`text-xs text-center py-3 ${text.muted}`}>Searching…</p>
                )}
                {!searching && results.length === 0 && query.trim().length >= 2 && (
                  <p className={`text-xs text-center py-3 ${text.muted}`}>
                    No formulary match. You can still type a free-text drug name to prescribe — but allergy and dose checks will be limited.
                  </p>
                )}
                {results.slice(0, 10).map((r) => (
                  <button
                    key={r.id}
                    type="button"
                    onClick={() => handleSelectDrug(r)}
                    className="w-full text-left px-3 py-2 hover:bg-cyan-500/10 transition-colors border-b border-slate-200/10 last:border-0"
                  >
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className={`text-sm font-bold ${text.heading}`}>{r.genericName}</span>
                      {r.isOnReml && <span className="text-[9px] font-bold uppercase px-1 py-0.5 rounded bg-emerald-500/15 text-emerald-600">REML</span>}
                      {r.isHighAlert && <span className="text-[9px] font-bold uppercase px-1 py-0.5 rounded bg-red-500/15 text-red-600">High-alert</span>}
                    </div>
                    {r.drugClass && <p className={`text-[10px] ${text.muted}`}>{r.drugClass}</p>}
                  </button>
                ))}
              </div>
            )}
          </div>
        )}
      </div>

      {/* ── Allergy banner — must be above the form fields, never below the submit button ── */}
      {hasAllergyConflict && (
        <div className="rounded-xl border-2 border-red-500 bg-red-500/10 p-3 flex items-start gap-2 animate-pulse-slow">
          <ShieldAlert className="w-5 h-5 text-red-600 mt-0.5 flex-shrink-0" />
          <div className="flex-1 text-xs">
            <p className="font-extrabold text-red-700 uppercase tracking-wide">
              Allergy conflict — verify before prescribing
            </p>
            <p className={`mt-1 ${isDark ? 'text-red-200' : 'text-red-800'}`}>
              Patient's known allergies overlap with this drug's class: <strong>{allergyMatches.join(', ')}</strong>.
            </p>
            <p className={`mt-1 ${isDark ? 'text-red-200/80' : 'text-red-700'}`}>
              Patient: <em>{patient?.knownAllergies}</em>
            </p>
          </div>
        </div>
      )}

      {/* ── Pregnancy / teratogen banner ──
          Fires for Category D / X drugs prescribed to PREGNANT,
          POSSIBLY_PREGNANT, or BREASTFEEDING patients. Severity styling
          mirrors the allergy banner for high-severity hazards (Cat X
          for pregnancy, Cat D for confirmed PREGNANT) and steps down
          to amber for moderate cases (Cat D for POSSIBLY_PREGNANT, or
          Cat X / D in breastfeeding where the category is a proxy for
          lactation safety rather than a strict ban). */}
      {pregnancyHazard && (
        <div
          className={`rounded-xl border-2 p-3 flex items-start gap-2 ${
            pregnancyHazard.severity === 'high'
              ? 'border-red-500 bg-red-500/10 animate-pulse-slow'
              : 'border-amber-500 bg-amber-500/10'
          }`}
        >
          <Baby
            className={`w-5 h-5 mt-0.5 flex-shrink-0 ${
              pregnancyHazard.severity === 'high' ? 'text-red-600' : 'text-amber-600'
            }`}
          />
          <div className="flex-1 text-xs">
            <p
              className={`font-extrabold uppercase tracking-wide ${
                pregnancyHazard.severity === 'high' ? 'text-red-700' : 'text-amber-700'
              }`}
            >
              {pregnancyHazard.severity === 'high' ? 'Teratogen risk — review before prescribing' : 'Pregnancy caution'}
            </p>
            <p
              className={`mt-1 ${
                pregnancyHazard.severity === 'high'
                  ? isDark ? 'text-red-200' : 'text-red-800'
                  : isDark ? 'text-amber-200' : 'text-amber-800'
              }`}
            >
              {pregnancyHazard.message}
            </p>
            <p
              className={`mt-1 ${
                pregnancyHazard.severity === 'high'
                  ? isDark ? 'text-red-200/80' : 'text-red-700'
                  : isDark ? 'text-amber-200/80' : 'text-amber-700'
              }`}
            >
              Patient pregnancy status: <strong>{pregnancyHazard.status.replace(/_/g, ' ')}</strong>
              {' · '}Drug pregnancy category: <strong>{pregnancyHazard.category}</strong>
            </p>
          </div>
        </div>
      )}

      {/* ── Form fields — only show once a drug is chosen or typed ── */}
      {(selected || query.trim().length >= 2) && (
        <>
          {/* Dose row with pediatric calc / adult-range hints */}
          <div>
            <div className="flex items-center justify-between mb-1.5">
              <label className={`block text-[10px] font-bold uppercase tracking-wider ${text.label}`}>Dose</label>
              {selected && (
                <span className={`text-[10px] ${text.muted}`}>
                  {patient?.isPediatric
                    ? selected.pediatricMinDoseMgPerKg != null
                      ? `Pediatric typical: ${selected.pediatricMinDoseMgPerKg}–${selected.pediatricMaxDoseMgPerKg ?? selected.pediatricMinDoseMgPerKg} ${doseUnitLabel(selected.doseUnit)}/kg`
                      : 'No pediatric range — verify dose'
                    : adultDoseLabel(selected)
                      ? `Adult typical: ${adultDoseLabel(selected)}`
                      : 'No adult range on file — verify dose'}
                </span>
              )}
            </div>
            <input
              value={dose}
              onChange={(e) => setDose(e.target.value)}
              placeholder={selected
                ? `e.g. "${(selected.adultMinDoseMg ?? selected.adultMaxDoseMg ?? 1)} ${doseUnitLabel(selected.doseUnit)}"`
                : 'e.g. "500 mg" or "10 mg/kg"'}
              className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
              style={glassInner}
            />
            {/* Pediatric calc — one-tap accept */}
            {pedRec && patient?.isPediatric && (
              <button
                type="button"
                onClick={() => setDose(`${pedRec.total} ${pedRec.unit}`)}
                className="mt-2 inline-flex items-center gap-1.5 px-2.5 py-1 text-[11px] font-bold rounded-lg bg-blue-500/10 text-blue-600 hover:bg-blue-500/20 transition-colors"
              >
                <Calculator className="w-3 h-3" />
                Pediatric: {pedRec.rangeLabel} × {latestTriage?.weightKg} kg = {pedRec.total} {pedRec.unit}
              </button>
            )}
            {/* Adult quick-pick chips */}
            {selected && !patient?.isPediatric && selected.adultMinDoseMg != null && selected.adultMaxDoseMg != null && (
              <div className="mt-2 flex items-center gap-1.5 flex-wrap">
                {(() => {
                  const u = doseUnitLabel(selected.doseUnit);
                  return [selected.adultMinDoseMg, Math.round((selected.adultMinDoseMg + selected.adultMaxDoseMg) / 2), selected.adultMaxDoseMg].map((d, i) => (
                    <button
                      key={i}
                      type="button"
                      onClick={() => setDose(`${d} ${u}`)}
                      className="px-2 py-0.5 text-[10px] font-bold rounded bg-cyan-500/10 text-cyan-600 hover:bg-cyan-500/20 transition-colors"
                    >
                      {d} {u}
                    </button>
                  ));
                })()}
              </div>
            )}
          </div>

          {/* Route + Frequency */}
          <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Route</label>
              <select
                value={route}
                onChange={(e) => setRoute(e.target.value as MedicationRoute)}
                className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white' : 'text-slate-800'}`}
                style={glassInner}
              >
                {showRoutes.map((r) => (
                  <option key={r} value={r}>{ROUTE_LABELS[r]} ({r})</option>
                ))}
              </select>
            </div>
            <div>
              <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Frequency</label>
              <input
                value={frequency}
                onChange={(e) => setFrequency(e.target.value)}
                placeholder="e.g. STAT, TDS, Q6H, PRN"
                className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
                style={glassInner}
              />
              <div className="mt-2 flex items-center gap-1 flex-wrap">
                {COMMON_FREQUENCIES.map((f) => (
                  <button
                    key={f}
                    type="button"
                    onClick={() => setFrequency(f)}
                    className={`px-2 py-0.5 text-[10px] font-bold rounded transition-colors ${
                      frequency === f
                        ? 'bg-cyan-500 text-white'
                        : 'bg-cyan-500/10 text-cyan-600 hover:bg-cyan-500/20'
                    }`}
                  >
                    {f}
                  </button>
                ))}
              </div>
            </div>
          </div>

          {/* Optional notes */}
          <div>
            <label className={`block text-[10px] font-bold uppercase tracking-wider mb-1.5 ${text.label}`}>Notes (optional)</label>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Indication, special instructions…"
              rows={2}
              className={`w-full px-3 py-2.5 rounded-xl text-sm outline-none resize-none ${isDark ? 'text-white placeholder-slate-500' : 'text-slate-800 placeholder-slate-400'}`}
              style={glassInner}
            />
          </div>

          {/* Contraindications / interactions — informational, below the form */}
          {selected && (selected.contraindications || selected.majorInteractions) && (
            <div className="rounded-xl border border-amber-500/20 bg-amber-500/5 p-2.5 text-[11px] space-y-1">
              {selected.contraindications && (
                <p><strong className="text-amber-700">Contraindications:</strong> <span className={isDark ? 'text-amber-200/80' : 'text-amber-800'}>{selected.contraindications}</span></p>
              )}
              {selected.majorInteractions && (
                <p><strong className="text-amber-700">Major interactions:</strong> <span className={isDark ? 'text-amber-200/80' : 'text-amber-800'}>{selected.majorInteractions}</span></p>
              )}
            </div>
          )}

          {/* ── Override-reason field ──
              Surfaces only when there's a known allergy conflict or
              pregnancy/teratogen hazard. The reason is required
              (≥ 5 chars) and is sent to the safety engine's override
              endpoint after prescribe + validate, so the audit trail
              records *why* the doctor chose to proceed despite the
              flagged risk. Without this, an "override and prescribe"
              click would land in the medications list with no
              recorded justification — exactly the kind of silent
              audit gap that defeats the safety engine. */}
          {needsOverride && (
            <div className="rounded-xl border border-red-500/30 bg-red-500/5 p-3 space-y-2">
              <label className="block text-[10px] font-bold uppercase tracking-wider text-red-700">
                Override Reason — required
                <span className={`ml-2 font-normal normal-case ${text.muted}`}>
                  {overrideKind === 'allergy-and-pregnancy'
                    ? '(allergy + pregnancy hazard both present)'
                    : overrideKind === 'allergy' ? '(allergy conflict)' : '(pregnancy hazard)'}
                </span>
              </label>
              <textarea
                value={overrideReason}
                onChange={(e) => setOverrideReason(e.target.value)}
                placeholder="Document the clinical reasoning: e.g. 'No safe alternative; benefit clearly outweighs risk; informed consent obtained.' Minimum 5 characters."
                rows={2}
                className={`w-full px-3 py-2.5 rounded-lg text-sm outline-none resize-none ${
                  isDark ? 'text-white placeholder-slate-500 bg-slate-900/50' : 'text-slate-800 placeholder-slate-400 bg-white'
                } border border-red-500/40 focus:border-red-500`}
              />
              {overrideReason.trim().length > 0 && overrideReason.trim().length < 5 && (
                <p className="text-[10px] font-medium text-red-600">
                  Please write a fuller reason — short text is hard to interpret in an audit review.
                </p>
              )}
            </div>
          )}

          {/* Action row */}
          <div className="flex items-center gap-3">
            <button
              type="button"
              onClick={handleSubmit}
              disabled={!canSubmit}
              className={`inline-flex items-center gap-2 px-5 py-2.5 rounded-xl text-xs font-bold shadow-lg hover:-translate-y-0.5 transition-all disabled:opacity-50 disabled:cursor-not-allowed ${
                needsOverride
                  ? 'bg-gradient-to-r from-red-600 to-red-700 text-white'
                  : 'bg-gradient-to-r from-slate-800 to-slate-700 text-white'
              }`}
            >
              {formLoading
                ? <Loader2 className="w-3.5 h-3.5 animate-spin" />
                : needsOverride ? <ShieldAlert className="w-3.5 h-3.5" /> : <Send className="w-3.5 h-3.5" />}
              {needsOverride
                ? overrideKind === 'allergy-and-pregnancy' ? 'Override and prescribe'
                  : overrideKind === 'allergy' ? 'Override allergy and prescribe'
                  : 'Override pregnancy caution and prescribe'
                : 'Prescribe'}
            </button>
            <button type="button" onClick={onClose} className={`px-4 py-2.5 text-xs font-bold rounded-xl ${text.muted}`}>Cancel</button>
            {selected && (
              <span className={`ml-auto inline-flex items-center gap-1 text-[10px] ${text.muted}`}>
                <Sparkles className="w-3 h-3 text-cyan-500" />
                Pre-filled from REML
              </span>
            )}
          </div>
        </>
      )}
    </div>
  );
}
