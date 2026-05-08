package com.smartTriage.smartTriage_server.module.lab.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Senior tech verifies and releases a pending lab result.
 * The verifier name is captured for the audit trail.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VerifyResultRequest {

    /** Senior tech (HEAD_LAB_TECHNICIAN) signing off. */
    private String verifiedByName;

    /** Optional sign-off note. */
    private String notes;
}
