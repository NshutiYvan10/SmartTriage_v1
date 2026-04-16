package com.smartTriage.smartTriage_server.module.sepsis.mapper;

import com.smartTriage.smartTriage_server.common.enums.SepsisBundleItem;
import com.smartTriage.smartTriage_server.common.enums.SepsisStatus;
import com.smartTriage.smartTriage_server.module.sepsis.dto.SepsisBundleStatusResponse;
import com.smartTriage.smartTriage_server.module.sepsis.dto.SepsisScreeningResponse;
import com.smartTriage.smartTriage_server.module.sepsis.entity.SepsisScreening;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Mapper for converting SepsisScreening entities to response DTOs.
 */
public final class SepsisMapper {

    private SepsisMapper() {
    }

    private static final int TOTAL_BUNDLE_ITEMS = SepsisBundleItem.values().length;

    public static SepsisScreeningResponse toResponse(SepsisScreening screening) {
        return toResponse(screening, List.of());
    }

    public static SepsisScreeningResponse toResponse(SepsisScreening screening, List<String> findings) {
        int itemsCompleted = countBundleItems(screening);
        boolean bundleRequired = screening.getSepsisStatus() == SepsisStatus.SEPSIS_SUSPECTED
                || screening.getSepsisStatus() == SepsisStatus.SEVERE_SEPSIS
                || screening.getSepsisStatus() == SepsisStatus.SEPTIC_SHOCK;

        SepsisScreeningResponse.SepsisScreeningResponseBuilder builder = SepsisScreeningResponse.builder()
                .id(screening.getId())
                .screenedAt(screening.getScreenedAt())
                .screenedByName(screening.getScreenedByName())
                .sepsisStatus(screening.getSepsisStatus())
                .qsofaScore(screening.getQsofaScore())
                .sirsScore(screening.getSirsScore())
                .alteredMentation(screening.isAlteredMentation())
                .respiratoryRateHigh(screening.isRespiratoryRateHigh())
                .systolicBpLow(screening.isSystolicBpLow())
                .temperatureCriteriaMet(screening.isTemperatureCriteriaMet())
                .heartRateCriteriaMet(screening.isHeartRateCriteriaMet())
                .respiratoryRateCriteriaMet(screening.isRespiratoryRateCriteriaMet())
                .wbcCriteriaMet(screening.isWbcCriteriaMet())
                .suspectedInfectionSource(screening.getSuspectedInfectionSource())
                .lactateLevel(screening.getLactateLevel())
                .findings(findings)
                .bundleRequired(bundleRequired)
                .bundleStartedAt(screening.getBundleStartedAt())
                .bundleCompletedAt(screening.getBundleCompletedAt())
                .bloodCultureObtained(screening.isBloodCultureObtained())
                .broadSpectrumAntibiotics(screening.isBroadSpectrumAntibiotics())
                .ivCrystalloidBolus(screening.isIvCrystalloidBolus())
                .lactateMeasured(screening.isLactateMeasured())
                .vasopressorsIfNeeded(screening.isVasopressorsIfNeeded())
                .repeatLactateIfElevated(screening.isRepeatLactateIfElevated())
                .bundleItemsCompleted(itemsCompleted)
                .bundleItemsTotal(TOTAL_BUNDLE_ITEMS)
                .notes(screening.getNotes())
                .createdAt(screening.getCreatedAt());

        // Visit info
        if (screening.getVisit() != null) {
            builder.visitId(screening.getVisit().getId());
            builder.visitNumber(screening.getVisit().getVisitNumber());
            if (screening.getVisit().getPatient() != null) {
                builder.patientName(
                        screening.getVisit().getPatient().getFirstName() + " " +
                                screening.getVisit().getPatient().getLastName());
            }
        }

        return builder.build();
    }

    public static SepsisBundleStatusResponse toBundleStatusResponse(SepsisScreening screening) {
        int itemsCompleted = countBundleItems(screening);
        long minutesSinceStart = 0;
        boolean isBundleOverdue = false;

        if (screening.getBundleStartedAt() != null) {
            minutesSinceStart = Duration.between(screening.getBundleStartedAt(), Instant.now()).toMinutes();
            isBundleOverdue = minutesSinceStart > 60 && screening.getBundleCompletedAt() == null;
        }

        String patientName = null;
        if (screening.getVisit() != null && screening.getVisit().getPatient() != null) {
            patientName = screening.getVisit().getPatient().getFirstName() + " " +
                    screening.getVisit().getPatient().getLastName();
        }

        return SepsisBundleStatusResponse.builder()
                .screeningId(screening.getId())
                .visitId(screening.getVisit() != null ? screening.getVisit().getId() : null)
                .patientName(patientName)
                .sepsisStatus(screening.getSepsisStatus())
                .bundleStartedAt(screening.getBundleStartedAt())
                .bundleCompletedAt(screening.getBundleCompletedAt())
                .minutesSinceBundleStart(minutesSinceStart)
                .isBundleOverdue(isBundleOverdue)
                .isBundleComplete(screening.getBundleCompletedAt() != null)
                .bloodCultureObtained(screening.isBloodCultureObtained())
                .broadSpectrumAntibiotics(screening.isBroadSpectrumAntibiotics())
                .ivCrystalloidBolus(screening.isIvCrystalloidBolus())
                .lactateMeasured(screening.isLactateMeasured())
                .vasopressorsIfNeeded(screening.isVasopressorsIfNeeded())
                .repeatLactateIfElevated(screening.isRepeatLactateIfElevated())
                .itemsCompleted(itemsCompleted)
                .totalItems(TOTAL_BUNDLE_ITEMS)
                .compliancePercentage(TOTAL_BUNDLE_ITEMS > 0
                        ? (double) itemsCompleted / TOTAL_BUNDLE_ITEMS * 100.0
                        : 0.0)
                .build();
    }

    private static int countBundleItems(SepsisScreening screening) {
        int count = 0;
        if (screening.isBloodCultureObtained()) count++;
        if (screening.isBroadSpectrumAntibiotics()) count++;
        if (screening.isIvCrystalloidBolus()) count++;
        if (screening.isLactateMeasured()) count++;
        if (screening.isVasopressorsIfNeeded()) count++;
        if (screening.isRepeatLactateIfElevated()) count++;
        return count;
    }
}
