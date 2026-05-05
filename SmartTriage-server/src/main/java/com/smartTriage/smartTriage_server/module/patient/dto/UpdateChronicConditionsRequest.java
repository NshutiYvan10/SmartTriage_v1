package com.smartTriage.smartTriage_server.module.patient.dto;

import lombok.Data;

/**
 * Updates the patient's free-text chronicConditions field. Same semantics
 * as UpdateAllergiesRequest: full free-text replacement, null clears.
 */
@Data
public class UpdateChronicConditionsRequest {
    private String chronicConditions;
}
