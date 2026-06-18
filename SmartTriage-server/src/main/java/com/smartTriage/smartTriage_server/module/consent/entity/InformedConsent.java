package com.smartTriage.smartTriage_server.module.consent.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.ConsentGrantor;
import com.smartTriage.smartTriage_server.common.enums.ConsentStatus;
import com.smartTriage.smartTriage_server.common.enums.ConsentType;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * InformedConsent — a structured, legally-meaningful record that informed consent
 * was obtained (or refused, or later withdrawn) for a specific intervention.
 *
 * <p>Unlike a free-text document, this captures the discrete elements a valid
 * consent requires: what intervention, what was disclosed (risks / benefits /
 * alternatives), whether the patient's questions were answered, whether an
 * interpreter was used, WHO consented (patient or a named proxy with their
 * relationship), and the authenticated clinician who obtained it.
 *
 * <p>Authorship is ALWAYS derived from the authenticated principal — the
 * obtaining clinician's id/name/role/license are snapshotted server-side, never
 * supplied by the client (the same rule the documentation module now enforces).
 */
@Entity
@Table(name = "informed_consents", indexes = {
        @Index(name = "idx_consent_visit", columnList = "visit_id"),
        @Index(name = "idx_consent_type", columnList = "consent_type"),
        @Index(name = "idx_consent_status", columnList = "status"),
        @Index(name = "idx_consent_obtained_by", columnList = "obtained_by_user_id"),
        @Index(name = "idx_consent_active", columnList = "is_active")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InformedConsent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_type", nullable = false, length = 30)
    private ConsentType consentType;

    @Column(name = "procedure_name", nullable = false)
    private String procedureName;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    // ====================================================================
    // DISCLOSURE — the elements of a valid informed consent
    // ====================================================================

    @Column(name = "risks_explained", columnDefinition = "TEXT")
    private String risksExplained;

    @Column(name = "benefits_explained", columnDefinition = "TEXT")
    private String benefitsExplained;

    @Column(name = "alternatives_explained", columnDefinition = "TEXT")
    private String alternativesExplained;

    @Column(name = "questions_answered", nullable = false)
    @Builder.Default
    private boolean questionsAnswered = false;

    @Column(name = "interpreter_used", nullable = false)
    @Builder.Default
    private boolean interpreterUsed = false;

    @Column(name = "interpreter_name")
    private String interpreterName;

    @Column(name = "language")
    private String language;

    // ====================================================================
    // WHO CONSENTED
    // ====================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "consent_grantor", nullable = false, length = 40)
    private ConsentGrantor consentGrantor;

    /** Name of the person who gave/refused consent (patient or named proxy). */
    @Column(name = "grantor_name")
    private String grantorName;

    /** Proxy's relationship to the patient (e.g. "Mother", "Husband"), if not the patient. */
    @Column(name = "grantor_relationship")
    private String grantorRelationship;

    @Column(name = "witness_name")
    private String witnessName;

    // ====================================================================
    // STATUS + AUTHENTICATED OBTAINING CLINICIAN
    // ====================================================================

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ConsentStatus status;

    /** The authenticated clinician who obtained/documented the consent. */
    @Column(name = "obtained_by_user_id")
    private UUID obtainedByUserId;

    @Column(name = "obtained_by_name", nullable = false)
    private String obtainedByName;

    @Column(name = "obtained_by_role")
    private String obtainedByRole;

    @Column(name = "obtained_by_license_number", length = 50)
    private String obtainedByLicenseNumber;

    @Column(name = "obtained_at", nullable = false)
    private Instant obtainedAt;

    // ====================================================================
    // WITHDRAWAL (consent can be revoked; original obtaining record preserved)
    // ====================================================================

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
