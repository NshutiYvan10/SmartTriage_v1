package com.smartTriage.smartTriage_server.module.pathway.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathwayStepResponse {

    private UUID id;
    private UUID pathwayId;
    private Integer stepOrder;
    private String stepTitle;
    private String stepDescription;
    private Integer timeframeMinutes;
    private boolean isMandatory;
    private String category;
}
