package com.smartTriage.smartTriage_server.module.shift.mapper;

import com.smartTriage.smartTriage_server.module.shift.dto.ChargeNurseDelegationDtos;
import com.smartTriage.smartTriage_server.module.shift.entity.ChargeNurseDelegation;
import com.smartTriage.smartTriage_server.module.user.entity.User;

import java.time.Instant;

public final class ChargeNurseDelegationMapper {

    private ChargeNurseDelegationMapper() {}

    public static ChargeNurseDelegationDtos.Response toResponse(ChargeNurseDelegation d) {
        if (d == null) return null;
        User delegating = d.getDelegatingUser();
        User delegate   = d.getDelegate();
        User revokedBy  = d.getRevokedBy();
        return ChargeNurseDelegationDtos.Response.builder()
                .id(d.getId())
                .hospitalId(d.getHospital() != null ? d.getHospital().getId() : null)
                .delegatingUserId(delegating != null ? delegating.getId() : null)
                .delegatingUserName(fullName(delegating))
                .delegateUserId(delegate != null ? delegate.getId() : null)
                .delegateUserName(fullName(delegate))
                .startsAt(d.getStartsAt())
                .endsAt(d.getEndsAt())
                .reason(d.getReason())
                .revokedAt(d.getRevokedAt())
                .revokedById(revokedBy != null ? revokedBy.getId() : null)
                .revokedByName(fullName(revokedBy))
                .revocationReason(d.getRevocationReason())
                .currentlyActive(d.isCurrentlyActive(Instant.now()))
                .build();
    }

    private static String fullName(User u) {
        if (u == null) return null;
        return (u.getFirstName() == null ? "" : u.getFirstName())
                + " "
                + (u.getLastName() == null ? "" : u.getLastName());
    }
}
