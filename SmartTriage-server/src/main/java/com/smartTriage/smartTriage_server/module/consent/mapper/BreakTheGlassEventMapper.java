package com.smartTriage.smartTriage_server.module.consent.mapper;

import com.smartTriage.smartTriage_server.module.consent.dto.BreakTheGlassEventResponse;
import com.smartTriage.smartTriage_server.module.consent.entity.BreakTheGlassEvent;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;

/**
 * Maps a {@link BreakTheGlassEvent} to its governance response. The national ID is masked here —
 * the governance audience audits the clinician, never the patient's full identifier. Must be
 * invoked inside an open transaction (reads the lazy PersonIdentity).
 */
public final class BreakTheGlassEventMapper {

    private BreakTheGlassEventMapper() {}

    public static BreakTheGlassEventResponse toResponse(BreakTheGlassEvent e) {
        PersonIdentity identity = e.getPersonIdentity();
        return BreakTheGlassEventResponse.builder()
                .id(e.getId())
                .personIdentityId(identity != null ? identity.getId() : null)
                .maskedNationalId(mask(identity != null ? identity.getNationalId() : null))
                .actorUserId(e.getActorUserId())
                .actorName(e.getActorName())
                .actorRole(e.getActorRole())
                .actorHospitalId(e.getActorHospitalId())
                .reason(e.getReason())
                .priorConsentState(e.getPriorConsentState())
                .accessedAt(e.getAccessedAt())
                .acknowledged(e.isAcknowledged())
                .acknowledgedByName(e.getAcknowledgedByName())
                .acknowledgedAt(e.getAcknowledgedAt())
                .acknowledgmentNote(e.getAcknowledgmentNote())
                .build();
    }

    private static String mask(String nationalId) {
        return nationalId == null || nationalId.length() < 4
                ? "(none)" : "***" + nationalId.substring(nationalId.length() - 4);
    }
}
