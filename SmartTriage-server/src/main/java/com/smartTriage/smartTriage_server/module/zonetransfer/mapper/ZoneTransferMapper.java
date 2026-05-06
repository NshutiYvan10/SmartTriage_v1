package com.smartTriage.smartTriage_server.module.zonetransfer.mapper;

import com.smartTriage.smartTriage_server.module.user.entity.User;
import com.smartTriage.smartTriage_server.module.zonetransfer.dto.ZoneTransferResponse;
import com.smartTriage.smartTriage_server.module.zonetransfer.entity.ZoneTransfer;

public final class ZoneTransferMapper {

    private ZoneTransferMapper() {}

    public static ZoneTransferResponse toResponse(ZoneTransfer t) {
        ZoneTransferResponse.ZoneTransferResponseBuilder b = ZoneTransferResponse.builder()
                .id(t.getId())
                .fromZone(t.getFromZone())
                .toZone(t.getToZone())
                .status(t.getStatus())
                .reason(t.getReason())
                .initiatedAt(t.getInitiatedAt())
                .acceptedAt(t.getAcceptedAt())
                .declinedAt(t.getDeclinedAt())
                .declinedReason(t.getDeclinedReason())
                .handoverNote(t.getHandoverNote())
                .triggeringAlertId(t.getTriggeringAlertId())
                .triggeringSignEventId(t.getTriggeringSignEventId())
                .createdAt(t.getCreatedAt());

        if (t.getVisit() != null) {
            b.visitId(t.getVisit().getId())
                    .visitNumber(t.getVisit().getVisitNumber())
                    .isPediatric(t.getVisit().isPediatric());
            if (t.getVisit().getPatient() != null) {
                b.patientName(t.getVisit().getPatient().getFirstName() + " "
                        + t.getVisit().getPatient().getLastName());
            }
        }
        nameAndId(t.getInitiatedBy(), b::initiatedById, b::initiatedByName);
        nameAndId(t.getProposedClinician(), b::proposedClinicianId, b::proposedClinicianName);
        nameAndId(t.getAcceptedBy(), b::acceptedById, b::acceptedByName);
        nameAndId(t.getDeclinedBy(), b::declinedById, b::declinedByName);
        return b.build();
    }

    private static void nameAndId(
            User u,
            java.util.function.Consumer<java.util.UUID> idSetter,
            java.util.function.Consumer<String> nameSetter) {
        if (u == null) return;
        idSetter.accept(u.getId());
        String first = u.getFirstName() == null ? "" : u.getFirstName();
        String last = u.getLastName() == null ? "" : u.getLastName();
        String full = (first + " " + last).trim();
        if (!full.isEmpty()) nameSetter.accept(full);
    }
}
