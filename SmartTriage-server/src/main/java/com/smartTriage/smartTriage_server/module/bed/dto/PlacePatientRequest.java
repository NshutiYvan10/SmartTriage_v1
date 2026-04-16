package com.smartTriage.smartTriage_server.module.bed.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Place a triaged patient into a specific bed.
 *
 * Side effects (handled by BedService.placePatient):
 *   * Bed.currentVisit ← visit   AND  Visit.currentBed ← bed (atomic)
 *   * Bed.status ← OCCUPIED
 *   * Visit.status ← UNDER_ASSESSMENT (if still TRIAGED)
 *   * If the bed has an assigned IoTDevice, a DeviceSession is
 *     auto-created so vitals flow without a manual pairing click.
 *
 * The caller's authenticated identity is used as "placedBy" — no need to
 * send it in the body.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlacePatientRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;
}
