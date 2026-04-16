package com.smartTriage.smartTriage_server.common.enums;

/**
 * Types of clinical notes that can be recorded during an ED visit.
 * Maps to the documentation sections on the Rwanda national triage forms.
 */
public enum NoteType {

    /** Physical examination findings */
    PHYSICAL_FINDINGS,

    /** Progress notes during the visit */
    PROGRESS_NOTE,

    /** Nursing observations and notes */
    NURSING_NOTE,

    /** Doctor's clinical notes */
    DOCTOR_NOTE,

    /** Triage narrative / clinical impression */
    TRIAGE_NOTE,

    /** History of presenting complaint (HPC) */
    HISTORY_OF_PRESENTING_COMPLAINT,

    /** Past medical history */
    PAST_MEDICAL_HISTORY,

    /** Social history */
    SOCIAL_HISTORY,

    /** Family history */
    FAMILY_HISTORY,

    /** Review of systems */
    REVIEW_OF_SYSTEMS,

    /** Allergies and adverse reactions */
    ALLERGIES,

    /** Current medications (pre-visit) */
    CURRENT_MEDICATIONS,

    /** Treatment plan / management plan */
    TREATMENT_PLAN,

    /** Discharge summary */
    DISCHARGE_SUMMARY,

    /** Handover notes (shift handover, transfer) */
    HANDOVER,

    /** Other / free text note */
    OTHER
}
