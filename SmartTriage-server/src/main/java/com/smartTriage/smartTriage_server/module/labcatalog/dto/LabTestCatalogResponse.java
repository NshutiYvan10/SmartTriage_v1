package com.smartTriage.smartTriage_server.module.labcatalog.dto;

import com.smartTriage.smartTriage_server.common.enums.InvestigationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LabTestCatalogResponse {
    private UUID id;
    private String testName;
    private String shortName;
    private InvestigationType investigationType;
    private String category;
    private String specimenType;
    private Integer statTurnaroundMinutes;
    private Integer routineTurnaroundMinutes;
    private String clinicalUse;
    private boolean isCommonInRwanda;
}
