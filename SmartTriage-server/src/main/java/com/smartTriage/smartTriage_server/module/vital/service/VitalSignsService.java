package com.smartTriage.smartTriage_server.module.vital.service;

import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
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

    @Transactional
    public VitalSignsResponse recordVitals(RecordVitalsRequest request) {
        Visit visit = visitService.findVisitOrThrow(request.getVisitId());

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
                .bloodGlucose(request.getBloodGlucose())
                .painScore(request.getPainScore())
                .gcsScore(request.getGcsScore())
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
