package com.smartTriage.smartTriage_server.module.ems.service;

import com.smartTriage.smartTriage_server.common.enums.EmsInterventionType;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsIntervention;
import com.smartTriage.smartTriage_server.module.ems.entity.EmsRun;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.patient.entity.Patient;
import com.smartTriage.smartTriage_server.module.visit.entity.Visit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders an {@link EmsRun} (+ its interventions) into a professional, printable Patient Care
 * Report (PCR) PDF — the standard pre-hospital handoff artifact.
 *
 * <p>Built on the shared {@link PdfReport} house-style kit (branded masthead, section headers,
 * key/value tables, vitals stat-tiles, bullet lists, narrative panel, and the every-page footer
 * that carries the confidentiality label + who exported it + Page X of Y). The report walks the
 * full run: patient banner (with an unmistakable unidentified flag), crew, timeline (with computed
 * intervals), incident, field triage, on-scene vitals, medications/fluids, procedures, transfer of
 * care, and the paramedic narrative.
 *
 * <p>Server-side (OpenPDF, via {@link PdfReport}). Must be invoked inside an open transaction — it
 * reads the lazy visit / patient / hospital associations.
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

    /**
     * Render the run's PCR. {@code exportedBy} is the display name of the authenticated caller who
     * requested the export (resolved in the controller); it is recorded on the masthead + footer of
     * every page for traceability.
     */
    public byte[] render(EmsRun run, List<EmsIntervention> interventions, String exportedBy) {
        Hospital h = safeHospital(run);
        String orgName = h != null && h.getName() != null && !h.getName().isBlank() ? h.getName() : "Hospital";

        List<String> orgMeta = new ArrayList<>();
        if (h != null) {
            if (h.getHospitalCode() != null && !h.getHospitalCode().isBlank()) {
                orgMeta.add("Code: " + h.getHospitalCode());
            }
            String addr = joinNonBlank(", ", h.getAddress(), h.getCity(), h.getCountry());
            if (!addr.isBlank()) orgMeta.add(addr);
            if (h.getPhoneNumber() != null && !h.getPhoneNumber().isBlank()) {
                orgMeta.add("Tel: " + h.getPhoneNumber());
            }
        }

        try {
            PdfReport r = PdfReport.beginLedger(new PdfReport.Spec(
                    "Patient Care Report (PCR)",
                    "Patient Care Report",
                    orgName,
                    orgMeta,
                    exportedBy,
                    "EMS patient care report",
                    "Pre-hospital · EMS"));

            renderSubject(r, run);

            int s = 0;
            r.ledgerSection(sec(++s), "Run & Crew");
            r.ledgerKv(buildRunCrew(run));
            r.ledgerSection(sec(++s), "Timeline");
            r.ledgerKv(buildTimeline(run));
            r.ledgerSection(sec(++s), "Incident");
            r.ledgerKv(buildIncident(run));
            r.ledgerSection(sec(++s), "Field Triage");
            r.ledgerKv(buildFieldTriage(run));

            r.ledgerSection(sec(++s), "On-Scene Vital Signs");
            renderVitals(r, run);

            r.ledgerSection(sec(++s), "Medications & Fluids Given");
            renderInterventions(r, interventions, true);
            r.ledgerSection(sec(++s), "Procedures & Interventions");
            renderInterventions(r, interventions, false);

            r.ledgerSection(sec(++s), "Transfer of Care");
            r.ledgerKv(buildTransferOfCare(run));

            r.ledgerSection(sec(++s), "Narrative");
            if (notBlank(run.getNotes())) prose(r, run.getNotes());
            else r.paragraph("No narrative recorded.", PdfReport.F_BODY);

            // Transfer-of-care is a signed handoff — printable sign-off lines for both parties.
            r.ledgerSignatures("Handing over — EMS crew", "Receiving — ED clinician");

            return r.finish();
        } catch (Exception e) {
            log.error("Failed to render EMS PCR PDF for run {}: {}",
                    run.getId(), e.getMessage(), e);
            throw new IllegalStateException("Could not generate EMS PCR PDF", e);
        }
    }

    // ── Subject: the #1b bordered ID table (field-triage category as an outlined chip) ──
    private void renderSubject(PdfReport r, EmsRun run) {
        Patient patient = safePatient(run);
        boolean unidentified = patient != null && patient.isUnidentified();
        if (unidentified) {
            r.paragraph("UNIDENTIFIED PATIENT — identity unresolved", PdfReport.F_ALERT);
        }

        String patientName = patient != null
                ? (nullToEmpty(patient.getFirstName()) + " " + nullToEmpty(patient.getLastName())).trim()
                : "";
        if (patientName.isBlank()) patientName = "Unidentified field patient";

        List<PdfReport.LedgerCell> ids = new ArrayList<>();
        ids.add(PdfReport.lcell("Patient", patientName));
        // Field triage category as the outlined SATS-coloured chip, when captured.
        String cat = run.getFieldTriageCategory();
        if (cat != null && !cat.isBlank()) {
            ids.add(PdfReport.lcellChip("Field triage", cat.toUpperCase(), satsColor(cat)));
        }
        String ageSex = joinDot(
                run.getPatientAgeYears() != null ? String.valueOf(run.getPatientAgeYears()) : null,
                run.getPatientSex() != null && !run.getPatientSex().isBlank() ? run.getPatientSex() : null);
        if (ageSex != null) ids.add(PdfReport.lcell("Age / Sex", ageSex));
        if (patient != null && patient.getMedicalRecordNumber() != null && !patient.getMedicalRecordNumber().isBlank()) {
            ids.add(PdfReport.lcellMono("MRN", patient.getMedicalRecordNumber()));
        }
        Visit visit = safeVisit(run);
        if (visit != null && visit.getVisitNumber() != null && !visit.getVisitNumber().isBlank()) {
            ids.add(PdfReport.lcellMono("ED Visit", visit.getVisitNumber()));
        }
        if (patient != null && patient.getNationalId() != null && !patient.getNationalId().isBlank()) {
            ids.add(PdfReport.lcellMono("National ID", patient.getNationalId()));
        }
        r.ledgerIdTable(ids);
    }

    /** "a · b" from non-blank parts, or null. */
    private static String joinDot(String a, String b) {
        if (a == null && b == null) return null;
        if (a == null) return b;
        if (b == null) return a;
        return a + " · " + b;
    }

    /** SATS category string → pill color (null-safe). */
    private static java.awt.Color satsColor(String cat) {
        if (cat == null) return null;
        String k = cat.toUpperCase();
        if (k.contains("RED")) return PdfReport.SATS_RED;
        if (k.contains("ORANGE")) return PdfReport.SATS_ORANGE;
        if (k.contains("YELLOW")) return PdfReport.SATS_YELLOW;
        if (k.contains("GREEN")) return PdfReport.SATS_GREEN;
        if (k.contains("BLUE")) return PdfReport.SATS_BLUE;
        return PdfReport.SLATE_400;
    }

    // ── Sections (flat #1b bordered key/value tables) ────────────────
    private List<PdfReport.LedgerCell> buildRunCrew(EmsRun run) {
        List<PdfReport.LedgerCell> c = new ArrayList<>();
        add(c, "Service", run.getService() != null ? run.getService().name() : null);
        add(c, "Unit callsign", run.getUnitCallsign());
        add(c, "Crew / Paramedic", run.getParamedicName());
        add(c, "Status", run.getStatus() != null ? run.getStatus().name() : null);
        add(c, "Lifecycle stage", lifecycleStage(run));
        add(c, "Priority transport (lights)", run.isLightsActive() ? "ACTIVE" : "No");
        addFull(c, "Cancelled reason", run.getCancelReason());
        return c;
    }

    private List<PdfReport.LedgerCell> buildTimeline(EmsRun run) {
        List<PdfReport.LedgerCell> c = new ArrayList<>();
        addMono(c, "Dispatched", ts(run.getDispatchedAt()));
        addMono(c, "Scene arrived", ts(run.getSceneArrivedAt()));
        addMono(c, "Scene left", ts(run.getSceneLeftAt()));
        addMono(c, "ED arrived", ts(run.getEdArrivedAt()));
        addMono(c, "Handed off", ts(run.getHandedOffAt()));
        // Computed intervals — only where both endpoints exist.
        addMono(c, "Response time", interval(run.getDispatchedAt(), run.getSceneArrivedAt()));
        addMono(c, "On-scene time", interval(run.getSceneArrivedAt(), run.getSceneLeftAt()));
        addMono(c, "Transport time", interval(run.getSceneLeftAt(), run.getEdArrivedAt()));
        addMono(c, "Total run time", interval(run.getDispatchedAt(), run.getHandedOffAt()));
        return c;
    }

    private List<PdfReport.LedgerCell> buildIncident(EmsRun run) {
        List<PdfReport.LedgerCell> c = new ArrayList<>();
        add(c, "Location", run.getIncidentLocation());
        add(c, "Mechanism", run.getMechanism());
        addMono(c, "ETA (minutes)", run.getEtaMinutes() != null ? run.getEtaMinutes().toString() : null);
        Visit v = safeVisit(run);
        addFull(c, "Chief complaint", v != null ? v.getChiefComplaint() : null);
        addFull(c, "History", run.getHistorySummary());
        addFull(c, "Injuries observed", run.getInjuriesObserved());
        return c;
    }

    private List<PdfReport.LedgerCell> buildFieldTriage(EmsRun run) {
        List<PdfReport.LedgerCell> c = new ArrayList<>();
        add(c, "Category", run.getFieldTriageCategory());
        addMono(c, "TEWS", run.getFieldTewsScore() != null ? run.getFieldTewsScore().toString() : null);
        add(c, "Pediatric", Boolean.TRUE.equals(run.getFieldTriageIsChild()) ? "Yes" : null);
        addFull(c, "Reason", run.getFieldTriageReason());
        return c;
    }

    /** On-scene vitals as the #1b bordered mono table: a header row of present vitals over one value row. */
    private void renderVitals(PdfReport r, EmsRun run) {
        List<String> labels = new ArrayList<>();
        List<String> values = new ArrayList<>();
        addVital(labels, values, "GCS", num(run.getFieldGcs()));
        addVital(labels, values, "RR", num(run.getFieldRespRate()));
        addVital(labels, values, "HR", num(run.getFieldHr()));
        String bp = (run.getFieldSbp() != null || run.getFieldDbp() != null)
                ? (num(run.getFieldSbp()) + "/" + num(run.getFieldDbp())) : "";
        addVital(labels, values, "BP mmHg", (bp.isBlank() || bp.equals("/")) ? null : bp);
        addVital(labels, values, "SpO2 %", run.getFieldSpo2() != null ? run.getFieldSpo2().toString() : null);
        addVital(labels, values, "Temp °C", run.getFieldTemp() != null ? run.getFieldTemp().toPlainString() : null);
        addVital(labels, values, "Glucose mmol/L", run.getFieldGlucose() != null ? run.getFieldGlucose().toPlainString() : null);
        if (labels.isEmpty()) {
            r.paragraph("No on-scene vitals recorded.", PdfReport.F_BODY);
            return;
        }
        boolean[] mono = new boolean[labels.size()];
        java.util.Arrays.fill(mono, true);
        List<String[]> valueRow = new ArrayList<>();
        valueRow.add(values.toArray(new String[0]));
        r.ledgerDataTable(labels.toArray(new String[0]), null, valueRow, mono);
    }

    /**
     * Render an intervention bucket as a #1b bordered data table. When {@code medsAndFluids} is true,
     * only the MEDICATION / FLUID types are emitted; otherwise every other intervention type is.
     * Columns: Time (mono) · Type · Detail (detail · dose · route) · By · Outcome (+ notes).
     */
    private void renderInterventions(PdfReport r, List<EmsIntervention> interventions, boolean medsAndFluids) {
        List<String[]> rows = new ArrayList<>();
        if (interventions != null) {
            for (EmsIntervention iv : interventions) {
                boolean isMedOrFluid = iv.getType() == EmsInterventionType.MEDICATION
                        || iv.getType() == EmsInterventionType.FLUID;
                if (medsAndFluids != isMedOrFluid) continue;

                String time = iv.getGivenAt() != null ? ts(iv.getGivenAt()) : "—";
                String type = iv.getType() != null ? iv.getType().getDescription() : "—";
                List<String> spec = new ArrayList<>();
                if (iv.getDetail() != null && !iv.getDetail().isBlank()) spec.add(iv.getDetail());
                if (iv.getDose() != null && !iv.getDose().isBlank()) spec.add(iv.getDose());
                if (iv.getRoute() != null && !iv.getRoute().isBlank()) spec.add(iv.getRoute());
                String detail = spec.isEmpty() ? "—" : String.join(" · ", spec);
                String by = iv.getGivenByName() != null && !iv.getGivenByName().isBlank() ? iv.getGivenByName() : "—";
                StringBuilder outcome = new StringBuilder();
                if (iv.getOutcome() != null && !iv.getOutcome().isBlank()) outcome.append(iv.getOutcome());
                if (iv.getNotes() != null && !iv.getNotes().isBlank()) {
                    if (outcome.length() > 0) outcome.append(' ');
                    outcome.append('(').append(iv.getNotes()).append(')');
                }
                rows.add(new String[]{time, type, detail, by, outcome.length() == 0 ? "—" : outcome.toString()});
            }
        }
        if (rows.isEmpty()) {
            r.paragraph("None recorded.", PdfReport.F_BODY);
            return;
        }
        r.ledgerDataTable(new String[]{"Time", "Type", "Detail", "By", "Outcome"},
                new float[]{16, 18, 34, 16, 16}, rows,
                new boolean[]{true, false, false, false, false});
    }

    private List<PdfReport.LedgerCell> buildTransferOfCare(EmsRun run) {
        List<PdfReport.LedgerCell> c = new ArrayList<>();
        add(c, "Handed off to", run.getHandedOffToName());
        addFull(c, "Acknowledgement", run.getHandoverAcknowledgementText());
        if (run.getPreArrivalAckedByName() != null && !run.getPreArrivalAckedByName().isBlank()) {
            addFull(c, "Pre-arrival acknowledged by", run.getPreArrivalAckedByName()
                    + (run.getPreArrivalAckedAt() != null ? " (" + ts(run.getPreArrivalAckedAt()) + ")" : ""));
        }
        return c;
    }

    // ── ledger-cell + section builders ───────────────────────────────
    private static void add(List<PdfReport.LedgerCell> c, String label, String v) {
        if (notBlank(v)) c.add(PdfReport.lcell(label, v));
    }

    private static void addMono(List<PdfReport.LedgerCell> c, String label, String v) {
        if (notBlank(v)) c.add(PdfReport.lcellMono(label, v));
    }

    private static void addFull(List<PdfReport.LedgerCell> c, String label, String v) {
        if (notBlank(v)) c.add(PdfReport.lcellFull(label, v));
    }

    private static void addVital(List<String> labels, List<String> values, String label, String v) {
        if (notBlank(v)) { labels.add(label); values.add(v); }
    }

    /** Two-digit #1b section number: 1 → "01". */
    private static String sec(int n) { return n < 10 ? "0" + n : String.valueOf(n); }

    /** Emit the paramedic narrative as flat #1b body lines (blank lines → a small gap). */
    private static void prose(PdfReport r, String text) {
        if (text == null || text.isBlank()) return;
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) { r.spacer(4f); continue; }
            r.paragraph(line.replace("**", "").trim(), PdfReport.F_BODY);
        }
    }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    // ── Helpers ─────────────────────────────────────────────────────

    /**
     * Case-lifecycle stage, mirroring {@code EmsRunMapper.lifecycleStage} so the PCR agrees with
     * the dashboard card / board on where the ambulance case is.
     */
    private static String lifecycleStage(EmsRun run) {
        if (run.getStatus() == null) return "DISPATCHED";
        return switch (run.getStatus()) {
            case CANCELLED -> "CANCELLED";
            case HANDED_OFF -> "HANDED_OFF";
            case ARRIVED -> run.getArrivalAckedAt() != null ? "RECEIVED" : "AT_DOOR";
            case EN_ROUTE -> "EN_ROUTE";
            case DISPATCHED -> "DISPATCHED";
        };
    }

    /** Duration between two instants as "Hh Mm" / "Nm", or null if either endpoint is missing/inverted. */
    private static String interval(Instant from, Instant to) {
        if (from == null || to == null) return null;
        long minutes = Duration.between(from, to).toMinutes();
        if (minutes < 0) return null;
        if (minutes < 60) return minutes + "m";
        long h = minutes / 60;
        long m = minutes % 60;
        return m == 0 ? h + "h" : h + "h " + m + "m";
    }

    private static String num(Integer v) { return v != null ? v.toString() : null; }
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
