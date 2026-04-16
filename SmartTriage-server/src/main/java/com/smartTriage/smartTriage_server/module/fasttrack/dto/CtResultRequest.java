package com.smartTriage.smartTriage_server.module.fasttrack.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording CT scan results in a fast-track activation.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CtResultRequest {

    @NotBlank(message = "CT result is required")
    private String ctResult;

    private Boolean isHemorrhagic;
}
