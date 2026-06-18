package com.smartTriage.smartTriage_server.module.consent.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Request to withdraw a previously-given consent. The withdrawing clinician is
 *  derived from the authenticated user; only a reason is supplied. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WithdrawConsentRequest {

    @NotBlank(message = "A reason is required to withdraw consent")
    private String reason;
}
