package com.smartTriage.smartTriage_server.module.vital.mapper;

import com.smartTriage.smartTriage_server.module.vital.dto.VitalSignsResponse;
import com.smartTriage.smartTriage_server.module.vital.entity.VitalSigns;

public final class VitalSignsMapper {

    private VitalSignsMapper() {}

    public static VitalSignsResponse toResponse(VitalSigns vitals) {
        return VitalSignsResponse.builder()
                .id(vitals.getId())
                .visitId(vitals.getVisit().getId())
                .recordedAt(vitals.getRecordedAt())
                .respiratoryRate(vitals.getRespiratoryRate())
                .heartRate(vitals.getHeartRate())
                .systolicBp(vitals.getSystolicBp())
                .diastolicBp(vitals.getDiastolicBp())
                .temperature(vitals.getTemperature())
                .spo2(vitals.getSpo2())
                .avpu(vitals.getAvpu())
                .bloodGlucose(vitals.getBloodGlucose())
                .painScore(vitals.getPainScore())
                .gcsScore(vitals.getGcsScore())
                .weightKg(vitals.getWeightKg())
                .source(vitals.getSource())
                .deviceId(vitals.getDeviceId())
                .notes(vitals.getNotes())
                .createdAt(vitals.getCreatedAt())
                .build();
    }
}
