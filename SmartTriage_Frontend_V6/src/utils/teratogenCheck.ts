/**
 * teratogenCheck — flag drugs known to harm a fetus or breastfed
 * infant when the patient's chart explicitly records pregnancy or
 * breastfeeding.
 *
 * Why this exists:
 *   Teratogenic exposure is irreversible. A single dose of warfarin
 *   in the first trimester, isotretinoin in any trimester, or an ACE
 *   inhibitor in the second/third can cause permanent fetal harm.
 *   These are also the prescribing errors clinicians most regret —
 *   the patient walks out, the harm shows up at delivery, and there
 *   is no remediation path. A hard-stop dialog at order entry is the
 *   cheapest intervention with the highest expected value.
 *
 * Trigger philosophy — explicit mention only:
 *   This check ONLY fires when `chronicConditions` (or the equivalent
 *   chart field) literally records a pregnancy or breastfeeding state.
 *   We deliberately do NOT trigger on demographics (female + age
 *   13–50) because:
 *     - It would alert on every female-of-childbearing-age
 *       prescription, training the prescriber to dismiss the dialog.
 *     - Trans / non-binary patients exist and demographic gating
 *       creates miss paths that matter.
 *     - The cost of a missed teratogen warning is borne by the fetus,
 *       not the clinician — alert fatigue is a worse failure mode
 *       than a slightly conservative trigger.
 *
 *   The explicit-mention requirement parallels Phase 12a's CKD trigger.
 *   Both are "the chart told us; we trust it" detectors.
 *
 * Scope of the rules table:
 *   - **Category X** (absolute contraindication): warfarin,
 *     isotretinoin, methotrexate, misoprostol (outside obstetric
 *     indication), thalidomide, ribavirin, finasteride.
 *   - **Category D** (clear fetal risk; only when benefit clearly
 *     outweighs): ACE inhibitors, ARBs, NSAIDs (3rd trimester),
 *     tetracyclines, valproate, phenytoin, carbamazepine, lithium,
 *     statins, fluconazole (high dose).
 *   - **Aminoglycoside ototoxicity** is called out separately — not
 *     a category D in all references, but the eighth-cranial-nerve
 *     damage to the fetus is well-evidenced.
 *
 *   We surface the category to the prescriber so a "category D when
 *   alternatives exist" decision feels different from a "category X,
 *   never" decision.
 *
 * Out of scope:
 *   - Trimester-specific advice (NSAIDs are safer in the first
 *     trimester than the third; we don't parse trimester from text).
 *   - Dose-dependent teratogenicity (most of these are dose-relevant,
 *     but encoding "above X mg/kg/day" requires structured dose data
 *     we don't have).
 *   - Breastfeeding-only drugs that are pregnancy-safe (e.g. some
 *     chemotherapy) — covered as a separate `breastfeeding` trigger.
 *
 * Phase 13a marker: this ships frontend-only with V24 audit
 * piggyback (`[teratogen]` tag). When a structured pregnancy field
 * lands on PatientResponse / VisitResponse (Phase 13b), the chart-
 * scan trigger stays as a fallback for free-text-only entries.
 */

export type PregnancyState = 'pregnant' | 'breastfeeding';
export type TeratogenCategory = 'X' | 'D' | 'D-late' | 'caution';

export interface TeratogenMatch {
  /** Drug-class label shown to the clinician. */
  drugClassLabel: string;
  /** Pregnancy / breastfeeding category. */
  category: TeratogenCategory;
  /** Whether the chart said "pregnant" or "breastfeeding". */
  state: PregnancyState;
  /** The matched substring from chronicConditions, shown back to
   *  the prescriber so they can confirm we read it correctly. */
  evidence: string;
  /** Short clinical reason — what's the harm? */
  concern: string;
}

interface TeratogenRule {
  keywords: string[];
  label: string;
  category: TeratogenCategory;
  concern: string;
  /** True if the drug is also concerning in lactation. Default false:
   *  many category-X-in-pregnancy drugs are compatible with breast-
   *  feeding because the dose to the infant is much lower. We mark
   *  the explicit ones. */
  alsoBreastfeeding?: boolean;
}

const TERATOGENS: TeratogenRule[] = [
  // ── Category X (absolute) ──
  {
    keywords: ['warfarin', 'coumadin'],
    label: 'warfarin',
    category: 'X',
    concern: 'Warfarin embryopathy (nasal hypoplasia, stippled epiphyses) and fetal CNS bleeding. Switch to LMWH.',
  },
  {
    keywords: ['isotretinoin', 'roaccutane', 'accutane'],
    label: 'isotretinoin',
    category: 'X',
    concern: 'Severe craniofacial, cardiac, and CNS malformations even at low doses. Absolutely contraindicated.',
    alsoBreastfeeding: true,
  },
  {
    keywords: ['methotrexate'],
    label: 'methotrexate',
    category: 'X',
    concern: 'Folate antagonism — neural-tube defects, limb anomalies, embryotoxic. Avoid in pregnancy and lactation.',
    alsoBreastfeeding: true,
  },
  {
    keywords: ['misoprostol', 'cytotec'],
    label: 'misoprostol',
    category: 'X',
    concern: 'Uterotonic — pregnancy loss, Möbius sequence. Only used in pregnancy under defined obstetric protocols.',
  },
  {
    keywords: ['thalidomide'],
    label: 'thalidomide',
    category: 'X',
    concern: 'Phocomelia and other limb-reduction defects from a single dose. Strict TERIS/iPledge-equivalent protocol required.',
  },
  {
    keywords: ['ribavirin'],
    label: 'ribavirin',
    category: 'X',
    concern: 'Embryocidal and teratogenic in animal studies at sub-therapeutic doses. Avoid; partner of male patient also screened.',
  },
  {
    keywords: ['finasteride', 'dutasteride'],
    label: '5α-reductase inhibitor',
    category: 'X',
    concern: 'Genital-development abnormalities in male fetus. Pregnant women should not handle crushed tablets.',
  },

  // ── Category D / D-late (clear risk) ──
  {
    // ACEi: catch with the 'pril' suffix as elsewhere.
    keywords: ['captopril', 'enalapril', 'lisinopril', 'ramipril', 'perindopril', 'pril'],
    label: 'ACE inhibitor',
    category: 'D',
    concern: 'Fetopathy in 2nd/3rd trimester — oligohydramnios, renal dysgenesis, skull hypoplasia. Switch to labetalol/methyldopa.',
  },
  {
    keywords: ['losartan', 'valsartan', 'irbesartan', 'telmisartan', 'candesartan', 'sartan'],
    label: 'ARB',
    category: 'D',
    concern: 'Same fetopathy as ACE inhibitors — oligohydramnios, renal failure, neonatal hypotension.',
  },
  {
    keywords: ['ibuprofen', 'diclofenac', 'naproxen', 'indomethacin', 'ketorolac', 'meloxicam', 'celecoxib', 'piroxicam', 'mefenamic'],
    label: 'NSAID',
    category: 'D-late',
    concern: 'Premature closure of ductus arteriosus and oligohydramnios after 30 weeks. Avoid in 3rd trimester.',
  },
  {
    keywords: ['doxycycline', 'tetracycline', 'minocycline'],
    label: 'tetracycline',
    category: 'D',
    concern: 'Fetal tooth discoloration and reduced bone growth after week 16. Use alternative antibiotic.',
    alsoBreastfeeding: true,
  },
  {
    keywords: ['valproate', 'valproic acid', 'sodium valproate'],
    label: 'valproate',
    category: 'X',
    concern: 'Highest teratogenicity of any anticonvulsant — neural-tube defects, dysmorphism, autism risk, IQ loss.',
  },
  {
    keywords: ['phenytoin'],
    label: 'phenytoin',
    category: 'D',
    concern: 'Fetal hydantoin syndrome — craniofacial anomalies, growth restriction, mild cognitive impairment.',
  },
  {
    keywords: ['carbamazepine', 'tegretol'],
    label: 'carbamazepine',
    category: 'D',
    concern: 'Neural-tube defects (~1%); supplement folate at high dose if continuation is required.',
  },
  {
    keywords: ['lithium'],
    label: 'lithium',
    category: 'D',
    concern: 'Ebstein anomaly (cardiac) — risk highest in 1st trimester. Discuss with psychiatry before changing.',
    alsoBreastfeeding: true,
  },
  {
    keywords: ['simvastatin', 'atorvastatin', 'rosuvastatin', 'pravastatin', 'lovastatin', 'statin'],
    label: 'statin',
    category: 'X',
    concern: 'Theoretical teratogenicity from cholesterol-synthesis inhibition. Hold for the duration of pregnancy.',
  },
  {
    keywords: ['fluconazole'],
    label: 'fluconazole (high dose)',
    category: 'D',
    concern: 'High-dose (≥400 mg/day) chronic use — craniofacial, skeletal, cardiac defects. Single-dose 150 mg considered safer.',
  },
  {
    keywords: ['leflunomide'],
    label: 'leflunomide',
    category: 'X',
    concern: 'Animal teratogen with very long half-life — washout protocol with cholestyramine required pre-conception.',
  },

  // ── Caution (well-evidenced concern, not formal D/X) ──
  {
    keywords: ['gentamicin', 'amikacin', 'tobramycin', 'streptomycin'],
    label: 'aminoglycoside',
    category: 'caution',
    concern: 'Eighth-cranial-nerve toxicity — fetal sensorineural deafness reported. Use only when alternative unavailable.',
  },
  {
    keywords: ['trimethoprim', 'cotrimoxazole', 'co-trimoxazole', 'septrin', 'bactrim', 'sulfamethoxazole'],
    label: 'co-trimoxazole / trimethoprim',
    category: 'caution',
    concern: '1st trimester: folate-antagonism → neural-tube defects. 3rd trimester: kernicterus risk in neonate.',
  },
  {
    keywords: ['chloramphenicol'],
    label: 'chloramphenicol',
    category: 'caution',
    concern: 'Grey-baby syndrome in neonate exposed near delivery. Avoid in late pregnancy and lactation.',
    alsoBreastfeeding: true,
  },
];

/**
 * Free-text tokens that mean "this patient is pregnant". Lowercased.
 * The list is permissive on natural-language phrasing but conservative
 * on structure: we want "G2P1", "first trimester", "20 weeks pregnant"
 * to all hit, but we don't want "pregnancy test negative" to fire
 * (handled by the negation guard below).
 */
const PREGNANCY_TOKENS = [
  'pregnant',
  'pregnancy',
  'gestation',
  'gestational',
  'gravid',
  'trimester',
  'antenatal',
  'expecting',
  'in utero',
  'fetus',
  'foetus',
];

const BREASTFEEDING_TOKENS = [
  'breastfeeding',
  'breast-feeding',
  'breast feeding',
  'lactating',
  'lactation',
  'nursing mother',
];

/**
 * GxPy obstetric notation (gravida/para). Matches "G2P1", "G3 P0",
 * "G1P0+0", "g2p1". Case-insensitive. The presence of GxPy is a
 * strong signal that the chart is in obstetric context — most
 * clinicians don't write GxPy unless it's relevant.
 */
const GP_NOTATION = /\bg\s*\d+\s*p\s*\d+/i;

/**
 * Negation patterns we explicitly exclude. "pregnancy test negative"
 * literally contains "pregnancy" — naïve substring match would fire.
 * The list is short on purpose; the dialog includes the matched
 * evidence string so the prescriber can sanity-check us.
 */
const PREGNANCY_NEGATIONS = [
  'not pregnant',
  'no pregnancy',
  'pregnancy test negative',
  'pregnancy ruled out',
  'denies pregnancy',
  'ectopic ruled out',
  'pregnancy: no',
  'pregnancy - no',
];

/**
 * Try to detect a pregnancy / breastfeeding state from free-text
 * conditions. Returns the state and the matched evidence substring,
 * or null when no signal is found or a negation guard fires first.
 */
function findPregnancyState(
  chronicConditions: string | null | undefined,
): { state: PregnancyState; evidence: string } | null {
  if (!chronicConditions) return null;
  const s = chronicConditions.toLowerCase();

  // Negation guards run first — they short-circuit a positive match
  // when the chart explicitly rules pregnancy out. Do NOT invert this:
  // running positives first would cause "pregnancy test negative" to
  // fire on the bare token "pregnancy".
  for (const neg of PREGNANCY_NEGATIONS) {
    if (s.includes(neg)) return null;
  }

  // GP notation is the strongest pregnancy signal — match the actual
  // matched substring back so the dialog can show "G2P1".
  const gp = s.match(GP_NOTATION);
  if (gp) return { state: 'pregnant', evidence: gp[0].toUpperCase() };

  for (const tok of PREGNANCY_TOKENS) {
    if (s.includes(tok)) return { state: 'pregnant', evidence: tok };
  }

  for (const tok of BREASTFEEDING_TOKENS) {
    if (s.includes(tok)) return { state: 'breastfeeding', evidence: tok };
  }

  return null;
}

/** Map a drug name to its teratogen rule, or null. */
function classifyDrug(drugName: string): TeratogenRule | null {
  const n = drugName.toLowerCase();
  for (const rule of TERATOGENS) {
    for (const kw of rule.keywords) {
      if (n.includes(kw)) return rule;
    }
  }
  return null;
}

/**
 * Phase 13b — structured `pregnancyStatus` enum lookup. Mirrors the
 * frontend `PregnancyStatus` union (which mirrors the backend enum).
 *
 * Mapping rules:
 *   - PREGNANT             → fires as 'pregnant'.
 *   - POSSIBLY_PREGNANT    → fires as 'pregnant' (conservative — Cat-X
 *                            warnings should fire even on suspicion).
 *   - BREASTFEEDING        → fires as 'breastfeeding' (rules table
 *                            decides whether the drug applies).
 *   - NOT_PREGNANT         → suppress the check entirely.
 *   - NOT_APPLICABLE       → suppress the check entirely.
 *   - UNKNOWN              → null → caller falls back to text scan.
 *   - null / undefined     → null → caller falls back to text scan.
 *
 * Returns either a {state, evidence} pair (check fires) OR the
 * sentinel string `'suppress'` (check is explicitly off — do NOT
 * fall back to free text) OR null (no structured signal — fall back).
 */
function structuredPregnancyState(
  pregnancyStatus: string | null | undefined,
): { state: PregnancyState; evidence: string } | 'suppress' | null {
  if (pregnancyStatus == null) return null;
  switch (pregnancyStatus) {
    case 'PREGNANT':
      return { state: 'pregnant', evidence: 'recorded as pregnant' };
    case 'POSSIBLY_PREGNANT':
      return { state: 'pregnant', evidence: 'recorded as possibly pregnant' };
    case 'BREASTFEEDING':
      return { state: 'breastfeeding', evidence: 'recorded as breastfeeding' };
    case 'NOT_PREGNANT':
    case 'NOT_APPLICABLE':
      return 'suppress';
    case 'UNKNOWN':
    default:
      return null;
  }
}

/**
 * Run the teratogen check. Returns 0 or 1 matches. Returns [] when:
 *   - drug isn't in our teratogen rules table, OR
 *   - structured pregnancyStatus suppresses the check (NOT_PREGNANT,
 *     NOT_APPLICABLE), OR
 *   - no pregnancy / breastfeeding evidence (structured null AND no
 *     free-text match), OR
 *   - state is breastfeeding but the drug isn't flagged for lactation.
 *
 * Phase 13b — `pregnancyStatus` is the primary trigger. The free-text
 * `chronicConditions` scan is preserved as a fallback so legacy
 * records (no structured value) still get coverage. Order:
 *   1. If `pregnancyStatus` is NOT_PREGNANT or NOT_APPLICABLE → quiet.
 *   2. If `pregnancyStatus` is PREGNANT / POSSIBLY_PREGNANT /
 *      BREASTFEEDING → fire on that signal directly (no text scan).
 *   3. If `pregnancyStatus` is null / UNKNOWN → fall back to scanning
 *      `chronicConditions` for legacy text markers.
 *
 * Pregnancy state always trumps breastfeeding when both are mentioned
 * in the same chart text — pregnancy is the more dangerous window.
 */
export function checkTeratogenRisk(
  drugName: string | null | undefined,
  chronicConditions: string | null | undefined,
  pregnancyStatus?: string | null,
): TeratogenMatch[] {
  if (!drugName) return [];

  const rule = classifyDrug(drugName);
  if (!rule) return [];

  // Phase 13b — try the structured field first.
  const structured = structuredPregnancyState(pregnancyStatus);
  if (structured === 'suppress') return [];

  const found = structured ?? findPregnancyState(chronicConditions);
  if (!found) return [];

  // Drug isn't a known lactation concern but the patient is breast-
  // feeding (not pregnant). Stay silent — the rules table only flags
  // lactation explicitly via `alsoBreastfeeding`.
  if (found.state === 'breastfeeding' && !rule.alsoBreastfeeding) {
    return [];
  }

  return [{
    drugClassLabel: rule.label,
    category: rule.category,
    state: found.state,
    evidence: found.evidence,
    concern: rule.concern,
  }];
}

/**
 * Wire-format snapshot for the V24 audit column. `[teratogen]` tag
 * prefix mirrors `[duplicate]` (Phase 11), `[overdose]` (Phase 9),
 * `[renal]` (Phase 12a). A SQL `LIKE '%[teratogen]%'` filter pulls
 * teratogen overrides out for the safety report without a new
 * column. The category is embedded in brackets so a downstream
 * consumer can distinguish category-X (absolute) from category-D
 * (relative) without re-classifying the drug.
 *
 * Example output:
 *   "[teratogen][X] warfarin — pregnant ('G2P1') — Warfarin
 *    embryopathy and fetal CNS bleeding. Switch to LMWH."
 */
export function formatTeratogenMatches(matches: TeratogenMatch[]): string {
  return matches
    .map(
      (m) =>
        `[teratogen][${m.category}] ${m.drugClassLabel} — ${m.state} ('${m.evidence}') — ${m.concern}`,
    )
    .join('; ');
}
