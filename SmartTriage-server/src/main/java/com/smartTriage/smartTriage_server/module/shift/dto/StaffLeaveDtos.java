package com.smartTriage.smartTriage_server.module.shift.dto;

import com.smartTriage.smartTriage_server.common.enums.LeaveStatus;
import com.smartTriage.smartTriage_server.common.enums.LeaveType;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public class StaffLeaveDtos {

    private StaffLeaveDtos() {}

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CreateRequest {
        /**
         * The user the leave is for. Optional — when omitted, the request is
         * for the authenticated user themselves (self-service path). When
         * present, the controller enforces that the actor has CN/admin
         * authority to file leave on someone else's behalf.
         */
        private UUID userId;

        @NotNull(message = "Leave type is required")
        private LeaveType leaveType;

        @NotNull(message = "Start date is required")
        private LocalDate startsOn;

        @NotNull(message = "End date is required")
        private LocalDate endsOn;

        private String reason;

        /**
         * When true and the actor has approval authority, the row is created
         * already in APPROVED status. Used for retroactive sick leave
         * ("nurse called in sick this morning").
         */
        @Builder.Default
        private boolean autoApprove = false;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class DecisionRequest {
        /** Required for REJECTED, optional for APPROVED. */
        private String note;
    }

    @Data @NoArgsConstructor @AllArgsConstructor @Builder
    public static class Response {
        private UUID id;
        private UUID hospitalId;
        private UUID userId;
        private String userName;

        private LeaveType leaveType;
        private LeaveStatus leaveStatus;
        private LocalDate startsOn;
        private LocalDate endsOn;
        private String reason;

        private Instant requestedAt;
        private UUID requestedById;
        private String requestedByName;

        private Instant approvedAt;
        private UUID approvedById;
        private String approvedByName;

        private Instant rejectedAt;
        private UUID rejectedById;
        private String rejectedByName;
        private String rejectionReason;

        private Instant cancelledAt;
        private UUID cancelledById;
        private String cancelledByName;

        private String externalReference;
    }
}
