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
import com.smartTriage.smartTriage_server.module.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing clinical governance policies — creation, approval workflow,
 * activation, suspension, archival, and version tracking with full audit trail.
 *
 * <p>The audit actor is always resolved server-side from the authenticated principal
 * (never trusted from the request body), so every {@code policy_audit_logs} row names
 * the person who actually performed the action.
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
     * Create a new draft policy. The creator is stamped from the authenticated
     * principal (the request body's createdByName is ignored — server is the
     * source of truth). Hospital scoping is enforced at the controller.
     */
    @Transactional
    public ClinicalPolicy createPolicy(CreatePolicyRequest request) {
        log.info("Creating new {} policy: {}", request.getPolicyType(), request.getPolicyName());

        Hospital hospital = null;
        if (request.getHospitalId() != null) {
            hospital = hospitalRepository.findByIdAndIsActiveTrue(request.getHospitalId())
                    .orElseThrow(() -> new ResourceNotFoundException("Hospital", "id", request.getHospitalId()));
        }

        String actor = currentActorName();

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
                .createdByName(actor)
                .changeReason(request.getChangeReason())
                .notes(request.getNotes())
                .build();

        policy = policyRepository.save(policy);

        createAuditLog(policy, "CREATED", actor, null, policy.getPolicyContent(), request.getChangeReason());

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

        if (request.getPolicyType() != null) policy.setPolicyType(request.getPolicyType());
        if (request.getPolicyName() != null) policy.setPolicyName(request.getPolicyName());
        if (request.getPolicyCode() != null) policy.setPolicyCode(request.getPolicyCode());
        if (request.getDescription() != null) policy.setDescription(request.getDescription());
        if (request.getPolicyContent() != null) policy.setPolicyContent(request.getPolicyContent());
        if (request.getEffectiveFrom() != null) policy.setEffectiveFrom(request.getEffectiveFrom());
        if (request.getEffectiveTo() != null) policy.setEffectiveTo(request.getEffectiveTo());
        if (request.getPolicyVersion() != null) policy.setPolicyVersion(request.getPolicyVersion());
        if (request.getChangeReason() != null) policy.setChangeReason(request.getChangeReason());
        if (request.getNotes() != null) policy.setNotes(request.getNotes());

        policy = policyRepository.save(policy);

        createAuditLog(policy, "UPDATED", currentActorName(),
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

        createAuditLog(policy, "SUBMITTED_FOR_APPROVAL", currentActorName(),
                null, null, "Submitted for approval");

        log.info("Policy {} submitted for approval", policyId);
        return policy;
    }

    /**
     * Approve a pending policy. The approver name defaults to the authenticated caller.
     */
    @Transactional
    public ClinicalPolicy approvePolicy(UUID policyId, String approverName, String notes) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() != PolicyStatus.PENDING_APPROVAL) {
            throw new IllegalStateException(
                    "Only PENDING_APPROVAL policies can be approved. Current status: " + policy.getStatus());
        }

        String actor = currentActorName();
        String approver = (approverName != null && !approverName.isBlank()) ? approverName : actor;

        policy.setStatus(PolicyStatus.APPROVED);
        policy.setApprovedByName(approver);
        policy.setApprovedAt(Instant.now());
        policy.setApprovalNotes(notes);

        policy = policyRepository.save(policy);

        createAuditLog(policy, "APPROVED", actor, null, null, notes);

        log.info("Policy {} approved by {}", policyId, approver);
        return policy;
    }

    /**
     * One-step approve + activate. Policy administration is owned by the
     * SUPER_ADMIN / HOSPITAL_ADMIN who answers to no higher reviewer inside
     * the system, so forcing them through submit → approve → activate adds
     * ceremony without adding control. This compresses the lifecycle for a
     * DRAFT (or already-submitted PENDING_APPROVAL) policy while keeping the
     * full audit trail: an APPROVED audit row is written, then the standard
     * activation (including superseding any previous ACTIVE version) runs.
     */
    @Transactional
    public ClinicalPolicy approveAndActivatePolicy(UUID policyId, String notes) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() != PolicyStatus.DRAFT
                && policy.getStatus() != PolicyStatus.PENDING_APPROVAL) {
            throw new IllegalStateException(
                    "Only DRAFT or PENDING_APPROVAL policies can be approved and activated. Current status: "
                            + policy.getStatus());
        }

        String actor = currentActorName();
        policy.setStatus(PolicyStatus.APPROVED);
        policy.setApprovedByName(actor);
        policy.setApprovedAt(Instant.now());
        policy.setApprovalNotes(notes);
        policy = policyRepository.save(policy);

        createAuditLog(policy, "APPROVED", actor, null, null,
                (notes != null && !notes.isBlank()) ? notes : "Administrator one-step approval");

        return activatePolicy(policy.getId());
    }

    /**
     * Activate an approved policy. Any previous ACTIVE version of the same type+code
     * (for the same hospital, or system-wide when the policy is system-wide) is archived
     * and linked as the previous version.
     */
    @Transactional
    public ClinicalPolicy activatePolicy(UUID policyId) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() != PolicyStatus.APPROVED) {
            throw new IllegalStateException(
                    "Only APPROVED policies can be activated. Current status: " + policy.getStatus());
        }

        String actor = currentActorName();

        if (policy.getPolicyCode() != null) {
            Optional<ClinicalPolicy> currentActive = (policy.getHospital() != null)
                    ? policyRepository.findActiveByHospitalAndCodeAndType(
                            policy.getHospital().getId(), policy.getPolicyCode(), policy.getPolicyType())
                    : policyRepository.findSystemWideActiveByCodeAndType(
                            policy.getPolicyCode(), policy.getPolicyType());

            if (currentActive.isPresent() && !currentActive.get().getId().equals(policy.getId())) {
                ClinicalPolicy previous = currentActive.get();
                previous.setStatus(PolicyStatus.ARCHIVED);
                policyRepository.save(previous);

                createAuditLog(previous, "ARCHIVED", actor,
                        null, null, "Superseded by policy version " + policy.getPolicyVersion());

                policy.setPreviousVersion(previous);
                log.info("Previous active policy {} archived, superseded by {}", previous.getId(), policyId);
            }
        }

        policy.setStatus(PolicyStatus.ACTIVE);
        policy = policyRepository.save(policy);

        createAuditLog(policy, "ACTIVATED", actor, null, null, "Policy activated");

        log.info("Policy {} activated", policyId);
        return policy;
    }

    /**
     * Suspend an active policy (temporary hold; can be reactivated).
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

        createAuditLog(policy, "SUSPENDED", currentActorName(), null, null, reason);

        log.info("Policy {} suspended: {}", policyId, reason);
        return policy;
    }

    /**
     * Reactivate a suspended policy back to ACTIVE (lifts a temporary hold).
     */
    @Transactional
    public ClinicalPolicy reactivatePolicy(UUID policyId) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() != PolicyStatus.SUSPENDED) {
            throw new IllegalStateException(
                    "Only SUSPENDED policies can be reactivated. Current status: " + policy.getStatus());
        }

        policy.setStatus(PolicyStatus.ACTIVE);
        policy = policyRepository.save(policy);

        createAuditLog(policy, "REACTIVATED", currentActorName(), null, null, "Suspension lifted");

        log.info("Policy {} reactivated", policyId);
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

        createAuditLog(policy, "ARCHIVED", currentActorName(), null, null, "Policy archived");

        log.info("Policy {} archived", policyId);
        return policy;
    }

    /**
     * Restore an archived policy back to DRAFT. Not straight to ACTIVE:
     * a restored policy must re-pass submit → approve → activate so nothing
     * re-enters clinical use without review.
     */
    @Transactional
    public ClinicalPolicy unarchivePolicy(UUID policyId) {
        ClinicalPolicy policy = findPolicy(policyId);

        if (policy.getStatus() != PolicyStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Only ARCHIVED policies can be restored. Current status: " + policy.getStatus());
        }

        policy.setStatus(PolicyStatus.DRAFT);
        policy = policyRepository.save(policy);

        createAuditLog(policy, "UNARCHIVED", currentActorName(), null, null,
                "Policy restored from archive to draft for re-review");

        log.info("Policy {} unarchived (restored to DRAFT)", policyId);
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

    /** All ACTIVE policies for a hospital (+ system-wide), across every type — single query. */
    public List<ClinicalPolicy> getAllActive(UUID hospitalId) {
        return policyRepository.findAllActiveForHospital(hospitalId);
    }

    /**
     * Get policies for a hospital (and system-wide defaults) with pagination and an
     * optional status filter.
     */
    public Page<ClinicalPolicy> getAllPolicies(UUID hospitalId, PolicyStatus status, Pageable pageable) {
        return policyRepository.findForHospital(hospitalId, status, pageable);
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

    /** Display name of the authenticated caller, for audit attribution; "SYSTEM" if unresolved. */
    private String currentActorName() {
        try {
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.getPrincipal() instanceof User u) {
                String name = (u.getFirstName() + " " + u.getLastName()).trim();
                return name.isBlank() ? u.getEmail() : name;
            }
        } catch (Exception ignored) {
            // fall through to SYSTEM
        }
        return "SYSTEM";
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
