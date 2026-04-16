package com.smartTriage.smartTriage_server.module.clinical.repository;

import com.smartTriage.smartTriage_server.common.enums.DiagnosisType;
import com.smartTriage.smartTriage_server.module.clinical.entity.Diagnosis;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DiagnosisRepository extends JpaRepository<Diagnosis, UUID> {

    Page<Diagnosis> findByVisitIdAndIsActiveTrueOrderByDiagnosedAtDesc(
            UUID visitId, Pageable pageable);

    List<Diagnosis> findByVisitIdAndIsActiveTrueOrderByDiagnosedAtAsc(UUID visitId);

    List<Diagnosis> findByVisitIdAndDiagnosisTypeAndIsActiveTrueOrderByDiagnosedAtDesc(
            UUID visitId, DiagnosisType diagnosisType);

    Optional<Diagnosis> findByIdAndIsActiveTrue(UUID id);

    Optional<Diagnosis> findByVisitIdAndIsPrimaryTrueAndIsActiveTrue(UUID visitId);
}
