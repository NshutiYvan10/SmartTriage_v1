package com.smartTriage.smartTriage_server.module.ems.dto;

import com.smartTriage.smartTriage_server.common.enums.EmsInterventionType;
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
public class EmsInterventionResponse {
    private UUID id;
    private EmsInterventionType type;
    private Instant givenAt;
    private String givenByName;
    private String detail;
    private String dose;
    private String route;
    private String outcome;
    private String notes;
}
