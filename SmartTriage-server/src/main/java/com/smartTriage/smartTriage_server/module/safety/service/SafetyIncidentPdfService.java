package com.smartTriage.smartTriage_server.module.safety.service;

import com.smartTriage.smartTriage_server.common.enums.IncidentSeverity;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.common.report.PdfReport.LedgerCell;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.safety.entity.SafetyIncident;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.smartTriage.smartTriage_server.common.report.PdfReport.lcell;
import static com.smartTriage.smartTriage_server.common.report.PdfReport.lcellChip;
import static com.smartTriage.smartTriage_server.common.report.PdfReport.lcellColor;
import static com.smartTriage.smartTriage_server.common.report.PdfReport.lcellFull;
import static com.smartTriage.smartTriage_server.common.report.PdfReport.lcellMono;

/**
 * Renders a {@link SafetyIncident} into a printable single-incident report PDF — the formal record
 * for the quality/governance file: classification, timeline, narrative, investigation, root cause,
 * corrective action, and closure.
 *
 * <p>A safety incident is a <b>tabular, audit-oriented</b> record that is scanned and
 * cross-referenced rather than read start-to-finish, so it uses the shared {@link PdfReport}
 * kit's <b>#1b "ledger / dossier"</b> style: a flat ink-ruled masthead, a bordered incident ID
 * table (with severity as an outlined chip), numbered section headers (01, 02…), flat bordered
 * key/value tables with monospace dates, and an ink-ruled footer. Must run in an open
 * transaction (reads the lazy hospital association).
 */
@Slf4j
@Service
public class SafetyIncidentPdfService {

    /** A rendered PDF plus a safe download filename, both computed inside the load transaction. */
    public record RenderedPdf(byte[] bytes, String filename) {}

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Kigali"));

    public String filename(SafetyIncident i) {
        String n = i.getIncidentNumber() != null ? i.getIncidentNumber() : "incident";
        return ("safety-incident-" + n + ".pdf").replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * Render the incident report. {@code exportedBy} is the display name of the caller who
     * triggered the export — recorded in the masthead + footer for traceability.
     */
    public byte[] render(SafetyIncident i, String exportedBy) {
        try {
            Hospital h = safeHospital(i);
            String orgName = (h != null && h.getName() != null && !h.getName().isBlank())
                    ? h.getName() : "Hospital";

            // #1b ledger chrome: ink-ruled masthead + footer.
            PdfReport r = PdfReport.beginLedger(new PdfReport.Spec(
                    "Safety Incident Report",       // masthead title → "SAFETY INCIDENT REPORT"
                    "Safety Incident",              // footer doc type → "SMARTTRIAGE · SAFETY INCIDENT"
                    orgName,
                    orgMeta(h),
                    exportedBy,
                    "protected health record",      // footer left → "CONFIDENTIAL — PROTECTED HEALTH RECORD"
                    "Patient safety & governance"));

            // ── Incident ID table: identity at a glance; severity as an outlined chip ──
            List<LedgerCell> id = new ArrayList<>();
            id.add(lcellMono("Incident No", nz(i.getIncidentNumber())));
            IncidentSeverity sev = i.getSeverity();
            if (sev != null) id.add(lcellChip("Severity", pretty(sev.name()), severityColor(sev)));
            id.add(lcell("Type", pretty(name(i.getIncidentType()))));
            id.add(lcell("Status", pretty(name(i.getStatus()))));
            if (i.getIncidentDateTime() != null) id.add(lcellMono("Occurred", ts(i.getIncidentDateTime())));
            if (notBlank(i.getLocationInHospital())) id.add(lcell("Location", i.getLocationInHospital()));
            id.add(reportedByCell(i));
            if (i.getReportedAt() != null) id.add(lcellMono("Reported At", ts(i.getReportedAt())));
            id.add(harmedCell(i));
            id.add(lcell("Anonymous", i.isAnonymous() ? "Yes" : "No"));
            r.ledgerIdTable(id);

            int n = 0;

            // ── 0n INCIDENT DETAILS ──
            List<LedgerCell> details = new ArrayList<>();
            addFull(details, "Description", i.getDescription());
            addFull(details, "Contributing factors", i.getContributingFactors());
            addFull(details, "Immediate actions", i.getImmediateActions());
            addFull(details, "Involved staff", i.getInvolvedStaffNames());
            if (!details.isEmpty()) { r.ledgerSection(num(++n), "Incident details"); r.ledgerKv(details); }

            // ── 0n INVESTIGATION ──
            List<LedgerCell> inv = new ArrayList<>();
            add(inv, "Investigator", i.getInvestigatorName());
            add(inv, "Root cause category", i.getRootCauseCategory());
            addMono(inv, "Started", ts(i.getInvestigationStartedAt()));
            addMono(inv, "Completed", ts(i.getInvestigationCompletedAt()));
            addFull(inv, "Root cause analysis", i.getRootCauseAnalysis());
            if (!inv.isEmpty()) { r.ledgerSection(num(++n), "Investigation"); r.ledgerKv(inv); }

            // ── 0n CORRECTIVE ACTION ──
            List<LedgerCell> ca = new ArrayList<>();
            addFull(ca, "Action", i.getCorrectiveAction());
            add(ca, "Owner", i.getCorrectiveActionOwner());
            addMono(ca, "Deadline", ts(i.getCorrectiveActionDeadline()));
            addMono(ca, "Completed", ts(i.getCorrectiveActionCompletedAt()));
            addFull(ca, "Preventive measures", i.getPreventiveMeasures());
            if (!ca.isEmpty()) { r.ledgerSection(num(++n), "Corrective action"); r.ledgerKv(ca); }

            // ── 0n CLOSURE ──
            List<LedgerCell> cl = new ArrayList<>();
            addMono(cl, "Closed", ts(i.getClosedAt()));
            add(cl, "Closed by", i.getClosedByName());
            addFull(cl, "Lessons learned", i.getLessonsLearned());
            addFull(cl, "Notes", i.getNotes());
            if (!cl.isEmpty()) { r.ledgerSection(num(++n), "Closure"); r.ledgerKv(cl); }

            return r.finish();
        } catch (Exception e) {
            log.error("Failed to render safety-incident PDF for {}: {}", i.getId(), e.getMessage(), e);
            throw new IllegalStateException("Could not generate safety-incident PDF", e);
        }
    }

    // ── ledger-cell builders ──

    /** Add a plain half-width key/value cell when the value is present. */
    private static void add(List<LedgerCell> l, String label, String v) {
        if (notBlank(v)) l.add(lcell(label, v));
    }

    /** Add a monospace half-width cell (dates / IDs) when the value is present. */
    private static void addMono(List<LedgerCell> l, String label, String v) {
        if (notBlank(v)) l.add(lcellMono(label, v));
    }

    /** Add a full-width row (long prose) when the value is present. */
    private static void addFull(List<LedgerCell> l, String label, String v) {
        if (notBlank(v)) l.add(lcellFull(label, v));
    }

    /** Reporter identity, folding the role in and honouring anonymous reporting. */
    private static LedgerCell reportedByCell(SafetyIncident i) {
        String name = i.getReportedByName();
        if (i.isAnonymous() || !notBlank(name)) name = "Anonymous";
        String role = i.getReportedByRole();
        String value = notBlank(role) ? name + " (" + role + ")" : name;
        return lcell("Reported By", value);
    }

    /** Patient-harm flag — rendered red/bold when harm occurred (the ledger's emphasis idiom). */
    private static LedgerCell harmedCell(SafetyIncident i) {
        Boolean harmed = i.getPatientHarmed();
        if (harmed == null) return lcell("Patient Harmed", "Not recorded");
        return harmed ? lcellColor("Patient Harmed", "Yes", PdfReport.DANGER)
                      : lcell("Patient Harmed", "No");
    }

    /** Two-digit section number: 1 → "01". */
    private static String num(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    // ── helpers ──

    /** Severity → the semantic colour of its outlined chip (green = no harm … red = severe/death). */
    private static Color severityColor(IncidentSeverity sev) {
        return switch (sev) {
            case NEAR_MISS, NO_HARM -> PdfReport.SATS_GREEN;
            case MILD_HARM -> PdfReport.ACCENT;
            case MODERATE_HARM -> PdfReport.SATS_ORANGE;
            case SEVERE_HARM, DEATH -> PdfReport.DANGER;
        };
    }

    /** Address / district / province / phone lines for the masthead (blank lines auto-dropped). */
    private static List<String> orgMeta(Hospital h) {
        List<String> lines = new ArrayList<>();
        if (h == null) return lines;
        String cityProvince = joinNonBlank(", ", h.getCity(), h.getProvince());
        addIfPresent(lines, h.getAddress());
        addIfPresent(lines, cityProvince);
        addIfPresent(lines, h.getPhoneNumber());
        addIfPresent(lines, h.getEmail());
        return lines;
    }

    private static void addIfPresent(List<String> lines, String v) {
        if (v != null && !v.isBlank()) lines.add(v);
    }

    private static String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(p);
        }
        return sb.toString();
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    /** "MODERATE_HARM" → "Moderate harm". */
    private static String pretty(String enumName) {
        if (enumName == null || enumName.isBlank()) return null;
        String s = enumName.replace('_', ' ').trim();
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String name(Enum<?> e) { return e != null ? e.name() : null; }
    private static String nz(String s) { return s != null ? s : "—"; }
    private static String ts(Instant i) { return i != null ? TS.format(i) : null; }

    private static Hospital safeHospital(SafetyIncident i) {
        try { return i.getHospital(); } catch (Exception e) { return null; }
    }
}
