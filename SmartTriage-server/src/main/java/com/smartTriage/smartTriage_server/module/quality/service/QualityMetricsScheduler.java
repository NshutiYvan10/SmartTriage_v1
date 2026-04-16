package com.smartTriage.smartTriage_server.module.quality.service;

import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * QualityMetricsScheduler — automated daily computation of quality metrics.
 *
 * Runs at 00:05 daily to compute the previous day's metrics for all active hospitals.
 * Also triggers weekly aggregation on Mondays and monthly aggregation on the 1st.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityMetricsScheduler {

    private final QualityMetricsService qualityMetricsService;
    private final HospitalRepository hospitalRepository;

    /**
     * Daily metrics computation — runs at 00:05 every day.
     * Computes metrics for the previous day for all active hospitals.
     */
    @Scheduled(cron = "0 5 0 * * *")
    public void computeDailyMetricsForAllHospitals() {
        LocalDate yesterday = LocalDate.now().minusDays(1);
        log.info("Starting scheduled daily metrics computation for {}", yesterday);

        List<Hospital> activeHospitals = hospitalRepository.findAll().stream()
                .filter(Hospital::isActive)
                .toList();

        int successCount = 0;
        int failCount = 0;

        for (Hospital hospital : activeHospitals) {
            try {
                qualityMetricsService.computeDailyMetrics(hospital.getId(), yesterday);
                successCount++;
            } catch (Exception e) {
                failCount++;
                log.error("Failed to compute daily metrics for hospital {}: {}",
                        hospital.getName(), e.getMessage(), e);
            }
        }

        log.info("Daily metrics computation complete: {} succeeded, {} failed out of {} hospitals",
                successCount, failCount, activeHospitals.size());

        // Weekly aggregation on Mondays
        if (LocalDate.now().getDayOfWeek() == DayOfWeek.MONDAY) {
            computeWeeklyForAllHospitals(activeHospitals);
        }

        // Monthly aggregation on 1st of month
        if (LocalDate.now().getDayOfMonth() == 1) {
            computeMonthlyForAllHospitals(activeHospitals);
        }
    }

    private void computeWeeklyForAllHospitals(List<Hospital> hospitals) {
        LocalDate weekStart = LocalDate.now().minusDays(7);
        log.info("Starting weekly metrics aggregation for week of {}", weekStart);

        for (Hospital hospital : hospitals) {
            try {
                qualityMetricsService.computeWeeklyMetrics(hospital.getId(), weekStart);
            } catch (Exception e) {
                log.error("Failed to compute weekly metrics for hospital {}: {}",
                        hospital.getName(), e.getMessage(), e);
            }
        }
    }

    private void computeMonthlyForAllHospitals(List<Hospital> hospitals) {
        YearMonth previousMonth = YearMonth.now().minusMonths(1);
        log.info("Starting monthly metrics aggregation for {}", previousMonth);

        for (Hospital hospital : hospitals) {
            try {
                qualityMetricsService.computeMonthlyMetrics(hospital.getId(), previousMonth);
            } catch (Exception e) {
                log.error("Failed to compute monthly metrics for hospital {}: {}",
                        hospital.getName(), e.getMessage(), e);
            }
        }
    }
}
