package com.smartTriage.smartTriage_server.module.pathway.dto;

import com.smartTriage.smartTriage_server.common.enums.PathwayCategory;
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
public class ClinicalPathwayResponse {

    private UUID id;
    private String pathwayCode;
    private String pathwayName;
    private PathwayCategory category;
    private String description;
    private String targetPopulation;
    private String protocolVersion;
    private String sourceGuideline;
    private boolean isActive;
    private Instant createdAt;
}
