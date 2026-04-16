package com.smartTriage.smartTriage_server.module.shift.service;

import com.smartTriage.smartTriage_server.common.enums.ShiftPeriod;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * Shift-boundary scheduler — fires a few minutes before each shift starts
 * so the new shift's roster is already in place when staff clock in.
 *
 * <h3>Schedule</h3>
 * <ul>
 *   <li><b>06:45</b> daily — materialize the DAY shift (07:00 start) for every
 *       active hospital.</li>
 *   <li><b>18:45</b> daily — materialize the NIGHT shift (19:00 start) for
 *       every active hospital.</li>
 * </ul>
 *
 * <p>The underlying {@link ShiftMaterializerService} is idempotent, so
 * re-running this (e.g. after a restart at 08:00) is safe — it will not
 * create duplicate assignments.
 *
 * <p>On application startup we also run a <em>catch-up</em> materialization
 * so a fresh deploy doesn't leave the current shift empty if we missed the
 * scheduled trigger.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShiftMaterializerScheduler {

    private final HospitalRepository hospitalRepository;
    private final ShiftMaterializerService shiftMaterializerService;

    /**
     * DAY shift materialization — 15 minutes before 07:00.
     * Cron: sec min hour day month dow (Spring 6 fields).
     */
    @Scheduled(cron = "0 45 6 * * *", zone = "Africa/Kigali")
    public void materializeDayShift() {
        LocalDate today = LocalDate.now();
        log.info("[ShiftMaterializer] Firing DAY materialization for {}", today);
        sweepAllHospitals(today, ShiftPeriod.DAY);
    }

    /**
     * NIGHT shift materialization — 15 minutes before 19:00.
     */
    @Scheduled(cron = "0 45 18 * * *", zone = "Africa/Kigali")
    public void materializeNightShift() {
        LocalDate today = LocalDate.now();
        log.info("[ShiftMaterializer] Firing NIGHT materialization for {}", today);
        sweepAllHospitals(today, ShiftPeriod.NIGHT);
    }

    /**
     * Catch-up sweep shortly after startup. Materializes whichever shift is
     * currently in progress in case we missed the scheduled trigger.
     */
    @Scheduled(initialDelay = 30_000, fixedDelay = Long.MAX_VALUE)
    public void catchUpOnStartup() {
        LocalDate shiftDate = ShiftAssignmentService.getCurrentShiftDate();
        ShiftPeriod shiftPeriod = ShiftAssignmentService.getCurrentShiftPeriod();
        log.info("[ShiftMaterializer] Startup catch-up for {} {}", shiftDate, shiftPeriod);
        sweepAllHospitals(shiftDate, shiftPeriod);
    }

    private void sweepAllHospitals(LocalDate shiftDate, ShiftPeriod shiftPeriod) {
        List<Hospital> hospitals = hospitalRepository.findByIsActiveTrue();
        int totalCreated = 0;
        for (Hospital hospital : hospitals) {
            try {
                int created = shiftMaterializerService.materializeShift(hospital, shiftDate, shiftPeriod);
                totalCreated += created;
            } catch (Exception e) {
                // A failure on one tenant must not block the sweep.
                log.error("[ShiftMaterializer] Failed for hospital {} {} {}: {}",
                        hospital.getHospitalCode(), shiftDate, shiftPeriod, e.getMessage(), e);
            }
        }
        log.info("[ShiftMaterializer] Sweep complete — {} hospitals, {} rows created for {} {}",
                hospitals.size(), totalCreated, shiftDate, shiftPeriod);
    }
}
