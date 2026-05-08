package com.smartTriage.smartTriage_server.module.lab.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Junior tech emergency override — releases an AWAITING_VERIFICATION
 * result without senior sign-off. The reason is required and is
 * persisted so an audit can show why the safety gate was bypassed.
 *
 * Realistic use case: 03:00, no senior on duty, patient deteriorating
 * and the doctor needs the value now. The auto-release timeout would
 * eventually fire, but the junior chooses to release immediately.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OverrideVerificationRequest {

    @NotBlank(message = "A reason is required when bypassing senior verification.")
    private String reason;

    private String overrideByName;
}
