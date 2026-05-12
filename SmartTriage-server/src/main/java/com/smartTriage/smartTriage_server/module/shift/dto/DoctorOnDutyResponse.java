package com.smartTriage.smartTriage_server.module.shift.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * V56 — one option in the triage form's "Notified Doctor" / "Attending
 * Doctor" picker. Sourced from the user's active shift assignment
 * today, filtered to a specific destination zone (RED → RESUS,
 * ORANGE → ACUTE, etc.) by the controller.
 *
 * <p>Sorted by clinical hierarchy: PRIMARY_DOCTOR first, then
 * SUPERVISING_DOCTOR, then RESIDENT — matches the ED's escalation
 * convention.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DoctorOnDutyResponse {

    /** User id — used by the form to capture a precise audit link. */
    private UUID userId;

    /** Display name shown in the dropdown option. */
    private String fullName;

    /** The user's role on today's shift in the queried zone. */
    private ShiftFunction shiftFunction;

    /** The user's zone (always matches the query — included for clarity). */
    private EdZone zone;

    /** True when this doctor is currently the shift-lead. */
    private boolean shiftLead;

    /**
     * Zone-aggregate active patient count. Proxy for "how busy is this
     * doctor right now". Not per-doctor today (we don't have a definitive
     * primaryDoctorId link on Visit yet); the same DTO surface will swap
     * to per-doctor counts once that field lands.
     */
    private long zonePatientCount;

    /**
     * Last activity timestamp for this user, if known. Lets the picker
     * show "last active 47 min ago" as a hint when the doctor's session
     * is stale. NULL when unknown.
     */
    private Instant lastActiveAt;
}
