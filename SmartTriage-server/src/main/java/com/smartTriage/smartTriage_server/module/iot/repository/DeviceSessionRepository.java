package com.smartTriage.smartTriage_server.module.iot.repository;

import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
