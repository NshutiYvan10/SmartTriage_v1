package com.smartTriage.smartTriage_server.module.patient.dto;

import lombok.Data;

/**
 * Updates the patient's free-text knownAllergies field. Distinct from
 * UpdatePregnancyStatusRequest by intent: this is for clinicians who
 * learn of a new allergy mid-visit (e.g. patient reacts to a test dose,
 * family arrives and clarifies history). The new value REPLACES the
 * existing free-text — clinicians wanting to add to existing allergies
 * should compose the new full string client-side and send it whole.
 *
 * Null is a real, intentional value here: a clinician may want to clear
 * a previously-recorded allergy that turned out to be wrong. The service
 * persists null as "no allergies recorded" rather than rejecting.
 */
@Data
public class UpdateAllergiesRequest {
    /** Full free-text replacement. Comma-separated convention; null clears. */
    private String knownAllergies;
}
