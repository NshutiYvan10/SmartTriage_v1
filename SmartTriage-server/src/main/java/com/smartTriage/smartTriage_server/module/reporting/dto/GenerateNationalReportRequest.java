package com.smartTriage.smartTriage_server.module.reporting.dto;

import com.smartTriage.smartTriage_server.common.enums.MohReportType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDate;

/**
 * Request DTO for generating a NATIONAL MoH report (aggregated across all active hospitals).
 * Unlike {@link GenerateReportRequest} there is no hospitalId — the rollup spans every hospital.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GenerateNationalReportRequest {

    @NotNull(message = "Report type is required")
    private MohReportType reportType;

    @NotNull(message = "Period start date is required")
    private LocalDate periodStart;

    @NotNull(message = "Period end date is required")
    private LocalDate periodEnd;
}
