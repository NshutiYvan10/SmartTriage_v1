package com.smartTriage.smartTriage_server.module.medication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to countersign (verify) a medication administration.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CountersignMedicationRequest {

    /** The ID of the medication entry to countersign */
    private UUID medicationId;

    /** Optional: explicit countersigner name if not current user */
    private String countersignedByName;

    private String notes;
}
