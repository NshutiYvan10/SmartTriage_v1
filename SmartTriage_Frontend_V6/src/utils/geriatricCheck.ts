/**
 * geriatricCheck — flag drugs from the Beers Criteria "potentially
 * inappropriate medications" list when prescribed to a patient ≥ 65.
 *
 * Why this exists:
 *   Older adults metabolise and tolerate many common ED drugs
 *   differently. Diphenhydramine for "a little something to help
 *   her sleep" causes delirium and falls. A long-acting benzo
 *   started in the ED for a 78-year-old's anxiety often ends in
 *   a hip fracture. The American Geriatrics Society Beers Criteria
 *   is the standard-of-care reference for these drug–elderly
 *   interactions, but ED prescribers under time pressure don't
 *   reliably consult it. A bounds check at prescribe time —
 *   "this drug is on the Beers list and your patient is 78" —
 *   catches the omission before the order leaves the screen.
 *
 *   This is the geriatric counterpart to teratogenCheck (pregnancy)
 *   and pediatricDoseCheck (paediatric weight-based). Same shape:
 *   demographic-gated trigger, hardcoded rule table covering the
 *   highest-yield ED-relevant entries, V24 audit-trail piggyback.
 *
 * Trigger:
 *   ageInYears ≥ 65, period. Deterministic — no free-text scan.
 *   Below 65: silent skip, no warning. The threshold matches the
 *   Beers Criteria definition; we don't second-guess "but they're
 *   a young 70" — that judgement belongs to the prescriber when
 *   they read the dialog.
 *
 * Calibration (severity tiers):
 *   - 'avoid'   — Beers strongly recommends against. Common ED
 *                 drugs that change clinical outcomes: long-acting
 *                 benzos, anticholinergics (diphenhydramine,
 *                 promethazine, hydroxyzine), tricyclics,
 *                 pethidine/meperidine, glyburide,
 *                 cyclobenzaprine. Wire token: `[geriatric][avoid]`.
 *   - 'caution' — Beers says use with caution / dose-reduce. Drugs
 *                 that may still be appropriate but warrant the
 *                 prescriber's deliberate consent: short-acting
 *                 benzos, NSAIDs, α-blockers, tramadol. Wire
 *                 token: `[geriatric][caution]`.
 *
 * Out of scope (intentionally):
 *   - Drug–disease Beers entries ("avoid X in patients with Y") —
 *     combinatorial; needs structured disease coding we don't have.
 *   - Drug–drug Beers entries — partly covered by interactionCheck.
 *   - Dose-thresholded entries ("digoxin > 0.125 mg/day") — would
 *     need reliable frequency parsing, same gap pediatricDoseCheck
 *     called out.
 *   - Chronic-duration entries ("PPI > 8 weeks") — ED prescribes
 *     single doses or short courses; chronic-duration concerns
 *     belong to outpatient review, not the ED safety pipeline.
 *   - Estrogens, sliding-scale insulin, opioid+benzo combos — out
 *     of typical ED scope or already covered elsewhere.
 *
 * What this is NOT:
 *   This is not a hard stop. The dialog renders the warning; the
 *   prescriber decides. A 78-year-old in resus needing midazolam
 *   for refractory seizure absolutely gets it — the warning fires,
 *   the prescriber overrides, the audit trail captures the override,
 *   and the safety officer reviews the pattern in the Phase 14
 *   dashboard. Alert fatigue is mitigated by limiting the table to
 *   high-leverage entries, not by softening the warning itself.
 */

export type GeriatricSeverity = 'avoid' | 'caution';

export interface GeriatricMatch {
  /** Drug-class label shown in the dialog ("Diphenhydramine",
   *  "Long-acting benzodiazepine", etc.). */
  drugClassLabel: string;
  /** Beers severity tier (drives wire-format tag + dialog colour). */
  severity: GeriatricSeverity;
  /** Patient age that triggered the check — surfaced in the dialog
   *  so the prescriber can sanity-check ("yes, she's 78") rather
   *  than wonder if we read the wrong record. */
  ageYears: number;
  /** Clinical concern — what's the elderly-specific harm? */
  concern: string;
}

interface GeriatricRule {
  /** Lowercase substrings — match if any appears in the drug name. */
  keywords: string[];
  /** Human label shown in the warning. */
  label: string;
  /** Beers tier. */
  severity: GeriatricSeverity;
  /** Concern shown in the dialog. */
  concern: string;
}

/**
 * Beers Criteria entries scoped to the ED prescribing surface.
 * Curated from AGS 2023 Beers Criteria, narrowed to drugs that
 * actually appear in ED orders and whose harm in the elderly is
 * mechanism-clear (anticholinergic, sedating, GI-bleeding, etc.).
 *
 * First-match-wins on substring — `findRule` uses the order below.
 * For ambiguous keywords ("diazepam" matches both diazepam and
 * its less-flagged short-acting cousins), we list the long-acting
 * forms first so the avoid-tier rule wins.
 */
const GERIATRIC_RULES: GeriatricRule[] = [
  // ── Anticholinergic load — delirium, falls, urinary retention ──
  {
    keywords: ['diphenhydramine', 'benadryl'],
    label: 'Diphenhydramine',
    severity: 'avoid',
    concern: 'Strong anticholinergic burden — delirium, sedation, falls, urinary retention. Use second-generation antihistamine (cetirizine, loratadine) instead.',
  },
  {
    keywords: ['promethazine', 'phenergan'],
    label: 'Promethazine',
    severity: 'avoid',
    concern: 'Anticholinergic + sedating. Avoid for nausea/vomiting in elderly — use ondansetron.',
  },
  {
    keywords: ['hydroxyzine', 'atarax'],
    label: 'Hydroxyzine',
    severity: 'avoid',
    concern: 'Sedating antihistamine with anticholinergic effects — falls and delirium risk.',
  },
  {
    keywords: ['hyoscine', 'scopolamine', 'buscopan'],
    label: 'Hyoscine / scopolamine',
    severity: 'avoid',
    concern: 'Anticholinergic — confusion, urinary retention, constipation in elderly.',
  },
  {
    keywords: ['amitriptyline'],
    label: 'Amitriptyline',
    severity: 'avoid',
    concern: 'Strongly anticholinergic + cardiotoxic at moderate doses. Avoid in elderly; consider SSRI / SNRI for indication.',
  },
  {
    keywords: ['doxepin'],
    label: 'Doxepin (>6 mg)',
    severity: 'avoid',
    concern: 'Anticholinergic at >6 mg/day. Low-dose only acceptable; ED orders are typically higher.',
  },
  {
    keywords: ['prochlorperazine', 'stemetil'],
    label: 'Prochlorperazine',
    severity: 'avoid',
    concern: 'Anticholinergic + extrapyramidal; high fall risk and parkinsonism in elderly.',
  },

  // ── Long-acting benzodiazepines — falls, prolonged sedation ──
  {
    keywords: ['chlordiazepoxide', 'librium'],
    label: 'Chlordiazepoxide',
    severity: 'avoid',
    concern: 'Long half-life accumulates in elderly — prolonged sedation, falls, hip fracture risk.',
  },
  {
    keywords: ['flurazepam'],
    label: 'Flurazepam',
    severity: 'avoid',
    concern: 'Long-acting metabolites accumulate — daytime sedation, falls.',
  },
  {
    keywords: ['diazepam'],
    label: 'Diazepam (long-acting)',
    severity: 'avoid',
    concern: 'Long half-life in elderly (40+ hrs). Falls and prolonged sedation. Use lorazepam if benzo is essential.',
  },
  {
    keywords: ['clonazepam'],
    label: 'Clonazepam',
    severity: 'avoid',
    concern: 'Long-acting benzo — falls, sedation. Caution if seizure indication is firm.',
  },

  // ── Short-acting benzos — caution tier ──
  {
    keywords: ['lorazepam'],
    label: 'Lorazepam',
    severity: 'caution',
    concern: 'Acceptable short-acting choice but still raises fall risk in elderly. Use the lowest effective dose; avoid combining with opioids.',
  },
  {
    keywords: ['alprazolam'],
    label: 'Alprazolam',
    severity: 'caution',
    concern: 'Short-acting but high abuse / dependency potential. Falls and rebound anxiety in elderly.',
  },
  {
    keywords: ['midazolam'],
    label: 'Midazolam',
    severity: 'caution',
    concern: 'Acceptable for procedural sedation. Reduce dose by 30–50% in elderly; titrate.',
  },

  // ── Skeletal muscle relaxants — central anticholinergic + sedation ──
  {
    keywords: ['cyclobenzaprine', 'flexeril'],
    label: 'Cyclobenzaprine',
    severity: 'avoid',
    concern: 'Anticholinergic + sedating; poor efficacy beyond 1 week. Avoid in elderly.',
  },
  {
    keywords: ['methocarbamol', 'robaxin'],
    label: 'Methocarbamol',
    severity: 'avoid',
    concern: 'Sedation, falls; minimal benefit in elderly.',
  },
  {
    keywords: ['carisoprodol', 'soma'],
    label: 'Carisoprodol',
    severity: 'avoid',
    concern: 'Sedating; metabolised to meprobamate (barbiturate-like). Avoid in elderly.',
  },

  // ── Opioids with elderly-specific issues ──
  {
    keywords: ['pethidine', 'meperidine', 'demerol'],
    label: 'Pethidine / meperidine',
    severity: 'avoid',
    concern: 'Active metabolite (normeperidine) accumulates → seizures, neurotoxicity. Especially dangerous in renal impairment, common in elderly.',
  },
  {
    keywords: ['tramadol'],
    label: 'Tramadol',
    severity: 'caution',
    concern: 'Seizure risk, serotonin syndrome (esp. with SSRIs), hyponatraemia (SIADH). Reduce dose; avoid if other risk factors.',
  },

  // ── Sulfonylureas — hypoglycaemia ──
  {
    keywords: ['glyburide', 'glibenclamide'],
    label: 'Glyburide / glibenclamide',
    severity: 'avoid',
    concern: 'Long-acting sulfonylurea — prolonged hypoglycaemia in elderly, especially with renal decline. Use glipizide or DPP-4 inhibitor if oral hypoglycaemic needed.',
  },

  // ── Cardiovascular ──
  {
    keywords: ['doxazosin'],
    label: 'Doxazosin',
    severity: 'caution',
    concern: 'α-blocker — orthostatic hypotension and falls. Not first-line in elderly hypertensives.',
  },
  {
    keywords: ['prazosin'],
    label: 'Prazosin',
    severity: 'caution',
    concern: 'α-blocker — orthostatic hypotension; first-dose syncope. Caution in elderly.',
  },
  {
    keywords: ['terazosin'],
    label: 'Terazosin',
    severity: 'caution',
    concern: 'α-blocker — orthostatic hypotension and falls.',
  },
  {
    keywords: ['nifedipine'],
    label: 'Nifedipine (immediate-release)',
    severity: 'caution',
    concern: 'Short-acting calcium-channel blocker — hypotension, reflex tachycardia. Prefer long-acting forms.',
  },

  // ── NSAIDs — caution (single doses less critical than chronic,
  //    but still meaningful GI/renal/CV risk in elderly) ──
  {
    keywords: ['ibuprofen', 'naproxen', 'diclofenac', 'indomethacin', 'ketorolac', 'piroxicam', 'meloxicam'],
    label: 'NSAID',
    severity: 'caution',
    concern: 'GI bleeding, renal injury, fluid retention all elevated in elderly. Use lowest effective dose for shortest duration; consider paracetamol first.',
  },

  // ── Antipsychotics — used for delirium but with care ──
  {
    keywords: ['chlorpromazine', 'thorazine', 'largactil'],
    label: 'Chlorpromazine',
    severity: 'avoid',
    concern: 'Anticholinergic + α-blocking — falls, EPS, prolonged QT in elderly.',
  },
  {
    keywords: ['haloperidol'],
    label: 'Haloperidol',
    severity: 'caution',
    concern: 'Acceptable for acute delirium / agitation but increases mortality in dementia (boxed warning). Lowest effective dose; reassess daily.',
  },
];

/**
 * First-match-wins lookup. Same shape as the other safety utilities.
 */
function findRule(drugName: string): GeriatricRule | null {
  const n = drugName.toLowerCase();
  for (const rule of GERIATRIC_RULES) {
    for (const kw of rule.keywords) {
      if (n.includes(kw)) return rule;
    }
  }
  return null;
}

/**
 * Run the geriatric check. Returns 0 or 1 matches.
 *
 * Returns [] when:
 *   - drugName is empty,
 *   - ageInYears < 65 (or null/undefined),
 *   - drug isn't in the Beers-curated table.
 *
 * The empty-array shape mirrors the other safety checks so the
 * caller can branch identically on `.length > 0`.
 */
export function checkGeriatricRisk(
  drugName: string | null | undefined,
  ageInYears: number | null | undefined,
): GeriatricMatch[] {
  if (!drugName) return [];
  if (ageInYears == null || !isFinite(ageInYears) || ageInYears < 65) return [];

  const rule = findRule(drugName);
  if (!rule) return [];

  return [{
    drugClassLabel: rule.label,
    severity: rule.severity,
    ageYears: ageInYears,
    concern: rule.concern,
  }];
}

/**
 * Wire-format snapshot for the V24 audit-trail column. Tag prefix
 * is `[geriatric][avoid]` or `[geriatric][caution]` — same sub-tag
 * pattern as `[teratogen][X]` / `[teratogen][D]` so MedicationService's
 * severity ladder can map cleanly:
 *
 *   [geriatric][avoid]   → HIGH
 *   [geriatric][caution] → MEDIUM
 *   [geriatric] (bare)   → MEDIUM (fail-safe)
 *
 * Phase 14's Override Audit dashboard groups these under a new
 * "Geriatric" override class.
 *
 * Example output:
 *   "[geriatric][avoid] Diphenhydramine — age 78 — Strong anticholinergic
 *    burden — delirium, sedation, falls, urinary retention…"
 */
export function formatGeriatricMatches(matches: GeriatricMatch[]): string {
  return matches
    .map((m) =>
      `[geriatric][${m.severity}] ${m.drugClassLabel} — age ${m.ageYears} — ${m.concern}`,
    )
    .join('; ');
}
