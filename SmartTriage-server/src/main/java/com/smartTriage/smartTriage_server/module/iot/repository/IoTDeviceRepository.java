package com.smartTriage.smartTriage_server.module.iot.repository;

import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
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
public interface IoTDeviceRepository extends JpaRepository<IoTDevice, UUID> {

        Optional<IoTDevice> findByIdAndIsActiveTrue(UUID id);

        Optional<IoTDevice> findBySerialNumberAndIsActiveTrue(String serialNumber);

        Optional<IoTDevice> findByApiKeyAndIsActiveTrue(String apiKey);

        Page<IoTDevice> findByHospitalIdAndIsActiveTrueOrderByDeviceNameAsc(
                        UUID hospitalId, Pageable pageable);

        List<IoTDevice> findByHospitalIdAndStatusAndIsActiveTrue(
                        UUID hospitalId, DeviceStatus status);

        /**
         * Find devices that have missed their heartbeat deadline — for disconnect
         * detection
         */
        @Query("SELECT d FROM IoTDevice d WHERE d.isActive = true AND d.status IN ('ONLINE', 'MONITORING') " +
                        "AND d.lastHeartbeatAt < :cutoff")
        List<IoTDevice> findStaleDevices(@Param("cutoff") Instant cutoff);

        /** Devices available for assignment (online but not monitoring) */
        @Query("SELECT d FROM IoTDevice d WHERE d.isActive = true AND d.hospital.id = :hospitalId " +
                        "AND d.status = 'ONLINE' ORDER BY d.deviceName ASC")
        List<IoTDevice> findAvailableDevices(@Param("hospitalId") UUID hospitalId);

        long countByHospitalIdAndStatusAndIsActiveTrue(UUID hospitalId, DeviceStatus status);

        /**
         * All devices that are currently ONLINE or MONITORING — for simulation
         * heartbeats
         */
        @Query("SELECT d FROM IoTDevice d WHERE d.isActive = true AND d.status IN ('ONLINE', 'MONITORING')")
        List<IoTDevice> findAllOnlineOrMonitoring();

        /**
         * All devices that the simulator should drive — i.e. every active
         * device that is not permanently retired. This includes devices in
         * REGISTERED or OFFLINE state so the simulator can auto-power them on
         * via heartbeat, matching how a real ESP32 monitor would behave the
         * moment it is plugged in and reaches the hospital WiFi. Without
         * this, admin-registered monitors would stay REGISTERED forever
         * unless someone clicked "Power On" in the admin UI.
         */
        @Query("SELECT d FROM IoTDevice d WHERE d.isActive = true AND d.status <> 'DECOMMISSIONED'")
        List<IoTDevice> findAllSimulatable();

        /**
         * The IoT device permanently assigned to a given bed (if any). Used by
         * BedService to auto-create a DeviceSession when a patient is placed
         * in a bed that has a wall-mounted monitor.
         */
        Optional<IoTDevice> findByAssignedBedIdAndIsActiveTrue(UUID bedId);

        /**
         * V54 — Triage-zone monitors for a hospital. Only returns devices that
         * are flagged as triage monitors AND currently in service (admin
         * inventory state). Status (ONLINE/OFFLINE) is included so the
         * frontend can disable the picker when the monitor isn't reporting.
         */
        @Query("SELECT d FROM IoTDevice d WHERE d.isActive = true AND d.hospital.id = :hospitalId " +
                        "AND d.triageMonitor = true AND d.inService = true ORDER BY d.deviceName ASC")
        List<IoTDevice> findTriageMonitors(@Param("hospitalId") UUID hospitalId);

        /** All devices assigned to beds in a hospital — admin view. */
        @Query("SELECT d FROM IoTDevice d WHERE d.isActive = true AND d.hospital.id = :hospitalId " +
                        "AND d.assignedBed IS NOT NULL ORDER BY d.assignedBed.zone ASC, d.assignedBed.code ASC")
        List<IoTDevice> findAllAssignedToBeds(@Param("hospitalId") UUID hospitalId);
}
