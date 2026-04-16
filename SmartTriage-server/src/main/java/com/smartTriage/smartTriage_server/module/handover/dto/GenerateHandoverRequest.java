package com.smartTriage.smartTriage_server.module.handover.dto;

import com.smartTriage.smartTriage_server.common.enums.HandoverReportType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateHandoverRequest {

    @NotNull(message = "Report type is required")
    private HandoverReportType reportType;

    private String generatedByName;

    private String notes;
}
