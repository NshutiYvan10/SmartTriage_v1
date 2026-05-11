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
        return DeviceSessionResponse.builder()
                .id(session.getId())
                .deviceId(session.getDevice().getId())
                .deviceName(session.getDevice().getDeviceName())
                .deviceSerialNumber(session.getDevice().getSerialNumber())
                .visitId(session.getVisit().getId())
                .visitNumber(session.getVisit().getVisitNumber())
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
