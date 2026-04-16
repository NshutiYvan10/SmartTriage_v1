package com.smartTriage.smartTriage_server.module.pathway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkipStepRequest {

    @NotBlank(message = "Skip reason is required")
    private String reason;

    private String completedByName;
}
