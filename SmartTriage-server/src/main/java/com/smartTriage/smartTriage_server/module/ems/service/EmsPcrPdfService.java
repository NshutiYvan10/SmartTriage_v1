package com.smartTriage.smartTriage_server.module.ems.service;

import com.smartTriage.smartTriage_server.common.enums.EmsInterventionType;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.common.report.PdfReport.KeyVal;
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

import static com.smartTriage.smartTriage_server.common.report.PdfReport.kv;

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
            PdfReport r = PdfReport.begin(new PdfReport.Spec(
                    "PRE-HOSPITAL — PATIENT CARE REPORT (PCR)",
                    "Patient Care Report",
                    orgName,
                    orgMeta,
                    exportedBy,
                    "EMS patient care report"));

            renderSubject(r, run);
            r.sectionHeader("Run & Crew");
            r.keyValues(buildRunCrew(run));
            r.sectionHeader("Timeline");
            r.keyValues(buildTimeline(run));
            r.sectionHeader("Incident");
            r.keyValues(buildIncident(run));
            r.sectionHeader("Field Triage");
            r.keyValues(buildFieldTriage(run));
            r.sectionHeader("On-Scene Vital Signs");
            r.statTiles(buildVitals(run));
            r.sectionHeader("Medications & Fluids Given");
            r.bullets(buildMedications(interventions));
            r.sectionHeader("Procedures & Interventions");
            r.bullets(buildProcedures(interventions));
            r.sectionHeader("Transfer of Care");
            r.keyValues(buildTransferOfCare(run));
            r.sectionHeader("Narrative");
            r.narrative(run.getNotes());

            return r.finish();
        } catch (Exception e) {
            log.error("Failed to render EMS PCR PDF for run {}: {}",
                    run.getId(), e.getMessage(), e);
            throw new IllegalStateException("Could not generate EMS PCR PDF", e);
        }
    }

    // ── Subject banner ──────────────────────────────────────────────
    private void renderSubject(PdfReport r, EmsRun run) {
        Patient patient = safePatient(run);
        boolean unidentified = patient != null && patient.isUnidentified();
        if (unidentified) {
            r.alertBanner("UNIDENTIFIED PATIENT — identity unresolved");
        }

        String patientName = patient != null
                ? (nullToEmpty(patient.getFirstName()) + " " + nullToEmpty(patient.getLastName())).trim()
                : "";
        if (patientName.isBlank()) patientName = "Unidentified field patient";

        List<String> meta = new ArrayList<>();
        if (run.getPatientAgeYears() != null) meta.add("Age " + run.getPatientAgeYears());
        if (run.getPatientSex() != null && !run.getPatientSex().isBlank()) meta.add("Sex " + run.getPatientSex());
        if (patient != null && patient.getNationalId() != null && !patient.getNationalId().isBlank()) {
            meta.add("National ID " + patient.getNationalId());
        }
        if (patient != null && patient.getMedicalRecordNumber() != null && !patient.getMedicalRecordNumber().isBlank()) {
            meta.add("MRN " + patient.getMedicalRecordNumber());
        }
        Visit visit = safeVisit(run);
        if (visit != null && visit.getVisitNumber() != null && !visit.getVisitNumber().isBlank()) {
            meta.add("ED Visit " + visit.getVisitNumber());
        }
        r.subjectHeadline(patientName, String.join("  ·  ", meta));
    }

    // ── Sections ────────────────────────────────────────────────────
    private List<KeyVal> buildRunCrew(EmsRun run) {
        List<KeyVal> pairs = new ArrayList<>();
        pairs.add(kv("Service", run.getService() != null ? run.getService().name() : null));
        pairs.add(kv("Unit callsign", run.getUnitCallsign()));
        pairs.add(kv("Crew / Paramedic", run.getParamedicName()));
        pairs.add(kv("Status", run.getStatus() != null ? run.getStatus().name() : null));
        pairs.add(kv("Lifecycle stage", lifecycleStage(run)));
        pairs.add(kv("Priority transport (lights)", run.isLightsActive() ? "ACTIVE" : "No"));
        pairs.add(kv("Cancelled reason", run.getCancelReason()));
        return pairs;
    }

    private List<KeyVal> buildTimeline(EmsRun run) {
        List<KeyVal> pairs = new ArrayList<>();
        pairs.add(kv("Dispatched", ts(run.getDispatchedAt())));
        pairs.add(kv("Scene arrived", ts(run.getSceneArrivedAt())));
        pairs.add(kv("Scene left", ts(run.getSceneLeftAt())));
        pairs.add(kv("ED arrived", ts(run.getEdArrivedAt())));
        pairs.add(kv("Handed off", ts(run.getHandedOffAt())));
        // Computed intervals — only where both endpoints exist.
        pairs.add(kv("Response time", interval(run.getDispatchedAt(), run.getSceneArrivedAt())));
        pairs.add(kv("On-scene time", interval(run.getSceneArrivedAt(), run.getSceneLeftAt())));
        pairs.add(kv("Transport time", interval(run.getSceneLeftAt(), run.getEdArrivedAt())));
        pairs.add(kv("Total run time", interval(run.getDispatchedAt(), run.getHandedOffAt())));
        return pairs;
    }

    private List<KeyVal> buildIncident(EmsRun run) {
        List<KeyVal> pairs = new ArrayList<>();
        pairs.add(kv("Location", run.getIncidentLocation()));
        pairs.add(kv("Mechanism", run.getMechanism()));
        Visit v = safeVisit(run);
        pairs.add(kv("Chief complaint", v != null ? v.getChiefComplaint() : null));
        pairs.add(kv("History", run.getHistorySummary()));
        pairs.add(kv("Injuries observed", run.getInjuriesObserved()));
        pairs.add(kv("ETA (minutes)", run.getEtaMinutes() != null ? run.getEtaMinutes().toString() : null));
        return pairs;
    }

    private List<KeyVal> buildFieldTriage(EmsRun run) {
        List<KeyVal> pairs = new ArrayList<>();
        pairs.add(kv("Category", run.getFieldTriageCategory()));
        pairs.add(kv("TEWS", run.getFieldTewsScore() != null ? run.getFieldTewsScore().toString() : null));
        pairs.add(kv("Reason", run.getFieldTriageReason()));
        pairs.add(kv("Pediatric", Boolean.TRUE.equals(run.getFieldTriageIsChild()) ? "Yes" : null));
        return pairs;
    }

    private List<KeyVal> buildVitals(EmsRun run) {
        List<KeyVal> tiles = new ArrayList<>();
        tiles.add(kv("GCS", num(run.getFieldGcs())));
        tiles.add(kv("RR", num(run.getFieldRespRate())));
        tiles.add(kv("HR", num(run.getFieldHr())));
        String bp = (run.getFieldSbp() != null || run.getFieldDbp() != null)
                ? (num(run.getFieldSbp()) + "/" + num(run.getFieldDbp())) : "";
        tiles.add(kv("BP mmHg", (bp.isBlank() || bp.equals("/")) ? null : bp));
        tiles.add(kv("SpO2 %", run.getFieldSpo2() != null ? run.getFieldSpo2().toString() : null));
        tiles.add(kv("Temp °C", run.getFieldTemp() != null ? run.getFieldTemp().toPlainString() : null));
        tiles.add(kv("Glucose mmol/L", run.getFieldGlucose() != null ? run.getFieldGlucose().toPlainString() : null));
        return tiles;
    }

    /** Medications & fluids given — the MEDICATION / FLUID interventions. */
    private List<String> buildMedications(List<EmsIntervention> interventions) {
        return formatInterventions(interventions, true);
    }

    /** Every OTHER intervention (oxygen, airway, CPR, splinting, tourniquet…). */
    private List<String> buildProcedures(List<EmsIntervention> interventions) {
        return formatInterventions(interventions, false);
    }

    /**
     * Format the interventions into bullet lines. When {@code medsAndFluids} is true, only the
     * MEDICATION / FLUID types are emitted (with a dose-forward layout); otherwise every remaining
     * intervention type is emitted (the procedures / interventions bucket).
     */
    private List<String> formatInterventions(List<EmsIntervention> interventions, boolean medsAndFluids) {
        List<String> lines = new ArrayList<>();
        if (interventions == null) return lines;
        for (EmsIntervention iv : interventions) {
            boolean isMedOrFluid = iv.getType() == EmsInterventionType.MEDICATION
                    || iv.getType() == EmsInterventionType.FLUID;
            if (medsAndFluids != isMedOrFluid) continue;

            List<String> parts = new ArrayList<>();
            // Lead with the type label for procedures (so a bare "CPR" reads clearly); meds/fluids
            // lead with the drug detail.
            if (!medsAndFluids && iv.getType() != null) parts.add(iv.getType().getDescription());
            if (iv.getDetail() != null && !iv.getDetail().isBlank()) parts.add(iv.getDetail());
            if (iv.getDose() != null && !iv.getDose().isBlank()) parts.add(iv.getDose());
            if (iv.getRoute() != null && !iv.getRoute().isBlank()) parts.add(iv.getRoute());
            if (iv.getGivenAt() != null) parts.add("@ " + ts(iv.getGivenAt()));
            if (iv.getGivenByName() != null && !iv.getGivenByName().isBlank()) parts.add("by " + iv.getGivenByName());

            StringBuilder line = new StringBuilder(String.join("  ·  ", parts));
            if (iv.getOutcome() != null && !iv.getOutcome().isBlank()) {
                line.append("  →  ").append(iv.getOutcome());
            }
            if (iv.getNotes() != null && !iv.getNotes().isBlank()) {
                line.append("  (").append(iv.getNotes()).append(")");
            }
            String text = line.toString().trim();
            if (!text.isBlank()) lines.add(text);
        }
        return lines;
    }

    private List<KeyVal> buildTransferOfCare(EmsRun run) {
        List<KeyVal> pairs = new ArrayList<>();
        pairs.add(kv("Handed off to", run.getHandedOffToName()));
        pairs.add(kv("Acknowledgement", run.getHandoverAcknowledgementText()));
        if (run.getPreArrivalAckedByName() != null && !run.getPreArrivalAckedByName().isBlank()) {
            pairs.add(kv("Pre-arrival acknowledged by", run.getPreArrivalAckedByName()
                    + (run.getPreArrivalAckedAt() != null ? " (" + ts(run.getPreArrivalAckedAt()) + ")" : "")));
        }
        return pairs;
    }

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
