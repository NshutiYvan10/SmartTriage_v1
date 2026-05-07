package com.smartTriage.smartTriage_server.module.shift.mapper;

import com.smartTriage.smartTriage_server.module.shift.dto.StaffLeaveDtos;
import com.smartTriage.smartTriage_server.module.shift.entity.StaffLeave;
import com.smartTriage.smartTriage_server.module.user.entity.User;

public final class StaffLeaveMapper {

    private StaffLeaveMapper() {}

    public static StaffLeaveDtos.Response toResponse(StaffLeave l) {
        if (l == null) return null;
        return StaffLeaveDtos.Response.builder()
                .id(l.getId())
                .hospitalId(l.getHospital() != null ? l.getHospital().getId() : null)
                .userId(l.getUser() != null ? l.getUser().getId() : null)
                .userName(fullName(l.getUser()))
                .leaveType(l.getLeaveType())
                .leaveStatus(l.getLeaveStatus())
                .startsOn(l.getStartsOn())
                .endsOn(l.getEndsOn())
                .reason(l.getReason())
                .requestedAt(l.getRequestedAt())
                .requestedById(l.getRequestedBy() != null ? l.getRequestedBy().getId() : null)
                .requestedByName(fullName(l.getRequestedBy()))
                .approvedAt(l.getApprovedAt())
                .approvedById(l.getApprovedBy() != null ? l.getApprovedBy().getId() : null)
                .approvedByName(fullName(l.getApprovedBy()))
                .rejectedAt(l.getRejectedAt())
                .rejectedById(l.getRejectedBy() != null ? l.getRejectedBy().getId() : null)
                .rejectedByName(fullName(l.getRejectedBy()))
                .rejectionReason(l.getRejectionReason())
                .cancelledAt(l.getCancelledAt())
                .cancelledById(l.getCancelledBy() != null ? l.getCancelledBy().getId() : null)
                .cancelledByName(fullName(l.getCancelledBy()))
                .externalReference(l.getExternalReference())
                .build();
    }

    private static String fullName(User u) {
        if (u == null) return null;
        return (u.getFirstName() == null ? "" : u.getFirstName())
                + " "
                + (u.getLastName() == null ? "" : u.getLastName());
    }
}
