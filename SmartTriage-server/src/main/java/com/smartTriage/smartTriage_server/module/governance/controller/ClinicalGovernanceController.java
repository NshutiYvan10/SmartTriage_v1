package com.smartTriage.smartTriage_server.module.governance.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.PolicyStatus;
import com.smartTriage.smartTriage_server.common.enums.PolicyType;
import com.smartTriage.smartTriage_server.module.governance.dto.*;
import com.smartTriage.smartTriage_server.module.governance.entity.ClinicalPolicy;
import com.smartTriage.smartTriage_server.module.governance.mapper.ClinicalPolicyMapper;
import com.smartTriage.smartTriage_server.module.governance.mapper.PolicyAuditLogMapper;
import com.smartTriage.smartTriage_server.module.governance.service.ClinicalGovernanceService;
import com.smartTriage.smartTriage_server.module.governance.service.GovernancePolicyReportService;
import com.smartTriage.smartTriage_server.module.user.entity.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for clinical governance policy management.
 * Supports full lifecycle: create, update, approve, activate, suspend, reactivate, archive.
 *
 * <p>Authorization: writes are SUPER_ADMIN / HOSPITAL_ADMIN; reads add NURSE /
 * DOCTOR. Because a method-level {@code @PreAuthorize} REPLACES the class-level one, every
 * method carries its OWN complete expression (role gate AND object-level scope), and the
 * class-level annotation is only a floor for any method without its own. Object-level gates
 * ({@code canManagePolicy} / {@code canAccessPolicy}) close cross-hospital access by policy id.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/governance/policies")
@RequiredArgsConstructor
@PreAuthorize("hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'NURSE', 'DOCTOR')")
public class ClinicalGovernanceController {

    private static final String ROLE_WRITE = "hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN')";
    private static final String ROLE_READ = "hasAnyRole('SUPER_ADMIN', 'HOSPITAL_ADMIN', 'NURSE', 'DOCTOR')";

    private final ClinicalGovernanceService governanceService;
    private final GovernancePolicyReportService reportService;

    /**
     * Create a new draft policy. A hospital-scoped policy requires access to that hospital;
     * a system-wide policy (no hospitalId) is SUPER_ADMIN-only.
     */
    @PostMapping
    @PreAuthorize(ROLE_WRITE + " and (#request.hospitalId == null "
            + "? hasRole('SUPER_ADMIN') "
            + ": @clinicalAuthz.canAccessHospital(authentication, #request.hospitalId))")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> createPolicy(
            @Valid @RequestBody CreatePolicyRequest request) {
        log.info("Creating policy: {}", request.getPolicyName());
        ClinicalPolicy policy = governanceService.createPolicy(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy created", ClinicalPolicyMapper.toResponse(policy)));
    }

    @PutMapping("/{id}")
    @PreAuthorize(ROLE_WRITE + " and @clinicalAuthz.canManagePolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> updatePolicy(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePolicyRequest request) {
        log.info("Updating policy {}", id);
        ClinicalPolicy policy = governanceService.updatePolicy(id, request);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy updated", ClinicalPolicyMapper.toResponse(policy)));
    }

    @PutMapping("/{id}/submit")
    @PreAuthorize(ROLE_WRITE + " and @clinicalAuthz.canManagePolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> submitForApproval(@PathVariable UUID id) {
        log.info("Submitting policy {} for approval", id);
        ClinicalPolicy policy = governanceService.submitForApproval(id);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy submitted for approval", ClinicalPolicyMapper.toResponse(policy)));
    }

    @PutMapping("/{id}/approve")
    @PreAuthorize(ROLE_WRITE + " and @clinicalAuthz.canManagePolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> approvePolicy(
            @PathVariable UUID id,
            @Valid @RequestBody ApprovePolicyRequest request) {
        log.info("Approving policy {}", id);
        ClinicalPolicy policy = governanceService.approvePolicy(
                id, request.getApproverName(), request.getNotes());
        return ResponseEntity.ok(ApiResponse.success(
                "Policy approved", ClinicalPolicyMapper.toResponse(policy)));
    }

    /**
     * One-step approve + activate for the policy administrator (SUPER_ADMIN /
     * HOSPITAL_ADMIN — the only write roles). The admin answers to no higher
     * reviewer, so the submit → approve → activate ceremony is compressed
     * into a single action; the audit trail still records APPROVED and
     * ACTIVATED separately.
     */
    @PutMapping("/{id}/approve-activate")
    @PreAuthorize(ROLE_WRITE + " and @clinicalAuthz.canManagePolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> approveAndActivatePolicy(
            @PathVariable UUID id,
            @RequestBody(required = false) ApprovePolicyRequest request) {
        log.info("Approve+activate policy {}", id);
        ClinicalPolicy policy = governanceService.approveAndActivatePolicy(
                id, request != null ? request.getNotes() : null);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy approved and activated", ClinicalPolicyMapper.toResponse(policy)));
    }

    @PutMapping("/{id}/activate")
    @PreAuthorize(ROLE_WRITE + " and @clinicalAuthz.canManagePolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> activatePolicy(@PathVariable UUID id) {
        log.info("Activating policy {}", id);
        ClinicalPolicy policy = governanceService.activatePolicy(id);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy activated", ClinicalPolicyMapper.toResponse(policy)));
    }

    @PutMapping("/{id}/suspend")
    @PreAuthorize(ROLE_WRITE + " and @clinicalAuthz.canManagePolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> suspendPolicy(
            @PathVariable UUID id,
            @Valid @RequestBody SuspendPolicyRequest request) {
        log.info("Suspending policy {}", id);
        ClinicalPolicy policy = governanceService.suspendPolicy(id, request.getReason());
        return ResponseEntity.ok(ApiResponse.success(
                "Policy suspended", ClinicalPolicyMapper.toResponse(policy)));
    }

    @PutMapping("/{id}/reactivate")
    @PreAuthorize(ROLE_WRITE + " and @clinicalAuthz.canManagePolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> reactivatePolicy(@PathVariable UUID id) {
        log.info("Reactivating policy {}", id);
        ClinicalPolicy policy = governanceService.reactivatePolicy(id);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy reactivated", ClinicalPolicyMapper.toResponse(policy)));
    }

    @PutMapping("/{id}/archive")
    @PreAuthorize(ROLE_WRITE + " and @clinicalAuthz.canManagePolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> archivePolicy(@PathVariable UUID id) {
        log.info("Archiving policy {}", id);
        ClinicalPolicy policy = governanceService.archivePolicy(id);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy archived", ClinicalPolicyMapper.toResponse(policy)));
    }

    /**
     * Restore an ARCHIVED policy back to DRAFT. Deliberately NOT straight to
     * ACTIVE — a restored policy must re-pass the submit → approve → activate
     * lifecycle so nothing re-enters clinical use without review.
     */
    @PutMapping("/{id}/unarchive")
    @PreAuthorize(ROLE_WRITE + " and @clinicalAuthz.canManagePolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> unarchivePolicy(@PathVariable UUID id) {
        log.info("Unarchiving policy {}", id);
        ClinicalPolicy policy = governanceService.unarchivePolicy(id);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy restored to draft", ClinicalPolicyMapper.toResponse(policy)));
    }

    /**
     * Get active policies for a hospital, optionally filtered by type.
     */
    @GetMapping("/hospital/{hospitalId}/active")
    @PreAuthorize(ROLE_READ + " and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<List<ClinicalPolicyResponse>>> getActivePolicies(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) PolicyType type) {
        List<ClinicalPolicyResponse> policies = (type != null
                ? governanceService.getActivePolicies(hospitalId, type)
                : governanceService.getAllActive(hospitalId))
                .stream()
                .map(ClinicalPolicyMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(policies));
    }

    /**
     * Get all policies for a hospital (+ system-wide defaults), paginated, with an
     * optional status filter.
     */
    @GetMapping("/hospital/{hospitalId}")
    @PreAuthorize(ROLE_READ + " and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<ApiResponse<Page<ClinicalPolicyResponse>>> getAllPolicies(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) PolicyStatus status,
            Pageable pageable) {
        Page<ClinicalPolicyResponse> policies = governanceService
                .getAllPolicies(hospitalId, status, pageable)
                .map(ClinicalPolicyMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(policies));
    }

    @GetMapping("/{id}")
    @PreAuthorize(ROLE_READ + " and @clinicalAuthz.canAccessPolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> getPolicy(@PathVariable UUID id) {
        ClinicalPolicy policy = governanceService.getPolicy(id);
        return ResponseEntity.ok(ApiResponse.success(ClinicalPolicyMapper.toResponse(policy)));
    }

    // ====================================================================
    // REPORTS / EXPORTS — the clinical governance report (register PDF/CSV +
    // single-policy document). Same read gate as the register listing.
    // ====================================================================

    /** The whole policy register as a professional, printable PDF (governance KPIs + register table). */
    @GetMapping("/hospital/{hospitalId}/report/pdf")
    @PreAuthorize(ROLE_READ + " and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<byte[]> downloadRegisterPdf(@PathVariable UUID hospitalId, Authentication authentication) {
        GovernancePolicyReportService.RenderedPdf pdf = reportService.renderRegister(hospitalId, actorName(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.filename() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.bytes());
    }

    /** The policy register as CSV — one row per policy (feeds the in-app table preview / spreadsheet). */
    @GetMapping("/hospital/{hospitalId}/report/csv")
    @PreAuthorize(ROLE_READ + " and @clinicalAuthz.canAccessHospital(authentication, #hospitalId)")
    public ResponseEntity<String> downloadRegisterCsv(@PathVariable UUID hospitalId) {
        GovernancePolicyReportService.RenderedCsv csv = reportService.renderRegisterCsv(hospitalId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + csv.filename() + "\"")
                .contentType(MediaType.parseMediaType("text/csv"))
                .body(csv.csv());
    }

    /** A single policy as its own branded, printable document (full content + approval attestation). */
    @GetMapping("/{id}/pdf")
    @PreAuthorize(ROLE_READ + " and @clinicalAuthz.canAccessPolicy(authentication, #id)")
    public ResponseEntity<byte[]> downloadPolicyPdf(@PathVariable UUID id, Authentication authentication) {
        GovernancePolicyReportService.RenderedPdf pdf = reportService.renderPolicy(id, actorName(authentication));
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + pdf.filename() + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf.bytes());
    }

    private static String actorName(Authentication authentication) {
        if (authentication != null && authentication.getPrincipal() instanceof User u) {
            String name = ((u.getFirstName() == null ? "" : u.getFirstName()) + " "
                    + (u.getLastName() == null ? "" : u.getLastName())).trim();
            return name.isBlank() ? u.getEmail() : name;
        }
        return "SmartTriage user";
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(ROLE_READ + " and @clinicalAuthz.canAccessPolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<List<ClinicalPolicyResponse>>> getPolicyHistory(@PathVariable UUID id) {
        ClinicalPolicy policy = governanceService.getPolicy(id);
        UUID hospitalId = policy.getHospital() != null ? policy.getHospital().getId() : null;
        String policyCode = policy.getPolicyCode();

        if (policyCode == null || hospitalId == null) {
            return ResponseEntity.ok(ApiResponse.success(List.of(ClinicalPolicyMapper.toResponse(policy))));
        }

        List<ClinicalPolicyResponse> history = governanceService
                .getPolicyHistory(policyCode, hospitalId).stream()
                .map(ClinicalPolicyMapper::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(ApiResponse.success(history));
    }

    @GetMapping("/{id}/audit")
    @PreAuthorize(ROLE_READ + " and @clinicalAuthz.canAccessPolicy(authentication, #id)")
    public ResponseEntity<ApiResponse<Page<PolicyAuditLogResponse>>> getAuditLog(
            @PathVariable UUID id, Pageable pageable) {
        Page<PolicyAuditLogResponse> auditLog = governanceService
                .getAuditLog(id, pageable)
                .map(PolicyAuditLogMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(auditLog));
    }
}
