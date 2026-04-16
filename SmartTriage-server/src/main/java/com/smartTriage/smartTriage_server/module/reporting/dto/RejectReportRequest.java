package com.smartTriage.smartTriage_server.module.reporting.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for rejecting a submitted MoH report.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RejectReportRequest {

    @NotBlank(message = "Rejection reason is required")
    private String reason;
}
