package com.smartTriage.smartTriage_server.module.iot.simulator;

import com.smartTriage.smartTriage_server.common.enums.DeviceStatus;
import com.smartTriage.smartTriage_server.common.enums.DeviceType;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceVitalPayload;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceAckResponse;
import com.smartTriage.smartTriage_server.module.iot.engine.ContinuousMonitoringEngine;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.repository.DeviceSessionRepository;
import com.smartTriage.smartTriage_server.module.iot.repository.IoTDeviceRepository;
import com.smartTriage.smartTriage_server.module.iot.service.DeviceService;
import com.smartTriage.smartTriage_server.module.iot.service.VitalStreamService;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * VitalSimulatorService — simulates ESP32 multi-parameter monitors for EVERY
 * admin-registered device in the hospital. The goal is to act like a real
 * fleet of monitors, so that a tester can register a device in the admin
 * UI, link it to a bed, place a patient in that bed, and immediately see
 * live vitals flowing — without having to "plug in hardware" or click
 * "Power On" on each monitor.
 *
 * Activated by: smarttriage.simulation.enabled=true
 *
 * Behaviour (every 5 seconds):
 *   1. HEARTBEAT — For every active, non-decommissioned device, send a
 *      heartbeat. Devices in REGISTERED or OFFLINE state are transitioned
 *      to ONLINE via {@link DeviceService#processHeartbeat}, which mirrors
 *      what a physical ESP32 does the moment it connects to WiFi. This is
 *      what lets Monitor 1 and Monitor 2 (admin-created) come alive
 *      automatically without admin having to hit the power-on button.
 *   2. VITAL STREAM — For every active {@code DeviceSession} (created
 *      either manually by a nurse or automatically by {@code BedService}
 *      when a patient is placed in a bed whose {@code assignedBed} is
 *      linked to a monitor), generate realistic vitals and ingest them
 *      through the full pipeline: validate → persist → WebSocket push →
 *      AI monitoring engine.
 *
 * On startup it also registers one default simulated device (SIM-ESP32-001)
 * and brings it ONLINE so there's always at least one simulated device
 * available for ad-hoc testing even when the hospital has no admin-created
 * monitors yet.
 *
 * Simulated vitals (stable adult, with natural variation):
 * HR: 73–83 bpm, RR: 14–18, SpO2: 95–99%, Temp: 36.5–37.1°C,
 * BP: 112–128 / 70–80 mmHg, Glucose: 5.0–5.8 mmol/L
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "smarttriage.simulation.enabled", havingValue = "true")
public class VitalSimulatorService {

    private final DeviceService deviceService;
    private final VitalStreamService vitalStreamService;
    private final ContinuousMonitoringEngine monitoringEngine;
    private final IoTDeviceRepository deviceRepository;
    private final DeviceSessionRepository sessionRepository;
    private final HospitalRepository hospitalRepository;
    private final PlatformTransactionManager transactionManager;

    private static final String SIM_SERIAL = "SIM-ESP32-001";
    private static final String SIM_DEVICE_NAME = "Simulated Multi-Parameter Monitor";

    // Per-session sequence counters
    private final ConcurrentHashMap<UUID, Long> sessionSequence = new ConcurrentHashMap<>();
    private volatile boolean ready = false;
    private final Random random = new Random();

    // ── Normal vital baselines (stable adult) ──
    private static final int BASE_HR = 78;
    private static final int BASE_RR = 16;
    private static final int BASE_SPO2 = 97;
    private static final double BASE_TEMP = 36.8;
    private static final int BASE_SBP = 120;
    private static final int BASE_DBP = 75;
    private static final double BASE_GLUCOSE = 5.4;

    // ====================================================================
    // INITIALIZATION
    // ====================================================================

    /**
     * Initialize the simulator on application-ready. NOT annotated @Transactional
     * on purpose — each sub-step runs in its own short-lived transaction via
     * TransactionTemplate so the INSERT of a new sim device is fully committed
     * before the subsequent heartbeat UPDATE, which eliminates the class of
     * optimistic-lock conflicts that can surface during DevTools restart or
     * when scheduled beans race at startup.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void initialize() {
        try {
            log.info("╔══════════════════════════════════════════════════════════════╗");
            log.info("║       VITAL SIMULATOR — Starting                            ║");
            log.info("╚══════════════════════════════════════════════════════════════╝");

            TransactionTemplate tx = new TransactionTemplate(transactionManager);

            // ── Step 1: pick any hospital (read-only TX) ──────────────────
            Hospital hospital = tx.execute(status ->
                    hospitalRepository.findAll().stream().findFirst().orElse(null));

            if (hospital == null) {
                log.error("SIMULATOR: No hospital found. Cannot register default device.");
                ready = true; // still tick for admin-registered devices
                return;
            }

            // ── Step 2: find-or-register the sim device in its OWN TX so the
            // INSERT is committed before the heartbeat UPDATE runs. Returning
            // only the UUID avoids carrying a detached entity across TX
            // boundaries (which was the previous source of version drift). ─
            final UUID hospitalId = hospital.getId();
            UUID deviceId = tx.execute(status -> findOrRegisterDevice(hospitalId).getId());

            // ── Step 3: initial heartbeat in its own TX. processHeartbeat is
            // itself @Transactional so it creates TX2. If a scheduled task
            // (the simulator's own tick() or DeviceHeartbeatScheduler) happens
            // to race on this device, we catch the optimistic-lock failure and
            // rely on the next tick to bring it online — better than crashing
            // the entire application on startup. ───────────────────────────
            try {
                IoTDevice device = tx.execute(status ->
                        deviceRepository.findById(deviceId).orElse(null));
                if (device != null) {
                    deviceService.processHeartbeat(device, "127.0.0.1");
                }
            } catch (ObjectOptimisticLockingFailureException e) {
                log.warn("SIMULATOR: version conflict on initial heartbeat — " +
                        "device will come ONLINE on next tick ({})", e.getMessage());
            }

            ready = true;

            log.info("╔══════════════════════════════════════════════════════════════╗");
            log.info("║  SIMULATOR: Default device {} ONLINE      ║", padRight(SIM_SERIAL, 18));
            log.info("║                                                              ║");
            log.info("║  All admin-powered-on devices will also receive heartbeats.  ║");
            log.info("║  Active monitoring sessions get simulated vital streams.     ║");
            log.info("╚══════════════════════════════════════════════════════════════╝");

        } catch (Exception e) {
            log.error("SIMULATOR: Initialization failed — {}", e.getMessage(), e);
            ready = true; // still allow ticking
        }
    }

    // ====================================================================
    // SCHEDULED TICK — heartbeat ALL devices + stream vitals for sessions
    // ====================================================================

    @Scheduled(fixedDelayString = "${smarttriage.simulation.interval-ms:5000}")
    @Transactional
    public void tick() {
        if (!ready)
            return;

        try {
            // ── Step 1: Heartbeat EVERY active, non-decommissioned device ──
            // Including devices in REGISTERED / OFFLINE state so the simulator
            // acts like a real ESP32 fleet: a monitor that boots and reaches
            // WiFi announces itself and transitions itself to ONLINE, it does
            // not wait for the admin to click a button. processHeartbeat()
            // handles the REGISTERED/OFFLINE → ONLINE flip and publishes a
            // WebSocket event so the admin UI updates instantly.
            //
            // Each update is isolated per-device so that a single stale row
            // (e.g. a concurrent admin power-off or decommission) does not
            // abort the whole batch. A version conflict here is benign:
            // whatever bumped the version is a fresher update than ours.
            List<IoTDevice> simulatable = deviceRepository.findAllSimulatable();
            for (IoTDevice device : simulatable) {
                try {
                    DeviceStatus currentStatus = device.getStatus();
                    if (currentStatus == DeviceStatus.REGISTERED
                            || currentStatus == DeviceStatus.OFFLINE) {
                        // State transition — delegate to the canonical code
                        // path so the status flip, WebSocket push, and
                        // audit log all happen exactly like a real device
                        // check-in. Runs in processHeartbeat's own TX.
                        deviceService.processHeartbeat(device, "127.0.0.1");
                    } else {
                        // ONLINE / MONITORING: fast inline refresh. No state
                        // transition needed, so skip the extra TX hop.
                        device.setLastHeartbeatAt(Instant.now());
                        if (device.getBatteryLevel() == null)
                            device.setBatteryLevel(100);
                        if (device.getWifiRssi() == null)
                            device.setWifiRssi(-40);
                        deviceRepository.save(device);
                    }
                } catch (ObjectOptimisticLockingFailureException e) {
                    log.debug("SIM: skipping heartbeat for {} — concurrent update",
                            device.getSerialNumber());
                } catch (Exception e) {
                    // Never let one misbehaving device kill the tick.
                    log.warn("SIM: heartbeat failed for {} — {}",
                            device.getSerialNumber(), e.getMessage());
                }
            }

            // ── Step 2: Stream vitals for ALL active monitoring sessions ──
            List<DeviceSession> activeSessions = sessionRepository.findBySessionActiveTrueAndIsActiveTrue();

            // Clean up sequence counters for ended sessions
            Set<UUID> activeIds = new HashSet<>();
            activeSessions.forEach(s -> activeIds.add(s.getId()));
            sessionSequence.keySet().removeIf(id -> !activeIds.contains(id));

            for (DeviceSession session : activeSessions) {
                try {
                    streamVitalsForSession(session);
                } catch (Exception e) {
                    log.debug("SIM: Error streaming for session {}: {}",
                            session.getId(), e.getMessage());
                }
            }

        } catch (Exception e) {
            log.error("SIMULATOR: Error on tick: {}", e.getMessage());
        }
    }

    private void streamVitalsForSession(DeviceSession session) {
        IoTDevice device = session.getDevice();
        long seq = sessionSequence.merge(session.getId(), 1L, Long::sum);

        if (seq == 1) {
            log.info("SIM: ▶ Starting vital stream for Visit {} via Device {}",
                    visit(session), device.getSerialNumber());
        }

        // Generate vitals
        DeviceVitalPayload payload = generateNormalVitals(device.getSerialNumber(), seq);

        // Ingest through full pipeline (validate → persist → WebSocket push)
        DeviceAckResponse ack = vitalStreamService.ingestVitals(payload, device, session);

        if (ack.isAccepted()) {
            // Run AI monitoring engine (deterioration detection, auto-retriage)
            try {
                monitoringEngine.analyseAndRespond(session.getVisit().getId(), session);
            } catch (Exception e) {
                log.debug("SIM: Monitoring engine note: {}", e.getMessage());
            }

            if (seq % 12 == 1) { // Log every ~60s
                log.info("SIM [{}] HR:{} RR:{} SpO2:{}% T:{}°C BP:{}/{} | seq:{}",
                        visit(session), payload.getHeartRate(), payload.getRespiratoryRate(),
                        payload.getSpo2(), payload.getTemperature(),
                        payload.getSystolicBp(), payload.getDiastolicBp(), seq);
            }
        } else {
            log.warn("SIM [{}] REJECTED — {}", visit(session), ack.getRejectionReason());
        }
    }

    // ====================================================================
    // VITAL GENERATION — normal-range with natural variation
    // ====================================================================

    private DeviceVitalPayload generateNormalVitals(String serialNumber, long seq) {
        // ST-segment deviation — combines:
        //   1. A slow sinusoidal drift (~60s period at 5s tick interval) so
        //      the value visibly changes between successive readings. Without
        //      this, pure gaussian noise around 0 with σ=0.08 produces lots
        //      of 0.00 / -0.01 / 0.01 values that look static to a human
        //      eye when rounded to two decimals.
        //   2. Small gaussian jitter on top so it doesn't look mechanical.
        // Result: ~±0.10 mV with a visible slow sweep — still well inside
        // the clinical "normal ST" band (±0.1 mV is routine benign baseline
        // wander from respiration / electrode contact).
        double slowDrift = Math.sin(seq / 12.0) * 0.08; // 0.08 mV peak at ~1-min period
        double jitter = random.nextGaussian() * 0.03;   // fine-grained noise
        double stDeviation = Math.round((slowDrift + jitter) * 100.0) / 100.0;
        int qrsDuration = vary(88, 6); // 82–94 ms (normal: 80–100ms)

        return DeviceVitalPayload.builder()
                .serialNumber(serialNumber)
                .capturedAt(Instant.now())
                .sequenceNumber(seq)
                // Vital signs — normal range with realistic variation
                .heartRate(vary(BASE_HR, 5)) // 73–83 bpm
                .spo2(clamp(vary(BASE_SPO2, 2), 92, 100)) // 95–99%
                .respiratoryRate(vary(BASE_RR, 2)) // 14–18 bpm
                .temperature(varyDouble(BASE_TEMP, 0.3)) // 36.5–37.1°C
                .systolicBp(vary(BASE_SBP, 8)) // 112–128 mmHg
                .diastolicBp(vary(BASE_DBP, 5)) // 70–80 mmHg
                .bloodGlucose(varyDouble(BASE_GLUCOSE, 0.4)) // 5.0–5.8 mmol/L
                // ECG — Normal Sinus Rhythm with realistic parameters
                .ecgRhythm("NSR")
                .ecgQrsDuration(qrsDuration)
                .ecgStDeviation(stDeviation)
                // Device metadata
                .batteryLevel(clamp(95 - (int) (seq / 200), 10, 100))
                .wifiRssi(-40 + random.nextInt(10)) // -40 to -31 dBm
                .spo2PerfusionIndex(1.5 + random.nextDouble() * 3.0)
                .firmwareVersion("1.0.0-sim")
                .build();
    }

    private int vary(int base, int range) {
        return base - range + random.nextInt(range * 2 + 1);
    }

    private double varyDouble(double base, double range) {
        return Math.round((base - range + random.nextDouble() * range * 2) * 10.0) / 10.0;
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    // ====================================================================
    // HELPER — find or register the default simulated device
    // ====================================================================

    /**
     * Find the existing simulator device or register a new one. Called inside
     * a TransactionTemplate.execute() so the INSERT (if performed) is committed
     * before control returns — the caller gets back an id pointing at a row
     * that definitively exists. Hospital is re-loaded by id rather than passed
     * as an entity reference to keep it attached to the current session.
     */
    private IoTDevice findOrRegisterDevice(UUID hospitalId) {
        return deviceRepository.findBySerialNumberAndIsActiveTrue(SIM_SERIAL)
                .orElseGet(() -> {
                    Hospital hospital = hospitalRepository.findById(hospitalId)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Hospital " + hospitalId + " disappeared before sim device registration"));

                    byte[] randomBytes = new byte[48];
                    new SecureRandom().nextBytes(randomBytes);
                    String apiKey = "st_sim_" + Base64.getUrlEncoder()
                            .withoutPadding().encodeToString(randomBytes);

                    IoTDevice device = IoTDevice.builder()
                            .serialNumber(SIM_SERIAL)
                            .deviceName(SIM_DEVICE_NAME)
                            .deviceType(DeviceType.ESP32_MONITOR)
                            .hospital(hospital)
                            .apiKey(apiKey)
                            .status(DeviceStatus.REGISTERED)
                            .firmwareVersion("1.0.0-sim")
                            .location("Simulation (Virtual)")
                            .heartbeatTimeoutSeconds(60)
                            .dataIntervalSeconds(5)
                            .notes("Auto-registered by VitalSimulatorService for testing")
                            .build();

                    // saveAndFlush — force the INSERT to hit the DB within
                    // this TX so any subsequent SELECT (from the next TX)
                    // reads a committed, consistent row.
                    device = deviceRepository.saveAndFlush(device);
                    log.info("SIMULATOR: Registered device {} (API key: {}...)",
                            SIM_SERIAL, apiKey.substring(0, 15));
                    return device;
                });
    }

    private String visit(DeviceSession s) {
        try {
            return s.getVisit().getVisitNumber();
        } catch (Exception e) {
            return "?";
        }
    }

    private String padRight(String s, int n) {
        if (s == null)
            s = "";
        return String.format("%-" + n + "s", s.length() > n ? s.substring(0, n) : s);
    }
}
