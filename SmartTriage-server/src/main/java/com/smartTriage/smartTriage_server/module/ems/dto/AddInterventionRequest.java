package com.smartTriage.smartTriage_server.module.ems.dto;

import com.smartTriage.smartTriage_server.common.enums.EmsInterventionType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Paramedic adds one intervention to a run.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AddInterventionRequest {

    @NotNull(message = "Intervention type is required")
    private EmsInterventionType type;

    /** Defaults to now when null. */
    private Instant givenAt;

    private String givenByName;
    private String detail;
    private String dose;
    private String route;
    private String outcome;
    private String notes;
}
