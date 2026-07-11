package com.smartTriage.smartTriage_server.module.handover.service;

import com.smartTriage.smartTriage_server.common.enums.Gender;
import com.smartTriage.smartTriage_server.common.enums.TriageCategory;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
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
import java.util.function.Supplier;

/**
 * Renders a {@link HandoverReport} into a professional, printable PDF for
 * physical handover and record-keeping. Rendered through the shared
 * {@link PdfReport} kit: branded masthead, a patient banner with the acuity
 * pill, an unmistakable unidentified-patient flag, every on-screen section
 * verbatim (nothing summarised away) grouped under the S/B/A/R framework, a
 * red callout for active clinical alerts, an acknowledgement signature block,
 * and the standard confidentiality + attribution + page-number footer.
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

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(KIGALI);
    private static final DateTimeFormatter ARRIVED =
            DateTimeFormatter.ofPattern("dd MMM · HH:mm").withZone(KIGALI);
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

        try {
            PdfReport r = PdfReport.begin(new PdfReport.Spec(
                    "Handover · SBAR",
                    "Handover / SBAR",
                    orgName,
                    hospitalMeta(h),
                    exportedBy,
                    "clinical handover report",
                    "Emergency department"));

            renderPatientBanner(r, report);
            renderStatusBanners(r, report);
            renderMetaStrip(r, report, type);
            renderSections(r, report);
            renderAcknowledgement(r, report);

            return r.finish();
        } catch (Exception e) {
            log.error("Failed to render handover PDF for report {}: {}", report.getId(), e.getMessage(), e);
            throw new IllegalStateException("Could not generate handover PDF", e);
        }
    }

    // ── Hospital masthead meta ──────────────────────────────────────

    private List<String> hospitalMeta(Hospital h) {
        List<String> bits = new ArrayList<>();
        if (h == null) return bits;
        if (h.getHospitalCode() != null) bits.add("Code: " + h.getHospitalCode());
        if (h.getTier() != null) bits.add(h.getTier().toString());
        String addr = joinNonBlank(" ", h.getAddress(), h.getCity(), h.getCountry());
        if (!addr.isBlank()) bits.add(addr);
        if (h.getPhoneNumber() != null) bits.add("Tel: " + h.getPhoneNumber());
        if (h.getEmail() != null) bits.add(h.getEmail());
        return bits;
    }

    // ── Patient banner + status banners + parameter strip ──────────

    private void renderPatientBanner(PdfReport r, HandoverReport report) {
        Visit visit = report.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;

        String patientName = patient != null
                ? (patient.getFirstName() + " " + patient.getLastName()).trim() : "Unknown";
        if (patientName.isBlank()) patientName = "Unknown";

        List<PdfReport.KeyVal> ids = new ArrayList<>();
        if (visit != null && visit.getVisitNumber() != null) {
            ids.add(PdfReport.kv("Visit", visit.getVisitNumber()));
        }
        if (patient != null && patient.getMedicalRecordNumber() != null) {
            ids.add(PdfReport.kv("MRN", patient.getMedicalRecordNumber()));
        }
        String ageSex = ageSex(patient);
        if (ageSex != null) ids.add(PdfReport.kv("Age / Sex", ageSex));
        if (visit != null && visit.getArrivalTime() != null) {
            ids.add(PdfReport.kv("Arrived", ARRIVED.format(visit.getArrivalTime())));
        }

        TriageCategory cat = visit != null ? visit.getCurrentTriageCategory() : null;
        if (cat != null) {
            r.patientBanner(patientName, ids, cat.name(), cat.getDescription(), satsColor(cat));
        } else {
            r.patientBanner(patientName, ids, null, null, null);
        }
    }

    private void renderStatusBanners(PdfReport r, HandoverReport report) {
        Visit visit = report.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;
        // Unmistakable unidentified-patient flag — this is a red alert, not a status note.
        if (patient != null && patient.isUnidentified()) {
            r.alertBanner("UNIDENTIFIED PATIENT — identity unresolved");
        }
        if (!report.isAcknowledged()) {
            r.statusBanner("Awaiting acknowledgement by the receiving clinician");
        }
    }

    private void renderMetaStrip(PdfReport r, HandoverReport report, String type) {
        String gen = report.getGeneratedAt() != null ? TS.format(report.getGeneratedAt()) : "—";
        String ack = report.isAcknowledged()
                ? ("Acknowledged " + (report.getAcknowledgedAt() != null ? TS.format(report.getAcknowledgedAt()) : "")
                   + (report.getReceivedByName() != null ? " by " + report.getReceivedByName() : "")).trim()
                : "Pending";
        r.metaStrip(List.of(
                PdfReport.kv("Report type", type),
                PdfReport.kv("Generated", gen),
                PdfReport.kv("Handing over", report.getGeneratedByName()),
                PdfReport.kv("Acknowledgement", ack)));
    }

    // ── SBAR sections ───────────────────────────────────────────────

    /**
     * Every on-screen section renders verbatim (blank ones are skipped), grouped
     * under the SBAR framework. Order within each group mirrors the on-screen
     * report exactly — nothing summarised away.
     */
    private void renderSections(PdfReport r, HandoverReport report) {
        sbarGroup(r, "S", "Situation", List.of(
                section("Patient Summary", report::getPatientSummary),
                section("Presenting Complaint", report::getPresentingComplaint)));

        sbarGroup(r, "B", "Background", List.of(
                section("Pre-Hospital / EMS", report::getPrehospitalSummary),
                section("Triage Summary", report::getTriageSummary),
                section("Vital Signs Trend", report::getVitalSignsTrend),
                section("Investigations & Results", report::getInvestigationsResults)));

        sbarGroup(r, "A", "Assessment", List.of(
                section("Diagnosis Summary", report::getDiagnosisSummary),
                section("Acute Protocols & Critical Events", report::getAcuteProtocols),
                alertSection("Active Clinical Alerts", report::getActiveClinicalAlerts)));

        sbarGroup(r, "R", "Recommendation", List.of(
                section("Treatment Summary", report::getTreatmentSummary),
                section("Medication Audit Trail", report::getMedicationAudit),
                section("Procedures & Documents", report::getProceduresDocuments),
                section("Outstanding Tasks & Disposition", report::getOutstandingTasks),
                section("Assessment & Plan", report::getPlanOfCare),
                section("ED Timeline", report::getEdTimeline),
                section("Handover Notes", report::getNotes)));
    }

    private record Section(String label, String content, boolean alert) {}

    private static Section section(String label, Supplier<String> content) {
        return new Section(label, content.get(), false);
    }

    private static Section alertSection(String label, Supplier<String> content) {
        return new Section(label, content.get(), true);
    }

    /** Render one SBAR group: letter badge header, then each non-blank section. */
    private void sbarGroup(PdfReport r, String letter, String groupLabel, List<Section> sections) {
        List<Section> present = sections.stream()
                .filter(s -> s.content() != null && !s.content().isBlank())
                .toList();
        if (present.isEmpty()) return;
        r.sbarGroup(letter, groupLabel);
        for (Section s : present) {
            r.subHeader(s.label());
            if (s.alert()) {
                // Content is pre-formatted multi-line text; the callout preserves lines.
                r.alertCallout(null, s.content());
            } else {
                r.narrative(s.content());
            }
        }
    }

    // ── Acknowledgement / sign-off ──────────────────────────────────

    private void renderAcknowledgement(PdfReport r, HandoverReport report) {
        r.spacer(4f);
        r.subHeader("Acknowledgement — handover is complete only when the receiving clinician signs");
        String outgoing = report.getGeneratedByName() != null && !report.getGeneratedByName().isBlank()
                ? "Handing over — " + report.getGeneratedByName() : "Handing over";
        r.signatureBlock(outgoing, "Receiving clinician");
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private static String ageSex(Patient patient) {
        if (patient == null) return null;
        String age = null;
        if (patient.getDateOfBirth() != null) {
            int years = Period.between(patient.getDateOfBirth(), LocalDate.now(KIGALI)).getYears();
            if (years >= 0) age = String.valueOf(years);
        }
        String sex = null;
        if (patient.getGender() != null && patient.getGender() != Gender.UNKNOWN) {
            sex = patient.getGender() == Gender.MALE ? "Male" : "Female";
        }
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
