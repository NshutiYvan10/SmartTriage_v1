package com.smartTriage.smartTriage_server.module.ems.service;

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
import com.smartTriage.smartTriage_server.module.ems.entity.EmsIntervention;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders an {@link EmsRun} (+ its interventions) into a professional, printable Patient Care
 * Report (PCR) PDF — the standard pre-hospital handoff artifact. Hospital letterhead, a patient
 * banner (with an unmistakable unidentified-patient flag), then the full run: crew, timeline,
 * incident, field triage, on-scene vitals, interventions, transfer of care, and narrative.
 *
 * <p>Server-side (OpenPDF), mirroring {@code HandoverPdfService}. Must be invoked inside an open
 * transaction — it reads the lazy visit / patient / hospital associations.
 */
@Slf4j
@Service
public class EmsPcrPdfService {

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

    /** Safe download filename for a run's PCR. */
    public String filename(EmsRun run) {
        String visit = "field";
        try {
            if (run.getVisit() != null && run.getVisit().getVisitNumber() != null) {
                visit = run.getVisit().getVisitNumber();
            } else if (run.getId() != null) {
                visit = run.getId().toString().substring(0, 8);
            }
        } catch (Exception ignored) { /* lazy/null — fall back to "field" */ }
        String date = run.getDispatchedAt() != null ? "-" + FILE_TS.format(run.getDispatchedAt()) : "";
        return ("pcr-" + visit + date + ".pdf").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    public byte[] render(EmsRun run, List<EmsIntervention> interventions) {
        Document doc = new Document(PageSize.A4, 42, 42, 60, 54);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new Footer());
            doc.open();

            renderLetterhead(doc, safeHospital(run));
            renderTitleAndPatient(doc, run);
            renderSections(doc, run, interventions);

            doc.close();
        } catch (Exception e) {
            log.error("Failed to render EMS PCR PDF for run {}: {}",
                    run.getId(), e.getMessage(), e);
            throw new IllegalStateException("Could not generate EMS PCR PDF", e);
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
            String addr = joinNonBlank(" ", h.getAddress(), h.getCity(), h.getCountry());
            if (!addr.isBlank()) bits.add(addr);
            if (h.getPhoneNumber() != null) bits.add("Tel: " + h.getPhoneNumber());
            if (!bits.isEmpty()) {
                Paragraph meta = new Paragraph(String.join("  ·  ", bits), H_META);
                meta.setSpacingAfter(6f);
                doc.add(meta);
            }
        }
        doc.add(rule());
    }

    // ── Title + patient banner ──────────────────────────────────────
    private void renderTitleAndPatient(Document doc, EmsRun run) throws Exception {
        Paragraph title = new Paragraph("PRE-HOSPITAL — PATIENT CARE REPORT (PCR)", H_TITLE);
        title.setSpacingBefore(8f);
        title.setSpacingAfter(6f);
        doc.add(title);

        Patient patient = safePatient(run);
        if (patient != null && patient.isUnidentified()) {
            doc.add(new Paragraph("** UNIDENTIFIED PATIENT — identity unresolved **", H_ALERT));
        }
        String patientName = patient != null
                ? (nullToEmpty(patient.getFirstName()) + " " + nullToEmpty(patient.getLastName())).trim()
                : "";
        if (patientName.isBlank()) patientName = "Unidentified field patient";
        doc.add(new Paragraph(patientName, H_PATIENT));

        // Demographics: prefer the linked patient record; fall back to what the crew captured on scene.
        List<String> demo = new ArrayList<>();
        if (run.getPatientAgeYears() != null) demo.add("Age: " + run.getPatientAgeYears());
        if (run.getPatientSex() != null && !run.getPatientSex().isBlank()) demo.add("Sex: " + run.getPatientSex());
        if (patient != null && patient.getNationalId() != null) demo.add("National ID: " + patient.getNationalId());
        if (patient != null && patient.getMedicalRecordNumber() != null) demo.add("MRN: " + patient.getMedicalRecordNumber());
        if (!demo.isEmpty()) doc.add(new Paragraph(String.join("   ", demo), H_META));

        Visit visit = safeVisit(run);
        if (visit != null && visit.getVisitNumber() != null) {
            doc.add(new Paragraph("ED Visit: " + visit.getVisitNumber(), H_META));
        }
        String gen = "Generated " + TS.format(Instant.now());
        if (run.getParamedicName() != null) gen += " · crew: " + run.getParamedicName();
        doc.add(new Paragraph(gen, H_LABEL));
        doc.add(rule());
    }

    // ── Sections ────────────────────────────────────────────────────
    private void renderSections(Document doc, EmsRun run, List<EmsIntervention> interventions) throws Exception {
        addSection(doc, "Run & Crew", buildRunCrew(run));
        addSection(doc, "Timeline", buildTimeline(run));
        addSection(doc, "Incident", buildIncident(run));
        addSection(doc, "Field Triage", buildFieldTriage(run));
        addSection(doc, "On-Scene Vital Signs", buildVitals(run));
        addSection(doc, "Interventions", buildInterventions(interventions));
        addSection(doc, "Transfer of Care", buildTransferOfCare(run));
        addSection(doc, "Narrative", run.getNotes());
    }

    private String buildRunCrew(EmsRun run) {
        StringBuilder sb = new StringBuilder();
        kv(sb, "Service", run.getService() != null ? run.getService().name() : null);
        kv(sb, "Unit callsign", run.getUnitCallsign());
        kv(sb, "Paramedic", run.getParamedicName());
        kv(sb, "Status", run.getStatus() != null ? run.getStatus().name() : null);
        kv(sb, "Priority transport (lights)", run.isLightsActive() ? "ACTIVE" : "No");
        if (run.getCancelReason() != null && !run.getCancelReason().isBlank()) {
            kv(sb, "Cancelled reason", run.getCancelReason());
        }
        return sb.toString();
    }

    private String buildTimeline(EmsRun run) {
        StringBuilder sb = new StringBuilder();
        kv(sb, "Dispatched", ts(run.getDispatchedAt()));
        kv(sb, "Scene arrived", ts(run.getSceneArrivedAt()));
        kv(sb, "Scene left", ts(run.getSceneLeftAt()));
        kv(sb, "ED arrived", ts(run.getEdArrivedAt()));
        kv(sb, "Handed off", ts(run.getHandedOffAt()));
        return sb.toString();
    }

    private String buildIncident(EmsRun run) {
        StringBuilder sb = new StringBuilder();
        kv(sb, "Location", run.getIncidentLocation());
        kv(sb, "Mechanism", run.getMechanism());
        kv(sb, "History", run.getHistorySummary());
        kv(sb, "Injuries observed", run.getInjuriesObserved());
        return sb.toString();
    }

    private String buildFieldTriage(EmsRun run) {
        StringBuilder sb = new StringBuilder();
        kv(sb, "Category", run.getFieldTriageCategory());
        kv(sb, "TEWS", run.getFieldTewsScore() != null ? run.getFieldTewsScore().toString() : null);
        kv(sb, "Reason", run.getFieldTriageReason());
        if (Boolean.TRUE.equals(run.getFieldTriageIsChild())) kv(sb, "Pediatric", "Yes");
        return sb.toString();
    }

    private String buildVitals(EmsRun run) {
        StringBuilder sb = new StringBuilder();
        kv(sb, "GCS", num(run.getFieldGcs()));
        kv(sb, "Resp rate", num(run.getFieldRespRate()));
        kv(sb, "Heart rate", num(run.getFieldHr()));
        String bp = run.getFieldSbp() != null || run.getFieldDbp() != null
                ? (num(run.getFieldSbp()) + "/" + num(run.getFieldDbp())) : null;
        kv(sb, "Blood pressure", bp != null && !bp.equals("/") ? bp + " mmHg" : null);
        kv(sb, "SpO2", run.getFieldSpo2() != null ? run.getFieldSpo2() + " %" : null);
        kv(sb, "Temperature", run.getFieldTemp() != null ? run.getFieldTemp() + " °C" : null);
        kv(sb, "Glucose", run.getFieldGlucose() != null ? run.getFieldGlucose() + " mmol/L" : null);
        return sb.toString();
    }

    private String buildInterventions(List<EmsIntervention> interventions) {
        if (interventions == null || interventions.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (EmsIntervention iv : interventions) {
            List<String> parts = new ArrayList<>();
            if (iv.getType() != null) parts.add(iv.getType().name());
            if (iv.getDetail() != null && !iv.getDetail().isBlank()) parts.add(iv.getDetail());
            if (iv.getDose() != null && !iv.getDose().isBlank()) parts.add("dose " + iv.getDose());
            if (iv.getRoute() != null && !iv.getRoute().isBlank()) parts.add(iv.getRoute());
            if (iv.getGivenAt() != null) parts.add("@ " + ts(iv.getGivenAt()));
            if (iv.getGivenByName() != null && !iv.getGivenByName().isBlank()) parts.add("by " + iv.getGivenByName());
            if (iv.getOutcome() != null && !iv.getOutcome().isBlank()) parts.add("→ " + iv.getOutcome());
            sb.append("• ").append(String.join("  ·  ", parts)).append('\n');
            if (iv.getNotes() != null && !iv.getNotes().isBlank()) {
                sb.append("    ").append(iv.getNotes()).append('\n');
            }
        }
        return sb.toString();
    }

    private String buildTransferOfCare(EmsRun run) {
        StringBuilder sb = new StringBuilder();
        kv(sb, "Handed off to", run.getHandedOffToName());
        kv(sb, "Acknowledgement", run.getHandoverAcknowledgementText());
        if (run.getPreArrivalAckedByName() != null) {
            kv(sb, "Pre-arrival acknowledged by", run.getPreArrivalAckedByName()
                    + (run.getPreArrivalAckedAt() != null ? " (" + ts(run.getPreArrivalAckedAt()) + ")" : ""));
        }
        return sb.toString();
    }

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

    // ── Helpers ─────────────────────────────────────────────────────
    private static void kv(StringBuilder sb, String label, String value) {
        if (value != null && !value.isBlank()) sb.append(label).append(": ").append(value).append('\n');
    }

    private static String num(Integer v) { return v != null ? v.toString() : ""; }
    private static String ts(Instant i) { return i != null ? TS.format(i) : null; }
    private static String nullToEmpty(String s) { return s != null ? s : ""; }

    private static Hospital safeHospital(EmsRun run) {
        try { return run.getHospital(); } catch (Exception e) { return null; }
    }
    private static Visit safeVisit(EmsRun run) {
        try { return run.getVisit(); } catch (Exception e) { return null; }
    }
    private static Patient safePatient(EmsRun run) {
        try {
            Visit v = run.getVisit();
            return v != null ? v.getPatient() : null;
        } catch (Exception e) { return null; }
    }

    private static Paragraph rule() {
        Paragraph p = new Paragraph("");
        p.setSpacingBefore(2f);
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
                    "CONFIDENTIAL — EMS patient care report · Page " + writer.getPageNumber(), f);
            ColumnText.showTextAligned(cb, Element.ALIGN_CENTER, p,
                    (doc.left() + doc.right()) / 2, doc.bottom() - 20, 0);
        }
    }
}
