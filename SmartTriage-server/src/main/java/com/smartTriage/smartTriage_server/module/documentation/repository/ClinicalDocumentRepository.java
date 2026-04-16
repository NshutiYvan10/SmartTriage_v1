package com.smartTriage.smartTriage_server.module.documentation.repository;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.module.documentation.entity.ClinicalDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClinicalDocumentRepository extends JpaRepository<ClinicalDocument, UUID> {

    Optional<ClinicalDocument> findByIdAndIsActiveTrue(UUID id);

    Page<ClinicalDocument> findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(UUID visitId, Pageable pageable);

    List<ClinicalDocument> findByVisitIdAndIsActiveTrueOrderByCreatedAtAsc(UUID visitId);

    List<ClinicalDocument> findByVisitIdAndDocumentTypeAndIsActiveTrueOrderByCreatedAtDesc(
            UUID visitId, ClinicalDocumentType documentType);

    List<ClinicalDocument> findByOriginalDocumentIdAndIsActiveTrueOrderByAmendedAtAsc(UUID originalDocumentId);

    Optional<ClinicalDocument> findFirstByVisitIdAndDocumentTypeAndIsActiveTrueOrderByCreatedAtDesc(
            UUID visitId, ClinicalDocumentType documentType);
}
