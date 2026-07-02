package com.smartTriage.smartTriage_server.module.lab.repository;

import com.smartTriage.smartTriage_server.module.lab.dto.LabReportDocumentResponse;
import com.smartTriage.smartTriage_server.module.lab.entity.LabReportDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface LabReportDocumentRepository extends JpaRepository<LabReportDocument, UUID> {

    /**
     * Metadata for a lab order's attached documents, newest first. Uses a
     * constructor projection so the {@code content} bytes are NEVER loaded when
     * merely listing (they stream only from the download endpoint).
     */
    @Query("SELECT new com.smartTriage.smartTriage_server.module.lab.dto.LabReportDocumentResponse("
            + "d.id, d.labOrderId, d.investigationId, d.fileName, d.contentType, d.sizeBytes, d.uploadedByName, d.description, d.createdAt) "
            + "FROM LabReportDocument d WHERE d.labOrderId = :labOrderId AND d.isActive = true "
            + "ORDER BY d.createdAt DESC")
    List<LabReportDocumentResponse> findMetadataByLabOrder(@Param("labOrderId") UUID labOrderId);

    @Query("SELECT new com.smartTriage.smartTriage_server.module.lab.dto.LabReportDocumentResponse("
            + "d.id, d.labOrderId, d.investigationId, d.fileName, d.contentType, d.sizeBytes, d.uploadedByName, d.description, d.createdAt) "
            + "FROM LabReportDocument d WHERE d.investigationId = :investigationId AND d.isActive = true "
            + "ORDER BY d.createdAt DESC")
    List<LabReportDocumentResponse> findMetadataByInvestigation(@Param("investigationId") UUID investigationId);

    Optional<LabReportDocument> findByIdAndIsActiveTrue(UUID id);
}
