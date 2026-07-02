package com.smartTriage.smartTriage_server.module.lab.service;

import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.lab.dto.LabReportDocumentResponse;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.entity.LabReportDocument;
import com.smartTriage.smartTriage_server.module.clinical.entity.Investigation;
import com.smartTriage.smartTriage_server.module.clinical.repository.InvestigationRepository;
import com.smartTriage.smartTriage_server.module.lab.repository.LabOrderRepository;
import com.smartTriage.smartTriage_server.module.lab.repository.LabReportDocumentRepository;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Attach / list / download / remove full lab report documents on a lab order —
 * the interim standard alongside structured result entry until the automated
 * results pipeline exists. Files are size-capped and content-type allow-listed,
 * stored in-database, and the uploader is server-attributed (non-repudiation).
 *
 * <p>Callers are authorised at the controller (canAccessLabOrder / role).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LabReportDocumentService {

    /** Hard ceiling on a single stored report — matches the multipart limit. */
    static final long MAX_SIZE_BYTES = 15L * 1024 * 1024; // 15 MB

    /** Report formats a lab produces: PDF + common scanned-image types. */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "image/png", "image/jpeg", "image/jpg", "image/tiff");

    private final LabReportDocumentRepository documentRepository;
    private final LabOrderRepository labOrderRepository;
    private final InvestigationRepository investigationRepository;

    // ── Lab-order documents ──
    public List<LabReportDocumentResponse> listForOrder(UUID labOrderId) {
        return documentRepository.findMetadataByLabOrder(labOrderId);
    }

    /**
     * Full entity (incl. bytes) for streaming a lab-order document's download.
     * Verifies the document belongs to {@code labOrderId} — the endpoint is
     * authorised on the ORDER, so a mismatched (order, document) pair must not
     * resolve (defense-in-depth against IDOR through a swapped id).
     */
    public LabReportDocument getForDownload(UUID labOrderId, UUID documentId) {
        return requireOwned(documentId, labOrderId, null);
    }

    @Transactional
    public LabReportDocumentResponse upload(UUID labOrderId, MultipartFile file, String description) {
        LabOrder order = labOrderRepository.findById(labOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("LabOrder", "id", labOrderId));
        UUID visitId = (order.getVisit() != null) ? order.getVisit().getId() : null;
        if (visitId == null) {
            throw new ClinicalBusinessException("Lab order is not linked to a visit.");
        }
        return persist(visitId, labOrderId, null, file, description, "lab order " + labOrderId);
    }

    @Transactional
    public void softDelete(UUID labOrderId, UUID documentId) {
        LabReportDocument doc = requireOwned(documentId, labOrderId, null);
        doc.softDelete();
        documentRepository.save(doc);
        log.info("[lab-doc] Soft-deleted report document {} (order {})", documentId, labOrderId);
    }

    // ── Imaging/ECG investigation documents ──
    public List<LabReportDocumentResponse> listForInvestigation(UUID investigationId) {
        return documentRepository.findMetadataByInvestigation(investigationId);
    }

    public LabReportDocument getForDownloadByInvestigation(UUID investigationId, UUID documentId) {
        return requireOwned(documentId, null, investigationId);
    }

    @Transactional
    public LabReportDocumentResponse uploadForInvestigation(UUID investigationId, MultipartFile file, String description) {
        Investigation inv = investigationRepository.findByIdAndIsActiveTrue(investigationId)
                .orElseThrow(() -> new ResourceNotFoundException("Investigation", "id", investigationId));
        UUID visitId = (inv.getVisit() != null) ? inv.getVisit().getId() : null;
        if (visitId == null) {
            throw new ClinicalBusinessException("Investigation is not linked to a visit.");
        }
        return persist(visitId, null, investigationId, file, description, "investigation " + investigationId);
    }

    @Transactional
    public void softDeleteByInvestigation(UUID investigationId, UUID documentId) {
        LabReportDocument doc = requireOwned(documentId, null, investigationId);
        doc.softDelete();
        documentRepository.save(doc);
        log.info("[lab-doc] Soft-deleted report document {} (investigation {})", documentId, investigationId);
    }

    // ── Shared core ──

    /**
     * Load an active document and verify it belongs to the expected owner (lab
     * order OR investigation, whichever is non-null). A mismatch resolves as
     * not-found — the endpoint is authorised on the owner, so a swapped id must
     * not leak another owner's document (IDOR defense).
     */
    private LabReportDocument requireOwned(UUID documentId, UUID labOrderId, UUID investigationId) {
        LabReportDocument doc = documentRepository.findByIdAndIsActiveTrue(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("LabReportDocument", "id", documentId));
        boolean ok = (labOrderId != null && labOrderId.equals(doc.getLabOrderId()))
                || (investigationId != null && investigationId.equals(doc.getInvestigationId()));
        if (!ok) {
            throw new ResourceNotFoundException("LabReportDocument", "id", documentId);
        }
        return doc;
    }

    private LabReportDocumentResponse persist(UUID visitId, UUID labOrderId, UUID investigationId,
                                              MultipartFile file, String description, String ownerLabel) {
        if (file == null || file.isEmpty()) {
            throw new ClinicalBusinessException("No file was uploaded.");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new ClinicalBusinessException(
                    "File is too large (max " + (MAX_SIZE_BYTES / (1024 * 1024)) + " MB).");
        }
        String contentType = file.getContentType() != null ? file.getContentType().toLowerCase() : "";
        if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
            throw new ClinicalBusinessException(
                    "Unsupported file type. Attach a PDF or an image (PNG/JPEG/TIFF).");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new ClinicalBusinessException("Could not read the uploaded file.");
        }

        User actor = currentUser();
        LabReportDocument doc = LabReportDocument.builder()
                .labOrderId(labOrderId)
                .investigationId(investigationId)
                .visitId(visitId)
                .fileName(sanitizeFileName(file.getOriginalFilename()))
                .contentType(contentType)
                .sizeBytes(bytes.length)
                .content(bytes)
                .uploadedById(actor != null ? actor.getId() : null)
                .uploadedByName(formatUserName(actor))
                .description(description != null && !description.isBlank() ? description.trim() : null)
                .build();
        doc = documentRepository.save(doc);

        log.info("[lab-doc] Attached report '{}' ({} bytes, {}) to {} by {}",
                doc.getFileName(), doc.getSizeBytes(), doc.getContentType(), ownerLabel, doc.getUploadedByName());

        return LabReportDocumentResponse.builder()
                .id(doc.getId())
                .labOrderId(doc.getLabOrderId())
                .investigationId(doc.getInvestigationId())
                .fileName(doc.getFileName())
                .contentType(doc.getContentType())
                .sizeBytes(doc.getSizeBytes())
                .uploadedByName(doc.getUploadedByName())
                .description(doc.getDescription())
                .uploadedAt(doc.getCreatedAt())
                .build();
    }

    private User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.getPrincipal() instanceof User u) ? u : null;
    }

    private String formatUserName(User u) {
        if (u == null) return null;
        String name = ((u.getFirstName() != null ? u.getFirstName().trim() : "")
                + " " + (u.getLastName() != null ? u.getLastName().trim() : "")).trim();
        return !name.isEmpty() ? name : u.getEmail();
    }

    /** Strip any path components a client filename might carry; keep it bounded. */
    private String sanitizeFileName(String raw) {
        String name = (raw == null || raw.isBlank()) ? "report" : raw;
        name = name.replaceAll(".*[/\\\\]", "").trim();
        if (name.isEmpty()) name = "report";
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }
}
