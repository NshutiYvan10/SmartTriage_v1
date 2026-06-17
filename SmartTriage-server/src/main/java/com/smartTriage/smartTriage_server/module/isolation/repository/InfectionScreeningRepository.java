package com.smartTriage.smartTriage_server.module.isolation.repository;

import com.smartTriage.smartTriage_server.common.enums.NotifiableDisease;
import com.smartTriage.smartTriage_server.module.isolation.entity.InfectionScreening;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InfectionScreeningRepository extends JpaRepository<InfectionScreening, UUID> {

    Optional<InfectionScreening> findByIdAndIsActiveTrue(UUID id);

    List<InfectionScreening> findByVisitIdAndIsActiveTrueOrderByScreenedAtDesc(UUID visitId);

    /** Projection for hospital-scope authz — the screening's visit id. */
    @Query("SELECT s.visit.id FROM InfectionScreening s WHERE s.id = :id")
    Optional<UUID> findVisitIdById(@Param("id") UUID id);

    /**
     * Open isolations for a visit — isolation required and not yet ended. Used to
     * supersede/close a prior active isolation when a lower-risk re-screen clears it,
     * so a single visit never carries two conflicting active precautions.
     */
    @Query("SELECT s FROM InfectionScreening s WHERE s.visit.id = :visitId AND s.isActive = true " +
            "AND s.isolationType IS NOT NULL AND s.isolationEndedAt IS NULL ORDER BY s.screenedAt DESC")
    List<InfectionScreening> findOpenIsolationsForVisit(@Param("visitId") UUID visitId);

    /**
     * Flagged isolations awaiting room placement — isolation required, not yet roomed,
     * not ended, still active. The placement monitor escalates those past their due time.
     */
    @Query("SELECT s FROM InfectionScreening s WHERE s.isActive = true AND s.isolationType IS NOT NULL " +
            "AND s.isolationEndedAt IS NULL AND s.isolationRoomAssigned IS NULL")
    List<InfectionScreening> findAwaitingPlacement();

    /**
     * Active isolations for a hospital — isolation started but not ended.
     */
    @Query("SELECT s FROM InfectionScreening s JOIN s.visit v WHERE v.hospital.id = :hospitalId " +
            "AND s.isActive = true AND s.isolationType IS NOT NULL " +
            "AND s.isolationStartedAt IS NOT NULL AND s.isolationEndedAt IS NULL " +
            "ORDER BY s.screenedAt DESC")
    List<InfectionScreening> findActiveIsolationsByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Notifiable disease cases for a hospital — for public health dashboard.
     */
    @Query("SELECT s FROM InfectionScreening s JOIN s.visit v WHERE v.hospital.id = :hospitalId " +
            "AND s.isActive = true AND s.notifiableDisease IS NOT NULL " +
            "ORDER BY s.screenedAt DESC")
    List<InfectionScreening> findNotifiableDiseasesByHospital(@Param("hospitalId") UUID hospitalId);

    /**
     * Notifiable disease cases pending public health notification.
     */
    @Query("SELECT s FROM InfectionScreening s JOIN s.visit v WHERE v.hospital.id = :hospitalId " +
            "AND s.isActive = true AND s.notifiableDisease IS NOT NULL " +
            "AND s.publicHealthNotifiedAt IS NULL " +
            "ORDER BY s.screenedAt ASC")
    List<InfectionScreening> findPendingNotifications(@Param("hospitalId") UUID hospitalId);
}
