package com.smartTriage.smartTriage_server.module.fasttrack.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Body for completing or cancelling a fast-track activation.
 * {@code outcome} is the clinical outcome note on completion; {@code reason}
 * is the cancellation reason. Both optional.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FastTrackOutcomeRequest {
    private String outcome;
    private String reason;
}
