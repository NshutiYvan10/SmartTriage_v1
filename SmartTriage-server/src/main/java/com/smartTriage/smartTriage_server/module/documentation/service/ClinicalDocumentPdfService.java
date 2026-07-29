package com.smartTriage.smartTriage_server.module.documentation.service;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.common.report.PdfReport.KeyVal;
import com.smartTriage.smartTriage_server.module.documentation.entity.ClinicalDocument;
import com.smartTriage.smartTriage_server.module.documentation.repository.ClinicalDocumentRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.smartTriage.smartTriage_server.common.report.PdfReport.kv;

/**
 * Renders a single {@link ClinicalDocument} as a professional, printable
 * PDF using the shared {@link PdfReport} toolkit — the same branded
 * masthead, patient banner, sectioning, and confidentiality footer the
 * MOH / operational / lab / EMS / safety reports use.
 *
 * <p>The generated documents (handover, discharge, …) store their body as
 * a lightly-structured plain text: {@code === TITLE ===} banners,
 * {@code --- SECTION ---} headings, and {@code Label: value} fields. We
 * parse that back into real PDF sections, key/value tables, and prose so
 * the output reads as a clinical document rather than a text dump — the
 * on-screen "DocumentPaper" preview mirrors the same structure.
 */
@Service
@RequiredArgsConstructor
public class ClinicalDocumentPdfService {

    private final ClinicalDocumentRepository repository;

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm").withZone(KIGALI);
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private static final Pattern TITLE = Pattern.compile("^={2,}\\s*(.*?)\\s*={2,}$");
    private static final Pattern SECTION = Pattern.compile("^-{2,}\\s*(.*?)\\s*-{2,}$");
    private static final Pattern KEYVAL = Pattern.compile("^\\s*([A-Za-z][A-Za-z0-9 /()#.&_-]{0,44}):\\s+(.*\\S)\\s*$");

    public record RenderedPdf(byte[] bytes, String filename) {}

    @Transactional(readOnly = true)
    public RenderedPdf render(UUID documentId, String exportedByName) {
        ClinicalDocument d = repository.findByIdAndIsActiveTrue(documentId)
                .orElseThrow(() -> new ResourceNotFoundException("ClinicalDocument", "id", documentId));

        Visit visit = d.getVisit();
        Patient p = visit != null ? visit.getPatient() : null;
        Hospital h = visit != null ? visit.getHospital() : null;

        String orgName = h != null && notBlank(h.getName()) ? h.getName() : "SmartTriage";
        List<String> orgMeta = new ArrayList<>();
        if (h != null) {
            String loc = joinNonBlank(", ", h.getAddress(), h.getCity());
            if (notBlank(loc)) orgMeta.add(loc);
            if (notBlank(h.getPhoneNumber())) orgMeta.add("Tel: " + h.getPhoneNumber());
        }
        orgMeta.add("Emergency Department");

        String label = label(d.getDocumentType());
        PdfReport r = PdfReport.begin(new PdfReport.Spec(
                label.toUpperCase(),
                "Clinical Document",
                orgName,
                orgMeta,
                notBlank(exportedByName) ? exportedByName : "SmartTriage",
                "protected health record",
                "Clinical documentation"));

        // ── Patient banner ──
        String patientName = p != null
                ? joinNonBlank(" ", p.getFirstName(), p.getLastName())
                : "Unidentified patient";
        if (!notBlank(patientName)) patientName = "Unidentified patient";
        List<KeyVal> ids = new ArrayList<>();
        if (p != null && notBlank(p.getMedicalRecordNumber())) ids.add(kv("MRN", p.getMedicalRecordNumber()));
        if (visit != null && notBlank(visit.getVisitNumber())) ids.add(kv("Visit", visit.getVisitNumber()));
        if (p != null && p.getDateOfBirth() != null) ids.add(kv("DOB", D.format(p.getDateOfBirth()) + " (" + age(p.getDateOfBirth()) + ")"));
        if (p != null && p.getGender() != null) ids.add(kv("Sex", titleCase(p.getGender().name())));
        TriageCategory cat = visit != null ? visit.getCurrentTriageCategory() : null;
        r.patientBanner(patientName, ids,
                cat != null ? titleCase(cat.name()) : null, catMeaning(cat), catColor(cat));

        // ── Status line ──
        String status = d.isSigned()
                ? "Signed" + (d.getSignedAt() != null ? " · " + DT.format(d.getSignedAt()) : "")
                : "Draft — pending signature";
        if (d.isAmendment()) status = "Amended document · " + status;
        r.statusBanner(status);

        // ── Body ──
        renderContent(r, d.getContent());

        // ── Structured details (procedure / operative / death) ──
        List<KeyVal> structured = new ArrayList<>();
        addKv(structured, "Procedure", d.getProcedurePerformed());
        addKv(structured, "Performed by", d.getProcedurePerformedBy());
        addKv(structured, "Anaesthesia", d.getAnaesthesiaType());
        addKv(structured, "Indication", d.getProcedureIndication());
        addKv(structured, "Findings", d.getProcedureFindings());
        addKv(structured, "Complications", d.getProcedureComplications());
        addKv(structured, "Outcome", d.getProcedureOutcome());
        addKv(structured, "Time of death", d.getTimeOfDeath() != null ? DT.format(d.getTimeOfDeath()) : null);
        addKv(structured, "Cause of death", d.getCauseOfDeath());
        addKv(structured, "Antecedent causes", d.getAntecedentCauses());
        addKv(structured, "Manner of death", d.getMannerOfDeath());
        if (!structured.isEmpty()) {
            r.sectionHeader("Structured details");
            r.keyValues(structured);
        }

        // ── Amendment ──
        if (d.isAmendment() && notBlank(d.getAmendmentReason())) {
            r.sectionHeader("Amendment");
            r.narrative("Reason for amendment: " + d.getAmendmentReason());
        }

        // ── Notes ──
        if (notBlank(d.getNotes())) {
            r.sectionHeader("Additional notes");
            r.narrative(d.getNotes());
        }

        // ── Authentication (electronic signatures) ──
        r.sectionHeader("Authentication");
        List<KeyVal> auth = new ArrayList<>();
        auth.add(kv("Authored by", authLine(d.getAuthorName(), d.getAuthorRole(), d.getAuthorLicenseNumber())));
        auth.add(kv("Status", d.isSigned()
                ? "Electronically signed" + (d.getSignedAt() != null ? " on " + DT.format(d.getSignedAt()) : "")
                : "Not yet signed"));
        if (notBlank(d.getCoSignedByName())) {
            auth.add(kv("Co-signed by", authLine(d.getCoSignedByName(), d.getCoSignedByRole(), d.getCoSignedByLicenseNumber())));
            if (d.getCoSignedAt() != null) auth.add(kv("Co-signed", DT.format(d.getCoSignedAt())));
        }
        if (notBlank(d.getTemplateUsed())) auth.add(kv("Template", d.getTemplateUsed()));
        r.keyValues(auth);

        String vn = visit != null && notBlank(visit.getVisitNumber()) ? visit.getVisitNumber() : "document";
        String filename = d.getDocumentType().name().toLowerCase().replace('_', '-') + "_" + vn + ".pdf";
        return new RenderedPdf(r.finish(), filename);
    }

    // ── Content parser: plain-text sections → real PDF structure ──
    private void renderContent(PdfReport r, String content) {
        if (!notBlank(content)) return;
        List<KeyVal> kvBuf = new ArrayList<>();
        List<String> prose = new ArrayList<>();
        for (String raw : content.replace("\r\n", "\n").split("\n", -1)) {
            String t = raw.trim();
            if (t.isEmpty()) { flushProse(r, prose); continue; }
            if (TITLE.matcher(t).matches()) { flushKv(r, kvBuf); flushProse(r, prose); continue; } // banner = masthead title already
            Matcher sec = SECTION.matcher(t);
            if (sec.matches()) { flushKv(r, kvBuf); flushProse(r, prose); r.sectionHeader(sec.group(1)); continue; }
            Matcher kvm = KEYVAL.matcher(raw);
            if (kvm.matches()) { flushProse(r, prose); kvBuf.add(kv(kvm.group(1).trim(), kvm.group(2).trim())); continue; }
            flushKv(r, kvBuf);
            prose.add(t.replaceFirst("^[•*-]\\s+", "• "));
        }
        flushKv(r, kvBuf);
        flushProse(r, prose);
    }

    private void flushKv(PdfReport r, List<KeyVal> buf) {
        if (!buf.isEmpty()) { r.keyValues(new ArrayList<>(buf)); buf.clear(); }
    }

    private void flushProse(PdfReport r, List<String> buf) {
        if (!buf.isEmpty()) { r.narrative(String.join("\n", buf)); buf.clear(); }
    }

    // ── helpers ──
    private static void addKv(List<KeyVal> list, String label, String value) {
        if (notBlank(value)) list.add(kv(label, value));
    }

    private static String authLine(String name, String role, String licence) {
        String base = notBlank(name) ? name : "Unknown clinician";
        if (notBlank(role)) base += " (" + role + ")";
        if (notBlank(licence)) base += " · Licence " + licence;
        return base;
    }

    private static String label(ClinicalDocumentType type) {
        if (type == null) return "Clinical Document";
        return switch (type) {
            case INITIAL_ASSESSMENT -> "Initial Assessment";
            case PROGRESS_NOTE -> "Progress Note";
            case PROCEDURE_NOTE -> "Procedure Note";
            case OPERATIVE_NOTE -> "Operative Note";
            case CONSULTATION_NOTE -> "Consultation Note";
            case NURSING_ASSESSMENT -> "Nursing Assessment";
            case TRIAGE_NARRATIVE -> "Triage Narrative";
            case DISCHARGE_SUMMARY -> "Discharge Summary";
            case TRANSFER_SUMMARY -> "Transfer Summary";
            case HANDOVER_DOCUMENT -> "Shift Handover";
            case INFORMED_CONSENT -> "Informed Consent";
            case DEATH_CERTIFICATE -> "Death Certificate";
            default -> titleCase(type.name().replace('_', ' '));
        };
    }

    private static Color catColor(TriageCategory c) {
        if (c == null) return null;
        return switch (c) {
            case RED -> PdfReport.SATS_RED;
            case ORANGE -> PdfReport.SATS_ORANGE;
            case YELLOW -> PdfReport.SATS_YELLOW;
            case GREEN -> PdfReport.SATS_GREEN;
            case BLUE -> PdfReport.SATS_BLUE;
        };
    }

    private static String catMeaning(TriageCategory c) {
        if (c == null) return null;
        return switch (c) {
            case RED -> "Immediate";
            case ORANGE -> "Very urgent";
            case YELLOW -> "Urgent";
            case GREEN -> "Routine";
            case BLUE -> "Dead on arrival";
        };
    }

    private static String age(LocalDate dob) {
        long years = ChronoUnit.YEARS.between(dob, LocalDate.now(KIGALI));
        if (years >= 2) return years + " yrs";
        long months = ChronoUnit.MONTHS.between(dob, LocalDate.now(KIGALI));
        return months + " mo";
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static String joinNonBlank(String sep, String... parts) {
        List<String> keep = new ArrayList<>();
        for (String s : parts) if (notBlank(s)) keep.add(s.trim());
        return String.join(sep, keep);
    }

    private static String titleCase(String s) {
        if (!notBlank(s)) return s;
        String lower = s.toLowerCase();
        StringBuilder sb = new StringBuilder();
        boolean up = true;
        for (char ch : lower.toCharArray()) {
            sb.append(up && Character.isLetter(ch) ? Character.toUpperCase(ch) : ch);
            up = ch == ' ' || ch == '-' || ch == '/';
        }
        return sb.toString();
    }
}
