package com.smartTriage.smartTriage_server.module.iot.dto;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request to start a monitoring session — link a device to a patient's visit.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StartMonitoringRequest {

    @NotNull(message = "Device ID is required")
    private UUID deviceId;

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    /** Name of the clinician initiating monitoring */
    private String startedByName;
}
