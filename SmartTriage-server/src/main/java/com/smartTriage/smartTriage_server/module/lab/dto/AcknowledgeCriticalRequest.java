package com.smartTriage.smartTriage_server.module.lab.dto;

import com.smartTriage.smartTriage_server.common.enums.CriticalContactMethod;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Doctor's acknowledgement of a critical lab value.
 *
 * Captures the read-back text as free-form (the doctor types what they
 * understood the value to be) and the contact method, so the ack is a
 * JCI-aligned attestation rather than a one-click "I saw it". The service
 * enforces the contract server-side: contactMethod is always required, and
 * readbackText is required for the VERBAL channels (PHONE / IN_PERSON, per
 * record-and-read-back); for IN_APP the displayed value + the ack itself is
 * the confirmation and readbackText is an optional note.
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
