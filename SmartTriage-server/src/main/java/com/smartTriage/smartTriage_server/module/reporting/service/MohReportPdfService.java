package com.smartTriage.smartTriage_server.module.reporting.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.PdfWriter;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.reporting.entity.MohReport;
import com.smartTriage.smartTriage_server.module.reporting.repository.MohReportRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Renders a {@link MohReport} into a printable / submittable PDF — the statutory
 * Ministry-of-Health ED return for a hospital + period. De-identified aggregate
 * statistics only (no patient identifiers), server-rendered with OpenPDF.
 */
@Service
@RequiredArgsConstructor
public class MohReportPdfService {

    private final MohReportRepository mohReportRepository;

    private static final DateTimeFormatter DT = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Kigali"));
    private static final DateTimeFormatter D = DateTimeFormatter
            .ofPattern("yyyy-MM-dd").withZone(ZoneId.of("Africa/Kigali"));

    private static final Font H1 = new Font(Font.HELVETICA, 16, Font.BOLD, new Color(15, 23, 42));
    private static final Font H2 = new Font(Font.HELVETICA, 11, Font.BOLD, new Color(2, 132, 199));
    private static final Font LABEL = new Font(Font.HELVETICA, 9, Font.BOLD, new Color(71, 85, 105));
    private static final Font VALUE = new Font(Font.HELVETICA, 9, Font.NORMAL, new Color(15, 23, 42));
    private static final Font MUTED = new Font(Font.HELVETICA, 8, Font.ITALIC, new Color(100, 116, 139));

    @Transactional(readOnly = true)
    public byte[] renderById(UUID id) {
        MohReport report = mohReportRepository.findByIdAndIsActiveTrue(id)
                .orElseThrow(() -> new ResourceNotFoundException("MohReport", "id", id));
        return render(report);
    }

    public byte[] render(MohReport r) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 48, 48, 54, 54);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            boolean national = r.getReportLevel() == com.smartTriage.smartTriage_server.common.enums.ReportLevel.NATIONAL;
            doc.add(p("Ministry of Health — Emergency Department Return", H1));
            if (national) {
                int n = r.getIncludedHospitalCount() != null ? r.getIncludedHospitalCount() : 0;
                doc.add(p("National rollup — " + n + " hospital" + (n == 1 ? "" : "s"), H2));
            } else {
                String hospitalName = r.getHospital() != null && r.getHospital().getName() != null
                        ? r.getHospital().getName() : "Hospital";
                doc.add(p(hospitalName
                        + (r.getHospital() != null && r.getHospital().getHospitalCode() != null
                            ? "  (" + r.getHospital().getHospitalCode() + ")" : ""), H2));
            }
            doc.add(p("Level: " + r.getReportLevel()
                    + "    Report type: " + r.getReportType()
                    + "    Status: " + r.getStatus(), VALUE));
            doc.add(p("Period: " + fmtD(r.getReportPeriodStart()) + " to " + fmtD(r.getReportPeriodEnd()), VALUE));
            doc.add(p("Generated: " + fmtDt(r.getGeneratedAt())
                    + (r.getGeneratedByName() != null ? " by " + r.getGeneratedByName() : ""), MUTED));
            if (r.getSubmittedAt() != null) {
                doc.add(p("Submitted: " + fmtDt(r.getSubmittedAt())
                        + (r.getSubmittedByName() != null ? " by " + r.getSubmittedByName() : ""), MUTED));
            }
            doc.add(spacer());

            section(doc, "Activity");
            kv(doc, "Total ED visits", num(r.getTotalEdVisits()));
            kv(doc, "Total triaged", num(r.getTotalTriaged()));
            kv(doc, "Paediatric visits", num(r.getPediatricVisitCount()));
            kv(doc, "Average wait time (min)", dec(r.getAverageWaitTimeMinutes()));
            kv(doc, "Average length of stay (min)", dec(r.getAverageLengthOfStayMinutes()));
            if (r.getTriageCategoryBreakdown() != null) {
                kv(doc, "Triage category breakdown", r.getTriageCategoryBreakdown());
            }

            section(doc, "Disposition");
            kv(doc, "Admissions", num(r.getAdmissionCount()));
            kv(doc, "ICU admissions", num(r.getIcuAdmissionCount()));
            kv(doc, "Transfers", num(r.getTransferCount()));
            kv(doc, "Left without being seen", num(r.getLeftWithoutBeingSeenCount()));
            kv(doc, "Mortality", num(r.getMortalityCount()));

            section(doc, "Surveillance & Safety");
            kv(doc, "Malaria positive", num(r.getMalariaPositiveCount()));
            kv(doc, "Sepsis screened", num(r.getSepsisScreenedCount()));
            kv(doc, "Isolation activated", num(r.getIsolationActivatedCount()));
            if (r.getTopDiagnoses() != null) kv(doc, "Top diagnoses", r.getTopDiagnoses());
            if (r.getTopChiefComplaints() != null) kv(doc, "Top chief complaints", r.getTopChiefComplaints());

            doc.add(spacer());
            doc.add(p("De-identified aggregate statistics — contains no patient identifiers. "
                    + "Generated by SmartTriage for Ministry of Health / HMIS submission.", MUTED));
            doc.close();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to render MoH report PDF: " + e.getMessage(), e);
        }
        return out.toByteArray();
    }

    private static void section(Document doc, String title) throws Exception {
        Paragraph para = new Paragraph(title, H2);
        para.setSpacingBefore(10);
        para.setSpacingAfter(4);
        doc.add(para);
    }

    private static void kv(Document doc, String label, String value) throws Exception {
        Paragraph para = new Paragraph();
        para.add(new com.lowagie.text.Chunk(label + ":  ", LABEL));
        para.add(new com.lowagie.text.Chunk(value, VALUE));
        para.setSpacingAfter(2);
        doc.add(para);
    }

    private static Paragraph p(String text, Font font) {
        Paragraph para = new Paragraph(text, font);
        para.setAlignment(Element.ALIGN_LEFT);
        return para;
    }

    private static Paragraph spacer() {
        Paragraph para = new Paragraph(" ");
        para.setSpacingAfter(6);
        return para;
    }

    private static String num(Integer v) { return v != null ? String.valueOf(v) : "—"; }
    private static String dec(Double v) { return v != null ? String.format("%.1f", v) : "—"; }
    private String fmtDt(java.time.Instant i) { return i != null ? DT.format(i) : "—"; }
    private String fmtD(java.time.Instant i) { return i != null ? D.format(i) : "—"; }
}
