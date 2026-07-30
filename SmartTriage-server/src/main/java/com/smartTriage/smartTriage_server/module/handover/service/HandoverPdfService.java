package com.smartTriage.smartTriage_server.module.handover.service;

import com.smartTriage.smartTriage_server.common.enums.Gender;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.common.report.PdfReport.AlertGroup;
import com.smartTriage.smartTriage_server.common.report.PdfReport.AlertItem;
import com.smartTriage.smartTriage_server.common.report.PdfReport.Card;
import com.smartTriage.smartTriage_server.common.report.PdfReport.CalloutVariant;
import com.smartTriage.smartTriage_server.common.report.PdfReport.GridPair;
import com.smartTriage.smartTriage_server.common.report.PdfReport.LineItem;
import com.smartTriage.smartTriage_server.common.report.PdfReport.RedFlag;
import com.smartTriage.smartTriage_server.common.report.PdfReport.TimelineRow;
import com.smartTriage.smartTriage_server.module.bed.entity.Bed;
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
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders a {@link HandoverReport} into a professional, printable PDF laid out
 * to the "Structured clinical" (#1a) design: branded masthead, patient banner
 * with acuity pill, an allergy / chronic strip, and the S/B/A/R framework as
 * structured cards — a SITUATION identity grid + chief-complaint callout,
 * BACKGROUND stat tiles + vitals grid + investigation rows, an ASSESSMENT with
 * diagnosis rows, fast-track / sepsis cards and a red-flag callout, severity-
 * grouped clinical alerts, a RECOMMENDATION medication list, an outstanding-
 * tasks disposition block, an ED timeline, and a signed handover-notes block.
 *
 * <p>The {@link HandoverReport} persists pre-formatted TEXT sections; this
 * service reconstructs the structured look by reading identity/timeline facts
 * straight off the {@link Visit}/{@link Patient} entities and parsing the
 * semi-structured text for the clinical detail. Nothing is fabricated and no
 * non-blank section is dropped — anything that cannot be parsed cleanly falls
 * back to a styled narrative/callout panel.
 *
 * <p>Server-side (OpenPDF) rather than client-side so the document is
 * deterministic, complete, and archivable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class HandoverPdfService {

    private final HandoverReportRepository handoverReportRepository;

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KIGALI);
    private static final DateTimeFormatter ARRIVED =
            DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm").withZone(KIGALI);
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(KIGALI);

    /** A rendered PDF plus a safe download filename, both computed inside the load transaction. */
    public record RenderedPdf(byte[] bytes, String filename) {}

    /**
     * Load the report and render it within ONE transaction (so the lazy
     * visit/patient/hospital associations resolve safely), returning the PDF
     * bytes plus a sanitised download filename.
     *
     * @param exportedBy display name of the user who triggered the export (attribution)
     */
    public RenderedPdf renderDocument(UUID reportId, String exportedBy) {
        HandoverReport report = handoverReportRepository.findByIdAndIsActiveTrue(reportId)
                .orElseThrow(() -> new ResourceNotFoundException("HandoverReport", "id", reportId));
        byte[] bytes = render(report, exportedBy);
        String visitNo = report.getVisit() != null && report.getVisit().getVisitNumber() != null
                ? report.getVisit().getVisitNumber() : "report";
        String date = report.getGeneratedAt() != null ? "-" + FILE_TS.format(report.getGeneratedAt()) : "";
        String filename = ("handover-" + visitNo + date + ".pdf").replaceAll("[^A-Za-z0-9._-]", "_");
        return new RenderedPdf(bytes, filename);
    }

    public byte[] render(HandoverReport report, String exportedBy) {
        Hospital h = report.getHospital();
        String orgName = h != null && h.getName() != null ? h.getName() : "Hospital";
        String type = report.getReportType() != null
                ? prettyType(report.getReportType().name()) : "Handover";
        String title = (report.getReportType() != null
                ? report.getReportType().name().replace('_', ' ') : "HANDOVER") + " · SBAR";

        try {
            PdfReport r = PdfReport.begin(new PdfReport.Spec(
                    title,
                    "Handover / SBAR",
                    orgName,
                    hospitalMeta(h),
                    exportedBy,
                    "protected health record",
                    "Clinical documentation"));

            renderPatientBanner(r, report, type);
            renderStatusBanners(r, report);
            renderAllergyStrip(r, report);

            renderSituation(r, report);
            renderBackground(r, report);
            renderAssessment(r, report);
            renderAlerts(r, report);
            renderRecommendation(r, report);
            renderDisposition(r, report);
            renderTimeline(r, report);
            renderHandoverNotes(r, report);

            return r.finish();
        } catch (Exception e) {
            log.error("Failed to render handover PDF for report {}: {}", report.getId(), e.getMessage(), e);
            throw new IllegalStateException("Could not generate handover PDF", e);
        }
    }

    // ── Hospital masthead meta (single clean address · tel · email line) ──

    private List<String> hospitalMeta(Hospital h) {
        List<String> bits = new ArrayList<>();
        if (h == null) return bits;
        String addr = joinNonBlank(", ", h.getAddress(), h.getCity());
        String line = addr;
        if (h.getPhoneNumber() != null && !h.getPhoneNumber().isBlank())
            line = joinNonBlank(" · ", line, "Tel: " + h.getPhoneNumber());
        if (h.getEmail() != null && !h.getEmail().isBlank())
            line = joinNonBlank(" · ", line, h.getEmail());
        if (!line.isBlank()) bits.add(line);
        return bits;
    }

    // ── Patient banner + status banners + parameter strip ──────────

    private void renderPatientBanner(PdfReport r, HandoverReport report, String type) {
        Visit visit = report.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;

        String patientName = patient != null
                ? (safe(patient.getFirstName()) + " " + safe(patient.getLastName())).trim() : "Unknown";
        if (patientName.isBlank()) patientName = "Unknown";

        List<PdfReport.KeyVal> ids = new ArrayList<>();
        if (visit != null && visit.getVisitNumber() != null) ids.add(PdfReport.kv("Visit", visit.getVisitNumber()));
        if (patient != null && patient.getMedicalRecordNumber() != null)
            ids.add(PdfReport.kv("MRN", patient.getMedicalRecordNumber()));
        String ageSex = ageSex(patient);
        if (ageSex != null) ids.add(PdfReport.kv("Age / Sex", ageSex));
        if (visit != null && visit.getArrivalTime() != null)
            ids.add(PdfReport.kv("Arrived", ARRIVED.format(visit.getArrivalTime())));

        TriageCategory cat = visit != null ? visit.getCurrentTriageCategory() : null;
        String level = cat != null ? cat.name() : null;
        String meaning = cat != null ? cat.getDescription() : null;
        Color color = cat != null ? satsColor(cat) : null;
        String tews = visit != null && visit.getCurrentTewsScore() != null
                ? "TEWS " + visit.getCurrentTewsScore() : null;

        // Row 2 (inside the banner): report-meta + acknowledgement chip.
        List<PdfReport.KeyVal> meta = new ArrayList<>();
        meta.add(PdfReport.kv("Report type", type));
        meta.add(PdfReport.kv("Generated",
                report.getGeneratedAt() != null ? TS.format(report.getGeneratedAt()) : null));
        meta.add(PdfReport.kv("Handing over", report.getGeneratedByName()));
        String ackText = report.isAcknowledged() ? "Acknowledged" : "Pending";
        PdfReport.KeyVal ack = PdfReport.kv("Acknowledgement", ackText);

        r.patientBanner(patientName, ids, level, meaning, color, tews, meta, ack, !report.isAcknowledged());
    }

    private void renderStatusBanners(PdfReport r, HandoverReport report) {
        Visit visit = report.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;
        // Unmistakable unidentified-patient flag — a red alert, not a status note.
        if (patient != null && patient.isUnidentified()) {
            r.alertBanner("UNIDENTIFIED PATIENT — identity unresolved");
        }
    }

    private void renderAllergyStrip(PdfReport r, HandoverReport report) {
        Visit visit = report.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;
        String allergies = patient != null ? patient.getKnownAllergies() : null;
        String chronic = patient != null ? patient.getChronicConditions() : null;
        r.allergyStrip(allergies, chronic);
    }

    // ── S — SITUATION ───────────────────────────────────────────────

    private void renderSituation(PdfReport r, HandoverReport report) {
        Visit visit = report.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;
        r.sbarGroup("S", "SITUATION");

        List<GridPair> pairs = new ArrayList<>();
        if (patient != null) {
            String name = (safe(patient.getFirstName()) + " " + safe(patient.getLastName())).trim();
            pairs.add(PdfReport.gp("Name", name));
            if (patient.getDateOfBirth() != null) {
                int age = Period.between(patient.getDateOfBirth(), LocalDate.now(KIGALI)).getYears();
                if (age >= 0) pairs.add(PdfReport.gp("Age", age + " years"));
                pairs.add(PdfReport.gp("Date of Birth", patient.getDateOfBirth().toString()));
            }
            pairs.add(PdfReport.gp("Gender", genderLabel(patient.getGender())));
            pairs.add(PdfReport.gp("MRN", patient.getMedicalRecordNumber()));
            pairs.add(PdfReport.gp("National ID", patient.getNationalId()));
        }
        if (visit != null) {
            pairs.add(PdfReport.gp("Visit Number", visit.getVisitNumber()));
            pairs.add(PdfReport.gp("Pediatric", visit.isPediatric() ? "Yes" : "No"));
            String loc = location(visit);
            if (loc != null) pairs.add(PdfReport.gpFull("Location", loc));
        }
        r.kvGrid(pairs);

        String complaint = visit != null ? visit.getChiefComplaint() : null;
        if (isBlank(complaint)) complaint = stripPrefix(report.getPresentingComplaint(), "Chief Complaint:");
        if (!isBlank(complaint)) r.calloutBox(CalloutVariant.CHIEF, "Chief Complaint", complaint.trim());
    }

    // ── B — BACKGROUND ──────────────────────────────────────────────

    private void renderBackground(PdfReport r, HandoverReport report) {
        Visit visit = report.getVisit();
        r.sbarGroup("B", "BACKGROUND");

        // 4 stat tiles (triage events / category / TEWS / retriage) from text, entity as fallback.
        String events = firstGroup(report.getTriageSummary(), "Total Triage Events:\\s*(\\d+)");
        String category = firstGroup(report.getTriageSummary(), "Current Category:\\s*(\\w+)");
        if (isBlank(category) && visit != null && visit.getCurrentTriageCategory() != null)
            category = visit.getCurrentTriageCategory().name();
        String tews = firstGroup(report.getTriageSummary(), "Current TEWS Score:\\s*(\\d+)");
        if (isBlank(tews) && visit != null && visit.getCurrentTewsScore() != null)
            tews = String.valueOf(visit.getCurrentTewsScore());
        String retriage = firstGroup(report.getTriageSummary(), "Retriage Count:\\s*(\\d+)");
        if (isBlank(retriage) && visit != null) retriage = String.valueOf(visit.getRetriageCount());

        Color catColor = visit != null && visit.getCurrentTriageCategory() != null
                ? satsColor(visit.getCurrentTriageCategory()) : PdfReport.INK;
        List<PdfReport.StatTile> tiles = new ArrayList<>();
        tiles.add(PdfReport.stat(events, "Triage events"));
        tiles.add(PdfReport.stat(category, "Current category", catColor));
        tiles.add(PdfReport.stat(tews, "Current TEWS"));
        tiles.add(PdfReport.stat(retriage, "Retriage count"));
        r.statCards(tiles, 4, 11f);

        String history = triageHistory(report.getTriageSummary());
        if (!isBlank(history)) r.calloutBox(CalloutVariant.NEUTRAL, "Triage history", history);

        // Pre-hospital / EMS (rarely present) — styled narrative, nothing dropped.
        if (hasData(report.getPrehospitalSummary())) {
            r.subHeader("Pre-hospital / EMS");
            r.narrative(report.getPrehospitalSummary().trim());
        }

        // Vitals grid + trend note.
        Vitals v = parseVitals(report.getVitalSignsTrend());
        if (v != null && !v.tiles.isEmpty()) {
            r.subHeader("Vital signs trend" + (v.header != null ? " — " + v.header : ""));
            List<PdfReport.StatTile> vt = v.tiles.stream()
                    .map(kv -> PdfReport.stat(kv.value(), kv.label())).toList();
            r.statCards(vt, 6, 9.5f);
            if (!isBlank(v.trend)) r.mutedNote("Trend: " + v.trend);
        }

        // Investigations line-items.
        List<LineItem> inv = parseInvestigations(report.getInvestigationsResults());
        if (!inv.isEmpty()) {
            String n = firstGroup(report.getInvestigationsResults(), "Total Investigations:\\s*(\\d+)");
            r.subHeader("Investigations & results" + (n != null ? " — " + n + " total" : ""));
            r.lineItems(inv);
        }
    }

    // ── A — ASSESSMENT ──────────────────────────────────────────────

    private void renderAssessment(PdfReport r, HandoverReport report) {
        List<LineItem> diagnoses = parseDiagnoses(report.getDiagnosisSummary());
        Acute acute = parseAcuteProtocols(report.getAcuteProtocols());
        boolean any = !diagnoses.isEmpty() || acute.fastTrack != null || acute.sepsis != null
                || !acute.redFlags.isEmpty() || !isBlank(acute.carePathways);
        if (!any) return;

        r.sbarGroup("A", "ASSESSMENT");
        if (!diagnoses.isEmpty()) {
            r.subHeader("Diagnosis");
            r.lineItems(diagnoses);
        }
        if (acute.fastTrack != null || acute.sepsis != null) r.twoUpCards(acute.fastTrack, acute.sepsis);
        if (!acute.redFlags.isEmpty())
            r.redFlagCallout("Active red-flag signs — " + acute.redFlags.size(), acute.redFlags);
        if (!isBlank(acute.carePathways)) r.bodyLine(acute.carePathways);
    }

    // ── ACTIVE CLINICAL ALERTS (own section, red badge) ─────────────

    private void renderAlerts(PdfReport r, HandoverReport report) {
        List<AlertGroup> groups = parseAlerts(report.getActiveClinicalAlerts());
        int total = groups.stream().mapToInt(g -> g.items().size()).sum();
        if (total == 0) return;
        r.sbarGroup("!", "Active clinical alerts — " + total, PdfReport.SATS_RED);
        r.alertGroups(groups);
    }

    // ── R — RECOMMENDATION ──────────────────────────────────────────

    private void renderRecommendation(PdfReport r, HandoverReport report) {
        List<LineItem> meds = parseMedications(report.getTreatmentSummary());
        boolean procedures = hasData(report.getProceduresDocuments());
        if (meds.isEmpty() && !procedures) return;
        r.sbarGroup("R", "RECOMMENDATION");
        if (!meds.isEmpty()) {
            String n = firstGroup(report.getTreatmentSummary(), "Total Medications:\\s*(\\d+)");
            r.subHeader("Medications" + (n != null ? " — " + n + " total" : ""));
            r.lineItems(meds);
        }
        if (procedures) {
            r.subHeader("Procedures & documents");
            r.narrative(report.getProceduresDocuments().trim());
        }
    }

    // ── OUTSTANDING TASKS & DISPOSITION (gray badge) ────────────────

    private void renderDisposition(PdfReport r, HandoverReport report) {
        MedAudit audit = parseMedAudit(report.getMedicationAudit());
        List<String> pending = parseOutstanding(report.getOutstandingTasks());
        String progress = progressNote(report.getPlanOfCare());
        boolean any = audit.activeLine != null || !audit.rows.isEmpty()
                || !pending.isEmpty() || !isBlank(progress);
        if (!any) return;

        r.sbarGroup("•", "Outstanding tasks & disposition", PdfReport.MUTED);
        if (audit.activeLine != null) r.mutedNote(audit.activeLine);
        if (!audit.rows.isEmpty()) {
            r.dataTable(new String[]{"Medication", "Prescribed", "Status", "Note"},
                    new float[]{38, 22, 18, 22}, audit.rows, 4);
        }
        for (String line : pending) r.bodyLine(line);
        if (!isBlank(progress)) r.calloutBox(CalloutVariant.NEUTRAL, null, progress);
    }

    // ── ED TIMELINE (gray badge) ────────────────────────────────────

    private void renderTimeline(PdfReport r, HandoverReport report) {
        Visit visit = report.getVisit();
        if (visit == null) return;
        String txt = report.getEdTimeline();
        String doorTriage = firstGroup(txt, "Door-to-Triage:\\s*(\\d+)\\s*min");
        String doorPhys = firstGroup(txt, "Door-to-Physician:\\s*(\\d+)\\s*min");

        List<TimelineRow> rows = new ArrayList<>();
        if (visit.getArrivalTime() != null)
            rows.add(new TimelineRow("Arrival", TS.format(visit.getArrivalTime()), null, PdfReport.BRAND));
        if (visit.getTriageTime() != null)
            rows.add(new TimelineRow("Triage", TS.format(visit.getTriageTime()),
                    doorTriage != null ? "(door-to-triage " + doorTriage + " min)" : null, PdfReport.BRAND));
        if (visit.getAssessmentStartTime() != null)
            rows.add(new TimelineRow("Assessment start", TS.format(visit.getAssessmentStartTime()),
                    doorPhys != null ? "(door-to-physician " + doorPhys + " min)" : null, PdfReport.BRAND));
        String status = visit.getStatus() != null ? visit.getStatus().name() : null;
        TriageCategory cat = visit.getCurrentTriageCategory();
        if (status != null) {
            String val = status + (cat != null ? " · Category " + cat.name() : "");
            rows.add(new TimelineRow("Current status", val, null, cat != null ? satsColor(cat) : PdfReport.MUTED));
        }
        if (rows.isEmpty()) return;

        r.sbarGroup("•", "ED timeline", PdfReport.MUTED);
        r.timeline(rows);
        if (hasData(report.getNotes())) r.mutedNote(report.getNotes().trim());
    }

    // ── HANDOVER NOTES (gray badge) ─────────────────────────────────

    private void renderHandoverNotes(PdfReport r, HandoverReport report) {
        r.sbarGroup("•", "Handover notes", PdfReport.MUTED);
        String handing = !isBlank(report.getGeneratedByName()) ? report.getGeneratedByName() : "the outgoing clinician";
        r.mutedNote("Handover is complete only when the receiving clinician signs. Handing over: " + handing + ".");
        r.signatureBoxes("Handing-over clinician", "Receiving clinician");
        String attribution = "Authored by " + handing + " · "
                + (report.isAcknowledged()
                    ? "Acknowledged" + (report.getReceivedByName() != null ? " by " + report.getReceivedByName() : "")
                    : "Not yet signed");
        r.mutedNote(attribution);
    }

    // ══════════════════════════════════════════════════════════════════
    // Parsers — reconstruct structure from the persisted TEXT sections.
    // ══════════════════════════════════════════════════════════════════

    /** Vitals grid + latest-timestamp header + trend note. */
    private record Vitals(String header, List<PdfReport.KeyVal> tiles, String trend) {
        Vitals(String header, List<PdfReport.KeyVal> tiles, String trend) {
            this.header = header; this.tiles = tiles; this.trend = trend;
        }
    }

    private Vitals parseVitals(String text) {
        if (isBlank(text)) return null;
        String latest = firstGroup(text, "Latest Vitals \\((.*?)\\)");
        String readings = firstGroup(text, "Vital Signs Trend \\((\\d+) readings\\)");
        List<PdfReport.KeyVal> tiles = new ArrayList<>();
        List<String> trendBits = new ArrayList<>();
        boolean inTrend = false;
        for (String raw : text.split("\n")) {
            String line = raw.trim();
            if (line.startsWith("Latest Vitals")) { inTrend = false; continue; }
            if (line.startsWith("Vital Signs Trend")) { inTrend = true; continue; }
            if (line.isEmpty() || !line.contains(":")) continue;
            int i = line.indexOf(':');
            String key = line.substring(0, i).trim();
            String val = line.substring(i + 1).trim();
            if (val.isEmpty()) continue;
            if (!inTrend) {
                String[] tile = vitalTile(key, val);
                if (tile != null) tiles.add(PdfReport.kv(tile[0], tile[1]));
            } else {
                trendBits.add(key + " " + lowerFirst(val));
            }
        }
        String header = null;
        if (latest != null) header = "latest " + latest + (readings != null ? " (" + readings + " readings)" : "");
        else if (readings != null) header = readings + " readings";
        return new Vitals(header, tiles, String.join(" · ", trendBits));
    }

    /** Map a "HR: 74 bpm" vital line to a [value, "HR bpm"] tile. */
    private String[] vitalTile(String key, String val) {
        String shortKey = switch (key.toLowerCase()) {
            case "hr", "heart rate" -> "HR";
            case "rr", "respiratory rate" -> "RR";
            case "bp", "blood pressure" -> "BP";
            case "temp", "temperature" -> "Temp";
            case "spo2" -> "SpO2";
            case "blood glucose", "glucose" -> "Glucose";
            case "weight" -> "Weight";
            default -> key;
        };
        String value;
        String unit;
        int sp = val.indexOf(' ');
        if (sp < 0) { value = val; unit = ""; }
        else { value = val.substring(0, sp).trim(); unit = val.substring(sp + 1).trim(); }
        String label = unit.isEmpty() ? shortKey : shortKey + " " + unit;
        return new String[]{value, label};
    }

    /** Compact one-line triage-history summary (bold ts + category), most-recent first. */
    private String triageHistory(String text) {
        if (isBlank(text)) return null;
        int idx = text.indexOf("Triage History");
        if (idx < 0) return null;
        String[] lines = text.substring(idx).split("\n");
        StringBuilder sb = new StringBuilder();
        Pattern entry = Pattern.compile("\\[(.*?)\\]\\s*Category:\\s*(\\w+)\\s*\\|\\s*TEWS:\\s*(\\d+)(?:\\s*\\|\\s*by\\s*(.*))?");
        String pendingDecision = null;
        for (String raw : lines) {
            String line = raw.trim();
            Matcher m = entry.matcher(line);
            if (m.find()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append("**").append(m.group(1)).append("** — Category **").append(m.group(2))
                  .append("**, TEWS ").append(m.group(3));
                if (m.group(4) != null && !m.group(4).isBlank()) sb.append(", by ").append(m.group(4).trim());
                pendingDecision = null;
            } else if (line.startsWith("Decision:") && sb.length() > 0) {
                sb.append(". ").append(line.substring("Decision:".length()).trim());
            }
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    private static final Pattern INV = Pattern.compile(
            "\\[(.*?)\\]\\s*(.*?)\\s*\\((\\w+)\\)\\s*-\\s*Status:\\s*(\\w+)(.*)");

    private List<LineItem> parseInvestigations(String text) {
        List<LineItem> out = new ArrayList<>();
        if (!hasData(text)) return out;
        String[] lines = text.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = INV.matcher(lines[i].trim());
            if (!m.matches()) continue;
            String ts = m.group(1), name = m.group(2), typ = m.group(3), status = m.group(4);
            String rest = m.group(5) != null ? m.group(5) : "";
            boolean critical = rest.contains("[CRITICAL]");
            // Trailing person segment (drop Status/Priority; strip [CRITICAL]).
            String person = null;
            for (String seg : rest.replace("[CRITICAL]", "").split("\\|")) {
                String s = seg.trim();
                if (s.isEmpty() || s.startsWith("Priority:")) continue;
                person = s;
            }
            // Optional following "Result: ..." line.
            String result = null;
            if (i + 1 < lines.length && lines[i + 1].trim().startsWith("Result:"))
                result = lines[++i].trim().substring("Result:".length()).trim();
            String statusLabel = status + (critical ? " · CRITICAL" : "");
            String detail;
            if (result != null) {
                String name2 = stripLead(person, "ordered by ", "resulted by ", "requested by ");
                detail = result + (name2 != null ? " · " + name2 : "");
            } else {
                detail = person;
            }
            out.add(PdfReport.lineItem(ts, name.trim(), typ, statusLabel, detail));
        }
        return out;
    }

    private static final Pattern DIAG = Pattern.compile("\\[(.*?)\\]\\s*([A-Z_ ]+?):\\s*(.*)");

    private List<LineItem> parseDiagnoses(String text) {
        List<LineItem> out = new ArrayList<>();
        if (!hasData(text)) return out;
        for (String raw : text.split("\n")) {
            Matcher m = DIAG.matcher(raw.trim());
            if (!m.matches()) continue;
            String ts = m.group(1), status = m.group(2).trim(), rest = m.group(3).trim();
            String name = rest, detail = null;
            int by = rest.lastIndexOf(" - by ");
            if (by >= 0) { name = rest.substring(0, by).trim(); detail = "by " + rest.substring(by + 6).trim(); }
            out.add(PdfReport.lineItem(ts, name, null, status, detail));
        }
        return out;
    }

    /** Parsed acute-protocols block: two cards + red-flags + care-pathways line. */
    private static final class Acute {
        Card fastTrack;
        Card sepsis;
        List<RedFlag> redFlags = new ArrayList<>();
        String carePathways;
    }

    private Acute parseAcuteProtocols(String text) {
        Acute a = new Acute();
        if (!hasData(text)) return a;
        String[] lines = text.split("\n");
        String mode = null;
        // Buffers.
        String ftTitle = null, ftStatus = null, ftActBy = null;
        List<String> ftSub = new ArrayList<>();
        String sepResult = null, sepMetrics = null;
        List<String> sepSub = new ArrayList<>();
        List<String> pathways = new ArrayList<>();

        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("FAST-TRACK:")) {
                mode = "ft";
                Matcher m = Pattern.compile("FAST-TRACK:\\s*(.*?)\\s*\\[(.*?)\\]\\s*—\\s*activated\\s*(.*)").matcher(line);
                if (m.find()) {
                    ftTitle = m.group(1).trim();
                    ftStatus = m.group(2).trim();
                    ftActBy = m.group(3).trim();
                }
                continue;
            }
            if (line.startsWith("SEPSIS:")) {
                mode = "sepsis";
                Matcher m = Pattern.compile("SEPSIS:\\s*(.*?)\\s*—\\s*(.*)").matcher(line);
                if (m.find()) { sepResult = m.group(1).trim(); sepMetrics = m.group(2).trim(); }
                else sepResult = line.substring("SEPSIS:".length()).trim();
                continue;
            }
            if (line.startsWith("ACTIVE RED-FLAG SIGNS")) { mode = "redflag"; continue; }
            if (line.startsWith("CARE PATHWAYS")) { mode = "pathway"; continue; }

            if ("ft".equals(mode)) ftSub.add(line);
            else if ("sepsis".equals(mode)) sepSub.add(line);
            else if ("redflag".equals(mode) && line.startsWith("-")) {
                RedFlag rf = parseRedFlag(line.substring(1).trim());
                if (rf != null) a.redFlags.add(rf);
            } else if ("pathway".equals(mode) && line.startsWith("-")) {
                pathways.add(cleanPathway(line.substring(1).trim()));
            }
        }

        // Build fast-track card.
        if (ftTitle != null) {
            List<String> body = new ArrayList<>();
            String aspirin = subValue(ftSub, "Aspirin given:");
            String activated = ftActBy != null ? "Activated " + ftActBy : null;
            String l1 = joinNonBlank(" · ", activated, aspirin != null ? "Aspirin given: " + aspirin : null);
            if (!l1.isBlank()) body.add(l1);
            String outcome = subValue(ftSub, "Outcome:");
            if (outcome != null) body.add("Outcome: " + outcome);
            Color[] chip = PdfReport.chipColors(ftStatus);
            a.fastTrack = new Card("Fast track · " + ftTitle, PdfReport.ALERT_CRIT_HEAD,
                    ftStatus, chip[0], chip[1], PdfReport.ALLERGY_BG, PdfReport.ALLERGY_BORDER, body);
        }
        // Build sepsis card.
        if (sepResult != null) {
            List<String> body = new ArrayList<>();
            String source = subValue(sepSub, "Suspected source:");
            String l1 = joinNonBlank(" · ",
                    sepMetrics != null ? sepMetrics.replace(", ", " · ") : null,
                    source != null ? "suspected source: " + source : null);
            if (!l1.isBlank()) body.add(l1);
            String bundle = subValue(sepSub, "1-hour bundle:");
            String screened = subValue(sepSub, "Screened by");
            String l2 = joinNonBlank(" · ",
                    bundle != null ? "1-hour bundle: " + bundle.toLowerCase() : null,
                    screened != null ? "screened by " + screened : null);
            if (!l2.isBlank()) body.add(l2);
            boolean noSepsis = sepResult.toUpperCase().startsWith("NO ");
            Color chipBg = noSepsis ? PdfReport.CHIP_GREEN_BG : PdfReport.CHIP_RED_BG;
            Color chipFg = noSepsis ? PdfReport.CHIP_GREEN_FG : PdfReport.CHIP_RED_FG;
            a.sepsis = new Card("Sepsis screening", PdfReport.INK,
                    sepResult, chipBg, chipFg, Color.WHITE, PdfReport.HAIRLINE_SOFT, body);
        }
        if (!pathways.isEmpty())
            a.carePathways = "**Care Pathways** (" + pathways.size() + " active) — " + String.join(" · ", pathways);
        return a;
    }

    private static final Pattern REDFLAG = Pattern.compile("(.*?)\\s*\\[(.*?)\\]\\s*—\\s*(.*)");

    private RedFlag parseRedFlag(String line) {
        // Optional "** WORSENING **" emphasis prefix.
        line = line.replaceAll("^\\*\\*\\s*.*?\\s*\\*\\*\\s*", "").trim();
        Matcher m = REDFLAG.matcher(line);
        if (!m.matches()) return new RedFlag(titleCase(line), null, null, null, null);
        String name = titleCase(m.group(1).trim());
        String sev = m.group(2).trim();
        String after = m.group(3).trim();               // "PRESENT, 2026-.. by Jean Mugabo"
        String state = after;
        String person = null;
        int comma = after.indexOf(',');
        if (comma >= 0) state = after.substring(0, comma).trim();
        int by = after.lastIndexOf(" by ");
        if (by >= 0) person = after.substring(by + 4).trim();
        String chipText = !state.isBlank() ? state : sev;
        boolean worsening = chipText.equalsIgnoreCase("WORSENING");
        Color chipBg = worsening ? PdfReport.CHIP_ORANGE_BG : PdfReport.CHIP_RED_BG;
        Color chipFg = worsening ? PdfReport.CHIP_ORANGE_FG : PdfReport.CHIP_RED_FG;
        String trailing = joinNonBlank(" · ",
                sev.isBlank() ? null : "[" + sev + "]",
                person != null ? "by " + person : null);
        return new RedFlag(name, chipText, chipBg, chipFg, trailing.isBlank() ? null : trailing);
    }

    private String cleanPathway(String line) {
        // "Acute Coronary Syndrome Management [ACTIVE], activated 2026-.. by X" → drop the [STATE] tag.
        return line.replaceAll("\\s*\\[[^\\]]*\\]", "").replaceFirst(",\\s*activated", ", activated").trim();
    }

    private static final Pattern ALERT_HEAD = Pattern.compile(
            "\\[(\\w+)\\]\\s+(.+?)\\s+\\(([A-Z0-9_]+)\\)\\s+-\\s+(.+)");
    private static final Pattern ESCALATION = Pattern.compile(
            "\\[ESCALATED\\s*[—-]\\s*unacknowledged for\\s*(\\d+)\\s*min\\]");

    private List<AlertGroup> parseAlerts(String text) {
        List<AlertItem> crit = new ArrayList<>(), high = new ArrayList<>(),
                med = new ArrayList<>(), low = new ArrayList<>();
        if (hasData(text)) {
            String[] lines = text.split("\n");
            for (int i = 0; i < lines.length; i++) {
                Matcher m = ALERT_HEAD.matcher(lines[i].trim());
                if (!m.matches()) continue;
                String sev = m.group(1).toUpperCase();
                String title = m.group(2).trim();
                // Detail = following indented line(s).
                StringBuilder detail = new StringBuilder();
                while (i + 1 < lines.length && lines[i + 1].startsWith("    ")) {
                    if (detail.length() > 0) detail.append(' ');
                    detail.append(lines[++i].trim());
                }
                String body = detail.toString();
                String esc = null;
                Matcher e = ESCALATION.matcher(body);
                if (e.find()) { esc = "unacknowledged " + e.group(1) + " min"; body = e.replaceAll("").trim(); }
                AlertItem item = new AlertItem(title, body, esc);
                switch (sev) {
                    case "CRITICAL" -> crit.add(item);
                    case "HIGH" -> high.add(item);
                    case "MEDIUM" -> med.add(item);
                    default -> low.add(item);
                }
            }
        }
        List<AlertGroup> groups = new ArrayList<>();
        if (!crit.isEmpty()) groups.add(new AlertGroup("Critical · " + crit.size(),
                PdfReport.ALERT_CRIT_HEAD, PdfReport.ALERT_CRIT_BG, PdfReport.ALERT_CRIT_BAR, crit));
        if (!high.isEmpty()) groups.add(new AlertGroup("High · " + high.size(),
                PdfReport.ALERT_HIGH_HEAD, PdfReport.ALERT_HIGH_BG, PdfReport.ALERT_HIGH_BAR, high));
        if (!med.isEmpty()) groups.add(new AlertGroup("Medium · " + med.size(),
                PdfReport.ALERT_MED_HEAD, PdfReport.ALERT_MED_BG, PdfReport.ALERT_MED_BAR, med));
        if (!low.isEmpty()) groups.add(new AlertGroup("Low · " + low.size(),
                PdfReport.MUTED, PdfReport.PANEL, PdfReport.SLATE_400, low));
        return groups;
    }

    private static final Pattern MED = Pattern.compile("\\[(.*?)\\]\\s*(.*?)\\s*-\\s*Status:\\s*(\\w+)(.*)");

    private List<LineItem> parseMedications(String text) {
        List<LineItem> out = new ArrayList<>();
        if (!hasData(text)) return out;
        for (String raw : text.split("\n")) {
            Matcher m = MED.matcher(raw.trim());
            if (!m.matches()) continue;
            String ts = m.group(1), drug = m.group(2).trim(), status = m.group(3);
            String rest = m.group(4) != null ? m.group(4) : "";
            String label = drug.replace(" via ", " · ");
            String adminBy = null, prescribedBy = null;
            for (String seg : rest.split("\\|")) {
                String s = seg.trim();
                if (s.startsWith("Administered:")) {
                    int by = s.lastIndexOf(" by ");
                    if (by >= 0) adminBy = "by " + s.substring(by + 4).trim();
                } else if (s.startsWith("Prescribed by:")) {
                    prescribedBy = "prescribed by " + s.substring("Prescribed by:".length()).trim();
                }
            }
            String detail = joinNonBlank(" · ", adminBy, prescribedBy);
            out.add(PdfReport.lineItem(ts, label, null, status, detail.isBlank() ? null : detail));
        }
        return out;
    }

    /** Parsed medication audit: the "active at handover" line + a disposition table. */
    private static final class MedAudit {
        String activeLine;
        List<String[]> rows = new ArrayList<>();
    }

    private MedAudit parseMedAudit(String text) {
        MedAudit a = new MedAudit();
        if (!hasData(text)) return a;
        String active = firstGroup(text, "ACTIVE AT HANDOVER:\\s*(.*)");
        if (active != null) a.activeLine = "Active at handover: " + active.trim();

        // Numbered blocks: "1. Drug Route — Schedule" then Prescribed / Status / Note lines.
        String[] lines = text.split("\n");
        String med = null, prescribed = null, status = null, note = null;
        for (String raw : lines) {
            String line = raw.trim();
            Matcher head = Pattern.compile("^\\d+\\.\\s*(.*)").matcher(line);
            if (head.matches()) {
                if (med != null) a.rows.add(new String[]{med, prescribed, status, note});
                med = head.group(1).replace(" — ", " · ").trim();
                prescribed = status = note = null;
            } else if (line.startsWith("Prescribed")) {
                prescribed = firstGroup(line, "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})");
            } else if (line.startsWith("Status:")) {
                status = line.substring("Status:".length()).trim();
            } else if (line.startsWith("Note:")) {
                note = line.substring("Note:".length()).trim();
            }
        }
        if (med != null) a.rows.add(new String[]{med, prescribed, status, note});
        return a;
    }

    /** "Pending Investigations (2): a · b" bold lines (one per block). */
    private List<String> parseOutstanding(String text) {
        List<String> out = new ArrayList<>();
        if (!hasData(text)) return out;
        String[] lines = text.split("\n");
        String head = null;
        List<String> items = new ArrayList<>();
        for (String raw : lines) {
            String line = raw.trim();
            if (line.matches("Pending .*\\(\\d+\\):")) {
                if (head != null) out.add("**" + head + "** " + String.join(" · ", items));
                head = line.substring(0, line.length() - 1);   // drop trailing ':'
                items = new ArrayList<>();
            } else if (line.startsWith("-") && head != null) {
                items.add(line.substring(1).trim());
            }
        }
        if (head != null) out.add("**" + head + "** " + String.join(" · ", items));
        return out;
    }

    /** "**Latest progress note** — X: "..."" from the plan-of-care section, when present. */
    private String progressNote(String text) {
        if (!hasData(text)) return null;
        Matcher m = Pattern.compile("Latest Progress Note.*?—\\s*(.*?):\\s*\\n?(.*)", Pattern.DOTALL).matcher(text);
        if (m.find()) {
            String who = m.group(1).trim();
            String body = m.group(2).trim().replace("\n", " ");
            return "**Latest progress note** — " + who + ": “" + body + "”";
        }
        // Substantive non-progress plan text: keep it (bold lead).
        return "**Plan of care** — " + text.trim().replace("\n", " ");
    }

    // ── Small helpers ───────────────────────────────────────────────

    private static String location(Visit visit) {
        if (visit == null) return null;
        String zone = visit.getCurrentEdZone() != null ? "Zone " + visit.getCurrentEdZone().name() : null;
        Bed bed = visit.getCurrentBed();
        String bedStr = null;
        if (bed != null && bed.getCode() != null) {
            bedStr = "Bed " + bed.getCode() + (bed.getLabel() != null ? " (" + bed.getLabel() + ")" : "");
        }
        String loc = joinNonBlank(", ", zone, bedStr);
        return loc.isBlank() ? null : loc;
    }

    private static String genderLabel(Gender g) {
        if (g == null || g == Gender.UNKNOWN) return null;
        return g == Gender.MALE ? "Male" : g == Gender.FEMALE ? "Female" : g.name();
    }

    private static String ageSex(Patient patient) {
        if (patient == null) return null;
        String age = null;
        if (patient.getDateOfBirth() != null) {
            int years = Period.between(patient.getDateOfBirth(), LocalDate.now(KIGALI)).getYears();
            if (years >= 0) age = String.valueOf(years);
        }
        String sex = genderLabel(patient.getGender());
        if (age == null && sex == null) return null;
        if (age == null) return sex;
        if (sex == null) return age;
        return age + " · " + sex;
    }

    private static Color satsColor(TriageCategory cat) {
        return switch (cat) {
            case RED -> PdfReport.SATS_RED;
            case ORANGE -> PdfReport.SATS_ORANGE;
            case YELLOW -> PdfReport.SATS_YELLOW;
            case GREEN -> PdfReport.SATS_GREEN;
            case BLUE -> PdfReport.SATS_BLUE;
        };
    }

    /** "SHIFT_HANDOVER" → "Shift handover". */
    private static String prettyType(String name) {
        String s = name.replace('_', ' ').trim();
        if (s.isEmpty()) return "Handover";
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String titleCase(String s) {
        if (s == null || s.isBlank()) return s;
        String lower = s.trim().toLowerCase();
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static String lowerFirst(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    /** First regex capture group in the text, or null. */
    private static String firstGroup(String text, String regex) {
        if (text == null) return null;
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    /** Value after a "Label:" prefix among indented sub-lines (e.g. "Aspirin given: No"). */
    private static String subValue(List<String> subs, String label) {
        for (String s : subs) {
            if (s.startsWith(label)) return s.substring(label.length()).trim();
        }
        return null;
    }

    private static String stripLead(String s, String... leads) {
        if (s == null) return null;
        for (String lead : leads) if (s.startsWith(lead)) return s.substring(lead.length()).trim();
        return s;
    }

    private static String stripPrefix(String s, String prefix) {
        if (s == null) return null;
        String t = s.trim();
        return t.startsWith(prefix) ? t.substring(prefix.length()).trim() : t;
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static boolean isBlank(String s) { return s == null || s.isBlank(); }

    /** True when a section carries real data (not blank and not a "No ..." placeholder). */
    private static boolean hasData(String s) {
        if (isBlank(s)) return false;
        String t = s.trim();
        return !t.matches("(?is)No [\\w &/-]+ (recorded|ordered|prescribed|available|documented)\\.?");
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
}
