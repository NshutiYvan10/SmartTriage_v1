package com.smartTriage.smartTriage_server.module.iot.mapper;

import com.smartTriage.smartTriage_server.module.iot.dto.DeviceResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.DeviceSessionResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.VitalStreamResponse;
import com.smartTriage.smartTriage_server.module.iot.entity.DeviceSession;
import com.smartTriage.smartTriage_server.module.iot.entity.IoTDevice;
import com.smartTriage.smartTriage_server.module.iot.entity.VitalStream;

import java.util.UUID;

/**
 * Maps IoT entities to response DTOs.
 */
public final class IoTMapper {

    private IoTMapper() {
    }

    public static DeviceResponse toResponse(IoTDevice device, UUID activeVisitId) {
        return DeviceResponse.builder()
                .id(device.getId())
                .serialNumber(device.getSerialNumber())
                .deviceName(device.getDeviceName())
                .deviceType(device.getDeviceType())
                .hospitalId(device.getHospital().getId())
                .status(device.getStatus())
                .firmwareVersion(device.getFirmwareVersion())
                .lastHeartbeatAt(device.getLastHeartbeatAt())
                .lastDataAt(device.getLastDataAt())
                .batteryLevel(device.getBatteryLevel())
                .wifiRssi(device.getWifiRssi())
                .ipAddress(device.getIpAddress())
                .macAddress(device.getMacAddress())
                .location(device.getLocation())
                .inService(device.isInService())
                .triageMonitor(device.isTriageMonitor())
                .heartbeatTimeoutSeconds(device.getHeartbeatTimeoutSeconds())
                .dataIntervalSeconds(device.getDataIntervalSeconds())
                .notes(device.getNotes())
                .activeVisitId(activeVisitId)
                .createdAt(device.getCreatedAt())
                .updatedAt(device.getUpdatedAt())
                .build();
    }

    public static DeviceResponse toResponse(IoTDevice device) {
        return toResponse(device, null);
    }

    public static DeviceSessionResponse toResponse(DeviceSession session) {
        // Patient name + bed are read defensively — the session's
        // Visit / Patient / Bed associations are lazy, so a session
        // mapped outside a Hibernate session would throw on access.
        // Mapping happens inside the @Transactional service methods
        // that return these DTOs, so the lazy loads do succeed there;
        // the try/catch is belt-and-braces for ad-hoc callers.
        String pName = null;
        String bCode = null;
        com.smartTriage.smartTriage_server.common.enums.EdZone bZone = null;
        try {
            com.smartTriage.smartTriage_server.module.visit.entity.Visit v = session.getVisit();
            if (v != null) {
                if (v.getPatient() != null) {
                    String fn = v.getPatient().getFirstName();
                    String ln = v.getPatient().getLastName();
                    pName = ((fn == null ? "" : fn) + " " + (ln == null ? "" : ln)).trim();
                    if (pName.isEmpty()) pName = null;
                }
                if (v.getCurrentBed() != null) {
                    bCode = v.getCurrentBed().getCode();
                    bZone = v.getCurrentBed().getZone();
                }
            }
        } catch (Exception ignored) {
            // Lazy-init outside a session — leave the fields null.
        }
        return DeviceSessionResponse.builder()
                .id(session.getId())
                .deviceId(session.getDevice().getId())
                .deviceName(session.getDevice().getDeviceName())
                .deviceSerialNumber(session.getDevice().getSerialNumber())
                .visitId(session.getVisit().getId())
                .visitNumber(session.getVisit().getVisitNumber())
                .patientName(pName)
                .bedCode(bCode)
                .bedZone(bZone)
                .startedAt(session.getStartedAt())
                .endedAt(session.getEndedAt())
                .sessionActive(session.isSessionActive())
                .startedByName(session.getStartedByName())
                .endedByName(session.getEndedByName())
                .endReason(session.getEndReason())
                .totalReadings(session.getTotalReadings())
                .rejectedReadings(session.getRejectedReadings())
                .alertsGenerated(session.getAlertsGenerated())
                .retriagesTriggered(session.getRetriagesTriggered())
                .trendStatus(session.getTrendStatus())
                .trendUpdatedAt(session.getTrendUpdatedAt())
                .monitoringState(session.getMonitoringState())
                .monitoringStateAt(session.getMonitoringStateAt())
                .pausedAt(session.getPausedAt())
                .pausedByName(session.getPausedByName())
                .resumedAt(session.getResumedAt())
                .resumedByName(session.getResumedByName())
                .continuityGroupId(session.getContinuityGroupId())
                .createdAt(session.getCreatedAt())
                .build();
    }

    public static VitalStreamResponse toResponse(VitalStream vs) {
        return VitalStreamResponse.builder()
                .id(vs.getId())
                .visitId(vs.getVisit().getId())
                .deviceId(vs.getDeviceId())
                .sessionId(vs.getSessionId())
                .capturedAt(vs.getCapturedAt())
                .receivedAt(vs.getReceivedAt())
                .heartRate(vs.getHeartRate())
                .spo2(vs.getSpo2())
                .respiratoryRate(vs.getRespiratoryRate())
                .temperature(vs.getTemperature())
                .systolicBp(vs.getSystolicBp())
                .diastolicBp(vs.getDiastolicBp())
                .bloodGlucose(vs.getBloodGlucose())
                .ecgRhythm(vs.getEcgRhythm())
                .ecgQrsDuration(vs.getEcgQrsDuration())
                .ecgStDeviation(vs.getEcgStDeviation())
                .signalQuality(vs.getSignalQuality())
                .spo2PerfusionIndex(vs.getSpo2PerfusionIndex())
                .isValidated(vs.isValidated())
                .rejectionReason(vs.getRejectionReason())
                .batteryLevel(vs.getBatteryLevel())
                .wifiRssi(vs.getWifiRssi())
                .sequenceNumber(vs.getSequenceNumber())
                .build();
    }
}
