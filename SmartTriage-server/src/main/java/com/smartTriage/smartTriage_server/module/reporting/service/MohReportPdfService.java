package com.smartTriage.smartTriage_server.module.reporting.service;

import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import com.smartTriage.smartTriage_server.module.reporting.repository.MohReportRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    private static final ObjectMapper JSON = new ObjectMapper();

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

        PdfReport report = PdfReport.beginLedger(new PdfReport.Spec(
                "Emergency Department Return",
                "MOH Report",
                orgName,
                orgMeta,
                exportedBy,
                "MOH AGGREGATE RETURN",
                "Ministry of Health · statutory"));

        // ── Report metadata: the #1b bordered ID table ──
        List<PdfReport.LedgerCell> id = new ArrayList<>();
        id.add(PdfReport.lcell("Return", national
                ? "National ED Return"
                : (r.getHospital() != null && r.getHospital().getName() != null
                    ? r.getHospital().getName() : "Hospital") + " — ED Return"));
        id.add(PdfReport.lcell("Level", str(r.getReportLevel())));
        id.add(PdfReport.lcell("Report type", str(r.getReportType())));
        id.add(PdfReport.lcell("Status", str(r.getStatus())));
        id.add(PdfReport.lcellFullMono("Period", fmtD(r.getReportPeriodStart()) + " to " + fmtD(r.getReportPeriodEnd())));
        id.add(PdfReport.lcellFull("Generated",
                fmtDt(r.getGeneratedAt()) + (r.getGeneratedByName() != null ? " by " + r.getGeneratedByName() : "")));
        if (r.getSubmittedAt() != null) {
            id.add(PdfReport.lcellFull("Submitted",
                    fmtDt(r.getSubmittedAt()) + (r.getSubmittedByName() != null ? " by " + r.getSubmittedByName() : "")));
        }
        report.ledgerIdTable(id);

        int s = 0;

        // ── 0n ACTIVITY ──
        report.ledgerSection(sec(++s), "Activity");
        List<PdfReport.LedgerCell> activity = new ArrayList<>();
        activity.add(PdfReport.lcellMono("ED visits", num(r.getTotalEdVisits())));
        activity.add(PdfReport.lcellMono("Triaged", num(r.getTotalTriaged())));
        activity.add(PdfReport.lcellMono("Paediatric", num(r.getPediatricVisitCount())));
        activity.add(PdfReport.lcellMono("Average wait time (min)", dec(r.getAverageWaitTimeMinutes())));
        activity.add(PdfReport.lcellMono("Average length of stay (min)", dec(r.getAverageLengthOfStayMinutes())));
        String triageMix = formatMap(r.getTriageCategoryBreakdown());
        if (triageMix != null) activity.add(PdfReport.lcellFull("Triage category breakdown", triageMix));
        report.ledgerKv(activity);

        // ── 0n DISPOSITION ──
        report.ledgerSection(sec(++s), "Disposition");
        report.ledgerKv(List.of(
                PdfReport.lcellMono("Admissions", num(r.getAdmissionCount())),
                PdfReport.lcellMono("ICU admissions", num(r.getIcuAdmissionCount())),
                PdfReport.lcellMono("Transfers", num(r.getTransferCount())),
                PdfReport.lcellMono("Left without being seen", num(r.getLeftWithoutBeingSeenCount())),
                PdfReport.lcellMono("Mortality", num(r.getMortalityCount()))));

        // ── 0n SURVEILLANCE & SAFETY ──
        report.ledgerSection(sec(++s), "Surveillance & Safety");
        report.ledgerKv(List.of(
                PdfReport.lcellMono("Malaria positive", num(r.getMalariaPositiveCount())),
                PdfReport.lcellMono("Sepsis screened", num(r.getSepsisScreenedCount())),
                PdfReport.lcellMono("Isolation activated", num(r.getIsolationActivatedCount()))));

        // ── 0n IDSR Notifiable Diseases (V111) — the statutory Rwanda return ──
        report.ledgerSection(sec(++s), "IDSR Notifiable Diseases");
        List<PdfReport.LedgerCell> idsr = new ArrayList<>();
        idsr.add(PdfReport.lcellMono("Notifiable cases detected", num(r.getNotifiableDiseaseCount())));
        idsr.add(PdfReport.lcellMono("Reported to RBC", num(r.getPublicHealthNotifiedCount())));
        String byDisease = itemCountLine(r.getNotifiableDiseaseBreakdown());
        if (byDisease != null) idsr.add(PdfReport.lcellFull("Cases by disease", byDisease));
        report.ledgerKv(idsr);

        // ── 0n Clinical profile — top diagnoses / chief complaints ──
        List<String[]> topDx = itemCountRows(r.getTopDiagnoses());
        List<String[]> topCc = itemCountRows(r.getTopChiefComplaints());
        if (!topDx.isEmpty() || !topCc.isEmpty()) {
            report.ledgerSection(sec(++s), "Clinical profile");
            if (!topDx.isEmpty()) {
                report.ledgerDataTable(new String[]{"TOP DIAGNOSIS", "CASES"},
                        new float[]{82f, 18f}, topDx, new boolean[]{false, true});
            }
            if (!topCc.isEmpty()) {
                if (!topDx.isEmpty()) report.spacer(5f);
                report.ledgerDataTable(new String[]{"TOP CHIEF COMPLAINT", "CASES"},
                        new float[]{82f, 18f}, topCc, new boolean[]{false, true});
            }
        }

        report.spacer(6f);
        report.paragraph("De-identified aggregate statistics — contains no patient identifiers. "
                + "Generated by SmartTriage for Ministry of Health / HMIS submission.", PdfReport.F_META);

        return report.finish();
    }

    /** Two-digit #1b section number: 1 → "01". */
    private static String sec(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    /** {"K":v,...} breakdown map → "K v   ·   K v"; blank / "{}" / unparseable-empty → null. */
    private static String formatMap(String raw) {
        if (raw == null || raw.isBlank() || "{}".equals(raw.trim())) return null;
        try {
            Map<String, Object> m = JSON.readValue(raw,
                    new TypeReference<java.util.LinkedHashMap<String, Object>>() {});
            if (m.isEmpty()) return null;
            List<String> parts = new ArrayList<>();
            m.forEach((k, v) -> parts.add(k + " " + v));
            return String.join("   ·   ", parts);
        } catch (Exception e) {
            return raw.trim();
        }
    }

    /** [{"item":..,"count":..},...] → [item, count] rows; blank / "[]" / unparseable → empty. */
    private static List<String[]> itemCountRows(String raw) {
        List<String[]> rows = new ArrayList<>();
        if (raw == null || raw.isBlank() || "[]".equals(raw.trim())) return rows;
        try {
            List<Map<String, Object>> list = JSON.readValue(raw,
                    new TypeReference<List<Map<String, Object>>>() {});
            for (Map<String, Object> it : list) {
                Object item = it.get("item");
                if (item == null) item = it.get("name");
                if (item == null) item = it.get("disease");
                Object cnt = it.get("count");
                if (cnt == null) cnt = it.get("cases");
                rows.add(new String[]{item != null ? String.valueOf(item) : "—",
                        cnt != null ? String.valueOf(cnt) : "—"});
            }
        } catch (Exception e) {
            // leave empty — the caller omits the block rather than dumping raw JSON
        }
        return rows;
    }

    /** [{"item":..,"count":..},...] → "item count   ·   item count"; empty → null. */
    private static String itemCountLine(String raw) {
        List<String[]> rows = itemCountRows(raw);
        if (rows.isEmpty()) return null;
        List<String> parts = new ArrayList<>();
        for (String[] row : rows) parts.add(row[0] + " " + row[1]);
        return String.join("   ·   ", parts);
    }

    private static String str(Object v) { return v != null ? String.valueOf(v) : "—"; }
    private static String num(Integer v) { return v != null ? String.valueOf(v) : "—"; }
    private static String dec(Double v) { return v != null ? String.format("%.1f", v) : "—"; }
    private String fmtDt(java.time.Instant i) { return i != null ? DT.format(i) : "—"; }
    private String fmtD(java.time.Instant i) { return i != null ? D.format(i) : "—"; }
}
