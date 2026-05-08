package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.common.enums.CriticalContactMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Doctor's acknowledgement of a critical lab value.
 *
 * Phase 1 captures the read-back text as free-form (the doctor types
 * what they understood the value to be) and the contact method, so
 * the ack is a JCI-aligned attestation rather than a one-click "I saw
 * it". The fields are optional on the API so a covering ack with no
 * details is still possible, but the UI should always collect them.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AcknowledgeCriticalRequest {

    /** Free-text — what the doctor heard / read back. */
    private String readbackText;

    /** Phone / in-person / in-app. */
    private CriticalContactMethod contactMethod;

    private String acknowledgedByName;
}
