package com.smartTriage.smartTriage_server.module.prediction.repository;

import com.smartTriage.smartTriage_server.module.prediction.entity.SurgePrediction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface SurgePredictionRepository extends JpaRepository<SurgePrediction, UUID> {

    Optional<SurgePrediction> findByIdAndIsActiveTrue(UUID id);

    @Query("SELECT s FROM SurgePrediction s WHERE s.hospital.id = :hospitalId " +
            "AND s.isActive = true ORDER BY s.predictedAt DESC LIMIT 1")
    Optional<SurgePrediction> findLatestByHospital(@Param("hospitalId") UUID hospitalId);

    @Query("SELECT s FROM SurgePrediction s WHERE s.hospital.id = :hospitalId " +
            "AND s.isActive = true ORDER BY s.predictedAt DESC")
    Page<SurgePrediction> findByHospitalOrderByPredictedAtDesc(
            @Param("hospitalId") UUID hospitalId, Pageable pageable);
}
