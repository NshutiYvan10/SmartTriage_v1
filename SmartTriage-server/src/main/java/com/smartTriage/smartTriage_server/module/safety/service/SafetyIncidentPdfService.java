package com.smartTriage.smartTriage_server.module.safety.service;

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
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.safety.entity.SafetyIncident;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Renders a {@link SafetyIncident} into a printable single-incident report PDF — the formal record
 * for the quality/governance file: classification, timeline, narrative, investigation, root cause,
 * corrective action, and closure. Mirrors {@code HandoverPdfService}; must run in an open
 * transaction (reads the lazy hospital association).
 */
@Slf4j
@Service
public class SafetyIncidentPdfService {

    /** A rendered PDF plus a safe download filename, both computed inside the load transaction. */
    public record RenderedPdf(byte[] bytes, String filename) {}

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Kigali"));

    private static final Color NAVY = new Color(11, 74, 110);
    private static final Color GREY = new Color(110, 110, 110);
    private static final Color RED = new Color(190, 30, 45);

    private static final Font H_HOSPITAL = new Font(Font.HELVETICA, 18, Font.BOLD, NAVY);
    private static final Font H_META = new Font(Font.HELVETICA, 8, Font.NORMAL, GREY);
    private static final Font H_TITLE = new Font(Font.HELVETICA, 13, Font.BOLD, Color.BLACK);
    private static final Font H_SECTION = new Font(Font.HELVETICA, 11, Font.BOLD, NAVY);
    private static final Font H_BODY = new Font(Font.COURIER, 8, Font.NORMAL, Color.BLACK);
    private static final Font H_ALERT = new Font(Font.HELVETICA, 11, Font.BOLD, RED);

    public String filename(SafetyIncident i) {
        String n = i.getIncidentNumber() != null ? i.getIncidentNumber() : "incident";
        return ("safety-incident-" + n + ".pdf").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public byte[] render(SafetyIncident i) {
        Document doc = new Document(PageSize.A4, 42, 42, 60, 54);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer());
            doc.open();

            Hospital h = safeHospital(i);
            doc.add(new Paragraph(h != null && h.getName() != null ? h.getName() : "Hospital", H_HOSPITAL));
            doc.add(rule());

            Paragraph title = new Paragraph("PATIENT SAFETY INCIDENT REPORT", H_TITLE);
            title.setSpacingBefore(8f);
            title.setSpacingAfter(4f);
            doc.add(title);
            if (Boolean.TRUE.equals(i.getPatientHarmed())) {
                doc.add(new Paragraph("** PATIENT HARM REPORTED **", H_ALERT));
            }
            doc.add(new Paragraph(
                    "No. " + nz(i.getIncidentNumber()) + "   ·   " + nz(name(i.getIncidentType()))
                    + "   ·   Severity " + nz(name(i.getSeverity())) + "   ·   " + nz(name(i.getStatus())),
                    H_META));
            doc.add(rule());

            addSection(doc, "Classification", build(
                    kv("Type", name(i.getIncidentType())),
                    kv("Severity", name(i.getSeverity())),
                    kv("Status", name(i.getStatus())),
                    kv("Patient harmed", i.getPatientHarmed() != null ? (i.getPatientHarmed() ? "Yes" : "No") : null),
                    kv("Anonymous report", i.isAnonymous() ? "Yes" : null)));

            addSection(doc, "When & where", build(
                    kv("Occurred", ts(i.getIncidentDateTime())),
                    kv("Location", i.getLocationInHospital()),
                    kv("Reported", ts(i.getReportedAt())),
                    kv("Reported by", i.getReportedByName()),
                    kv("Reporter role", i.getReportedByRole())));

            addSection(doc, "Description", i.getDescription());
            addSection(doc, "Contributing factors", i.getContributingFactors());
            addSection(doc, "Immediate actions", i.getImmediateActions());
            addSection(doc, "Involved staff", i.getInvolvedStaffNames());

            addSection(doc, "Investigation", build(
                    kv("Investigator", i.getInvestigatorName()),
                    kv("Started", ts(i.getInvestigationStartedAt())),
                    kv("Completed", ts(i.getInvestigationCompletedAt())),
                    kv("Root cause category", i.getRootCauseCategory())));
            addSection(doc, "Root cause analysis", i.getRootCauseAnalysis());

            addSection(doc, "Corrective action", build(
                    kv("Action", i.getCorrectiveAction()),
                    kv("Owner", i.getCorrectiveActionOwner()),
                    kv("Deadline", ts(i.getCorrectiveActionDeadline())),
                    kv("Completed", ts(i.getCorrectiveActionCompletedAt()))));
            addSection(doc, "Preventive measures", i.getPreventiveMeasures());

            addSection(doc, "Closure", build(
                    kv("Closed", ts(i.getClosedAt())),
                    kv("Closed by", i.getClosedByName())));
            addSection(doc, "Lessons learned", i.getLessonsLearned());
            addSection(doc, "Notes", i.getNotes());

            doc.close();
        } catch (Exception e) {
            log.error("Failed to render safety-incident PDF for {}: {}", i.getId(), e.getMessage(), e);
            throw new IllegalStateException("Could not generate safety-incident PDF", e);
        }
        return out.toByteArray();
    }

    // ── helpers ──
    private void addSection(Document doc, String label, String content) throws Exception {
        if (content == null || content.isBlank()) return;
        Paragraph heading = new Paragraph(label, H_SECTION);
        heading.setSpacingBefore(11f);
        heading.setSpacingAfter(3f);
        heading.setKeepTogether(true);
        doc.add(heading);
        for (String line : content.split("\n", -1)) {
            Paragraph p = new Paragraph(line.isEmpty() ? Chunk.NEWLINE.getContent() : line, H_BODY);
            p.setLeading(10f);
            doc.add(p);
        }
    }

    private static String build(String... lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) if (l != null) sb.append(l).append('\n');
        return sb.toString();
    }
    private static String kv(String label, String value) {
        return (value != null && !value.isBlank()) ? label + ": " + value : null;
    }
    private static String name(Enum<?> e) { return e != null ? e.name() : null; }
    private static String nz(String s) { return s != null ? s : "—"; }
    private static String ts(Instant i) { return i != null ? TS.format(i) : null; }

    private static Hospital safeHospital(SafetyIncident i) {
        try { return i.getHospital(); } catch (Exception e) { return null; }
    }

    private static Paragraph rule() {
        Paragraph p = new Paragraph("");
        p.setSpacingBefore(2f);
        p.add(new Chunk(new com.lowagie.text.pdf.draw.LineSeparator(0.6f, 100f, NAVY, Element.ALIGN_CENTER, -2)));
        p.setSpacingAfter(4f);
        return p;
    }

    private static final class Footer extends PdfPageEventHelper {
        private final Font f = new Font(Font.HELVETICA, 7, Font.ITALIC, GREY);
        @Override
        public void onEndPage(PdfWriter writer, Document doc) {
            PdfContentByte cb = writer.getDirectContent();
            Phrase p = new Phrase("CONFIDENTIAL — patient safety incident report · Page " + writer.getPageNumber(), f);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, p,
                    (doc.left() + doc.right()) / 2, doc.bottom() - 20, 0);
        }
    }
}
