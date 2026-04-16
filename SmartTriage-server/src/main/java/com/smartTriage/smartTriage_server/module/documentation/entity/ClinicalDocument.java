package com.smartTriage.smartTriage_server.module.documentation.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

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

    @Column(name = "author_name", nullable = false)
    private String authorName;

    @Column(name = "author_role")
    private String authorRole;

    @Column(name = "author_license_number")
    private String authorLicenseNumber;

    @Column(name = "signed_at")
    private Instant signedAt;

    @Column(name = "is_signed", nullable = false)
    @Builder.Default
    private boolean isSigned = false;

    @Column(name = "co_signed_by_name")
    private String coSignedByName;

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
}
