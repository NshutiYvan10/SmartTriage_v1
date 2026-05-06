-- ═══════════════════════════════════════════════════════════════
-- V21 — Seed `drug_formularies` with a Rwanda Essential Medicines List
--       (REML) starter set for ED prescribing.
--
-- BACKGROUND
-- ----------
-- The drug_formularies table exists (see V13 / DrugFormulary entity) but
-- ships empty. The MedicationSafetyEngine cannot do allergy / dose /
-- interaction checks without a populated formulary, and the prescribing UI
-- has no autocomplete data. Every doctor was typing free-text drug names,
-- with no safety net.
--
-- WHAT THIS MIGRATION DOES
-- ------------------------
-- Inserts ~25 system-wide (`hospital_id = NULL`) formulary entries covering
-- the highest-impact emergency-department drug categories:
--   - Pain / fever / inflammation
--   - Antibiotics
--   - Cardiac / resuscitation
--   - Respiratory
--   - GI / antiemetic / fluids
--   - Emergency hypoglycemia / seizure
--   - Allergy / anaphylaxis
--
-- This is a STARTER set, not the full REML. The existing
-- `POST /api/v1/med-safety/formulary` endpoint allows SUPER_ADMIN to add
-- more entries over time. We deliberately seed a focused, well-curated
-- list rather than a half-validated full REML import — wrong dose ranges
-- in a clinical-safety table are worse than a smaller correct list.
--
-- DOSE-RANGE SOURCES
-- ------------------
-- Adult and pediatric dose ranges below are taken from:
--   - Rwanda Standard Treatment Guidelines (2019)
--   - WHO Model List of Essential Medicines (22nd edition)
--   - BNF / BNFC for cross-validation
-- Where sources disagreed I used the more conservative (lower) maximum
-- so the safety engine flags overdose earlier rather than later. The
-- safety-engine override (DOCTOR-only) lets a doctor exceed the range
-- with a recorded justification when clinically necessary — that's the
-- right place to relax limits, not in the seed data.
--
-- ALLERGEN GROUPS
-- ---------------
-- The `allergen_groups` column drives MedicationSafetyEngine.checkAllergies
-- via substring match against patient.knownAllergies. Comma-separated.
-- Cross-reactivity is encoded by listing every related token a clinician
-- might write in the chart — e.g. amoxicillin lists
-- "penicillin,beta-lactam,amoxicillin" so any of those words in a free-text
-- allergy field will trip the warning.
--
-- IDEMPOTENCY
-- -----------
-- Insert is gated on (generic_name, hospital_id) so re-running the
-- migration does not duplicate. Hospital_id = NULL is treated as a single
-- bucket via a partial unique index (defined below). This lets a hospital
-- override a system-wide entry with its own row later without conflict.
-- ═══════════════════════════════════════════════════════════════

-- Partial unique index — only enforce uniqueness on system-wide rows so
-- hospital-specific overrides can co-exist with their system-wide twins.
CREATE UNIQUE INDEX IF NOT EXISTS uq_formulary_systemwide_generic_name
    ON drug_formularies (LOWER(generic_name))
    WHERE hospital_id IS NULL AND is_active = TRUE;

-- All inserts share these defaults; we set them explicitly so a migration
-- failure halfway through doesn't leave rows with NULLs that violate
-- ddl-auto=validate after rollback.
WITH defaults AS (
    SELECT
        gen_random_uuid()                          AS id,
        NOW()                                       AS created_at,
        NOW()                                       AS updated_at,
        TRUE                                        AS is_active,
        0                                           AS version,
        NULL::uuid                                  AS hospital_id
)
SELECT 1; -- (CTE placeholder, real inserts below)

-- ── Pain / fever / inflammation ─────────────────────────────────────
INSERT INTO drug_formularies (
    id, created_at, updated_at, is_active, version, hospital_id,
    generic_name, brand_names, drug_class, atc_code, reml_category,
    adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
    pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
    geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
    available_routes, contraindications, major_interactions, allergen_groups,
    is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml
)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, NULL::uuid, * FROM (VALUES
    -- Paracetamol (acetaminophen) — first-line antipyretic / mild analgesic.
    ('Paracetamol', 'Panadol,Tylenol,Doliprane', 'Analgesic/Antipyretic', 'N02BE01', 'Analgesics non-opioid',
     500.0::float8, 1000.0::float8, 4000.0::float8,
     10.0::float8, 15.0::float8, 60.0::float8,
     NULL::float8, FALSE, TRUE,
     'PO,IV,PR', 'Severe hepatic impairment', NULL, 'paracetamol,acetaminophen',
     FALSE, FALSE, NULL, 'B', TRUE),

    -- Ibuprofen — NSAID. Avoid in dehydration, GI bleeding, late pregnancy.
    ('Ibuprofen', 'Brufen,Advil,Nurofen', 'NSAID', 'M01AE01', 'Analgesics non-opioid',
     200.0::float8, 400.0::float8, 1200.0::float8,
     5.0::float8, 10.0::float8, 40.0::float8,
     25.0::float8, TRUE, FALSE,
     'PO', 'Active GI bleeding,severe heart failure,third-trimester pregnancy', 'warfarin,aspirin,methotrexate', 'ibuprofen,nsaid,salicylate',
     FALSE, FALSE, NULL, 'C', TRUE),

    -- Diclofenac — NSAID, common in REML.
    ('Diclofenac', 'Voltaren,Cataflam', 'NSAID', 'M01AB05', 'Analgesics non-opioid',
     25.0::float8, 75.0::float8, 150.0::float8,
     0.5::float8, 1.0::float8, 3.0::float8,
     25.0::float8, TRUE, FALSE,
     'PO,IM', 'Active GI bleeding,severe heart failure,third-trimester pregnancy', 'warfarin,methotrexate', 'diclofenac,nsaid',
     FALSE, FALSE, NULL, 'C', TRUE),

    -- Morphine — opioid analgesic, high-alert.
    ('Morphine', 'MS Contin,Sevredol', 'Opioid Analgesic', 'N02AA01', 'Analgesics opioid',
     2.0::float8, 10.0::float8, 60.0::float8,
     0.05::float8, 0.1::float8, 0.4::float8,
     50.0::float8, TRUE, TRUE,
     'IV,IM,SC,PO', 'Respiratory depression,paralytic ileus,head injury without ventilation', 'benzodiazepines,alcohol,MAOIs', 'morphine,opioid',
     TRUE, TRUE, 'Respiratory depression risk; require continuous monitoring after IV administration', 'C', TRUE),

    -- Tramadol — moderate opioid. Lower risk than morphine but still a controlled drug.
    ('Tramadol', 'Tramal,Ultram', 'Opioid Analgesic', 'N02AX02', 'Analgesics opioid',
     50.0::float8, 100.0::float8, 400.0::float8,
     1.0::float8, 2.0::float8, 8.0::float8,
     25.0::float8, TRUE, TRUE,
     'PO,IV,IM', 'Acute alcohol/opioid intoxication,seizure history,uncontrolled epilepsy', 'SSRIs,MAOIs,warfarin', 'tramadol,opioid',
     TRUE, FALSE, 'Risk of serotonin syndrome with SSRIs/MAOIs', 'C', TRUE)
) AS t(generic_name, brand_names, drug_class, atc_code, reml_category,
       adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
       pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
       geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
       available_routes, contraindications, major_interactions, allergen_groups,
       is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml)
WHERE NOT EXISTS (
    SELECT 1 FROM drug_formularies f
    WHERE LOWER(f.generic_name) = LOWER(t.generic_name) AND f.hospital_id IS NULL AND f.is_active = TRUE
);

-- ── Antibiotics ─────────────────────────────────────────────────────
INSERT INTO drug_formularies (
    id, created_at, updated_at, is_active, version, hospital_id,
    generic_name, brand_names, drug_class, atc_code, reml_category,
    adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
    pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
    geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
    available_routes, contraindications, major_interactions, allergen_groups,
    is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml
)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, NULL::uuid, * FROM (VALUES
    -- Amoxicillin — first-line beta-lactam.
    ('Amoxicillin', 'Amoxil,Moxypen', 'Aminopenicillin', 'J01CA04', 'Antibacterials',
     250.0::float8, 1000.0::float8, 3000.0::float8,
     15.0::float8, 30.0::float8, 90.0::float8,
     NULL::float8, TRUE, FALSE,
     'PO,IV', 'Penicillin allergy', 'methotrexate,warfarin', 'amoxicillin,penicillin,beta-lactam',
     FALSE, FALSE, NULL, 'B', TRUE),

    -- Amoxicillin/clavulanate — broader-spectrum beta-lactam.
    ('Amoxicillin/Clavulanate', 'Augmentin,Co-amoxiclav', 'Beta-lactam combination', 'J01CR02', 'Antibacterials',
     375.0::float8, 1000.0::float8, 3000.0::float8,
     15.0::float8, 30.0::float8, 90.0::float8,
     NULL::float8, TRUE, TRUE,
     'PO,IV', 'Penicillin allergy,history of cholestatic jaundice with this drug', 'methotrexate,warfarin', 'amoxicillin,penicillin,beta-lactam,clavulanate',
     FALSE, FALSE, NULL, 'B', TRUE),

    -- Ceftriaxone — third-generation cephalosporin, IV/IM. Cross-reactive with PCN.
    ('Ceftriaxone', 'Rocephin', 'Cephalosporin (3rd gen)', 'J01DD04', 'Antibacterials',
     1000.0::float8, 2000.0::float8, 4000.0::float8,
     50.0::float8, 75.0::float8, 100.0::float8,
     NULL::float8, FALSE, FALSE,
     'IV,IM', 'Severe penicillin allergy,neonatal hyperbilirubinemia,calcium-containing IV solutions in neonates', 'calcium IV,warfarin', 'cephalosporin,beta-lactam,ceftriaxone',
     FALSE, FALSE, NULL, 'B', TRUE),

    -- Ciprofloxacin — fluoroquinolone. Black-box for tendon rupture.
    ('Ciprofloxacin', 'Ciproxin,Cipro', 'Fluoroquinolone', 'J01MA02', 'Antibacterials',
     250.0::float8, 750.0::float8, 1500.0::float8,
     10.0::float8, 20.0::float8, 30.0::float8,
     NULL::float8, TRUE, FALSE,
     'PO,IV', 'Pregnancy,children <18 (use with caution),tendon disorders', 'theophylline,warfarin,NSAIDs,antacids', 'ciprofloxacin,fluoroquinolone,quinolone',
     FALSE, FALSE, 'Risk of tendinitis and tendon rupture; QT prolongation', 'C', TRUE),

    -- Metronidazole — anaerobic / antiprotozoal.
    ('Metronidazole', 'Flagyl', 'Nitroimidazole', 'J01XD01', 'Antibacterials',
     400.0::float8, 500.0::float8, 4000.0::float8,
     7.5::float8, 10.0::float8, 30.0::float8,
     NULL::float8, FALSE, TRUE,
     'PO,IV', 'First-trimester pregnancy,active CNS disease,active alcohol use', 'warfarin,alcohol,phenytoin,lithium', 'metronidazole,nitroimidazole',
     FALSE, FALSE, NULL, 'B', TRUE),

    -- Gentamicin — aminoglycoside, narrow therapeutic index, requires renal monitoring.
    ('Gentamicin', 'Garamycin', 'Aminoglycoside', 'J01GB03', 'Antibacterials',
     80.0::float8, 240.0::float8, 480.0::float8,
     2.5::float8, 7.5::float8, 7.5::float8,
     50.0::float8, TRUE, FALSE,
     'IV,IM', 'Severe renal impairment,myasthenia gravis,known aminoglycoside allergy', 'loop diuretics,vancomycin,NSAIDs', 'gentamicin,aminoglycoside',
     TRUE, TRUE, 'Nephrotoxicity and ototoxicity; monitor levels', 'D', TRUE)
) AS t(generic_name, brand_names, drug_class, atc_code, reml_category,
       adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
       pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
       geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
       available_routes, contraindications, major_interactions, allergen_groups,
       is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml)
WHERE NOT EXISTS (
    SELECT 1 FROM drug_formularies f
    WHERE LOWER(f.generic_name) = LOWER(t.generic_name) AND f.hospital_id IS NULL AND f.is_active = TRUE
);

-- ── Cardiac / resuscitation ─────────────────────────────────────────
INSERT INTO drug_formularies (
    id, created_at, updated_at, is_active, version, hospital_id,
    generic_name, brand_names, drug_class, atc_code, reml_category,
    adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
    pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
    geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
    available_routes, contraindications, major_interactions, allergen_groups,
    is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml
)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, NULL::uuid, * FROM (VALUES
    -- Adrenaline (epinephrine) — anaphylaxis / cardiac arrest. High-alert.
    ('Adrenaline', 'Epinephrine', 'Sympathomimetic', 'C01CA24', 'Resuscitation',
     0.3::float8, 1.0::float8, 5.0::float8,
     0.01::float8, 0.01::float8, 0.05::float8,
     NULL::float8, FALSE, FALSE,
     'IM,IV,SC,INH', 'No absolute contraindication in anaphylaxis or arrest', 'beta-blockers,MAOIs,tricyclic antidepressants', 'adrenaline,epinephrine',
     TRUE, TRUE, 'High-alert; concentration confusion (1:1000 vs 1:10000) has caused deaths — verify route and concentration', 'C', TRUE),

    -- Atropine — bradycardia, organophosphate poisoning.
    ('Atropine', 'AtroPen', 'Anticholinergic', 'A03BA01', 'Resuscitation',
     0.5::float8, 3.0::float8, 3.0::float8,
     0.01::float8, 0.02::float8, 0.04::float8,
     NULL::float8, FALSE, FALSE,
     'IV,IM,SC', 'Closed-angle glaucoma,obstructive uropathy,paralytic ileus', 'antihistamines,phenothiazines', 'atropine,anticholinergic',
     TRUE, TRUE, NULL, 'C', TRUE),

    -- Aspirin — antiplatelet for ACS / suspected MI.
    ('Aspirin', 'ASA,Acetylsalicylic Acid', 'Antiplatelet/NSAID', 'B01AC06', 'Cardiovascular',
     75.0::float8, 300.0::float8, 4000.0::float8,
     NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, TRUE, FALSE,
     'PO', 'Active GI bleeding,children with viral illness (Reye syndrome),hemophilia,severe asthma', 'warfarin,methotrexate,ACE inhibitors', 'aspirin,salicylate,nsaid',
     FALSE, FALSE, 'Reye syndrome risk in children — avoid in <16y with viral illness', 'D', TRUE),

    -- Glyceryl Trinitrate (GTN) — angina / pulmonary edema.
    ('Glyceryl Trinitrate', 'GTN,Nitroglycerin', 'Vasodilator', 'C01DA02', 'Cardiovascular',
     0.3::float8, 0.6::float8, 3.0::float8,
     NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, FALSE, FALSE,
     'SL,IV,TOP', 'Hypotension,severe anemia,raised intracranial pressure,recent PDE5 inhibitor (sildenafil/tadalafil within 24-48h)', 'sildenafil,tadalafil,vardenafil', 'glyceryl trinitrate,nitrate,nitroglycerin',
     TRUE, FALSE, 'Severe hypotension if combined with PDE5 inhibitors', 'C', TRUE),

    -- Furosemide — loop diuretic, acute pulmonary edema.
    ('Furosemide', 'Lasix', 'Loop Diuretic', 'C03CA01', 'Cardiovascular',
     20.0::float8, 80.0::float8, 200.0::float8,
     0.5::float8, 1.0::float8, 6.0::float8,
     25.0::float8, TRUE, TRUE,
     'PO,IV,IM', 'Anuria,severe hypokalemia,severe hyponatremia', 'aminoglycosides,lithium,NSAIDs', 'furosemide,sulfa,sulfonamide',
     FALSE, FALSE, NULL, 'C', TRUE)
) AS t(generic_name, brand_names, drug_class, atc_code, reml_category,
       adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
       pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
       geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
       available_routes, contraindications, major_interactions, allergen_groups,
       is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml)
WHERE NOT EXISTS (
    SELECT 1 FROM drug_formularies f
    WHERE LOWER(f.generic_name) = LOWER(t.generic_name) AND f.hospital_id IS NULL AND f.is_active = TRUE
);

-- ── Respiratory ─────────────────────────────────────────────────────
INSERT INTO drug_formularies (
    id, created_at, updated_at, is_active, version, hospital_id,
    generic_name, brand_names, drug_class, atc_code, reml_category,
    adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
    pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
    geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
    available_routes, contraindications, major_interactions, allergen_groups,
    is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml
)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, NULL::uuid, * FROM (VALUES
    -- Salbutamol — beta-agonist, acute bronchospasm.
    ('Salbutamol', 'Ventolin,Albuterol', 'Beta-2 Agonist', 'R03AC02', 'Respiratory',
     2.5::float8, 5.0::float8, 40.0::float8,
     0.15::float8, 0.15::float8, 0.6::float8,
     NULL::float8, FALSE, FALSE,
     'INH,NEB,PO,IV', 'Severe coronary disease (caution)', 'beta-blockers,MAOIs,tricyclic antidepressants', 'salbutamol,beta-agonist,albuterol',
     FALSE, FALSE, NULL, 'C', TRUE),

    -- Hydrocortisone — IV steroid, anaphylaxis / asthma / adrenal crisis.
    ('Hydrocortisone', 'Solu-Cortef,Cortef', 'Corticosteroid', 'H02AB09', 'Endocrine',
     100.0::float8, 200.0::float8, 800.0::float8,
     2.0::float8, 4.0::float8, 16.0::float8,
     NULL::float8, FALSE, TRUE,
     'IV,IM,PO', 'Systemic fungal infection,live vaccines', 'NSAIDs,warfarin,potassium-depleting diuretics', 'hydrocortisone,corticosteroid,steroid',
     FALSE, FALSE, NULL, 'C', TRUE),

    -- Prednisolone — oral corticosteroid.
    ('Prednisolone', 'Predforte', 'Corticosteroid', 'H02AB06', 'Endocrine',
     5.0::float8, 60.0::float8, 80.0::float8,
     1.0::float8, 2.0::float8, 4.0::float8,
     NULL::float8, FALSE, TRUE,
     'PO', 'Systemic fungal infection,live vaccines', 'NSAIDs,warfarin', 'prednisolone,corticosteroid,steroid',
     FALSE, FALSE, NULL, 'C', TRUE)
) AS t(generic_name, brand_names, drug_class, atc_code, reml_category,
       adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
       pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
       geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
       available_routes, contraindications, major_interactions, allergen_groups,
       is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml)
WHERE NOT EXISTS (
    SELECT 1 FROM drug_formularies f
    WHERE LOWER(f.generic_name) = LOWER(t.generic_name) AND f.hospital_id IS NULL AND f.is_active = TRUE
);

-- ── GI / antiemetic / fluids ────────────────────────────────────────
INSERT INTO drug_formularies (
    id, created_at, updated_at, is_active, version, hospital_id,
    generic_name, brand_names, drug_class, atc_code, reml_category,
    adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
    pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
    geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
    available_routes, contraindications, major_interactions, allergen_groups,
    is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml
)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, NULL::uuid, * FROM (VALUES
    -- Omeprazole — PPI.
    ('Omeprazole', 'Losec,Prilosec', 'Proton Pump Inhibitor', 'A02BC01', 'Gastrointestinal',
     20.0::float8, 40.0::float8, 80.0::float8,
     0.7::float8, 1.4::float8, 3.5::float8,
     NULL::float8, FALSE, TRUE,
     'PO,IV', 'Hypersensitivity to PPIs', 'clopidogrel,warfarin,methotrexate', 'omeprazole,ppi,proton pump inhibitor',
     FALSE, FALSE, NULL, 'C', TRUE),

    -- Metoclopramide — antiemetic, prokinetic.
    ('Metoclopramide', 'Maxolon,Reglan', 'Antiemetic', 'A03FA01', 'Gastrointestinal',
     10.0::float8, 10.0::float8, 30.0::float8,
     0.1::float8, 0.15::float8, 0.5::float8,
     50.0::float8, TRUE, FALSE,
     'PO,IV,IM', 'GI hemorrhage,mechanical obstruction,perforation,pheochromocytoma,Parkinsonism', 'opioids,antipsychotics,SSRIs', 'metoclopramide,prokinetic',
     FALSE, FALSE, 'Tardive dyskinesia risk; do not exceed 12 weeks total exposure', 'B', TRUE),

    -- Ondansetron — 5HT3 antiemetic.
    ('Ondansetron', 'Zofran', 'Antiemetic (5HT3)', 'A04AA01', 'Gastrointestinal',
     4.0::float8, 8.0::float8, 16.0::float8,
     0.1::float8, 0.15::float8, 0.45::float8,
     NULL::float8, FALSE, TRUE,
     'PO,IV,IM', 'Long QT syndrome,known hypersensitivity', 'apomorphine,SSRIs,tramadol', 'ondansetron,5ht3,setron',
     FALSE, FALSE, 'QT prolongation risk', 'B', TRUE),

    -- Oral Rehydration Salts — diarrhea/dehydration. Dose by volume not mg.
    ('Oral Rehydration Salts', 'ORS,WHO ORS', 'Rehydration', NULL, 'Fluid replacement',
     NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, NULL::float8, NULL::float8,
     NULL::float8, FALSE, FALSE,
     'PO', 'Severe dehydration requiring IV,intractable vomiting,intestinal obstruction', NULL, 'ors,oral rehydration salts',
     FALSE, FALSE, NULL, 'A', TRUE)
) AS t(generic_name, brand_names, drug_class, atc_code, reml_category,
       adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
       pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
       geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
       available_routes, contraindications, major_interactions, allergen_groups,
       is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml)
WHERE NOT EXISTS (
    SELECT 1 FROM drug_formularies f
    WHERE LOWER(f.generic_name) = LOWER(t.generic_name) AND f.hospital_id IS NULL AND f.is_active = TRUE
);

-- ── Emergency hypoglycemia / seizure / allergy ──────────────────────
INSERT INTO drug_formularies (
    id, created_at, updated_at, is_active, version, hospital_id,
    generic_name, brand_names, drug_class, atc_code, reml_category,
    adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
    pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
    geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
    available_routes, contraindications, major_interactions, allergen_groups,
    is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml
)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, NULL::uuid, * FROM (VALUES
    -- Dextrose 50% — hypoglycemia.
    ('Dextrose 50%', 'D50W,Glucose 50%', 'Carbohydrate', 'B05CB01', 'Resuscitation',
     12500.0::float8, 25000.0::float8, 50000.0::float8,
     500.0::float8, 1000.0::float8, 2000.0::float8,
     NULL::float8, FALSE, FALSE,
     'IV', 'Intracranial or intraspinal hemorrhage,severe dehydration,delirium tremens (give thiamine first)', NULL, 'dextrose,glucose',
     TRUE, TRUE, 'Tissue necrosis with extravasation; ensure patent IV access', 'C', TRUE),

    -- Diazepam — seizure / sedation. Benzo, high-alert.
    ('Diazepam', 'Valium', 'Benzodiazepine', 'N05BA01', 'Anticonvulsant',
     2.0::float8, 10.0::float8, 30.0::float8,
     0.1::float8, 0.3::float8, 1.0::float8,
     50.0::float8, FALSE, TRUE,
     'IV,PR,PO,IM', 'Severe respiratory depression,acute narrow-angle glaucoma,sleep apnea', 'opioids,alcohol,other CNS depressants', 'diazepam,benzodiazepine,benzo',
     TRUE, TRUE, 'Respiratory depression risk; especially with concurrent opioids', 'D', TRUE),

    -- Chlorpheniramine — antihistamine for allergic reactions.
    ('Chlorpheniramine', 'Piriton,Chlor-Trimeton', 'H1 Antihistamine', 'R06AB04', 'Allergy',
     4.0::float8, 10.0::float8, 24.0::float8,
     0.1::float8, 0.2::float8, 0.4::float8,
     50.0::float8, FALSE, TRUE,
     'PO,IV,IM,SC', 'Closed-angle glaucoma,prostatic hypertrophy,bladder neck obstruction,severe asthma exacerbation', 'alcohol,MAOIs,other CNS depressants', 'chlorpheniramine,antihistamine',
     FALSE, FALSE, NULL, 'B', TRUE)
) AS t(generic_name, brand_names, drug_class, atc_code, reml_category,
       adult_min_dose_mg, adult_max_dose_mg, adult_max_daily_dose_mg,
       pediatric_min_dose_mg_per_kg, pediatric_max_dose_mg_per_kg, pediatric_max_daily_dose_mg_per_kg,
       geriatric_adjustment_percent, renal_adjustment_required, hepatic_adjustment_required,
       available_routes, contraindications, major_interactions, allergen_groups,
       is_high_alert, requires_double_check, black_box_warning, pregnancy_category, is_on_reml)
WHERE NOT EXISTS (
    SELECT 1 FROM drug_formularies f
    WHERE LOWER(f.generic_name) = LOWER(t.generic_name) AND f.hospital_id IS NULL AND f.is_active = TRUE
);
