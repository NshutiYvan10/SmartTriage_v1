package com.smartTriage.smartTriage_server.module.icu.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Request DTO for recording the ICU team's response to an escalation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IcuResponseRequest {

    private boolean accepted;

    private String declineReason;

    private String bedNumber;
}
