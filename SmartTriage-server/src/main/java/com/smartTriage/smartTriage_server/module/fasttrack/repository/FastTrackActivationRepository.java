package com.smartTriage.smartTriage_server.module.fasttrack.repository;

import com.smartTriage.smartTriage_server.common.enums.FastTrackStatus;
import com.smartTriage.smartTriage_server.module.fasttrack.entity.FastTrackActivation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FastTrackActivationRepository extends JpaRepository<FastTrackActivation, UUID> {

    Optional<FastTrackActivation> findByIdAndIsActiveTrue(UUID id);

    Optional<FastTrackActivation> findFirstByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(UUID visitId);

    List<FastTrackActivation> findByVisitIdAndIsActiveTrueOrderByActivatedAtDesc(UUID visitId);

    /**
     * Active fast-tracks for a hospital — not yet completed or cancelled.
     */
    @Query("SELECT f FROM FastTrackActivation f JOIN f.visit v WHERE v.hospital.id = :hospitalId " +
            "AND f.isActive = true AND f.status NOT IN ('COMPLETED', 'CANCELLED') " +
            "ORDER BY f.activatedAt DESC")
    List<FastTrackActivation> findActiveFastTracksByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Check for existing active fast-track for a visit — prevents duplicate activations.
     */
    boolean existsByVisitIdAndStatusNotInAndIsActiveTrue(UUID visitId, List<FastTrackStatus> terminalStatuses);
}
