package com.smartTriage.smartTriage_server.module.zonetransfer.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Body of POST /zone-transfers/{id}/accept.
 *
 * <p>Both fields are optional:
 * <ul>
 *   <li>{@code handoverNote} — free-text note from the accepting
 *       clinician, stored on the transfer row.</li>
 *   <li>{@code destinationBedId} — when present, the physical move
 *       happens in the same transaction as the acceptance: the
 *       patient's current bed (if any) is released to CLEANING, the
 *       destination bed is occupied, and the monitoring session hops
 *       to the destination bed's monitor with chart continuity
 *       preserved. The bed must be in the transfer's target zone.
 *       When absent, only the logical zone + clinician change — the
 *       bed move can be done later from the bed board ("accept now,
 *       move when the bay is ready").</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcceptTransferRequest {

    private String handoverNote;

    private UUID destinationBedId;
}
