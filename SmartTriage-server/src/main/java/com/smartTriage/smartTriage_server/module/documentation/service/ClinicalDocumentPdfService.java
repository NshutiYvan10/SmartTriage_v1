package com.smartTriage.smartTriage_server.module.documentation.service;

import com.smartTriage.smartTriage_server.common.enums.ClinicalDocumentType;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.common.report.PdfReport.CalloutVariant;
import com.smartTriage.smartTriage_server.common.report.PdfReport.GridPair;
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

import static com.smartTriage.smartTriage_server.common.report.PdfReport.gp;
import static com.smartTriage.smartTriage_server.common.report.PdfReport.gpFull;
import static com.smartTriage.smartTriage_server.common.report.PdfReport.kv;

/**
 * Renders a single {@link ClinicalDocument} into a professional, printable PDF
 * laid out to the "Structured clinical" (#1a) design — the same branded
 * masthead, neutral patient banner with acuity pill, badged sections, soft
 * callout cards, structured key/value grids and signature blocks the finished
 * handover / SBAR report uses (see {@code HandoverPdfService}).
 *
 * <p>Clinical documents persist their body as lightly-structured plain text:
 * {@code === TITLE ===} banners, {@code --- SECTION ---} headings and
 * {@code Label: value} fields (the auto-generated discharge / handover
 * summaries), or free prose (a typed SOAP progress note). This service parses
 * that back into real PDF structure — a numbered #1a section per content area,
 * a two-up {@code kvGrid} for structured fields, a chief-complaint-style yellow
 * callout for the primary narrative and neutral cards for supporting prose,
 * bulleted soft cards for record lists — so the output reads as a clinical
 * document rather than a text dump. The type-specific structured fields
 * (procedure / operative / death-certificate) and the electronic-signature
 * attestation are rendered the same way, and the medico-legal sign-off types
 * gain printable wet-ink signature boxes. Nothing is fabricated and every
 * non-blank field is preserved — this is a visual / organisational pass only.
 *
 * <p>Server-side (OpenPDF, {@code com.lowagie}) so the record is deterministic,
 * complete and archivable.
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

    /** A value at or under this length renders in the two-up grid; longer values become soft cards. */
    private static final int SHORT_VALUE = 45;
    /** Prose buffers longer than this render as flowing bullets (page-breakable) rather than one tall card. */
    private static final int LIST_CARD_MAX = 10;
    /** Fallback section label for free-form documents that carry no {@code --- SECTION ---} markers. */
    private static final String LEAD_SECTION = "Clinical details";

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

        // ── #1a patient banner (neutral panel · ids · acuity pill · doc-meta row) ──
        renderBanner(r, d, visit, p);
        if (d.isAmendment()) {
            r.statusBanner("AMENDED DOCUMENT — corrects a previously signed record; see the amendment section.");
        }

        // ── Body: one numbered #1a section per content area (shared running counter) ──
        int[] seq = {1};
        new ContentParser(r, seq).run(d.getContent());
        renderStructured(r, d, seq);
        renderAmendment(r, d, seq);
        renderNotes(r, d, seq);
        renderAuthentication(r, d, seq);

        String vn = visit != null && notBlank(visit.getVisitNumber()) ? visit.getVisitNumber() : "document";
        String filename = d.getDocumentType().name().toLowerCase().replace('_', '-') + "_" + vn + ".pdf";
        return new RenderedPdf(r.finish(), filename);
    }

    // ── Patient banner ────────────────────────────────────────────────
    private void renderBanner(PdfReport r, ClinicalDocument d, Visit visit, Patient p) {
        String patientName = p != null ? joinNonBlank(" ", p.getFirstName(), p.getLastName()) : "Unidentified patient";
        if (!notBlank(patientName)) patientName = "Unidentified patient";

        List<KeyVal> ids = new ArrayList<>();
        if (p != null && notBlank(p.getMedicalRecordNumber())) ids.add(kv("MRN", p.getMedicalRecordNumber()));
        if (visit != null && notBlank(visit.getVisitNumber())) ids.add(kv("Visit", visit.getVisitNumber()));
        if (p != null && p.getDateOfBirth() != null)
            ids.add(kv("DOB", D.format(p.getDateOfBirth()) + " (" + age(p.getDateOfBirth()) + ")"));
        if (p != null && p.getGender() != null) ids.add(kv("Sex", titleCase(p.getGender().name())));

        TriageCategory cat = visit != null ? visit.getCurrentTriageCategory() : null;
        String level = cat != null ? titleCase(cat.name()) : null;
        String tews = visit != null && visit.getCurrentTewsScore() != null ? "TEWS " + visit.getCurrentTewsScore() : null;

        // Row 2 (inside the banner): document meta + a signed/draft status chip.
        List<KeyVal> meta = new ArrayList<>();
        meta.add(kv("Author", d.getAuthorName()));
        if (d.isSigned() && d.getSignedAt() != null) meta.add(kv("Signed", DT.format(d.getSignedAt())));
        else meta.add(kv("Created", d.getCreatedAt() != null ? DT.format(d.getCreatedAt()) : null));
        if (notBlank(d.getTemplateUsed())) meta.add(kv("Template", d.getTemplateUsed()));

        KeyVal status = kv("Status", d.isSigned() ? "Signed" : "Draft");
        r.patientBanner(patientName, ids, level, catMeaning(cat), catColor(cat), tews, meta, status, !d.isSigned());
    }

    // ── Structured type-specific fields (procedure / operative / death) ──
    private void renderStructured(PdfReport r, ClinicalDocument d, int[] seq) {
        boolean procedure = anyNotBlank(d.getProcedurePerformed(), d.getProcedurePerformedBy(), d.getAnaesthesiaType(),
                d.getProcedureIndication(), d.getProcedureFindings(), d.getProcedureComplications(), d.getProcedureOutcome());
        if (procedure) {
            section(r, seq, "Procedure details");
            r.kvGrid(List.of(
                    gp("Procedure", d.getProcedurePerformed()),
                    gp("Performed by", d.getProcedurePerformedBy()),
                    gp("Anaesthesia", d.getAnaesthesiaType())));
            callout(r, CalloutVariant.NEUTRAL, "Indication", d.getProcedureIndication());
            callout(r, CalloutVariant.NEUTRAL, "Findings", d.getProcedureFindings());
            callout(r, CalloutVariant.NEUTRAL, "Complications", d.getProcedureComplications());
            callout(r, CalloutVariant.NEUTRAL, "Outcome", d.getProcedureOutcome());
        }

        boolean death = d.getTimeOfDeath() != null
                || anyNotBlank(d.getCauseOfDeath(), d.getAntecedentCauses(), d.getMannerOfDeath());
        if (death) {
            section(r, seq, "Cause of death");
            r.kvGrid(List.of(
                    gp("Time of death", d.getTimeOfDeath() != null ? DT.format(d.getTimeOfDeath()) : null),
                    gp("Manner of death", d.getMannerOfDeath())));
            // Cause of death is the primary certified datum → chief-complaint yellow.
            callout(r, CalloutVariant.CHIEF, "Cause of death", d.getCauseOfDeath());
            callout(r, CalloutVariant.NEUTRAL, "Antecedent causes", d.getAntecedentCauses());
        }
    }

    private void renderAmendment(PdfReport r, ClinicalDocument d, int[] seq) {
        if (d.isAmendment() && notBlank(d.getAmendmentReason())) {
            section(r, seq, "Amendment");
            callout(r, CalloutVariant.REDFLAG, "Reason for amendment", d.getAmendmentReason());
        }
    }

    private void renderNotes(PdfReport r, ClinicalDocument d, int[] seq) {
        if (notBlank(d.getNotes())) {
            section(r, seq, "Additional notes");
            callout(r, CalloutVariant.NEUTRAL, null, d.getNotes());
        }
    }

    // ── Authentication: electronic signatures + printable wet-ink sign-off ──
    private void renderAuthentication(PdfReport r, ClinicalDocument d, int[] seq) {
        section(r, seq, "Authentication");
        List<GridPair> auth = new ArrayList<>();
        auth.add(gpFull("Authored by", authLine(d.getAuthorName(), d.getAuthorRole(), d.getAuthorLicenseNumber())));
        auth.add(gpFull("Status", d.isSigned()
                ? "Electronically signed" + (d.getSignedAt() != null ? " on " + DT.format(d.getSignedAt()) : "")
                : "Not yet signed"));
        if (notBlank(d.getCoSignedByName())) {
            auth.add(gpFull("Co-signed by", authLine(d.getCoSignedByName(), d.getCoSignedByRole(), d.getCoSignedByLicenseNumber())));
            if (d.getCoSignedAt() != null) auth.add(gpFull("Co-signed", DT.format(d.getCoSignedAt())));
        }
        if (notBlank(d.getTemplateUsed())) auth.add(gpFull("Template", d.getTemplateUsed()));
        r.kvGrid(auth);

        String[] parties = signatureParties(d.getDocumentType());
        if (parties != null) {
            r.mutedNote("Physical sign-off — complete the signatures below on the printed copy.");
            r.signatureBoxes(parties[0], parties[1]);
        }
    }

    /** The medico-legal sign-off types that carry printable wet-ink signature boxes. */
    private static String[] signatureParties(ClinicalDocumentType type) {
        if (type == null) return null;
        return switch (type) {
            case DEATH_CERTIFICATE -> new String[]{"Certifying clinician", "Witness"};
            case INFORMED_CONSENT -> new String[]{"Patient / guardian", "Clinician obtaining consent"};
            case AGAINST_MEDICAL_ADVICE -> new String[]{"Patient / guardian", "Witnessing clinician"};
            case DISCHARGE_SUMMARY -> new String[]{"Discharging clinician", "Patient / guardian"};
            default -> null;
        };
    }

    // ══════════════════════════════════════════════════════════════════
    // Content parser: plain-text sections → #1a structure (badged sections,
    // grids, soft callouts, bulleted list cards). Deferred section emission
    // drops genuinely-empty sections while preserving every datum.
    // ══════════════════════════════════════════════════════════════════
    private static final class ContentParser {
        private final PdfReport r;
        private final int[] seq;
        private final List<GridPair> grid = new ArrayList<>();
        private final List<String> prose = new ArrayList<>();
        private String pendingSection;     // section label awaiting its first content line
        private boolean sectionActive;     // a badge is currently open
        private boolean anyBadge;          // any section badge emitted at all
        private String curSection;         // active section name (for complaint detection)

        ContentParser(PdfReport r, int[] seq) { this.r = r; this.seq = seq; }

        void run(String content) {
            if (!notBlank(content)) return;
            for (String raw : content.replace("\r\n", "\n").split("\n", -1)) feed(raw);
            flushGrid();
            flushProse();
        }

        private void feed(String raw) {
            String t = raw.trim();
            if (t.isEmpty()) { flushProse(); return; }                 // blank line ends a prose paragraph
            if (TITLE.matcher(t).matches()) { flushGrid(); flushProse(); return; }  // the masthead already carries the title
            Matcher sec = SECTION.matcher(t);
            if (sec.matches()) { flushGrid(); flushProse(); pendingSection = sec.group(1); sectionActive = false; return; }
            // Record lists (meds, vitals, triage, notes) are indented two spaces by the generator.
            if (raw.startsWith("  ")) { flushGrid(); prose.add("• " + t); return; }
            Matcher kvm = KEYVAL.matcher(raw);
            if (kvm.matches()) {
                String k = kvm.group(1).trim(), v = kvm.group(2).trim();
                // The leading "Generated: …" preamble is redundant with the footer attribution.
                if (!anyBadge && pendingSection == null && k.equalsIgnoreCase("Generated")) return;
                if (v.length() <= SHORT_VALUE && !v.contains("\n")) { flushProse(); grid.add(gp(k, v)); }
                else { flushGrid(); prose.add("**" + k + ":** " + v); }
                return;
            }
            flushGrid();
            prose.add(t.replaceFirst("^[•*\\-]\\s+", "• "));
        }

        /** Open the pending section (or a lead section for free-form content) exactly when content appears. */
        private void openSection() {
            if (pendingSection != null) {
                r.sbarGroup(String.valueOf(seq[0]++), pendingSection);
                curSection = pendingSection; pendingSection = null; sectionActive = true; anyBadge = true;
            } else if (!sectionActive) {
                r.sbarGroup(String.valueOf(seq[0]++), LEAD_SECTION);
                curSection = LEAD_SECTION; sectionActive = true; anyBadge = true;
            }
        }

        private void flushGrid() {
            if (grid.isEmpty()) return;
            openSection();
            r.kvGrid(new ArrayList<>(grid));
            grid.clear();
        }

        private void flushProse() {
            if (prose.isEmpty()) return;
            openSection();
            if (prose.size() == 1 && isPlaceholder(prose.get(0))) {
                r.mutedNote(stripBullet(prose.get(0)));
            } else if (prose.size() > LIST_CARD_MAX) {
                // Long lists flow as page-breakable bullets rather than a single tall card.
                r.bullets(prose.stream().map(ClinicalDocumentPdfService::stripBullet).toList());
            } else {
                CalloutVariant v = isComplaintSection(curSection) ? CalloutVariant.CHIEF : CalloutVariant.NEUTRAL;
                r.calloutBox(v, null, String.join("\n", prose));
            }
            prose.clear();
        }
    }

    // ── small render helpers ──
    private static void section(PdfReport r, int[] seq, String label) {
        r.sbarGroup(String.valueOf(seq[0]++), label);
    }

    private static void callout(PdfReport r, CalloutVariant v, String heading, String body) {
        if (notBlank(body)) r.calloutBox(v, heading, body);
    }

    // ── text helpers ──
    private static boolean isComplaintSection(String name) {
        if (name == null) return false;
        String s = name.toLowerCase();
        return s.contains("complaint") || s.contains("presenting") || s.contains("present illness")
                || s.contains("history of present") || s.contains("reason for");
    }

    /** A short "No … recorded / found / ordered / …" line — render it lightly, not as a card. */
    private static boolean isPlaceholder(String line) {
        String s = stripBullet(line).trim();
        return s.length() <= 44 && s.toLowerCase().startsWith("no ");
    }

    private static String stripBullet(String s) {
        return s == null ? "" : s.replaceFirst("^•\\s+", "");
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
            case AGAINST_MEDICAL_ADVICE -> "Against Medical Advice";
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

    private static boolean anyNotBlank(String... values) {
        for (String v : values) if (notBlank(v)) return true;
        return false;
    }

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
