package com.smartTriage.smartTriage_server.module.vital.dto;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.VitalSource;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VitalSignsResponse {

    private UUID id;
    private UUID visitId;
    private Instant recordedAt;
    private Integer respiratoryRate;
    private Integer heartRate;
    private Integer systolicBp;
    private Integer diastolicBp;
    private Double temperature;
    private Integer spo2;
    private AvpuScore avpu;
    private Double bloodGlucose;
    private Integer painScore;
    private Integer gcsScore;
    /**
     * Phase 12b — adult body weight in kg. Optional; nullable on
     * most vitals rows. Drives Cockcroft-Gault eGFR for renal
     * dose checking.
     */
    private BigDecimal weightKg;
    private VitalSource source;
    private String deviceId;
    private String notes;
    private Instant createdAt;
}
