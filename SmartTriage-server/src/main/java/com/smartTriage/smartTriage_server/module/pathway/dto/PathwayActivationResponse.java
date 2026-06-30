package com.smartTriage.smartTriage_server.module.pathway.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.PathwayActivationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathwayActivationResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;

    // ── Denormalised patient context (board / list display) ──
    // Every patient-scoped activation row MUST show WHO the pathway is
    // for and WHERE that patient is, without a second fetch.
    // Populated from visit → patient / currentEdZone / currentBed.
    private UUID patientId;
    private String patientName;
    private EdZone currentZone;
    private String currentBedLabel;

    private UUID pathwayId;
    private String pathwayName;
    private String pathwayCode;
    private Instant activatedAt;
    private String activatedByName;
    private Instant completedAt;
    private PathwayActivationStatus status;
    private String deviationReason;
    private String notes;
    private Instant createdAt;
}
