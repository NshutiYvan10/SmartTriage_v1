package com.smartTriage.smartTriage_server.module.ems.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Paramedic sends the pre-arrival ping. The system creates a Visit
 * (or links to an existing patient) and broadcasts an
 * {@code EMS_PRE_ARRIVAL} alert to the receiving ED.
 *
 * Identity rules:
 *   - patientId set → link to known patient
 *   - patientId null → create a placeholder Visit + Patient under the
 *     run's hospital. The registrar resolves identity on arrival.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreregisterRequest {

    /** Optional — when known. */
    private UUID patientId;

    /** Updated ETA in minutes. Optional. */
    private Integer etaMinutes;

    /** Short pre-arrival summary the charge nurse sees on the inbound board. */
    private String preArrivalNote;
}
