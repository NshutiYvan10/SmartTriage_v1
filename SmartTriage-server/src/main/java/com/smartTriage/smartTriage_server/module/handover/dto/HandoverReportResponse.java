package com.smartTriage.smartTriage_server.module.handover.dto;

import com.smartTriage.smartTriage_server.common.enums.HandoverReportType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HandoverReportResponse {

    private UUID id;
    private UUID visitId;
    private String visitNumber;
    private String patientName;
    private UUID hospitalId;
    private String hospitalName;
    private HandoverReportType reportType;
    private Instant generatedAt;
    private String generatedByName;

    // Content sections
    private String patientSummary;
    private String presentingComplaint;
    private String triageSummary;
    private String vitalSignsTrend;
    private String investigationsResults;
    private String diagnosisSummary;
    private String treatmentSummary;
    private String activeClinicalAlerts;
    private String outstandingTasks;
    private String planOfCare;
    private String edTimeline;
    /** V67 — full medication audit trail (orders, doses, misses, reasons). */
    private String medicationAudit;

    // Acknowledgment
    private String receivedByName;
    private Instant receivedAt;
    private Instant acknowledgedAt;
    private boolean acknowledged;
    private String notes;

    private Instant createdAt;
}
