/**
 * eGfrCalc — Cockcroft-Gault estimated GFR for the adult prescribe-time
 * renal-risk check.
 *
 * Why Cockcroft-Gault and not MDRD / CKD-EPI:
 *   The drug-dosing literature (FDA labels, BNF, Sanford Guide) is
 *   built on Cockcroft-Gault. When a label says "reduce dose if CrCl
 *   < 30 mL/min", the threshold is calibrated against CG, not MDRD.
 *   Using a different equation here would systematically miss or
 *   over-call dose adjustments. CKD-EPI is the better epidemiology
 *   estimator but is the wrong tool for prescribing.
 *
 * The formula:
 *   eGFR (mL/min) = ((140 - age) × weightKg × sexFactor)
 *                   / (72 × serumCreatinine_mgPerDl)
 *
 *   sexFactor = 0.85 if female, 1.0 otherwise.
 *
 * Inputs:
 *   - ageYears: deterministic from DOB. Required, > 0.
 *   - weightKg: latest recorded adult weight. Required, > 0.
 *   - creatinine: serum creatinine. Accepts mg/dL OR µmol/L (the
 *     two units commonly seen on Rwandan and EU labs respectively).
 *     Conversion: 1 mg/dL ≈ 88.4 µmol/L.
 *   - sex: 'female' applies the 0.85 multiplier; anything else
 *     uses 1.0. We accept the union to keep the call site flexible
 *     (the patient's `gender` is 'MALE'/'FEMALE'/'UNKNOWN').
 *
 * Why this isn't a hard stop:
 *   eGFR is a screening number. A 30 in a frail 90-year-old with
 *   stable CKD3b is normal for them; a 30 dropping from 80 over
 *   24 hours is acute kidney injury. The dialog surfaces the value
 *   plus the drug's renal threshold so the prescriber decides.
 *
 * Returns null when any input is missing / invalid — calling code
 * should handle null as "no eGFR available, fall back to Phase 12a
 * screening check".
 */

export type Sex = 'male' | 'female' | 'unknown';

/**
 * Convert µmol/L → mg/dL. Accepts the common unit string variants
 * we see in Rwandan and EU lab results, including the µ vs u and
 * the / vs per spellings. Returns the input unchanged if the unit
 * already looks like mg/dL or is unrecognised — let the eGFR call
 * fail-soft to null rather than silently miscalculate.
 */
export function normaliseCreatinineToMgPerDl(
  value: number,
  unit: string | null | undefined,
): number | null {
  if (!isFinite(value) || value <= 0) return null;
  if (!unit) return null;
  const u = unit.toLowerCase().replace(/\s+/g, '').replace('μ', 'µ');
  if (u === 'mg/dl' || u === 'mgperdl' || u === 'mg/100ml') return value;
  if (
    u === 'µmol/l' ||
    u === 'umol/l' ||
    u === 'micromol/l' ||
    u === 'micromoles/l'
  ) {
    return value / 88.4;
  }
  // Unknown unit — refuse to compute. Better silent miss than wrong.
  return null;
}

/**
 * Cockcroft-Gault eGFR. Returns null when any input is missing,
 * invalid, or out of physiological range. The output is a
 * non-negative mL/min; we deliberately do NOT clip at 120 — values
 * above range are still informative ("you have kidney function to
 * burn, this drug is fine").
 */
export function cockcroftGaultEgfr(input: {
  ageYears: number | null | undefined;
  weightKg: number | null | undefined;
  creatinineMgPerDl: number | null | undefined;
  sex: Sex;
}): number | null {
  const { ageYears, weightKg, creatinineMgPerDl, sex } = input;
  if (ageYears == null || !isFinite(ageYears) || ageYears <= 0 || ageYears > 130) return null;
  if (weightKg == null || !isFinite(weightKg) || weightKg <= 0) return null;
  if (
    creatinineMgPerDl == null ||
    !isFinite(creatinineMgPerDl) ||
    creatinineMgPerDl <= 0
  ) return null;

  const sexFactor = sex === 'female' ? 0.85 : 1.0;
  const egfr = ((140 - ageYears) * weightKg * sexFactor) / (72 * creatinineMgPerDl);
  if (!isFinite(egfr) || egfr < 0) return null;
  return egfr;
}

/**
 * Drug-specific renal thresholds — when eGFR falls below the listed
 * ceiling, the dialog should fire. Values are conservative ED-relevant
 * cut-offs from FDA labels / BNF for the highest-leverage prescribing
 * surface; this isn't a comprehensive renal formulary.
 *
 * `severity`:
 *   'avoid'   — strong recommendation against (or: requires
 *               significant adjustment we can't compute here).
 *   'caution' — dose-reduce or extend-interval; standard ED reflex.
 *
 * First-match-wins keyword lookup — same shape as
 * pediatricDoseCheck / adultDoseCheck / geriatricCheck.
 */
export interface RenalDoseRule {
  keywords: string[];
  label: string;
  /** eGFR threshold in mL/min. Fires when patient eGFR is BELOW this. */
  thresholdEgfr: number;
  severity: 'avoid' | 'caution';
  concern: string;
}

const RENAL_DOSE_RULES: RenalDoseRule[] = [
  // ── NSAIDs — vasoconstriction at the afferent arteriole ──
  {
    keywords: ['ibuprofen', 'naproxen', 'diclofenac', 'indomethacin', 'ketorolac', 'piroxicam', 'meloxicam'],
    label: 'NSAID',
    thresholdEgfr: 30,
    severity: 'avoid',
    concern: 'NSAIDs reduce afferent arteriolar perfusion. Avoid below eGFR 30; below 60 use lowest dose, shortest course.',
  },

  // ── Anticoagulation / antiplatelet ──
  {
    keywords: ['enoxaparin', 'clexane', 'fragmin', 'dalteparin'],
    label: 'LMWH',
    thresholdEgfr: 30,
    severity: 'caution',
    concern: 'Renal clearance — anti-Xa accumulates below eGFR 30. Reduce dose by 50% or switch to UFH.',
  },
  {
    keywords: ['rivaroxaban'],
    label: 'Rivaroxaban',
    thresholdEgfr: 30,
    severity: 'avoid',
    concern: 'Avoid below eGFR 30 (atrial fibrillation indication); below 50 use 15 mg daily.',
  },
  {
    keywords: ['apixaban'],
    label: 'Apixaban',
    thresholdEgfr: 25,
    severity: 'caution',
    concern: 'Below eGFR 25 evidence is limited. Reduce to 2.5 mg BD if two of: age ≥ 80, weight ≤ 60 kg, Cr ≥ 1.5 mg/dL.',
  },
  {
    keywords: ['dabigatran'],
    label: 'Dabigatran',
    thresholdEgfr: 30,
    severity: 'avoid',
    concern: 'Avoid below eGFR 30 — heavily renally cleared, bleeding risk rises sharply.',
  },

  // ── Antibiotics — most need dose-interval extension below 50 ──
  {
    keywords: ['vancomycin'],
    label: 'Vancomycin',
    thresholdEgfr: 50,
    severity: 'caution',
    concern: 'Renal clearance — interval extension required. Use trough-guided dosing; below eGFR 30 is single loading dose then trough at 24h.',
  },
  {
    keywords: ['gentamicin', 'amikacin', 'tobramycin', 'streptomycin'],
    label: 'Aminoglycoside',
    thresholdEgfr: 60,
    severity: 'caution',
    concern: 'Nephrotoxic AND renally cleared. Below eGFR 60 use extended-interval dosing; below 30 reconsider drug class.',
  },
  {
    keywords: ['ciprofloxacin', 'levofloxacin', 'ofloxacin'],
    label: 'Fluoroquinolone',
    thresholdEgfr: 50,
    severity: 'caution',
    concern: 'Reduce dose / extend interval below eGFR 50.',
  },
  {
    keywords: ['co-trimoxazole', 'cotrimoxazole', 'trimethoprim', 'bactrim', 'septrin'],
    label: 'Co-trimoxazole / trimethoprim',
    thresholdEgfr: 30,
    severity: 'caution',
    concern: 'Dose-reduce below eGFR 30; avoid below 15 (hyperkalaemia, accumulation).',
  },
  {
    keywords: ['nitrofurantoin'],
    label: 'Nitrofurantoin',
    thresholdEgfr: 30,
    severity: 'avoid',
    concern: 'Avoid below eGFR 30 — fails to concentrate in urine, accumulates systemically (peripheral neuropathy).',
  },

  // ── Other ──
  {
    keywords: ['metformin'],
    label: 'Metformin',
    thresholdEgfr: 30,
    severity: 'avoid',
    concern: 'Avoid below eGFR 30 (lactic acidosis risk); 30–45 reduce by 50%; review dose at 45–60.',
  },
  {
    keywords: ['digoxin'],
    label: 'Digoxin',
    thresholdEgfr: 50,
    severity: 'caution',
    concern: 'Renally cleared; toxicity rises sharply below eGFR 50. Use lowest dose and trough levels.',
  },
  {
    keywords: ['allopurinol'],
    label: 'Allopurinol',
    thresholdEgfr: 60,
    severity: 'caution',
    concern: 'Reduce starting dose below eGFR 60; titrate slowly to avoid hypersensitivity syndrome.',
  },
  {
    keywords: ['acyclovir', 'aciclovir', 'valacyclovir', 'valaciclovir'],
    label: 'Acyclovir / valacyclovir',
    thresholdEgfr: 50,
    severity: 'caution',
    concern: 'Crystalluria + neurotoxicity below eGFR 50. Extend interval; ensure hydration.',
  },
  {
    keywords: ['lithium'],
    label: 'Lithium',
    thresholdEgfr: 60,
    severity: 'caution',
    concern: 'Narrow therapeutic index, renally cleared. Monitor levels closely below eGFR 60; avoid below 30.',
  },
];

export interface RenalEgfrMatch {
  drugClassLabel: string;
  thresholdEgfr: number;
  patientEgfr: number;
  severity: 'avoid' | 'caution';
  concern: string;
}

/** First-match-wins lookup against RENAL_DOSE_RULES. */
function findRenalRule(drugName: string): RenalDoseRule | null {
  const n = drugName.toLowerCase();
  for (const rule of RENAL_DOSE_RULES) {
    for (const kw of rule.keywords) {
      if (n.includes(kw)) return rule;
    }
  }
  return null;
}

/**
 * Check whether the prescribed drug + the patient's computed eGFR
 * triggers a dose-adjustment warning. Returns 0 or 1 matches.
 *
 * Returns [] when:
 *   - drug not in RENAL_DOSE_RULES
 *   - eGFR is null (unknown — Phase 12a screening check still applies
 *     upstream, this layer doesn't fire on missing data)
 *   - eGFR is at or above the rule's threshold
 */
export function checkRenalEgfrDosing(
  drugName: string | null | undefined,
  egfr: number | null,
): RenalEgfrMatch[] {
  if (!drugName) return [];
  if (egfr == null || !isFinite(egfr)) return [];
  const rule = findRenalRule(drugName);
  if (!rule) return [];
  if (egfr >= rule.thresholdEgfr) return [];
  return [{
    drugClassLabel: rule.label,
    thresholdEgfr: rule.thresholdEgfr,
    patientEgfr: egfr,
    severity: rule.severity,
    concern: rule.concern,
  }];
}

/**
 * Wire-format snapshot for V24 audit-trail. Tag prefix
 * `[renal-egfr][avoid]` / `[renal-egfr][caution]` — keeps the existing
 * `[renal]` Phase 12a screening tag distinct from the eGFR-driven
 * dose check so the safety officer can see which signal fired.
 *
 * MedicationService routes both through the renal-precaution branch
 * for severity calibration; the dashboard can split them by tag if
 * the volume warrants.
 *
 * Example:
 *   "[renal-egfr][avoid] Nitrofurantoin — eGFR 22 (threshold 30) —
 *    Avoid below eGFR 30 — fails to concentrate in urine…"
 */
export function formatRenalEgfrMatches(matches: RenalEgfrMatch[]): string {
  return matches
    .map((m) =>
      `[renal-egfr][${m.severity}] ${m.drugClassLabel} — ` +
      `eGFR ${m.patientEgfr.toFixed(0)} (threshold ${m.thresholdEgfr}) — ` +
      `${m.concern}`,
    )
    .join('; ');
}
