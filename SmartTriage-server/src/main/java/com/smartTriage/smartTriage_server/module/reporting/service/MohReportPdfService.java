package com.smartTriage.smartTriage_server.module.reporting.service;

import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import com.smartTriage.smartTriage_server.module.reporting.repository.MohReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Renders a {@link MohReport} into a printable / submittable PDF — the statutory
 * Ministry-of-Health ED return for a hospital + period. De-identified aggregate
 * statistics only (no patient identifiers), server-rendered via the shared
 * {@link PdfReport} branded report kit for a consistent SmartTriage house style.
 */
@Service
@RequiredArgsConstructor
public class MohReportPdfService {

    private final MohReportRepository mohReportRepository;

    private static final DateTimeFormatter DT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Kigali"));
    private static final DateTimeFormatter D = DateTimeFormatter
            .ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Africa/Kigali"));

    @Transactional(readOnly = true)
    public byte[] renderById(UUID id, String exportedBy) {
        MohReport report = mohReportRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("MohReport", "id", id));
        return render(report, exportedBy);
    }

    public byte[] render(MohReport r, String exportedBy) {
        boolean national = r.getReportLevel() == com.smartTriage.smartTriage_server.common.enums.ReportLevel.NATIONAL;

        String orgName;
        List<String> orgMeta = new ArrayList<>();
        if (national) {
            orgName = "National / MOH";
            int n = r.getIncludedHospitalCount() != null ? r.getIncludedHospitalCount() : 0;
            orgMeta.add("National rollup — " + n + " hospital" + (n == 1 ? "" : "s"));
        } else {
            orgName = r.getHospital() != null && r.getHospital().getName() != null
                    ? r.getHospital().getName() : "Hospital";
            if (r.getHospital() != null && r.getHospital().getHospitalCode() != null) {
                orgMeta.add("Facility code: " + r.getHospital().getHospitalCode());
            }
        }

        PdfReport report = PdfReport.begin(new PdfReport.Spec(
                "MINISTRY OF HEALTH — EMERGENCY DEPARTMENT RETURN",
                "MOH Report",
                orgName,
                orgMeta,
                exportedBy,
                "Ministry of Health aggregate report"));

        // ── Report metadata ──
        report.subjectHeadline(
                national
                        ? "National ED Return"
                        : (r.getHospital() != null && r.getHospital().getName() != null
                            ? r.getHospital().getName() : "Hospital") + " — ED Return",
                "Period " + fmtD(r.getReportPeriodStart()) + " to " + fmtD(r.getReportPeriodEnd()));

        report.sectionHeader("Report");
        List<PdfReport.KeyVal> meta = new ArrayList<>();
        meta.add(PdfReport.kv("Level", str(r.getReportLevel())));
        meta.add(PdfReport.kv("Report type", str(r.getReportType())));
        meta.add(PdfReport.kv("Status", str(r.getStatus())));
        meta.add(PdfReport.kv("Period", fmtD(r.getReportPeriodStart()) + " to " + fmtD(r.getReportPeriodEnd())));
        meta.add(PdfReport.kv("Generated",
                fmtDt(r.getGeneratedAt())
                        + (r.getGeneratedByName() != null ? " by " + r.getGeneratedByName() : "")));
        if (r.getSubmittedAt() != null) {
            meta.add(PdfReport.kv("Submitted",
                    fmtDt(r.getSubmittedAt())
                            + (r.getSubmittedByName() != null ? " by " + r.getSubmittedByName() : "")));
        }
        report.keyValues(meta);

        // ── Activity — headline counts as stat tiles; the key/values below carry only the
        //    detail NOT already in the tiles (avoids showing the same number twice). ──
        report.sectionHeader("Activity");
        report.statTiles(List.of(
                PdfReport.kv("ED visits", num(r.getTotalEdVisits())),
                PdfReport.kv("Triaged", num(r.getTotalTriaged())),
                PdfReport.kv("Paediatric", num(r.getPediatricVisitCount()))));
        report.keyValues(List.of(
                PdfReport.kv("Average wait time (min)", dec(r.getAverageWaitTimeMinutes())),
                PdfReport.kv("Average length of stay (min)", dec(r.getAverageLengthOfStayMinutes()))));
        if (r.getTriageCategoryBreakdown() != null && !r.getTriageCategoryBreakdown().isBlank()) {
            report.narrative("Triage category breakdown\n" + r.getTriageCategoryBreakdown());
        }

        // ── Disposition ──
        report.sectionHeader("Disposition");
        report.keyValues(List.of(
                PdfReport.kv("Admissions", num(r.getAdmissionCount())),
                PdfReport.kv("ICU admissions", num(r.getIcuAdmissionCount())),
                PdfReport.kv("Transfers", num(r.getTransferCount())),
                PdfReport.kv("Left without being seen", num(r.getLeftWithoutBeingSeenCount())),
                PdfReport.kv("Mortality", num(r.getMortalityCount()))));

        // ── Surveillance & Safety ──
        report.sectionHeader("Surveillance & Safety");
        report.keyValues(List.of(
                PdfReport.kv("Malaria positive", num(r.getMalariaPositiveCount())),
                PdfReport.kv("Sepsis screened", num(r.getSepsisScreenedCount())),
                PdfReport.kv("Isolation activated", num(r.getIsolationActivatedCount()))));
        if (r.getTopDiagnoses() != null && !r.getTopDiagnoses().isBlank()) {
            report.narrative("Top diagnoses\n" + r.getTopDiagnoses());
        }
        if (r.getTopChiefComplaints() != null && !r.getTopChiefComplaints().isBlank()) {
            report.narrative("Top chief complaints\n" + r.getTopChiefComplaints());
        }

        report.spacer(6f);
        report.paragraph("De-identified aggregate statistics — contains no patient identifiers. "
                + "Generated by SmartTriage for Ministry of Health / HMIS submission.", PdfReport.F_META);

        return report.finish();
    }

    private static String str(Object v) { return v != null ? String.valueOf(v) : "—"; }
    private static String num(Integer v) { return v != null ? String.valueOf(v) : "—"; }
    private static String dec(Double v) { return v != null ? String.format("%.1f", v) : "—"; }
    private String fmtDt(java.time.Instant i) { return i != null ? DT.format(i) : "—"; }
    private String fmtD(java.time.Instant i) { return i != null ? D.format(i) : "—"; }
}
