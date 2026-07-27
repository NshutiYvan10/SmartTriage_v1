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

    /**
     * When true the session opens as a SPOT_CHECK: it self-completes
     * once one validated full vitals set is captured (creating the
     * clinical VitalSigns snapshot that resets the patient's
     * reassessment clock) and hard-times-out after 10 minutes.
     * Default false = classic continuous monitoring.
     */
    private boolean spotCheck;
}
