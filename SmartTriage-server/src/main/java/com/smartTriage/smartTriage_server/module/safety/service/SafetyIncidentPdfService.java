package com.smartTriage.smartTriage_server.module.safety.service;

import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.common.report.PdfReport.KeyVal;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.safety.entity.SafetyIncident;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static com.smartTriage.smartTriage_server.common.report.PdfReport.kv;

/**
 * Renders a {@link SafetyIncident} into a printable single-incident report PDF — the formal record
 * for the quality/governance file: classification, timeline, narrative, investigation, root cause,
 * corrective action, and closure. Built on the shared {@link PdfReport} house style so it reads as a
 * branded, consistent deliverable. Must run in an open transaction (reads the lazy hospital
 * association).
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

            PdfReport r = PdfReport.begin(new PdfReport.Spec(
                    "PATIENT SAFETY INCIDENT REPORT",
                    "Safety Incident Report",
                    orgName,
                    orgMeta(h),
                    exportedBy,
                    "patient-safety incident report"));

            // Subject line: incident number + at-a-glance classification.
            String meta = "No. " + nz(i.getIncidentNumber())
                    + "  ·  " + nz(name(i.getIncidentType()))
                    + "  ·  Severity " + nz(name(i.getSeverity()))
                    + "  ·  " + nz(name(i.getStatus()));
            r.subjectHeadline("Incident " + nz(i.getIncidentNumber()), meta);

            // Prominent safety banners (patient harm and/or critical severity).
            String banner = severityBanner(i);
            if (banner != null) r.alertBanner(banner);

            r.sectionHeader("Classification");
            r.keyValues(List.of(
                    kv("Type", name(i.getIncidentType())),
                    kv("Severity", name(i.getSeverity())),
                    kv("Status", name(i.getStatus())),
                    kv("Patient harmed", tri(i.getPatientHarmed())),
                    kv("Anonymous report", i.isAnonymous() ? "Yes" : null)));

            r.sectionHeader("When & where");
            r.keyValues(List.of(
                    kv("Occurred", ts(i.getIncidentDateTime())),
                    kv("Location", i.getLocationInHospital()),
                    kv("Reported", ts(i.getReportedAt())),
                    kv("Reported by", i.getReportedByName()),
                    kv("Reporter role", i.getReportedByRole())));

            narrativeSection(r, "Description", i.getDescription());
            narrativeSection(r, "Contributing factors", i.getContributingFactors());
            narrativeSection(r, "Immediate actions", i.getImmediateActions());
            narrativeSection(r, "Involved staff", i.getInvolvedStaffNames());

            r.sectionHeader("Investigation");
            r.keyValues(List.of(
                    kv("Investigator", i.getInvestigatorName()),
                    kv("Started", ts(i.getInvestigationStartedAt())),
                    kv("Completed", ts(i.getInvestigationCompletedAt())),
                    kv("Root cause category", i.getRootCauseCategory())));
            narrativeSection(r, "Root cause analysis", i.getRootCauseAnalysis());

            r.sectionHeader("Corrective action");
            r.keyValues(List.of(
                    kv("Action", i.getCorrectiveAction()),
                    kv("Owner", i.getCorrectiveActionOwner()),
                    kv("Deadline", ts(i.getCorrectiveActionDeadline())),
                    kv("Completed", ts(i.getCorrectiveActionCompletedAt()))));
            narrativeSection(r, "Preventive measures", i.getPreventiveMeasures());

            r.sectionHeader("Closure");
            r.keyValues(List.of(
                    kv("Closed", ts(i.getClosedAt())),
                    kv("Closed by", i.getClosedByName())));
            narrativeSection(r, "Lessons learned", i.getLessonsLearned());
            narrativeSection(r, "Notes", i.getNotes());

            return r.finish();
        } catch (Exception e) {
            log.error("Failed to render safety-incident PDF for {}: {}", i.getId(), e.getMessage(), e);
            throw new IllegalStateException("Could not generate safety-incident PDF", e);
        }
    }

    // ── helpers ──

    /** A section header + narrative panel; renders nothing when the content is blank. */
    private static void narrativeSection(PdfReport r, String label, String content) {
        if (content == null || content.isBlank()) return;
        r.sectionHeader(label);
        r.narrative(content);
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

    /** The safety banner: patient harm and/or a severe/critical severity, else null. */
    private static String severityBanner(SafetyIncident i) {
        String sev = name(i.getSeverity());
        boolean harm = Boolean.TRUE.equals(i.getPatientHarmed());
        boolean critical = sev != null
                && (sev.contains("SEVERE") || sev.contains("DEATH") || sev.contains("CRITICAL"));
        if (harm && critical) return "** PATIENT HARM REPORTED · SEVERITY " + sev + " **";
        if (harm) return "** PATIENT HARM REPORTED **";
        if (critical) return "** SEVERITY " + sev + " **";
        return null;
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

    private static String tri(Boolean b) {
        return b != null ? (b ? "Yes" : "No") : null;
    }

    private static String name(Enum<?> e) { return e != null ? e.name() : null; }
    private static String nz(String s) { return s != null ? s : "—"; }
    private static String ts(Instant i) { return i != null ? TS.format(i) : null; }

    private static Hospital safeHospital(SafetyIncident i) {
        try { return i.getHospital(); } catch (Exception e) { return null; }
    }
}
