package com.smartTriage.smartTriage_server.module.handover.service;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.handover.entity.HandoverReport;
import com.smartTriage.smartTriage_server.module.handover.repository.HandoverReportRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Renders a {@link HandoverReport} into a professional, printable PDF for
 * physical handover and record-keeping. Hospital letterhead, a patient banner
 * (with an unmistakable unidentified-patient flag), every on-screen section
 * verbatim (nothing summarised away), and a confidentiality + page footer.
 *
 * <p>Server-side (OpenPDF) rather than client-side so the document is
 * deterministic, complete (the medication audit can run long), and archivable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HandoverPdfService {

    private final HandoverReportRepository handoverReportRepository;

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Kigali"));
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.of("Africa/Kigali"));

    /** A rendered PDF plus a safe download filename, both computed inside the load transaction. */
    public record RenderedPdf(byte[] bytes, String filename) {}

    private static final Color NAVY = new Color(11, 74, 110);
    private static final Color GREY = new Color(110, 110, 110);
    private static final Color RED = new Color(190, 30, 45);

    private static final Font H_HOSPITAL = new Font(Font.HELVETICA, 18, Font.BOLD, NAVY);
    private static final Font H_META = new Font(Font.HELVETICA, 8, Font.NORMAL, GREY);
    private static final Font H_TITLE = new Font(Font.HELVETICA, 13, Font.BOLD, Color.BLACK);
    private static final Font H_PATIENT = new Font(Font.HELVETICA, 12, Font.BOLD, Color.BLACK);
    private static final Font H_LABEL = new Font(Font.HELVETICA, 8, Font.BOLD, GREY);
    private static final Font H_SECTION = new Font(Font.HELVETICA, 11, Font.BOLD, NAVY);
    private static final Font H_BODY = new Font(Font.COURIER, 8, Font.NORMAL, Color.BLACK);
    private static final Font H_ALERT = new Font(Font.HELVETICA, 11, Font.BOLD, RED);

    /**
     * Load the report and render it within ONE transaction (so the lazy
     * visit/patient/hospital associations resolve safely), returning the PDF
     * bytes plus a sanitised download filename.
     */
    public RenderedPdf renderDocument(UUID reportId) {
        HandoverReport report = handoverReportRepository.findByIdAndIsActiveTrue(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("HandoverReport", "id", reportId));
        byte[] bytes = render(report);
        String visitNo = report.getVisit() != null && report.getVisit().getVisitNumber() != null
                ? report.getVisit().getVisitNumber() : "report";
        String date = report.getGeneratedAt() != null ? "-" + FILE_TS.format(report.getGeneratedAt()) : "";
        String filename = ("handover-" + visitNo + date + ".pdf").replaceAll("[^A-Za-z0-9._-]", "_");
        return new RenderedPdf(bytes, filename);
    }

    public byte[] render(HandoverReport report) {
        Document doc = new Document(PageSize.A4, 42, 42, 60, 54);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer());
            doc.open();

            renderLetterhead(doc, report.getHospital());
            renderTitleAndPatient(doc, report);
            renderSections(doc, report);

            doc.close();
        } catch (Exception e) {
            log.error("Failed to render handover PDF for report {}: {}", report.getId(), e.getMessage(), e);
            throw new IllegalStateException("Could not generate handover PDF", e);
        }
        return out.toByteArray();
    }

    // ── Letterhead ──────────────────────────────────────────────────

    private void renderLetterhead(Document doc, Hospital h) throws Exception {
        String name = h != null && h.getName() != null ? h.getName() : "Hospital";
        Paragraph hp = new Paragraph(name, H_HOSPITAL);
        hp.setSpacingAfter(1f);
        doc.add(hp);

        if (h != null) {
            List<String> bits = new ArrayList<>();
            if (h.getHospitalCode() != null) bits.add("Code: " + h.getHospitalCode());
            if (h.getTier() != null) bits.add(h.getTier().toString());
            String addr = joinNonBlank(" ", h.getAddress(), h.getCity(), h.getCountry());
            if (!addr.isBlank()) bits.add(addr);
            if (h.getPhoneNumber() != null) bits.add("Tel: " + h.getPhoneNumber());
            if (h.getEmail() != null) bits.add(h.getEmail());
            if (!bits.isEmpty()) {
                Paragraph meta = new Paragraph(String.join("  ·  ", bits), H_META);
                meta.setSpacingAfter(6f);
                doc.add(meta);
            }
        }
        doc.add(rule());
    }

    // ── Title + patient banner ──────────────────────────────────────

    private void renderTitleAndPatient(Document doc, HandoverReport report) throws Exception {
        String type = report.getReportType() != null
                ? report.getReportType().name().replace('_', ' ') : "HANDOVER";
        Paragraph title = new Paragraph("EMERGENCY DEPARTMENT — " + type, H_TITLE);
        title.setSpacingBefore(8f);
        title.setSpacingAfter(6f);
        doc.add(title);

        Visit visit = report.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;

        if (patient != null && patient.isUnidentified()) {
            doc.add(new Paragraph("** UNIDENTIFIED PATIENT — identity unresolved **", H_ALERT));
        }
        String patientName = patient != null
                ? (patient.getFirstName() + " " + patient.getLastName()).trim() : "Unknown";
        doc.add(new Paragraph(patientName, H_PATIENT));

        List<String> ids = new ArrayList<>();
        if (visit != null && visit.getVisitNumber() != null) ids.add("Visit: " + visit.getVisitNumber());
        if (patient != null && patient.getMedicalRecordNumber() != null) ids.add("MRN: " + patient.getMedicalRecordNumber());
        if (!ids.isEmpty()) doc.add(new Paragraph(String.join("   ", ids), H_META));

        // Generation + acknowledgement provenance.
        String gen = "Generated " + (report.getGeneratedAt() != null ? TS.format(report.getGeneratedAt()) : "—");
        if (report.getGeneratedByName() != null) gen += " by " + report.getGeneratedByName();
        doc.add(new Paragraph(gen, H_META));

        String ack = report.isAcknowledged()
                ? "Acknowledged " + (report.getAcknowledgedAt() != null ? TS.format(report.getAcknowledgedAt()) : "")
                  + (report.getReceivedByName() != null ? " by " + report.getReceivedByName() : "")
                : "** NOT YET ACKNOWLEDGED **";
        doc.add(new Paragraph(ack, report.isAcknowledged() ? H_META : H_LABEL));
        doc.add(rule());
    }

    // ── Sections ────────────────────────────────────────────────────

    private void renderSections(Document doc, HandoverReport r) throws Exception {
        // Order mirrors the on-screen report exactly — nothing summarised away.
        addSection(doc, "Patient Summary", r::getPatientSummary);
        addSection(doc, "Pre-Hospital / EMS", r::getPrehospitalSummary);
        addSection(doc, "Presenting Complaint", r::getPresentingComplaint);
        addSection(doc, "Triage Summary", r::getTriageSummary);
        addSection(doc, "Vital Signs Trend", r::getVitalSignsTrend);
        addSection(doc, "Investigations & Results", r::getInvestigationsResults);
        addSection(doc, "Diagnosis Summary", r::getDiagnosisSummary);
        addSection(doc, "Acute Protocols & Critical Events", r::getAcuteProtocols);
        addSection(doc, "Treatment Summary", r::getTreatmentSummary);
        addSection(doc, "Medication Audit Trail", r::getMedicationAudit);
        addSection(doc, "Procedures & Documents", r::getProceduresDocuments);
        addSection(doc, "Active Clinical Alerts", r::getActiveClinicalAlerts);
        addSection(doc, "Outstanding Tasks & Disposition", r::getOutstandingTasks);
        addSection(doc, "Assessment & Plan", r::getPlanOfCare);
        addSection(doc, "ED Timeline", r::getEdTimeline);
        addSection(doc, "Handover Notes", r::getNotes);
    }

    private void addSection(Document doc, String label, Supplier<String> contentSupplier) throws Exception {
        String content = contentSupplier.get();
        if (content == null || content.isBlank()) return;

        Paragraph heading = new Paragraph(label, H_SECTION);
        heading.setSpacingBefore(11f);
        heading.setSpacingAfter(3f);
        heading.setKeepTogether(true);
        doc.add(heading);

        // Monospaced, line-by-line so the section's indentation/formatting is
        // preserved exactly as on screen.
        for (String line : content.split("\n", -1)) {
            Paragraph p = new Paragraph(line.isEmpty() ? Chunk.NEWLINE.getContent() : line, H_BODY);
            p.setLeading(10f);
            doc.add(p);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static Paragraph rule() {
        Paragraph p = new Paragraph("");
        p.setSpacingBefore(2f);
        // A thin underline rule via a bottom border on a 1pt chunk.
        Chunk line = new Chunk(new com.lowagie.text.pdf.draw.LineSeparator(0.6f, 100f, NAVY, Element.ALIGN_CENTER, -2));
        p.add(line);
        p.setSpacingAfter(4f);
        return p;
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(p.trim());
            }
        }
        return sb.toString();
    }

    /** Bottom-of-page confidentiality line + page number. */
    private static final class Footer extends PdfPageEventHelper {
        private final Font f = new Font(Font.HELVETICA, 7, Font.ITALIC, GREY);

        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            PdfContentByte cb = writer.getDirectContent();
            Phrase p = new Phrase(
                    "CONFIDENTIAL — Emergency Department clinical handover · Page " + writer.getPageNumber(), f);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, p,
                    (doc.left() + doc.right()) / 2, doc.bottom() - 20, 0);
        }
    }
}
