package com.smartTriage.smartTriage_server.module.governance.controller;

import com.smartTriage.smartTriage_server.common.dto.ApiResponse;
import com.smartTriage.smartTriage_server.common.enums.PolicyType;
import com.smartTriage.smartTriage_server.module.governance.dto.*;
import com.smartTriage.smartTriage_server.module.governance.entity.ClinicalPolicy;
import com.smartTriage.smartTriage_server.module.governance.mapper.ClinicalPolicyMapper;
import com.smartTriage.smartTriage_server.module.governance.mapper.PolicyAuditLogMapper;
import com.smartTriage.smartTriage_server.module.governance.service.ClinicalGovernanceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * REST controller for clinical governance policy management.
 * Supports full lifecycle: create, update, approve, activate, suspend, archive.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/governance/policies")
@RequiredArgsConstructor
public class ClinicalGovernanceController {

    private final ClinicalGovernanceService governanceService;

    /**
     * Create a new draft policy.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> createPolicy(
            @Valid @RequestBody CreatePolicyRequest request) {
        log.info("Creating policy: {}", request.getPolicyName());
        ClinicalPolicy policy = governanceService.createPolicy(request);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy created", ClinicalPolicyMapper.toResponse(policy)));
    }

    /**
     * Update a draft policy.
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> updatePolicy(
            @PathVariable UUID id,
            @Valid @RequestBody UpdatePolicyRequest request) {
        log.info("Updating policy {}", id);
        ClinicalPolicy policy = governanceService.updatePolicy(id, request);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy updated", ClinicalPolicyMapper.toResponse(policy)));
    }

    /**
     * Submit a draft policy for approval.
     */
    @PutMapping("/{id}/submit")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> submitForApproval(
            @PathVariable UUID id) {
        log.info("Submitting policy {} for approval", id);
        ClinicalPolicy policy = governanceService.submitForApproval(id);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy submitted for approval", ClinicalPolicyMapper.toResponse(policy)));
    }

    /**
     * Approve a pending policy.
     */
    @PutMapping("/{id}/approve")
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
     * Activate an approved policy.
     */
    @PutMapping("/{id}/activate")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> activatePolicy(
            @PathVariable UUID id) {
        log.info("Activating policy {}", id);
        ClinicalPolicy policy = governanceService.activatePolicy(id);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy activated", ClinicalPolicyMapper.toResponse(policy)));
    }

    /**
     * Suspend an active policy.
     */
    @PutMapping("/{id}/suspend")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> suspendPolicy(
            @PathVariable UUID id,
            @Valid @RequestBody SuspendPolicyRequest request) {
        log.info("Suspending policy {}", id);
        ClinicalPolicy policy = governanceService.suspendPolicy(id, request.getReason());
        return ResponseEntity.ok(ApiResponse.success(
                "Policy suspended", ClinicalPolicyMapper.toResponse(policy)));
    }

    /**
     * Archive a policy.
     */
    @PutMapping("/{id}/archive")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> archivePolicy(
            @PathVariable UUID id) {
        log.info("Archiving policy {}", id);
        ClinicalPolicy policy = governanceService.archivePolicy(id);
        return ResponseEntity.ok(ApiResponse.success(
                "Policy archived", ClinicalPolicyMapper.toResponse(policy)));
    }

    /**
     * Get active policies for a hospital by type.
     */
    @GetMapping("/hospital/{hospitalId}/active")
    public ResponseEntity<ApiResponse<List<ClinicalPolicyResponse>>> getActivePolicies(
            @PathVariable UUID hospitalId,
            @RequestParam(required = false) PolicyType type) {
        List<ClinicalPolicyResponse> policies;
        if (type != null) {
            policies = governanceService.getActivePolicies(hospitalId, type).stream()
                    .map(ClinicalPolicyMapper::toResponse)
                    .collect(Collectors.toList());
        } else {
            // Return all active policies across all types
            List<ClinicalPolicyResponse> allActive = new java.util.ArrayList<>();
            for (PolicyType pt : PolicyType.values()) {
                governanceService.getActivePolicies(hospitalId, pt).stream()
                        .map(ClinicalPolicyMapper::toResponse)
                        .forEach(allActive::add);
            }
            policies = allActive;
        }
        return ResponseEntity.ok(ApiResponse.success(policies));
    }

    /**
     * Get all policies for a hospital with pagination.
     */
    @GetMapping("/hospital/{hospitalId}")
    public ResponseEntity<ApiResponse<Page<ClinicalPolicyResponse>>> getAllPolicies(
            @PathVariable UUID hospitalId, Pageable pageable) {
        Page<ClinicalPolicyResponse> policies = governanceService
                .getAllPolicies(hospitalId, pageable)
                .map(ClinicalPolicyMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(policies));
    }

    /**
     * Get a single policy by ID.
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ClinicalPolicyResponse>> getPolicy(@PathVariable UUID id) {
        ClinicalPolicy policy = governanceService.getPolicy(id);
        return ResponseEntity.ok(ApiResponse.success(ClinicalPolicyMapper.toResponse(policy)));
    }

    /**
     * Get version history for a policy by its code.
     */
    @GetMapping("/{id}/history")
    public ResponseEntity<ApiResponse<List<ClinicalPolicyResponse>>> getPolicyHistory(
            @PathVariable UUID id) {
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

    /**
     * Get audit log for a specific policy.
     */
    @GetMapping("/{id}/audit")
    public ResponseEntity<ApiResponse<Page<PolicyAuditLogResponse>>> getAuditLog(
            @PathVariable UUID id, Pageable pageable) {
        Page<PolicyAuditLogResponse> auditLog = governanceService
                .getAuditLog(id, pageable)
                .map(PolicyAuditLogMapper::toResponse);
        return ResponseEntity.ok(ApiResponse.success(auditLog));
    }
}
