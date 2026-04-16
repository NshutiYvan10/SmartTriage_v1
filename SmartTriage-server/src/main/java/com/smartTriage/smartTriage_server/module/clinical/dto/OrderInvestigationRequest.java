package com.smartTriage.smartTriage_server.module.clinical.dto;

import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to order an investigation for a visit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderInvestigationRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotNull(message = "Investigation type is required")
    private InvestigationType investigationType;

    @NotBlank(message = "Test name is required")
    private String testName;

    /** Name of ordering clinician */
    private String orderedByName;

    /** Priority: STAT, URGENT, ROUTINE (default ROUTINE) */
    private String priority;

    private String notes;
}
