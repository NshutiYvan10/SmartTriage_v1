-- V52 — Remove OTHER from the Gender enum.
--
-- Hospital policy: the system only records MALE / FEMALE for
-- identified patients. UNKNOWN remains as the sentinel for
-- placeholder / unidentified patients (Direct Resus, EMS unknown
-- arrivals).
--
-- Any existing rows with gender='OTHER' are migrated to UNKNOWN
-- rather than dropped — keeps the row valid without forcing a
-- clinical guess on data already captured. If a hospital's actual
-- policy needs MALE/FEMALE only on every row, that's a follow-up
-- registrar workflow, not a schema concern.

UPDATE patients SET gender = 'UNKNOWN' WHERE gender = 'OTHER';
