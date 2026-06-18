package com.smartTriage.smartTriage_server.module.documentation.repository;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.module.documentation.entity.ClinicalDocument;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ClinicalDocumentRepository extends JpaRepository<ClinicalDocument, UUID> {

    Optional<ClinicalDocument> findByIdAndIsActiveTrue(UUID id);

    /**
     * Projection used by hospital-scope authorization: a document's visit id,
     * resolved without loading the whole row, so the GET-by-id endpoint can be
     * gated by {@code canAccessVisit}. Resolves regardless of is_active so an
     * unknown id and a soft-deleted id both deny without leaking existence.
     */
    @Query("select d.visit.id from ClinicalDocument d where d.id = :id")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);

    Page<ClinicalDocument> findByVisitIdAndIsActiveTrueOrderByCreatedAtDesc(UUID visitId, Pageable pageable);

    List<ClinicalDocument> findByVisitIdAndIsActiveTrueOrderByCreatedAtAsc(UUID visitId);

    List<ClinicalDocument> findByVisitIdAndDocumentTypeAndIsActiveTrueOrderByCreatedAtDesc(
            UUID visitId, ClinicalDocumentType documentType);

    List<ClinicalDocument> findByOriginalDocumentIdAndIsActiveTrueOrderByAmendedAtAsc(UUID originalDocumentId);

    Optional<ClinicalDocument> findFirstByVisitIdAndDocumentTypeAndIsActiveTrueOrderByCreatedAtDesc(
            UUID visitId, ClinicalDocumentType documentType);

    /** Does an active document of this type exist for the visit? Used to require a
     *  real discharge summary before a discharge disposition is recorded. */
    boolean existsByVisitIdAndDocumentTypeAndIsActiveTrue(UUID visitId, ClinicalDocumentType documentType);
}
