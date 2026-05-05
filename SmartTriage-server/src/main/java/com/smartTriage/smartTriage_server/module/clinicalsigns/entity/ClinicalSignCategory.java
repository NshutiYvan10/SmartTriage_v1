package com.smartTriage.smartTriage_server.module.clinicalsigns.entity;

/**
 * Top-level grouping of clinical signs, mirroring the sections of the
 * Rwanda National Triage Form (Adult and Child).
 *
 * Used for grouping in the doctor's chart (Emergency / mSAT VU / mSAT URG /
 * Special / Pediatric Emergency) and for the re-triage engine's escalation
 * rules — different categories drive different category upgrades.
 */
public enum ClinicalSignCategory {
    /** Section 1 emergency signs — life-threatening, drive RED category. */
    EMERGENCY,
    /** Section 1b pediatric-only emergency signs (child triage form). */
    PEDIATRIC_EMERGENCY,
    /** Section 3 mSAT Very Urgent discriminators — drive ORANGE category. */
    MSAT_VU,
    /** Section 4 mSAT Urgent discriminators — drive YELLOW category. */
    MSAT_URG,
    /** Bottom-of-form special considerations — context, not category-driving. */
    SPECIAL
}
