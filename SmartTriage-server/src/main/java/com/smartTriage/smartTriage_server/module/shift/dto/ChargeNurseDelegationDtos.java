package com.smartTriage.smartTriage_server.module.shift.dto;

import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Aggregated DTOs for the {@code charge_nurse_delegations} resource. Kept in
 * one file because the request/response shapes are tightly coupled and
 * neither stands alone outside this module.
 */
public class ChargeNurseDelegationDtos {

    private ChargeNurseDelegationDtos() {}

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class CreateRequest {
        @NotNull(message = "Delegate user ID is required")
        private UUID delegateUserId;

        @NotNull(message = "Start time is required")
        private Instant startsAt;

        /** Null means open-ended ("until I revoke"). UI must surface this. */
        private Instant endsAt;

        @NotNull(message = "Reason is required")
        private String reason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class RevokeRequest {
        private String revocationReason;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Response {
        private UUID id;
        private UUID hospitalId;

        private UUID delegatingUserId;
        private String delegatingUserName;

        private UUID delegateUserId;
        private String delegateUserName;

        private Instant startsAt;
        private Instant endsAt;
        private String reason;

        private Instant revokedAt;
        private UUID revokedById;
        private String revokedByName;
        private String revocationReason;

        /** Convenience: was this row in effect at the moment of serialization? */
        private boolean currentlyActive;
    }
}
