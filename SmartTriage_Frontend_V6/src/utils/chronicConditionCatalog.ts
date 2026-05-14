/* ════════════════════════════════════════════════════════════════════
   Chronic-condition catalog — curated list of common conditions seen
   in Rwandan ED practice.

   Drives the chronic-conditions dropdown the same way the formulary
   drives the allergy dropdown. Each entry has:
     • a short canonical CODE (HTN, T2DM, CKD, SCD…) — persisted to
       conditionCode so the safety engine can lookup by code without
       string-matching the display label,
     • the display label,
     • an optional category for grouping,
     • optional safety-engine tags so the renal-risk / teratogen /
       hypoglycemia checks can read the patient's structured list
       and decide "yes, this patient has CKD" from the codes alone.

   Adding entries: keep the catalog conservative — only conditions
   that change ED management. Codes are uppercase, short, and stable
   (rename = data migration), so think before changing one.
   ════════════════════════════════════════════════════════════════════ */

export interface ChronicConditionCatalogEntry {
  /** Stable short code, uppercase. */
  code: string;
  /** Display label. */
  label: string;
  /** Display category for grouped pickers. */
  category:
    | 'Cardiovascular'
    | 'Endocrine / Metabolic'
    | 'Respiratory'
    | 'Renal'
    | 'Hepatic'
    | 'Infectious'
    | 'Hematologic'
    | 'Neurological'
    | 'Gastrointestinal'
    | 'Oncology'
    | 'Mental health'
    | 'Other';
  /** Safety-engine flags. Used by renalRiskCheck, teratogenCheck, etc. */
  flags?: {
    /** Triggers the renal-risk safety dialog when ACTIVE/CONTROLLED. */
    ckd?: boolean;
    /** Triggers the teratogen safety dialog. */
    pregnancy?: boolean;
    /** Diabetes — drives hypoglycemia engine + dose-adjust hints. */
    diabetes?: boolean;
    /** Patient on chronic anticoagulation. */
    anticoagulated?: boolean;
    /** Hepatic dysfunction — flags meds metabolised hepatically. */
    hepatic?: boolean;
  };
  /** Concise help text shown under the chip in the dropdown. */
  help?: string;
}

export const CHRONIC_CONDITION_CATALOG: ChronicConditionCatalogEntry[] = [
  // ── Cardiovascular ──────────────────────────────────────────────
  { code: 'HTN',          label: 'Hypertension',                 category: 'Cardiovascular' },
  { code: 'CHF',          label: 'Heart failure',                category: 'Cardiovascular', help: 'Congestive heart failure / any NYHA class' },
  { code: 'CAD',          label: 'Coronary artery disease',      category: 'Cardiovascular' },
  { code: 'MI_HX',        label: 'Prior myocardial infarction',  category: 'Cardiovascular' },
  { code: 'AF',           label: 'Atrial fibrillation',          category: 'Cardiovascular' },
  { code: 'RHD',          label: 'Rheumatic heart disease',      category: 'Cardiovascular', help: 'Common in young adults post strep' },
  { code: 'STROKE_HX',    label: 'Prior stroke / TIA',           category: 'Cardiovascular' },
  { code: 'ANTICOAG',     label: 'On chronic anticoagulation',   category: 'Cardiovascular', flags: { anticoagulated: true }, help: 'Warfarin / DOAC' },

  // ── Endocrine / Metabolic ──────────────────────────────────────
  { code: 'T1DM',         label: 'Type 1 diabetes',              category: 'Endocrine / Metabolic', flags: { diabetes: true } },
  { code: 'T2DM',         label: 'Type 2 diabetes',              category: 'Endocrine / Metabolic', flags: { diabetes: true } },
  { code: 'HYPOTHY',      label: 'Hypothyroidism',               category: 'Endocrine / Metabolic' },
  { code: 'HYPERTHY',     label: 'Hyperthyroidism',              category: 'Endocrine / Metabolic' },
  { code: 'ADRENAL',      label: 'Adrenal insufficiency',        category: 'Endocrine / Metabolic', help: 'Addison / steroid-dependent' },

  // ── Respiratory ────────────────────────────────────────────────
  { code: 'ASTHMA',       label: 'Asthma',                       category: 'Respiratory' },
  { code: 'COPD',         label: 'COPD',                         category: 'Respiratory' },
  { code: 'TB_ACTIVE',    label: 'Tuberculosis (active)',        category: 'Respiratory' },
  { code: 'TB_TREATED',   label: 'Tuberculosis (treated)',       category: 'Respiratory' },

  // ── Renal ──────────────────────────────────────────────────────
  { code: 'CKD',          label: 'Chronic kidney disease',       category: 'Renal', flags: { ckd: true }, help: 'Specify stage in notes' },
  { code: 'ESRD',         label: 'End-stage renal disease',      category: 'Renal', flags: { ckd: true }, help: 'Dialysis-dependent' },

  // ── Hepatic ────────────────────────────────────────────────────
  { code: 'HEP_B',        label: 'Chronic hepatitis B',          category: 'Hepatic', flags: { hepatic: true } },
  { code: 'HEP_C',        label: 'Chronic hepatitis C',          category: 'Hepatic', flags: { hepatic: true } },
  { code: 'CIRRHOSIS',    label: 'Cirrhosis',                    category: 'Hepatic', flags: { hepatic: true } },

  // ── Infectious / immunologic ───────────────────────────────────
  { code: 'HIV_ART',      label: 'HIV — on ART',                 category: 'Infectious', help: 'Note current VL and regimen' },
  { code: 'HIV_UNTREATED',label: 'HIV — untreated',              category: 'Infectious' },
  { code: 'HIV_LTFU',     label: 'HIV — lost to follow-up',      category: 'Infectious' },

  // ── Hematologic ────────────────────────────────────────────────
  { code: 'SCD',          label: 'Sickle cell disease',          category: 'Hematologic', help: 'Note last crisis date in notes' },
  { code: 'SCT',          label: 'Sickle cell trait',            category: 'Hematologic' },
  { code: 'THAL',         label: 'Thalassemia',                  category: 'Hematologic' },
  { code: 'ANEMIA',       label: 'Chronic anemia',               category: 'Hematologic' },
  { code: 'BLEED_DIS',    label: 'Bleeding disorder',            category: 'Hematologic', help: 'Haemophilia / vWD / etc.' },

  // ── Neurological ───────────────────────────────────────────────
  { code: 'EPILEPSY',     label: 'Epilepsy',                     category: 'Neurological' },
  { code: 'MIGRAINE',     label: 'Migraine (chronic)',           category: 'Neurological' },
  { code: 'PARKINSON',    label: 'Parkinson disease',            category: 'Neurological' },

  // ── Gastrointestinal ───────────────────────────────────────────
  { code: 'PUD',          label: 'Peptic ulcer disease',         category: 'Gastrointestinal' },
  { code: 'IBD',          label: 'Inflammatory bowel disease',   category: 'Gastrointestinal' },
  { code: 'GERD',         label: 'GERD',                         category: 'Gastrointestinal' },

  // ── Oncology ───────────────────────────────────────────────────
  { code: 'CA_ACTIVE',    label: 'Cancer — active treatment',    category: 'Oncology', help: 'Specify type + regimen in notes' },
  { code: 'CA_REMISSION', label: 'Cancer — in remission',        category: 'Oncology' },

  // ── Mental health ──────────────────────────────────────────────
  { code: 'DEPRESSION',   label: 'Depression',                   category: 'Mental health' },
  { code: 'ANXIETY',      label: 'Anxiety disorder',             category: 'Mental health' },
  { code: 'BIPOLAR',      label: 'Bipolar disorder',             category: 'Mental health' },
  { code: 'SCHIZ',        label: 'Schizophrenia',                category: 'Mental health' },

  // ── Other ──────────────────────────────────────────────────────
  { code: 'CHRONIC_PAIN', label: 'Chronic pain syndrome',        category: 'Other' },
  { code: 'ARTHRITIS',    label: 'Severe arthritis',             category: 'Other' },
  { code: 'CTD',          label: 'Connective tissue disease',    category: 'Other', help: 'SLE / RA / scleroderma / etc.' },
];

/** O(1) lookup table by code — used by the safety-engine checks. */
const BY_CODE: Record<string, ChronicConditionCatalogEntry> = Object.fromEntries(
  CHRONIC_CONDITION_CATALOG.map((c) => [c.code, c]),
);

export function getCatalogEntry(code: string | null | undefined): ChronicConditionCatalogEntry | null {
  if (!code) return null;
  return BY_CODE[code.trim().toUpperCase()] ?? null;
}

/**
 * Case-insensitive search across code + label + category. Used by
 * the chip-style searchable picker in PatientChronicConditionsPanel.
 */
export function searchCatalog(query: string): ChronicConditionCatalogEntry[] {
  const q = query.trim().toLowerCase();
  if (!q) return CHRONIC_CONDITION_CATALOG;
  return CHRONIC_CONDITION_CATALOG.filter(
    (c) =>
      c.code.toLowerCase().includes(q)
      || c.label.toLowerCase().includes(q)
      || c.category.toLowerCase().includes(q),
  );
}

/**
 * Quick helpers for the safety-engine integrations. The renal / teratogen /
 * diabetes checks should read these instead of substring-matching the
 * legacy chronicConditions free-text column. Caller passes the array
 * of structured rows (already filtered to ACTIVE + CONTROLLED).
 */
export function hasFlaggedCondition(
  rows: ReadonlyArray<{ conditionCode?: string | null; status: import('@/api/types').ChronicConditionStatus }>,
  flag: keyof NonNullable<ChronicConditionCatalogEntry['flags']>,
): boolean {
  for (const r of rows) {
    if (r.status !== 'ACTIVE' && r.status !== 'CONTROLLED') continue;
    const entry = getCatalogEntry(r.conditionCode);
    if (entry?.flags?.[flag]) return true;
  }
  return false;
}
