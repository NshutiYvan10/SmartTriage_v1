package com.smartTriage.smartTriage_server.module.iot.service;

import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.enums.SessionType;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * SpotCheckTimeoutSweep — the hard stop for forgotten spot-checks.
 *
 * <p>A SPOT_CHECK session normally self-completes inside the ingest
 * pipeline once a full validated vitals set arrives. If the nurse gets
 * pulled away (probes never attached, cart wheeled off mid-check), the
 * session would otherwise stay open forever — logically gluing the
 * shared roaming monitor to a patient it is no longer on. This sweep
 * closes any live spot-check older than {@link #TIMEOUT_MINUTES} as
 * INCOMPLETE and releases the device. An incomplete check does NOT
 * reset the patient's reassessment clock (no VitalSigns snapshot was
 * produced), so the worklist keeps showing them as due — the honest
 * outcome.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpotCheckTimeoutSweep {

    private final DeviceSessionRepository sessionRepository;
    private final IoTDeviceRepository deviceRepository;

    static final long TIMEOUT_MINUTES = 10;

    @Scheduled(fixedDelayString = "${smarttriage.iot.spot-check-sweep-ms:60000}")
    @Transactional
    public void closeTimedOutSpotChecks() {
        Instant cutoff = Instant.now().minus(TIMEOUT_MINUTES, ChronoUnit.MINUTES);
        List<DeviceSession> stale = sessionRepository
                .findBySessionTypeAndSessionActiveTrueAndIsActiveTrueAndStartedAtBefore(
                        SessionType.SPOT_CHECK, cutoff);
        for (DeviceSession session : stale) {
            try {
                session.endSession("System",
                        "Spot check timed out after " + TIMEOUT_MINUTES + " min — incomplete");
                sessionRepository.save(session);

                IoTDevice device = deviceRepository
                        .findByIdAndIsActiveTrue(session.getDevice().getId())
                        .orElse(null);
                if (device != null && device.getStatus() == DeviceStatus.MONITORING) {
                    device.setStatus(DeviceStatus.ONLINE);
                    deviceRepository.save(device);
                }
                log.warn("Spot check TIMED OUT for visit {} on device {} — closed incomplete",
                        session.getVisit().getVisitNumber(),
                        session.getDevice().getSerialNumber());
            } catch (Exception e) {
                log.error("Failed to close timed-out spot-check {}: {}",
                        session.getId(), e.getMessage());
            }
        }
    }
}
