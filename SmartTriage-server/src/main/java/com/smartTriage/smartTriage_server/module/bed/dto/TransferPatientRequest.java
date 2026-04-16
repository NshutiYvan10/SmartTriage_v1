package com.smartTriage.smartTriage_server.module.bed.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Transfer a patient from one bed to another (e.g. Acute → Resus on
 * clinical deterioration).
 *
 * Side effects (handled atomically by BedService.transferPatient):
 *   * Source bed: currentVisit cleared, status → CLEANING
 *   * Destination bed: currentVisit ← visit, status → OCCUPIED
 *   * Any active DeviceSession on the source bed's monitor is closed
 *     (reason: "Patient transferred to <destCode>")
 *   * If the destination bed has an assigned monitor, a new DeviceSession
 *     is auto-created so vitals continuity is preserved on the patient's
 *     chart (readings just come from a different device after transfer).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TransferPatientRequest {

    @NotNull(message = "Destination bed ID is required")
    private UUID destinationBedId;

    /** Optional free-text reason shown in audit log. */
    private String reason;
}
