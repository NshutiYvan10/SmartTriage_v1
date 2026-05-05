package com.smartTriage.smartTriage_server.common.enums;

/**
 * How a candidate row matched a patient-lookup query.
 *
 * Used by {@code PatientLookupService} so the frontend can show the triage
 * nurse exactly why each candidate appeared. Ordered roughly by trust:
 * Tier 1 = deterministic single-row match; Tier 3/4 = ranked fuzzy matches.
 */
public enum MatchType {
    /** Exact match on national_id (Tier 1 — gold standard for adults). */
    NATIONAL_ID,

    /** Exact match on passport_number (Tier 1 — foreigners). */
    PASSPORT,

    /** Exact match on birth_certificate_number (Tier 1 — pediatric). */
    BIRTH_CERTIFICATE,

    /** Exact match on hospital-scoped MRN (Tier 2 — internal). */
    MRN,

    /** Exact match on phone_number AND date_of_birth (Tier 3). */
    PHONE_AND_DOB,

    /** Exact match on phone_number only (Tier 3 — lower confidence). */
    PHONE,

    /** Exact match on guardian_national_id, with name/DOB corroboration (Tier 3 pediatric). */
    GUARDIAN_NATIONAL_ID,

    /** Exact match on guardian_phone, with name/DOB corroboration (Tier 3 pediatric). */
    GUARDIAN_PHONE,

    /** Exact match on first+last name AND date_of_birth (Tier 4 — last resort). */
    DEMOGRAPHIC
}
