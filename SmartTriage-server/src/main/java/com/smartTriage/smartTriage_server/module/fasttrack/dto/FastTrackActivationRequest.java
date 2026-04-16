package com.smartTriage.smartTriage_server.module.fasttrack.dto;

import com.smartTriage.smartTriage_server.common.enums.FastTrackType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * Request DTO for activating a stroke or MI fast-track protocol.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FastTrackActivationRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotNull(message = "Fast-track type is required")
    private FastTrackType fastTrackType;

    private String activatedByName;

    /** Symptom onset time — critical for stroke thrombolysis window */
    private Instant symptomOnsetTime;

    /** BE-FAST screening score for stroke */
    private String beFastScore;

    /** NIH Stroke Scale score (0-42) */
    private Integer nihssScore;

    /** Chest pain onset time for MI */
    private Instant chestPainOnsetTime;

    private String notes;
}
