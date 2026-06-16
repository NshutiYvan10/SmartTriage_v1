package com.smartTriage.smartTriage_server.module.vital.dto;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.GlucoseUnit;
import com.smartTriage.smartTriage_server.common.enums.VitalSource;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecordVitalsRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    @Min(value = 0, message = "Respiratory rate cannot be negative")
    @Max(value = 80, message = "Respiratory rate exceeds physiological range")
    private Integer respiratoryRate;

    @Min(value = 0, message = "Heart rate cannot be negative")
    @Max(value = 300, message = "Heart rate exceeds physiological range")
    private Integer heartRate;

    @Min(value = 0, message = "Systolic BP cannot be negative")
    @Max(value = 300, message = "Systolic BP exceeds physiological range")
    private Integer systolicBp;

    @Min(value = 0, message = "Diastolic BP cannot be negative")
    @Max(value = 200, message = "Diastolic BP exceeds physiological range")
    private Integer diastolicBp;

    @Min(value = 25, message = "Temperature below physiological range")
    @Max(value = 45, message = "Temperature above physiological range")
    private Double temperature;

    @Min(value = 0, message = "SpO2 cannot be negative")
    @Max(value = 100, message = "SpO2 cannot exceed 100%")
    private Integer spo2;

    private AvpuScore avpu;

    /**
     * Blood glucose in {@link #bloodGlucoseUnit} (default mmol/L). Stored and
     * classified in mmol/L after conversion. No physiologic Min/Max here on
     * purpose: a hyperglycemic-crisis value legitimately exceeds 40 mmol/L, so a
     * hard cap would block recording it — the unit-aware conversion, not a range
     * clamp, is what keeps the value on the correct scale.
     */
    private Double bloodGlucose;

    /** Unit {@link #bloodGlucose} was entered in. Defaults to mmol/L when omitted. */
    @Builder.Default
    private GlucoseUnit bloodGlucoseUnit = GlucoseUnit.MMOL_L;

    @Min(value = 0, message = "Pain score minimum is 0")
    @Max(value = 10, message = "Pain score maximum is 10")
    private Integer painScore;

    @Min(value = 3, message = "GCS minimum is 3")
    @Max(value = 15, message = "GCS maximum is 15")
    private Integer gcsScore;

    /**
     * Phase 12b — adult body weight in kg. Optional. Drives Cockcroft-
     * Gault eGFR for renal-risk dose checking on the prescribe path.
     * Range gates obviously-wrong entries (e.g. typo'd 250 kg or
     * decimal-shifted 5 kg adult).
     */
    @DecimalMin(value = "10.00", message = "Weight below physiological range")
    @DecimalMax(value = "300.00", message = "Weight above physiological range")
    private BigDecimal weightKg;

    @Builder.Default
    private VitalSource source = VitalSource.MANUAL_ENTRY;

    private String deviceId;
    private String notes;
}
