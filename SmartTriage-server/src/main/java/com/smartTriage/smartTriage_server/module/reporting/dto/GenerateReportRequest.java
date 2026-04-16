package com.smartTriage.smartTriage_server.module.reporting.dto;

import com.smartTriage.smartTriage_server.common.enums.MohReportType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Request DTO for generating a MoH report.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateReportRequest {

    @NotNull(message = "Hospital ID is required")
    private UUID hospitalId;

    @NotNull(message = "Report type is required")
    private MohReportType reportType;

    @NotNull(message = "Period start date is required")
    private LocalDate periodStart;

    @NotNull(message = "Period end date is required")
    private LocalDate periodEnd;

    private String generatedByName;
}
