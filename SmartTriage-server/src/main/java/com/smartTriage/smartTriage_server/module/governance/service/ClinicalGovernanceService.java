package com.smartTriage.smartTriage_server.module.governance.service;

import com.smartTriage.smartTriage_server.common.enums.PolicyStatus;
import com.smartTriage.smartTriage_server.common.enums.PolicyType;
import com.smartTriage.smartTriage_server.common.exception.ResourceNotFoundException;
import com.smartTriage.smartTriage_server.module.governance.dto.CreatePolicyRequest;
import com.smartTriage.smartTriage_server.module.governance.dto.UpdatePolicyRequest;
import com.smartTriage.smartTriage_server.module.governance.entity.ClinicalPolicy;
import com.smartTriage.smartTriage_server.module.governance.entity.PolicyAuditLog;
import com.smartTriage.smartTriage_server.module.governance.repository.ClinicalPolicyRepository;
import com.smartTriage.smartTriage_server.module.governance.repository.PolicyAuditLogRepository;
import com.smartTriage.smartTriage_server.module.hospital.entity.Hospital;
import com.smartTriage.smartTriage_server.module.hospital.repository.HospitalRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing clinical governance policies — creation, approval workflow,
 * activation, suspension, archival, and version tracking with full audit trail.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ClinicalGovernanceService {

    private final ClinicalPolicyRepository policyRepository;
    private final PolicyAuditLogRepository auditLogRepository;
    private final HospitalRepository hospitalRepository;

    /**
     * Create a new draft policy.
     */
    @Transactional
    public ClinicalPolicy createPolicy(CreatePolicyRequest request) {
        log.info("Creating new {} policy: {}", request.getPolicyType(), request.getPolicyName());

        Hospital hospital = null;
        if (request.getHospitalId() != null) {
            hospital = hospitalRepository.findByIdAndIsActiveTrue(request.getHospitalId())
                    .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", request.getHospitalId()));
        }

        ClinicalPolicy policy = ClinicalPolicy.builder()
                .hospital(hospital)
                .policyType(request.getPolicyType())
                .policyName(request.getPolicyName())
                .policyCode(request.getPolicyCode())
                .description(request.getDescription())
                .policyContent(request.getPolicyContent())
                .effectiveFrom(request.getEffectiveFrom())
                .effectiveTo(request.getEffectiveTo())
                .policyVersion(request.getPolicyVersion() != null ? request.getPolicyVersion() : "1.0")
                .status(PolicyStatus.DRAFT)
                .createdByName(request.getCreatedByName())
                .changeReason(request.getChangeReason())
                .notes(request.getNotes())
                .build();

        policy = policyRepository.save(policy);

        createAuditLog(policy, "CREATED", request.getCreatedByName(),
                null, policy.getPolicyContent(), request.getChangeReason());

        log.info("Policy created with ID: {}", policy.getId());
        return policy;
    }

    /**
     * Update a draft policy. Only DRAFT policies can be updated.
     */
    @Transactional
    public ClinicalPolicy updatePolicy(UUID policyId, UpdatePolicyRequest request) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() != PolicyStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only DRAFT policies can be updated. Current status: " + policy.getStatus());
        }

        String previousContent = policy.getPolicyContent();

        if (request.getPolicyType() != null) {
            policy.setPolicyType(request.getPolicyType());
        }
        if (request.getPolicyName() != null) {
            policy.setPolicyName(request.getPolicyName());
        }
        if (request.getPolicyCode() != null) {
            policy.setPolicyCode(request.getPolicyCode());
        }
        if (request.getDescription() != null) {
            policy.setDescription(request.getDescription());
        }
        if (request.getPolicyContent() != null) {
            policy.setPolicyContent(request.getPolicyContent());
        }
        if (request.getEffectiveFrom() != null) {
            policy.setEffectiveFrom(request.getEffectiveFrom());
        }
        if (request.getEffectiveTo() != null) {
            policy.setEffectiveTo(request.getEffectiveTo());
        }
        if (request.getPolicyVersion() != null) {
            policy.setPolicyVersion(request.getPolicyVersion());
        }
        if (request.getChangeReason() != null) {
            policy.setChangeReason(request.getChangeReason());
        }
        if (request.getNotes() != null) {
            policy.setNotes(request.getNotes());
        }

        policy = policyRepository.save(policy);

        createAuditLog(policy, "UPDATED", policy.getCreatedByName(),
                previousContent, policy.getPolicyContent(), request.getChangeReason());

        log.info("Policy {} updated", policyId);
        return policy;
    }

    /**
     * Submit a draft policy for approval.
     */
    @Transactional
    public ClinicalPolicy submitForApproval(UUID policyId) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() != PolicyStatus.DRAFT) {
            throw new IllegalStateException(
                    "Only DRAFT policies can be submitted for approval. Current status: " + policy.getStatus());
        }

        policy.setStatus(PolicyStatus.PENDING_APPROVAL);
        policy = policyRepository.save(policy);

        createAuditLog(policy, "SUBMITTED_FOR_APPROVAL", policy.getCreatedByName(),
                null, null, "Submitted for approval");

        log.info("Policy {} submitted for approval", policyId);
        return policy;
    }

    /**
     * Approve a pending policy.
     */
    @Transactional
    public ClinicalPolicy approvePolicy(UUID policyId, String approverName, String notes) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() != PolicyStatus.PENDING_APPROVAL) {
            throw new IllegalStateException(
                    "Only PENDING_APPROVAL policies can be approved. Current status: " + policy.getStatus());
        }

        policy.setStatus(PolicyStatus.APPROVED);
        policy.setApprovedByName(approverName);
        policy.setApprovedAt(Instant.now());
        policy.setApprovalNotes(notes);

        policy = policyRepository.save(policy);

        createAuditLog(policy, "APPROVED", approverName, null, null, notes);

        log.info("Policy {} approved by {}", policyId, approverName);
        return policy;
    }

    /**
     * Activate an approved policy. Deactivates any previous active version of the same
     * policy type and code for the same hospital.
     */
    @Transactional
    public ClinicalPolicy activatePolicy(UUID policyId) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() != PolicyStatus.APPROVED) {
            throw new IllegalStateException(
                    "Only APPROVED policies can be activated. Current status: " + policy.getStatus());
        }

        // Deactivate previous active version of same type+code for hospital
        if (policy.getPolicyCode() != null && policy.getHospital() != null) {
            Optional<ClinicalPolicy> currentActive = policyRepository.findActiveByHospitalAndCodeAndType(
                    policy.getHospital().getId(), policy.getPolicyCode(), policy.getPolicyType());

            if (currentActive.isPresent()) {
                ClinicalPolicy previous = currentActive.get();
                previous.setStatus(PolicyStatus.ARCHIVED);
                policyRepository.save(previous);

                createAuditLog(previous, "ARCHIVED", policy.getApprovedByName(),
                        null, null, "Superseded by policy version " + policy.getPolicyVersion());

                policy.setPreviousVersion(previous);
                log.info("Previous active policy {} archived, superseded by {}", previous.getId(), policyId);
            }
        }

        policy.setStatus(PolicyStatus.ACTIVE);
        policy = policyRepository.save(policy);

        createAuditLog(policy, "ACTIVATED", policy.getApprovedByName(), null, null, "Policy activated");

        log.info("Policy {} activated", policyId);
        return policy;
    }

    /**
     * Suspend an active policy.
     */
    @Transactional
    public ClinicalPolicy suspendPolicy(UUID policyId, String reason) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() != PolicyStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only ACTIVE policies can be suspended. Current status: " + policy.getStatus());
        }

        policy.setStatus(PolicyStatus.SUSPENDED);

        policy = policyRepository.save(policy);

        createAuditLog(policy, "SUSPENDED", policy.getApprovedByName(), null, null, reason);

        log.info("Policy {} suspended: {}", policyId, reason);
        return policy;
    }

    /**
     * Archive a policy.
     */
    @Transactional
    public ClinicalPolicy archivePolicy(UUID policyId) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() == PolicyStatus.ARCHIVED) {
            throw new IllegalStateException("Policy is already archived");
        }

        policy.setStatus(PolicyStatus.ARCHIVED);
        policy = policyRepository.save(policy);

        createAuditLog(policy, "ARCHIVED", policy.getCreatedByName(), null, null, "Policy archived");

        log.info("Policy {} archived", policyId);
        return policy;
    }

    /**
     * Get active policies for a hospital by type.
     * Falls back to system-wide defaults if no hospital-specific policies exist.
     */
    public List<ClinicalPolicy> getActivePolicies(UUID hospitalId, PolicyType type) {
        List<ClinicalPolicy> policies = policyRepository.findActivePoliciesByHospitalAndType(hospitalId, type);
        if (policies.isEmpty()) {
            policies = policyRepository.findSystemWideActivePolicies(type);
        }
        return policies;
    }

    /**
     * Get all policies for a hospital with pagination.
     */
    public Page<ClinicalPolicy> getAllPolicies(UUID hospitalId, Pageable pageable) {
        return policyRepository.findByHospitalIdAndIsActiveTrueOrderByCreatedAtDesc(hospitalId, pageable);
    }

    /**
     * Get a single policy by ID.
     */
    public ClinicalPolicy getPolicy(UUID policyId) {
        return findPolicy(policyId);
    }

    /**
     * Get version history for a policy code within a hospital.
     */
    public List<ClinicalPolicy> getPolicyHistory(String policyCode, UUID hospitalId) {
        return policyRepository.findByHospitalAndPolicyCode(hospitalId, policyCode);
    }

    /**
     * Get audit log for a specific policy.
     */
    public Page<PolicyAuditLog> getAuditLog(UUID policyId, Pageable pageable) {
        // Verify policy exists
        findPolicy(policyId);
        return auditLogRepository.findByPolicyIdAndIsActiveTrueOrderByActionAtDesc(policyId, pageable);
    }

    // ====================================================================
    // PRIVATE HELPERS
    // ====================================================================

    private ClinicalPolicy findPolicy(UUID policyId) {
        return policyRepository.findByIdAndIsActiveTrue(policyId)
                .orElseThrow(() -> new ResourceNotFoundException("ClinicalPolicy", "id", policyId));
    }

    private void createAuditLog(ClinicalPolicy policy, String action, String actionByName,
                                 String previousContent, String newContent, String reason) {
        PolicyAuditLog auditLog = PolicyAuditLog.builder()
                .policy(policy)
                .action(action)
                .actionAt(Instant.now())
                .actionByName(actionByName != null ? actionByName : "SYSTEM")
                .previousContent(previousContent)
                .newContent(newContent)
                .reason(reason)
                .build();

        auditLogRepository.save(auditLog);
    }
}
