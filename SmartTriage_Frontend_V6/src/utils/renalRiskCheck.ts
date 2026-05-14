/**
 * renalRiskCheck — flag drugs that are dangerous in renal impairment
 * when the patient either (a) carries a chronic-kidney-disease
 * condition on their profile, or (b) presents with vitals that hint
 * at acute kidney injury (haemodynamic instability).
 *
 * Why this exists, and why it's NOT a full eGFR check:
 *   The proper renal-dose check requires creatinine + adult weight to
 *   compute Cockcroft-Gault. SmartTriage today captures creatinine as
 *   free-text on Investigation results and has no adult weight field —
 *   the data plumbing isn't there yet. Until that lab pipeline is
 *   structured, we ship the highest-leverage piece we CAN do with
 *   today's data: a hard-stop warning when a known-renal-dangerous
 *   drug is prescribed in a patient where renal function is plausibly
 *   compromised.
 *
 *   Two trigger paths:
 *     1. CKD trigger (HIGH confidence) — the patient's
 *        `chronicConditions` text mentions CKD, dialysis, kidney
 *        disease, etc. The clinician explicitly recorded it; we trust
 *        it and surface a warning on every renal-dangerous drug.
 *     2. AKI trigger (LOWER confidence) — vitals fit a "shock or
 *        near-shock" pattern that frequently coincides with pre-renal
 *        AKI: SBP < 90, or SBP < 100 with compensatory tachycardia
 *        (HR > 110). We only fire this trigger for the most lethal
 *        renal-drug combinations to avoid alert fatigue.
 *
 * Calibration:
 *   - CKD trigger: every drug in our renal-risk table fires.
 *   - AKI trigger: only the "fail-fast in shock" drugs fire (NSAIDs,
 *     ACEi, ARBs, aminoglycosides, metformin). We don't fire the AKI
 *     trigger for drugs whose renal concern is chronic accumulation
 *     (digoxin, allopurinol) — those need a real eGFR, not vitals.
 *
 * Out of scope (intentionally):
 *   - Computed eGFR or Cockcroft-Gault (no creatinine, no adult weight).
 *   - Severity tiering by eGFR threshold (need the number).
 *   - Dose-reduction recommendations ("reduce to 50% in CrCl 30–50").
 *
 * When the lab pipeline is structured (Phase 12b), this utility stays —
 * the symptom/condition triggers are still useful as a fallback when
 * creatinine is stale or absent. The eGFR layer plugs in alongside.
 */

export type RenalTrigger = 'ckd' | 'aki_likely';

export interface RenalMatch {
  /** The drug class that fired (label shown to clinician). */
  drugClassLabel: string;
  /** Why we think renal function may be compromised. */
  trigger: RenalTrigger;
  /** Short clinical reason — what's the harm with this drug? */
  concern: string;
  /** For CKD trigger: the matched substring from chronicConditions
   *  so the prescriber can confirm we read it correctly. */
  conditionEvidence?: string;
  /** For AKI trigger: the offending vital values. */
  vitalEvidence?: string;
}

/**
 * Renal-dangerous drug classes. `akiSensitive: true` means the drug
 * is in the "must not give in shock / pre-renal AKI" tier — these
 * fire on the AKI trigger. The rest fire only on the CKD trigger.
 */
interface RenalRiskClass {
  /** Lowercase substrings to match against drug name. */
  keywords: string[];
  /** Human label shown in the warning. */
  label: string;
  /** Clinical concern shown in the dialog. */
  concern: string;
  /** True if this drug should also fire on AKI-likely vitals. */
  akiSensitive: boolean;
}

const RENAL_RISK: RenalRiskClass[] = [
  {
    keywords: ['ibuprofen', 'diclofenac', 'naproxen', 'indomethacin', 'ketorolac', 'meloxicam', 'celecoxib', 'piroxicam', 'mefenamic'],
    label: 'NSAID',
    concern: 'Reduces renal perfusion via afferent arteriole vasoconstriction — can precipitate or worsen AKI.',
    akiSensitive: true,
  },
  {
    // ACE-I keywords: include 'pril' as a catch-all suffix (matches
    // captopril/enalapril/lisinopril/ramipril/perindopril). Same trick
    // as interactionCheck.
    keywords: ['captopril', 'enalapril', 'lisinopril', 'ramipril', 'perindopril', 'pril'],
    label: 'ACE inhibitor',
    concern: 'Drops glomerular filtration pressure — hold in AKI; reduce / monitor in CKD.',
    akiSensitive: true,
  },
  {
    keywords: ['losartan', 'valsartan', 'irbesartan', 'telmisartan', 'candesartan', 'sartan'],
    label: 'ARB',
    concern: 'Same renal-perfusion concern as ACE inhibitors — hold in AKI.',
    akiSensitive: true,
  },
  {
    keywords: ['gentamicin', 'amikacin', 'tobramycin', 'streptomycin'],
    label: 'aminoglycoside',
    concern: 'Direct nephrotoxicity — dose by levels; avoid or shorten course in any renal impairment.',
    akiSensitive: true,
  },
  {
    keywords: ['metformin'],
    label: 'metformin',
    concern: 'Lactic-acidosis risk if eGFR < 30. Hold in any acute illness with shock.',
    akiSensitive: true,
  },
  {
    keywords: ['vancomycin'],
    label: 'vancomycin',
    concern: 'Nephrotoxic — dose by levels; reduce frequency in CKD.',
    akiSensitive: false,
  },
  {
    keywords: ['digoxin'],
    label: 'digoxin',
    concern: 'Renal clearance; accumulates in CKD — reduce dose, monitor levels.',
    akiSensitive: false,
  },
  {
    keywords: ['allopurinol'],
    label: 'allopurinol',
    concern: 'Reduce dose in CKD — accumulation increases SJS/TEN risk.',
    akiSensitive: false,
  },
  {
    keywords: ['rivaroxaban', 'apixaban', 'dabigatran', 'edoxaban'],
    label: 'DOAC',
    concern: 'Renal-cleared — dose-reduce or avoid in moderate-to-severe CKD; check product-specific thresholds.',
    akiSensitive: false,
  },
  {
    keywords: ['cotrimoxazole', 'co-trimoxazole', 'sulfamethoxazole', 'septrin', 'bactrim', 'trimethoprim'],
    label: 'co-trimoxazole',
    concern: 'Hyperkalaemia + renal accumulation — reduce dose, watch K⁺ in CKD.',
    akiSensitive: false,
  },
  {
    keywords: ['lithium'],
    label: 'lithium',
    concern: 'Renally cleared with narrow therapeutic index — toxicity risk in any renal impairment.',
    akiSensitive: false,
  },
  {
    keywords: ['contrast', 'iohexol', 'iopamidol'],
    label: 'iodinated contrast',
    concern: 'Contrast-induced nephropathy risk — defer or hydrate aggressively if renal function impaired.',
    akiSensitive: true,
  },
];

/**
 * Tokens we look for in `chronicConditions` free text. Lowercased.
 * Permissive on purpose: a patient profile that says "CKD stage 3"
 * and one that says "chronic kidney disease" should both fire.
 *
 * `dialysis` is included because a dialysis patient is by definition
 * a renal-failure patient — the most extreme case for our purposes.
 */
const CKD_TOKENS = [
  'ckd',
  'chronic kidney',
  'kidney disease',
  'kidney failure',
  'renal failure',
  'renal impairment',
  'renal insufficiency',
  'nephropathy',
  'dialysis',
  'haemodialysis',
  'hemodialysis',
  'esrd',
  'end-stage renal',
  'end stage renal',
];

/** Returns the first CKD token found in the free-text conditions
 *  string, or null. The matched token is shown back to the prescriber
 *  in the dialog so they can confirm we read the chart correctly. */
function findCkdEvidence(chronicConditions: string | null | undefined): string | null {
  if (!chronicConditions) return null;
  const s = chronicConditions.toLowerCase();
  for (const tok of CKD_TOKENS) {
    if (s.includes(tok)) return tok;
  }
  return null;
}

/**
 * Vitals-based AKI heuristic. Fires when the patient is in shock or
 * near-shock — a hemodynamic state that frequently produces pre-renal
 * AKI. We deliberately set a tight threshold (SBP < 90, or SBP < 100
 * with HR > 110) rather than a permissive one because this trigger
 * is paired with renal-dangerous drugs that we don't want to alert on
 * for every borderline-low BP.
 *
 * Returns a short evidence string ("SBP 82, HR 124") or null.
 */
export interface VitalsLike {
  systolicBp?: number | null;
  heartRate?: number | null;
}

function findAkiEvidence(vitals: VitalsLike | null | undefined): string | null {
  if (!vitals) return null;
  const sbp = typeof vitals.systolicBp === 'number' ? vitals.systolicBp : null;
  const hr  = typeof vitals.heartRate === 'number' ? vitals.heartRate : null;

  if (sbp != null && sbp < 90) {
    return hr != null ? `SBP ${sbp} mmHg, HR ${hr} bpm` : `SBP ${sbp} mmHg`;
  }
  if (sbp != null && sbp < 100 && hr != null && hr > 110) {
    return `SBP ${sbp} mmHg + HR ${hr} bpm (compensatory tachycardia)`;
  }
  return null;
}

/** Map a drug name to its renal-risk class entry, or null. */
function classifyDrug(drugName: string): RenalRiskClass | null {
  const n = drugName.toLowerCase();
  for (const cls of RENAL_RISK) {
    for (const kw of cls.keywords) {
      if (n.includes(kw)) return cls;
    }
  }
  return null;
}

/**
 * Run the renal-risk check. Returns 0 or 1 matches — a drug fires at
 * most once even when both CKD and AKI triggers apply (CKD wins
 * because the evidence is stronger / less ambiguous; the prescriber
 * still sees the vitals on the visit page).
 *
 * Returns [] when:
 *   - drug isn't in our renal-risk table, OR
 *   - no CKD evidence in chronicConditions AND vitals don't suggest AKI, OR
 *   - vitals suggest AKI but the drug isn't in the akiSensitive tier.
 */
export function checkRenalRisk(
  drugName: string | null | undefined,
  chronicConditions: string | null | undefined,
  vitals: VitalsLike | null | undefined,
  /**
   * Workflow 2 refinement — structured chronic conditions. Each row
   * carries an optional curated {@code conditionCode}; CKD / ESRD
   * codes resolve to CKD-evidence even when the legacy free-text
   * column is empty. Pass an empty array when no structured rows
   * are available (callers that haven't been updated yet keep
   * working through the legacy free-text path).
   */
  structuredConditions: ReadonlyArray<{
    conditionCode?: string | null;
    conditionName: string;
    status: import('@/api/types').ChronicConditionStatus;
  }> = [],
): RenalMatch[] {
  if (!drugName) return [];

  const cls = classifyDrug(drugName);
  if (!cls) return [];

  // Structured first — check the catalog's `ckd` flag. We only
  // consider ACTIVE / CONTROLLED rows; IN_REMISSION / RESOLVED
  // don't drive renal gating.
  const ckdRow = structuredConditions.find((r) => {
    if (r.status !== 'ACTIVE' && r.status !== 'CONTROLLED') return false;
    if (!r.conditionCode) return false;
    // Inline the catalog flag check (avoids a circular import).
    const code = r.conditionCode.trim().toUpperCase();
    return code === 'CKD' || code === 'ESRD';
  });
  if (ckdRow) {
    return [{
      drugClassLabel: cls.label,
      trigger: 'ckd',
      concern: cls.concern,
      conditionEvidence: ckdRow.conditionName,
    }];
  }

  const ckdEvidence = findCkdEvidence(chronicConditions);
  if (ckdEvidence) {
    return [{
      drugClassLabel: cls.label,
      trigger: 'ckd',
      concern: cls.concern,
      conditionEvidence: ckdEvidence,
    }];
  }

  // AKI trigger only fires for drugs whose harm in shock is acute
  // and well-evidenced. Chronic-accumulation drugs need real eGFR.
  if (cls.akiSensitive) {
    const aki = findAkiEvidence(vitals);
    if (aki) {
      return [{
        drugClassLabel: cls.label,
        trigger: 'aki_likely',
        concern: cls.concern,
        vitalEvidence: aki,
      }];
    }
  }

  return [];
}

/**
 * Wire-format snapshot for the V24 audit column. Tag prefix is
 * `[renal]` — same piggyback pattern as `[duplicate]` (Phase 11) and
 * `[overdose]`/`[underdose]` (Phase 9), so a SQL `LIKE '%[renal]%'`
 * filter pulls renal overrides out for the safety report without a
 * new column.
 *
 * Example output:
 *   "[renal] Ibuprofen (NSAID) — ckd evidence: 'ckd stage 3' —
 *    Reduces renal perfusion via afferent arteriole vasoconstriction"
 */
export function formatRenalMatches(matches: RenalMatch[]): string {
  return matches
    .map((m) => {
      const evidence = m.trigger === 'ckd'
        ? `ckd evidence: '${m.conditionEvidence ?? ''}'`
        : `aki-likely vitals: ${m.vitalEvidence ?? ''}`;
      return `[renal] ${m.drugClassLabel} — ${evidence} — ${m.concern}`;
    })
    .join('; ');
}
