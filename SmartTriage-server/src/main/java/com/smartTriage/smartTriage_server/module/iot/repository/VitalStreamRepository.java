package com.smartTriage.smartTriage_server.module.iot.repository;

import com.smartTriage.smartTriage_server.module.iot.entity.VitalStream;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface VitalStreamRepository extends JpaRepository<VitalStream, UUID> {

    /** Most recent reading for a visit — used for real-time display */
    Optional<VitalStream> findFirstByVisitIdAndIsValidatedTrueAndIsActiveTrueOrderByCapturedAtDesc(
            UUID visitId);

    /** Recent validated readings in a time window — for trend analysis */
    @Query("SELECT vs FROM VitalStream vs WHERE vs.visit.id = :visitId " +
            "AND vs.isActive = true AND vs.isValidated = true " +
            "AND vs.capturedAt BETWEEN :from AND :to ORDER BY vs.capturedAt ASC")
    List<VitalStream> findValidatedInTimeRange(
            @Param("visitId") UUID visitId,
            @Param("from") Instant from,
            @Param("to") Instant to);

    /** Last N validated readings for a visit — for deterioration analysis */
    @Query("SELECT vs FROM VitalStream vs WHERE vs.visit.id = :visitId " +
            "AND vs.isActive = true AND vs.isValidated = true " +
            "ORDER BY vs.capturedAt DESC")
    List<VitalStream> findRecentValidated(@Param("visitId") UUID visitId, Pageable pageable);

    /** All readings (including invalid) for a session — for audit */
    Page<VitalStream> findBySessionIdAndIsActiveTrueOrderByCapturedAtDesc(
            UUID sessionId, Pageable pageable);

    /** Count readings for a session */
    long countBySessionIdAndIsActiveTrue(UUID sessionId);

    /** Count readings in a time range for a visit */
    long countByVisitIdAndIsActiveTrueAndCapturedAtBetween(
            UUID visitId, Instant from, Instant to);

    /** Get the latest timestamp for gap detection */
    @Query("SELECT MAX(vs.sequenceNumber) FROM VitalStream vs WHERE vs.deviceId = :deviceId " +
            "AND vs.isActive = true")
    Optional<Long> findMaxSequenceNumber(@Param("deviceId") String deviceId);

    /** Paginated stream history for a visit */
    Page<VitalStream> findByVisitIdAndIsActiveTrueOrderByCapturedAtDesc(
            UUID visitId, Pageable pageable);
}
