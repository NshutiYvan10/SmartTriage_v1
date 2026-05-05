/**
 * pediatricDoseCheck — flag prescribed doses that fall outside the
 * weight-based mg/kg range for paediatric patients.
 *
 * Why this exists:
 *   Paediatric dosing is the most error-prone step in ED prescribing.
 *   The same drug at the same dose label ("500 mg") is therapeutic for
 *   a 25 kg child and a tenfold overdose for a 3 kg neonate. The cause
 *   is almost always cognitive: the prescriber knows the adult dose
 *   reflexively and forgets to scale by weight. A simple bound check —
 *   "this dose is >2× what 0–18 kg/kg recommends" — catches the
 *   majority of decimal-shift and "I forgot the patient is a child"
 *   errors before the order leaves the screen.
 *
 *   Backend with a full paediatric formulary (Lexicomp Paediatric, BNFc)
 *   is the long-term answer. This utility is the bridge: a hardcoded,
 *   high-value subset covering the top ED paediatric drugs, scoped to
 *   single-dose mg/kg ranges. Frequency adjustment is intentionally
 *   excluded — most overdose errors are per-dose, not per-day.
 *
 * Strategy:
 *   1. Match the prescribed drug to a rule by keyword substring.
 *   2. Parse a numeric mg out of the free-text dose string.
 *   3. Compute mg/kg using triage-recorded weight.
 *   4. Compare against the rule's [minMgPerKg, maxMgPerKg] range.
 *
 * Calibrated for a usable signal-to-noise ratio:
 *   - Drugs we don't recognize → no match (silent skip).
 *   - Doses we can't parse (e.g. "1 tab", "2 puffs", "5 ml") → null
 *     (silent skip — better than a false positive that the prescriber
 *     learns to dismiss).
 *   - Weight-less or non-paediatric visits → caller skips us entirely.
 *
 * Out of scope (intentionally):
 *   - Total-daily-dose checks (would need to parse frequency reliably).
 *   - Body surface area dosing (chemotherapy, etc. — not ED scope).
 *   - Renal/hepatic adjustment (separate phase).
 *   - Route-specific rules (IV vs PO different ranges).
 *
 * Two failure modes to be aware of:
 *   - False negative on unparseable dose strings — accepted: backend
 *     formulary will catch when wired up.
 *   - False positive on weight-based orders ("10 mg/kg") that the
 *     prescriber wrote as the per-kg dose, not the total. We detect
 *     the "/kg" token in the dose string and skip in that case.
 */

export type DoseSeverity = 'overdose' | 'underdose';

export interface DoseMatch {
  /** Drug name as the clinician typed it. */
  drugName: string;
  /** Free-text dose string the clinician typed. */
  doseString: string;
  /** Parsed total dose in milligrams (single dose). */
  prescribedMg: number;
  /** Patient weight used for the calculation (kg). */
  weightKg: number;
  /** Computed mg/kg for this single dose. */
  prescribedMgPerKg: number;
  /** Lower bound of the recommended single-dose mg/kg range. */
  minMgPerKg: number;
  /** Upper bound of the recommended single-dose mg/kg range. */
  maxMgPerKg: number;
  /** Whether the dose is too high or too low. */
  severity: DoseSeverity;
  /** Drug-class label shown in the dialog. */
  ruleLabel: string;
  /** How many times the upper bound the dose is (only for overdose).
   *  Surfaces "this is 3× the max" to make magnitude obvious. */
  foldOverMax?: number;
}

interface DoseRule {
  /** Lowercase substrings — match if any appears in the drug name. */
  keywords: string[];
  /** Human label shown in the warning ("Paracetamol", "Ibuprofen"). */
  label: string;
  /** Lower bound of single-dose mg/kg. */
  minMgPerKg: number;
  /** Upper bound of single-dose mg/kg. Doses above this fire overdose. */
  maxMgPerKg: number;
}

/**
 * Top ED paediatric drugs with single-dose mg/kg ranges. Sources:
 * BNFc, WHO IMCI, RCH (Melbourne) paediatric guidelines. Conservative
 * upper bounds — the upper bound is the lowest commonly-cited maximum
 * single dose so the warning fires before any reasonable formulary
 * would consider the dose safe.
 *
 * Ranges are SINGLE-DOSE, not daily. A daily-dose label that happens
 * to match a single-dose number is the prescriber's responsibility to
 * recognize — but we don't synthesize a frequency-aware total because
 * frequency parsing from free text is unreliable.
 */
const DOSE_RULES: DoseRule[] = [
  // Analgesics / antipyretics
  { keywords: ['paracetamol', 'acetaminophen'], label: 'Paracetamol', minMgPerKg: 10, maxMgPerKg: 15 },
  { keywords: ['ibuprofen'],                    label: 'Ibuprofen',   minMgPerKg: 5,  maxMgPerKg: 10 },
  { keywords: ['diclofenac'],                   label: 'Diclofenac',  minMgPerKg: 0.3, maxMgPerKg: 1 },

  // Antibiotics — single-dose ranges (multiply by frequency for daily)
  { keywords: ['amoxicillin'],                  label: 'Amoxicillin',     minMgPerKg: 15, maxMgPerKg: 30 },
  { keywords: ['ampicillin'],                   label: 'Ampicillin',      minMgPerKg: 25, maxMgPerKg: 50 },
  { keywords: ['ceftriaxone'],                  label: 'Ceftriaxone',     minMgPerKg: 50, maxMgPerKg: 80 },
  { keywords: ['cefotaxime'],                   label: 'Cefotaxime',      minMgPerKg: 25, maxMgPerKg: 50 },
  { keywords: ['gentamicin'],                   label: 'Gentamicin',      minMgPerKg: 5,  maxMgPerKg: 7.5 },
  { keywords: ['azithromycin'],                 label: 'Azithromycin',    minMgPerKg: 5,  maxMgPerKg: 10 },
  { keywords: ['erythromycin'],                 label: 'Erythromycin',    minMgPerKg: 7.5, maxMgPerKg: 12.5 },
  { keywords: ['metronidazole', 'flagyl'],      label: 'Metronidazole',   minMgPerKg: 7.5, maxMgPerKg: 10 },
  { keywords: ['cotrimoxazole', 'co-trimoxazole', 'septrin', 'bactrim'], label: 'Co-trimoxazole (TMP component)', minMgPerKg: 4, maxMgPerKg: 6 },

  // Respiratory
  { keywords: ['salbutamol', 'albuterol'],      label: 'Salbutamol (neb)', minMgPerKg: 0.1, maxMgPerKg: 0.15 },
  { keywords: ['prednisolone', 'prednisone'],   label: 'Prednisolone',     minMgPerKg: 1,   maxMgPerKg: 2 },
  { keywords: ['dexamethasone'],                label: 'Dexamethasone',    minMgPerKg: 0.15, maxMgPerKg: 0.6 },

  // Emergency / critical
  // Adrenaline IM 0.01 mg/kg (1:1000). The range is intentionally
  // tight — anything outside is almost certainly an error.
  { keywords: ['adrenaline', 'epinephrine'],    label: 'Adrenaline (IM)',  minMgPerKg: 0.005, maxMgPerKg: 0.01 },
  { keywords: ['hydrocortisone'],               label: 'Hydrocortisone',   minMgPerKg: 2, maxMgPerKg: 4 },
  { keywords: ['midazolam'],                    label: 'Midazolam',        minMgPerKg: 0.05, maxMgPerKg: 0.2 },
  { keywords: ['diazepam'],                     label: 'Diazepam',         minMgPerKg: 0.1, maxMgPerKg: 0.3 },
  { keywords: ['lorazepam'],                    label: 'Lorazepam',        minMgPerKg: 0.05, maxMgPerKg: 0.1 },
  { keywords: ['phenobarbital', 'phenobarbitone'], label: 'Phenobarbital', minMgPerKg: 15, maxMgPerKg: 20 },
  { keywords: ['phenytoin'],                    label: 'Phenytoin (load)', minMgPerKg: 15, maxMgPerKg: 20 },
  { keywords: ['ondansetron'],                  label: 'Ondansetron',      minMgPerKg: 0.1, maxMgPerKg: 0.15 },
  { keywords: ['morphine'],                     label: 'Morphine (IV)',    minMgPerKg: 0.05, maxMgPerKg: 0.1 },
  { keywords: ['fentanyl'],                     label: 'Fentanyl (IV)',    minMgPerKg: 0.001, maxMgPerKg: 0.002 },

  // Antimalarials / antiparasitics (high local relevance)
  { keywords: ['artesunate'],                   label: 'Artesunate (IV)',  minMgPerKg: 2.4, maxMgPerKg: 3 },
  { keywords: ['quinine'],                      label: 'Quinine',          minMgPerKg: 8, maxMgPerKg: 10 },

  // Other common
  { keywords: ['furosemide', 'frusemide', 'lasix'], label: 'Furosemide',   minMgPerKg: 0.5, maxMgPerKg: 1 },
  { keywords: ['ranitidine'],                       label: 'Ranitidine',   minMgPerKg: 1, maxMgPerKg: 2 },
  { keywords: ['omeprazole'],                       label: 'Omeprazole',   minMgPerKg: 0.5, maxMgPerKg: 1 },
];

/**
 * Parse a free-text dose string into milligrams. Returns null when the
 * input doesn't look like a mass-based dose we can reason about (e.g.
 * "1 tab", "2 puffs", "5 ml without strength").
 *
 * Handles:
 *   - "500mg", "500 mg", "500MG"
 *   - "0.5g", "1.5 g"  → converted to mg
 *   - "250mcg", "250 µg" → converted to mg
 *   - leading whitespace, trailing periods
 *
 * Bails out (returns null) on:
 *   - dose strings containing "/kg" (already weight-normalised)
 *   - tablet / capsule / drop / spray / puff / patch counts
 *   - millilitres without a strength
 *   - empty / whitespace-only input
 *
 * The bail-out path is wide on purpose. False positives ("you wrote
 * 5ml = overdose!") teach the prescriber to dismiss the dialog. False
 * negatives (we silently skip an unparseable dose) leave the safety
 * net to the backend and to the eyeballs reading the order.
 */
export function parseDoseMg(doseStr: string | null | undefined): number | null {
  if (!doseStr) return null;
  const s = doseStr.trim().toLowerCase();
  if (!s) return null;

  // Already weight-normalised — the clinician wrote "10 mg/kg", which
  // is the per-kg figure, not a total. We can't re-derive total without
  // assuming the frequency, so bail.
  if (s.includes('/kg') || s.includes(' per kg')) return null;

  // Volume-based ("5ml") or count-based dose forms — no mass, can't check.
  if (/\b(tab|tablet|cap|capsule|drop|drops|spray|puff|patch|sachet|lozenge)s?\b/.test(s)) return null;
  // ml/L without an mg figure → unparseable
  if (/\d+\s*m?l\b/.test(s) && !/\d+\s*(mg|g|mcg|µg|microgram)/.test(s)) return null;

  // Pull the first numeric + unit pair we recognize. The number may
  // be decimal (.5, 0.5, 1.5).
  const re = /(\d+(?:\.\d+)?)\s*(mcg|µg|microgram|mg|g)\b/;
  const m = s.match(re);
  if (!m) return null;

  const value = parseFloat(m[1]);
  if (!isFinite(value) || value <= 0) return null;

  const unit = m[2];
  switch (unit) {
    case 'g':         return value * 1000;
    case 'mg':        return value;
    case 'mcg':
    case 'µg':
    case 'microgram': return value / 1000;
    default:          return null;
  }
}

/** Find a dose rule for this drug name (first match wins). */
function findRule(drugName: string): DoseRule | null {
  const n = drugName.toLowerCase();
  for (const rule of DOSE_RULES) {
    for (const kw of rule.keywords) {
      if (n.includes(kw)) return rule;
    }
  }
  return null;
}

/**
 * Check a paediatric prescribed dose against the weight-based range.
 * Returns at most one match — a single drug can only over- or under-
 * dose against a single rule. Returns [] when:
 *   - drug isn't in our rules table
 *   - dose string doesn't parse to a mass
 *   - weight is missing or non-positive
 *   - dose is within range
 *
 * The empty-array return shape mirrors the other safety checks
 * (allergyCheck, interactionCheck) so the caller can just check
 * `matches.length > 0` to decide whether to open the dialog.
 */
export function checkPediatricDose(
  drugName: string | null | undefined,
  doseStr: string | null | undefined,
  weightKg: number | null | undefined,
): DoseMatch[] {
  if (!drugName) return [];
  if (weightKg == null || !isFinite(weightKg) || weightKg <= 0) return [];

  const rule = findRule(drugName);
  if (!rule) return [];

  const prescribedMg = parseDoseMg(doseStr);
  if (prescribedMg == null) return [];

  const prescribedMgPerKg = prescribedMg / weightKg;

  // Within range — silent.
  if (prescribedMgPerKg >= rule.minMgPerKg && prescribedMgPerKg <= rule.maxMgPerKg) {
    return [];
  }

  const severity: DoseSeverity =
    prescribedMgPerKg > rule.maxMgPerKg ? 'overdose' : 'underdose';

  const match: DoseMatch = {
    drugName,
    doseString: doseStr ?? '',
    prescribedMg,
    weightKg,
    prescribedMgPerKg,
    minMgPerKg: rule.minMgPerKg,
    maxMgPerKg: rule.maxMgPerKg,
    severity,
    ruleLabel: rule.label,
  };

  if (severity === 'overdose') {
    match.foldOverMax = prescribedMgPerKg / rule.maxMgPerKg;
  }

  return [match];
}

/**
 * Serialize dose matches for the V24 audit-trail column. Same trick
 * as Phase 11 (`[duplicate]` prefix) — each line gets an `[overdose]`
 * or `[underdose]` prefix so a SQL `LIKE '%[overdose]%'` query can
 * filter for the most safety-critical override class without needing
 * a new column.
 *
 * Example output:
 *   "[overdose] Paracetamol 1500mg @ 10kg = 150 mg/kg (max 15) — 10× max;
 *    [underdose] Amoxicillin 50mg @ 10kg = 5 mg/kg (min 15)"
 */
export function formatDoseMatches(matches: DoseMatch[]): string {
  return matches
    .map((m) => {
      const head = `[${m.severity}] ${m.ruleLabel} ${m.doseString} @ ${m.weightKg}kg`;
      const calc = `${round(m.prescribedMgPerKg)} mg/kg`;
      const bound =
        m.severity === 'overdose'
          ? `(max ${m.maxMgPerKg})`
          : `(min ${m.minMgPerKg})`;
      const fold =
        m.foldOverMax && m.foldOverMax >= 1.5
          ? ` — ${round(m.foldOverMax)}× max`
          : '';
      return `${head} = ${calc} ${bound}${fold}`;
    })
    .join('; ');
}

function round(n: number): string {
  if (n >= 10) return n.toFixed(0);
  if (n >= 1) return n.toFixed(1);
  return n.toFixed(2);
}
