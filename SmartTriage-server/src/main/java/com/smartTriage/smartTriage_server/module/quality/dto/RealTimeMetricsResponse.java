package com.smartTriage.smartTriage_server.module.quality.dto;

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
public class RealTimeMetricsResponse {

    private UUID hospitalId;
    private String hospitalName;
    private Instant calculatedAt;

    // Current occupancy
    private int currentEdOccupancy;
    private int edCapacity;
    private double edOccupancyPercent;

    // Patients by triage category
    private int redPatients;
    private int orangePatients;
    private int yellowPatients;
    private int greenPatients;
    private int bluePatients;

    // Current wait times
    private double averageCurrentWaitMinutes;
    private int patientsAwaitingTriage;
    private int patientsAwaitingAssessment;

    // Active workload
    private int patientsUnderTreatment;
    private int patientsUnderObservation;
    private int pendingDisposition;

    // Pending items
    private long pendingInvestigations;
    private long activeAlerts;
    private long unacknowledgedAlerts;

    // Pediatric
    private int pediatricPatients;
}
