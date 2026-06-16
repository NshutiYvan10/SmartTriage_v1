package com.smartTriage.smartTriage_server.module.vital.service;

import com.smartTriage.smartTriage_server.common.enums.AvpuScore;
import com.smartTriage.smartTriage_server.common.enums.GlucoseUnit;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.hypoglycemia.service.HypoglycemiaService;
import com.smartTriage.smartTriage_server.module.iot.engine.ContinuousMonitoringEngine;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import com.smartTriage.smartTriage_server.module.visit.service.VisitService;
import com.smartTriage.smartTriage_server.module.vital.dto.RecordVitalsRequest;
import com.smartTriage.smartTriage_server.module.vital.dto.VitalSignsResponse;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;
import com.smartTriage.smartTriage_server.module.vital.mapper.VitalSignsMapper;
import com.smartTriage.smartTriage_server.module.vital.repository.VitalSignsRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * VitalSigns service — captures and retrieves patient vital signs.
 *
 * After recording vitals, the system should trigger TEWS recalculation
 * and deterioration detection. This is the data input for the monitoring engine.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VitalSignsService {

    private final VitalSignsRepository vitalSignsRepository;
    private final VisitService visitService;
    /**
     * S3 — manually-recorded vitals now feed the deterioration engine.
     * Only its focused {@code evaluateManualVitals} entry point is used
     * here; that call is best-effort (never throws) so it can never break
     * the clinician's vitals write. The engine depends only on
     * repositories / lower-level services (no back-edge to this service),
     * so there is no circular bean wiring.
     */
    private final ContinuousMonitoringEngine monitoringEngine;
    /**
     * Hypoglycemia auto-detection on every manual/POC glucose reading. Like the
     * deterioration hook above, its entry point is best-effort (never throws) so
     * it can never break the clinician's vitals write. HypoglycemiaService does
     * not depend back on this service, so there is no circular wiring.
     */
    private final HypoglycemiaService hypoglycemiaService;

    @Transactional
    public VitalSignsResponse recordVitals(RecordVitalsRequest request) {
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());

        // Normalise glucose to mmol/L (the canonical unit) using the declared
        // source unit, so a mg/dL glucometer reading is stored and classified
        // correctly instead of being read as a (wildly wrong) mmol/L value.
        GlucoseUnit glucoseUnit = request.getBloodGlucoseUnit() != null
                ? request.getBloodGlucoseUnit() : GlucoseUnit.MMOL_L;
        Double glucoseMmol = request.getBloodGlucose() != null
                ? glucoseUnit.toMmolL(request.getBloodGlucose()) : null;

        VitalSigns vitals = VitalSigns.builder()
                .visit(visit)
                .recordedAt(Instant.now())
                .respiratoryRate(request.getRespiratoryRate())
                .heartRate(request.getHeartRate())
                .systolicBp(request.getSystolicBp())
                .diastolicBp(request.getDiastolicBp())
                .temperature(request.getTemperature())
                .spo2(request.getSpo2())
                .avpu(request.getAvpu())
                .bloodGlucose(glucoseMmol)
                .painScore(request.getPainScore())
                .gcsScore(request.getGcsScore())
                .weightKg(request.getWeightKg())
                .source(request.getSource())
                .deviceId(request.getDeviceId())
                .notes(request.getNotes())
                .build();

        vitals = vitalSignsRepository.save(vitals);

        log.info("Vitals recorded for visit {} — HR:{} RR:{} BP:{}/{} T:{} SpO2:{} AVPU:{}",
                visit.getVisitNumber(),
                vitals.getHeartRate(), vitals.getRespiratoryRate(),
                vitals.getSystolicBp(), vitals.getDiastolicBp(),
                vitals.getTemperature(), vitals.getSpo2(), vitals.getAvpu());

        // S3 — run deterioration detection on the just-recorded MANUAL
        // reading. Previously only IoT VitalStream data reached the engine,
        // so critically abnormal values a clinician typed here produced no
        // alert. evaluateManualVitals is best-effort (never throws), so a
        // failure cannot roll back or break the vitals write above.
        monitoringEngine.evaluateManualVitals(visit, vitals);

        // Auto-detect hypoglycemia on every glucose reading — previously the
        // detector only ran on a manual POST and read a frozen triage snapshot,
        // so a low POC fingerstick recorded here produced no event/alert.
        if (vitals.getBloodGlucose() != null) {
            boolean neuroglycopenia =
                    (vitals.getAvpu() != null && vitals.getAvpu() != AvpuScore.ALERT)
                    || (vitals.getGcsScore() != null && vitals.getGcsScore() < 15);
            hypoglycemiaService.evaluateGlucoseReading(visit, vitals.getBloodGlucose(), neuroglycopenia, "MANUAL_VITALS");
        }

        return VitalSignsMapper.toResponse(vitals);
    }

    public Page<VitalSignsResponse> getVitalsByVisit(UUID visitId, Pageable pageable) {
        return vitalSignsRepository.findByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visitId, pageable)
                .map(VitalSignsMapper::toResponse);
    }

    public VitalSignsResponse getLatestVitals(UUID visitId) {
        VitalSigns vitals = vitalSignsRepository
                .findFirstByVisitIdAndIsActiveTrueOrderByRecordedAtDesc(visitId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No vital signs recorded for visit: " + visitId));
        return VitalSignsMapper.toResponse(vitals);
    }

    public VitalSigns findVitalSignsOrThrow(UUID id) {
        return vitalSignsRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("VitalSigns", "id", id));
    }
}
