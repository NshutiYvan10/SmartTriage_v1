/**
 * allergyCheck — match a prescribed drug name against a free-text
 * patient allergy list and surface the conflicts.
 *
 * Why this exists:
 *   `Patient.knownAllergies` is a single free-text string ("Penicillin,
 *   sulfa drugs, peanuts"). At prescribe time we need a yes/no answer:
 *   "is this drug something the patient is allergic to?" The clinician
 *   can't be expected to expand "Penicillin" into "amoxicillin,
 *   ampicillin, flucloxacillin, ..." in their head — the system has to.
 *
 * Strategy:
 *   1. Tokenize the allergy string on common separators (comma,
 *      semicolon, newline, slash, " and ").
 *   2. For each token, normalize and look it up in CROSS_REACTIVITY to
 *      get the family of related drug names + the canonical label.
 *   3. Substring-match the drug name against any expanded term.
 *   4. Return both the matched allergen TOKEN (what the patient said)
 *      and the canonical FAMILY label so the dialog can be specific:
 *      "Patient is allergic to 'Penicillin' (beta-lactam family) — the
 *      prescribed Amoxicillin is in this family."
 *
 * False positive vs false negative tradeoff:
 *   We err toward false positives. A spurious confirmation dialog is
 *   annoying. A missed allergy is a sentinel event. Substring match
 *   ("amox" in "amoxicillin") is intentionally permissive.
 *
 * What this is NOT:
 *   This is NOT a clinical decision support engine. It's a last-line
 *   "did you mean to do this" prompt. The authoritative DDI/allergy
 *   check belongs in the backend pharmacy service.
 */

/**
 * Cross-reactivity table: when the patient says X, also flag drugs
 * matching any of the keywords in that family.
 *
 * Entries are intentionally lowercase. Add keywords liberally — the
 * cost of an extra dialog is far lower than a missed allergy.
 *
 * Each entry's `keywords[0]` is treated as the canonical allergen name
 * shown to the clinician.
 */
const CROSS_REACTIVITY: Array<{
  /** Tokens in the patient's allergy string that trigger this family. */
  triggers: string[];
  /** Drug-name keywords that, if the prescribed drug contains any,
   *  count as a hit. Always include the trigger tokens themselves. */
  keywords: string[];
  /** Family label shown in the dialog. */
  family: string;
}> = [
  {
    triggers: ['penicillin', 'pcn', 'amox', 'ampicillin', 'flucloxacillin', 'augmentin'],
    keywords: [
      'penicillin', 'amoxicillin', 'ampicillin', 'flucloxacillin',
      'cloxacillin', 'augmentin', 'amoxiclav', 'piperacillin', 'tazocin',
      'benzathine', 'benzylpenicillin', 'phenoxymethylpenicillin',
    ],
    family: 'penicillins (beta-lactam)',
  },
  {
    triggers: ['cephalosporin', 'cef', 'ceftriaxone', 'cefuroxime', 'cephalexin'],
    keywords: [
      'cef', 'ceph', 'ceftriaxone', 'cefuroxime', 'cefotaxime',
      'cefepime', 'cefazolin', 'cephalexin', 'cefixime', 'cefaclor',
    ],
    family: 'cephalosporins (beta-lactam)',
  },
  {
    triggers: ['beta-lactam', 'beta lactam', 'betalactam'],
    keywords: [
      'penicillin', 'amoxicillin', 'ampicillin', 'flucloxacillin',
      'augmentin', 'cef', 'ceph', 'meropenem', 'imipenem', 'ertapenem',
      'aztreonam',
    ],
    family: 'beta-lactams',
  },
  {
    triggers: ['sulfa', 'sulpha', 'sulfonamide', 'septrin', 'bactrim', 'cotrim', 'sulfamethoxazole'],
    keywords: [
      'sulfa', 'sulpha', 'sulfamethoxazole', 'sulfasalazine',
      'sulfadiazine', 'septrin', 'bactrim', 'cotrim', 'co-trimoxazole',
      'trimethoprim', // commonly co-formulated; flag for awareness
    ],
    family: 'sulfa drugs (sulfonamides)',
  },
  {
    triggers: ['nsaid', 'nsaids', 'aspirin', 'ibuprofen', 'diclofenac', 'naproxen'],
    keywords: [
      'aspirin', 'ibuprofen', 'diclofenac', 'naproxen', 'indomethacin',
      'ketorolac', 'mefenamic', 'piroxicam', 'celecoxib', 'meloxicam',
      'nsaid',
    ],
    family: 'NSAIDs',
  },
  {
    triggers: ['aspirin', 'asa', 'acetylsalicylic'],
    keywords: ['aspirin', 'acetylsalicylic', 'asa'],
    family: 'aspirin / salicylates',
  },
  {
    triggers: ['macrolide', 'erythromycin', 'azithromycin', 'clarithromycin'],
    keywords: ['erythromycin', 'azithromycin', 'clarithromycin', 'macrolide'],
    family: 'macrolides',
  },
  {
    triggers: ['quinolone', 'ciprofloxacin', 'levofloxacin', 'moxifloxacin', 'fluoroquinolone'],
    keywords: ['ciprofloxacin', 'levofloxacin', 'moxifloxacin', 'norfloxacin', 'ofloxacin', 'floxacin'],
    family: 'fluoroquinolones',
  },
  {
    triggers: ['tetracycline', 'doxycycline'],
    keywords: ['tetracycline', 'doxycycline', 'minocycline'],
    family: 'tetracyclines',
  },
  {
    triggers: ['opioid', 'opiate', 'morphine', 'codeine', 'pethidine', 'tramadol', 'fentanyl'],
    keywords: ['morphine', 'codeine', 'pethidine', 'tramadol', 'fentanyl', 'oxycodone', 'hydrocodone', 'methadone', 'buprenorphine'],
    family: 'opioids',
  },
];

/** Words that look like allergies but are explicit "no allergy" markers. */
const NEGATION_TOKENS = new Set([
  '', 'none', 'n/a', 'na', 'nil', 'no known', 'no known allergies',
  'nka', 'nkda', 'unknown', 'no allergies',
]);

/**
 * Split a free-text allergy string into individual lowercase tokens.
 * Keeps multi-word tokens like "no known allergies" intact (they get
 * filtered as negations later).
 */
export function tokenizeAllergies(raw: string | null | undefined): string[] {
  if (!raw) return [];
  return raw
    .split(/[,;/\n]|\s+and\s+|\s+&\s+/i)
    .map((t) => t.trim().toLowerCase())
    .filter((t) => t.length > 0)
    .filter((t) => !NEGATION_TOKENS.has(t));
}

export interface AllergyMatch {
  /** The token from the patient's allergy list that triggered this. */
  patientAllergen: string;
  /** Canonical family label, when the trigger came via cross-reactivity. */
  family?: string;
  /** Why we matched: 'direct' = drug name contains the allergen token
   *  itself; 'cross' = drug name matches a sibling in the family. */
  matchType: 'direct' | 'cross';
  /** Workflow 2 — structured severity from PatientAllergyResponse,
   *  set only when the match came from a structured row. Free-text
   *  fallback matches leave this undefined (the dialog then treats
   *  the match as MODERATE — a safe default). */
  severity?: import('@/api/types').AllergySeverity;
  /** Reaction text from the structured allergy record, if any. */
  reaction?: string;
  /** Source row id — lets the dialog deep-link "open allergy record". */
  allergyId?: string;
}

/**
 * Check whether the prescribed drug name conflicts with any token in
 * the patient's free-text allergy list. Returns all matches (a drug
 * can hit multiple families — e.g. ceftriaxone in a patient who lists
 * both "penicillin" and "cephalosporin").
 */
export function checkDrugAgainstAllergies(
  drugName: string | null | undefined,
  knownAllergies: string | null | undefined,
): AllergyMatch[] {
  if (!drugName || !knownAllergies) return [];
  const drug = drugName.trim().toLowerCase();
  if (!drug) return [];

  const tokens = tokenizeAllergies(knownAllergies);
  if (tokens.length === 0) return [];

  const matches: AllergyMatch[] = [];
  const seen = new Set<string>(); // dedup on (patientAllergen|family)

  for (const token of tokens) {
    // 1) Direct substring match: drug name contains the patient's
    //    allergen token verbatim. e.g. token="peanuts" — only matches
    //    a drug literally containing "peanuts" (rare, but correct).
    if (drug.includes(token)) {
      const key = `${token}|direct`;
      if (!seen.has(key)) {
        matches.push({ patientAllergen: token, matchType: 'direct' });
        seen.add(key);
      }
    }

    // 2) Cross-reactivity: token belongs to a family; check if drug
    //    matches any keyword in that family.
    for (const family of CROSS_REACTIVITY) {
      const triggered = family.triggers.some((trig) => token.includes(trig));
      if (!triggered) continue;
      const hit = family.keywords.some((kw) => drug.includes(kw));
      if (!hit) continue;
      const key = `${token}|${family.family}`;
      if (seen.has(key)) continue;
      matches.push({
        patientAllergen: token,
        family: family.family,
        matchType: 'cross',
      });
      seen.add(key);
    }
  }

  return matches;
}

/**
 * Serialize matches into the wire-format string the backend persists
 * verbatim into medication_administrations.allergy_override_matches.
 * Format: "<token> [(<family>)] [severity:LABEL] [reaction:TEXT]; …"
 *
 * Stable, human-readable, not JSON — this is an audit snapshot, not
 * structured data for re-parsing.
 *
 * Example outputs:
 *   "penicillin (penicillins/beta-lactam) [severity:ANAPHYLAXIS] [reaction:facial swelling]; peanuts"
 *   "penicillin (penicillins/beta-lactam); peanuts"   (no structured detail)
 */
export function formatAllergyMatches(matches: AllergyMatch[]): string {
  return matches
    .map((m) => {
      const head = m.matchType === 'cross' && m.family
        ? `${m.patientAllergen} (${m.family})`
        : m.patientAllergen;
      const severity = m.severity ? ` [severity:${m.severity}]` : '';
      const reaction = m.reaction ? ` [reaction:${m.reaction}]` : '';
      return `${head}${severity}${reaction}`;
    })
    .join('; ');
}

/**
 * Workflow 2 — match a prescribed drug against the patient's
 * structured allergy rows. Same family/cross-reactivity expansion as
 * {@link checkDrugAgainstAllergies} but each returned match carries
 * the source row's severity, reaction text, and id so the dialog
 * can render the right flavour (soft acknowledge for MILD; hard
 * stop with override reason for SEVERE / ANAPHYLAXIS).
 *
 * Callers should prefer this function when {@code structuredAllergies}
 * is non-empty and fall back to {@link checkDrugAgainstAllergies}
 * against the legacy free-text column otherwise — that way a patient
 * who hasn't been re-captured yet still gets a safety dialog (just
 * without graded severity).
 */
export function checkDrugAgainstStructuredAllergies(
  drugName: string | null | undefined,
  structuredAllergies: ReadonlyArray<import('@/api/types').PatientAllergyResponse>,
): AllergyMatch[] {
  if (!drugName || structuredAllergies.length === 0) return [];
  const drug = drugName.trim().toLowerCase();
  if (!drug) return [];

  const matches: AllergyMatch[] = [];
  const seen = new Set<string>();

  for (const row of structuredAllergies) {
    if (row.verificationStatus === 'REFUTED') continue;
    const token = (row.allergenName || '').trim().toLowerCase();
    if (!token) continue;

    // 1) Direct substring match in either direction.
    if (drug.includes(token) || token.includes(drug)) {
      const key = `${row.id}|direct`;
      if (!seen.has(key)) {
        matches.push({
          patientAllergen: row.allergenName,
          matchType: 'direct',
          severity: row.severity,
          reaction: row.reaction ?? undefined,
          allergyId: row.id,
        });
        seen.add(key);
      }
    }

    // 2) Cross-reactivity expansion against the family table.
    for (const family of CROSS_REACTIVITY) {
      const triggered = family.triggers.some((trig) => token.includes(trig));
      if (!triggered) continue;
      const hit = family.keywords.some((kw) => drug.includes(kw));
      if (!hit) continue;
      const key = `${row.id}|${family.family}`;
      if (seen.has(key)) continue;
      matches.push({
        patientAllergen: row.allergenName,
        family: family.family,
        matchType: 'cross',
        severity: row.severity,
        reaction: row.reaction ?? undefined,
        allergyId: row.id,
      });
      seen.add(key);
    }
  }

  return matches;
}

/**
 * Highest-severity match wins — drives the prescribe-safety dialog
 * variant and the alert-severity calibration sent back to the
 * backend. UNKNOWN is treated alongside MODERATE because we
 * deliberately don't downgrade an allergy whose reaction we don't
 * know. Returns null when no match carries a structured severity
 * (e.g. only legacy free-text matches fired).
 */
export function highestAllergySeverity(
  matches: AllergyMatch[],
): import('@/api/types').AllergySeverity | null {
  const rank: Record<import('@/api/types').AllergySeverity, number> = {
    MILD: 1,
    MODERATE: 2,
    UNKNOWN: 2,
    SEVERE: 3,
    ANAPHYLAXIS: 4,
  };
  let best: import('@/api/types').AllergySeverity | null = null;
  for (const m of matches) {
    if (!m.severity) continue;
    if (best === null || rank[m.severity] > rank[best]) best = m.severity;
  }
  return best;
}
