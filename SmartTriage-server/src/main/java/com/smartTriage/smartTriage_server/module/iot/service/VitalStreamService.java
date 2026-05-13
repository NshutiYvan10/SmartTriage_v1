package com.smartTriage.smartTriage_server.module.iot.service;

import com.smartTriage.smartTriage_server.common.enums.VitalSource;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceAckResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceVitalPayload;
import com.smartTriage.smartTriage_server.module.iot.dto.VitalStreamResponse;
import com.smartTriage.smartTriage_server.module.iot.engine.VitalValidationEngine;
import com.smartTriage.smartTriage_server.module.iot.engine.VitalValidationEngine.ValidationResult;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.entity.VitalStream;
import com.smartTriage.smartTriage_server.module.iot.mapper.IoTMapper;
import com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.VitalStreamRepository;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * VitalStreamService — core ingestion pipeline for IoT vital data.
 *
 * Handles the high-frequency data path:
 * ESP32 → REST endpoint → authenticate → validate → persist → ack
 *
 * Also provides:
 * - Snapshot creation: aggregate recent stream data into a validated VitalSigns
 * record
 * - Stream queries for trend analysis and real-time display
 *
 * Performance target: < 50ms per ingest call at 5-second intervals per device.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VitalStreamService {

    private final VitalStreamRepository streamRepository;
    private final VitalSignsRepository vitalSignsRepository;
    private final IoTDeviceRepository deviceRepository;
    private final com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository sessionRepository;
    private final VitalValidationEngine validationEngine;
    private final RealTimeEventPublisher eventPublisher;

    // ====================================================================
    // DATA INGESTION
    // ====================================================================

    /**
     * Ingest a vital payload from an authenticated device.
     *
     * @param payload the raw vital data from the device
     * @param device  the authenticated IoT device
     * @param session the active monitoring session (nullable if device not linked)
     * @return DeviceAckResponse with acceptance status and optional commands
     */
    @Transactional
    public DeviceAckResponse ingestVitals(DeviceVitalPayload payload,
            IoTDevice device,
            DeviceSession session) {
        Instant now = Instant.now();

        // If no active session, reject
        if (session == null) {
            return DeviceAckResponse.builder()
                    .accepted(false)
                    .rejectionReason("No active monitoring session for this device")
                    .serverTimestamp(now.toEpochMilli())
                    .build();
        }

        // Validate the payload
        ValidationResult validation = validationEngine.validate(payload);

        Visit visit = session.getVisit();

        // Build VitalStream record (always persisted, even if invalid — for audit)
        VitalStream stream = VitalStream.builder()
                .visit(visit)
                .deviceId(device.getSerialNumber())
                .sessionId(session.getId())
                .capturedAt(payload.getCapturedAt() != null ? payload.getCapturedAt() : now)
                .receivedAt(now)
                .heartRate(payload.getHeartRate())
                .spo2(payload.getSpo2())
                .respiratoryRate(payload.getRespiratoryRate())
                .temperature(payload.getTemperature())
                .systolicBp(payload.getSystolicBp())
                .diastolicBp(payload.getDiastolicBp())
                .bloodGlucose(payload.getBloodGlucose())
                .ecgWaveform(payload.getEcgWaveform())
                .ecgRhythm(payload.getEcgRhythm())
                .ecgQrsDuration(payload.getEcgQrsDuration())
                .ecgStDeviation(payload.getEcgStDeviation())
                .signalQuality(validation.signalQuality())
                .spo2PerfusionIndex(payload.getSpo2PerfusionIndex())
                .isValidated(validation.isValid())
                .rejectionReason(validation.rejectionReason())
                .batteryLevel(payload.getBatteryLevel())
                .wifiRssi(payload.getWifiRssi())
                .sequenceNumber(payload.getSequenceNumber())
                .build();

        stream = streamRepository.save(stream);

        // Counter increment via @Modifying UPDATE — does NOT bump
        // session.@Version, so concurrent user-facing writes (takeover,
        // end-session, etc.) no longer race with the simulator's
        // 5-second tick. See DeviceSessionRepository.incrementReadings.
        sessionRepository.incrementReadings(session.getId(), validation.isValid() ? 0 : 1);
        // Keep the in-memory entity counters in sync so any caller that
        // reads `session` later in the same TX sees the latest values.
        session.incrementReadings();
        if (!validation.isValid()) {
            session.incrementRejected();
        }

        // Telemetry update on device — same @Modifying-UPDATE pattern.
        // Does NOT bump device.@Version, so a nurse clicking "Pull from
        // Monitor" mid-stream no longer collides with this write.
        // We pass `firmwareVersion` separately if present — that's
        // genuine business state, not telemetry, so it still goes
        // through save() below to preserve the version check on that
        // narrow field.
        deviceRepository.updateTelemetry(
                device.getId(),
                now,                              // heartbeatAt — always updated
                now,                              // dataAt — always updated (we just got data)
                payload.getBatteryLevel(),        // null → preserves existing
                payload.getWifiRssi());           // null → preserves existing

        // Firmware version reports rarely (only when the device boots
        // with new firmware). Treat as a real business write — keeps
        // @Version protection for the field that matters. The race
        // window here is negligible (firmware changes once per device-
        // upgrade cycle, not every 5 seconds).
        if (payload.getFirmwareVersion() != null) {
            IoTDevice freshDevice = deviceRepository.findById(device.getId()).orElse(device);
            if (!payload.getFirmwareVersion().equals(freshDevice.getFirmwareVersion())) {
                freshDevice.setFirmwareVersion(payload.getFirmwareVersion());
                deviceRepository.save(freshDevice);
            }
        }

        // Build acknowledgment
        DeviceAckResponse ack = DeviceAckResponse.builder()
                .accepted(validation.isValid())
                .readingId(stream.getId().toString())
                .rejectionReason(validation.rejectionReason())
                .serverTimestamp(now.toEpochMilli())
                .build();

        if (!validation.isValid()) {
            log.debug("Rejected reading from device {}: {}",
                    device.getSerialNumber(), validation.rejectionReason());
        }

        // Push validated readings to WebSocket after transaction commits
        if (validation.isValid()) {
            // Promote the session to LIVE on the first validated reading.
            // Handles both STARTING (initial Start) and STALLED → LIVE
            // recovery without needing the watcher to wake. PAUSED and
            // DISCONNECTED stay where they are — clinician must Resume
            // (PAUSED), or the heartbeat self-heal must run first
            // (DISCONNECTED → STARTING).
            com.smartTriage.smartTriage_server.common.enums.MonitoringState s =
                    session.getMonitoringState();
            if (s == com.smartTriage.smartTriage_server.common.enums.MonitoringState.STARTING
                    || s == com.smartTriage.smartTriage_server.common.enums.MonitoringState.STALLED
                    || s == com.smartTriage.smartTriage_server.common.enums.MonitoringState.DEGRADED) {
                session.transitionState(
                        com.smartTriage.smartTriage_server.common.enums.MonitoringState.LIVE);
                sessionRepository.save(session);
            }

            final VitalStream savedStream = stream;
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            try {
                                eventPublisher.publishVitalReading(savedStream);
                            } catch (Exception e) {
                                log.warn("Failed to publish vital reading to WebSocket: {}",
                                        e.getMessage());
                            }
                        }
                    });

            // Periodically bridge IoT stream → clinical VitalSigns table
            // Every 12 validated readings (~60s at 5s intervals) so the Doctor's
            // VisitDetailPage always shows reasonably fresh IoT-sourced vitals.
            long readings = session.getTotalReadings();
            if (readings > 0 && readings % 12 == 0) {
                try {
                    createVitalSnapshot(visit.getId(), device.getSerialNumber());
                    log.debug("Periodic vital snapshot created at reading #{} for visit {}",
                            readings, visit.getVisitNumber());
                } catch (Exception e) {
                    log.warn("Failed to create periodic vital snapshot: {}", e.getMessage());
                }
            }
        }

        return ack;
    }

    // ====================================================================
    // SNAPSHOT CREATION
    // ====================================================================

    /**
     * Create a validated VitalSigns snapshot from recent stream data.
     * This bridges the high-frequency VitalStream to the clinical VitalSigns table.
     *
     * Aggregation strategy: use the MOST RECENT validated reading for each vital.
     * This is simple, auditable, and clinically appropriate for TEWS calculation.
     *
     * @param visitId  the visit to create a snapshot for
     * @param deviceId the device serial number (for the VitalSigns.deviceId field)
     * @return the created VitalSigns entity
     */
    @Transactional
    public VitalSigns createVitalSnapshot(UUID visitId, String deviceId) {
        // Get the last 30 validated readings (roughly 2.5 minutes at 5s intervals)
        List<VitalStream> recentReadings = streamRepository.findRecentValidated(
                visitId, PageRequest.of(0, 30));

        if (recentReadings.isEmpty()) {
            log.warn("Cannot create vital snapshot for visit {} — no recent validated readings", visitId);
            return null;
        }

        // Use most recent reading as the base
        VitalStream latest = recentReadings.getFirst();
        Visit visit = latest.getVisit();

        // Create clinical snapshot
        VitalSigns snapshot = VitalSigns.builder()
                .visit(visit)
                .recordedAt(latest.getCapturedAt())
                .heartRate(latest.getHeartRate())
                .spo2(latest.getSpo2())
                .respiratoryRate(latest.getRespiratoryRate())
                .temperature(latest.getTemperature())
                .systolicBp(latest.getSystolicBp())
                .diastolicBp(latest.getDiastolicBp())
                .bloodGlucose(latest.getBloodGlucose())
                .source(VitalSource.IOT_DEVICE)
                .deviceId(deviceId)
                .notes("Auto-generated from IoT stream data")
                .build();

        snapshot = vitalSignsRepository.save(snapshot);

        log.info("Vital snapshot created for visit {} from IoT stream (HR:{} RR:{} SpO2:{} T:{} BP:{}/{})",
                visit.getVisitNumber(),
                snapshot.getHeartRate(), snapshot.getRespiratoryRate(),
                snapshot.getSpo2(), snapshot.getTemperature(),
                snapshot.getSystolicBp(), snapshot.getDiastolicBp());

        return snapshot;
    }

    // ====================================================================
    // STREAM QUERIES
    // ====================================================================

    /**
     * Get the latest validated reading for a visit (real-time display).
     */
    public VitalStreamResponse getLatestReading(UUID visitId) {
        return streamRepository
                .findFirstByVisitIdAndIsValidatedTrueAndIsActiveTrueOrderByCapturedAtDesc(visitId)
                .map(IoTMapper::toResponse)
                .orElse(null);
    }

    /**
     * Get recent validated readings for trend display.
     */
    public List<VitalStreamResponse> getRecentReadings(UUID visitId, int count) {
        return streamRepository
                .findRecentValidated(visitId, PageRequest.of(0, count))
                .stream()
                .map(IoTMapper::toResponse)
                .toList();
    }

    /**
     * Get validated readings in a time window (for trend analysis).
     */
    public List<VitalStream> getValidatedReadingsInWindow(UUID visitId, Instant from, Instant to) {
        return streamRepository.findValidatedInTimeRange(visitId, from, to);
    }

    /**
     * Get paginated stream history for a visit.
     */
    public Page<VitalStreamResponse> getStreamHistory(UUID visitId, Pageable pageable) {
        return streamRepository
                .findByVisitIdAndIsActiveTrueOrderByCapturedAtDesc(visitId, pageable)
                .map(IoTMapper::toResponse);
    }

    /**
     * Get stream history for a session (audit trail).
     */
    public Page<VitalStreamResponse> getSessionStreamHistory(UUID sessionId, Pageable pageable) {
        return streamRepository
                .findBySessionIdAndIsActiveTrueOrderByCapturedAtDesc(sessionId, pageable)
                .map(IoTMapper::toResponse);
    }
}
