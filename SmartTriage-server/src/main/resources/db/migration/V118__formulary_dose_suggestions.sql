-- V118: dose-suggestion fields on the formulary.
--
-- The formulary already POLICES doses (min/max ranges feed the safety
-- engine). These columns let the same rows SUGGEST them: the typical
-- starting dose, the standard interval and route — so the prescribe
-- form can pre-fill a clinically correct order the doctor confirms or
-- edits, instead of typing every field. Suggestion values are starting
-- points for a typical patient; the safety engine still validates
-- whatever is finally submitted.
ALTER TABLE drug_formularies
    ADD COLUMN typical_adult_dose_mg DOUBLE PRECISION,
    ADD COLUMN typical_pediatric_dose_mg_per_kg DOUBLE PRECISION,
    ADD COLUMN default_interval_hours DOUBLE PRECISION,
    ADD COLUMN default_route VARCHAR(20),
    ADD COLUMN suggestion_note VARCHAR(300);

-- Seed the high-frequency ED drugs with REML/WHO-standard typical doses.
-- Values are deliberately conservative starting doses; ranges stay the
-- authoritative bounds. Only rows that exist are touched (generic_name
-- match), so hospitals with trimmed formularies are unaffected.
UPDATE drug_formularies SET typical_adult_dose_mg = 1000, typical_pediatric_dose_mg_per_kg = 15,  default_interval_hours = 6,  default_route = 'PO',  suggestion_note = 'Max 4 g/day adult; 60 mg/kg/day paediatric' WHERE generic_name = 'Paracetamol';
UPDATE drug_formularies SET typical_adult_dose_mg = 400,  typical_pediatric_dose_mg_per_kg = 10,  default_interval_hours = 8,  default_route = 'PO',  suggestion_note = 'With food; avoid in dehydration/renal impairment' WHERE generic_name = 'Ibuprofen';
UPDATE drug_formularies SET typical_adult_dose_mg = 50,   typical_pediatric_dose_mg_per_kg = 1,   default_interval_hours = 8,  default_route = 'PO',  suggestion_note = 'Avoid in asthma/renal impairment/pregnancy 3rd trimester' WHERE generic_name = 'Diclofenac';
UPDATE drug_formularies SET typical_adult_dose_mg = 50,   typical_pediatric_dose_mg_per_kg = NULL, default_interval_hours = 6, default_route = 'PO',  suggestion_note = 'Not recommended under 12 years' WHERE generic_name = 'Tramadol';
UPDATE drug_formularies SET typical_adult_dose_mg = 5,    typical_pediatric_dose_mg_per_kg = 0.1, default_interval_hours = 4,  default_route = 'IV',  suggestion_note = 'Titrate to pain; monitor respiratory rate' WHERE generic_name = 'Morphine';
UPDATE drug_formularies SET typical_adult_dose_mg = 2000, typical_pediatric_dose_mg_per_kg = 50,  default_interval_hours = 24, default_route = 'IV',  suggestion_note = 'Meningitis: 100 mg/kg/day' WHERE generic_name = 'Ceftriaxone';
UPDATE drug_formularies SET typical_adult_dose_mg = 500,  typical_pediatric_dose_mg_per_kg = 15,  default_interval_hours = 8,  default_route = 'PO',  suggestion_note = NULL WHERE generic_name = 'Amoxicillin';
UPDATE drug_formularies SET typical_adult_dose_mg = 625,  typical_pediatric_dose_mg_per_kg = 15,  default_interval_hours = 8,  default_route = 'PO',  suggestion_note = 'Dose expressed as amoxicillin component for paediatrics' WHERE generic_name = 'Amoxicillin/Clavulanate';
UPDATE drug_formularies SET typical_adult_dose_mg = 1000, typical_pediatric_dose_mg_per_kg = 50,  default_interval_hours = 6,  default_route = 'IV',  suggestion_note = NULL WHERE generic_name = 'Ampicillin';
UPDATE drug_formularies SET typical_adult_dose_mg = 240,  typical_pediatric_dose_mg_per_kg = 7.5, default_interval_hours = 24, default_route = 'IV',  suggestion_note = 'Once-daily dosing; check renal function' WHERE generic_name = 'Gentamicin';
UPDATE drug_formularies SET typical_adult_dose_mg = 500,  typical_pediatric_dose_mg_per_kg = 7.5, default_interval_hours = 8,  default_route = 'PO',  suggestion_note = 'Avoid alcohol during and 48 h after' WHERE generic_name = 'Metronidazole';
UPDATE drug_formularies SET typical_adult_dose_mg = 500,  typical_pediatric_dose_mg_per_kg = 10,  default_interval_hours = 24, default_route = 'PO',  suggestion_note = NULL WHERE generic_name = 'Azithromycin';
UPDATE drug_formularies SET typical_adult_dose_mg = 500,  typical_pediatric_dose_mg_per_kg = NULL, default_interval_hours = 12, default_route = 'PO', suggestion_note = 'Avoid in children unless no alternative' WHERE generic_name = 'Ciprofloxacin';
UPDATE drug_formularies SET typical_adult_dose_mg = 2400, typical_pediatric_dose_mg_per_kg = 2.4, default_interval_hours = 12, default_route = 'IV',  suggestion_note = 'Severe malaria: 2.4 mg/kg at 0, 12, 24 h then daily' WHERE generic_name = 'Artesunate';
UPDATE drug_formularies SET typical_adult_dose_mg = 80,   typical_pediatric_dose_mg_per_kg = NULL, default_interval_hours = 12, default_route = 'PO', suggestion_note = 'Weight-band tablets per national malaria guideline; give with fatty food' WHERE generic_name = 'Artemether-Lumefantrine';
UPDATE drug_formularies SET typical_adult_dose_mg = 4,    typical_pediatric_dose_mg_per_kg = 0.15, default_interval_hours = 8, default_route = 'IV',  suggestion_note = NULL WHERE generic_name = 'Ondansetron';
UPDATE drug_formularies SET typical_adult_dose_mg = 10,   typical_pediatric_dose_mg_per_kg = NULL, default_interval_hours = 8, default_route = 'IV',  suggestion_note = 'Avoid in children (extrapyramidal risk)' WHERE generic_name = 'Metoclopramide';
UPDATE drug_formularies SET typical_adult_dose_mg = 40,   typical_pediatric_dose_mg_per_kg = 1,   default_interval_hours = 24, default_route = 'PO',  suggestion_note = NULL WHERE generic_name = 'Omeprazole';
UPDATE drug_formularies SET typical_adult_dose_mg = 100,  typical_pediatric_dose_mg_per_kg = 4,   default_interval_hours = 6,  default_route = 'IV',  suggestion_note = NULL WHERE generic_name = 'Hydrocortisone';
UPDATE drug_formularies SET typical_adult_dose_mg = 5,    typical_pediatric_dose_mg_per_kg = 0.15, default_interval_hours = 6, default_route = 'NEB', suggestion_note = 'Nebulised 2.5–5 mg; repeat per response' WHERE generic_name = 'Salbutamol';
UPDATE drug_formularies SET typical_adult_dose_mg = 10,   typical_pediatric_dose_mg_per_kg = 0.3, default_interval_hours = 12, default_route = 'IV',  suggestion_note = 'Status epilepticus: 0.3 mg/kg IV / 0.5 mg/kg PR' WHERE generic_name = 'Diazepam';
