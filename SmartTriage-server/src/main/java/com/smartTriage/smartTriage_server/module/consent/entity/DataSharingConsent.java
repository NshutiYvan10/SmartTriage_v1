package com.smartTriage.smartTriage_server.module.consent.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.ConsentGrantor;
import com.smartTriage.smartTriage_server.common.enums.DataSharingConsentStatus;
import com.smartTriage.smartTriage_server.common.enums.DataSharingScope;
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
 * Cross-hospital DATA-SHARING consent (Phase 2) — the patient's opt-in (or refusal) to share
 * their deep clinical record across SmartTriage hospitals. Keyed on the shared {@link PersonIdentity}
 * (NOT a visit — this is a person-level directive, unlike the visit-scoped InformedConsent).
 *
 * Only a live {@code GRANTED} row is "effective"; a DB partial-unique index enforces at most one
 * effective grant per person. The obtaining/withdrawing actor is always the authenticated principal.
 */
@Entity
@Table(name = "data_sharing_consents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataSharingConsent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "person_identity_id", nullable = false)
    private PersonIdentity personIdentity;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private DataSharingConsentStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope", nullable = false, length = 30)
    @Builder.Default
    private DataSharingScope scope = DataSharingScope.FULL_RECORD;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_grantor", nullable = false, length = 40)
    private ConsentGrantor consentGrantor;

    @Column(name = "grantor_name")
    private String grantorName;

    @Column(name = "grantor_relationship")
    private String grantorRelationship;

    @Column(name = "obtained_by_user_id")
    private UUID obtainedByUserId;

    @Column(name = "obtained_by_name", nullable = false)
    private String obtainedByName;

    @Column(name = "obtained_by_role", length = 30)
    private String obtainedByRole;

    @Column(name = "obtained_by_license_number", length = 50)
    private String obtainedByLicenseNumber;

    @Column(name = "obtained_at", nullable = false)
    private Instant obtainedAt;

    @Column(name = "withdrawn_by_user_id")
    private UUID withdrawnByUserId;

    @Column(name = "withdrawn_by_name")
    private String withdrawnByName;

    @Column(name = "withdrawn_at")
    private Instant withdrawnAt;

    @Column(name = "withdrawal_reason", columnDefinition = "TEXT")
    private String withdrawalReason;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;
}
