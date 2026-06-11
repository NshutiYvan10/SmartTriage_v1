package com.smartTriage.smartTriage_server.module.medication.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Infusion lifecycle event for a CONTINUOUS order (V67):
 * start (confirm initiation), rate change, or stop.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InfusionEventRequest {

    /** Rate at this event. Start defaults to the order's prescribed rate. */
    @DecimalMin(value = "0.0", inclusive = false, message = "Rate must be positive")
    private Double rateValue;

    @Size(max = 20)
    private String rateUnit;

    /** Display-name fallback for the recording nurse. */
    @Size(max = 255)
    private String recordedByName;

    /** Second clinician — mandatory when the order requires a witness (start only). */
    @Size(max = 255)
    private String witnessName;

    /** Reason — required for stop and rate-change events. */
    @Size(max = 500)
    private String reason;
}
