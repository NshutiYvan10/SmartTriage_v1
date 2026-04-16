package com.smartTriage.smartTriage_server.module.medication.dto;

import com.smartTriage.smartTriage_server.common.enums.MedicationRoute;
import com.smartTriage.smartTriage_server.common.enums.MedicationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Full response DTO for a Medication Administration Record (MAR) entry.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MedicationResponse {

    private UUID id;
    private UUID visitId;

    // Drug details
    private String drugName;
    private String dose;
    private MedicationRoute route;
    private String frequency;

    // Prescribing chain
    private UUID prescribedById;
    private String prescribedByName;
    private Instant prescribedAt;

    // Administration chain
    private UUID administeredById;
    private String administeredByName;
    private Instant administeredAt;

    // Countersigning chain
    private UUID countersignedById;
    private String countersignedByName;
    private Instant countersignedAt;

    // Status & notes
    private MedicationStatus status;
    private String notes;

    // Audit
    private Instant createdAt;
    private Instant updatedAt;
}
