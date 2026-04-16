package com.smartTriage.smartTriage_server.module.pathway.dto;

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
