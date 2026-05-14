package com.smartTriage.smartTriage_server.module.patient.dto;

import com.smartTriage.smartTriage_server.common.enums.ChronicConditionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Record a structured chronic condition. The clinician either picks
 * a curated entry from the frontend catalog (sending both the short
 * code and the canonical label) or enters free text (catalog code
 * omitted; the conditionName carries the clinician's words verbatim).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordChronicConditionRequest {

    @NotBlank(message = "Condition name is required")
    @Size(max = 200)
    private String conditionName;

    /** Curated short code from the frontend catalog (HTN, T2DM, …). */
    @Size(max = 40)
    private String conditionCode;

    /** Defaults to ACTIVE if omitted. */
    private ChronicConditionStatus status;

    @Size(max = 500)
    private String notes;

    private LocalDate onsetDate;

    @Size(max = 200)
    private String recordedByName;
}
