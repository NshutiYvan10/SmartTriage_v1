package com.smartTriage.smartTriage_server.module.clinicalsigns.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Batch update for one or more signs at the same observation timestamp.
 * A typical ward-round entry might update Convulsions → ABSENT,
 * AlteredMentalStatus → IMPROVING, ChestPain → PRESENT (newly emerged)
 * — all three landing on the same recorded_at.
 *
 * recordedAt is optional; defaults to "now" on the server when null.
 * recordedByName is optional fallback for the user display string when
 * the authenticated principal can't be resolved.
 */
@Data
public class RecordClinicalSignsBatchRequest {

    @NotNull(message = "visitId is required")
    private UUID visitId;

    @NotEmpty(message = "events list must contain at least one entry")
    @Valid
    private List<RecordClinicalSignRequest> events;

    /** Optional explicit clinical observation time. Defaults to server "now". */
    private Instant recordedAt;

    private String recordedByName;
}
