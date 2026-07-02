package com.smartTriage.smartTriage_server.module.lab.entity;

import com.smartTriage.smartTriage_server.common.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * A full lab report document (scanned/printed PDF or image) attached to a lab
 * order — the interim standard for fulfilling an order until the structured
 * results pipeline exists (future API integration).
 *
 * <p>The file is stored in-database as {@code bytea}. NB: the {@code content}
 * field is a plain {@code byte[]} (NOT {@code @Lob}) so Hibernate 6 maps it to
 * {@code bytea} — {@code @Lob byte[]} would map to a Postgres large object
 * ({@code oid}) and mismatch the migration.
 */
@Entity
@Table(name = "lab_report_document", indexes = {
        @Index(name = "idx_lab_report_document_order", columnList = "lab_order_id"),
        @Index(name = "idx_lab_report_document_visit", columnList = "visit_id")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LabReportDocument extends BaseEntity {

    /** The lab order this report belongs to (FK enforced in the DB). */
    @Column(name = "lab_order_id", nullable = false)
    private UUID labOrderId;

    /** Denormalised visit id for scoped queries/authz without dereferencing the order. */
    @Column(name = "visit_id", nullable = false)
    private UUID visitId;

    @Column(name = "file_name", nullable = false, length = 255)
    private String fileName;

    @Column(name = "content_type", nullable = false, length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    /** Raw file bytes. Plain byte[] → bytea (see class note); loaded only on download. */
    @Column(name = "content", nullable = false, columnDefinition = "bytea")
    private byte[] content;

    /** Server-attributed uploader (principal) — non-repudiation, never client text. */
    @Column(name = "uploaded_by_id")
    private UUID uploadedById;

    @Column(name = "uploaded_by_name", length = 255)
    private String uploadedByName;

    @Column(name = "description", length = 500)
    private String description;
}
