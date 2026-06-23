package com.smartTriage.smartTriage_server.module.consent.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.module.patient.entity.PersonIdentity;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable forensic record of an emergency BREAK-THE-GLASS access to a patient's cross-hospital
 * deep record without consent (Phase 2). Captures who, when, why, and what the consent state was
 * at override time — so governance can distinguish "no consent on file" from "overrode an explicit
 * refusal". One row per emergency access (no stateful override session).
 */
@Entity
@Table(name = "break_the_glass_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BreakTheGlassEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_identity_id", nullable = false)
    private PersonIdentity personIdentity;

    @Column(name = "actor_user_id")
    private UUID actorUserId;

    @Column(name = "actor_name", nullable = false)
    private String actorName;

    @Column(name = "actor_role", length = 30)
    private String actorRole;

    @Column(name = "actor_hospital_id")
    private UUID actorHospitalId;

    @Column(name = "reason", nullable = false, columnDefinition = "TEXT")
    private String reason;

    /** Consent state at override time: NONE | DENIED | WITHDRAWN. */
    @Column(name = "prior_consent_state", length = 20)
    private String priorConsentState;

    @Column(name = "accessed_at", nullable = false)
    private Instant accessedAt;
}
