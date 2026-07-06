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
                .pediatric(screening.isPediatric())
                .pediatricCaveat(screening.getPediatricCaveat())
                .insufficientData(screening.isInsufficientData())
                .dataQualityNote(screening.getDataQualityNote())
                .bundleStartedByName(screening.getBundleStartedByName())
                .bundleCompletedByName(screening.getBundleCompletedByName())
                .bloodCultureObtainedAt(screening.getBloodCultureObtainedAt())
                .broadSpectrumAntibioticsAt(screening.getBroadSpectrumAntibioticsAt())
                .ivCrystalloidBolusAt(screening.getIvCrystalloidBolusAt())
                .lactateMeasuredAt(screening.getLactateMeasuredAt())
                .vasopressorsIfNeededAt(screening.getVasopressorsIfNeededAt())
                .repeatLactateIfElevatedAt(screening.getRepeatLactateIfElevatedAt())
                .notes(screening.getNotes())
                .createdAt(screening.getCreatedAt());

        // Visit info. currentBed and patient are LAZY on Visit; when this mapper runs outside the
        // loading transaction (controllers map after the @Transactional service returns) an
        // uninitialised proxy would throw LazyInitializationException. Guard with isInitialized so
        // mapping degrades to a null label instead of a 500 — the write paths initialise these
        // in-service so the data is present there.
        // Guard the visit proxy itself (not just its bed/patient): the read paths
        // return detached entities, so an uninitialised visit would throw
        // LazyInitializationException on getVisitNumber(). The service hydrates it
        // before mapping; this degrades to a bare id rather than a 500 if it didn't.
        if (screening.getVisit() != null && org.hibernate.Hibernate.isInitialized(screening.getVisit())) {
            builder.visitId(screening.getVisit().getId());
            builder.visitNumber(screening.getVisit().getVisitNumber());
            builder.currentZone(screening.getVisit().getCurrentEdZone());
            if (screening.getVisit().getCurrentBed() != null
                    && org.hibernate.Hibernate.isInitialized(screening.getVisit().getCurrentBed())) {
                builder.currentBedLabel(screening.getVisit().getCurrentBed().getCode());
            }
            if (screening.getVisit().getPatient() != null
                    && org.hibernate.Hibernate.isInitialized(screening.getVisit().getPatient())) {
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
        // Same lazy-proxy guard as toResponse: the bundle endpoints map a detached
        // screening whose visit/patient may be uninitialised — never dereference an
        // uninitialised proxy here (the service hydrates it on the happy path).
        if (screening.getVisit() != null && org.hibernate.Hibernate.isInitialized(screening.getVisit())
                && screening.getVisit().getPatient() != null
                && org.hibernate.Hibernate.isInitialized(screening.getVisit().getPatient())) {
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
