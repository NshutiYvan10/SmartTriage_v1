package com.smartTriage.smartTriage_server.module.shift.mapper;

import com.smartTriage.smartTriage_server.module.shift.dto.ShiftSwapDtos;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftAssignment;
import com.smartTriage.smartTriage_server.module.shift.entity.ShiftSwapRequest;
import com.smartTriage.smartTriage_server.module.user.entity.User;

public final class ShiftSwapMapper {

    private ShiftSwapMapper() {}

    public static ShiftSwapDtos.Response toResponse(ShiftSwapRequest s) {
        if (s == null) return null;
        return ShiftSwapDtos.Response.builder()
                .id(s.getId())
                .hospitalId(s.getHospital() != null ? s.getHospital().getId() : null)
                .status(s.getStatus())
                .requestReason(s.getRequestReason())
                .requesterSide(toSnapshot(s.getRequesterAssignment()))
                .partnerSide(toSnapshot(s.getPartnerAssignment()))
                .createdAt(s.getCreatedAt())
                .partnerRespondedAt(s.getPartnerRespondedAt())
                .partnerResponseNote(s.getPartnerResponseNote())
                .chargeRespondedAt(s.getChargeRespondedAt())
                .chargeResponderId(s.getChargeResponder() != null ? s.getChargeResponder().getId() : null)
                .chargeResponderName(fullName(s.getChargeResponder()))
                .chargeResponseNote(s.getChargeResponseNote())
                .cancelledAt(s.getCancelledAt())
                .cancelledById(s.getCancelledBy() != null ? s.getCancelledBy().getId() : null)
                .cancelledByName(fullName(s.getCancelledBy()))
                .rejectionReason(s.getRejectionReason())
                .build();
    }

    private static ShiftSwapDtos.AssignmentSnapshot toSnapshot(ShiftAssignment a) {
        if (a == null) return null;
        User u = a.getUser();
        return ShiftSwapDtos.AssignmentSnapshot.builder()
                .assignmentId(a.getId())
                .userId(u != null ? u.getId() : null)
                .userName(fullName(u))
                .shiftDate(a.getShiftDate())
                .shiftPeriod(a.getShiftPeriod())
                .zone(a.getZone())
                .shiftFunction(a.getShiftFunction())
                .build();
    }

    private static String fullName(User u) {
        if (u == null) return null;
        return (u.getFirstName() == null ? "" : u.getFirstName())
                + " "
                + (u.getLastName() == null ? "" : u.getLastName());
    }
}
