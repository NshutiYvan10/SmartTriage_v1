package com.smartTriage.smartTriage_server.module.fasttrack.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording ECG results in a fast-track activation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EcgResultRequest {

    @NotBlank(message = "ECG result is required")
    private String ecgResult;

    private Boolean stElevation;
}
