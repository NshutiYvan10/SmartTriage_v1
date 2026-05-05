/**
 * interactionCheck — match a prescribed drug against the visit's
 * currently active medications and flag known dangerous combinations.
 *
 * Why this exists:
 *   The prescriber is looking at one screen and one drug. They are
 *   not memorizing "this patient is also on warfarin and clarithromycin
 *   from yesterday's order." Drug–drug interactions (DDIs) are the
 *   second-leading cause of preventable medication harm after allergy
 *   mismatches.
 *
 *   Backend pharmacy services with full DDI databases (FDB, Lexicomp)
 *   are the right answer long-term. This is the bridge: a hardcoded,
 *   high-value subset of the most clinically actionable interactions,
 *   surfaced at order entry as a hard-stop dialog. Every entry here
 *   reflects a top-tier interaction in the standard references —
 *   chosen because the consequence is catastrophic and the evidence
 *   is uncontested.
 *
 * Strategy:
 *   1. Classify the prescribed drug into one or more drug classes by
 *      keyword match.
 *   2. Classify each active prescription on the visit the same way.
 *   3. For every (prescribed_class, active_class) pair, look up the
 *      INTERACTIONS table. If hit, record the conflict.
 *
 * Same-class duplicates (two NSAIDs, two ACEi) are NOT handled by the
 * interaction matcher — they get their own check at the bottom of
 * this file (`checkDuplicateTherapy`) because the framing differs:
 * "additive same effect" rather than "drug X breaks drug Y".
 *
 * Out of scope (intentionally):
 *   - Dose-dependent interactions (most of these are dose-relevant,
 *     but encoding "high dose" requires parsing free-text dose).
 *   - Time-windowed interactions ("within 14 days of MAOI").
 *
 * Calibrated for false positives: a clinician can override after
 * reading the dialog. A missed major interaction can kill.
 */

export type Severity = 'major' | 'contraindicated';

/**
 * A drug class — a set of keywords that, if found as a substring of
 * the lowercased drug name, classifies the drug into this class.
 */
interface DrugClass {
  /** Stable key used in the INTERACTIONS table. */
  key: string;
  /** Human-readable label shown to the clinician. */
  label: string;
  /** Lowercase substrings to match against drug names. */
  keywords: string[];
}

const CLASSES: DrugClass[] = [
  { key: 'warfarin',     label: 'warfarin',                keywords: ['warfarin', 'coumadin'] },
  { key: 'doac',         label: 'DOAC',                    keywords: ['rivaroxaban', 'apixaban', 'dabigatran', 'edoxaban'] },
  { key: 'aspirin',      label: 'aspirin',                 keywords: ['aspirin', 'acetylsalicylic'] },
  { key: 'clopidogrel',  label: 'clopidogrel/antiplatelet', keywords: ['clopidogrel', 'ticagrelor', 'prasugrel'] },
  { key: 'nsaid',        label: 'NSAID',                   keywords: ['ibuprofen', 'diclofenac', 'naproxen', 'indomethacin', 'ketorolac', 'meloxicam', 'celecoxib', 'piroxicam', 'mefenamic'] },
  { key: 'macrolide',    label: 'macrolide',               keywords: ['erythromycin', 'azithromycin', 'clarithromycin'] },
  { key: 'metronidazole', label: 'metronidazole',          keywords: ['metronidazole', 'flagyl'] },
  { key: 'cotrim',       label: 'co-trimoxazole (TMP-SMX)', keywords: ['cotrimoxazole', 'co-trimoxazole', 'sulfamethoxazole', 'septrin', 'bactrim', 'trimethoprim'] },
  { key: 'amiodarone',   label: 'amiodarone',              keywords: ['amiodarone'] },
  { key: 'methotrexate', label: 'methotrexate',            keywords: ['methotrexate'] },
  { key: 'acei',         label: 'ACE inhibitor',           keywords: ['captopril', 'enalapril', 'lisinopril', 'ramipril', 'perindopril', 'pril'] },
  { key: 'arb',          label: 'ARB',                     keywords: ['losartan', 'valsartan', 'irbesartan', 'telmisartan', 'candesartan', 'sartan'] },
  { key: 'k_sparing',    label: 'potassium-sparing diuretic', keywords: ['spironolactone', 'eplerenone', 'amiloride', 'triamterene'] },
  { key: 'k_supp',       label: 'potassium supplement',    keywords: ['potassium chloride', 'kcl', 'potassium citrate'] },
  { key: 'maoi',         label: 'MAOI',                    keywords: ['phenelzine', 'tranylcypromine', 'isocarboxazid', 'selegiline', 'moclobemide'] },
  { key: 'ssri',         label: 'SSRI/SNRI',               keywords: ['fluoxetine', 'sertraline', 'paroxetine', 'citalopram', 'escitalopram', 'venlafaxine', 'duloxetine'] },
  { key: 'tramadol',     label: 'tramadol',                keywords: ['tramadol'] },
  { key: 'pethidine',    label: 'pethidine/meperidine',    keywords: ['pethidine', 'meperidine'] },
  { key: 'statin',       label: 'statin',                  keywords: ['simvastatin', 'atorvastatin', 'rosuvastatin', 'pravastatin', 'lovastatin', 'statin'] },
  { key: 'azole',        label: 'azole antifungal',        keywords: ['ketoconazole', 'itraconazole', 'fluconazole', 'voriconazole', 'posaconazole'] },
  { key: 'beta_blocker', label: 'beta-blocker',            keywords: ['propranolol', 'atenolol', 'metoprolol', 'bisoprolol', 'carvedilol', 'labetalol', 'olol'] },
  { key: 'nondhp_ccb',   label: 'non-DHP calcium channel blocker', keywords: ['verapamil', 'diltiazem'] },
  { key: 'opioid',       label: 'opioid',                  keywords: ['morphine', 'codeine', 'pethidine', 'tramadol', 'fentanyl', 'oxycodone', 'hydrocodone', 'methadone', 'buprenorphine'] },
  { key: 'benzo',        label: 'benzodiazepine',          keywords: ['diazepam', 'lorazepam', 'midazolam', 'clonazepam', 'alprazolam', 'oxazepam', 'temazepam'] },
  { key: 'digoxin',      label: 'digoxin',                 keywords: ['digoxin'] },
  { key: 'sildenafil',   label: 'PDE5 inhibitor',          keywords: ['sildenafil', 'tadalafil', 'vardenafil'] },
  { key: 'nitrate',      label: 'nitrate',                 keywords: ['nitroglycerin', 'glyceryl trinitrate', 'isosorbide'] },
  { key: 'allopurinol',  label: 'allopurinol',             keywords: ['allopurinol'] },
  { key: 'azathioprine', label: 'azathioprine / 6-MP',     keywords: ['azathioprine', 'mercaptopurine', '6-mp'] },
  { key: 'lithium',      label: 'lithium',                 keywords: ['lithium'] },
  { key: 'loop_diuretic', label: 'loop diuretic',          keywords: ['furosemide', 'frusemide', 'bumetanide', 'torsemide'] },
  { key: 'thiazide',     label: 'thiazide diuretic',       keywords: ['hydrochlorothiazide', 'bendroflumethiazide', 'indapamide', 'chlorthalidone'] },
];

/** A pair-wise interaction. The pair is unordered — checked both directions. */
interface InteractionPair {
  a: string;
  b: string;
  severity: Severity;
  mechanism: string;
}

const INTERACTIONS: InteractionPair[] = [
  // Anticoagulant + bleeding risk
  { a: 'warfarin', b: 'aspirin',     severity: 'major', mechanism: 'additive bleeding risk' },
  { a: 'warfarin', b: 'clopidogrel', severity: 'major', mechanism: 'additive bleeding risk' },
  { a: 'warfarin', b: 'nsaid',       severity: 'major', mechanism: 'GI bleeding + INR elevation' },
  { a: 'doac',     b: 'aspirin',     severity: 'major', mechanism: 'additive bleeding risk' },
  { a: 'doac',     b: 'clopidogrel', severity: 'major', mechanism: 'additive bleeding risk' },
  { a: 'doac',     b: 'nsaid',       severity: 'major', mechanism: 'GI bleeding risk' },

  // Warfarin INR-altering interactions
  { a: 'warfarin', b: 'macrolide',     severity: 'major', mechanism: 'CYP inhibition → INR↑' },
  { a: 'warfarin', b: 'metronidazole', severity: 'major', mechanism: 'CYP inhibition → INR↑' },
  { a: 'warfarin', b: 'cotrim',        severity: 'major', mechanism: 'CYP inhibition → INR↑' },
  { a: 'warfarin', b: 'amiodarone',    severity: 'major', mechanism: 'CYP inhibition → INR↑' },
  { a: 'warfarin', b: 'azole',         severity: 'major', mechanism: 'CYP inhibition → INR↑' },

  // Methotrexate
  { a: 'methotrexate', b: 'nsaid',  severity: 'major', mechanism: 'reduced clearance → toxicity' },
  { a: 'methotrexate', b: 'cotrim', severity: 'contraindicated', mechanism: 'additive bone-marrow suppression' },

  // Hyperkalemia stack
  { a: 'acei', b: 'k_sparing',  severity: 'major', mechanism: 'hyperkalemia / AKI' },
  { a: 'acei', b: 'k_supp',     severity: 'major', mechanism: 'hyperkalemia' },
  { a: 'acei', b: 'arb',        severity: 'major', mechanism: 'AKI / hyperkalemia (dual RAAS blockade)' },
  { a: 'arb',  b: 'k_sparing',  severity: 'major', mechanism: 'hyperkalemia / AKI' },
  { a: 'arb',  b: 'k_supp',     severity: 'major', mechanism: 'hyperkalemia' },

  // Serotonin syndrome
  { a: 'maoi',     b: 'ssri',     severity: 'contraindicated', mechanism: 'serotonin syndrome' },
  { a: 'maoi',     b: 'tramadol', severity: 'contraindicated', mechanism: 'serotonin syndrome + seizures' },
  { a: 'maoi',     b: 'pethidine', severity: 'contraindicated', mechanism: 'serotonin syndrome' },
  { a: 'ssri',     b: 'tramadol', severity: 'major',           mechanism: 'serotonin syndrome' },
  { a: 'ssri',     b: 'pethidine', severity: 'major',          mechanism: 'serotonin syndrome' },

  // Statin myopathy / rhabdomyolysis
  { a: 'statin', b: 'macrolide', severity: 'major', mechanism: 'rhabdomyolysis risk' },
  { a: 'statin', b: 'azole',     severity: 'major', mechanism: 'rhabdomyolysis risk' },

  // Cardiac
  { a: 'beta_blocker', b: 'nondhp_ccb', severity: 'major', mechanism: 'bradycardia / AV block' },
  { a: 'digoxin',      b: 'amiodarone', severity: 'major', mechanism: 'digoxin toxicity' },

  // Respiratory depression
  { a: 'opioid', b: 'benzo', severity: 'major', mechanism: 'respiratory depression' },

  // PDE5 + nitrate
  { a: 'sildenafil', b: 'nitrate', severity: 'contraindicated', mechanism: 'severe hypotension' },

  // Bone-marrow suppression
  { a: 'allopurinol', b: 'azathioprine', severity: 'contraindicated', mechanism: 'severe marrow suppression' },

  // Lithium toxicity
  { a: 'lithium', b: 'nsaid',         severity: 'major', mechanism: 'reduced clearance → toxicity' },
  { a: 'lithium', b: 'acei',          severity: 'major', mechanism: 'reduced clearance → toxicity' },
  { a: 'lithium', b: 'arb',           severity: 'major', mechanism: 'reduced clearance → toxicity' },
  { a: 'lithium', b: 'thiazide',      severity: 'major', mechanism: 'reduced clearance → toxicity' },
  { a: 'lithium', b: 'loop_diuretic', severity: 'major', mechanism: 'altered clearance' },
];

/**
 * Classify a drug name into its drug-class keys. A drug can match
 * multiple classes (e.g. "tramadol" matches both `tramadol` and the
 * generic `opioid`) — we want all of them so every applicable
 * interaction surfaces.
 */
export function classifyDrug(drugName: string | null | undefined): string[] {
  if (!drugName) return [];
  const lower = drugName.trim().toLowerCase();
  if (!lower) return [];
  const hits: string[] = [];
  for (const cls of CLASSES) {
    if (cls.keywords.some((kw) => lower.includes(kw))) {
      hits.push(cls.key);
    }
  }
  return hits;
}

/** Look up a class label by key (returns the key itself if unknown). */
function classLabel(key: string): string {
  return CLASSES.find((c) => c.key === key)?.label ?? key;
}

export interface InteractionMatch {
  /** The other drug (verbatim drug name from the active medication). */
  otherDrugName: string;
  /** Class label for the prescribed drug, e.g. "warfarin". */
  prescribedClassLabel: string;
  /** Class label for the active drug, e.g. "NSAID". */
  otherClassLabel: string;
  /** Why this interaction matters clinically. */
  mechanism: string;
  /** Severity tier. */
  severity: Severity;
}

/**
 * Active medication shape — we only need the drug name and a status
 * filter. Caller passes the full MedicationResponse list; we drop
 * anything not currently in flight (cancelled / refused).
 */
export interface ActiveMedicationLike {
  drugName: string;
  status: string;
}

/**
 * Statuses we treat as "still in effect" for DDI purposes. A
 * cancelled or refused order can't interact with anything.
 */
const ACTIVE_STATUSES = new Set(['PRESCRIBED', 'ADMINISTERED', 'HELD']);

/**
 * Check the prescribed drug against every currently active drug on
 * the visit. Deduplicated on (otherDrugName, mechanism) so two
 * concurrent NSAID orders don't produce two identical lines.
 */
export function checkInteractions(
  drugName: string | null | undefined,
  activeMeds: ActiveMedicationLike[] | null | undefined,
): InteractionMatch[] {
  if (!drugName || !activeMeds || activeMeds.length === 0) return [];
  const prescribedClasses = classifyDrug(drugName);
  if (prescribedClasses.length === 0) return [];

  const matches: InteractionMatch[] = [];
  const seen = new Set<string>();

  for (const med of activeMeds) {
    if (!ACTIVE_STATUSES.has(med.status)) continue;
    const otherClasses = classifyDrug(med.drugName);
    if (otherClasses.length === 0) continue;

    for (const pc of prescribedClasses) {
      for (const oc of otherClasses) {
        // An interaction with itself isn't a DDI — that's a duplicate
        // therapy issue (different check, not in scope here).
        if (pc === oc) continue;

        const hit = INTERACTIONS.find(
          (it) => (it.a === pc && it.b === oc) || (it.a === oc && it.b === pc),
        );
        if (!hit) continue;

        const key = `${med.drugName}|${hit.mechanism}`;
        if (seen.has(key)) continue;
        seen.add(key);

        matches.push({
          otherDrugName: med.drugName,
          prescribedClassLabel: classLabel(pc),
          otherClassLabel: classLabel(oc),
          mechanism: hit.mechanism,
          severity: hit.severity,
        });
      }
    }
  }

  return matches;
}

/**
 * Serialize matches into the wire format the backend will persist as
 * `interaction_override_matches`. Same human-readable, semicolon-
 * delimited shape as the allergy formatter.
 *
 * Example: "Aspirin 75mg + warfarin/aspirin: additive bleeding risk
 * [major]; Erythromycin + warfarin/macrolide: CYP inhibition → INR↑
 * [major]"
 */
export function formatInteractionMatches(matches: InteractionMatch[]): string {
  return matches
    .map(
      (m) =>
        `${m.otherDrugName} + ${m.prescribedClassLabel}/${m.otherClassLabel}: ${m.mechanism} [${m.severity}]`,
    )
    .join('; ');
}

// ════════════════════════════════════════════════════════════════════
//  DUPLICATE THERAPY
// ════════════════════════════════════════════════════════════════════
//
// Distinct from drug–drug interactions. A duplicate is two different
// drugs in the same therapeutic class on the same visit — e.g. two
// NSAIDs, two benzodiazepines, two ACE inhibitors. These don't always
// cross-react in a CYP/receptor sense (so they're not in the
// INTERACTIONS table) but they ARE redundant and additive — two NSAIDs
// don't help twice as much, they just double the GI/renal risk.
//
// Why a separate check (not an interaction):
//   - Mechanism is "additive of the same effect" not "drug X breaks
//     drug Y" — clinician should see different framing.
//   - More commonly intentional than DDIs (PRN + scheduled, loading +
//     maintenance, IV-to-PO bridge). Lower visual urgency in the
//     dialog (yellow rather than orange/red) acknowledges this.
//   - QA reports may want to filter "real interactions" separately
//     from "duplicate therapy" overrides.
//
// We persist the override snapshot through the existing V24
// interaction-override columns (no new migration) but tag each
// duplicate hit with a `[duplicate]` prefix so a SQL LIKE filter can
// separate the two kinds when needed.

/**
 * Classes where having two different drugs in the class on the same
 * visit is clinically meaningful. Excluded: single-drug classes
 * (warfarin, lithium, digoxin, …) where a duplicate is structurally
 * impossible, and classes already covered by the INTERACTIONS table
 * via cross-class entries.
 */
const DUPLICATE_RELEVANT_CLASSES = new Set<string>([
  'nsaid', 'opioid', 'benzo', 'acei', 'arb', 'ssri',
  'statin', 'beta_blocker', 'macrolide', 'azole',
  'k_sparing', 'thiazide', 'loop_diuretic', 'cotrim',
  'doac', 'clopidogrel', 'nondhp_ccb', 'nitrate',
]);

export interface DuplicateMatch {
  /** The other drug name (verbatim from the active medication). */
  otherDrugName: string;
  /** Class label both drugs share, e.g. "NSAID". */
  sharedClassLabel: string;
}

/**
 * Check whether the prescribed drug duplicates a therapeutic class
 * already covered by another active medication on this visit.
 *
 * Identical drug-name matches (case- and whitespace-insensitive) are
 * skipped — re-ordering the same drug is a different concern (refill,
 * route change) handled elsewhere.
 */
export function checkDuplicateTherapy(
  drugName: string | null | undefined,
  activeMeds: ActiveMedicationLike[] | null | undefined,
): DuplicateMatch[] {
  if (!drugName || !activeMeds || activeMeds.length === 0) return [];
  const prescribedClasses = classifyDrug(drugName);
  if (prescribedClasses.length === 0) return [];

  const lowerDrug = drugName.trim().toLowerCase();
  const matches: DuplicateMatch[] = [];
  const seen = new Set<string>(); // dedup on (otherDrug|classKey)

  for (const med of activeMeds) {
    if (!ACTIVE_STATUSES.has(med.status)) continue;

    // Same drug re-ordered — not a duplicate-therapy hit.
    const lowerOther = med.drugName.trim().toLowerCase();
    if (lowerDrug === lowerOther) continue;

    const otherClasses = classifyDrug(med.drugName);
    if (otherClasses.length === 0) continue;

    for (const pc of prescribedClasses) {
      if (!DUPLICATE_RELEVANT_CLASSES.has(pc)) continue;
      if (!otherClasses.includes(pc)) continue;
      const key = `${med.drugName}|${pc}`;
      if (seen.has(key)) continue;
      seen.add(key);
      matches.push({
        otherDrugName: med.drugName,
        sharedClassLabel: classLabel(pc),
      });
    }
  }
  return matches;
}

/**
 * Serialize duplicate matches with a `[duplicate]` prefix so they can
 * coexist with interaction matches in the V24 interaction-override
 * column without QA having to guess which is which.
 *
 * Example: "[duplicate] Ibuprofen 400mg (NSAID); [duplicate] Lorazepam (benzodiazepine)"
 */
export function formatDuplicateMatches(matches: DuplicateMatch[]): string {
  return matches
    .map((m) => `[duplicate] ${m.otherDrugName} (${m.sharedClassLabel})`)
    .join('; ');
}
