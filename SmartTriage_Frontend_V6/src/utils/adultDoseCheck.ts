/**
 * adultDoseCheck — flag prescribed adult doses that fall outside the
 * single-dose mg range for common ED drugs.
 *
 * Why this exists alongside pediatricDoseCheck:
 *   Paediatric dosing is mg/kg × weight. Adult dosing is fundamentally
 *   different — fixed mg ranges per drug, not weight-scaled. So we
 *   can't share the same rule table or the same arithmetic. Without
 *   this utility the safety pipeline silently skips every adult
 *   prescription, which means a clinician writing
 *   "Paracetamol 4000 mg PO" gets no warning at all. That's the gap
 *   this fills.
 *
 * Strategy:
 *   1. Match the prescribed drug to a rule by keyword substring.
 *   2. Parse a numeric mg out of the free-text dose string (reuses
 *      the same parseDoseMg from pediatricDoseCheck — same caveats:
 *      bails on "1 tab", "5 ml without strength", "/kg" shorthand).
 *   3. Compare against the rule's [minMg, maxMg] single-dose range.
 *
 * Calibrated for false-positive aversion:
 *   - Drugs we don't recognize → silent skip.
 *   - Doses we can't parse → silent skip (false-negative > false-pos).
 *   - Ranges deliberately wide where IV and PO single-doses differ
 *     significantly. We use the conservative ENVELOPE (lowest min,
 *     highest max across routes) rather than picking one route — the
 *     dose check fires only on doses that are unsafe under ANY common
 *     route. That keeps the alert credible: when it fires, the dose
 *     is wrong regardless of how the clinician intended to give it.
 *   - Drugs with >5× IV/PO disparity (adrenaline, dopamine, insulin)
 *     are intentionally OMITTED — we'd need route awareness, which
 *     this utility doesn't have. The existing allergy / interaction /
 *     renal / teratogen checks still apply to those drugs.
 *
 * Out of scope (intentionally):
 *   - Daily-total checks (would need reliable frequency parsing).
 *   - Route-specific rules (IV vs PO different ranges).
 *   - Loading-vs-maintenance distinction (loading doses for digoxin,
 *     phenytoin etc. are huge — we exclude these drugs rather than
 *     misfire on a legitimate loading dose).
 *
 * Wire format:
 *   Same `[overdose]` / `[underdose]` prefixes as pediatricDoseCheck —
 *   convergence at the audit-trail layer means MedicationService's
 *   severity ladder, the ClinicalAlert auto-creation, and the Phase 14
 *   override-audit dashboard all surface adult-dose hits without
 *   needing to know they came from a different utility.
 */

import { parseDoseMg } from './pediatricDoseCheck';

export type AdultDoseSeverity = 'overdose' | 'underdose';

export interface AdultDoseMatch {
  /** Drug name as the clinician typed it. */
  drugName: string;
  /** Free-text dose string the clinician typed. */
  doseString: string;
  /** Parsed total dose in milligrams (single dose). */
  prescribedMg: number;
  /** Lower bound of the recommended single-dose mg range. */
  minMg: number;
  /** Upper bound of the recommended single-dose mg range. */
  maxMg: number;
  /** Whether the dose is too high or too low. */
  severity: AdultDoseSeverity;
  /** Drug-class label shown in the dialog. */
  ruleLabel: string;
  /** How many times the upper bound the dose is (only for overdose).
   *  Surfaces "this is 4× the max" so magnitude is obvious. */
  foldOverMax?: number;
}

interface AdultDoseRule {
  /** Lowercase substrings — match if any appears in the drug name. */
  keywords: string[];
  /** Human label shown in the warning. */
  label: string;
  /** Lower bound of single-dose mg. */
  minMg: number;
  /** Upper bound of single-dose mg. Doses above this fire overdose. */
  maxMg: number;
}

/**
 * Top ED adult drugs with single-dose mg ranges. Sources: BNF, eMC
 * SPC sheets, UpToDate adult dosing tables. The ENVELOPE strategy
 * (see header) means each entry's [minMg, maxMg] spans common adult
 * single doses across PO/IV/IM where applicable — narrower per-route
 * ranges live in the formulary backend (out of scope here).
 *
 * Curation principle: include drugs where a >2× dose error is a real
 * risk and the safe envelope is narrow enough to warn credibly. Skip
 * drugs whose loading-vs-maintenance range is too wide to alert on
 * (digoxin, phenytoin loading, warfarin titration).
 */
const DOSE_RULES_ADULT: AdultDoseRule[] = [
  // ── Analgesics / antipyretics ────────────────────────────────
  { keywords: ['paracetamol', 'acetaminophen'], label: 'Paracetamol',  minMg: 325,  maxMg: 1000 },
  { keywords: ['ibuprofen'],                    label: 'Ibuprofen',    minMg: 200,  maxMg: 800  },
  { keywords: ['diclofenac'],                   label: 'Diclofenac',   minMg: 25,   maxMg: 100  },
  { keywords: ['naproxen'],                     label: 'Naproxen',     minMg: 250,  maxMg: 500  },
  { keywords: ['aspirin'],                      label: 'Aspirin',      minMg: 75,   maxMg: 650  },
  { keywords: ['tramadol'],                     label: 'Tramadol',     minMg: 50,   maxMg: 100  },
  { keywords: ['codeine'],                      label: 'Codeine',      minMg: 15,   maxMg: 60   },
  { keywords: ['morphine'],                     label: 'Morphine',     minMg: 2,    maxMg: 15   },
  { keywords: ['pethidine', 'meperidine'],      label: 'Pethidine',    minMg: 25,   maxMg: 150  },

  // ── Antibiotics (single-dose envelope, PO + IV combined) ─────
  { keywords: ['amoxicillin'],                  label: 'Amoxicillin',     minMg: 250,  maxMg: 1000 },
  { keywords: ['co-amoxiclav', 'augmentin'],    label: 'Co-amoxiclav',    minMg: 375,  maxMg: 1200 },
  { keywords: ['ampicillin'],                   label: 'Ampicillin',      minMg: 250,  maxMg: 2000 },
  { keywords: ['ceftriaxone'],                  label: 'Ceftriaxone',     minMg: 500,  maxMg: 2000 },
  { keywords: ['cefotaxime'],                   label: 'Cefotaxime',      minMg: 500,  maxMg: 2000 },
  { keywords: ['cefuroxime'],                   label: 'Cefuroxime',      minMg: 250,  maxMg: 1500 },
  { keywords: ['metronidazole', 'flagyl'],      label: 'Metronidazole',   minMg: 200,  maxMg: 500  },
  { keywords: ['doxycycline'],                  label: 'Doxycycline',     minMg: 100,  maxMg: 200  },
  { keywords: ['azithromycin'],                 label: 'Azithromycin',    minMg: 250,  maxMg: 500  },
  { keywords: ['clarithromycin'],               label: 'Clarithromycin',  minMg: 250,  maxMg: 500  },
  { keywords: ['erythromycin'],                 label: 'Erythromycin',    minMg: 250,  maxMg: 500  },
  { keywords: ['ciprofloxacin'],                label: 'Ciprofloxacin',   minMg: 200,  maxMg: 750  },
  { keywords: ['levofloxacin'],                 label: 'Levofloxacin',    minMg: 250,  maxMg: 750  },
  { keywords: ['cotrimoxazole', 'co-trimoxazole', 'septrin', 'bactrim'],
                                                label: 'Co-trimoxazole',  minMg: 480,  maxMg: 960  },

  // ── Cardiovascular / diuretics ───────────────────────────────
  { keywords: ['furosemide', 'frusemide', 'lasix'], label: 'Furosemide',  minMg: 20,   maxMg: 80   },
  { keywords: ['hydrochlorothiazide'],              label: 'HCTZ',         minMg: 12.5, maxMg: 50   },
  { keywords: ['spironolactone'],                   label: 'Spironolactone', minMg: 25, maxMg: 100  },
  { keywords: ['atenolol'],                         label: 'Atenolol',     minMg: 25,   maxMg: 100  },
  { keywords: ['lisinopril'],                       label: 'Lisinopril',   minMg: 5,    maxMg: 40   },
  { keywords: ['amlodipine'],                       label: 'Amlodipine',   minMg: 2.5,  maxMg: 10   },
  { keywords: ['nifedipine'],                       label: 'Nifedipine',   minMg: 5,    maxMg: 30   },
  { keywords: ['atorvastatin'],                     label: 'Atorvastatin', minMg: 10,   maxMg: 80   },
  { keywords: ['simvastatin'],                      label: 'Simvastatin',  minMg: 10,   maxMg: 40   },
  { keywords: ['clopidogrel'],                      label: 'Clopidogrel',  minMg: 75,   maxMg: 300  },

  // ── Respiratory / steroids ───────────────────────────────────
  { keywords: ['salbutamol', 'albuterol'],      label: 'Salbutamol (neb)', minMg: 2.5,  maxMg: 5    },
  { keywords: ['prednisolone', 'prednisone'],   label: 'Prednisolone',     minMg: 5,    maxMg: 60   },
  { keywords: ['hydrocortisone'],               label: 'Hydrocortisone',   minMg: 50,   maxMg: 200  },
  { keywords: ['dexamethasone'],                label: 'Dexamethasone',    minMg: 2,    maxMg: 16   },

  // ── GI ───────────────────────────────────────────────────────
  { keywords: ['ondansetron'],                  label: 'Ondansetron',      minMg: 4,    maxMg: 8    },
  { keywords: ['metoclopramide'],               label: 'Metoclopramide',   minMg: 5,    maxMg: 20   },
  { keywords: ['omeprazole'],                   label: 'Omeprazole',       minMg: 20,   maxMg: 80   },
  { keywords: ['pantoprazole'],                 label: 'Pantoprazole',     minMg: 20,   maxMg: 80   },
  { keywords: ['ranitidine'],                   label: 'Ranitidine',       minMg: 50,   maxMg: 300  },
  { keywords: ['hyoscine', 'buscopan'],         label: 'Hyoscine',         minMg: 10,   maxMg: 20   },

  // ── Sedation / neuro ─────────────────────────────────────────
  { keywords: ['diazepam'],                     label: 'Diazepam',         minMg: 2,    maxMg: 20   },
  { keywords: ['lorazepam'],                    label: 'Lorazepam',        minMg: 0.5,  maxMg: 4    },
  { keywords: ['midazolam'],                    label: 'Midazolam',        minMg: 1,    maxMg: 5    },
  { keywords: ['haloperidol'],                  label: 'Haloperidol',      minMg: 0.5,  maxMg: 10   },

  // ── Other common ─────────────────────────────────────────────
  { keywords: ['tranexamic'],                   label: 'Tranexamic acid',  minMg: 500,  maxMg: 1000 },
  { keywords: ['vitamin k', 'phytomenadione'],  label: 'Vitamin K',        minMg: 1,    maxMg: 10   },
];

/** First-match-wins lookup. Same shape as pediatricDoseCheck.findRule. */
function findAdultRule(drugName: string): AdultDoseRule | null {
  const n = drugName.toLowerCase();
  for (const rule of DOSE_RULES_ADULT) {
    for (const kw of rule.keywords) {
      if (n.includes(kw)) return rule;
    }
  }
  return null;
}

/**
 * Check an adult prescribed dose against the single-dose mg range.
 * Returns 0 or 1 matches. Returns [] when:
 *   - drug isn't in our adult rules table,
 *   - dose string doesn't parse to a mass (tabs / drops / ml / "/kg"),
 *   - dose is within range.
 *
 * The empty-array return mirrors checkPediatricDose so the call site
 * can branch identically:
 *
 *     const doseMatches = visit.isPediatric && weightKg
 *       ? checkPediatricDose(name, dose, weightKg)
 *       : checkAdultDose(name, dose);
 */
export function checkAdultDose(
  drugName: string | null | undefined,
  doseStr: string | null | undefined,
): AdultDoseMatch[] {
  if (!drugName) return [];

  const rule = findAdultRule(drugName);
  if (!rule) return [];

  const prescribedMg = parseDoseMg(doseStr);
  if (prescribedMg == null) return [];

  // Within range — silent.
  if (prescribedMg >= rule.minMg && prescribedMg <= rule.maxMg) return [];

  const severity: AdultDoseSeverity =
    prescribedMg > rule.maxMg ? 'overdose' : 'underdose';

  const match: AdultDoseMatch = {
    drugName,
    doseString: doseStr ?? '',
    prescribedMg,
    minMg: rule.minMg,
    maxMg: rule.maxMg,
    severity,
    ruleLabel: rule.label,
  };

  if (severity === 'overdose') {
    match.foldOverMax = prescribedMg / rule.maxMg;
  }

  return [match];
}

/**
 * Serialize adult dose matches for the V24 audit-trail column. Same
 * tag prefix scheme as pediatricDoseCheck so MedicationService's
 * severity ladder and the Override Audit dashboard handle adult and
 * paediatric hits identically — they're both [overdose] / [underdose]
 * on the wire, the dashboard groups them under "Overdose" / "Underdose"
 * regardless of which utility produced them.
 *
 * Example output:
 *   "[overdose] Paracetamol 4000mg (max 1000) — 4× max;
 *    [underdose] Amoxicillin 50mg (min 250)"
 */
export function formatAdultDoseMatches(matches: AdultDoseMatch[]): string {
  return matches
    .map((m) => {
      const head = `[${m.severity}] ${m.ruleLabel} ${m.doseString}`;
      const bound =
        m.severity === 'overdose' ? `(max ${m.maxMg})` : `(min ${m.minMg})`;
      const fold =
        m.foldOverMax && m.foldOverMax >= 1.5
          ? ` — ${round(m.foldOverMax)}× max`
          : '';
      return `${head} ${bound}${fold}`;
    })
    .join('; ');
}

function round(n: number): string {
  if (n >= 10) return n.toFixed(0);
  if (n >= 1) return n.toFixed(1);
  return n.toFixed(2);
}
