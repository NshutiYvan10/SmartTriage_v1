package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.common.enums.LabPriority;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to create a new lab order.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderLabRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @NotBlank(message = "Test name is required")
    private String testName;

    private String testCode;

    @NotNull(message = "Priority is required")
    private LabPriority priority;

    private String orderedByName;

    /** Specimen type — e.g., "blood", "urine", "CSF", "sputum" */
    private String specimenType;

    private String notes;
}
