-- ═══════════════════════════════════════════════════════════════
-- V23 — ICD-10 catalog table + Rwanda-relevant seed.
--
-- BACKGROUND
-- ----------
-- Diagnoses on the chart used to require the doctor to type the ICD-10
-- code by hand — error-prone and slow. This migration adds a reference
-- catalog so the diagnosis-entry form can autocomplete from a curated
-- list, with conditions common in the Rwandan ED context pinned to the
-- top of the suggestions.
--
-- WHAT THIS MIGRATION DOES
-- ------------------------
-- 1. Creates `icd_codes` (matches the IcdCode JPA entity).
-- 2. Seeds ~80 codes covering the most frequent presenting and admitting
--    diagnoses in a Rwandan ED:
--      - Infectious: malaria, typhoid, sepsis, HIV, TB, meningitis,
--                    measles, tetanus, whooping cough, gastroenteritis
--      - Respiratory: pneumonia, asthma, bronchitis, COPD
--      - Cardiac: acute MI, heart failure, stroke, hypertensive crisis
--      - Trauma: head injury, fractures, multiple trauma, burns
--      - Endocrine: DKA, hypoglycemia, dehydration
--      - GI: appendicitis, GI bleed, hepatic failure, pancreatitis
--      - Obstetric: pre-eclampsia, eclampsia, ectopic, PPH
--      - Pediatric: neonatal jaundice, severe malnutrition
--      - Toxicology: snake bite, anaphylaxis, organophosphate poisoning
--      - Common symptom-based codes: shock, syncope, fever, dyspnea
--
-- DATA SOURCES
-- ------------
-- Codes and descriptions are taken from WHO ICD-10 (2019 update). The
-- `is_common_in_rwanda` flag reflects the Rwandan Ministry of Health's
-- top-causes-of-emergency-presentation lists and pediatric IMCI common
-- diagnoses. New codes can be added via SUPER_ADMIN tooling or future
-- migrations.
--
-- IDEMPOTENCY
-- -----------
-- Insert is gated on `code` so re-running the migration does not duplicate.
-- A unique index on (code) where is_active = true is added below.
-- ═══════════════════════════════════════════════════════════════

CREATE TABLE IF NOT EXISTS icd_codes (
    id                      UUID PRIMARY KEY,
    created_at              TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE,
    created_by              VARCHAR(255),
    last_modified_by        VARCHAR(255),
    is_active               BOOLEAN NOT NULL DEFAULT TRUE,
    version                 BIGINT,

    code                    VARCHAR(20) NOT NULL,
    description             TEXT NOT NULL,
    category                VARCHAR(100),
    is_common_in_rwanda     BOOLEAN NOT NULL DEFAULT FALSE,
    clinical_notes          TEXT
);

CREATE INDEX IF NOT EXISTS idx_icd_code              ON icd_codes (code);
CREATE INDEX IF NOT EXISTS idx_icd_description       ON icd_codes (description);
CREATE INDEX IF NOT EXISTS idx_icd_category          ON icd_codes (category);
CREATE INDEX IF NOT EXISTS idx_icd_common_rwanda     ON icd_codes (is_common_in_rwanda);
CREATE INDEX IF NOT EXISTS idx_icd_active            ON icd_codes (is_active);
CREATE UNIQUE INDEX IF NOT EXISTS uq_icd_code_active
    ON icd_codes (UPPER(code)) WHERE is_active = TRUE;

-- Seed: every row uses gen_random_uuid() and NOW() so the BaseEntity
-- audit fields are valid under ddl-auto=validate.
INSERT INTO icd_codes (id, created_at, updated_at, is_active, version, code, description, category, is_common_in_rwanda, clinical_notes)
SELECT gen_random_uuid(), NOW(), NOW(), TRUE, 0, * FROM (VALUES
    -- ── Infectious (A00-B99) ──
    ('A01.0', 'Typhoid fever',                                              'Infectious diseases',  TRUE,  'Confirm with Widal or blood culture; treat with ceftriaxone or fluoroquinolone'),
    ('A09',   'Diarrhea and gastroenteritis of presumed infectious origin', 'Infectious diseases',  TRUE,  'Assess hydration; ORS first-line, IV fluids if severe'),
    ('A15.0', 'Tuberculosis of lung, bacteriologically confirmed',          'Infectious diseases',  TRUE,  'GeneXpert MTB/RIF; isolate until smear-negative'),
    ('A16.2', 'Tuberculosis of lung, without mention of confirmation',      'Infectious diseases',  TRUE,  NULL),
    ('A35',   'Other tetanus',                                              'Infectious diseases',  TRUE,  'Manage in quiet darkened area; tetanus immunoglobulin and metronidazole'),
    ('A39.0', 'Meningococcal meningitis',                                   'Infectious diseases',  TRUE,  'Notifiable disease; isolate; ceftriaxone first-line'),
    ('A41.9', 'Sepsis, unspecified organism',                               'Infectious diseases',  TRUE,  'Sepsis-6 bundle within first hour; broad-spectrum antibiotic'),
    ('A41.5', 'Sepsis due to other Gram-negative organisms',                'Infectious diseases',  TRUE,  NULL),
    ('A41.0', 'Sepsis due to Staphylococcus aureus',                        'Infectious diseases',  FALSE, NULL),
    ('A87.9', 'Viral meningitis, unspecified',                              'Infectious diseases',  TRUE,  NULL),
    ('B05.9', 'Measles without complication',                               'Infectious diseases',  TRUE,  'Notifiable; vitamin A; isolate from non-immune contacts'),
    ('B20',   'HIV disease resulting in infectious and parasitic diseases', 'Infectious diseases',  TRUE,  'Check viral load and CD4; review ART adherence'),
    ('B24',   'Unspecified human immunodeficiency virus disease',           'Infectious diseases',  TRUE,  NULL),
    ('B50.9', 'Plasmodium falciparum malaria, unspecified',                 'Infectious diseases',  TRUE,  'Confirm with mRDT or thick smear; artesunate IV for severe, AL oral for uncomplicated'),
    ('B51.9', 'Plasmodium vivax malaria without complication',              'Infectious diseases',  TRUE,  NULL),
    ('B54',   'Unspecified malaria',                                        'Infectious diseases',  TRUE,  NULL),
    ('B58.9', 'Toxoplasmosis, unspecified',                                 'Infectious diseases',  FALSE, NULL),
    ('G03.9', 'Meningitis, unspecified',                                    'Infectious diseases',  TRUE,  'Empirical ceftriaxone + vancomycin; LP if no contraindication'),

    -- ── Respiratory (J00-J99) ──
    ('J18.9', 'Pneumonia, unspecified',                                     'Respiratory',          TRUE,  'CRB-65 score; amoxicillin first-line for community-acquired'),
    ('J20.9', 'Acute bronchitis, unspecified',                              'Respiratory',          TRUE,  NULL),
    ('J44.9', 'Chronic obstructive pulmonary disease, unspecified',         'Respiratory',          TRUE,  'Salbutamol nebulization, prednisolone, oxygen to keep SpO2 88-92%'),
    ('J45.9', 'Asthma, unspecified',                                        'Respiratory',          TRUE,  'Salbutamol + ipratropium nebulization; hydrocortisone IV for severe'),
    ('J81',   'Pulmonary oedema',                                           'Respiratory',          TRUE,  'Sit upright; furosemide IV; GTN if SBP allows'),
    ('J96.0', 'Acute respiratory failure',                                  'Respiratory',          TRUE,  NULL),
    ('J93.9', 'Pneumothorax, unspecified',                                  'Respiratory',          FALSE, 'Tension pneumothorax: needle decompression then chest drain'),

    -- ── Cardiovascular (I00-I99) ──
    ('I10',   'Essential (primary) hypertension',                           'Cardiovascular',       TRUE,  NULL),
    ('I21.9', 'Acute myocardial infarction, unspecified',                   'Cardiovascular',       TRUE,  'Aspirin 300mg PO + GTN SL if SBP >100; arrange transfer for PCI/thrombolysis'),
    ('I46.9', 'Cardiac arrest, unspecified',                                'Cardiovascular',       TRUE,  'ALS algorithm; adrenaline 1mg IV every 3-5 min'),
    ('I50.9', 'Heart failure, unspecified',                                 'Cardiovascular',       TRUE,  'Furosemide IV, GTN if SBP allows, sit upright'),
    ('I63.9', 'Cerebral infarction, unspecified',                           'Cardiovascular',       TRUE,  'Confirm with CT before any thrombolysis or anticoagulation'),
    ('I64',   'Stroke, not specified as haemorrhage or infarction',         'Cardiovascular',       TRUE,  NULL),
    ('I67.4', 'Hypertensive encephalopathy',                                'Cardiovascular',       FALSE, NULL),

    -- ── Endocrine / Metabolic (E00-E89) ──
    ('E10.1', 'Type 1 diabetes mellitus with ketoacidosis',                 'Endocrine',            TRUE,  'IV fluids first, then insulin infusion; replace potassium early'),
    ('E11.1', 'Type 2 diabetes mellitus with ketoacidosis',                 'Endocrine',            TRUE,  NULL),
    ('E14.9', 'Unspecified diabetes mellitus without complications',        'Endocrine',            TRUE,  NULL),
    ('E16.2', 'Hypoglycaemia, unspecified',                                 'Endocrine',            TRUE,  '50ml D50% IV bolus or 200ml D10% if no concentrated dextrose; recheck glucose 15min'),
    ('E86',   'Volume depletion (dehydration)',                             'Endocrine',            TRUE,  NULL),
    ('E87.5', 'Hyperkalaemia',                                              'Endocrine',            FALSE, 'Calcium gluconate IV, insulin/dextrose, salbutamol; ECG monitoring'),
    ('E40',   'Kwashiorkor (severe acute malnutrition with oedema)',        'Endocrine',            TRUE,  'Refer to therapeutic feeding programme; F-75 then F-100 or RUTF'),

    -- ── Gastrointestinal (K00-K93) ──
    ('K35.8', 'Acute appendicitis, other and unspecified',                  'Gastrointestinal',     TRUE,  NULL),
    ('K72.9', 'Hepatic failure, unspecified',                               'Gastrointestinal',     FALSE, NULL),
    ('K85.9', 'Acute pancreatitis, unspecified',                            'Gastrointestinal',     FALSE, NULL),
    ('K92.2', 'Gastrointestinal haemorrhage, unspecified',                  'Gastrointestinal',     TRUE,  'Two large-bore IV access; group and crossmatch; PPI infusion'),
    ('K56.7', 'Ileus, unspecified',                                         'Gastrointestinal',     FALSE, NULL),

    -- ── Genitourinary (N00-N99) ──
    ('N17.9', 'Acute renal failure, unspecified',                           'Genitourinary',        TRUE,  NULL),
    ('N39.0', 'Urinary tract infection, site not specified',                'Genitourinary',        TRUE,  NULL),
    ('N20.0', 'Calculus of kidney',                                         'Genitourinary',        FALSE, NULL),

    -- ── Obstetric (O00-O99) ──
    ('O00.9', 'Ectopic pregnancy, unspecified',                             'Obstetric',            TRUE,  'Surgical emergency if ruptured; large-bore IV, group and crossmatch'),
    ('O14.9', 'Pre-eclampsia, unspecified',                                 'Obstetric',            TRUE,  'Magnesium sulfate; control BP with labetalol/hydralazine'),
    ('O15.9', 'Eclampsia, unspecified as to time period',                   'Obstetric',            TRUE,  'Magnesium sulfate loading then maintenance; left lateral position; deliver'),
    ('O72.1', 'Other immediate postpartum haemorrhage',                     'Obstetric',            TRUE,  'Uterine massage; oxytocin IV; misoprostol; tranexamic acid'),
    ('O80',   'Single spontaneous delivery',                                'Obstetric',            FALSE, NULL),
    ('O20.0', 'Threatened abortion',                                        'Obstetric',            TRUE,  NULL),

    -- ── Neurological (G00-G99, R) ──
    ('G40.9', 'Epilepsy, unspecified',                                      'Neurological',         TRUE,  NULL),
    ('R56.8', 'Other and unspecified convulsions',                          'Neurological',         TRUE,  'Position safely; benzodiazepine if >5min; check glucose'),
    ('G93.4', 'Encephalopathy, unspecified',                                'Neurological',         FALSE, NULL),

    -- ── Trauma (S00-T98) ──
    ('S00.9', 'Superficial injury of head, part unspecified',               'Trauma',               TRUE,  NULL),
    ('S06.9', 'Intracranial injury, unspecified',                           'Trauma',               TRUE,  'GCS, pupils, CT brain; reverse anticoagulation if any'),
    ('S72.9', 'Fracture of femur, part unspecified',                        'Trauma',               TRUE,  NULL),
    ('S82.9', 'Fracture of lower leg, part unspecified',                    'Trauma',               TRUE,  NULL),
    ('S52.9', 'Fracture of forearm, part unspecified',                      'Trauma',               TRUE,  NULL),
    ('T07',   'Unspecified multiple injuries',                              'Trauma',               TRUE,  'Primary survey ABCDE; pan-CT if available; activate trauma team'),
    ('T14.9', 'Injury, unspecified',                                        'Trauma',               TRUE,  NULL),
    ('T20.3', 'Burn of third degree of head and neck',                      'Trauma',               FALSE, NULL),
    ('T31.0', 'Burns involving less than 10% of body surface',              'Trauma',               TRUE,  'Cool with running water 20 min; analgesia; tetanus status'),
    ('T31.3', 'Burns involving 30-39% of body surface',                     'Trauma',               TRUE,  'Parkland formula; ICU; airway watch if facial burns'),

    -- ── Toxicology / envenomation ──
    ('T63.0', 'Toxic effect of snake venom',                                'Toxicology',           TRUE,  'Immobilize limb; do not incise or apply tourniquet; antivenom per protocol'),
    ('T78.2', 'Anaphylactic shock, unspecified',                            'Toxicology',           TRUE,  'Adrenaline 0.5mg IM (adult) immediately; remove trigger; fluids; oxygen'),
    ('T78.4', 'Allergy, unspecified',                                       'Toxicology',           TRUE,  NULL),
    ('T60.0', 'Toxic effect of organophosphate and carbamate insecticides', 'Toxicology',           TRUE,  'Atropine titrated to dry secretions; pralidoxime; decontaminate'),
    ('T58',   'Toxic effect of carbon monoxide',                            'Toxicology',           FALSE, '100% oxygen; consider hyperbaric if available'),

    -- ── Pediatric specific ──
    ('P59.9', 'Neonatal jaundice, unspecified',                             'Pediatric',            TRUE,  NULL),
    ('P22.0', 'Respiratory distress syndrome of newborn',                   'Pediatric',            FALSE, NULL),
    ('A91',   'Dengue haemorrhagic fever',                                  'Infectious diseases',  FALSE, NULL),

    -- ── Symptom / sign codes (R00-R99) — used while diagnosis is provisional ──
    ('R06.0', 'Dyspnoea',                                                   'Symptoms',             TRUE,  NULL),
    ('R10.4', 'Other and unspecified abdominal pain',                       'Symptoms',             TRUE,  NULL),
    ('R50.9', 'Fever, unspecified',                                         'Symptoms',             TRUE,  NULL),
    ('R55',   'Syncope and collapse',                                       'Symptoms',             TRUE,  NULL),
    ('R57.0', 'Cardiogenic shock',                                          'Symptoms',             TRUE,  NULL),
    ('R57.1', 'Hypovolaemic shock',                                         'Symptoms',             TRUE,  NULL),
    ('R57.9', 'Shock, unspecified',                                         'Symptoms',             TRUE,  NULL),
    ('R65.2', 'Severe sepsis',                                              'Symptoms',             TRUE,  NULL),
    ('R40.2', 'Coma, unspecified',                                          'Symptoms',             TRUE,  NULL),

    -- ── Mental health / self-harm ──
    ('F19.9', 'Mental and behavioural disorders due to multiple drug use',  'Mental health',        FALSE, NULL),
    ('X69',   'Intentional self-poisoning by other unspecified substance',  'Mental health',        TRUE,  'Activate suicide-risk protocol; mental health consult; safety planning')
) AS t(code, description, category, is_common_in_rwanda, clinical_notes)
WHERE NOT EXISTS (
    SELECT 1 FROM icd_codes ic WHERE UPPER(ic.code) = UPPER(t.code) AND ic.is_active = TRUE
);
