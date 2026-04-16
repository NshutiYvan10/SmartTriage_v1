package com.smartTriage.smartTriage_server.module.medication.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to record that a medication was actually administered.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AdministerMedicationRequest {

    /** The ID of the medication entry to update */
    private UUID medicationId;

    /** Optional: explicit administrator name if not current user */
    private String administeredByName;

    private String notes;
}
