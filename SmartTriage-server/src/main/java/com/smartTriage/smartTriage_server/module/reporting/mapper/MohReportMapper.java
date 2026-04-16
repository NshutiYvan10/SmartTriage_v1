package com.smartTriage.smartTriage_server.module.reporting.mapper;

import com.smartTriage.smartTriage_server.module.reporting.dto.MohReportResponse;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;

/**
 * Mapper for MohReport entity to response DTO.
 */
public final class MohReportMapper {

    private MohReportMapper() {
    }

    public static MohReportResponse toResponse(MohReport report) {
        MohReportResponse.MohReportResponseBuilder builder = MohReportResponse.builder()
                .id(report.getId())
                .reportType(report.getReportType())
                .reportPeriodStart(report.getReportPeriodStart())
                .reportPeriodEnd(report.getReportPeriodEnd())
                .generatedAt(report.getGeneratedAt())
                .generatedByName(report.getGeneratedByName())
                .status(report.getStatus())
                .submittedAt(report.getSubmittedAt())
                .submittedByName(report.getSubmittedByName())
                .rejectionReason(report.getRejectionReason())
                .totalEdVisits(report.getTotalEdVisits())
                .totalTriaged(report.getTotalTriaged())
                .triageCategoryBreakdown(report.getTriageCategoryBreakdown())
                .averageWaitTimeMinutes(report.getAverageWaitTimeMinutes())
                .mortalityCount(report.getMortalityCount())
                .leftWithoutBeingSeenCount(report.getLeftWithoutBeingSeenCount())
                .admissionCount(report.getAdmissionCount())
                .icuAdmissionCount(report.getIcuAdmissionCount())
                .transferCount(report.getTransferCount())
                .topDiagnoses(report.getTopDiagnoses())
                .topChiefComplaints(report.getTopChiefComplaints())
                .pediatricVisitCount(report.getPediatricVisitCount())
                .malariaPositiveCount(report.getMalariaPositiveCount())
                .sepsisScreenedCount(report.getSepsisScreenedCount())
                .isolationActivatedCount(report.getIsolationActivatedCount())
                .averageLengthOfStayMinutes(report.getAverageLengthOfStayMinutes())
                .reportDataJson(report.getReportDataJson())
                .createdAt(report.getCreatedAt());

        if (report.getHospital() != null) {
            builder.hospitalId(report.getHospital().getId());
            builder.hospitalName(report.getHospital().getName());
        }

        return builder.build();
    }
}
