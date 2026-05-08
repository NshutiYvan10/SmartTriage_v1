package com.smartTriage.smartTriage_server.module.iot.service;

import com.smartTriage.smartTriage_server.common.enums.EdZone;
import com.smartTriage.smartTriage_server.module.alert.dto.ClinicalAlertResponse;
import com.smartTriage.smartTriage_server.module.clinical.dto.ClinicalNoteResponse;
import com.smartTriage.smartTriage_server.module.iot.dto.VitalStreamResponse;
import com.smartTriage.smartTriage_server.module.iot.mapper.IoTMapper;
import com.smartTriage.smartTriage_server.module.iot.entity.VitalStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * RealTimeEventPublisher — pushes IoT events to frontend dashboards via
 * WebSocket.
 *
 * Publishes to STOMP topics that frontend clients subscribe to:
 * /topic/vitals/{visitId} — real-time vital readings
 * /topic/alerts/{hospitalId} — hospital-wide clinical alerts
 * /topic/alerts/{hospitalId}/{zone} — zone-scoped doctor notifications
 * /topic/alerts/user/{userId} — user-targeted notifications
 * /topic/devices/{hospitalId} — device status changes
 * /topic/triage/{visitId} — triage changes
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RealTimeEventPublisher {

    private final SimpMessagingTemplate messagingTemplate;

    /**
     * Push a vital reading to the visit's dashboard topic.
     */
    public void publishVitalReading(VitalStream stream) {
        UUID visitId = stream.getVisit().getId();
        VitalStreamResponse response = IoTMapper.toResponse(stream);

        messagingTemplate.convertAndSend("/topic/vitals/" + visitId, (Object) response);
        log.trace("Published vital reading to /topic/vitals/{}", visitId);
    }

    /**
     * Push an alert notification to the hospital's alert topic.
     */
    public void publishAlert(UUID hospitalId, Map<String, Object> alertData) {
        messagingTemplate.convertAndSend("/topic/alerts/" + hospitalId, (Object) alertData);
        log.debug("Published alert to /topic/alerts/{}", hospitalId);
    }

    /**
     * Push a clinical alert to the hospital's alert topic (typed
     * ClinicalAlertResponse).
     * Frontend can parse this directly as ClinicalAlertResponse.
     */
    public void publishHospitalAlert(UUID hospitalId, ClinicalAlertResponse alertResponse) {
        messagingTemplate.convertAndSend("/topic/alerts/" + hospitalId, (Object) alertResponse);
        log.debug("Published typed alert to /topic/alerts/{}", hospitalId);
    }

    /**
     * Push a device status change to the hospital's devices topic.
     */
    public void publishDeviceStatusChange(UUID hospitalId, Map<String, Object> deviceData) {
        messagingTemplate.convertAndSend("/topic/devices/" + hospitalId, (Object) deviceData);
        log.debug("Published device status to /topic/devices/{}", hospitalId);
    }

    /**
     * Push a patient trend classification change (WORSENING / STABLE / IMPROVING)
     * to the visit topic. Monitoring dashboards subscribe here and update the
     * badge + counter without recomputing locally.
     */
    public void publishTrendChange(UUID visitId, Map<String, Object> trendData) {
        messagingTemplate.convertAndSend("/topic/trend/" + visitId, (Object) trendData);
        log.debug("Published trend change to /topic/trend/{}", visitId);
    }

    /**
     * Push a triage change to the visit's triage topic.
     */
    public void publishTriageChange(UUID visitId, Map<String, Object> triageData) {
        messagingTemplate.convertAndSend("/topic/triage/" + visitId, (Object) triageData);
        log.debug("Published triage change to /topic/triage/{}", visitId);
    }

    // ====================================================================
    // ZONE-AWARE NOTIFICATION TOPICS
    // ====================================================================

    /**
     * Push an alert to a specific ED zone topic — zone doctors and nurses subscribe
     * here.
     */
    public void publishZoneAlert(UUID hospitalId, EdZone zone, ClinicalAlertResponse alertResponse) {
        String topic = "/topic/alerts/" + hospitalId + "/" + zone.name();
        messagingTemplate.convertAndSend(topic, (Object) alertResponse);
        log.info("Published zone alert to {} (Tier {}, {})", topic, alertResponse.getEscalationTier(),
                alertResponse.getSeverity());
    }

    /**
     * Push an alert to a specific user — for targeted doctor notifications.
     */
    public void publishUserAlert(UUID userId, ClinicalAlertResponse alertResponse) {
        String topic = "/topic/alerts/user/" + userId;
        messagingTemplate.convertAndSend(topic, (Object) alertResponse);
        log.info("Published user alert to {} (Tier {})", topic, alertResponse.getEscalationTier());
    }

    // ====================================================================
    // BED OCCUPANCY TOPICS
    // ====================================================================

    /**
     * Push a bed-occupancy change to the hospital-wide beds topic.
     * Fired when a bed's status or occupant changes (placement, transfer,
     * discharge, cleaning, out-of-service). The frontend bed-grid view
     * subscribes here and re-fetches the affected zone on any event.
     */
    public void publishBedChange(UUID hospitalId, Map<String, Object> bedData) {
        String topic = "/topic/beds/" + hospitalId;
        messagingTemplate.convertAndSend(topic, (Object) bedData);
        log.debug("Published bed change to {}", topic);
    }

    // ====================================================================
    // CLINICAL NOTE TOPICS
    // ====================================================================

    /**
     * Push a clinical note event to the visit's notes topic. Fired on both
     * initial creation and supersede (correction) events so any subscribed
     * timeline / handover view can append the new row in real time without a
     * re-fetch. Subscribers can distinguish a correction by inspecting the
     * payload's {@code supersedesId} field — non-null indicates this note
     * supersedes an earlier one.
     */
    public void publishClinicalNote(UUID visitId, ClinicalNoteResponse note) {
        String topic = "/topic/visit/" + visitId + "/notes";
        messagingTemplate.convertAndSend(topic, (Object) note);
        log.debug("Published clinical note to {} (id:{}, supersedes:{})",
                topic, note.getId(), note.getSupersedesId());
    }

    // ====================================================================
    // LAB ORDER TOPICS
    // ====================================================================

    /**
     * Push a lab-order event to the hospital's lab topic. Fired on
     * order creation and on every workflow transition so the lab
     * tech's inbox / in-progress columns stay live without polling.
     * The payload is the LabOrderResponse — subscribers replace the
     * row in their cached list by id.
     */
    public void publishLabOrder(UUID hospitalId, Object labOrderResponse) {
        String topic = "/topic/lab/" + hospitalId;
        messagingTemplate.convertAndSend(topic, labOrderResponse);
        log.debug("Published lab-order event to {}", topic);
    }
}
