package com.smartTriage.smartTriage_server.module.pathway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request DTO for abandoning a pathway activation — a reason is mandatory (deviation trail). */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AbandonPathwayRequest {

    @NotBlank(message = "An abandon reason is required")
    private String reason;
}
