package com.smartTriage.smartTriage_server.module.icu.dto;

import com.smartTriage.smartTriage_server.common.enums.IcuTriggerType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Request DTO for initiating an ICU escalation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IcuEscalationRequest {

    @NotNull(message = "Visit ID is required")
    private UUID visitId;

    private IcuTriggerType triggerType;

    @NotBlank(message = "Escalation reason is required")
    private String escalationReason;
}
