package com.smartTriage.smartTriage_server.module.iot.repository;

import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DeviceSessionRepository extends JpaRepository<DeviceSession, UUID> {

    Optional<DeviceSession> findByIdAndIsActiveTrue(UUID id);

    /** Get the current active session for a device */
    Optional<DeviceSession> findByDeviceIdAndSessionActiveTrueAndIsActiveTrue(UUID deviceId);

    /** Get the current active session for a visit */
    Optional<DeviceSession> findByVisitIdAndSessionActiveTrueAndIsActiveTrue(UUID visitId);

    /** All active monitoring sessions (for dashboard) */
    List<DeviceSession> findBySessionActiveTrueAndIsActiveTrue();

    /** Active sessions for a specific hospital (via device) */
    List<DeviceSession> findByDeviceHospitalIdAndSessionActiveTrueAndIsActiveTrue(UUID hospitalId);

    /** Session history for a visit */
    Page<DeviceSession> findByVisitIdAndIsActiveTrueOrderByStartedAtDesc(
            UUID visitId, Pageable pageable);

    /** Session history for a device */
    Page<DeviceSession> findByDeviceIdAndIsActiveTrueOrderByStartedAtDesc(
            UUID deviceId, Pageable pageable);

    /**
     * High-frequency counter increment that intentionally <strong>does
     * NOT bump {@code @Version}</strong>. Used by VitalStreamService on
     * every accepted reading (~ every 5s per active session via the
     * simulator). Going through {@code save(entity)} bumped the version
     * every tick and caused any concurrent user-facing write
     * (end-session, takeover, etc.) to lose the race and surface
     * "The record was modified concurrently" to the nurse.
     *
     * <p>This single-shot UPDATE skips the entity lifecycle, so the
     * version field is preserved. Real user-user conflicts (two staff
     * editing the same row) are still protected by optimistic locking
     * on the legitimate write paths.
     */
    @Modifying
    @Query("UPDATE DeviceSession s SET s.totalReadings = s.totalReadings + 1, "
            + "s.rejectedReadings = s.rejectedReadings + :rejectedDelta "
            + "WHERE s.id = :id AND s.sessionActive = true AND s.isActive = true")
    int incrementReadings(
            @Param("id") UUID id,
            @Param("rejectedDelta") int rejectedDelta);
}
