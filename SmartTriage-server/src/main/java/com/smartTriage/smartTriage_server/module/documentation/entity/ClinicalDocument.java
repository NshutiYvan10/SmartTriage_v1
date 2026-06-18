package com.smartTriage.smartTriage_server.module.documentation.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/**
 * ClinicalDocument — a legally compliant clinical document for an ED visit.
 *
 * Key legal requirements:
 *   - Once signed, content CANNOT be modified (immutable)
 *   - Amendments create NEW linked documents (preserving audit trail)
 *   - Co-signing supports supervised clinicians (interns)
 *   - Vitals snapshot captured at documentation time
 *
 * This extends the basic ClinicalNote entity into a full legal documentation system.
 */
@Entity
@Table(name = "clinical_documents", indexes = {
        @Index(name = "idx_clin_doc_visit", columnList = "visit_id"),
        @Index(name = "idx_clin_doc_type", columnList = "document_type"),
        @Index(name = "idx_clin_doc_signed", columnList = "is_signed"),
        @Index(name = "idx_clin_doc_author", columnList = "author_name"),
        @Index(name = "idx_clin_doc_active", columnList = "is_active"),
        @Index(name = "idx_clin_doc_original", columnList = "original_document_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClinicalDocument extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "visit_id", nullable = false)
    private Visit visit;

    @Enumerated(EnumType.STRING)
    @Column(name = "document_type", nullable = false, length = 30)
    private ClinicalDocumentType documentType;

    @Column(name = "title", nullable = false)
    private String title;

    @Column(name = "content", nullable = false, columnDefinition = "TEXT")
    private String content;

    // ====================================================================
    // LEGAL COMPLIANCE
    // ====================================================================

    /**
     * Author identity is ALWAYS derived from the authenticated principal — never
     * from a client-supplied request body. {@code authorUserId} is the FK to the
     * signing/authoring {@link com.smartTriage.smartTriage_server.module.user.entity.User};
     * the name/role/license fields are immutable snapshots taken from that user's
     * record at authoring/signing time (so the displayed signature stays stable
     * even if the user's profile later changes).
     */
    @Column(name = "author_user_id")
    private UUID authorUserId;

    @Column(name = "author_name", nullable = false)
    private String authorName;

    @Column(name = "author_role")
    private String authorRole;

    @Column(name = "author_license_number", length = 50)
    private String authorLicenseNumber;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "is_signed", nullable = false)
    @Builder.Default
    private boolean isSigned = false;

    // Co-signer identity — also derived from the authenticated principal.
    @Column(name = "co_signed_by_user_id")
    private UUID coSignedByUserId;

    @Column(name = "co_signed_by_name")
    private String coSignedByName;

    @Column(name = "co_signed_by_role")
    private String coSignedByRole;

    @Column(name = "co_signed_by_license_number", length = 50)
    private String coSignedByLicenseNumber;

    @Column(name = "co_signed_at")
    private Instant coSignedAt;

    // ====================================================================
    // VITALS SNAPSHOT AT DOCUMENTATION TIME
    // ====================================================================

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "vital_signs_id")
    private VitalSigns vitalSigns;

    // ====================================================================
    // AMENDMENT TRACKING
    // ====================================================================

    @Column(name = "is_amendment", nullable = false)
    @Builder.Default
    private boolean isAmendment = false;

    @Column(name = "amendment_reason", columnDefinition = "TEXT")
    private String amendmentReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "original_document_id")
    private ClinicalDocument originalDocument;

    @Column(name = "amended_at")
    private Instant amendedAt;

    // ====================================================================
    // TEMPLATE
    // ====================================================================

    @Column(name = "template_used")
    private String templateUsed;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    // ====================================================================
    // SIGNED-DOCUMENT IMMUTABILITY GUARD (legal record)
    // ====================================================================
    // Once a document is signed it is a legal record: its content and identity
    // must never change and it must never be (soft- or hard-) deleted. The ONLY
    // permitted post-sign mutation is adding a single co-signature. Corrections
    // go through amendDocument(), which writes a NEW linked addendum and leaves
    // the original untouched. Enforced here at the persistence layer — defence in
    // depth alongside the V85 DB trigger — so that NO code path (not merely the
    // absence of an edit endpoint) can silently alter or remove a signed record.

    @Transient
    private boolean wasSignedOnLoad;
    @Transient
    private Object[] protectedSnapshot;

    @PostLoad
    void captureImmutabilitySnapshot() {
        this.wasSignedOnLoad = this.isSigned;
        this.protectedSnapshot = protectedState();
    }

    @PreUpdate
    void guardSignedImmutability() {
        // Allow the unsigned -> signed transition and the one-time co-signature;
        // block any change to a document that was ALREADY signed when loaded.
        if (wasSignedOnLoad && !Arrays.equals(protectedSnapshot, protectedState())) {
            throw new ClinicalBusinessException(
                    "Signed clinical document " + getId() + " is immutable: its content cannot be "
                    + "edited or deleted. Add a co-signature, or create an amendment for corrections.");
        }
    }

    @PreRemove
    void guardSignedDeletion() {
        if (this.isSigned) {
            throw new ClinicalBusinessException(
                    "Signed clinical document " + getId() + " cannot be deleted. "
                    + "Create an amendment instead — the legal record must be preserved.");
        }
    }

    /**
     * The fields that are frozen once a document is signed. Co-signature fields and
     * framework audit columns (updatedAt/version/lastModifiedBy) are deliberately
     * excluded — adding a co-signature is the one permitted post-sign change.
     */
    private Object[] protectedState() {
        return new Object[] {
                documentType, title, content, notes,
                authorUserId, authorName, authorRole, authorLicenseNumber,
                signedAt, isSigned, isActive(), isAmendment, amendmentReason, templateUsed,
                visit != null ? visit.getId() : null,
                vitalSigns != null ? vitalSigns.getId() : null,
                originalDocument != null ? originalDocument.getId() : null,
        };
    }
}
