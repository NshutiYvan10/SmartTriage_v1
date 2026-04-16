package com.smartTriage.smartTriage_server.module.pathway.dto;

import com.smartTriage.smartTriage_server.common.enums.PathwayCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CreatePathwayRequest {

    @NotBlank(message = "Pathway code is required")
    private String pathwayCode;

    @NotBlank(message = "Pathway name is required")
    private String pathwayName;

    @NotNull(message = "Category is required")
    private PathwayCategory category;

    private String description;
    private String targetPopulation;
    private String protocolVersion;
    private String sourceGuideline;

    private List<CreatePathwayStepRequest> steps;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreatePathwayStepRequest {
        private Integer stepOrder;
        private String stepTitle;
        private String stepDescription;
        private Integer timeframeMinutes;
        private boolean isMandatory;
        private String category;
    }
}
