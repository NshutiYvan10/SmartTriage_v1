package com.smartTriage.smartTriage_server.module.lab.service;

import com.smartTriage.smartTriage_server.common.exception.ClinicalBusinessException;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.lab.dto.LabReportDocumentResponse;
import com.smartTriage.smartTriage_server.module.lab.entity.LabOrder;
import com.smartTriage.smartTriage_server.module.lab.entity.LabReportDocument;
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

    public List<LabReportDocumentResponse> listForOrder(UUID labOrderId) {
        return documentRepository.findMetadataByLabOrder(labOrderId);
    }

    /**
     * Full entity (incl. bytes) for streaming a download. Verifies the document
     * belongs to {@code labOrderId} — the endpoint is authorised on the ORDER,
     * so a mismatched (order, document) pair must not resolve (defense-in-depth
     * against IDOR through a swapped id).
     */
    public LabReportDocument getForDownload(UUID labOrderId, UUID documentId) {
        LabReportDocument doc = documentRepository.findByIdAndIsActiveTrue(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("LabReportDocument", "id", documentId));
        if (!doc.getLabOrderId().equals(labOrderId)) {
            throw new ResourceNotFoundException("LabReportDocument", "id", documentId);
        }
        return doc;
    }

    @Transactional
    public LabReportDocumentResponse upload(UUID labOrderId, MultipartFile file, String description) {
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

        LabOrder order = labOrderRepository.findById(labOrderId)
                .orElseThrow(() -> new ResourceNotFoundException("LabOrder", "id", labOrderId));
        UUID visitId = (order.getVisit() != null) ? order.getVisit().getId() : null;
        if (visitId == null) {
            throw new ClinicalBusinessException("Lab order is not linked to a visit.");
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

        log.info("[lab-doc] Attached report '{}' ({} bytes, {}) to lab order {} by {}",
                doc.getFileName(), doc.getSizeBytes(), doc.getContentType(), labOrderId, doc.getUploadedByName());

        return LabReportDocumentResponse.builder()
                .id(doc.getId())
                .labOrderId(doc.getLabOrderId())
                .fileName(doc.getFileName())
                .contentType(doc.getContentType())
                .sizeBytes(doc.getSizeBytes())
                .uploadedByName(doc.getUploadedByName())
                .description(doc.getDescription())
                .uploadedAt(doc.getCreatedAt())
                .build();
    }

    @Transactional
    public void softDelete(UUID labOrderId, UUID documentId) {
        LabReportDocument doc = documentRepository.findByIdAndIsActiveTrue(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("LabReportDocument", "id", documentId));
        if (!doc.getLabOrderId().equals(labOrderId)) {
            throw new ResourceNotFoundException("LabReportDocument", "id", documentId);
        }
        doc.softDelete();
        documentRepository.save(doc);
        log.info("[lab-doc] Soft-deleted report document {} (order {})", documentId, labOrderId);
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
