package com.smartTriage.smartTriage_server.module.zonetransfer.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body of POST /zone-transfers/visit/{visitId}/initiate — a MANUAL
 * zone transfer opened by a clinician rather than the automatic
 * re-triage / deterioration path.
 *
 * <p>Two uses:
 * <ul>
 *   <li>Operational move — a charge nurse relocating a patient for
 *       flow reasons (overcrowding, isolation need, staffing).</li>
 *   <li>Step-down — a treating clinician moving a stabilised patient
 *       to a lower-acuity zone (e.g. RESUS → ACUTE).</li>
 * </ul>
 *
 * <p>The {@code toZone} is taken literally: unlike the auto path, a
 * manual step-down is never silently upgraded to a higher zone.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InitiateZoneTransferRequest {

    /** Target zone to move the patient to. Required. */
    @NotNull
    private EdZone toZone;

    /** Free-text rationale (operational reason or step-down justification). */
    private String reason;
}
