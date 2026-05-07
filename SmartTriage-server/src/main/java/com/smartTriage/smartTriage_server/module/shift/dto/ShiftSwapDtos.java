package com.smartTriage.smartTriage_server.module.shift.dto;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.common.enums.ShiftFunction;
import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.common.enums.SwapStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class ShiftSwapDtos {

    private ShiftSwapDtos() {}

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateRequest {
        @NotNull(message = "Requester assignment ID is required")
        private UUID requesterAssignmentId;

        @NotNull(message = "Partner assignment ID is required")
        private UUID partnerAssignmentId;

        private String requestReason;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DecisionRequest {
        /** Optional note from the responder. Required for rejections. */
        private String note;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class AssignmentSnapshot {
        private UUID assignmentId;
        private UUID userId;
        private String userName;
        private LocalDate shiftDate;
        private ShiftPeriod shiftPeriod;
        private EdZone zone;
        private ShiftFunction shiftFunction;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Response {
        private UUID id;
        private UUID hospitalId;
        private SwapStatus status;
        private String requestReason;

        private AssignmentSnapshot requesterSide;
        private AssignmentSnapshot partnerSide;

        private Instant createdAt;
        private Instant partnerRespondedAt;
        private String partnerResponseNote;

        private Instant chargeRespondedAt;
        private UUID chargeResponderId;
        private String chargeResponderName;
        private String chargeResponseNote;

        private Instant cancelledAt;
        private UUID cancelledById;
        private String cancelledByName;

        private String rejectionReason;
    }
}
