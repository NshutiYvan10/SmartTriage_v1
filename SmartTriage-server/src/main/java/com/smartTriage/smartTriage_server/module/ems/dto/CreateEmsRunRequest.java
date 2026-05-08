package com.smartTriage.smartTriage_server.module.ems.dto;

import com.smartTriage.smartTriage_server.common.enums.EmsService;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Paramedic starts a new run from the field. The hospital ID is
 * required so the pre-arrival ping (later) goes to the correct ED.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreateEmsRunRequest {

    @NotNull(message = "Receiving hospital is required")
    private UUID hospitalId;

    private EmsService service;
    private String unitCallsign;
    private String paramedicName;

    // Patient context — all optional; "Unknown" patient is OK
    private Integer patientAgeYears;
    private String patientSex;
    private String incidentLocation;
    private String mechanism;
    private String historySummary;
}
