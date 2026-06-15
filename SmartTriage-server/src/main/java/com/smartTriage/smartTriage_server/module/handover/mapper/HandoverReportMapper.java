package com.smartTriage.smartTriage_server.module.handover.mapper;

import com.smartTriage.smartTriage_server.module.handover.dto.HandoverReportResponse;
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;

public final class HandoverReportMapper {

    private HandoverReportMapper() {
    }

    public static HandoverReportResponse toResponse(HandoverReport report) {
        HandoverReportResponse.HandoverReportResponseBuilder builder = HandoverReportResponse.builder()
                .id(report.getId())
                .reportType(report.getReportType())
                .generatedAt(report.getGeneratedAt())
                .generatedByName(report.getGeneratedByName())
                .patientSummary(report.getPatientSummary())
                .presentingComplaint(report.getPresentingComplaint())
                .triageSummary(report.getTriageSummary())
                .vitalSignsTrend(report.getVitalSignsTrend())
                .investigationsResults(report.getInvestigationsResults())
                .diagnosisSummary(report.getDiagnosisSummary())
                .treatmentSummary(report.getTreatmentSummary())
                .activeClinicalAlerts(report.getActiveClinicalAlerts())
                .outstandingTasks(report.getOutstandingTasks())
                .planOfCare(report.getPlanOfCare())
                .edTimeline(report.getEdTimeline())
                .prehospitalSummary(report.getPrehospitalSummary())
                .acuteProtocols(report.getAcuteProtocols())
                .proceduresDocuments(report.getProceduresDocuments())
                .medicationAudit(report.getMedicationAudit())
                .receivedByName(report.getReceivedByName())
                .receivedAt(report.getReceivedAt())
                .acknowledgedAt(report.getAcknowledgedAt())
                .acknowledged(report.isAcknowledged())
                .notes(report.getNotes())
                .createdAt(report.getCreatedAt());

        if (report.getVisit() != null) {
            builder.visitId(report.getVisit().getId());
            builder.visitNumber(report.getVisit().getVisitNumber());
            if (report.getVisit().getPatient() != null) {
                builder.patientName(
                        report.getVisit().getPatient().getFirstName() + " " +
                                report.getVisit().getPatient().getLastName());
            }
        }

        if (report.getHospital() != null) {
            builder.hospitalId(report.getHospital().getId());
            builder.hospitalName(report.getHospital().getName());
        }

        return builder.build();
    }
}
