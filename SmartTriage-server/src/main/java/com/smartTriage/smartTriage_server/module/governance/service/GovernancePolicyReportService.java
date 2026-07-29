package com.smartTriage.smartTriage_server.module.governance.service;

import com.smartTriage.smartTriage_server.common.enums.PolicyStatus;
import com.smartTriage.smartTriage_server.common.enums.PolicyType;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.common.report.PdfReport;
import com.smartTriage.smartTriage_server.common.report.PdfReport.KeyVal;
import com.smartTriage.smartTriage_server.module.governance.entity.ClinicalPolicy;
import com.smartTriage.smartTriage_server.module.governance.repository.ClinicalPolicyRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.smartTriage.smartTriage_server.common.report.PdfReport.kv;

/**
 * Clinical Governance reporting — turns the policy register into professional,
 * downloadable artifacts using the shared {@link PdfReport} toolkit (the same
 * branded masthead / tables / confidentiality footer as the operational, MoH,
 * lab and safety reports).
 *
 * <p>Three outputs, mirroring the app-wide export pattern:
 * <ul>
 *   <li>{@link #renderRegister} — the whole policy register as a PDF: governance
 *       KPIs + a one-row-per-policy table (code, type, version, status, effective
 *       window, approver).</li>
 *   <li>{@link #renderRegisterCsv} — the same register as CSV for the table
 *       preview / spreadsheet.</li>
 *   <li>{@link #renderPolicy} — a single policy as its own branded, printable
 *       document (full content + approval attestation).</li>
 * </ul>
 * Previously the governance module had no export at all — only lifecycle CRUD.
 */
@Service
@RequiredArgsConstructor
public class GovernancePolicyReportService {

    private final ClinicalPolicyRepository policyRepository;
    private final HospitalRepository hospitalRepository;

    private static final ZoneId KIGALI = ZoneId.of("Africa/Kigali");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(KIGALI);
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm").withZone(KIGALI);

    /** Register display order: live policies first, then in-flight, then retired; ties by type then name. */
    private static final Map<PolicyStatus, Integer> STATUS_ORDER = Map.of(
            PolicyStatus.ACTIVE, 0, PolicyStatus.APPROVED, 1, PolicyStatus.PENDING_APPROVAL, 2,
            PolicyStatus.DRAFT, 3, PolicyStatus.SUSPENDED, 4, PolicyStatus.ARCHIVED, 5);

    private static final Comparator<ClinicalPolicy> REGISTER_ORDER = Comparator
            .comparingInt((ClinicalPolicy p) -> p.getStatus() == null ? 9 : STATUS_ORDER.getOrDefault(p.getStatus(), 9))
            .thenComparing(p -> typeLabel(p.getPolicyType()))
            .thenComparing(p -> nz(p.getPolicyName()));

    public record RenderedPdf(byte[] bytes, String filename) {}
    public record RenderedCsv(String csv, String filename) {}

    // ====================================================================
    // POLICY REGISTER — PDF
    // ====================================================================

    @Transactional(readOnly = true)
    public RenderedPdf renderRegister(UUID hospitalId, String requestedBy) {
        Hospital h = hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));
        List<ClinicalPolicy> policies = loadRegister(hospitalId);

        PdfReport r = PdfReport.begin(new PdfReport.Spec(
                "Clinical Governance Report",
                "Clinical Governance",
                h.getName(),
                meta(h),
                notBlank(requestedBy) ? requestedBy : "SmartTriage",
                "clinical governance register",
                "Governance & compliance"));

        // ── Governance at a glance ──
        Instant now = Instant.now();
        long total = policies.size();
        long active = countStatus(policies, PolicyStatus.ACTIVE);
        long inFlight = policies.stream().filter(p -> p.getStatus() == PolicyStatus.DRAFT
                || p.getStatus() == PolicyStatus.PENDING_APPROVAL || p.getStatus() == PolicyStatus.APPROVED).count();
        long suspended = countStatus(policies, PolicyStatus.SUSPENDED);
        long archived = countStatus(policies, PolicyStatus.ARCHIVED);
        long dueReview = policies.stream()
                .filter(p -> p.getStatus() == PolicyStatus.ACTIVE && p.getEffectiveTo() != null
                        && !p.getEffectiveTo().isAfter(now.plus(30, ChronoUnit.DAYS)))
                .count();

        r.kpiTiles(List.of(
                new PdfReport.Kpi(Long.toString(total), "", "Policies on register"),
                new PdfReport.Kpi(Long.toString(active), "", "Active"),
                new PdfReport.Kpi(Long.toString(inFlight), "", "In draft / approval"),
                new PdfReport.Kpi(Long.toString(suspended), "", "Suspended"),
                new PdfReport.Kpi(Long.toString(archived), "", "Archived"),
                new PdfReport.Kpi(Long.toString(dueReview), "", "Due for review ≤30d")
        ), 3);

        // ── The register ──
        r.sectionHeader("Policy register");
        if (policies.isEmpty()) {
            r.narrative("No clinical policies have been recorded for this hospital yet.");
        } else {
            String[] headers = {"Code", "Policy", "Type", "Ver.", "Status", "Effective", "Review by", "Approved by"};
            float[] widths = {1.05f, 2.5f, 1.55f, 0.7f, 1.15f, 1.15f, 1.15f, 1.6f};
            List<String[]> rows = new ArrayList<>();
            for (ClinicalPolicy p : policies) {
                rows.add(new String[]{
                        nz(p.getPolicyCode()),
                        nz(p.getPolicyName()),
                        typeLabel(p.getPolicyType()),
                        nz(p.getPolicyVersion()),
                        statusLabel(p.getStatus()),
                        fmtDate(p.getEffectiveFrom()),
                        fmtDate(p.getEffectiveTo()),
                        nz(p.getApprovedByName())
                });
            }
            r.dataTable(headers, widths, rows, headers.length); // text columns — left-aligned
        }

        r.spacer(6f);
        r.paragraph("Generated by SmartTriage from the clinical governance register. Status and dates reflect "
                + "the register at generation time; the generation itself is recorded in the hospital audit trail.",
                PdfReport.F_META);

        return new RenderedPdf(r.finish(),
                "clinical-governance-report_" + LocalDate.now(KIGALI) + ".pdf");
    }

    // ====================================================================
    // POLICY REGISTER — CSV
    // ====================================================================

    @Transactional(readOnly = true)
    public RenderedCsv renderRegisterCsv(UUID hospitalId) {
        // Validate the hospital exists (consistent 404 with the PDF path).
        hospitalRepository.findByIdAndIsActiveTrue(hospitalId)
                .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", hospitalId));
        List<ClinicalPolicy> policies = loadRegister(hospitalId);

        StringBuilder sb = new StringBuilder();
        sb.append("Code,Policy Name,Type,Version,Status,Effective From,Effective To,Created By,")
                .append("Approved By,Approved At,Description\n");
        for (ClinicalPolicy p : policies) {
            row(sb,
                    p.getPolicyCode(),
                    p.getPolicyName(),
                    typeLabel(p.getPolicyType()),
                    p.getPolicyVersion(),
                    statusLabel(p.getStatus()),
                    fmtDate(p.getEffectiveFrom()),
                    fmtDate(p.getEffectiveTo()),
                    p.getCreatedByName(),
                    p.getApprovedByName(),
                    fmtDateTime(p.getApprovedAt()),
                    p.getDescription());
        }
        return new RenderedCsv(sb.toString(), "clinical-governance-register_" + LocalDate.now(KIGALI) + ".csv");
    }

    // ====================================================================
    // SINGLE POLICY — PDF DOCUMENT
    // ====================================================================

    @Transactional(readOnly = true)
    public RenderedPdf renderPolicy(UUID policyId, String requestedBy) {
        ClinicalPolicy p = policyRepository.findByIdAndIsActiveTrue(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("ClinicalPolicy", "id", policyId));
        Hospital h = p.getHospital();
        String orgName = h != null && notBlank(h.getName()) ? h.getName() : "SmartTriage — system-wide policy";

        PdfReport r = PdfReport.begin(new PdfReport.Spec(
                notBlank(p.getPolicyName()) ? p.getPolicyName() : "Clinical Policy",
                "Clinical Policy",
                orgName,
                meta(h),
                notBlank(requestedBy) ? requestedBy : "SmartTriage",
                "clinical governance policy",
                typeLabel(p.getPolicyType())));

        // Status + effective window as the at-a-glance banner.
        String window = p.getEffectiveFrom() != null
                ? "Effective " + fmtDate(p.getEffectiveFrom())
                    + (p.getEffectiveTo() != null ? " — review by " + fmtDate(p.getEffectiveTo()) : "")
                : "";
        r.statusBanner(statusLabel(p.getStatus()) + (window.isEmpty() ? "" : " · " + window));

        List<KeyVal> id = new ArrayList<>();
        addKv(id, "Policy code", p.getPolicyCode());
        addKv(id, "Type", typeLabel(p.getPolicyType()));
        addKv(id, "Version", p.getPolicyVersion());
        addKv(id, "Created by", p.getCreatedByName());
        addKv(id, "Approved by", p.getApprovedByName());
        if (p.getApprovedAt() != null) addKv(id, "Approved at", fmtDateTime(p.getApprovedAt()));
        r.keyValues(id);

        if (notBlank(p.getDescription())) {
            r.sectionHeader("Summary");
            r.narrative(p.getDescription());
        }

        r.sectionHeader("Policy");
        r.narrative(notBlank(p.getPolicyContent()) ? p.getPolicyContent() : "No policy content recorded.");

        if (notBlank(p.getApprovalNotes())) {
            r.sectionHeader("Approval notes");
            r.narrative(p.getApprovalNotes());
        }
        if (notBlank(p.getChangeReason())) {
            r.sectionHeader("Change reason");
            r.narrative(p.getChangeReason());
        }
        if (notBlank(p.getNotes())) {
            r.sectionHeader("Notes");
            r.narrative(p.getNotes());
        }

        // Handwriting lines for the accountable owner + reviewer.
        r.signatureBlock("Approved by", "Reviewed by");

        return new RenderedPdf(r.finish(), "policy_" + slug(p.getPolicyCode(), p.getPolicyName()) + ".pdf");
    }

    // ====================================================================
    // Helpers
    // ====================================================================

    private List<ClinicalPolicy> loadRegister(UUID hospitalId) {
        List<ClinicalPolicy> policies = new ArrayList<>(
                policyRepository.findForHospital(hospitalId, null, PageRequest.of(0, 2000)).getContent());
        policies.sort(REGISTER_ORDER);
        return policies;
    }

    private static List<String> meta(Hospital h) {
        List<String> meta = new ArrayList<>();
        if (h != null && h.getHospitalCode() != null) meta.add("Facility code · " + h.getHospitalCode());
        return meta;
    }

    private static long countStatus(List<ClinicalPolicy> policies, PolicyStatus s) {
        return policies.stream().filter(p -> p.getStatus() == s).count();
    }

    private static String fmtDate(Instant i) {
        return i == null ? "—" : D.format(i);
    }

    private static String fmtDateTime(Instant i) {
        return i == null ? "—" : DT.format(i);
    }

    private static void addKv(List<KeyVal> list, String label, String value) {
        if (notBlank(value)) list.add(kv(label, value));
    }

    private static String typeLabel(PolicyType t) {
        if (t == null) return "—";
        return switch (t) {
            case TRIAGE_RULE -> "Triage rule";
            case DRUG_PROTOCOL -> "Drug protocol";
            case CLINICAL_GUIDELINE -> "Clinical guideline";
            case INFECTION_CONTROL -> "Infection control";
            case STAFFING_REQUIREMENT -> "Staffing requirement";
            case EQUIPMENT_PROTOCOL -> "Equipment protocol";
            case QUALITY_STANDARD -> "Quality standard";
            case CONSENT_FORM -> "Consent form";
            case DISCHARGE_CRITERIA -> "Discharge criteria";
            case OTHER -> "Other";
        };
    }

    private static String statusLabel(PolicyStatus s) {
        if (s == null) return "—";
        return switch (s) {
            case DRAFT -> "Draft";
            case PENDING_APPROVAL -> "Pending approval";
            case APPROVED -> "Approved";
            case ACTIVE -> "Active";
            case SUSPENDED -> "Suspended";
            case ARCHIVED -> "Archived";
        };
    }

    /** CSV row with proper escaping; a trailing newline. */
    private static void row(StringBuilder sb, String... cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(csv(cells[i]));
        }
        sb.append('\n');
    }

    /** CSV-escape: quote when the value contains a comma, quote, or newline; "" escapes a quote. */
    private static String csv(String v) {
        if (v == null) return "";
        boolean needsQuote = v.contains(",") || v.contains("\"") || v.contains("\n") || v.contains("\r");
        String out = v.replace("\"", "\"\"");
        return needsQuote ? "\"" + out + "\"" : out;
    }

    private static String slug(String code, String name) {
        String base = notBlank(code) ? code : (notBlank(name) ? name : "policy");
        String s = base.trim().toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "policy" : s;
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
