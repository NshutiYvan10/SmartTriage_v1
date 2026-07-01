package com.smartTriage.smartTriage_server.module.handover.service;

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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Renders a {@link HandoverReport} into a professional, printable PDF for
 * physical handover and record-keeping. Now rendered through the shared
 * {@link PdfReport} kit so it carries the SmartTriage branded masthead, an
 * unmistakable unidentified-patient flag, every on-screen section verbatim
 * (nothing summarised away), and the standard confidentiality + attribution +
 * page-number footer.
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

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Africa/Kigali"));
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmm").withZone(ZoneId.of("Africa/Kigali"));

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
                ? report.getReportType().name().replace('_', ' ') : "HANDOVER";

        try {
            PdfReport r = PdfReport.begin(new PdfReport.Spec(
                    "EMERGENCY DEPARTMENT — " + type,
                    "Handover / SBAR",
                    orgName,
                    hospitalMeta(h),
                    exportedBy,
                    "clinical handover report"));

            renderTitleAndPatient(r, report);
            renderSections(r, report);

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

    // ── Title + patient banner ──────────────────────────────────────

    private void renderTitleAndPatient(PdfReport r, HandoverReport report) {
        Visit visit = report.getVisit();
        Patient patient = visit != null ? visit.getPatient() : null;

        // Unmistakable unidentified-patient flag — preserved from the original render.
        if (patient != null && patient.isUnidentified()) {
            r.alertBanner("** UNIDENTIFIED PATIENT — identity unresolved **");
        }
        if (!report.isAcknowledged()) {
            r.alertBanner("** NOT YET ACKNOWLEDGED **");
        }

        String patientName = patient != null
                ? (patient.getFirstName() + " " + patient.getLastName()).trim() : "Unknown";
        if (patientName.isBlank()) patientName = "Unknown";

        List<String> ids = new ArrayList<>();
        if (visit != null && visit.getVisitNumber() != null) ids.add("Visit: " + visit.getVisitNumber());
        if (patient != null && patient.getMedicalRecordNumber() != null) ids.add("MRN: " + patient.getMedicalRecordNumber());
        r.subjectHeadline(patientName, String.join("   ", ids));

        // Generation + acknowledgement provenance as a clean key/value block.
        String gen = (report.getGeneratedAt() != null ? TS.format(report.getGeneratedAt()) : "—");
        if (report.getGeneratedByName() != null) gen += " by " + report.getGeneratedByName();

        String ack = report.isAcknowledged()
                ? "Acknowledged " + (report.getAcknowledgedAt() != null ? TS.format(report.getAcknowledgedAt()) : "")
                  + (report.getReceivedByName() != null ? " by " + report.getReceivedByName() : "")
                : "Not yet acknowledged";

        r.keyValues(List.of(
                PdfReport.kv("Generated", gen),
                PdfReport.kv("Acknowledgement", ack)));
    }

    // ── Sections ────────────────────────────────────────────────────

    private void renderSections(PdfReport r, HandoverReport report) {
        // Order mirrors the on-screen report exactly — nothing summarised away.
        addSection(r, "Patient Summary", report::getPatientSummary);
        addSection(r, "Pre-Hospital / EMS", report::getPrehospitalSummary);
        addSection(r, "Presenting Complaint", report::getPresentingComplaint);
        addSection(r, "Triage Summary", report::getTriageSummary);
        addSection(r, "Vital Signs Trend", report::getVitalSignsTrend);
        addSection(r, "Investigations & Results", report::getInvestigationsResults);
        addSection(r, "Diagnosis Summary", report::getDiagnosisSummary);
        addSection(r, "Acute Protocols & Critical Events", report::getAcuteProtocols);
        addSection(r, "Treatment Summary", report::getTreatmentSummary);
        addSection(r, "Medication Audit Trail", report::getMedicationAudit);
        addSection(r, "Procedures & Documents", report::getProceduresDocuments);
        addSection(r, "Active Clinical Alerts", report::getActiveClinicalAlerts);
        addSection(r, "Outstanding Tasks & Disposition", report::getOutstandingTasks);
        addSection(r, "Assessment & Plan", report::getPlanOfCare);
        addSection(r, "ED Timeline", report::getEdTimeline);
        addSection(r, "Handover Notes", report::getNotes);
    }

    /**
     * Render one SBAR section. Content is pre-formatted multi-line text (the
     * section's indentation is significant), so it goes into the narrative
     * panel which preserves line breaks verbatim.
     */
    private void addSection(PdfReport r, String label, Supplier<String> contentSupplier) {
        String content = contentSupplier.get();
        if (content == null || content.isBlank()) return;
        r.sectionHeader(label);
        r.narrative(content);
    }

    // ── Helpers ─────────────────────────────────────────────────────

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
